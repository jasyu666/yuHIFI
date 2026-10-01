#pragma once

#include <atomic>
#include <chrono>
#include <cstring>

#include "pcm_source.h"
#include "ring_buffer.h"

namespace hifi {

/*
 * 把环形缓冲包装成 PcmSource，供 IsoPlayer 从 USB 线程消费。
 *
 * fill() 的契约在这里体现得最清楚：**无论缓冲里有多少数据都立刻返回**。
 * 数据不够就补静音并记账。绝不能在 USB 提交线程上等待解码 ——
 * iso 端点不重传，等一次就是一声爆音。
 *
 * underrun 计数是判断缓冲水位够不够的直接依据。
 */
class RingBufferSource : public PcmSource {
public:
    /*
     * 一次欠载事件。
     *
     * 光有一个「累计欠载字节数」看不出卡顿发生在哪、有几回、每次多长 ——
     * 实测就是这么卡住的：用户报告听到 4 次卡顿，而报告里只有一个总数。
     */
    struct Event {
        uint64_t atFrames = 0;   // 事件开始时的输出帧计数
        uint64_t bytes = 0;      // 这一次累计补进去的静音字节
        int64_t baseMs = 0;      // 记录当时的 seek 基准，见 setSeekBaseProvider
        /*
         * 事件开始时，距离上一次真正从缓冲取到数据过了多久。
         *
         * 这是区分「解码线程被挂起」和「其他原因」的关键数据：缓冲耗尽
         * 只说明"没数据了"，而间隔很大才说明是**供给方停了很久**。
         * 实测切屏能让解码线程饿死 1 秒以上，这个数会直接体现出来。
         */
        uint64_t gapMs = 0;

        /*
         * 距本段开播过了多久。
         *
         * 「第几秒」（歌曲位置）在 seek 之后会跳变，而这个是单调递增的会话时钟，
         * 和日志里「离开应用 / 回到前台」两行配合，能把卡顿精确定位到
         * 「回来之后第几秒」。
         */
        uint64_t sinceStartMs = 0;
    };

    static constexpr int kMaxEvents = 20;

    explicit RingBufferSource(RingBuffer* rb) : rb_(rb) {}

    int fill(uint8_t* dst, int bytes) override {
        if (bytes <= 0) return 0;

        const uint64_t t = nowMs();
        if (!everFilled_) {          // 首次调用作为计时起点，避免把开机以来
            lastDataMs_ = t;         // 的时间算成"间隔"
            startMs_ = t;            // 同时作为会话时钟的原点
            everFilled_ = true;
        }

        /*
         * 暂停：整块写静音，**完全不碰环形缓冲**。
         *
         * 这一点是"暂停"能既立刻静音、又无缝恢复的关键：
         *   · 音频原地冻结在缓冲里 —— 位置不前进，恢复时从断点接着放，一个样本不差
         *   · 但数据流不断 —— DAC 持续收到（静音）数据，时钟保持锁定，
         *     恢复时不需要重新锁定，也就没有那声咔哒
         *
         * 所以这里的静音是**故意的**，记进 pauseSilence_ 而不是 underrun_。
         */
        const bool wantSilence =
                (paused_ != nullptr) && paused_->load(std::memory_order_relaxed);

        /*
         * ★★ 但**不能硬切**。
         *
         *   这里原来只有一句 memset —— 输出于是从"满幅音频"一步跳到"全零"，
         *   而阶跃 = 宽频冲击 = **按下暂停时那一声"咔哒"**（用户实测：
         *   "按下响一声"）。恢复时反过来，同样是一声。
         *
         *   现在先用 5ms 从**上一个输出样本**线性衰减到 0，之后才是纯零。
         *
         *   ★ 衰减只在 gain_ 还没到 0 的那几次 fill 里跑（约 5 次）；
         *     到了稳态就退化成一句 memset —— **热路径一分钱不多花**。
         */
        if (wantSilence) {
            if (canFade() && gain_ > 0.f) fadeOutTo(dst, bytes);
            else std::memset(dst, 0, static_cast<size_t>(bytes));
            pauseSilence_.fetch_add(static_cast<uint64_t>(bytes),
                                    std::memory_order_relaxed);
            return 0;
        }

        const size_t got = rb_->read(dst, static_cast<size_t>(bytes));
        if (got > 0) lastDataMs_ = t;    // 拿到了数据，刷新计时

        /*
         * 真正从环形缓冲里取出来的**音频**字节（不含补的静音）。
         *
         * 存在的理由是「音频少了一截」时能一刀切开责任：
         *   writtenFrames（进环） vs consumedBytes（出环） vs 收尾残留
         * 三个数一摆，就能判断丢在 解码写入 / 环形缓冲 / USB 提交 哪一段，
         * 而不是靠猜。实测就是靠它把「冷启动首播少 5 个 URB」定位下来的。
         */
        consumedBytes_.fetch_add(got, std::memory_order_relaxed);

        if (got < static_cast<size_t>(bytes)) {
            const size_t missing = static_cast<size_t>(bytes) - got;
            std::memset(dst + got, 0, missing);      // 静音填充，绝不阻塞
            underrun_.fetch_add(missing, std::memory_order_relaxed);
            recordUnderrun(missing);
        }

        /*
         * 恢复播放：同样不能硬切 —— 从全零一步跳回满幅也是阶跃。
         * 把头 5ms 的**真实音频**从 0 升到 1。
         */
        if (canFade() && gain_ < 1.f && got > 0) {
            fadeInOn(dst, static_cast<int>(got));
        }

        // 记住这一块最后送出去的那一帧 —— 下次暂停时从它开始衰减
        rememberTail(dst, bytes);
        return static_cast<int>(got);
    }

    uint64_t pauseSilenceBytes() const override {
        return pauseSilence_.load(std::memory_order_relaxed);
    }

    // ---- 欠载事件 ----

    int eventCount() const { return eventCount_; }
    const Event& event(int i) const { return events_[i]; }
    /* 超过 kMaxEvents 之后被丢弃的事件数 —— 报告里要说明，不能假装只有 20 次 */
    uint64_t droppedEvents() const { return dropped_; }

    void clearEvents() {
        eventCount_ = 0;
        dropped_ = 0;
        // 会话时钟随事件一起归零，下一次 fill 重新取原点
        everFilled_ = false;
        startMs_ = 0;
    }

    /* 输出侧已写出的帧数，用来给事件打上"发生在第几秒" */
    void setOutputFrameCounter(const std::atomic<uint64_t>* f) { writtenFrames_ = f; }

    /* seek 基准（毫秒）。事件要把当时的基准一起记下来 —— 否则 seek 之后再
       回头解读，位置会全部错乱（实测 340 秒的歌报出"第 634 秒"）。 */
    void setSeekBaseProvider(const std::atomic<int64_t>* b) { seekBaseMs_ = b; }

    /* 暂停标志。暂停期间补的静音不算欠载，见 fill()。 */
    void setPausedFlag(const std::atomic<bool>* p) { paused_ = p; }

    /*
     * 告诉这里输出的线格式。
     *
     * ★ 淡入淡出要**按样本**做，而 fill() 只认字节 —— 没有格式就没法缩放。
     *   [AudioEngine] 在**每一处**改 frameBytes_ 的地方都要跟着调这一句
     *   （PCM 直通 / DSD / fillOutputConfig 三处），否则格式一变，
     *   淡变就会按错的帧长切，出来的东西比咔哒还难听。
     */
    void setFormat(int channels, int subframeSize, int sampleRate) {
        channels_ = channels > 0 ? channels : 2;
        subframeSize_ = (subframeSize >= 2 && subframeSize <= 4) ? subframeSize : 2;
        frameBytes_ = channels_ * subframeSize_;
        // 5ms 的斜坡：够把阶跃抹掉，又短到听不出"渐弱"
        const int sr = sampleRate > 0 ? sampleRate : 48000;
        rampFrames_ = sr / 200;
        if (rampFrames_ < 16) rampFrames_ = 16;
        gainStep_ = 1.f / static_cast<float>(rampFrames_);
    }

    /**
     * 归零淡变状态。**每次 open() 都要调** —— 否则上一首残留的 gain_
     * 会让新的一首从头几毫秒被削。
     */
    void resetFade() {
        gain_ = 1.f;
        std::memset(lastFrame_, 0, sizeof(lastFrame_));
    }

    /*
     * 相距多少帧以内的两次欠载算同一次卡顿。
     *
     * 缓冲见底时 USB 线程会一个 URB 接一个 URB 地补静音（每个 URB 8 个
     * microframe），每次都要调一遍 fill。若逐次记录，一次听感上的卡顿会被
     * 拆成几十条记录。按时间窗合并之后，一条记录 ≈ 一次卡顿。
     */
    void setMergeWindowFrames(uint64_t frames) { mergeWindowFrames_ = frames; }

    uint64_t underrunBytes() const override {
        return underrun_.load(std::memory_order_relaxed);
    }

    /* 出环的真实音频字节（不含欠载/暂停补的静音）。纯统计 */
    uint64_t consumedBytes() const {
        return consumedBytes_.load(std::memory_order_relaxed);
    }

    void resetUnderrun() { underrun_.store(0, std::memory_order_relaxed); }

    /*
     * 告知「解码侧是否已到文件末尾」。由 AudioEngine 在构造时接上。
     */
    void setDecodeFinishedFlag(const std::atomic<bool>* f) { decodeFinished_ = f; }

    /*
     * 真正的「放完了」= 解码已到末尾 **且** 缓冲也排空了。
     *
     * IsoPlayer 靠这个在最后一帧送出去之后主动收手。少了它，USB 线程会继续
     * 按 microframe 节奏喂静音，直到上层（UI 每 500ms 轮询一次）发现为止 ——
     * 实测那一段有 478ms，而且会被记进 underrun，让这个指标彻底失去意义：
     * 报告上显示「欠载 579ms」，看起来像解码跟不上，其实只是收尾慢。
     */
    bool finished() const override {
        if (decodeFinished_ == nullptr ||
            !decodeFinished_->load(std::memory_order_acquire)) {
            return false;
        }
        // 缓冲里还剩哪怕一个字节，也先把它送出去
        return rb_->available() == 0;
    }

private:
    // ------------------------------------------------------------------
    //  淡入淡出
    //
    //  唯一目的：**消除阶跃**。见 fill() 里那两段说明。
    // ------------------------------------------------------------------

    /** 帧长合法才敢按样本算 —— 认不出来就退回硬切，宁可有咔哒也不能放错音 */
    bool canFade() const {
        return frameBytes_ > 0 && frameBytes_ <= kMaxFrameBytes && gainStep_ > 0.f;
    }

    /* 2/3/4 字节小端有符号样本的读写。线格式就是"小端、每样本 subframe 字节" */
    static int32_t loadSample(const uint8_t* p, int sub) {
        switch (sub) {
            case 2: { int16_t v; std::memcpy(&v, p, 2); return v; }
            case 3: {
                int32_t v = static_cast<int32_t>(p[0]) |
                            (static_cast<int32_t>(p[1]) << 8) |
                            (static_cast<int32_t>(p[2]) << 16);
                if (v & 0x800000) v |= ~0xFFFFFF;          // 符号扩展
                return v;
            }
            default: { int32_t v; std::memcpy(&v, p, 4); return v; }
        }
    }

    static void storeSample(uint8_t* p, int sub, int32_t v) {
        switch (sub) {
            case 2:
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                { const int16_t s = static_cast<int16_t>(v); std::memcpy(p, &s, 2); }
                break;
            case 3:
                if (v > 8388607) v = 8388607; else if (v < -8388608) v = -8388608;
                p[0] = static_cast<uint8_t>(v & 0xFF);
                p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
                p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
                break;
            default:
                std::memcpy(p, &v, 4);
                break;
        }
    }

    /**
     * 从 lastFrame_ 线性衰减到 0，**填满整块**（衰减段之后补零）。
     *
     * ★ 用"上一个输出样本"当起点，正是为了**接得上**：这样第一个衰减样本
     *   就等于刚才送出去的那个值，波形没有跳变，也就没有阶跃。
     */
    void fadeOutTo(uint8_t* dst, int bytes) {
        int off = 0;
        while (off + frameBytes_ <= bytes) {
            if (gain_ <= 0.f) break;
            for (int c = 0; c < channels_; ++c) {
                const int o = c * subframeSize_;
                const int32_t v = loadSample(lastFrame_ + o, subframeSize_);
                storeSample(dst + off + o, subframeSize_,
                            static_cast<int32_t>(static_cast<float>(v) * gain_));
            }
            gain_ -= gainStep_;
            off += frameBytes_;
        }
        if (gain_ < 0.f) gain_ = 0.f;
        if (off < bytes) {
            std::memset(dst + off, 0, static_cast<size_t>(bytes - off));
        }
    }

    /** 把真实音频的头几帧从 0 升到 1。只碰 got 范围内的完整帧 */
    void fadeInOn(uint8_t* dst, int validBytes) {
        for (int off = 0; off + frameBytes_ <= validBytes; off += frameBytes_) {
            if (gain_ >= 1.f) { gain_ = 1.f; return; }
            for (int c = 0; c < channels_; ++c) {
                const int o = c * subframeSize_;
                const int32_t v = loadSample(dst + off + o, subframeSize_);
                storeSample(dst + off + o, subframeSize_,
                            static_cast<int32_t>(static_cast<float>(v) * gain_));
            }
            gain_ += gainStep_;
        }
        if (gain_ > 1.f) gain_ = 1.f;
    }

    /**
     * 记住这一块**最后送出去的那一帧**。
     *
     * ★ 取的是 [dst, dst+bytes) 的尾巴 —— 欠载时尾巴是补的静音，
     *   那正好就是"最后送出去的是零"，语义是对的。
     */
    void rememberTail(const uint8_t* dst, int bytes) {
        if (!canFade() || bytes < frameBytes_) return;
        std::memcpy(lastFrame_, dst + bytes - frameBytes_,
                    static_cast<size_t>(frameBytes_));
    }

    /* 由 USB 提交线程调用。合并窗口内的连续欠载并成同一条记录。 */
    void recordUnderrun(size_t missing) {
        const uint64_t now =
            (writtenFrames_ != nullptr) ? writtenFrames_->load(std::memory_order_relaxed) : 0;

        if (eventCount_ > 0) {
            Event& last = events_[eventCount_ - 1];
            if (now >= last.atFrames && now - last.atFrames <= mergeWindowFrames_) {
                last.bytes += missing;
                return;
            }
        }
        if (eventCount_ >= kMaxEvents) {
            ++dropped_;
            return;
        }
        events_[eventCount_].atFrames = now;
        events_[eventCount_].bytes = missing;
        events_[eventCount_].baseMs =
            (seekBaseMs_ != nullptr) ? seekBaseMs_->load(std::memory_order_relaxed) : 0;
        // 记录时取当前时刻的间隔；同一事件后续的合并不会刷新它，
        // 所以这个值是"刚断供时的等待时长"，正是我们想知道的
        const uint64_t t = nowMs();
        events_[eventCount_].gapMs = (t > lastDataMs_) ? (t - lastDataMs_) : 0;
        events_[eventCount_].sinceStartMs = (t > startMs_) ? (t - startMs_) : 0;
        ++eventCount_;
    }

    static uint64_t nowMs() {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
    }

    RingBuffer* rb_ = nullptr;
    const std::atomic<bool>* decodeFinished_ = nullptr;
    const std::atomic<uint64_t>* writtenFrames_ = nullptr;
    const std::atomic<int64_t>* seekBaseMs_ = nullptr;
    const std::atomic<bool>* paused_ = nullptr;
    uint64_t mergeWindowFrames_ = 0;

    // ---- 输出线格式（淡入淡出要用）----
    static constexpr int kMaxFrameBytes = 8;   // 2ch × 4B；再宽就放弃淡变
    int channels_ = 2;
    int subframeSize_ = 2;
    int frameBytes_ = 4;
    int rampFrames_ = 240;                     // 5ms 的斜坡（setFormat 里按速率算）
    float gain_ = 1.f;                         // 当前增益：暂停时被拉到 0
    float gainStep_ = 1.f / 240.f;
    uint8_t lastFrame_[kMaxFrameBytes] = {0};  // 最后送出去的那一帧

    /* 计时：最近一次真正取到数据的时刻，用于量化解码线程被饿死多久 */
    uint64_t lastDataMs_ = 0;
    /* 会话时钟原点（本段首次 fill 的时刻） */
    uint64_t startMs_ = 0;
    bool everFilled_ = false;
    std::atomic<uint64_t> underrun_{0};
    std::atomic<uint64_t> pauseSilence_{0};
    /* 出环的真实音频字节（见 fill）。只统计，不参与任何逻辑 */
    std::atomic<uint64_t> consumedBytes_{0};

    Event events_[kMaxEvents];
    int eventCount_ = 0;
    uint64_t dropped_ = 0;
};

}  // namespace hifi
