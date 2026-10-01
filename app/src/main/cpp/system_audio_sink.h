#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

#include "pcm_source.h"

namespace hifi {

/*
 * 系统音频输出（AAudio）—— **非 bit-perfect** 那条路。
 *
 * ★★ 为什么要它
 *
 *   在那之前只有"独占 USB"一条路：没插小尾巴就什么都放不了，
 *   插了耳机也只能走小尾巴。这个 sink 让声音走 Android 的常规音频通路
 *   （蓝牙 / 普通有线 / 扬声器）。代价是**不是 bit-perfect** ——
 *   中间有 AudioFlinger 的混音，蓝牙时还有一层**真有损的编码**（SBC/AAC/LDAC）。
 *
 * ★★ 为什么能这么省事
 *
 *   它消费的是**同一个** `PcmSource` —— 也就是 `AudioEngine` 的环形缓冲。
 *   于是解码线程、环形缓冲、重采样器、欠载记账**全部照用**，
 *   这里只是在另一端把 `fill()` 出来的字节交给 AAudio，而不是塞进 USB URB。
 *
 *   ★ 线格式也完全一致：`fill()` 吐的就是"声道交织、小端、每样本 subframeSize 字节"
 *     的整数 PCM，而 AAudio 的 `PCM_I16` / `PCM_I24_PACKED` / `PCM_I32`
 *     正好就是这个布局 —— **一个字节都不用转，也就不会引入任何失真**。
 *
 * ★ 线程：AAudio 的 data callback 跑在它自己的高优先级线程上。和 USB 那条路
 *   一样的铁律：**绝不阻塞、绝不分配内存**。`fill()` 的契约（不够就补静音、
 *   立即返回）正好满足。
 *
 * ★ 和 `IsoPlayer` 的关系：不是替代，是并列的另一个输出端。同一次播放只会
 *   起其中一个 —— 由 `PlayerSession` 按输出模式决定。
 */
class SystemAudioSink {
public:
    ~SystemAudioSink();

    /*
     * 打开并启动。
     *
     * @param sampleRate   **必须**是已经问过系统的速率
     *                     （`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`）——
     *                     引擎已经按这个速率重采样过了。给错了就是变调。
     * @param subframeSize 每样本每声道字节数（2/3/4），决定 AAudio 的格式
     */
    bool start(PcmSource* source, int sampleRate, int channels, int subframeSize,
               std::string* err);

    void stop();

    bool isRunning() const { return running_.load(std::memory_order_acquire); }

    /*
     * ★★ 系统音频输出被系统掐掉了 —— **取走这个标志（取走即清）**。
     *
     *   AAudio 在**音频路由变化**时必然会掐掉现有流：插拔 USB 解码器、
     *   直连↔系统音频互相切换都会触发。而 error 回调里**不能关流**
     *   （AAudio 明令禁止，会死锁），所以那边只记标志，
     *   由 Java 侧那 200ms 心跳发现后**把会话停下来并告知用户**。
     *
     *   不修的话就是用户报的那个症状：界面显示"正在播放"、进度条不动、
     *   一个字的声音都没有，只能重启 App。
     */
    bool takeDisconnected() {
        return disconnected_.exchange(false, std::memory_order_acq_rel);
    }

    /**
     * 流被掐之后重开一条。
     *
     * ★ **引擎和环形缓冲都不动** —— 解码线程还在、播放位置还在，
     *   所以是"从当前位置接着放"，不是从头重来。
     *
     * ★★ 它曾经被删过一次，因为它把 worker 线程卡死了：`openStream` 会阻塞，
     *   而那时它既跑在 worker 上、又攥着 `lifecycleMutex_`。现在两条都解掉了
     *   （见下面 [lifecycleMutex_] 的说明），但**调用方仍然必须把它放在
     *   一条专用线程上** —— 这条 API 只是"不再拖累别人"，不是"不会卡"。
     *
     * @return 空串表示成功，否则是失败原因
     */
    std::string restart();

    std::string statsReport() const;

    /*
     * 探测系统实际会给的采样率。
     *
     * 开一个临时流、读回 `AAudioStream_getSampleRate`、立刻关掉。
     * **必须在打开音频文件之前调** —— 引擎的速率决策发生在 open() 里面，
     * 而它要先知道目标速率。
     *
     * 失败返回 0（调用方回落到 48000）。
     */
    static int probeSampleRate();

private:
    static aaudio_data_callback_result_t dataCallbackThunk(
            AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
    static void errorCallbackThunk(AAudioStream* stream, void* userData,
                                   aaudio_result_t error);

    void onData(void* audioData, int32_t numFrames);

    /**
     * 摘掉当前流，把所有权交出来。**调用方必须持有 [lifecycleMutex_]**。
     *
     * ★ 它**只做不阻塞的事**：把成员清掉、把流指针交出去，然后就返回。
     *   真正会阻塞的 `requestStop`/`close` 由调用方在**锁外**做
     *   （见 [closeStream_]）。
     */
    AAudioStream* detachLocked_();

    /**
     * 关掉一条流。**必须在锁外调** —— requestStop / close 都会阻塞到流真正停下。
     *
     * ★★ 这条规矩是整块代码的要点：**凡是可能阻塞的 AAudio 调用，一律不持锁**。
     *   否则一次卡住的 open 会把 stop()、isRunning()、下一次 start 全部拖死 ——
     *   实测就是这么把整条播放链路卡住的。
     */
    static void closeStream_(AAudioStream* s);

    AAudioStream* stream_ = nullptr;
    PcmSource* source_ = nullptr;

    /**
     * 只保护**成员变量的改动**，绝不覆盖任何阻塞调用。
     *
     * ★★ 原来是"一把锁包住整个 start()"，于是 `openStream` 在里面阻塞多久，
     *   锁就被攥多久 —— stop() 也卡、诊断也卡、下一次起播也卡。
     *   现在开流/关流都在锁外，锁里只做几次赋值。
     */
    std::mutex lifecycleMutex_;

    /**
     * 每停一次 / 每起一次都 +1。
     *
     * ★ 用来作废"在飞的那次 open"：开流期间如果有人 stop() 了（或者又起了
     *   一条新的），等它回来时会发现生成号变了，于是把刚打开的流原地关掉，
     *   而不是硬塞进去覆盖别人。
     */
    uint64_t generation_ = 0;

    std::atomic<bool> running_{false};

    int channels_ = 2;
    int frameBytes_ = 4;
    int sampleRate_ = 48000;
    /** 每样本每声道字节数。restart() 要原样传回去，所以得记着 */
    int subframe_ = 2;

    /** 见 [takeDisconnected]。**只由 error 回调置位**，start/stop 时清 */
    std::atomic<bool> disconnected_{false};

    // ---- 统计（只读，供报告）----
    std::atomic<uint64_t> callbacks_{0};
    std::atomic<uint64_t> framesFilled_{0};   // 真正取到音频的帧数
    std::atomic<uint64_t> silenceBytes_{0};   // 补进去的静音字节
    std::atomic<int> errors_{0};
    std::atomic<int> lastError_{0};
};

}  // namespace hifi
