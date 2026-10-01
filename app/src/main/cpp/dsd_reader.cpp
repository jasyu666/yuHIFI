#include "dsd_reader.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "HiFiDsd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace hifi {

namespace {

uint8_t rev8(uint8_t b) {
    b = static_cast<uint8_t>((b >> 4) | (b << 4));
    b = static_cast<uint8_t>(((b & 0xCC) >> 2) | ((b & 0x33) << 2));
    b = static_cast<uint8_t>(((b & 0xAA) >> 1) | ((b & 0x55) << 1));
    return b;
}

/* 8 位反转查表。函数内静态，C++11 起初始化是线程安全的 */
const std::array<uint8_t, 256>& revTable() {
    static const std::array<uint8_t, 256> t = [] {
        std::array<uint8_t, 256> a{};
        for (int i = 0; i < 256; ++i) a[i] = rev8(static_cast<uint8_t>(i));
        return a;
    }();
    return t;
}

uint32_t rd32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

uint64_t rd64(const uint8_t* p) {
    return static_cast<uint64_t>(rd32(p)) | (static_cast<uint64_t>(rd32(p + 4)) << 32);
}

/* pread 全部字节；返回实际读到的字节数 */
ssize_t preadAll(int fd, void* buf, size_t n, uint64_t off) {
    size_t done = 0;
    auto* p = static_cast<uint8_t*>(buf);
    while (done < n) {
        const ssize_t r = ::pread(fd, p + done, n - done, static_cast<off_t>(off + done));
        if (r <= 0) break;
        done += static_cast<size_t>(r);
    }
    return static_cast<ssize_t>(done);
}

}  // namespace

bool DsdReader::looksLikeDsd(const uint8_t* head, int len) {
    if (len < 4) return false;
    return std::memcmp(head, "DSD ", 4) == 0 || std::memcmp(head, "FRM8", 4) == 0;
}

DsdReader::~DsdReader() {
    // fd 的所有权归调用方（AudioEngine），这里**不关**
}

std::unique_ptr<DsdReader> DsdReader::open(int fd, std::string* err) {
    uint8_t head[128] = {0};
    const ssize_t n = preadAll(fd, head, sizeof(head), 0);
    if (n < 4) {
        if (err) *err = "读不到文件头";
        return nullptr;
    }

    std::unique_ptr<DsdReader> r(new DsdReader());
    r->fd_ = fd;

    if (std::memcmp(head, "DSD ", 4) == 0) {
        if (!r->openDsf(fd, err)) return nullptr;
        return r;
    }
    if (std::memcmp(head, "FRM8", 4) == 0) {
        if (!r->openDff(fd, err)) return nullptr;
        return r;
    }
    if (err) *err = "不是 DSD 文件（既不是 DSF 也不是 DFF）";
    return nullptr;
}

/*
 * DSF 头部布局（已用真实文件逐字节核对过）：
 *
 *   0x00 "DSD "          4
 *   0x04 头块长度         8  = 28
 *   0x0C 文件总长         8
 *   0x14 元数据指针       8
 *   0x1C "fmt "          4
 *   0x20 fmt 块长度       8  = 52
 *   0x28 格式版本         4
 *   0x2C 格式 ID          4
 *   0x30 声道类型         4
 *   0x34 声道数           4
 *   0x38 ★ DSD 位率       4   ← 11289600 = DSD256
 *   0x3C 每样本位数       4   = 1
 *   0x40 样本计数         8   ← 注意：是**所有声道合计的 bit 数**
 *   0x48 ★ 块大小         4   = 4096（每声道每块的字节数）
 *   0x4C 保留             4
 *   0x50 "data"          4
 *   0x54 data 块长度      8   ← **含 12 字节头**
 *   0x5C 数据从这里开始
 *
 *   数据区是**块交织**的：ch0 的第 0 块、ch1 的第 0 块、ch0 的第 1 块……
 */
bool DsdReader::openDsf(int fd, std::string* err) {
    uint8_t h[128] = {0};
    if (preadAll(fd, h, sizeof(h), 0) < 96) {
        if (err) *err = "DSF 头部不完整";
        return false;
    }
    if (std::memcmp(h + 0x1C, "fmt ", 4) != 0) {
        if (err) *err = "DSF 缺少 fmt 块";
        return false;
    }
    if (std::memcmp(h + 0x50, "data", 4) != 0) {
        if (err) *err = "DSF 缺少 data 块";
        return false;
    }

    const uint32_t chans = rd32(h + 0x34);
    const uint32_t bitRate = rd32(h + 0x38);
    const uint32_t bitOrder = rd32(h + 0x3C);    // 名叫 Bits per Sample，实为位序标志
    const uint32_t blockSize = rd32(h + 0x48);
    const uint64_t dataChunk = rd64(h + 0x54);   // 含 12 字节头

    if (chans < 1 || chans > 8) {
        if (err) *err = "DSF 声道数异常：" + std::to_string(chans);
        return false;
    }
    if (bitRate < 100000 || bitRate > 100000000) {
        if (err) *err = "DSF 采样率异常：" + std::to_string(bitRate);
        return false;
    }
    if (blockSize == 0 || (blockSize % 4) != 0 || blockSize > (1u << 20)) {
        if (err) *err = "DSF 块大小异常：" + std::to_string(blockSize);
        return false;
    }
    if (dataChunk < 12) {
        if (err) *err = "DSF data 块长度异常";
        return false;
    }

    channels_ = static_cast<int>(chans);
    bitRate_ = static_cast<int>(bitRate);
    blockSize_ = blockSize;

    /*
     * ★ 位序。0x3C 那个字段规范名叫 "Bits per Sample"，**实际是位序标志**：
     *   1 = LSB-first（要反转），8 = MSB-first（原样搬）。
     *
     *   其余取值只记不猜 —— 既不是 1 也不是 8 时按 LSB-first 走（绝大多数
     *   文件都是 1），并把原值打进日志，将来遇到怪文件有据可查。
     */
    msbFirst_ = (bitOrder == 8);
    if (bitOrder != 1 && bitOrder != 8) {
        LOGW("DSF 的位序字段是 %u（既不是 1 也不是 8）—— 按 LSB-first 处理", bitOrder);
    }
    dataStart_ = 0x5C;                            // 92
    dataBytes_ = dataChunk - 12;                  // 去掉那 12 字节块头

    const uint64_t groupBytes = static_cast<uint64_t>(blockSize_) * channels_;
    totalBlocks_ = (dataBytes_ + groupBytes - 1) / groupBytes;

    /*
     * 时长按**每声道**的 bit 数算。
     *
     * ★ `sample count` 那个字段（0x40）是**所有声道合计**的 bit 数，
     *   不是每声道的 —— 直接用它会算成两倍时长。
     *   实测：451,608,576 ÷ 2 = 225,804,288 bit/声道 ÷ 11,289,600 = 20.000 秒，
     *   和文档里"DSD256 源转出的 20 秒片段"完全吻合。
     */
    const uint64_t bitsPerCh = (dataBytes_ / static_cast<uint64_t>(channels_)) * 8ULL;
    durationMs_ = static_cast<int64_t>(bitsPerCh * 1000ULL / static_cast<uint64_t>(bitRate_));

    formatName_ = "dsf";
    rawBuf_.resize(static_cast<size_t>(groupBytes));

    LOGI("DSF: %dch 位率 %dHz → 请求 %dHz，块 %u，%llu 字节数据，%lld ms，位序 %s",
         channels_, bitRate_, wordRate(), blockSize_,
         static_cast<unsigned long long>(dataBytes_),
         static_cast<long long>(durationMs_),
         msbFirst_ ? "MSB-first(原样搬)" : "LSB-first(逐字节反转)");
    return true;
}

bool DsdReader::openDff(int fd, std::string* err) {
    (void) fd;
    /*
     * DFF 还没实现。
     *
     * 它和 DSF 的差别：FRM8 容器 + 大端 + **MSB-first 位序**（DSF 是 LSB-first）。
     * 所以位序那一步会不一样，不能照抄。
     *
     * 手上没有 .dff 测试文件 —— 按项目一贯的做法，**没有真实数据就不写**，
     * 免得写出一段"看着对但从没跑过"的代码。
     */
    if (err) {
        *err = "DFF 容器还没实现（手上没有 .dff 测试文件，不写没验过的代码）。"
               "请先用 .dsf，或者提供一个 .dff 样本。";
    }
    return false;
}

int DsdReader::read(uint8_t* dst, int bytes) {
    const int frameBytes = channels_ * 4;      // 一个 DSD_U32_BE 帧 = 2ch × 4B
    const int want = (bytes / frameBytes) * frameBytes;
    if (want <= 0) return 0;

    int done = 0;
    while (done < want) {
        if (outPos_ < outBuf_.size()) {
            const size_t n = std::min<size_t>(outBuf_.size() - outPos_,
                                              static_cast<size_t>(want - done));
            std::memcpy(dst + done, outBuf_.data() + outPos_, n);
            outPos_ += n;
            done += static_cast<int>(n);
            continue;
        }
        if (!loadNextBlock()) break;
    }
    framesOut_ += static_cast<uint64_t>(done / frameBytes);
    return done;
}

bool DsdReader::loadNextBlock() {
    if (eof_ || blockIdx_ >= totalBlocks_) {
        eof_ = true;
        return false;
    }

    const size_t groupBytes = static_cast<size_t>(blockSize_) * channels_;
    const uint64_t off = dataStart_ + blockIdx_ * groupBytes;
    const uint64_t remain = dataBytes_ - blockIdx_ * groupBytes;
    const size_t want = static_cast<size_t>(std::min<uint64_t>(groupBytes, remain));

    const ssize_t got = preadAll(fd_, rawBuf_.data(), want, off);
    if (got <= 0) {
        eof_ = true;
        return false;
    }
    const size_t have = static_cast<size_t>(got);

    // 每声道这一段有多少有效字节，向下对齐到 4 字节（= 一个 32-bit 字）
    size_t perCh = (have / static_cast<size_t>(channels_)) / 4 * 4;
    if (perCh == 0) {
        eof_ = true;
        return false;
    }
    const size_t words = perCh / 4;
    outBuf_.resize(words * static_cast<size_t>(channels_) * 4);

    const auto& rev = revTable();

    /*
     * ★ 从「块交织」搬到「逐字交织」，同时把位序正过来。
     *
     *   源（DSF）：先 ch0 的整块 4096 字节，再 ch1 的整块 4096 字节……
     *   目标：     L0 R0 L1 R1 …（每个字 4 字节大端）
     *
     *   ★ 位序按文件自己声明的来（见 openDsf）：LSB-first 的每字节反转，
     *     MSB-first 的原样搬。DSD_U32_BE 要的是**第一个位在字最高位**，
     *     所以拼的时候一律大端 —— 位序错了出来是噪音。
     */
    const auto fix = [this, &rev](uint8_t b) -> uint32_t {
        return msbFirst_ ? static_cast<uint32_t>(b) : static_cast<uint32_t>(rev[b]);
    };
    for (size_t w = 0; w < words; ++w) {
        for (int c = 0; c < channels_; ++c) {
            const uint8_t* s = rawBuf_.data() + static_cast<size_t>(c) * blockSize_ + w * 4;
            const uint32_t word = (fix(s[0]) << 24) |
                                  (fix(s[1]) << 16) |
                                  (fix(s[2]) << 8) |
                                  fix(s[3]);
            uint8_t* d = outBuf_.data() + (w * static_cast<size_t>(channels_) + c) * 4;
            d[0] = static_cast<uint8_t>(word >> 24);   // 大端
            d[1] = static_cast<uint8_t>(word >> 16);
            d[2] = static_cast<uint8_t>(word >> 8);
            d[3] = static_cast<uint8_t>(word);
        }
    }

    outPos_ = 0;
    ++blockIdx_;
    if (have < want || blockIdx_ >= totalBlocks_) eof_ = true;
    return true;
}

bool DsdReader::seekToMs(int64_t ms) {
    if (ms < 0) ms = 0;
    const uint64_t bitsPerCh = static_cast<uint64_t>(ms) *
                               static_cast<uint64_t>(bitRate_) / 1000ULL;
    const uint64_t bytesPerCh = bitsPerCh / 8ULL;
    uint64_t block = bytesPerCh / blockSize_;
    if (totalBlocks_ > 0 && block >= totalBlocks_) block = totalBlocks_ - 1;

    blockIdx_ = block;
    outBuf_.clear();
    outPos_ = 0;
    eof_ = false;
    /*
     * framesOut_ 不重置 —— 它是"读了多少帧"的累计量，供诊断用。
     * 位置计算走引擎那边的 writtenFrames_，不受影响。
     */
    return true;
}

}  // namespace hifi
