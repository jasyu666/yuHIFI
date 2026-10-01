#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace hifi {

/*
 * DSD 文件读取器 —— 把 .dsf / .dff 里的裸 DSD 位流，转成**USB 线上要的
 * `DSD_U32_BE` 线格式**。
 *
 * ★★ 为什么必须自己写
 *
 *   FFmpeg 那份 Android 构建里 `CONFIG_DSD_*_DECODER = 0`、`CONFIG_DSF_DEMUXER = 0`
 *   （`--disable-everything` + 白名单里没放）。**而且就算打开也没用** ——
 *   ffmpeg 的 DSD 解码器内部是 `dsd2pcm`：48 抽头 FIR 低通 + 8:1 抽取，
 *   交出来的是**有损 PCM**。而 native DSD 要的恰恰是**裸位流**。
 *
 * ★★ 线格式：每个 32-bit 字装 32 个 DSD 位
 *
 *   实测（2026-09-23，诊断页 ⑨ 挂 alt 4 发流读 feedback）已经定案：
 *   请求速率 = **DSD 位率 ÷ 32**
 *
 *     DSD64  2.8224 MHz →  88200     ✓ 实测通过
 *     DSD128 5.6448 MHz → 176400     ✓
 *     DSD256 11.2896 MHz → 352800    ✓
 *     DSD512 22.5792 MHz → 705600    ⚠ USB 层接受，DAC 层未经验证
 *
 *   另一种解释（每字装 1 位，请求位率本身）已被**带宽**否掉：
 *   本端点每 microframe 只有 776 字节（≈6.2 MB/s），那样 DSD256 要 22.6 MB/s。
 *
 * ★★ 位序：由文件头 0x3C 那个字段决定，`DSD_U32_BE` 一律要 MSB-first
 *
 *   `DSD_U32_BE` 要求第一个 DSD 位落在 32-bit 字的**最高位**。
 *
 *   而 DSF 文件自己可能两种都写 —— `fmt` 块 0x3C 那个字段，**规范名叫
 *   "Bits per Sample"，实际含义是位序标志**：
 *
 *     1 → LSB-first（第一个位在字节最低位）→ **每个字节要先按位反转**
 *     8 → MSB-first（第一个位在字节最高位）→ **原样搬**
 *
 *   （依据：DSF 规范 Annotation 4；ReSampler 的 dsf.h 也是这么读的 ——
 *   它按这个字段决定 `1 << (bitOrder == 8 ? 7 - j : j)`。）
 *
 *   ★ 早先这里写死"DSF 一定是 LSB-first"，遇到 0x3C = 8 的文件会把已经
 *     正确的位流反转一遍 —— **出来正好是噪音**，而且从代码上看不出来。
 *
 *   位序错了是噪音不是音乐，这一点很好判断 —— 一耳朵的事。
 *
 * 线程约定：**只在解码线程上用**。这是文件 I/O，绝不能碰实时线程。
 */
class DsdReader {
public:
    /*
     * 按 fd 打开。fd 的所有权**仍归调用方**（和 AudioEngine::open 的约定一致），
     * 内部只用 pread，不动文件偏移。
     *
     * 成功返回非空；失败时把原因写进 err。
     */
    static std::unique_ptr<DsdReader> open(int fd, std::string* err);

    /** 文件头是不是 DSD（"DSD " 或 "FRM8"）。用来在引擎里分流 */
    static bool looksLikeDsd(const uint8_t* head, int len);

    ~DsdReader();

    // ---- 元信息 ----
    int channels() const { return channels_; }

    /** DSD 位率（DSD64 = 2822400、DSD256 = 11289600） */
    int bitRate() const { return bitRate_; }

    /**
     * 要**请求给设备的采样率** = 位率 ÷ 32。
     *
     * ★ 这不是"我随便定的"，是实测出来的：见头文件开头的表。
     */
    int wordRate() const { return bitRate_ / 32; }

    int64_t durationMs() const { return durationMs_; }
    uint64_t dataBytes() const { return dataBytes_; }      // 全部声道的 DSD 数据总字节
    const char* formatName() const { return formatName_; }
    const char* containerName() const { return "DSD"; }

    /**
     * 读一段**已经转成 DSD_U32_BE 线格式**的数据。
     *
     * 输出是声道交织的：L0 R0 L1 R1 …，每个字 4 字节大端。
     *
     * @param bytes 期望的字节数（会被向下取整到 8 的倍数 —— 一个"帧"= 2ch × 4B）
     * @return 实际写入的字节数；0 表示到文件末尾
     */
    int read(uint8_t* dst, int bytes);

    /** 定位到指定毫秒。内部按块对齐，返回是否成功 */
    bool seekToMs(int64_t ms);

    /** 已经读出去的**帧数**（= 32-bit 字对）。用于诊断 */
    uint64_t framesRead() const { return framesOut_; }

private:
    DsdReader() = default;

    bool openDsf(int fd, std::string* err);
    bool openDff(int fd, std::string* err);

    /** 载入下一个块组并转成线格式，追加进 outBuf_ */
    bool loadNextBlock();

    int fd_ = -1;
    const char* formatName_ = "?";

    int channels_ = 2;
    int bitRate_ = 0;
    int64_t durationMs_ = 0;
    uint64_t dataBytes_ = 0;      // 全部声道的 DSD 数据总字节

    /*
     * 源文件的位序是不是 MSB-first（0x3C 字段 == 8）。
     *
     * false（0x3C == 1，绝大多数文件）= LSB-first，每个字节要按位反转。
     */
    bool msbFirst_ = false;

    // ---- DSF 布局 ----
    uint32_t blockSize_ = 0;      // 每声道每块的字节数（规范值 4096）
    uint64_t dataStart_ = 0;      // DSD 数据在文件里的起始偏移
    uint64_t blockIdx_ = 0;       // 下一个要读的"块组"下标
    uint64_t totalBlocks_ = 0;    // 块组总数

    // ---- 读缓冲：装的是一个块组转换后的线格式字节 ----
    std::vector<uint8_t> outBuf_;
    size_t outPos_ = 0;           // 已消费到 outBuf_ 的哪里
    std::vector<uint8_t> rawBuf_; // 原始块组（channels × blockSize）
    uint64_t framesOut_ = 0;

    bool eof_ = false;
};

}  // namespace hifi
