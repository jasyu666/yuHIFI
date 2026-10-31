#include "loudness_meter.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace hifi {

/*
 * K 加权系数（BS.1770 附录 1 的模拟原型）。
 *
 * 标准正文只给 48kHz 的数字；这里按采样率现算，测 44.1k/96k/192k
 * 时不需要先重采样到 48k —— 测量不该反过来动源数据。
 *
 * 常数与 ffmpeg f_ebur128.c 一致，方便和 `ffmpeg -filter_complex ebur128` 对拍。
 */
void LoudnessMeter::initFilters() {
    const double rate = static_cast<double>(sampleRate_);

    // 第一级：高频搁架（f0≈1682Hz, +4dB, Q≈0.707）
    for (auto& f : shelf_) {
        const double f0 = 1681.974450955533;
        const double G = 3.999843853973347;
        const double Q = 0.7071752369554196;

        const double K = std::tan(M_PI * f0 / rate);
        const double Vh = std::pow(10.0, G / 20.0);
        const double Vb = std::pow(Vh, 0.4996667741545416);
        const double a0 = 1.0 + K / Q + K * K;

        f.b0 = (Vh + Vb * K / Q + K * K) / a0;
        f.b1 = 2.0 * (K * K - Vh) / a0;
        f.b2 = (Vh - Vb * K / Q + K * K) / a0;
        f.a1 = 2.0 * (K * K - 1.0) / a0;
        f.a2 = (1.0 - K / Q + K * K) / a0;
        f.reset();
    }

    // 第二级：高通（f0≈38.1Hz, Q≈0.5）
    for (auto& f : hpf_) {
        const double f0 = 38.13547087602444;
        const double Q = 0.5003270373238773;

        const double K = std::tan(M_PI * f0 / rate);
        const double a0 = 1.0 + K / Q + K * K;

        f.b0 = 1.0;
        f.b1 = -2.0;
        f.b2 = 1.0;
        f.a1 = 2.0 * (K * K - 1.0) / a0;
        f.a2 = (1.0 - K / Q + K * K) / a0;
        f.reset();
    }
}

void LoudnessMeter::TruePeak::init() {
    const int M = TAPS - 1;
    const double fc = 0.5 / PHASES;
    double h[TAPS];
    double sum = 0.0;

    for (int n = 0; n < TAPS; ++n) {
        const double x = n - M / 2.0;
        const double sinc = (std::fabs(x) < 1e-9)
                ? 1.0
                : std::sin(M_PI * fc * x) / (M_PI * fc * x);
        const double w = 0.42 - 0.5 * std::cos(2.0 * M_PI * n / M)
                         + 0.08 * std::cos(4.0 * M_PI * n / M);
        h[n] = sinc * w;
        sum += h[n];
    }
    for (int n = 0; n < TAPS; ++n) h[n] /= sum;

    // 多相分解：相 p 的第 k 个系数 = h[p + k*PHASES]
    for (int p = 0; p < PHASES; ++p) {
        for (int k = 0; k < TAPS_PER_PHASE; ++k) {
            coeff[p][k] = h[p + k * PHASES] * PHASES;
        }
    }
    reset();
}

void LoudnessMeter::TruePeak::reset() {
    std::memset(hist, 0, sizeof(hist));
    pos = 0;
    peak = 0.0;
}

void LoudnessMeter::TruePeak::feed(double x) {
    hist[pos] = x;
    for (int p = 0; p < PHASES; ++p) {
        double acc = 0.0;
        int idx = pos;
        for (int k = 0; k < TAPS_PER_PHASE; ++k) {
            acc += coeff[p][k] * hist[idx];
            if (--idx < 0) idx = TAPS - 1;
        }
        const double a = std::fabs(acc);
        if (a > peak) peak = a;
    }
    if (++pos >= TAPS) pos = 0;
}

void LoudnessMeter::init(int sampleRate, int channels, bool measureTruePeak) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
    channels_ = channels > 0 ? channels : 2;
    measureTruePeak_ = measureTruePeak;
    hopSamples_ = std::max(1, sampleRate_ / 10);

    shelf_.assign(static_cast<size_t>(channels_), Biquad{});
    hpf_.assign(static_cast<size_t>(channels_), Biquad{});
    partial_.assign(static_cast<size_t>(channels_),
                    std::vector<double>(HOP_PARTS, 0.0));
    cur_.assign(static_cast<size_t>(channels_), 0.0);
    truePeak_.assign(static_cast<size_t>(channels_), TruePeak{});

    initFilters();
    if (measureTruePeak_) {
        for (auto& tp : truePeak_) tp.init();
    }

    blocks_.clear();
    resetHopState();
}

void LoudnessMeter::setFormat(int sampleRate, int channels) {
    const int fs = sampleRate > 0 ? sampleRate : 48000;
    const int ch = channels > 0 ? channels : 2;
    if (fs == sampleRate_ && ch == channels_) return;

    /*
     * 保留 blocks_，只重建滤波器和 100ms 累加器。
     * 当前未满 100ms 的残块直接丢弃 —— 专辑边界上的损失 < 100ms，
     * 比把两种采样率的滤波器状态混在一起安全。
     */
    sampleRate_ = fs;
    channels_ = ch;
    hopSamples_ = std::max(1, sampleRate_ / 10);

    shelf_.assign(static_cast<size_t>(channels_), Biquad{});
    hpf_.assign(static_cast<size_t>(channels_), Biquad{});
    partial_.assign(static_cast<size_t>(channels_),
                    std::vector<double>(HOP_PARTS, 0.0));
    cur_.assign(static_cast<size_t>(channels_), 0.0);
    truePeak_.assign(static_cast<size_t>(channels_), TruePeak{});

    initFilters();
    if (measureTruePeak_) {
        for (auto& tp : truePeak_) tp.init();
    }
    resetHopState();
}

void LoudnessMeter::reset() {
    for (auto& f : shelf_) f.reset();
    for (auto& f : hpf_) f.reset();
    for (auto& tp : truePeak_) tp.reset();
    blocks_.clear();
    resetHopState();
}

void LoudnessMeter::resetHopState() {
    for (auto& p : partial_) std::fill(p.begin(), p.end(), 0.0);
    for (auto& c : cur_) c = 0.0;
    slot_ = 0;
    filled_ = 0;
    sinceHop_ = 0;
}

void LoudnessMeter::feedInterleavedFloat(const float* data, int frames) {
    if (data == nullptr || frames <= 0 || channels_ <= 0) return;

    for (int i = 0; i < frames; ++i) {
        for (int c = 0; c < channels_; ++c) {
            const double x = data[static_cast<size_t>(i) * channels_ + c];
            const double y = hpf_[c].process(shelf_[c].process(x));
            cur_[c] += y * y;
            if (measureTruePeak_) truePeak_[c].feed(x);
        }
        if (++sinceHop_ >= hopSamples_) {
            closeHop();
            sinceHop_ = 0;
        }
    }
}

void LoudnessMeter::finish() {
    if (sinceHop_ > 0) closeHop();
    // 不足 4 个 100ms 分块（400ms）的窗口不算一个块，与标准一致
}

void LoudnessMeter::closeHop() {
    for (int c = 0; c < channels_; ++c) {
        partial_[c][slot_] = cur_[c];
        cur_[c] = 0.0;
    }

    // 所有声道同步推进 —— 用**一个**环形写指针，不是每声道一个
    if (++slot_ >= HOP_PARTS) slot_ = 0;
    if (filled_ < HOP_PARTS) ++filled_;

    if (filled_ < HOP_PARTS) return;

    double z = 0.0;
    for (int c = 0; c < channels_; ++c) {
        double win = 0.0;
        for (int k = 0; k < HOP_PARTS; ++k) win += partial_[c][k];
        const double meanSquare =
                win / static_cast<double>(hopSamples_ * HOP_PARTS);
        // 声道权重：L/R = 1.0；其余（环绕）1.41。本库以立体声为主。
        const double gi = (c < 2) ? 1.0 : 1.41;
        z += gi * meanSquare;
    }
    blocks_.push_back(z);
}

double LoudnessMeter::blockLufs(double z) {
    if (z <= 0.0) return -HUGE_VAL;
    return -0.691 + 10.0 * std::log10(z);
}

LoudnessResult LoudnessMeter::result() const {
    LoudnessResult out;

    if (blocks_.empty()) return out;

    // ① 绝对门限
    double sumAbs = 0.0;
    int nAbs = 0;
    for (double z : blocks_) {
        if (blockLufs(z) > ABS_GATE_LUFS) {
            sumAbs += z;
            ++nAbs;
        }
    }
    if (nAbs == 0) return out;

    // ② 相对门限（基于过绝对门限者的均值）
    const double relThreshold = blockLufs(sumAbs / nAbs) - REL_GATE_LU;

    // ③ 两级都过
    double sum = 0.0;
    int n = 0;
    for (double z : blocks_) {
        const double l = blockLufs(z);
        if (l > ABS_GATE_LUFS && l > relThreshold) {
            sum += z;
            ++n;
        }
    }
    if (n == 0) return out;

    out.lufs = blockLufs(sum / n);
    out.valid = true;

    if (measureTruePeak_) {
        double p = 0.0;
        for (const auto& tp : truePeak_) p = std::max(p, tp.peak);
        out.truePeakDb = (p > 0.0) ? 20.0 * std::log10(p) : -HUGE_VAL;
    } else {
        out.truePeakDb = 0.0;
    }
    return out;
}

}  // namespace hifi
