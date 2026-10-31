#pragma once

#include <cstdint>
#include <vector>

namespace hifi {

/*
 * 一次响度测量的结果。
 *
 * lufs       —— BS.1770-4 门控积分响度，单位 LUFS
 * truePeakDb —— EBU Tech 3341 真峰值，单位 dBTP（0 = 满刻度）
 * valid      —— 没解出任何完整的 400ms 块时为 false
 */
struct LoudnessResult {
    double lufs = 0.0;
    double truePeakDb = 0.0;
    bool valid = false;
};

/*
 * ITU-R BS.1770-4 响度计。
 *
 * 只做**测量**，不做任何播放处理。输入是交错 float（-1..1），
 * 和播放引擎里的「左对齐 S32」不同 —— 分析器在解码侧自己转 float。
 *
 * ★ 400ms 块 / 100ms 步进用「4 个 100ms 分块和」实现：
 *   每 100ms 只算一次小块，400ms 块 = 最近 4 个小块相加。
 *   所以块逻辑本身是 O(1) 的，真正的成本在逐样本的 K 加权。
 *
 * ★ 两级门限：
 *   1. 绝对门限 −70 LUFS
 *   2. 相对门限 = 过绝对门限者的均值 −10 LU
 *   少了这两级，前奏独奏和静音段会把整曲的响度带偏。
 */
class LoudnessMeter {
public:
    static constexpr int HOP_PARTS = 4;              // 400ms / 100ms
    static constexpr double ABS_GATE_LUFS = -70.0;
    static constexpr double REL_GATE_LU = 10.0;

    /*
     * 初始化（会清空之前的全部状态）。
     *
     * measureTruePeak = false 时跳过 4 倍过采样 —— 专辑计只负责响度，
     * 真峰值取各曲目真峰值的最大值即可，不必重复算。
     */
    void init(int sampleRate, int channels, bool measureTruePeak);

    /*
     * 只换格式、**保留已经积累的 400ms 块**（专辑里各曲目采样率/声道数
     * 不一致时用）。当前未满 100ms 的残块会丢弃 —— 边界处损失不到 100ms，
     * 对整张专辑的积分响度影响可以忽略。
     */
    void setFormat(int sampleRate, int channels);

    void reset();

    /** 喂交错 float 样本 */
    void feedInterleavedFloat(const float* data, int frames);

    /** 收尾：把最后一个不足 100ms 的残块结算掉（不足 400ms 的窗口不会成为块） */
    void finish();

    LoudnessResult result() const;

    size_t blockCount() const { return blocks_.size(); }
    int sampleRate() const { return sampleRate_; }
    int channels() const { return channels_; }

private:
    struct Biquad {
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        double z1 = 0, z2 = 0;

        inline double process(double x) {
            const double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void reset() { z1 = z2 = 0; }
    };

    /*
     * 真峰值：4 倍过采样后取绝对值最大。
     *
     * 多相 FIR，每相 12 抽头（共 48），窗函数法 sinc + Blackman。
     * ★ 这是工程实现用的近似滤波器；与标准附录那组固定系数相比，
     *   在实测样本上和 ffmpeg ebur128 的差距 < 0.1dB（见 docs/11 附录）。
     *   两者计算量同一量级，不影响调度与耗时判断。
     */
    struct TruePeak {
        static constexpr int PHASES = 4;
        static constexpr int TAPS_PER_PHASE = 12;
        static constexpr int TAPS = PHASES * TAPS_PER_PHASE;

        double coeff[PHASES][TAPS_PER_PHASE] = {};
        double hist[TAPS] = {};
        int pos = 0;
        double peak = 0.0;

        void init();
        void reset();
        void feed(double x);
    };

    void initFilters();
    void resetHopState();
    void closeHop();
    static double blockLufs(double z);

    int sampleRate_ = 48000;
    int channels_ = 2;
    int hopSamples_ = 4800;
    int sinceHop_ = 0;
    bool measureTruePeak_ = true;

    std::vector<Biquad> shelf_;
    std::vector<Biquad> hpf_;
    std::vector<std::vector<double>> partial_;   // [channel][4]
    std::vector<double> cur_;                    // [channel]
    std::vector<TruePeak> truePeak_;             // [channel]
    std::vector<double> blocks_;                 // 每个 400ms 块的加权均方和

    int slot_ = 0;       // 100ms 环形写指针（所有声道共用）
    int filled_ = 0;     // 已填了几个 100ms 分块
};

}  // namespace hifi
