#pragma once

#include "pcm_source.h"

namespace hifi {

/*
 * 1kHz 正弦测试音源。
 *
 * 这段逻辑原本长在 IsoPlayer 里（P0 阶段唯一的"音源"）。抽出来是为了让
 * IsoPlayer 只依赖 PcmSource 接口，从而能接真实解码器。
 * 保留它的意义：P0 那套诊断流程（②③④⑤）继续可用，调试时很好使。
 */
class ToneSource : public PcmSource {
public:
    struct Config {
        int sampleRate = 48000;
        int channels = 2;
        int subframeSize = 2;      // 每样本每声道的字节数
        int bitResolution = 16;    // 有效位
        int toneFreqHz = 1000;
    };

    explicit ToneSource(const Config& cfg);

    /* 重置相位与跨包残留，用于重新开始 */
    void reset();

    int fill(uint8_t* dst, int bytes) override;

private:
    Config cfg_{};
    int frameBytes_ = 0;
    double phase_ = 0.0;
    double phaseIncrement_ = 0.0;

    // 跨包续传：一个样本帧可能正好被 iso 包边界切开，
    // 未发完的尾部字节留到下一包开头续上，保证字节流不断裂。
    unsigned char partialFrame_[64] = {0};
    int partialLen_ = 0;
};

}  // namespace hifi
