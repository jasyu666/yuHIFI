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

/*
 * ★ DFF(IFF/DSDIFF) 是**大端**，DSF 是小端 —— 两套读法不能混用。
 *   混用的后果不是崩溃而是"块长度读出个天文数字"，然后被上限挡掉。
 */
uint32_t rd32be(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

uint64_t rd64be(const uint8_t* p) {
    return (static_cast<uint64_t>(rd32be(p)) << 32) | rd32be(p + 4);
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

/*
 * DFF（IFF / DSDIFF）头部布局 —— 已用一个真实的 DSD64 立体声文件逐字节核对过：
 *
 *   0x00 "FRM8"        4
 *   0x04 块长度        8   ★ 大端 = 文件总长 - 12
 *   0x0C "DSD "        4   form type
 *   ---- 往后是一串**平铺**的块，每块 = id(4) + 长度(8, 大端) + 块体，块体按偶数对齐 ----
 *   "FVER"  块体 4 字节：版本 0x01050000
 *   "PROP"  块体：先 "SND "，再嵌 FS / CHNL / CMPR / (LSCO) 等子块
 *   "DSD "  块体就是声音数据（若叫 "DST " 则是压缩的，本工程不支持）
 *
 * 实测样本：DSD64 立体声，FS=2822400、CHNL=2、CMPR="DSD "/"not compressed"、
 *           DSD 数据 @144 共 118569024 字节 → 168.04 秒。
 *
 * ★★★ 和 DSF 的三处根本差别（依据 DSDIFF 1.5 规范 §2.3；
 *     MPD 的 DsdiffDecoderPlugin 也是照这份规范写的，可交叉印证）：
 *
 *   1. **大端**。DSF 是小端。
 *
 *   2. **逐字节交织** —— 这是最容易搞错的一处。
 *      DSF 是"每声道 4096 字节一整块"地交替；
 *      DFF 的一**帧只有 `声道数` 个字节**（立体声 = 2 字节：L 一字节、R 一字节）。
 *      规范里 `frame_size = channels × 1`，MPD 也是用 `chunk_size / channels` 算帧数。
 *
 *   3. **MSB-first**（第一个位落在字节最高位）。DSF 常见的是 LSB-first、每字节要按位
 *      反转；DFF 是"原样搬"。★ 位序错了出来是噪音不是音乐 —— 一耳朵的事。
 */
bool DsdReader::openDff(int fd, std::string* err) {
    uint8_t head[16] = {0};
    if (preadAll(fd, head, sizeof(head), 0) < 16) {
        if (err) *err = "DFF 头部不完整";
        return false;
    }
    if (std::memcmp(head, "FRM8", 4) != 0) {
        if (err) *err = "不是 DFF 文件（缺少 FRM8）";
        return false;
    }
    if (std::memcmp(head + 12, "DSD ", 4) != 0) {
        if (err) *err = "DFF 的 form type 不是 DSD ";
        return false;
    }

    uint32_t chans = 0, sampleRate = 0;
    bool sawFs = false, sawChnl = false;
    uint64_t dataOff = 0, dataLen = 0;

    /*
     * 平铺扫块。★ 每个块头都**现读**，不能假设它们都挤在开头那 128 字节里 ——
     * 真实文件里 DSD 数据块之前可能夹着 ID3 / DIIN / DIAR 一大堆元数据块。
     * 加一个扫描上限，坏文件别让我们无限 pread 下去。
     */
    constexpr uint64_t kMaxScan = 1ULL << 20;      // 头部最多认 1 MiB
    uint64_t off = 16;
    while (off + 12 <= kMaxScan) {
        uint8_t ch[12];
        if (preadAll(fd, ch, sizeof(ch), off) < 12) break;
        const uint64_t csize = rd64be(ch + 4);
        if (csize > (1ULL << 40)) {
            if (err) *err = "DFF 块长度异常：" + std::to_string(csize);
            return false;
        }

        if (std::memcmp(ch, "PROP", 4) == 0) {
            // 块体头 4 字节是 propType，再往下才是 FS / CHNL / CMPR 这些子块
            uint8_t pt[4] = {0};
            if (preadAll(fd, pt, 4, off + 12) == 4 && std::memcmp(pt, "SND ", 4) == 0) {
                const uint64_t subEnd = off + 12 + csize;
                uint64_t sub = off + 16;
                while (sub + 12 <= subEnd && sub + 12 <= kMaxScan) {
                    uint8_t sh[12];
                    if (preadAll(fd, sh, sizeof(sh), sub) < 12) break;
                    const uint64_t ssize = rd64be(sh + 4);

                    if (std::memcmp(sh, "FS  ", 4) == 0 && ssize >= 4) {
                        uint8_t v[4];
                        if (preadAll(fd, v, 4, sub + 12) == 4) {
                            sampleRate = rd32be(v);
                            sawFs = true;
                        }
                    } else if (std::memcmp(sh, "CHNL", 4) == 0 && ssize >= 2) {
                        uint8_t v[2];
                        if (preadAll(fd, v, 2, sub + 12) == 2) {
                            chans = (static_cast<uint32_t>(v[0]) << 8) | v[1];
                            sawChnl = true;
                        }
                    } else if (std::memcmp(sh, "CMPR", 4) == 0 && ssize >= 4) {
                        uint8_t v[4];
                        if (preadAll(fd, v, 4, sub + 12) == 4 &&
                            std::memcmp(v, "DSD ", 4) != 0) {
                            /*
                             * ★ 只支持未压缩。DST 是一整套无损编解码器（远不止解容器），
                             *   本工程没做。而且必须**把话说清**，不能让用户对着一句
                             *   笼统的"打不开"去猜是文件坏了还是软件不行。
                             */
                            if (err) *err = std::string("这个 DFF 里的声音是 ") +
                                std::string(reinterpret_cast<const char*>(v), 4) +
                                " 压缩的，本应用只支持未压缩的 DSD 位流。"
                                "请改用 .dsf，或导出未压缩的 .dff。";
                            return false;
                        }
                    }

                    // ★ 子块块体同样按偶数对齐
                    const uint64_t next = sub + 12 + ssize + (ssize & 1);
                    if (next <= sub) break;
                    sub = next;
                }
            }
        } else if (std::memcmp(ch, "DSD ", 4) == 0) {
            dataOff = off + 12;
            dataLen = csize;
            break;
        } else if (std::memcmp(ch, "DST ", 4) == 0) {
            if (err) *err = "这个 DFF 是 DST 压缩的，本应用只支持未压缩的 DSD 位流。"
                            "请改用 .dsf，或导出未压缩的 .dff。";
            return false;
        }

        const uint64_t next = off + 12 + csize + (csize & 1);   // ★ 块体按偶数对齐
        if (next <= off) break;
        off = next;
    }

    if (dataLen == 0) {
        if (err) *err = "DFF 里找不到 DSD 声音数据块";
        return false;
    }
    if (!sawFs || !sawChnl) {
        if (err) *err = "DFF 缺少 FS / CHNL 块，无法确定采样率或声道数";
        return false;
    }
    if (chans < 1 || chans > 8) {
        if (err) *err = "DFF 声道数异常：" + std::to_string(chans);
        return false;
    }
    if (sampleRate < 100000 || sampleRate > 100000000) {
        if (err) *err = "DFF 采样率异常：" + std::to_string(sampleRate);
        return false;
    }

    channels_ = static_cast<int>(chans);
    bitRate_ = static_cast<int>(sampleRate);
    msbFirst_ = true;                 // ★ DFF 一律 MSB-first，见上面第 3 条
    dff_ = true;
    dataStart_ = dataOff;
    dataBytes_ = dataLen;

    /*
     * ★ "组"取**每声道 4096 字节** —— 和 DSF 的块大小**恰好一样**。
     *   这样 totalBlocks_ 和 seekToMs() 那套算术一个字都不用改，
     *   只有 loadNextBlock 里解交织那几行需要分叉。改动面小，出错面也小。
     */
    blockSize_ = 4096;
    const uint64_t groupBytes = static_cast<uint64_t>(blockSize_) * channels_;
    totalBlocks_ = (dataBytes_ + groupBytes - 1) / groupBytes;

    // 时长按**每声道**的 bit 数算（dataBytes_ 是全部声道的合计）
    const uint64_t bitsPerCh = (dataBytes_ / channels_) * 8ULL;
    durationMs_ = static_cast<int64_t>(bitsPerCh * 1000ULL / static_cast<uint64_t>(bitRate_));

    formatName_ = "dff";
    rawBuf_.resize(static_cast<size_t>(groupBytes));

    LOGI("DFF: %dch 位率 %dHz → 请求 %dHz，数据 @%llu 共 %llu 字节，%lld ms，"
         "逐字节交织 + MSB-first（原样搬）",
         channels_, bitRate_, wordRate(),
         static_cast<unsigned long long>(dataStart_),
         static_cast<unsigned long long>(dataBytes_),
         static_cast<long long>(durationMs_));
    return true;
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
    if (dff_) {
        /*
         * ★★ DFF：**逐字节交织**，源指针每挪一个字节就换一次声道。
         *
         *   第 w 个字的第 k 个字节，在文件里的位置是
         *       (w × 4 + k) × 声道数 + c
         *
         *   ★ 和下面 DSF 那条 `c × blockSize_ + w × 4` 是**完全不同的取法** ——
         *     两边的"帧"根本不是一个概念（DFF 一帧 = 声道数 × 1 字节）。
         *   ★ DFF 恒为 MSB-first，fix() 到这里是恒等，但照样走一遍：
         *     位序规则只此一处，将来谁也不用去记"哪条路要反转"。
         */
        const size_t ch = static_cast<size_t>(channels_);
        for (size_t w = 0; w < words; ++w) {
            const uint8_t* base = rawBuf_.data() + w * 4 * ch;
            for (size_t c = 0; c < ch; ++c) {
                const uint32_t word = (fix(base[c]) << 24) |
                                      (fix(base[ch + c]) << 16) |
                                      (fix(base[2 * ch + c]) << 8) |
                                      fix(base[3 * ch + c]);
                uint8_t* d = outBuf_.data() + (w * ch + c) * 4;
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
