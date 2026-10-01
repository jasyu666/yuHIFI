#include "tone_source.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace hifi {

namespace {
// 不用 <cmath> 的 M_PI：-std=c++17 会定义 __STRICT_ANSI__，
// bionic 在该宏下不暴露 M_PI
constexpr double kPi = 3.14159265358979323846;
}  // namespace

ToneSource::ToneSource(const Config& cfg) : cfg_(cfg) {
    frameBytes_ = cfg_.channels * cfg_.subframeSize;
    if (frameBytes_ <= 0) frameBytes_ = 1;
    if (cfg_.bitResolution <= 0) cfg_.bitResolution = 16;
    if (cfg_.sampleRate <= 0) cfg_.sampleRate = 48000;
    reset();
}

void ToneSource::reset() {
    phase_ = 0.0;
    phaseIncrement_ = 2.0 * kPi * static_cast<double>(cfg_.toneFreqHz) /
                      static_cast<double>(cfg_.sampleRate);
    partialLen_ = 0;
}

/*
 * 按 subframeSize 小端写入。当 bitResolution < subframeSize*8 时（例如
 * 4 字节容器装 24bit），按 ALSA/Linux 的 S24_LE 约定采用低位对齐 + 符号
 * 扩展，这也是 Android/Linux USB Audio HAL 的通行做法。
 */
int ToneSource::fill(uint8_t* dst, int bytes) {
    if (bytes <= 0) return 0;

    uint8_t* p = dst;
    int remaining = bytes;

    // 先补完上一包被切断的那个样本帧
    if (partialLen_ > 0) {
        const int take = std::min(frameBytes_ - partialLen_, remaining);
        std::memcpy(p, partialFrame_ + partialLen_, static_cast<size_t>(take));
        partialLen_ += take;
        p += take;
        remaining -= take;
        if (partialLen_ >= frameBytes_) partialLen_ = 0;
    }

    const double fullScale =
        static_cast<double>((1LL << (cfg_.bitResolution - 1)) - 1);

    while (remaining > 0) {
        const double s = std::sin(phase_);
        phase_ += phaseIncrement_;
        if (phase_ >= 2.0 * kPi) phase_ -= 2.0 * kPi;

        const int32_t v = static_cast<int32_t>(std::lround(s * fullScale));

        uint8_t* q = partialFrame_;
        for (int c = 0; c < cfg_.channels; ++c) {
            // 小端逐字节写出；v 为负时高位自然补 0xFF，即符号扩展
            for (int b = 0; b < cfg_.subframeSize; ++b) {
                q[b] = static_cast<uint8_t>((v >> (8 * b)) & 0xFF);
            }
            q += cfg_.subframeSize;
        }

        const int take = std::min(frameBytes_, remaining);
        std::memcpy(p, partialFrame_, static_cast<size_t>(take));
        partialLen_ = (take < frameBytes_) ? take : 0;
        p += take;
        remaining -= take;
    }

    return bytes;   // 测试音永远供得上，不会 underrun
}

}  // namespace hifi
