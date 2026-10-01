#pragma once

#include <cstdint>
#include <string>

struct SwrContext;

namespace hifi {

/*
 * 采样率转换器（交错 S32 → 交错 S32，只改速率不改格式）。
 *
 * 当前基于 FFmpeg 的 swresample。质量说明：
 *   swresample 内置的是加窗 sinc 重采样器，质量中等偏上，但不是最好的。
 *   44.1k 家族在这台设备上**必须**重采样，而它占了真实曲库的很大一部分，
 *   所以重采样质量直接决定多数音乐的听感。
 *
 *   → 后续应改用 libsoxr（VHQ 档，伪影可压到 -140dB 以下）。
 *     做法：为 Android 交叉编译 libsoxr，再以 --enable-libsoxr 重编 FFmpeg，
 *     swresample 会自动把 soxr 作为后端，本文件不用改。
 *     这是 P1 之后优先级最高的一项音质改进。
 */
class Resampler {
public:
    Resampler() = default;
    ~Resampler();

    Resampler(const Resampler&) = delete;
    Resampler& operator=(const Resampler&) = delete;

    bool init(int inRate, int outRate, int channels, std::string* err);
    void close();

    bool active() const { return swr_ != nullptr; }

    /*
     * 处理 inFrames 帧（交错 S32），输出到 out（容量 maxOutFrames 帧）。
     * 返回实际输出帧数。内部有少量缓冲，所以输出帧数可能少于输入。
     */
    int process(const int32_t* in, int inFrames, int32_t* out, int maxOutFrames);

    /* 输入结束后调用，取出重采样器内部残留的样本 */
    int flush(int32_t* out, int maxOutFrames);

    /* 输出/输入速率比，用于估算输出缓冲需求 */
    double ratio() const {
        return inRate_ > 0 ? static_cast<double>(outRate_) / inRate_ : 1.0;
    }

    int inRate() const { return inRate_; }
    int outRate() const { return outRate_; }

private:
    SwrContext* swr_ = nullptr;
    int inRate_ = 0;
    int outRate_ = 0;
    int channels_ = 0;
};

}  // namespace hifi
