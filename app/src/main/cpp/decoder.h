#pragma once

#include <cstdint>
#include <string>

namespace hifi {

/*
 * 音频解码器抽象。
 *
 * 输出统一为**交错 S32**（每样本 4 字节有符号，左对齐到 32 位容器）：
 *     16bit 源 → 左移 16
 *     24bit 源 → 左移 8
 *     32bit 源 → 原样
 *
 * 这样任何 ≤32bit 的整型源都能无损存放；只有在最终输出到 DAC 时
 * 才按 alt setting 的位深右移回去。只要输出位深与源位深匹配，
 * 来回移位就是精确可逆的，bit-perfect 不丢。
 *
 * 选 S32 而不是 float：整型源走整型通路，避免任何浮点舍入。
 */
class AudioDecoder {
public:
    virtual ~AudioDecoder() = default;

    /* fd 的所有权仍归调用方，解码器只读不关闭它 */
    virtual bool open(int fd, std::string* err) = 0;
    virtual void close() = 0;

    virtual int sampleRate() const = 0;
    virtual int channels() const = 0;
    virtual int bitsPerSample() const = 0;      // 源文件的有效位深
    virtual int64_t durationMs() const = 0;

    /*
     * 读取最多 maxFrames 个样本帧，交错写入 dst。
     * 返回实际帧数；0 = 到达末尾；<0 = 出错。
     */
    virtual int readFrames(int32_t* dst, int maxFrames) = 0;

    /* 定位到指定毫秒。返回是否成功。 */
    virtual bool seekToMs(int64_t ms) = 0;
};

}  // namespace hifi
