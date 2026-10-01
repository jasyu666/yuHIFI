#include "system_audio_sink.h"

#include <android/log.h>

#include <cstring>
#include <sstream>

#define LOG_TAG "HiFiSysAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hifi {

namespace {

/*
 * subframeSize → AAudio 格式。
 *
 * ★ 这里**不做任何转换**，因为布局本来就一样：
 *   `PcmSource::fill` 吐的是声道交织、小端、每样本 subframeSize 字节的整数 PCM，
 *   而 AAudio 的 PCM_I24_PACKED 就是"3 字节小端"，PCM_I32 就是"4 字节小端"。
 *   转换反而会引入失真。
 *
 * 1 字节（8bit）AAudio 没有对应格式，兜底到 I16 —— 但引擎那条路
 * 从来不会产出 subframe=1（位深 16/24/32 对应 2/3/4），属于防御。
 */
aaudio_format_t formatFor(int subframeSize) {
    switch (subframeSize) {
        case 3:  return AAUDIO_FORMAT_PCM_I24_PACKED;
        case 4:  return AAUDIO_FORMAT_PCM_I32;
        case 2:
        default: return AAUDIO_FORMAT_PCM_I16;
    }
}

const char* formatName(aaudio_format_t f) {
    switch (f) {
        case AAUDIO_FORMAT_PCM_I16:        return "I16";
        case AAUDIO_FORMAT_PCM_I24_PACKED: return "I24_PACKED";
        case AAUDIO_FORMAT_PCM_I32:        return "I32";
        case AAUDIO_FORMAT_PCM_FLOAT:      return "FLOAT";
        default:                           return "?";
    }
}

}  // namespace

SystemAudioSink::~SystemAudioSink() {
    stop();
}

int SystemAudioSink::probeSampleRate() {
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK || b == nullptr) {
        LOGW("探测采样率：createStreamBuilder 失败");
        return 0;
    }
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    // 不指定速率 —— 让系统给它的默认值，那才是设备真正在跑的速率
    AAudioStreamBuilder_setSampleRate(b, AAUDIO_UNSPECIFIED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, 2);

    AAudioStream* s = nullptr;
    const aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);

    if (r != AAUDIO_OK || s == nullptr) {
        LOGW("探测采样率：openStream 失败 %s", AAudio_convertResultToText(r));
        return 0;
    }
    const int rate = AAudioStream_getSampleRate(s);
    const int perf = AAudioStream_getPerformanceMode(s);
    AAudioStream_close(s);

    LOGI("系统音频探测：默认速率 %d Hz（performanceMode=%d）", rate, perf);
    return rate > 0 ? rate : 0;
}

bool SystemAudioSink::start(PcmSource* source, int sampleRate, int channels,
                            int subframeSize, std::string* err) {
    auto fail = [&](const std::string& m) {
        if (err) *err = m;
        LOGE("系统音频启动失败: %s", m.c_str());
        // ★ 收尾交给 stop()：它在锁里摘流、在**锁外**关流，不会阻塞
        stop();
        return false;
    };

    if (source == nullptr) return fail("数据源为空");
    if (sampleRate <= 0 || channels <= 0 || subframeSize <= 0) {
        return fail("参数非法");
    }
    if (running_.load()) return fail("已经在跑");

    /*
     * ★★ ① 锁里只做**不阻塞**的事：摘掉旧流、记参数、取生成号。
     *
     *    真正会阻塞的 openStream 在下面**锁外**做 —— 这就是这块代码的要点。
     *    原来是一把锁包住整个 start()，于是 openStream 卡多久锁就被攥多久：
     *    stop() 卡、诊断卡、下一次起播也卡。实测就是这么把整条播放链路
     *    卡死的（USB 拔插那一下它再也没返回过）。
     */
    AAudioStream* old = nullptr;
    uint64_t gen = 0;
    {
        std::lock_guard<std::mutex> lk(lifecycleMutex_);
        old = detachLocked_();
        gen = ++generation_;

        source_ = source;
        channels_ = channels;
        frameBytes_ = channels * subframeSize;
        subframe_ = subframeSize;
        sampleRate_ = sampleRate;
        // 新流一开始当然是"没断开"的
        disconnected_.store(false, std::memory_order_release);
        callbacks_.store(0);
        framesFilled_.store(0);
        silenceBytes_.store(0);
        errors_.store(0);
        lastError_.store(0);
    }
    closeStream_(old);       // ★ 锁外

    const aaudio_format_t fmt = formatFor(subframeSize);

    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK || b == nullptr) {
        return fail("AAudio_createStreamBuilder 失败");
    }
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    /*
     * SHARED 是**故意的**：这条路本来就是"和大家一起用"，
     * 独占（EXCLUSIVE）是 USB 那条路的语义，不是这条。
     * 而且共享模式下 AudioFlinger 会帮我们做设备混音/重采样，
     * 这正是"不追求 hifi"的模式想要的。
     */
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(b, fmt);
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setSampleRate(b, sampleRate);
    AAudioStreamBuilder_setDataCallback(b, &SystemAudioSink::dataCallbackThunk, this);
    AAudioStreamBuilder_setErrorCallback(b, &SystemAudioSink::errorCallbackThunk, this);

    AAudioStream* s = nullptr;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || s == nullptr) {
        return fail(std::string("openStream 失败: ") + AAudio_convertResultToText(r));
    }

    /*
     * ★★ 必须核对系统实际给的速率。
     *
     *   AAudio 要不到你请求的速率时会**自己挑一个**（而且不报错）。
     *   引擎是按我们请求的速率重采样过的 —— 若实际速率不同，
     *   声音就会**变调**，而且听起来只是"怪怪的"，很难判断出是速率错。
     *   所以这里宁可失败，也不静默地播放变调的声音。
     */
    const int actualRate = AAudioStream_getSampleRate(s);
    if (actualRate != sampleRate) {
        AAudioStream_close(s);
        std::ostringstream os;
        os << "系统给的是 " << actualRate << "Hz，但我们要的是 " << sampleRate
           << "Hz —— 直接放会变调，所以停在这里。";
        return fail(os.str());
    }

    // requestStart 也会阻塞，同样放在锁外
    const aaudio_result_t started = AAudioStream_requestStart(s);
    if (started != AAUDIO_OK) {
        AAudioStream_close(s);
        return fail(std::string("requestStart 失败: ") + AAudio_convertResultToText(started));
    }

    /*
     * ★ ③ 回到锁里安装，**必须核对生成号**：开流这段时间里可能有人 stop() 了、
     *    或者又起了新的一条 —— 那这条就没人要了，原地关掉，别硬塞进去覆盖。
     *
     * ★ 数据回调从 requestStart 成功那一刻就开始跑了，而那时 stream_/running_
     *   还没装上。它只读 source_ / frameBytes_（上面锁里已经设好），所以没问题。
     */
    bool installed = false;
    {
        std::lock_guard<std::mutex> lk(lifecycleMutex_);
        if (generation_ == gen) {
            stream_ = s;
            running_.store(true, std::memory_order_release);
            installed = true;
        }
    }
    if (!installed) {
        closeStream_(s);
        return fail("启动期间被取代（有人停止、或又起了一条）");
    }

    LOGI("系统音频已启动: %dHz %dch %s (%d 字节/帧) 缓冲 %d 帧%s",
         sampleRate_, channels_, formatName(fmt), frameBytes_,
         AAudioStream_getBufferSizeInFrames(s),
         AAudioStream_getPerformanceMode(s) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
                 ? " · 低延迟" : "");
    return true;
}

void SystemAudioSink::closeStream_(AAudioStream* s) {
    if (s == nullptr) return;
    /*
     * 先 requestStop 再 close。直接 close 也能停，但 requestStop 会让
     * AAudio 等回调跑完再收 —— 避免回调正在用 source_ 时我们把状态拆掉。
     *
     * ★ close 本身会阻塞到流真正停下来，所以**调用方必须在锁外调这个函数**。
     */
    AAudioStream_requestStop(s);
    AAudioStream_close(s);
}

AAudioStream* SystemAudioSink::detachLocked_() {
    AAudioStream* s = stream_;
    stream_ = nullptr;
    source_ = nullptr;
    running_.store(false, std::memory_order_release);
    // 主动停掉不算"被系统掐了" —— 别让心跳误以为要重建
    disconnected_.store(false, std::memory_order_release);
    return s;
}

void SystemAudioSink::stop() {
    AAudioStream* s = nullptr;
    {
        std::lock_guard<std::mutex> lk(lifecycleMutex_);
        ++generation_;                 // 让在飞的 start/restart 开完就作废
        s = detachLocked_();
    }
    // ★ 关流在**锁外** —— 它会阻塞，攥着锁会把 stop/诊断/下一次起播一起拖死
    closeStream_(s);
}

std::string SystemAudioSink::restart() {
    PcmSource* src = nullptr;
    int rate = 0, ch = 0, sub = 0;
    {
        std::lock_guard<std::mutex> lk(lifecycleMutex_);
        src = source_;
        if (src == nullptr) return "没有可续播的数据源（会话已经收尾了）";
        rate = sampleRate_;
        ch = channels_;
        sub = subframe_;
    }

    /*
     * 统计是**整场会话**的账（欠载、补的静音、回调次数），
     * 中途重建一次不该把它清零 —— 否则报告会少报前半段。
     */
    const uint64_t cb = callbacks_.load();
    const uint64_t filled = framesFilled_.load();
    const uint64_t sil = silenceBytes_.load();
    const uint64_t errs = errors_.load();

    std::string err;
    // ★ 不用先 stop() —— start() 自己会在锁里摘掉旧流、在锁外关掉它
    if (!start(src, rate, ch, sub, &err)) return err;

    callbacks_.store(cb + callbacks_.load());
    framesFilled_.store(filled + framesFilled_.load());
    silenceBytes_.store(sil + silenceBytes_.load());
    errors_.store(errs + errors_.load());

    LOGI("系统音频输出已重建: %dHz %dch（引擎未重建，从当前位置继续）", rate, ch);
    return {};
}

void SystemAudioSink::onData(void* audioData, int32_t numFrames) {
    callbacks_.fetch_add(1, std::memory_order_relaxed);
    if (source_ == nullptr || numFrames <= 0) {
        std::memset(audioData, 0, static_cast<size_t>(numFrames) * frameBytes_);
        return;
    }

    /*
     * 一次问到底。
     *
     * `fill` 的契约是"写满 bytes 字节，不够的部分补静音并**立即返回**"——
     * 所以这里绝不会阻塞，也就不违反实时线程的规矩。
     * 欠载/暂停静音的记账**由数据源自己负责**（只有它知道补了多少静音、
     * 以及当时是不是暂停），这里不再记第二份 —— 两份账对不上过。
     */
    const size_t bytes = static_cast<size_t>(numFrames) * static_cast<size_t>(frameBytes_);
    const int valid = source_->fill(static_cast<uint8_t*>(audioData), static_cast<int>(bytes));
    if (valid > 0) {
        framesFilled_.fetch_add(static_cast<uint64_t>(valid / frameBytes_),
                                std::memory_order_relaxed);
    }
    if (valid < static_cast<int>(bytes)) {
        silenceBytes_.fetch_add(static_cast<uint64_t>(bytes - valid),
                                std::memory_order_relaxed);
    }
}

aaudio_data_callback_result_t SystemAudioSink::dataCallbackThunk(
        AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
    static_cast<SystemAudioSink*>(userData)->onData(audioData, numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void SystemAudioSink::errorCallbackThunk(AAudioStream*, void* userData,
                                         aaudio_result_t error) {
    auto* self = static_cast<SystemAudioSink*>(userData);
    self->errors_.fetch_add(1, std::memory_order_relaxed);
    self->lastError_.store(static_cast<int>(error), std::memory_order_relaxed);

    /*
     * ★★ 这里**只做两次原子写，绝不碰流**。
     *
     *   AAudio 明确规定：error 回调里不能 stop/close 流 —— 那是在它自己的
     *   线程上回调进来的，关流会死锁。所以这里只留一个标志，
     *   由 Java 侧那 200ms 心跳发现后**把会话停下来并告知用户**。
     *
     *   · running_ = false —— 别再让 isPlaying() 撒谎。
     *     原来没人清它，于是流已经死了、nativeIsPlaying() 还一路返回 true，
     *     引擎对着一条死流继续灌数据：界面显示"正在播放"、进度条不动、
     *     一个字的声音都没有，只能重启 App。
     *
     *   · disconnected_ —— 交给心跳去收尾。
     *
     *   ★ 这个回调**必然会走到**：插拔 USB 解码器、直连↔系统音频互相切换，
     *     任何一次音频路由变化 AAudio 都会掐掉现有流。
     */
    self->running_.store(false, std::memory_order_release);
    self->disconnected_.store(true, std::memory_order_release);

    LOGW("AAudio 错误回调: %s（输出已失效，等待会话收尾）", AAudio_convertResultToText(error));
}

std::string SystemAudioSink::statsReport() const {
    std::ostringstream os;
    if (callbacks_.load() == 0) return {};

    const uint64_t filled = framesFilled_.load();
    os << "系统音频: " << sampleRate_ << "Hz " << channels_ << "ch"
       << "  回调 " << callbacks_.load() << " 次"
       << "  送出的音频 " << filled << " 帧";
    if (sampleRate_ > 0) {
        os << "（" << (filled / static_cast<uint64_t>(sampleRate_)) << "."
           << ((filled % static_cast<uint64_t>(sampleRate_)) * 10 /
               static_cast<uint64_t>(sampleRate_)) << " 秒）";
    }
    os << "\n";
    if (disconnected_.load()) {
        os << "  ⚠ 输出流被系统断开，尚待重建（报告里会少算重建之后的账）\n";
    }
    const uint64_t sil = silenceBytes_.load();
    if (sil > 0) {
        os << "  其中补的静音 " << sil << " 字节\n";
    }
    const int errs = errors_.load();
    if (errs > 0) {
        os << "  ⚠ AAudio 错误 " << errs << " 次，最后一个是 "
           << AAudio_convertResultToText(static_cast<aaudio_result_t>(lastError_.load()))
           << "\n";
    }
    os << "  ★ 这条路**不是 bit-perfect**：中间经过 AudioFlinger，"
          "蓝牙时还有一层有损编码\n";
    return os.str();
}

}  // namespace hifi
