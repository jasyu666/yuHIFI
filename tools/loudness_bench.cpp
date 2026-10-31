// ============================================================================
//  响度测量基准工具 —— 一次性实验用，不进 App。
//
//  目的只有一个：回答「在真机上把一首歌整曲分析一遍要多久」。
//
//  所以它按**真实的处理链**走：解码 → 转 float → K 加权 → 400ms/75% 分块
//  → 两级门限 → 集成响度，外加真峰值（4 倍过采样）。并且分段计时，
//  好看出时间花在哪一段（解码 / 格式转换 / 分析）。
//
//  用法：
//      loudness_bench <文件> [文件…]
//
//  输出两行：
//      一行人看的报告
//      一行机器可读的 CSV（便于批量跑完排序）
//
//  ★ 和将来真正实现的两处差异，读结果时要记住：
//    1. 这里用 libswresample 转成 float。真实现里引擎手里已经是
//       「左对齐到 32 位的 S32」，不需要 swr —— 所以**这里的耗时偏高**。
//    2. 真峰值那个 4 倍过采样滤波器是近似的（窗函数法 sinc）。
//       标准（BS.1770 附录 2）给的是指定的 48 抽头系数，
//       真实现要用标准那组，否则算出来的 dBTP 和别人不一样。
//       但两者的**计算量**是同一量级，计时结论不受影响。
//
//  ★ K 加权系数按采样率重算（标准正文只给 48kHz 的），
//    用的是 ffmpeg f_ebur128.c 里那组常数 —— 所以可以直接和
//    `ffmpeg -filter_complex ebur128` 的输出对拍。
// ============================================================================

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <chrono>
#include <string>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
}

namespace {

using Clock = std::chrono::steady_clock;

double msSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// ---------------------------------------------------------------------------
//  双二阶（Transposed Direct Form II，单精度状态用 double）
// ---------------------------------------------------------------------------
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

// ---------------------------------------------------------------------------
//  K 加权：两级串联。系数随采样率重算。
//
//  常数取自 ffmpeg 的 f_ebur128.c（也就是 BS.1770 附录 1 的模拟原型），
//  这样算出来的值和 ffmpeg 的 ebur128 滤镜应当逐位接近。
// ---------------------------------------------------------------------------
void initKWeighting(Biquad& shelf, Biquad& hpf, int fs) {
    const double rate = static_cast<double>(fs);

    // ---- 第一级：高频搁架（f0≈1682Hz, +4dB, Q≈0.707）----
    {
        const double f0 = 1681.974450955533;
        const double G = 3.999843853973347;
        const double Q = 0.7071752369554196;

        const double K = std::tan(M_PI * f0 / rate);
        const double Vh = std::pow(10.0, G / 20.0);
        const double Vb = std::pow(Vh, 0.4996667741545416);
        const double a0 = 1.0 + K / Q + K * K;

        shelf.b0 = (Vh + Vb * K / Q + K * K) / a0;
        shelf.b1 = 2.0 * (K * K - Vh) / a0;
        shelf.b2 = (Vh - Vb * K / Q + K * K) / a0;
        shelf.a1 = 2.0 * (K * K - 1.0) / a0;
        shelf.a2 = (1.0 - K / Q + K * K) / a0;
    }

    // ---- 第二级：高通（f0≈38.1Hz, Q≈0.5）----
    {
        const double f0 = 38.13547087602444;
        const double Q = 0.5003270373238773;

        const double K = std::tan(M_PI * f0 / rate);
        const double a0 = 1.0 + K / Q + K * K;

        hpf.b0 = 1.0;
        hpf.b1 = -2.0;
        hpf.b2 = 1.0;
        hpf.a1 = 2.0 * (K * K - 1.0) / a0;
        hpf.a2 = (1.0 - K / Q + K * K) / a0;
    }

    shelf.reset();
    hpf.reset();
}

// ---------------------------------------------------------------------------
//  真峰值：4 倍过采样后取绝对值最大。
//
//  多相 FIR，每相 12 抽头（共 48），窗函数法 sinc + Blackman。
//  ★ 这是近似实现，见文件头第 2 条。
// ---------------------------------------------------------------------------
struct TruePeak {
    static constexpr int PHASES = 4;
    static constexpr int TAPS_PER_PHASE = 12;
    static constexpr int TAPS = PHASES * TAPS_PER_PHASE;

    double coeff[PHASES][TAPS_PER_PHASE] = {};
    double hist[TAPS] = {};
    int pos = 0;
    double peak = 0.0;

    void init() {
        const int M = TAPS - 1;                 // 滤波器阶数
        const double fc = 0.5 / PHASES;         // 相对输入奈奎斯特的截止
        double h[TAPS];
        double sum = 0.0;
        for (int n = 0; n < TAPS; ++n) {
            const double x = n - M / 2.0;
            const double sinc = (std::fabs(x) < 1e-9) ? 1.0 : std::sin(M_PI * fc * x) / (M_PI * fc * x);
            // Blackman 窗
            const double w = 0.42 - 0.5 * std::cos(2.0 * M_PI * n / M)
                                   + 0.08 * std::cos(4.0 * M_PI * n / M);
            h[n] = sinc * w;
            sum += h[n];
        }
        for (int n = 0; n < TAPS; ++n) h[n] /= sum;

        // 多相分解：相 p 的第 k 个系数 = h[p + k*PHASES]
        for (int p = 0; p < PHASES; ++p)
            for (int k = 0; k < TAPS_PER_PHASE; ++k)
                coeff[p][k] = h[p + k * PHASES] * PHASES;
    }

    void reset() {
        std::memset(hist, 0, sizeof(hist));
        pos = 0;
        peak = 0.0;
    }

    inline void feed(double x) {
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
};

// ---------------------------------------------------------------------------
//  集成响度：400ms 窗 / 100ms 步进，两级门限。
//
//  ★ 内存 O(1)：每个声道只留 4 个 100ms 的分块平方和（400ms = 4×100ms），
//    块和就是这 4 个相加。整首歌不用驻留内存 —— 对 200 小时的库很关键。
// ---------------------------------------------------------------------------
class LoudnessMeter {
public:
    static constexpr int HOP_PARTS = 4;          // 400ms / 100ms
    static constexpr double ABS_GATE_LUFS = -70.0;
    static constexpr double REL_GATE_LU = 10.0;

    void init(int fs, int channels) {
        fs_ = fs;
        channels_ = channels;
        hopSamples_ = fs / 10;                   // 100ms
        if (hopSamples_ <= 0) hopSamples_ = 1;

        partial_.assign(channels, std::vector<double>(HOP_PARTS, 0.0));
        cur_.assign(channels, 0.0);
        slot_ = 0;
        filled_ = 0;
        sinceHop_ = 0;

        shelf_.assign(channels, Biquad{});
        hpf_.assign(channels, Biquad{});
        for (int c = 0; c < channels; ++c) initKWeighting(shelf_[c], hpf_[c], fs);

        tp_.resize(channels);
        for (auto& t : tp_) t.init();
        // LB_NOTP=1 跳过真峰值，用来量它在总耗时里占多少
        doTruePeak_ = (std::getenv("LB_NOTP") == nullptr);

        blocks_.clear();
    }

    // 喂交错 float（-1..1）。逐样本做 K 加权，每 100ms 收一个分块。
    void feed(const float* interleaved, int frames) {
        for (int i = 0; i < frames; ++i) {
            for (int c = 0; c < channels_; ++c) {
                const double x = interleaved[i * channels_ + c];
                const double y = hpf_[c].process(shelf_[c].process(x));
                cur_[c] += y * y;
                if (doTruePeak_) tp_[c].feed(x);
            }
            if (++sinceHop_ >= hopSamples_) {
                closeHop();
                sinceHop_ = 0;
            }
        }
    }

    void finish() {
        if (sinceHop_ > 0) closeHop();
        // 最后凑不满 4 个分块的窗口不算一个完整块 —— 与标准一致
    }

    double integratedLufs() const {
        if (blocks_.empty()) return -HUGE_VAL;

        // ① 绝对门限
        double sumAbs = 0.0; int nAbs = 0;
        for (double z : blocks_) {
            if (blockLufs(z) > ABS_GATE_LUFS) { sumAbs += z; ++nAbs; }
        }
        if (nAbs == 0) return -HUGE_VAL;

        // ② 相对门限（基于过绝对门限者的均值）
        const double relThreshold = blockLufs(sumAbs / nAbs) - REL_GATE_LU;

        // ③ 两级都过
        double sum = 0.0; int n = 0;
        for (double z : blocks_) {
            const double l = blockLufs(z);
            if (l > ABS_GATE_LUFS && l > relThreshold) { sum += z; ++n; }
        }
        if (n == 0) return -HUGE_VAL;
        return blockLufs(sum / n);
    }

    double truePeakDbfs() const {
        double p = 0.0;
        for (const auto& t : tp_) p = std::max(p, t.peak);
        if (p <= 0.0) return -HUGE_VAL;
        return 20.0 * std::log10(p);
    }

    size_t blockCount() const { return blocks_.size(); }

private:
    static double blockLufs(double z) {
        if (z <= 0.0) return -HUGE_VAL;
        return -0.691 + 10.0 * std::log10(z);
    }

    void closeHop() {
        for (int c = 0; c < channels_; ++c) {
            partial_[c][slot_] = cur_[c];
            cur_[c] = 0.0;
        }
        // 所有声道同步推进 —— 用**一个**环形写指针，不是每声道一个
        if (++slot_ >= HOP_PARTS) slot_ = 0;
        if (filled_ < HOP_PARTS) ++filled_;

        // 不满 400ms 不算一个块
        if (filled_ < HOP_PARTS) return;

        // 400ms 块的加权均方和。声道权重：L=R=1.0，环绕 1.41。
        // 这里只区分"前 2 声道"和"其余"—— 本工具跑的都是立体声/单声道。
        double z = 0.0;
        for (int c = 0; c < channels_; ++c) {
            double win = 0.0;
            for (int k = 0; k < HOP_PARTS; ++k) win += partial_[c][k];
            const double meanSquare = win / (double)(hopSamples_ * HOP_PARTS);
            const double gi = (c < 2) ? 1.0 : 1.41;
            z += gi * meanSquare;
        }
        blocks_.push_back(z);
    }

    int fs_ = 48000;
    int channels_ = 2;
    int hopSamples_ = 4800;
    int sinceHop_ = 0;

    std::vector<Biquad> shelf_, hpf_;
    std::vector<std::vector<double>> partial_;
    bool doTruePeak_ = true;
    int slot_ = 0;      // 环形写指针（所有声道共用）
    int filled_ = 0;    // 已经填了几个 100ms 分块
    std::vector<double> cur_;
    std::vector<TruePeak> tp_;
    std::vector<double> blocks_;
};

// ---------------------------------------------------------------------------

int measureOne(const char* path, bool csv) {
    AVFormatContext* fmt = nullptr;
    AVCodecContext* dec = nullptr;
    SwrContext* swr = nullptr;
    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();

    double tOpen = 0, tDecode = 0, tConvert = 0, tAnalyze = 0;
    double audioSec = 0;
    long long outSamples = 0;
    LoudnessMeter meter;
    int rc = 1;

    auto t0 = Clock::now();
    if (avformat_open_input(&fmt, path, nullptr, nullptr) < 0) {
        std::fprintf(stderr, "打不开: %s\n", path);
        goto done;
    }
    if (avformat_find_stream_info(fmt, nullptr) < 0) {
        std::fprintf(stderr, "找不到流信息: %s\n", path);
        goto done;
    }
    tOpen = msSince(t0);

    {
        const int idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (idx < 0) { std::fprintf(stderr, "没有音频流: %s\n", path); goto done; }

        const AVCodec* codec = avcodec_find_decoder(fmt->streams[idx]->codecpar->codec_id);
        if (!codec) { std::fprintf(stderr, "没有解码器\n"); goto done; }

        dec = avcodec_alloc_context3(codec);
        avcodec_parameters_to_context(dec, fmt->streams[idx]->codecpar);
        // 多线程解码：手机上这是必须的，单线程会慢好几倍
        dec->thread_count = 0;
        if (avcodec_open2(dec, codec, nullptr) < 0) {
            std::fprintf(stderr, "开解码器失败\n");
            goto done;
        }

        const int fs = dec->sample_rate;
        const int ch = dec->ch_layout.nb_channels;
        audioSec = (fmt->duration > 0) ? (fmt->duration / (double)AV_TIME_BASE) : 0.0;

        // 统一转成 float 平面 → 这里再交织，交给测量器
        AVChannelLayout outLayout;
        av_channel_layout_default(&outLayout, ch);
        swr_alloc_set_opts2(&swr, &outLayout, AV_SAMPLE_FMT_FLT, fs,
                            &dec->ch_layout, dec->sample_fmt, fs, 0, nullptr);
        av_channel_layout_uninit(&outLayout);
        if (!swr || swr_init(swr) < 0) { std::fprintf(stderr, "swr 初始化失败\n"); goto done; }

        meter.init(fs, ch);

        std::vector<float> buf;
        int64_t pts0 = AV_NOPTS_VALUE;

        while (true) {
            auto td = Clock::now();
            const int r = avcodec_receive_frame(dec, frame);
            if (r == AVERROR(EAGAIN)) {
                const int pr = av_read_frame(fmt, pkt);
                if (pr < 0) { avcodec_send_packet(dec, nullptr); continue; }
                if (pkt->stream_index == idx) avcodec_send_packet(dec, pkt);
                av_packet_unref(pkt);
                tDecode += msSince(td);
                continue;
            }
            if (r == AVERROR_EOF) { tDecode += msSince(td); break; }
            if (r < 0) { tDecode += msSince(td); break; }
            tDecode += msSince(td);

            if (pts0 == AV_NOPTS_VALUE) pts0 = frame->pts;

            const int maxOut = swr_get_out_samples(swr, frame->nb_samples) + 64;
            buf.resize((size_t)maxOut * ch);

            auto tc = Clock::now();
            uint8_t* outPtr = reinterpret_cast<uint8_t*>(buf.data());
            const int got = swr_convert(swr, &outPtr, maxOut,
                                        (const uint8_t**)frame->extended_data, frame->nb_samples);
            tConvert += msSince(tc);
            if (got > 0) {
                auto ta = Clock::now();
                meter.feed(buf.data(), got);
                tAnalyze += msSince(ta);
                outSamples += got;
            }
            av_frame_unref(frame);
        }
    }

    meter.finish();
    rc = 0;

    {
        const double lufs = meter.integratedLufs();
        const double tp = meter.truePeakDbfs();
        const double measured = (double)outSamples / (double)dec->sample_rate;
        const double totalMs = tOpen + tDecode + tConvert + tAnalyze;
        const double rt = (measured > 0) ? measured / (totalMs / 1000.0) : 0.0;

        if (csv) {
            // name,秒,总ms,开ms,解码ms,转换ms,分析ms,倍速,LUFS,dBTP
            std::printf("CSV,%s,%.2f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1fx,%.2f,%.2f\n",
                        path, measured, totalMs, tOpen, tDecode, tConvert, tAnalyze,
                        rt, lufs, tp);
        } else {
            std::printf("文件      %s\n", path);
            std::printf("时长      %.1f 秒（%.1f 分钟）\n", measured, measured / 60.0);
            std::printf("总耗时    %.1f ms   →  %.1f 倍实时\n", totalMs, rt);
            std::printf("  打开    %6.1f ms\n", tOpen);
            std::printf("  解码    %6.1f ms  (%.0f%%)\n", tDecode, 100.0 * tDecode / totalMs);
            std::printf("  转float %6.1f ms  (%.0f%%)\n", tConvert, 100.0 * tConvert / totalMs);
            std::printf("  分析    %6.1f ms  (%.0f%%)\n", tAnalyze, 100.0 * tAnalyze / totalMs);
            std::printf("集成响度  %.2f LUFS   （%zu 个 400ms 块）\n", lufs, meter.blockCount());
            std::printf("真峰值    %.2f dBFS  （近似滤波器）\n", tp);
        }
    }

done:
    if (swr) swr_free(&swr);
    if (dec) avcodec_free_context(&dec);
    if (fmt) avformat_close_input(&fmt);
    av_packet_free(&pkt);
    av_frame_free(&frame);
    return rc;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "用法: %s <文件> [文件…]\n", argv[0]);
        return 2;
    }
    const bool csv = (std::getenv("LB_CSV") != nullptr);
    int bad = 0;
    for (int i = 1; i < argc; ++i) {
        if (measureOne(argv[i], csv) != 0) ++bad;
        if (!csv) std::printf("\n");
    }
    return bad == 0 ? 0 : 1;
}
