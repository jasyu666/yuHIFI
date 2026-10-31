#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "crossfeed.h"
#include "decoder.h"
#include "dsd_reader.h"
#include "iso_player.h"
#include "rate_policy.h"
#include "resampler.h"
#include "ring_buffer.h"
#include "ring_buffer_source.h"

namespace hifi {

/*
 * 播放引擎：把「文件」变成「按要求速率和格式喂给 IsoPlayer 的字节流」。
 *
 *        [解码线程]                                    [USB 提交线程]
 *   解码 → 重采样(可选) → 转线格式 → 环形缓冲 → RingBufferSource::fill → IsoPlayer
 *
 * 两条线程只通过无锁环形缓冲交汇，谁都不会等谁：
 *   - 解码线程可以慢、可以卡（读文件、解压、重采样都不实时）
 *   - USB 线程绝不能等，缓冲空了就补静音
 *
 * 缓冲水位维持在约 1 秒：足够吸收解码抖动，又不至于让操作（暂停/seek）
 * 有明显的滞后感。水位过低会 underrun，过高则 seek 反应迟钝。
 */
class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    /*
     * 打开文件并决定输出参数，但不启动解码线程。
     * fd 的所有权仍归调用方，引擎只读。
     */
    bool open(int fd, std::string* err);
    void close();

    /* 是否已成功打开一个文件（PCM 走 FFmpeg，DSD 走 DsdReader，二者只居其一） */
    bool ready() const { return decoder_ != nullptr || dsd_ != nullptr; }

    /**
     * 当前打开的是不是 DSD 文件。
     *
     * ★ 界面侧要据此**换一条 alt setting 选择路径**：普通 PCM 的
     *   `selectForPlayback` 现在会显式排除 raw DSD 通道，所以 DSD 必须走
     *   另一条（挑 `bmFormats` bit31 那个 alt）。
     */
    bool isDsd() const { return dsd_ != nullptr; }
    int dsdBitRate() const { return dsd_ ? dsd_->bitRate() : 0; }

    /* 文件信息 + 速率决策的可读报告 */
    std::string openReport() const;

    // ---- 源与输出信息 ----
    int sourceRate() const { return sourceRate_; }
    int outputRate() const { return outputRate_; }
    int channels() const { return channels_; }
    int sourceBits() const { return sourceBits_; }
    bool resampling() const { return resampler_.active(); }
    int64_t durationMs() const { return durationMs_; }
    int subframeSize() const { return subframeSize_; }
    int bitResolution() const { return bitResolution_; }

    /*
     * 当前播放位置（毫秒）。
     *
     * 不能用 decodedFrames_ 直接换算 —— 那是**解码**进度，比实际出声超前
     * 整整一个缓冲（约 1 秒），而且重采样后源帧数与输出帧数也不再一一对应。
     *
     * 改为按输出侧记账：写进环形缓冲的输出帧数减去仍留在缓冲里的帧数，
     * 就是真正送给 DAC 的帧数。重采样不改变时间轴，所以除以输出速率
     * 得到的就是准确的播放时刻。seek 时以目标位置为新基准。
     */
    int64_t positionMs() const;
    const char* codecName() const { return codecName_; }
    const char* formatName() const { return formatName_; }

    /* 速率决策的可读说明，直接显示给用户 */
    std::string ratePolicyNote() const;

    /*
     * 按选定的 alt setting 填 OutputConfig。
     * subframeSize / bitResolution 由上层从描述符里选（应与源位深匹配）。
     */
    void fillOutputConfig(OutputConfig* cfg, int subframeSize, int bitResolution);

    /* 交给 IsoPlayer 的数据源 */
    RingBufferSource* source() { return &ringSource_; }

    /*
     * 把**设备自己声明的速率能力**告诉引擎。界面侧解析完描述符之后调一次。
     *
     * ★ 必须在 openFile 之前调 —— 速率决策发生在 openFile 里。
     *   没调的话 deviceCaps_ 保持 hasInfo=false，行为与以前完全一致
     *   （纯白名单策略，Dawn Pro 就是这条路）。
     */
    void setDeviceRates(const DeviceRateCaps& c) { deviceCaps_ = c; }

    // ---- 解码线程控制 ----
    bool startDecoding();
    void stopDecoding();

    void setPaused(bool p) { paused_.store(p); }

    /*
     * 交叉馈送开关与档位。
     *
     * ★ 用原子变量，解码线程每块读一次 —— **改设置立刻生效**，
     *   不需要重建引擎、也不需要重新开文件。
     * ★ 对 DSD 无效（DSD 不走 PCM 域），对单声道无效。
     * ★ 开了就不是 bit-perfect —— 调用方（界面）有义务说清楚。
     */
    /*
     * 软件音量（用户音量层）。
     *
     * ★★ **它必然不是 bit-perfect** —— 就是在样本上做乘法。
     *    所以有开关，默认关。
     *
     * ★ 增益用原子变量，解码线程每块读一次 → **拖动滑条实时生效**，
     *   不用重建引擎、不用重开文件。
     *
     * ★ 增益 == 1.0（0dB）时**直接跳过整个循环**，一个样本都不碰。
     *
     * ★★ 余量从哪来：内部格式是「源样本左对齐到 32 位」，
     *    16bit 源 `<< 16` 之后**低 16 位本来就是 0**。所以只要把**输出位深**
     *    提到 24bit，右移 8 位写出去时，衰减用掉的是那 8 位空位 ——
     *    **衰减 −48dB 以内不丢任何有效位**。见 [setVolumeControl]。
     *
     * ★ 对 DSD 无效（DSD 走原生位流，不经过 PCM 域）。
     *
     * ★★ 和响度平衡**相互独立**：这里的开关只管用户音量；响度有自己的
     *    [setLoudness]。两者都关时，处理链里一次乘法都不做。
     */
    void setVolumeControl(bool enabled) {
        std::lock_guard<std::mutex> lk(gainMutex_);
        volumeEnabled_.store(enabled);
        recomputeEffectiveGainLocked();
    }
    bool volumeControl() const { return volumeEnabled_.load(); }

    /*
     * 响度平衡：开关 + 增益，**一次原子地设进去**。
     *
     * ★ 和用户音量**完全解耦**：音量控制关着时，响度照样按测量结果处理。
     * ★ 只影响"响度增益是否参与合成"，不改用户音量的上下限/锁/滑条。
     * ★ 开关和增益一起设，避免解码线程在两通 JNI 之间看到旧增益。
     * ★ 对 DSD 无效（不经过 PCM 域）—— Kotlin 侧已排除。
     */
    void setLoudness(bool enabled, float g) {
        std::lock_guard<std::mutex> lk(gainMutex_);
        if (!(g > 0.0f) || !std::isfinite(g)) g = 1.0f;
        if (g > kMaxLoudnessGain) g = kMaxLoudnessGain;
        if (g < kMinGain) g = kMinGain;
        loudnessEnabled_.store(enabled);
        loudnessGain_.store(g);
        recomputeEffectiveGainLocked();
    }
    bool loudnessControl() const { return loudnessEnabled_.load(); }

    /** 用户音量增益（线性，1.0 = 0dB）。范围夹在 [kMinGain, kMaxVolumeGain] */
    void setGain(float g) {
        std::lock_guard<std::mutex> lk(gainMutex_);
        if (g > kMaxVolumeGain) g = kMaxVolumeGain;
        if (g < kMinGain) g = kMinGain;
        volumeGain_.store(g);
        recomputeEffectiveGainLocked();
    }
    float gain() const { return volumeGain_.load(); }

    /** 用户音量的分贝值（可为正）；0dB 单独返回 0，避免显示 −0.0 */
    float gainDb() const {
        const float g = volumeGain_.load();
        if (g >= 0.999999f && g <= 1.000001f) return 0.0f;
        return 20.0f * std::log10(g);
    }

    float loudnessGain() const { return loudnessGain_.load(); }

    /** 响度增益的分贝值；0dB 单独返回 0，避免浮点误差显示成 -0.0 */
    float loudnessGainDb() const {
        const float g = loudnessGain_.load();
        return (g >= 0.999999f && g <= 1.000001f)
                ? 0.0f : 20.0f * std::log10(g);
    }

    /** 热路径实际使用的合成增益（音量 × 响度） */
    float effectiveGain() const { return effectiveGainTarget_.load(); }

    /** 实际送出去的位深 —— 音量或响度任一层开着时会被提到 24bit */
    int outputBitResolution() const { return bitResolution_; }

    void setCrossfeed(bool on, int level) {
        crossfeed_.setLevel(static_cast<Crossfeed::Level>(level));
        crossfeed_.setEnabled(on);
    }
    bool crossfeedEnabled() const { return crossfeed_.enabled() && crossfeed_.active(); }
    bool crossfeedUsable() const { return crossfeed_.active(); }
    int crossfeedLevel() const { return static_cast<int>(crossfeed_.level()); }
    /** 参数字符串，给报告用。没开时返回空串 */
    std::string crossfeedNote() const {
        if (!crossfeed_.enabled() || !crossfeed_.active()) return std::string();
        char buf[128];
        crossfeed_.describe(buf, sizeof(buf));
        return std::string(buf);
    }
    bool paused() const { return paused_.load(); }

    /*
     * 请求定位到指定毫秒。
     *
     * 注意语义：**返回 true 只代表请求已被受理**，位置可能还没变 ——
     * 真正的 seek 由解码线程执行（av_seek_frame / swr_init 都不是线程安全的，
     * 不能在调用方线程里对正在使用的解码器动手）。正常情况几十毫秒内落地。
     */
    bool seekToMs(int64_t ms);

    /* 解码器已到文件末尾（缓冲里可能还有余量） */
    bool decodeFinished() const { return finished_.load(); }

    /*
     * 诊断用计数：解码侧收到多少**源**帧，输出侧写出多少**输出速率**帧。
     *
     * 这两个数配合文件本身的帧数，能定位「音频少了一截」到底丢在哪一段 ——
     * 解码器少给了，还是重采样器少吐了。没有它们就只能靠猜。
     */
    uint64_t decodedFrames() const { return decodedFrames_.load(); }
    uint64_t writtenFrames() const { return writtenFrames_.load(); }

    /*
     * 从环形缓冲**取出去**的**真实音频**字节（不含欠载/暂停补的静音）。
     *
     * ★ 和 writtenFrames（进环）一起构成「进环 → 出环 → 残留」三点账，
     *   是「音频少了一截」时切开责任段用的：
     *     · 进环满、出环少            → 丢在环形缓冲/取数这一侧
     *     · 出环满、残留 0、USB 统计少 → 丢在 USB 提交那一侧
     *     · 残留 ≠ 0 而 finished() 为真 → finished() 的判据本身有问题
     *   没有这三个数就只能猜，实测吃过这个亏。
     */
    uint64_t consumedBytes() const { return ringSource_.consumedBytes(); }

    /** 收尾那一刻环形缓冲里**还没被取走**的字节数 */
    size_t ringAvailableBytes() const { return ring_.available(); }

    /** 一帧（所有声道）的字节数，用来把字节换算成帧 */
    int frameBytes() const { return frameBytes_; }

    /**
     * 输出模式：非 0 表示走**系统音频**，值是系统给的采样率。
     *
     * ★★ 必须在 `open()` **之前**设 —— 速率决策发生在 open() 里面。
     *    这和 `setDeviceRates` 是同一个套路（都是"先定目标再开文件"）。
     *
     * ★ 语义差异：USB 那条路是"尽量直通、不行才重采样"；这条路是
     *   **必须落到系统给的那个速率上**（AAudio 只按这个速率跑）——
     *   所以内部会把目标速率包装成一个"只支持它的设备能力"传给
     *   `decideOutputRate`，正好得到"相同就直通、不同就重采样到它"。
     */
    void setSystemAudio(int systemRate) { systemRate_ = systemRate; }

    /** 当前是不是走系统音频（非 bit-perfect） */
    bool systemAudio() const { return systemRate_ > 0; }

    /*
     * seek 次数。上面两个计数都是**自上次 seek 起算**的分段值 ——
     * 不为 0 就说明这次播放 seek 过，差额校验同样只在段内成立。
     */
    int seekCount() const { return seekCount_.load(); }
    /* 缓冲里还有多少字节没送出去 */
    size_t bufferedBytes() const { return ring_.available(); }
    /* 累计因缓冲不足而补的静音字节数 —— 判断水位够不够的直接依据 */
    uint64_t underrunBytes() const { return ringSource_.underrunBytes(); }
    /* 缓冲水位占比，用于 UI 显示 */
    double bufferFillRatio() const;

    /*
     * 目标缓冲水位（字节）。
     *
     * 必须暴露出去：UI 之前把「播放进度」当成「缓冲水位」显示，害得用户
     * 照着错数字描述卡顿。有了它和 bufferedBytes() 才能算出真实水位。
     */
    size_t targetBufferBytes() const;

    /*
     * 欠载事件清单：每次缓冲见底算一次，含发生位置与补静音时长。
     * 只有累计字节数是不够的 —— 用户报「听到 4 次卡顿」时，
     * 必须能对上「第几秒、每次多长」。
     */
    std::string underrunEventsReport() const;

    std::string statusReport() const;

private:
    void decodeLoop();
    bool writeToRing(const uint8_t* data, size_t bytes);

    /**
     * 软件音量 + 响度平衡：原地乘一个**合成**增益。
     *
     * ★ 两个增益在设置时先乘成一个系数，热路径只做一次乘法 ——
     *   这不是优化，是正确性要求：响度增益可能 > 1，分两次乘会在第一步
     *   就把样本顶出 int32 范围（之后再乘音量也救不回来）。
     * ★ 合成增益 == 1.0 时**直接返回**，连循环都不进。
     * ★ 两个开关独立：音量关着、响度开着时，这里照样处理响度增益。
     * ★ 增益变化走 250ms 线性斜坡，避免拖音量滑条/响度结果到达时的台阶声。
     */
    void applyGain(int32_t* buf, int frames);

    /** 音量增益 × 响度增益 → 热路径只读这一个。调用方必须持有 gainMutex_ */
    void recomputeEffectiveGainLocked();

    /*
     * 真正执行 seek。**只能由解码线程调用** ——
     * FFmpeg 的 av_seek_frame 与 swr_init 都不是线程安全的，
     * 在别的线程里对正在使用的解码器/重采样器动手会直接崩。
     * 所以 seekToMs() 只登记请求，由解码线程在这里落地。
     */
    void doSeek(int64_t ms);

    /*
     * 把重采样器内部延迟线里剩的尾巴吐出来并写进环形缓冲。
     * 解码到文件末尾时调用一次 —— swresample 不会自动交还这部分。
     */
    void flushResampler();

    /** DSD 分流用。非空时 decoder_ 必为空，反之亦然 */
    bool openDsd(int fd, std::string* err);
    void decodeLoopDsd();
    void doSeekDsd(int64_t ms);

    std::unique_ptr<AudioDecoder> decoder_;
    std::unique_ptr<DsdReader> dsd_;
    Resampler resampler_;

    /**
     * 交叉馈送。**只作用于 PCM 路径**（decodeLoop），DSD 那条线程不碰它。
     * 状态（延迟线 / 低通）在 open() 里按输出速率预分配，
     * 在 doSeek() 里必须 reset —— 否则 seek 后会带出旧位置的尾巴。
     */
    Crossfeed crossfeed_;

    /*
     * 软件音量。**默认关**，而且增益默认 1.0（0dB）——
     * 两道门都关着，所以不开这个功能时处理链里连一次乘法都没有。
     */
    /*
     * 增益下限 = −80dB。
     *
     * ★ 软件音量本身没有硬下限（乘个小系数就行），真正的边界在别处：
     *   · **−48dB** 是 16bit 源在 24bit 输出里那 8 位余量用光的位置，
     *     再低就开始丢有效位 —— 但到那个响度早就听不出来了；
     *   · **约 −96dB** 才是数学极限：16bit 源只剩 0 位有效，等于静音。
     *   −80dB 留了一点余量，又远超"实际能听见"的阈，够用了。
     */
    static constexpr float kMinGain = 0.0001f;      // −80dB

    /*
     * 用户音量的上限：+15dB（用户 2026-10-17 定，和 Adobe Audition 的推子范围对齐）。
     * ★ 这是**数字放大**：没有更多位数，推过头就是 0dBFS 硬削顶。
     * ★ 设置层默认上限仍是 0dB，必须用户手动提高才会用到这里的正增益。
     */
    static constexpr float kMaxVolumeGain = 5.6234132f;   // +15dB

    /* 响度增益的防御上限：+24dB。真峰值钳制正常情况下远到不了这里 */
    static constexpr float kMaxLoudnessGain = 15.8489319f;

    std::atomic<bool> volumeEnabled_{false};
    std::atomic<bool> loudnessEnabled_{false};
    std::atomic<float> volumeGain_{1.0f};
    std::atomic<float> loudnessGain_{1.0f};
    std::atomic<float> effectiveGainTarget_{1.0f};

    /*
     * 只保护两个增益的"写 + 合算"这一对操作。
     * 解码线程**不拿这把锁**，它只读 effectiveGainTarget_。
     */
    mutable std::mutex gainMutex_;

    /*
     * 斜坡状态。只有解码线程读写，不需要原子量。
     * 250ms 按输出采样率换算成样本数。
     */
    float currentGain_ = 1.0f;
    float rampStep_ = 0.0f;
    float rampLastTarget_ = 1.0f;
    int rampRemainingSamples_ = 0;

    /** 设备声明的速率能力。hasInfo=false 时全部走白名单 */
    DeviceRateCaps deviceCaps_;

    int sourceRate_ = 0;
    int outputRate_ = 0;
    int channels_ = 0;
    int sourceBits_ = 0;
    int64_t durationMs_ = 0;
    const char* codecName_ = "?";
    const char* formatName_ = "?";

    int subframeSize_ = 2;
    int bitResolution_ = 16;
    int frameBytes_ = 4;

    /* 非 0 = 走系统音频，值是 AudioManager 给的系统采样率（见 setSystemAudio） */
    int systemRate_ = 0;

    // 2 MiB：48k/24bit/2ch (288kB/s) 下约 7.3 秒，192k/24bit 下约 1.8 秒，
    // 而 384k/24bit (2.3MB/s) 只有约 0.9 秒 —— 已经装不下 1 秒的目标水位，
    // 所以目标水位必须按容量封顶，见 targetBufferBytes()
    RingBuffer ring_{1 << 21};
    RingBufferSource ringSource_{&ring_};

    /* 重采样器的尾巴是否已经冲刷过（每次 seek 会重置） */
    bool flushDone_ = false;

    std::thread decodeThread_;

    /* seek 请求交接：UI 线程填 pendingSeekMs_，解码线程做完写 seekDoneMs_ */
    std::atomic<int64_t> pendingSeekMs_{-1};   // -1 = 无请求
    std::atomic<int64_t> seekDoneMs_{-1};      // 解码线程完成后的回执

    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> finished_{false};

    /* 解码进度：从文件里解出的**源**帧数（含还压在缓冲里的） */
    std::atomic<uint64_t> decodedFrames_{0};
    std::atomic<uint64_t> writtenBytes_{0};

    /* 输出进度：写进环形缓冲的**输出速率**帧数，用于算播放位置 */
    std::atomic<uint64_t> writtenFrames_{0};
    /* 最近一次 seek 的目标位置，作为位置计算的时间基准 */
    std::atomic<int64_t> seekBaseMs_{0};
    std::atomic<int> seekCount_{0};

    // fd 的所有权归引擎（Kotlin 侧用 detachFd() 转交过来），close() 时关闭。
    // 不用 getFd()：那样 ParcelFileDescriptor 仍持有它，一旦被 GC 就会
    // 在我们还在解码时把 fd 关掉。
    int fd_ = -1;
};

}  // namespace hifi
