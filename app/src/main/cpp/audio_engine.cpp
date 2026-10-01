#include "audio_engine.h"

#include <android/log.h>
#include <unistd.h>

#include <chrono>
#include <cstring>
#include <sstream>

#include "ffmpeg_decoder.h"
#include "format_convert.h"
#include "rate_policy.h"
#include "thread_priority.h"

#define LOG_TAG "HiFiEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hifi {

namespace {
using namespace std::chrono_literals;

/* 解码一次读取的帧数。4096 帧在 44.1kHz 下约 93ms，粒度合适。 */
constexpr int kDecodeChunkFrames = 4096;

/* 缓冲目标水位：约 1 秒的数据 */
constexpr int kTargetBufferSeconds = 1;

/*
 * DSD 每次从文件读多少字节。
 *
 * 取 64KB：DsdReader 内部是按"块组"（channels × 4096 = 8KB）为单位 pread 的，
 * 一次要 64KB 就是 8 个块组 —— 比一次要 8KB 少 8 倍的 pread 调用。
 * 64KB ÷ 8 字节/帧 = 8192 帧 ≈ 23ms @352800Hz，粒度也够细。
 */
constexpr int kDsdChunkBytes = 64 * 1024;
}  // namespace

AudioEngine::AudioEngine() {
    // 让数据源能回答「是否真的放完了」—— IsoPlayer 靠它在送完最后一帧后
    // 主动停止，而不是继续按 microframe 节奏喂静音
    ringSource_.setDecodeFinishedFlag(&finished_);
    // 欠载事件要记「发生在第几秒」，得能读到输出侧帧计数
    ringSource_.setOutputFrameCounter(&writtenFrames_);
    // 事件还要带上当时的 seek 基准，否则 seek 之后位置解读会全部错乱
    ringSource_.setSeekBaseProvider(&seekBaseMs_);
    // 暂停期间的静音是故意的，不该算欠载
    ringSource_.setPausedFlag(&paused_);
}

AudioEngine::~AudioEngine() {
    close();
}

bool AudioEngine::open(int fd, std::string* err) {
    close();

    /*
     * ---- 先分流：是不是 DSD ----
     *
     * 只看文件头 4 字节：`DSD ` = DSF，`FRM8` = DFF。
     *
     * ★ 必须在**这里**就分开。FFmpeg 那份构建里 DSD 解码器是关掉的
     *   （`CONFIG_DSD_*_DECODER=0`、`CONFIG_DSF_DEMUXER=0`），
     *   而且就算打开也没用 —— 它内部是 `dsd2pcm`（48 抽头 FIR + 8:1 抽取），
     *   交出的是**有损 PCM**，而 native DSD 要的恰恰是**裸位流**。
     */
    uint8_t head[4] = {0};
    if (::pread(fd, head, 4, 0) == 4 && DsdReader::looksLikeDsd(head, 4)) {
        return openDsd(fd, err);
    }

    auto dec = std::make_unique<FFmpegDecoder>();
    if (!dec->open(fd, err)) {
        ::close(fd);   // 所有权已转移给引擎，失败时由我们关掉
        return false;
    }

    fd_ = fd;
    decoder_ = std::move(dec);
    sourceRate_ = decoder_->sampleRate();
    channels_ = decoder_->channels();
    sourceBits_ = decoder_->bitsPerSample();
    durationMs_ = decoder_->durationMs();
    codecName_ = static_cast<FFmpegDecoder*>(decoder_.get())->codecName();
    formatName_ = static_cast<FFmpegDecoder*>(decoder_.get())->formatName();

    /*
     * ---- 速率决策 ----
     *
     * ★ 把**设备自己声明的速率表**一起传进去：设备报了就以它为准，
     *   没报（像 Dawn Pro 那种截断描述符）才回落到白名单。见 rate_policy.h。
     */
    /*
     * ★★ 系统音频模式下，目标速率由**系统**说了算，不是设备描述符。
     *
     *   把"只支持 systemRate_ 这一个速率"包装成一份设备能力传给同一个决策函数，
     *   正好得到要的语义：
     *     源速率 == 系统速率 → 直通（一个采样点都不碰）
     *     否则                → 重采样到系统速率
     *
     *   ★ 不能省这一步。AAudio 只按系统速率跑，喂进去别的速率就是**变调**，
     *     而且听起来只是"怪怪的"，很难判断出是速率不对。
     */
    RateDecision rd;
    if (systemRate_ > 0) {
        DeviceRateCaps sysCaps;
        sysCaps.hasInfo = true;
        sysCaps.discrete = {systemRate_};
        rd = decideOutputRate(sourceRate_, sysCaps);
    } else {
        rd = decideOutputRate(sourceRate_, deviceCaps_);
    }
    outputRate_ = rd.outputRate;

    // 相距 100ms 以内的欠载算同一次卡顿 —— 缓冲见底时 USB 线程会按 URB
    // 一连补几十次静音，不合并的话一次卡顿会被拆成几十条记录
    ringSource_.setMergeWindowFrames(static_cast<uint64_t>(outputRate_) / 10);
    ringSource_.resetFade();

    if (rd.resample) {
        if (!resampler_.init(sourceRate_, outputRate_, channels_, err)) {
            decoder_->close();
            decoder_.reset();
            return false;
        }
    }
    // 直通时完全不建重采样器 —— 一个采样点都不碰

    /*
     * 交叉馈送按**输出速率**准备 —— 延迟线的长度和低通系数都跟着它算。
     * 单声道 / 非立体声会在这里被拒（active() 变 false），调用方不必再判。
     *
     * ★ 放在重采样之后的原因：它在**输出域**工作，参数直接对应送到 DAC 的
     *   那个采样率，不用去管源文件是多少。
     */
    crossfeed_.configure(outputRate_, channels_);

    // ---- 默认线格式：与源位深匹配，保证无损 ----
    if (sourceBits_ > 24) {
        subframeSize_ = 4; bitResolution_ = 32;
    } else if (sourceBits_ > 16) {
        subframeSize_ = 3; bitResolution_ = 24;
    } else {
        subframeSize_ = 2; bitResolution_ = 16;
    }

    /*
     * ★★ 音量控制开着时，把输出位深**提到 24bit** —— 这是软件音量不丢有效位的前提。
     *
     *   内部格式是「源样本左对齐到 32 位」：16bit 源 `<< 16` 之后
     *   **低 16 位本来就是 0**。提到 24bit 输出后右移的是 8 位，
     *   衰减先吃掉那 8 位空位 —— **−48dB 以内不丢任何有效位**。
     *
     *   ★ 只有源低于 24bit 时才需要提；源本来就是 24/32bit 的话没有余量，
     *     衰减会丢位（但那是数学上无法回避的，降音量就是降信息量）。
     */
    if (volumeEnabled_.load() && bitResolution_ < 24) {
        subframeSize_ = 3; bitResolution_ = 24;
        LOGI("音量控制已开：线格式由 %dbit 提到 24bit（给衰减留 8 位余量）", sourceBits_);
    }
    frameBytes_ = channels_ * subframeSize_;
    // ★ 凡是改 frameBytes_ 的地方都必须跟着同步给数据源 —— 淡入淡出按样本做
    ringSource_.setFormat(channels_, subframeSize_, outputRate_);

    LOGI("open: %s/%s %dHz %dch %dbit -> 输出 %dHz %s%s",
         formatName_, codecName_, sourceRate_, channels_, sourceBits_,
         outputRate_, rd.resample ? "(重采样)" : "(直通)",
         systemRate_ > 0 ? "  [系统音频·非 bit-perfect]" : "  [USB 直连]");
    return true;
}

/*
 * DSD 分流。
 *
 * ★★ 和 PCM 路径最大的不同：**不走速率决策**。
 *
 *   `decideOutputRate` 是给 PCM 用的 —— 它的白名单里根本没有 705600
 *   （DSD512），碰到就会"重采样到最近的 48k 家族"。而 DSD 位流**不能那样重采样**，
 *   那会把数据彻底毁掉。DSD 的合法速率只有一个值：**位率 ÷ 32**，
 *   直接透传，没有第二选择。
 *
 * ★ 线格式固定为 2ch × 4 字节 = `DSD_U32_BE`，和接口 1 alt 4 的描述符一致
 *   （`bSubslotSize=4`、`bBitResolution=32`）。
 */
bool AudioEngine::openDsd(int fd, std::string* err) {
    auto r = DsdReader::open(fd, err);
    if (!r) {
        ::close(fd);   // 所有权已转移给引擎，失败时由我们关
        return false;
    }

    fd_ = fd;
    dsd_ = std::move(r);

    channels_ = dsd_->channels();
    sourceRate_ = dsd_->wordRate();     // 要请求给设备的速率 = 位率 ÷ 32
    outputRate_ = sourceRate_;          // 直通，无重采样
    sourceBits_ = 1;                    // DSD 本来就是 1 bit
    durationMs_ = dsd_->durationMs();
    codecName_ = "DSD";
    formatName_ = dsd_->formatName();

    // 环形缓冲的水位要按输出速率折算，和 PCM 路径一致
    ringSource_.setMergeWindowFrames(static_cast<uint64_t>(outputRate_) / 10);
    ringSource_.resetFade();

    // 线格式：DSD_U32_BE，2ch × 4 字节
    subframeSize_ = 4;
    bitResolution_ = 32;
    frameBytes_ = channels_ * subframeSize_;
    // ★ 凡是改 frameBytes_ 的地方都必须跟着同步给数据源 —— 淡入淡出按样本做
    ringSource_.setFormat(channels_, subframeSize_, outputRate_);

    // ★ 重采样器**不建** —— DSD 那条路上它必须保持 inactive
    //   （ratePolicyNote / openReport 都靠 resampler_.active() 判断）

    LOGI("open(DSD): %s %dch 位率 %dHz -> 请求 %dHz（直通，无重采样），时长 %lld ms",
         formatName_, channels_, dsd_->bitRate(), outputRate_,
         static_cast<long long>(durationMs_));
    return true;
}

void AudioEngine::close() {
    stopDecoding();
    ring_.clear();
    ringSource_.resetUnderrun();
    ringSource_.clearEvents();
    resampler_.close();
    // 延迟线按输出速率分配过，尺寸可能不小，析构时用 swap 真正还回去
    crossfeed_.close();
    if (decoder_) {
        decoder_->close();
        decoder_.reset();
    }
    // DsdReader 不持有 fd（所有权归引擎），析构即可
    dsd_.reset();
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
    sourceRate_ = outputRate_ = channels_ = sourceBits_ = 0;
    durationMs_ = 0;
    codecName_ = formatName_ = "?";
    finished_.store(false);
    decodedFrames_.store(0);
    writtenBytes_.store(0);
    writtenFrames_.store(0);
    seekBaseMs_.store(0);
    seekCount_.store(0);
    flushDone_ = false;

    /*
     * ★★ `paused_` 是**会话级**状态，必须跟着一起清。
     *
     *   不清的话：在**暂停状态下点开另一首曲目** → open() 里第一步就是 close()，
     *   上面那些都清了、偏偏 paused_ 还是 true → 新曲目的解码线程一直
     *   `sleep(20ms)` 不产出，输出端也一直走「暂停静音」分支 →
     *   状态机报 PLAYING、界面显示正在播放，**实际全静音、位置恒为 0**
     *   （positionMs = written - buffered，没人从环形缓冲取）。
     *   用户看到的"再按一次暂停播放就好了"，就是那一下把标志翻了回来。
     *
     *   实测定位：/debug/state 报 state=PLAYING、positionMs=0 且连采 6 次
     *   一动不动；AudioTrack 那边 `f:0`（有内容的帧为 0）而 `z`（零数据秒数）
     *   一直在涨。见 JOURNAL 2026-09-25。
     */
    paused_.store(false, std::memory_order_relaxed);
}

std::string AudioEngine::ratePolicyNote() const {
    std::ostringstream os;

    // ★ DSD 走的是完全独立的一条路：不查速率表、不重采样、不转 PCM
    if (dsd_) {
        os << "DSD 直通（bit-perfect）—— 位率 " << dsd_->bitRate()
           << "Hz ÷ 32 = " << outputRate_ << "Hz，裸位流原样送进 raw DSD 通道。\n"
              "    不重采样、不转 PCM、不碰音量。";
        return os.str();
    }

    if (!resampler_.active()) {
        os << "直通（bit-perfect）—— " << sourceRate_ << "Hz 全程未做重采样";
    } else {
        os << "重采样 " << sourceRate_ << "Hz → " << outputRate_ << "Hz";
        // ★ 旧文案写的是"设备实测不支持 44.1k 家族" —— 那个结论 p18 已经推翻了
        //   （8 个标准速率全支持）。现在走重采样只可能是因为：源速率非标准，
        //   或者设备自己声明了速率表而这一档不在里面。
        os << "\n    原因：源速率不在设备支持范围内（非标准速率，"
              "或设备声明了速率表而这一档不在其中）";
    }
    return os.str();
}

void AudioEngine::fillOutputConfig(OutputConfig* cfg, int subframeSize, int bitResolution) {
    subframeSize_ = subframeSize;
    bitResolution_ = bitResolution;
    frameBytes_ = channels_ * subframeSize_;
    // ★ 凡是改 frameBytes_ 的地方都必须跟着同步给数据源 —— 淡入淡出按样本做
    ringSource_.setFormat(channels_, subframeSize_, outputRate_);

    cfg->sampleRate = outputRate_;
    cfg->channels = channels_;
    cfg->subframeSize = subframeSize_;
    cfg->bitResolution = bitResolution_;
}

/*
 * 缓冲目标水位。
 *
 * 名义上是「1 秒的数据」，但对高采样率必须封顶：384kHz/24bit/2ch 下单秒
 * 就是 2.3MB，比整个环形缓冲（2MiB）还大。此时若仍按 1 秒去灌，预灌循环的
 * 退出条件永远不成立，而那一刻 USB 线程还没启动、没人在消费，
 * writeToRing 会一直写不进去并空转 —— 现象是点下播放后整个界面卡死。
 *
 * 封顶到容量的一半：既保证预灌一定结束得了，又给解码线程留出余量，
 * 不至于刚灌满就被下一次写入顶到边界。
 *
 * 环形缓冲容量本身由 RingBuffer 向上取整到 2 的幂，所以 capacity() 一定是
 * 2 的幂，取一半不会浪费太多。
 */
size_t AudioEngine::targetBufferBytes() const {
    const size_t oneSecond = static_cast<size_t>(outputRate_) *
                             static_cast<size_t>(frameBytes_) *
                             kTargetBufferSeconds;
    const size_t cap = ring_.capacity() / 2;
    return (oneSecond < cap) ? oneSecond : cap;
}

bool AudioEngine::startDecoding() {
    if (!decoder_ && !dsd_) return false;
    if (decodeThread_.joinable()) return true;

    /*
     * 清停止标志必须放在预灌**之前**。
     *
     * stopRequested_ 经 open() → close() → stopDecoding() 之后会残留为 true
     * （stopDecoding 无条件置位，close 不会复位它）。而 writeToRing() 的循环
     * 条件里带着这个标志 —— 不清的话预灌第一轮就让 writeToRing 直接返回 false
     * 并 break：那一块 4096 帧已经被解码器消费掉、却一个字都没写进缓冲，**凭空消失**。
     *
     * 实测后果有两个，都很难察觉：
     *   · 每次播放都从第 93ms 开始（4096 帧 ÷ 44100），开头被静默切掉
     *   · 预灌形同虚设，起播时缓冲是空的，USB 流一起来就先欠载
     */
    stopRequested_.store(false);
    finished_.store(false);

    // 起播前先灌满目标水位再启动 USB 流，避免开头就 underrun。
    // 这里同步解一小段，代价是起播多等约 1 秒（高采样率下按封顶值，更短）。
    const size_t targetBytes = targetBufferBytes();

    /*
     * ★ DSD 的预灌走一条独立的小循环。
     *
     *   不复用下面那段：它夹在解码器和重采样器之间（readFrames → swr → 线格式），
     *   而 DSD 那边根本没有前两者 —— DsdReader 直接吐出的就是线格式字节。
     */
    if (dsd_) {
        std::vector<uint8_t> dsdBuf(kDsdChunkBytes);
        while (ring_.available() < targetBytes) {
            const int got = dsd_->read(dsdBuf.data(), kDsdChunkBytes);
            if (got <= 0) break;                       // 文件很短，灌不满就算了
            decodedFrames_.fetch_add(static_cast<uint64_t>(got) / frameBytes_,
                                     std::memory_order_relaxed);
            if (!writeToRing(dsdBuf.data(), static_cast<size_t>(got))) break;
        }
        LOGI("起播预灌(DSD) %zu 字节（目标 %zu）", ring_.available(), targetBytes);
        decodeThread_ = std::thread(&AudioEngine::decodeLoopDsd, this);
        return true;
    }

    std::vector<int32_t> srcBuf(static_cast<size_t>(kDecodeChunkFrames) * channels_);
    const int rsCap = static_cast<int>(kDecodeChunkFrames * resampler_.ratio()) + 4096;
    std::vector<int32_t> rsBuf(static_cast<size_t>(rsCap) * channels_);
    std::vector<uint8_t> wireBuf;

    while (ring_.available() < targetBytes) {
        const int got = decoder_->readFrames(srcBuf.data(), kDecodeChunkFrames);
        if (got <= 0) break;   // 文件很短，灌不满就算了

        // 预灌也是从解码器取走的帧，必须计入 —— 漏掉的话 decodedFrames_ 会
        // 恒定少掉预灌那一截（约 11 块 = 45,056 帧 = 1 秒），
        // 于是「差额 = 写入缓冲 − 解码帧数×比率」永远多出 1 秒，校验形同虚设。
        decodedFrames_.fetch_add(static_cast<uint64_t>(got), std::memory_order_relaxed);

        // ★ 非 const：交叉馈送要**原地**改这段样本（见下）
        int32_t* p = srcBuf.data();
        int frames = got;
        if (resampler_.active()) {
            frames = resampler_.process(p, got, rsBuf.data(), rsCap);
            p = rsBuf.data();
        }
        if (frames <= 0) continue;

        /*
         * 交叉馈送 —— 在**输出速率域**、转线格式之前。
         *
         * ★★ **起播预灌和 decodeLoop 是两条独立的路径，两处都必须调。**
         *    只改 decodeLoop 的话，每首歌**开头那一秒**（预灌的那段）
         *    不会被处理 —— 开关刚打开时听感上就是"第一秒没效果"。
         *
         * ★ 原地改是安全的：p 指向的 srcBuf / rsBuf 都是本循环的局部缓冲，
         *   下一轮会整个重填，没有别人依赖旧内容。
         *
         * ★ 这里不判开关 —— process() 自己会看 enabled()，关着时立刻返回。
         */
        crossfeed_.process(p, frames);

        /*
         * 软件音量。★ 和交叉馈送同一个位置（输出速率域、转线格式之前）。
         *   增益 == 0dB 时 applyGain 直接返回，连循环都不进。
         */
        applyGain(p, frames);

        const size_t bytes = static_cast<size_t>(frames) * frameBytes_;
        wireBuf.resize(bytes);
        convertS32ToWire(p, frames, channels_, subframeSize_, bitResolution_, wireBuf.data());
        if (!writeToRing(wireBuf.data(), bytes)) break;
    }

    LOGI("起播预灌 %zu 字节（目标 %zu）", ring_.available(), targetBytes);

    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
    return true;
}

void AudioEngine::stopDecoding() {
    stopRequested_.store(true);
    if (decodeThread_.joinable()) decodeThread_.join();
}

/*
 * 冲刷重采样器。
 *
 * swr_convert 传 NULL 输入即进入冲刷模式，把内部延迟线里的样本吐出来。
 * 可能要调用多次才能吐干净（每次受 maxOutFrames 限制），所以这里循环到
 * 返回 0 为止。次数上限只是防御 —— 正常情况下一次就完了。
 */
void AudioEngine::flushResampler() {
    constexpr int kFlushFrames = 8192;
    std::vector<int32_t> rsBuf(static_cast<size_t>(kFlushFrames) * channels_);
    std::vector<uint8_t> wireBuf;

    uint64_t total = 0;
    for (int pass = 0; pass < 8; ++pass) {
        const int got = resampler_.flush(rsBuf.data(), kFlushFrames);
        if (got <= 0) break;

        const size_t bytes = static_cast<size_t>(got) * frameBytes_;
        wireBuf.resize(bytes);
        convertS32ToWire(rsBuf.data(), got, channels_, subframeSize_,
                         bitResolution_, wireBuf.data());
        if (!writeToRing(wireBuf.data(), bytes)) break;
        total += static_cast<uint64_t>(got);
    }
    if (total > 0) {
        LOGI("重采样器冲刷出 %llu 帧（%.1f ms）",
             static_cast<unsigned long long>(total),
             static_cast<double>(total) * 1000.0 / outputRate_);
    }
}

void AudioEngine::decodeLoop() {
    // 解码不必实时，但被饿死的代价是缓冲耗尽 —— 实测切屏时被挂起 1 秒以上，
    // 直接导致一段可闻的静音。提到 AUDIO 档，低于 USB 提交线程。
    setAudioThreadPriority(kPriorityAudio, "解码线程", kSlotDecode);

    std::vector<int32_t> srcBuf(static_cast<size_t>(kDecodeChunkFrames) * channels_);
    const int rsCap = static_cast<int>(kDecodeChunkFrames * resampler_.ratio()) + 4096;
    std::vector<int32_t> rsBuf(static_cast<size_t>(rsCap) * channels_);
    std::vector<uint8_t> wireBuf;

    const size_t targetBytes = targetBufferBytes();

    while (!stopRequested_.load(std::memory_order_acquire)) {
        // seek 请求优先于暂停：暂停时拖进度条也应该立刻生效
        const int64_t req = pendingSeekMs_.load(std::memory_order_acquire);
        if (req >= 0) {
            pendingSeekMs_.store(-1, std::memory_order_release);
            doSeek(req);
            seekDoneMs_.store(req, std::memory_order_release);
            continue;
        }

        if (paused_.load(std::memory_order_relaxed)) {
            std::this_thread::sleep_for(20ms);
            continue;
        }

        // 水位够了就歇着 —— 让缓冲维持在大约 1 秒，既不 underrun 也不会让
        // 暂停/seek 反应迟钝
        if (ring_.available() >= targetBytes) {
            std::this_thread::sleep_for(10ms);
            continue;
        }

        const int got = decoder_->readFrames(srcBuf.data(), kDecodeChunkFrames);
        if (got <= 0) {
            /*
             * 到文件末尾。**先把重采样器里积压的尾巴吐出来再宣告结束** ——
             * swresample 有内部滤波器延迟线，喂完最后一块输入时那部分还没
             * 变成输出，必须显式 flush 才会交还。不 flush 的话每首重采样的歌
             * 结尾都会少一截（实测 44.1k→48k 少了约 0.1 秒）。
             *
             * 只做一次：之后再把 flush 结果喂回去只会得到 0。
             */
            if (!flushDone_) {
                flushDone_ = true;
                if (resampler_.active()) flushResampler();
            }
            // 顺序要紧：flush 的输出先写进环形缓冲，再宣告结束。
            // 数据源的 finished() 要求「解码结束 **且** 缓冲排空」，
            // 所以刚写进去的尾巴不会被跳过。
            finished_.store(true, std::memory_order_release);
            std::this_thread::sleep_for(50ms);
            continue;
        }

        decodedFrames_.fetch_add(static_cast<uint64_t>(got), std::memory_order_relaxed);

        // ★ 非 const：交叉馈送要**原地**改这段样本（见下）
        int32_t* p = srcBuf.data();
        int frames = got;
        if (resampler_.active()) {
            frames = resampler_.process(p, got, rsBuf.data(), rsCap);
            p = rsBuf.data();
        }
        if (frames <= 0) continue;

        /*
         * 交叉馈送 —— 在**输出速率域**、转线格式之前。
         *
         * ★★ **起播预灌和 decodeLoop 是两条独立的路径，两处都必须调。**
         *    只改 decodeLoop 的话，每首歌**开头那一秒**（预灌的那段）
         *    不会被处理 —— 开关刚打开时听感上就是"第一秒没效果"。
         *
         * ★ 原地改是安全的：p 指向的 srcBuf / rsBuf 都是本循环的局部缓冲，
         *   下一轮会整个重填，没有别人依赖旧内容。
         *
         * ★ 这里不判开关 —— process() 自己会看 enabled()，关着时立刻返回。
         */
        crossfeed_.process(p, frames);

        /*
         * 软件音量。★ 和交叉馈送同一个位置（输出速率域、转线格式之前）。
         *   增益 == 0dB 时 applyGain 直接返回，连循环都不进。
         */
        applyGain(p, frames);

        const size_t bytes = static_cast<size_t>(frames) * frameBytes_;
        wireBuf.resize(bytes);
        convertS32ToWire(p, frames, channels_, subframeSize_, bitResolution_, wireBuf.data());

        if (!writeToRing(wireBuf.data(), bytes)) break;
    }
    LOGI("解码线程退出");
}

/*
 * DSD 的读取线程。
 *
 * 和 decodeLoop 的区别：没有解码器、没有重采样器、没有跨块的样本帧续传 ——
 * DsdReader 直接吐出的就是 `DSD_U32_BE` 线格式字节，写进环形缓冲即可。
 * 下游（RingBufferSource → IsoPlayer → URB）**一个字都不用改**。
 *
 * 水位/暂停/seek 那套逻辑和 PCM 路径保持一致，参数也共用。
 */
void AudioEngine::decodeLoopDsd() {
    setAudioThreadPriority(kPriorityAudio, "DSD 读取线程", kSlotDecode);

    const size_t targetBytes = targetBufferBytes();
    std::vector<uint8_t> buf(kDsdChunkBytes);

    while (!stopRequested_.load(std::memory_order_acquire)) {
        // seek 优先于暂停：暂停时拖进度条也应该立刻生效
        const int64_t req = pendingSeekMs_.load(std::memory_order_acquire);
        if (req >= 0) {
            pendingSeekMs_.store(-1, std::memory_order_release);
            doSeekDsd(req);
            seekDoneMs_.store(req, std::memory_order_release);
            continue;
        }

        if (paused_.load(std::memory_order_relaxed)) {
            std::this_thread::sleep_for(20ms);
            continue;
        }

        if (ring_.available() >= targetBytes) {
            std::this_thread::sleep_for(10ms);
            continue;
        }

        const int got = dsd_->read(buf.data(), kDsdChunkBytes);
        if (got <= 0) {
            finished_.store(true, std::memory_order_release);
            LOGI("DSD 读完：共 %llu 帧（%lld ms）",
                 static_cast<unsigned long long>(dsd_->framesRead()),
                 static_cast<long long>(durationMs_));
            break;
        }

        decodedFrames_.fetch_add(static_cast<uint64_t>(got) / frameBytes_,
                                 std::memory_order_relaxed);
        if (!writeToRing(buf.data(), static_cast<size_t>(got))) break;
    }
    LOGI("DSD 读取线程退出");
}

void AudioEngine::doSeekDsd(int64_t ms) {
    dsd_->seekToMs(ms);

    // 丢掉缓冲里尚未送出的旧数据。此刻 USB 线程可能正读到一半，
    // 它会通过 read() 里的 CAS 察觉并丢弃这一批 —— 听感是一次极短的咔哒。
    ring_.clear();

    // 位置基准改到目标处，两侧记账都清零（缓冲已清空）
    seekBaseMs_.store(ms, std::memory_order_relaxed);
    writtenFrames_.store(0, std::memory_order_relaxed);
    decodedFrames_.store(0, std::memory_order_relaxed);
    seekCount_.fetch_add(1, std::memory_order_relaxed);
    flushDone_ = false;   // DSD 没有重采样器，这只是保持状态一致
}

/*
 * 软件音量：原地乘一个增益。
 *
 * ★★ 增益 >= 1（0dB）时**直接返回，连循环都不进**。
 *    这样"开着音量控制但滑条在顶"和"没开音量控制"在数据上完全一致，
 *    用户不必担心"我开了这个功能会不会影响音质"。
 *
 * ★ 用 float 乘。内部样本是左对齐 32 位，float 有 24 位尾数 ——
 *   对 16bit 源（有效位在 bit31..bit16）绰绰有余；乘完低位小数也被保留，
 *   随后 convertS32ToWire 右移 8 位输出 24bit 时正好用上。
 *
 * ★ 满量程是 ±2³¹，乘以 <=1 的增益不会溢出，不需要 clamp。
 */
void AudioEngine::applyGain(int32_t* buf, int frames) {
    if (!volumeEnabled_.load(std::memory_order_relaxed)) return;

    const float g = gain_.load(std::memory_order_relaxed);
    if (g >= 0.999999f) return;          // 0dB：原样，一个样本都不碰

    const int total = frames * channels_;
    for (int i = 0; i < total; ++i) {
        buf[i] = static_cast<int32_t>(static_cast<float>(buf[i]) * g);
    }
}

bool AudioEngine::writeToRing(const uint8_t* data, size_t bytes) {
    size_t written = 0;
    while (written < bytes && !stopRequested_.load(std::memory_order_acquire)) {
        const size_t n = ring_.write(data + written, bytes - written);
        if (n == 0) {
            std::this_thread::sleep_for(5ms);
            continue;
        }
        written += n;
        writtenBytes_.fetch_add(n, std::memory_order_relaxed);
    }
    // 按输出帧记账（不是源帧）—— 位置计算只关心送出去多少时间
    if (frameBytes_ > 0) {
        writtenFrames_.fetch_add(written / static_cast<size_t>(frameBytes_),
                                 std::memory_order_relaxed);
    }
    return written == bytes;
}

/*
 * 解码线程侧的 seek 落地。只有它能碰解码器和重采样器。
 */
void AudioEngine::doSeek(int64_t ms) {
    if (dsd_) {
        doSeekDsd(ms);
        return;
    }
    if (!decoder_->seekToMs(ms)) {
        LOGW("seek 到 %lld ms 失败，位置保持不变", static_cast<long long>(ms));
        return;
    }

    // 丢掉缓冲里尚未送出的旧数据。此刻 USB 线程可能正读到一半，
    // 它会通过 read() 里的 CAS 察觉并丢弃这一批 —— 听感是一次极短的咔哒，
    // 拖进度条本来就有，可以接受。
    ring_.clear();

    // 重采样器内部也有状态（滤波器延迟线），重建它以彻底清干净。
    // 比 flush 更简单可靠，代价是一次性的滤波器预热。
    if (resampler_.active()) {
        std::string ignored;
        resampler_.init(sourceRate_, outputRate_, channels_, &ignored);
    }
    // 重建过的重采样器是干净的，末尾冲刷标志也要跟着复位 ——
    // 否则 seek 回中段后再播到结尾，那段尾巴就不会被吐出来了
    flushDone_ = false;

    /*
     * ★★ 交叉馈送的延迟线和低通状态**也必须清** —— 和上面 flushDone_
     *    是同一个道理。不清的话，seek 到新位置之后头几十个样本会混进
     *    **旧位置**的内容：延迟线很短，听感上就是"咔哒"一下，
     *    而且只在拖进度条时出现，极难定位。
     */
    crossfeed_.reset();

    // 位置基准改到目标处，并把两侧记账都清零 —— 缓冲已清空，
    // 此后写进去的帧全部属于新位置之后的音频。
    //
    // decodedFrames_ 也必须清零，否则「差额 = 写入缓冲 − 解码帧数×比率」
    // 这个校验会彻底失效：解码计数跨 seek 累加、输出计数却在 seek 时归零，
    // 两者相减毫无意义（实测报出过 -16340496 帧 / -340427 ms 的荒谬值）。
    // 现在它是**分段**统计，配合 seekCount 一起看。
    seekBaseMs_.store(ms, std::memory_order_relaxed);
    writtenFrames_.store(0, std::memory_order_relaxed);
    decodedFrames_.store(0, std::memory_order_relaxed);
    seekCount_.fetch_add(1, std::memory_order_relaxed);
    finished_.store(false, std::memory_order_release);
}

/*
 * 请求 seek。返回时位置可能尚未真正改变 —— 见下方说明。
 */
bool AudioEngine::seekToMs(int64_t ms) {
    if (!decoder_) return false;
    if (ms < 0) ms = 0;

    // 解码线程还没起来时没有竞争，直接同步做掉
    if (!decodeThread_.joinable()) {
        doSeek(ms);
        return true;
    }

    seekDoneMs_.store(-1, std::memory_order_relaxed);
    pendingSeekMs_.store(ms, std::memory_order_release);

    // 等解码线程接单。正常情况下它下一次循环就会看到（几十毫秒内），
    // 因为它每轮最多只解一个 4096 帧的块。
    //
    // 超时不报错：解码线程可能正卡在文件 I/O 上（网络存储、慢速 SD 卡），
    // 但请求已经排队，它回来时照样会执行。这里不值得为了一个进度条拖动
    // 把调用方拖住更久。
    for (int i = 0; i < 200; ++i) {
        if (seekDoneMs_.load(std::memory_order_acquire) == ms) return true;
        std::this_thread::sleep_for(1ms);
    }
    LOGW("seek 请求已排队但 %dms 内未完成，交由解码线程稍后执行", 200);
    return true;
}

int64_t AudioEngine::positionMs() const {
    if (outputRate_ <= 0 || frameBytes_ <= 0) return seekBaseMs_.load();

    const uint64_t written = writtenFrames_.load(std::memory_order_relaxed);
    const uint64_t buffered = static_cast<uint64_t>(ring_.available()) /
                              static_cast<uint64_t>(frameBytes_);
    // USB 线程可能刚刚又取走一点，导致 buffered > written 的瞬时假象
    const uint64_t played = (written > buffered) ? (written - buffered) : 0;

    const int64_t base = seekBaseMs_.load(std::memory_order_relaxed);
    const int64_t pos = base + static_cast<int64_t>(
        played * 1000ULL / static_cast<uint64_t>(outputRate_));

    // 到文件末尾且缓冲排空后，played 会略超过时长，夹住避免进度条越界
    if (durationMs_ > 0 && pos > durationMs_) return durationMs_;
    return pos;
}

double AudioEngine::bufferFillRatio() const {
    // 用与解码线程相同的目标值，否则高采样率下水位永远显示不到 100%
    const size_t target = targetBufferBytes();
    if (target == 0) return 0.0;
    return static_cast<double>(ring_.available()) / static_cast<double>(target);
}

std::string AudioEngine::openReport() const {
    std::ostringstream os;

    if (dsd_) {
        os << "格式: " << formatName_ << " / DSD（原生位流，**不转 PCM**）\n";
        os << "源: " << dsd_->bitRate() << "Hz 位率  " << channels_ << "ch  1bit\n";
        if (durationMs_ > 0) {
            os << "时长: " << (durationMs_ / 1000) << "."
               << ((durationMs_ % 1000) / 100) << " 秒\n";
        }
        os << "请求速率: " << outputRate_ << "Hz（= 位率 ÷ 32）\n";
        os << "速率策略: " << ratePolicyNote() << "\n";
        os << "线格式: DSD_U32_BE —— 每个 32-bit 字装 32 个 DSD 位（大端）\n";
        return os.str();
    }

    os << "格式: " << formatName_ << " / " << codecName_ << "\n";
    os << "源: " << sourceRate_ << "Hz  " << channels_ << "ch  "
       << sourceBits_ << "bit\n";
    if (durationMs_ > 0) {
        os << "时长: " << (durationMs_ / 1000) << "."
           << ((durationMs_ % 1000) / 100) << " 秒\n";
    }
    os << "输出速率: " << outputRate_ << "Hz\n";
    os << "速率策略: " << ratePolicyNote() << "\n";
    os << "线格式: " << bitResolution_ << "bit，子帧 " << subframeSize_ << " 字节\n";
    return os.str();
}

/*
 * 欠载事件清单。
 *
 * 用户能听出「卡了几次」，报告就必须能对上「第几秒、每次多长」——
 * 只有一个累计字节数时，两边根本没法核对（实测就卡在这一步：
 * 用户报 4 次卡顿，报告里只有一个 356 字节的总数，什么也说明不了）。
 */
std::string AudioEngine::underrunEventsReport() const {
    std::ostringstream os;
    const int n = ringSource_.eventCount();
    const uint64_t dropped = ringSource_.droppedEvents();

    if (n == 0 && dropped == 0) {
        os << "欠载事件: 无（缓冲全程没见底）\n";
        return os.str();
    }

    os << "欠载事件: " << (static_cast<uint64_t>(n) + dropped) << " 次\n";
    if (dropped > 0) {
        os << "  （只保留前 " << n << " 条，另有 " << dropped << " 条未记录）\n";
    }

    const double bytesPerMs = (outputRate_ > 0 && frameBytes_ > 0)
            ? static_cast<double>(outputRate_) * frameBytes_ / 1000.0
            : 0.0;

    /*
     * 位置用**事件自己记下的基准**算，不能用当前基准。
     *
     * 事件里的帧计数是「自记录那一刻的 seek 基准起算」的。若拿最后一次 seek
     * 的基准去解读所有历史事件，seek 之前那几条位置会全部错乱 ——
     * 实测 340 秒的歌报出过"第 634 秒"。
     */
    for (int i = 0; i < n; ++i) {
        const RingBufferSource::Event& e = ringSource_.event(i);
        os << "  #" << (i + 1) << "  第 ";
        if (outputRate_ > 0) {
            const double atSec =
                (static_cast<double>(e.baseMs) +
                 static_cast<double>(e.atFrames) * 1000.0 / outputRate_) / 1000.0;
            os << atSec;
        } else {
            os << "?";
        }
        os << " 秒   补静音 " << e.bytes << " 字节";
        if (bytesPerMs > 0) {
            os << " ≈ " << (static_cast<double>(e.bytes) / bytesPerMs) << " ms";
        }
        // 断供前的等待时长 —— 判断是不是解码线程被挂起的关键
        if (e.gapMs > 0) {
            os << "   断供前已等待 " << e.gapMs << " ms";
        }
        // 会话时钟：与日志里「离开应用 / 回到前台」两行对齐用
        os << "   [起播后 " << (e.sinceStartMs / 1000.0) << " 秒]\n";
    }
    return os.str();
}

std::string AudioEngine::statusReport() const {
    std::ostringstream os;
    os << "文件: " << formatName_ << " / " << codecName_ << "\n";
    os << "源: " << sourceRate_ << "Hz  " << channels_ << "ch  "
       << sourceBits_ << "bit\n";
    os << "输出: " << outputRate_ << "Hz  " << bitResolution_ << "bit  "
       << "子帧" << subframeSize_ << "B\n";
    /*
     * ★★ 开了交叉馈送就**不再是 bit-perfect** —— 它就是在改样本，
     *    比"重采样"还彻底（重采样在同速率下还能做到无损位拷贝）。
     *    所以这一行必须说实话，否则「直通（bit-perfect）」就是假话。
     */
    const bool xf = crossfeed_.enabled() && crossfeed_.active();
    os << "传输方式: " << (resampler_.active() ? "重采样" : "直通")
       << (xf ? " + 交叉馈送（非 bit-perfect）"
              : (resampler_.active() ? "" : "（bit-perfect）"))
       << "\n";
    if (xf) {
        char xfNote[160];
        crossfeed_.describe(xfNote, sizeof(xfNote));
        os << "交叉馈送: " << xfNote << "\n";
    }
    os << "缓冲: " << ring_.available() << " / " << ring_.capacity() << " 字节"
       << "（水位 " << static_cast<int>(bufferFillRatio() * 100) << "%）\n";
    os << "欠载: " << ringSource_.underrunBytes() << " 字节\n";
    const int64_t posMs = positionMs();
    os << "已播: " << (posMs / 1000) << "." << ((posMs % 1000) / 100) << " 秒";
    if (durationMs_ > 0) {
        os << " / " << (durationMs_ / 1000) << "."
           << ((durationMs_ % 1000) / 100) << " 秒";
    }
    os << "\n";
    os << "已解码: " << decodedFrames_.load() << " 帧";
    if (sourceRate_ > 0) {
        os << "（" << (decodedFrames_.load() / static_cast<uint64_t>(sourceRate_))
           << " 秒）";
    }
    os << "\n";
    if (finished_.load()) os << "状态: 已到文件末尾\n";
    if (paused_.load()) os << "状态: 已暂停\n";
    return os.str();
}

}  // namespace hifi
