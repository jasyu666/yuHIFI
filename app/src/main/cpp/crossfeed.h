#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

namespace hifi {

/*
 * 交叉馈送（crossfeed）—— 把一部分对侧声道混进本侧，减轻"头中效应"。
 *
 * 原理：戴耳机时左耳只听到左声道、右耳只听到右声道，而现实中右耳
 * 也会听到左耳那侧的声源 —— 只是**晚一点**（绕过头部多走一段路）
 * 而且**高频被头挡住**（头影效应）。缺了这两样，声像就全挤在脑袋里。
 *
 * 所以每个声道做两件事再混过去：
 *     · 延迟 D        模拟两耳之间的声程差
 *     · 一阶低通 fc   模拟头影效应（高频被头挡住）
 *
 *     outL = L + g·LPF(delay(R))
 *     outR = R + g·LPF(delay(L))
 *
 * ★★ 这**必然不是 bit-perfect** —— 它就是在改样本。
 *    默认关闭，界面上必须说清楚（见设置页那张卡片）。
 *
 * ★★ 只处理立体声。单声道没有"对侧"可混，直接跳过。
 *    调用方（AudioEngine）用 active() 判断。
 *
 * ★★ DSD 走的是原生位流，根本不经过 PCM 域，所以这个模块对 DSD 无能为力 ——
 *    这是架构决定的，不是漏做。DSD 那条线程不会调到这里。
 *
 * ── 精度取舍 ──
 * 内部用 float 算。内部样本格式是"左对齐到 32 位"，即满量程 ±2³¹；
 * float 有 24 位尾数，对 16bit / 24bit 源完全够（24bit 刚好无损）。
 * 32bit 源会有约 -144dB 的舍入 —— 已经远在 24bit 动态范围之外，听不出来，
 * 而且开了交叉馈送本来就不是 bit-perfect，前提已经成立。
 * 要换成 double 也行，代价是每个样本多几次乘加；目前没必要。
 */
class Crossfeed {
public:
    enum Level { WEAK = 0, MEDIUM = 1, STRONG = 2 };

    struct Param {
        float delayMs;    // 两耳声程差
        float cutoffHz;   // 头影效应的一阶低通
        float mixDb;      // 交叉量（负数）
    };

    /*
     * 三档参数。延迟上限取 0.5ms —— 头宽 18cm ÷ 声速 343m/s ≈ 0.52ms，
     * 再长就不像"声音绕过脑袋"而像"回声"了。
     * 低通 700Hz 和交叉量三档是 bs2b 的经典取值范围。
     */
    static const Param& param(Level lv) {
        static const Param kParams[3] = {
            {0.20f, 700.0f, -6.0f},    // 弱
            {0.35f, 700.0f, -4.5f},    // 中（默认）
            {0.50f, 700.0f, -3.0f},    // 强
        };
        return kParams[(lv < 0 || lv > 2) ? 1 : lv];
    }

    static const char* levelName(Level lv) {
        switch (lv) {
            case WEAK:   return "弱";
            case STRONG: return "强";
            default:     return "中";
        }
    }

    /**
     * 按当前输出参数准备。channels != 2 时直接置为不可用。
     *
     * ★ 延迟线**按最大档预分配** —— 切档只改读取距离，不重新分配。
     *   实时线程里绝不能分配内存。
     */
    bool configure(int sampleRate, int channels) {
        close();
        if (sampleRate <= 0 || channels != 2) return false;

        rate_ = sampleRate;
        channels_ = channels;

        // 最大档 0.5ms，再留两个样本的余量给取整
        maxDelay_ = static_cast<size_t>(sampleRate * 0.5f / 1000.0f) + 2;
        if (maxDelay_ < 4) maxDelay_ = 4;

        delayL_.assign(maxDelay_, 0.0f);
        delayR_.assign(maxDelay_, 0.0f);
        pos_ = 0;
        lpL_ = lpR_ = 0.0f;
        prepared_ = true;
        return true;
    }

    void close() {
        prepared_ = false;
        rate_ = 0;
        channels_ = 0;
        delayL_.clear();
        delayR_.clear();
        // ★ 用 swap 真正把内存还回去，别让 vector 一直占着
        std::vector<float>().swap(delayL_);
        std::vector<float>().swap(delayR_);
        pos_ = 0;
        lpL_ = lpR_ = 0.0f;
    }

    /**
     * 清空延迟线和滤波器状态。
     *
     * ★★ **seek 之后必须调** —— 和 flushDone_ = false、resampler_.init() 同理。
     *    不调的话，seek 到新位置后头几个样本会带着**旧位置的尾巴**混出来；
     *    延迟线很短，听起来就是"咔哒"一下，极难定位。
     */
    void reset() {
        for (auto& v : delayL_) v = 0.0f;
        for (auto& v : delayR_) v = 0.0f;
        pos_ = 0;
        lpL_ = lpR_ = 0.0f;
    }

    // ★ 开关和档位是**原子**的：解码线程每块读一次，改设置立刻生效，
    //   不用重建引擎。切档不重新分配，也不清状态（读位置变了而已，
    //   听感上就是一次很轻的变化）。
    void setEnabled(bool on) { enabled_.store(on, std::memory_order_relaxed); }
    bool enabled() const { return enabled_.load(std::memory_order_relaxed); }
    void setLevel(Level lv) { level_.store(static_cast<int>(lv), std::memory_order_relaxed); }
    Level level() const { return static_cast<Level>(level_.load(std::memory_order_relaxed)); }

    /** 能不能处理。调用方拿它决定要不要走这条路 */
    bool active() const { return prepared_ && channels_ == 2 && delayL_.size() == maxDelay_; }

    /** 参数字符串，给诊断报告用 */
    void describe(char* out, size_t n) const {
        const Param& p = param(level());
        std::snprintf(out, n, "%s（延迟 %.2fms / 低通 %.0fHz / 交叉 %.1fdB）",
                      levelName(level()), p.delayMs, p.cutoffHz, p.mixDb);
    }

    /**
     * 原地处理一段交错的 S32（左对齐）。
     *
     * ★★ 顺序要紧：**先用旧的历史算输出，再把本帧的原始输入推进延迟线**。
     *    反过来的话，刚混进去的对侧内容会被自己再延迟一遍 —— 成了反馈。
     */
    void process(int32_t* buf, int frames) {
        if (!active() || !enabled()) return;

        const Param& p = param(level());
        const float g = std::pow(10.0f, p.mixDb / 20.0f);

        /*
         * ★★ 归一化 1/(1+g)：中央内容（L≈R）会被放大 (1+g) 倍，
         *    强档 1.708 倍，几乎必然削顶。除一下就从数学上杜绝了溢出。
         *    代价是整体音量降 1/(1+g)（约 -3.5 ~ -4.7dB，按档不同）。
         *    比硬削顶好得多 —— 削顶是失真，降音量只是音量。
         */
        const float norm = 1.0f / (1.0f + g);

        size_t d = static_cast<size_t>(rate_ * p.delayMs / 1000.0f);
        if (d < 1) d = 1;
        if (d >= maxDelay_) d = maxDelay_ - 1;

        // 一阶低通：y += a·(x − y)，a = 1 − e^(−2π·fc/rate)
        const float a = 1.0f - std::exp(-2.0f * 3.14159265358979f * p.cutoffHz
                                        / static_cast<float>(rate_));

        constexpr float kScale = 2147483648.0f;   // 2^31，左对齐满量程

        for (int i = 0; i < frames; ++i) {
            const float l = static_cast<float>(buf[i * 2])     / kScale;
            const float r = static_cast<float>(buf[i * 2 + 1]) / kScale;

            // 取"对侧 D 个样本之前"的值
            size_t idx = (pos_ + maxDelay_ - d) % maxDelay_;
            const float dl = delayL_[idx];
            const float dr = delayR_[idx];

            lpL_ += a * (dr - lpL_);      // 右声道绕过来的内容，喂给左耳
            lpR_ += a * (dl - lpR_);

            float ol = (l + g * lpL_) * norm;
            float orr = (r + g * lpR_) * norm;

            // ★ 归一化之后理论上不越界，但浮点误差可能擦边，夹一下兜底
            if (ol > 1.0f) ol = 1.0f; else if (ol < -1.0f) ol = -1.0f;
            if (orr > 1.0f) orr = 1.0f; else if (orr < -1.0f) orr = -1.0f;

            // ★★ 推的是**原始输入**，不是刚算出来的 ol/orr（见函数头说明）
            delayL_[pos_] = l;
            delayR_[pos_] = r;
            pos_ = (pos_ + 1) % maxDelay_;

            buf[i * 2]     = static_cast<int32_t>(ol * kScale);
            buf[i * 2 + 1] = static_cast<int32_t>(orr * kScale);
        }
    }

private:
    bool prepared_ = false;
    int rate_ = 0;
    int channels_ = 0;
    size_t maxDelay_ = 0;

    /** 两条延迟线，各存**本侧**的历史；交叉时读对侧那条的旧值 */
    std::vector<float> delayL_;
    std::vector<float> delayR_;
    size_t pos_ = 0;

    float lpL_ = 0.0f;
    float lpR_ = 0.0f;

    std::atomic<bool> enabled_{false};
    std::atomic<int> level_{MEDIUM};
};

}  // namespace hifi
