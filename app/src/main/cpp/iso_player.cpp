#include "iso_player.h"

#include "thread_priority.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <sstream>

#define LOG_TAG "HiFiIso"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hifi {

namespace {

uint64_t nowMs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000ULL +
           static_cast<uint64_t>(ts.tv_nsec) / 1000000ULL;
}

/* 微秒版：URB 的正常提交间隔只有约 1ms，用毫秒量不出细节 */
uint64_t nowUs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ULL +
           static_cast<uint64_t>(ts.tv_nsec) / 1000ULL;
}

void LIBUSB_CALL isoCallbackTrampoline(libusb_transfer* tr) {
    static_cast<IsoPlayer*>(tr->user_data)->onIsoComplete(tr);
}

void LIBUSB_CALL feedbackCallbackTrampoline(libusb_transfer* tr) {
    static_cast<IsoPlayer*>(tr->user_data)->onFeedbackComplete(tr);
}

void updateMin(std::atomic<uint64_t>& slot, uint64_t v) {
    uint64_t cur = slot.load(std::memory_order_relaxed);
    while (v < cur && !slot.compare_exchange_weak(cur, v,
                                                  std::memory_order_relaxed)) {
        // cur 已被 CAS 更新，继续比较
    }
}

void updateMax(std::atomic<uint64_t>& slot, uint64_t v) {
    uint64_t cur = slot.load(std::memory_order_relaxed);
    while (v > cur && !slot.compare_exchange_weak(cur, v,
                                                  std::memory_order_relaxed)) {
        // 同上
    }
}

}  // namespace

IsoPlayer::IsoPlayer() = default;

IsoPlayer::~IsoPlayer() {
    stop();
}

// ---------------------------------------------------------------------------
//  包长分配
//
//  USB 每秒固定有 unitsPerSecond 个发送机会（高速下 8000 个 microframe），
//  每个机会应发
//      sampleRate / unitsPerSecond
//  个样本。该值常常不是整数（44100/8000 = 5.5125），用整数定点数做
//  Bresenham 分配，这样任意前缀的累计样本数都精确贴合理论值，不累积漂移。
//
//  ★ 分配单位必须是「样本」而不是「字节」。
//    若按字节分配，44100Hz 会得到 33/34 字节的包，而这两个长度分别对应
//    5.5 和 5.667 个样本 —— **每个包都装着半截样本**，设备侧样本流错位，
//    听感上是持续失真。48000Hz 这类整数速率每包恰好 6 个整样本（36 字节），
//    所以完全看不出问题，这也是该 bug 能长期隐藏的原因。
//
//  ★ 用「流式累加器」而不是「按包序号算 floor((n+1)S)-floor(nS)」。
//    后者要求速率恒定；而速率现在会随设备 feedback 在流中途变化
//    （异步 DAC 必须如此，见 applyFeedback）。累加器天然支持变速，
//    同样不累积漂移。
// ---------------------------------------------------------------------------
int IsoPlayer::nextPacketBytes() {
    sampleAccumulator_ += scaledSamplesPerUnit_;
    const uint64_t whole = sampleAccumulator_ / kSampleScale;
    sampleAccumulator_ -= whole * kSampleScale;
    return static_cast<int>(whole) * frameBytes_;
}

void IsoPlayer::prepareTransfer(libusb_transfer* tr) {
    int offset = 0;
    for (int i = 0; i < tr->num_iso_packets; ++i) {
        // nextPacketBytes 内部按「样本数」做小数分配再换算成字节，
        // 因此每个包必然包含整数个样本，且长期平均速率精确。
        // 详见 nextPacketBytes 上方的说明。
        const int sz = nextPacketBytes();
        tr->iso_packet_desc[i].length = static_cast<unsigned int>(sz);
        offset += sz;
    }

    if (offset > bufferCapacity_) {
        // 理论上不会发生（包长由采样率唯一决定），保底防止越界写
        LOGE("包总长 %d 超过缓冲容量 %d，本次回退为等长包", offset, bufferCapacity_);
        const int per = bufferCapacity_ / tr->num_iso_packets;
        offset = 0;
        for (int i = 0; i < tr->num_iso_packets; ++i) {
            tr->iso_packet_desc[i].length = static_cast<unsigned int>(per);
            offset += per;
        }
    }

    // 从数据源取数据。PcmSource::fill 的契约是"写满 bytes 字节，
    // 不足部分补静音并立即返回"，所以这里绝不会阻塞 USB 提交线程 ——
    // iso 端点不重传，一旦阻塞就是一声爆音。
    //
    // 返回值**只用于转储**：欠载/暂停静音的计数统一由数据源负责，只有它知道
    // 补了多少静音、以及当时是否处于暂停。原来这里自己又记了一份，
    // 结果两份账对不上 —— 暂停静音被重复算进欠载（实测 515164+3456+164
    // 恰好等于报告里的 518784，分毫不差）。
    const int valid = source_->fill(tr->buffer, offset);

    /*
     * 输出字节转储（bit-perfect 验证用）。
     *
     * 只收 valid 那一段 —— 后面 offset-valid 是欠载/暂停补的静音，
     * 掺进来就没法和参考 PCM 逐字节比对了。
     *
     * ★ 只 append 到内存 vector，**不做任何文件 I/O**：这里跑在 libusb
     *   事件线程上，一次阻塞写盘就会把队列抽干，正好复现我们修掉的那种卡顿。
     */
    if (dumpEnabled_.load(std::memory_order_relaxed) && valid > 0) {
        const size_t n = static_cast<size_t>(valid);
        if (dumpBuf_.size() + n <= kDumpLimitBytes) {
            dumpBuf_.insert(dumpBuf_.end(), tr->buffer, tr->buffer + n);
        } else if (!dumpTruncated_) {
            dumpTruncated_ = true;   // 只标记一次，报告里注明
        }
    }

    // libusb 的 submit_iso_transfer 会校验 transfer->length >= 各包长度之和
    tr->length = offset;

    packetsSubmitted_ += static_cast<uint64_t>(tr->num_iso_packets);
    bytesSubmitted_ += static_cast<uint64_t>(offset);

    /*
     * 记录相邻两次提交的间隔。
     *
     * 正常节奏是每个 URB 覆盖 8 个 microframe = 1ms，所以这个值应稳定在
     * 1000µs 左右。一旦拉长，就说明 USB 提交线程被饿死了 —— 那才是设备断粮的
     * 直接原因。「缺口」只说明结果（总共少送多少），这个才能说明原因（卡了多久）。
     *
     * 单写者：prepareTransfer 的调用者都在 libusb 事件线程上，所以
     * lastSubmitUs_ 与最大值的读改写不需要同步。
     */
    const uint64_t nowUsVal = nowUs();
    if (lastSubmitUs_ != 0) {
        const uint64_t gap = nowUsVal - lastSubmitUs_;
        if (gap > stats_.maxSubmitGapUs.load(std::memory_order_relaxed)) {
            stats_.maxSubmitGapUs.store(gap, std::memory_order_relaxed);
        }
        if (gap > 4000) {
            stats_.gapsOver4ms.fetch_add(1, std::memory_order_relaxed);
            if (gap > 8000) stats_.gapsOver8ms.fetch_add(1, std::memory_order_relaxed);
            recordStall(gap);   // 只有 >4ms 的才值得记
        }
    }
    lastSubmitUs_ = nowUsVal;
}

/*
 * 记一次提交卡顿。
 *
 * 关键是算出**超过队列余量的部分** —— 那才是真正让设备断粮的时长。
 * 一个 20ms 的卡顿在 32ms 余量面前完全被吸收，听不出来；
 * 一个 58ms 的卡顿则会造成 26ms 的静音。只统计"超过 8ms"是分不出这两者的。
 */
void IsoPlayer::recordStall(uint64_t gapUs) {
    const uint64_t queueUs = (unitsPerSecond_ > 0)
            ? static_cast<uint64_t>(cfg_.urbCount) *
              static_cast<uint64_t>(cfg_.packetsPerUrb) * 1000000ULL / unitsPerSecond_
            : 0;
    const uint64_t over = (gapUs > queueUs) ? (gapUs - queueUs) : 0;

    if (over > 0) {
        stats_.gapsOverQueue.fetch_add(1, std::memory_order_relaxed);
        stats_.punctureTotalUs.fetch_add(over, std::memory_order_relaxed);
    }

    const uint64_t atMs = nowMs() - startTimeMs_.load(std::memory_order_relaxed);

    // 按 gapUs 降序插入，只保留最严重的前 kMaxStalls 条
    int pos = stallCount_;
    while (pos > 0 && stalls_[pos - 1].gapUs < gapUs) --pos;
    if (pos >= kMaxStalls) return;

    const int last = (stallCount_ < kMaxStalls) ? stallCount_ : kMaxStalls - 1;
    for (int i = last; i > pos; --i) stalls_[i] = stalls_[i - 1];
    stalls_[pos] = {atMs, gapUs, over};
    if (stallCount_ < kMaxStalls) ++stallCount_;
}

// 测试音生成已移至 tone_source.cpp —— IsoPlayer 现在只依赖 PcmSource 接口，
// 不再自己产生音频数据。

// 音源自检已移至 ToneSource —— 它本来就只对测试音有意义。
bool IsoPlayer::start(libusb_context* ctx, libusb_device_handle* devh,
                      const OutputConfig& cfg, PcmSource* source, std::string* err) {
    // start 和 stop 会争同一批线程/URB，必须串行
    std::lock_guard<std::mutex> lk(lifecycleMutex_);
    auto fail = [&](const std::string& m) {
        if (err) *err = m;
        LOGE("start 失败: %s", m.c_str());
        releaseAll();
        return false;
    };

    if (running_.load()) return fail("引擎已在运行");
    if (ctx == nullptr || devh == nullptr) return fail("libusb 上下文/句柄为空");
    if (cfg.epAddr == 0) return fail("未指定 iso 数据端点");
    if (cfg.channels <= 0 || cfg.subframeSize <= 0 || cfg.bitResolution <= 0)
        return fail("声道数 / 子帧长度 / 位深 非法");
    if (cfg.sampleRate <= 0) return fail("采样率非法");
    if (cfg.packetsPerUrb <= 0 || cfg.urbCount <= 0) return fail("URB 参数非法");
    if (source == nullptr) return fail("未提供 PCM 数据源");

    ctx_ = ctx;
    devh_ = devh;
    cfg_ = cfg;
    source_ = source;

    /*
     * 清掉上一轮的转储，并**一次性把容量预留到位**。
     *
     * 必须在这里清、也只能在这里清：start() 保证此刻没有 URB 在跑
     * （上面刚判过 running_），而 setDumpEnabled 可能在播放中被 UI 调到 ——
     * 在那里清 vector 会和 USB 事件线程的 append 抢，是数据竞争。
     * 不清的后果很隐蔽：第二遍会追加在第一遍后面，比对直接失败。
     *
     * ★ reserve 不是优化，是**正确性要求**。实测踩过：
     *   不预留时 vector 按几何级数增长，每到 4/8/16/32MB 就在原地复制
     *   整个缓冲，而 append 跑在 **USB 提交线程**上 —— 于是提交间隔被卡出
     *   63 / 114 / 225 / 442 / 477 ms（发生时刻精确翻倍：4.1→8.3→16.6→33.2→66.4 秒），
     *   队列被打穿，听感是明显卡顿。而且四段测试全都中招，一度让人以为
     *   是 176.4k 速率本身的问题。
     *
     *   我在 README 里特意写了「绝不在 USB 提交线程上做文件 I/O」，
     *   却没料到**内存重分配一样致命** —— 它同样是一次不可控的长阻塞。
     *   预留之后 append 永不扩容，只剩一次几 KB 的 memcpy（微秒级）。
     */
    if (dumpEnabled_.load(std::memory_order_relaxed)) {
        dumpBuf_.clear();
        dumpBuf_.reserve(kDumpLimitBytes);
    }
    dumpTruncated_ = false;

    const int speed = libusb_get_device_speed(libusb_get_device(devh));
    if (speed <= 0) return fail("无法读取设备速度（libusb_get_device_speed）");
    unitsPerSecond_ = (speed >= LIBUSB_SPEED_HIGH) ? 8000ULL : 1000ULL;

    frameBytes_ = cfg_.channels * cfg_.subframeSize;
    scaledSamplesPerUnit_ = static_cast<uint64_t>(
        (static_cast<double>(cfg_.sampleRate) * 1000000.0) /
            static_cast<double>(unitsPerSecond_) + 0.5);
    if (scaledSamplesPerUnit_ == 0)
        return fail("每 microframe 样本数算得为 0，采样率不支持");
    sampleAccumulator_ = 0;   // 小数累加器从零起算

    packetsSubmitted_ = 0;
    bytesSubmitted_ = 0;
    lastSubmitUs_ = 0;      // 跳过第一次的间隔（它包含起播准备时间）
    stats_.reset();
    stopRequested_.store(false);
    paused_.store(false);
    stallCount_ = 0;
    activeTransfers_.store(0);

    LOGI("启动: speed=%d unitsPerSec=%llu frameBytes=%d 每单位样本=%.4f",
         speed, static_cast<unsigned long long>(unitsPerSecond_), frameBytes_,
         static_cast<double>(scaledSamplesPerUnit_) / 1e6);

    // ---- 分配探针缓冲区 ----
    // 单包分配量取端点 wMaxPacketSize 再留余量，避免高采样率下越界
    const int allocPerPacket = (cfg_.maxPacketSize > 0 ? cfg_.maxPacketSize : 1024) + 32;
    bufferCapacity_ = allocPerPacket * cfg_.packetsPerUrb;

    transfers_.assign(cfg_.urbCount, nullptr);
    buffers_.assign(cfg_.urbCount, std::vector<uint8_t>());

    for (int u = 0; u < cfg_.urbCount; ++u) {
        libusb_transfer* tr = libusb_alloc_transfer(cfg_.packetsPerUrb);
        if (tr == nullptr) return fail("libusb_alloc_transfer 失败（内存不足）");

        buffers_[u].assign(static_cast<size_t>(bufferCapacity_), 0);
        libusb_fill_iso_transfer(tr, devh_, cfg_.epAddr, buffers_[u].data(),
                                 bufferCapacity_, cfg_.packetsPerUrb,
                                 isoCallbackTrampoline, this, 1000);
        transfers_[u] = tr;
    }

    // ---- feedback 端点（异步 DAC 才有）----
    if (cfg_.feedbackEp != 0) {
        feedbackTransfer_ = libusb_alloc_transfer(0);
        if (feedbackTransfer_ == nullptr)
            return fail("feedback 传输分配失败");
        feedbackBuffer_.assign(4, 0);
        libusb_fill_iso_transfer(feedbackTransfer_, devh_, cfg_.feedbackEp,
                                 feedbackBuffer_.data(), 4, 1,
                                 feedbackCallbackTrampoline, this, 1000);
        feedbackTransfer_->iso_packet_desc[0].length = 4;
        feedbackTransfer_->length = 4;
    }

    running_.store(true);
    startTimeMs_.store(nowMs());
    isoThread_ = std::thread(&IsoPlayer::isoWorkerLoop, this);
    return true;
}

// ---------------------------------------------------------------------------
//  提交/回收主循环
// ---------------------------------------------------------------------------
void IsoPlayer::isoWorkerLoop() {
    // 实时性要求最高的线程：漏掉一个 microframe 就是一声爆音，
    // 且 iso 端点不重传，补不回来。
    setAudioThreadPriority(kPriorityUrgentAudio, "USB 提交线程", kSlotUsbSubmit);

    // 先把所有 URB 灌满并提交，形成 N 个包周期的在途缓冲
    for (auto* tr : transfers_) {
        if (tr == nullptr) continue;
        prepareTransfer(tr);
        activeTransfers_.fetch_add(1, std::memory_order_acq_rel);
        const int r = libusb_submit_transfer(tr);
        if (r == 0) {
            stats_.submitOk.fetch_add(1, std::memory_order_relaxed);
        } else {
            activeTransfers_.fetch_sub(1, std::memory_order_acq_rel);
            stats_.urbsFailed.fetch_add(1);
            stats_.lastTransferStatus.store(r);
            LOGE("libusb_submit_transfer 失败: %d (%s)", r, libusb_error_name(r));
        }
    }

    if (feedbackTransfer_ != nullptr) {
        const int r = libusb_submit_transfer(feedbackTransfer_);
        if (r < 0) {
            stats_.feedbackErrors.fetch_add(1);
            LOGW("feedback 传输提交失败: %d (%s)", r, libusb_error_name(r));
        }
    }

    const timeval tv{0, 100000};  // 100ms，便于及时响应停止请求
    while (!stopRequested_.load(std::memory_order_acquire)) {
        struct timeval t = tv;
        const int r = libusb_handle_events_timeout_completed(ctx_, &t, nullptr);
        if (r < 0 && r != LIBUSB_ERROR_INTERRUPTED) {
            LOGE("libusb_handle_events 失败: %d (%s)", r, libusb_error_name(r));
            stats_.lastTransferStatus.store(r);
        }

        if (cfg_.seconds > 0) {
            const uint64_t elapsed = nowMs() - startTimeMs_.load();
            if (elapsed >= static_cast<uint64_t>(cfg_.seconds) * 1000ULL) {
                LOGI("达到设定时长 %d 秒，自动停止", cfg_.seconds);
                break;
            }
        }

        /*
         * 数据源已放完（解码到末尾且缓冲排空）—— 主动收手，不要再喂静音。
         *
         * 必须在这里判断而不是在 onIsoComplete 回调里：那个回调跑在 libusb
         * 事件线程上，而 stop() 会 join 同一个线程，从回调里调用等于自锁。
         * 这个循环每 100ms 醒一次，收尾延迟最多 100ms，且这段时间送的是
         * 缓冲里剩余的最后一个 URB，不是凭空多出来的静音。
         */
        if (source_ != nullptr && source_->finished()) {
            LOGI("数据源已播完，自动停止传输");
            break;
        }
    }

    running_.store(false, std::memory_order_release);

    /*
     * ★★ 先把在途 URB 排空，**再**取消。
     *
     *   在途的那些 URB 里装的是**已经从文件读出来的真实音频**，不是静音。
     *   直接 cancel 掉它们 = 这段音频从来没上过 USB 线。
     *
     *   实测（p51，DSD256 20 秒那首）：统计里的「累计字节」比文件数据区
     *   少 86,712 字节 ≈ 30ms，正好 = 主动取消 30 个 URB × ≈2822 字节；
     *   「已送音频时长」19.9704s 对 20.001s，差额也是这 30ms。
     *   ★ 这和 DSD 无关，PCM 一直如此 —— 只是以前没往这儿看。
     *
     *   ★★ 修在这里，**不能修在主循环那个 finished() 判断上**：
     *      onIsoComplete 一看到数据源放完就自己置 stopRequested_（:476），
     *      `while (!stopRequested_)` 因此提前退出，根本轮不到那个判断。
     *      收尾只有这一条路。
     *
     *   好在回调那边已经做对了：放完（或收到停止请求）就不再重提交，
     *   队列会自己排空，最长「队列深度」ms。这里只是给它这点时间；
     *   超时仍没排空的再走原来的 cancel 流程 —— 不能把收尾吊死。
     */
    {
        const uint64_t drainDeadline =
                nowMs() + static_cast<uint64_t>(cfg_.urbCount) + 200;
        while (activeTransfers_.load(std::memory_order_acquire) > 0 &&
               nowMs() < drainDeadline) {
            struct timeval t{0, 5000};
            libusb_handle_events_timeout_completed(ctx_, &t, nullptr);
        }
        const int left = activeTransfers_.load(std::memory_order_acquire);
        if (left > 0) {
            LOGW("%d 个在途 URB 在 %d ms 内没排空，照旧取消收尾",
                 left, cfg_.urbCount + 200);
        } else {
            /*
             * 只说事实，不下结论。
             *
             * 这里**不能**写"结尾一个字节没丢" —— 排空成功只说明在途的都送完了，
             * 不代表环形缓冲里没有残留、也不代表每个包都没被主机截短。
             * 到底少没少，看统计里「累计字节」和文件数据区比，别让日志替我下结论。
             */
            LOGI("收尾：在途 URB 已全部送完");
        }
    }

    // 墙上耗时：与「已送音频时长」相减就是设备累计没收到数据的时长。
    // 排空之后、取消之前记 —— 排空期间音频还在往外送，那段时间不能算进缺口。
    stats_.wallClockMs.store(nowMs() - startTimeMs_.load(), std::memory_order_relaxed);

    // 取消所有在途传输，并等回调全部返回后才能释放内存
    for (auto* tr : transfers_) {
        if (tr != nullptr) libusb_cancel_transfer(tr);
    }
    if (feedbackTransfer_ != nullptr) {
        libusb_cancel_transfer(feedbackTransfer_);
    }

    const uint64_t deadline = nowMs() + 3000;
    while (activeTransfers_.load(std::memory_order_acquire) > 0 &&
           nowMs() < deadline) {
        struct timeval t{0, 20000};
        libusb_handle_events_timeout_completed(ctx_, &t, nullptr);
    }

    if (activeTransfers_.load(std::memory_order_acquire) > 0) {
        LOGW("仍有 %d 个传输未回收，可能出现内存泄漏",
             activeTransfers_.load(std::memory_order_acquire));
    }
}

void IsoPlayer::onIsoComplete(libusb_transfer* tr) {
    // 最先记一次"回调确实进来了" —— 和"提交成功"比，能看出 libusb 有没有吞掉回调
    stats_.callbacks.fetch_add(1, std::memory_order_relaxed);

    if (tr->status == LIBUSB_TRANSFER_COMPLETED) {
        stats_.urbsCompleted.fetch_add(1);

        uint64_t total = 0;
        uint64_t mn = UINT64_MAX;
        uint64_t mx = 0;
        for (int i = 0; i < tr->num_iso_packets; ++i) {
            const uint32_t len = tr->iso_packet_desc[i].actual_length;
            total += len;
            if (len < mn) mn = len;
            if (len > mx) mx = len;
            if (tr->iso_packet_desc[i].status != LIBUSB_TRANSFER_COMPLETED) {
                stats_.packetErrors.fetch_add(1);
            }
        }
        stats_.packetsTotal.fetch_add(static_cast<uint64_t>(tr->num_iso_packets));
        stats_.bytesTotal.fetch_add(total);
        if (mn != UINT64_MAX) {
            if (stats_.packetBytesMin.load() == 0) stats_.packetBytesMin.store(mn);
            else updateMin(stats_.packetBytesMin, mn);
            updateMax(stats_.packetBytesMax, mx);
        }
    } else if (tr->status == LIBUSB_TRANSFER_CANCELLED) {
        /*
         * CANCELLED 只可能来自我们自己调用的 libusb_cancel_transfer，
         * 而调用它的只有三处：停止、seek 清队、暂停 —— **都是正常操作**。
         *
         * 不这样区分的话，「一首歌正常播完」或「拖一次进度条」都会让报告上
         * 冒出若干次 URB 失败和一条 LIBUSB_TRANSFER_CANCELLED，
         * 与「收尾静音被记成欠载」一样，是把正常行为报成了故障。
         */
        stats_.urbsCancelled.fetch_add(1);
    } else {
        stats_.urbsFailed.fetch_add(1);
        stats_.lastTransferStatus.store(tr->status);
        if (tr->status == LIBUSB_TRANSFER_NO_DEVICE) {
            // 设备被拔了，立刻收手，否则会无限刷错误
            LOGE("设备已断开（NO_DEVICE），停止播放");
            stopRequested_.store(true);
        }
    }

    // 注意暂停期间**照常重提交** —— 流必须一直跑，DAC 才不会失锁。
    // 数据源此时只送静音，所以声音是停的。
    if (!stopRequested_.load(std::memory_order_acquire)) {
        /*
         * ★★ 顺序不能反：**先判 finished()，再 prepareTransfer**。
         *
         *   原来这两步是反的 —— prepareTransfer 先把环形缓冲里最后一段真实
         *   数据搬进 URB 缓冲，紧接着 finished() 才判为真，于是这个 URB 不提交；
         *   而那段数据**已经离开环形缓冲了**，就此蒸发。
         *   实测（p52，DSD256 20 秒那首）：送出去的字节比文件数据区少
         *   2,032 字节，正好是一个 URB 装走的量。
         *
         *   数据源已放完就不再提交新 URB（理由见下）—— 此时**不 fill 也不提交**，
         *   直接收手。唯一会多提交的，是"装着最后一段真实音频"的那个 URB，
         *   它本来就该发出去；不会喂静音。
         *
         * 为什么必须在这里判、不能只靠主循环那 100ms 一次的检查：
         *   在它醒来之前，USB 线程会按 1ms 的节奏继续把空缓冲填成静音，
         *   最多上百个 URB 的静音会被记进 underrun，让这个指标重新变得不可信。
         *
         * 这里**只能置标志，不能调 stop()** —— 本函数跑在 libusb 事件线程上，
         * 而 stop() 会 join 同一个线程，从回调里调用就是死锁。
         */
        if (source_ != nullptr && source_->finished()) {
            LOGI("数据源已播完，停止提交 URB");
            stopRequested_.store(true, std::memory_order_release);
        } else {
            prepareTransfer(tr);
            if (libusb_submit_transfer(tr) == 0) {
                stats_.submitOk.fetch_add(1, std::memory_order_relaxed);
                return;  // 保持在途计数不变
            }
            stats_.urbsFailed.fetch_add(1);
        }
    }
    activeTransfers_.fetch_sub(1, std::memory_order_acq_rel);
}

// ---------------------------------------------------------------------------
//  feedback 端点：异步 DAC 用它回报真实消耗速率
//
//  返回值 Fm 是「每 microframe 的样本数」的定点表示：高速为 Q16.16，
//  全速为 Q10.14。换算实际采样率 = Fm * unitsPerSecond。
//  例如 48000Hz 高速下 Fm = 6.0（每 125µs 消耗 6 个样本）。
// ---------------------------------------------------------------------------
/*
 * 把设备反馈的实际速率接进包长分配。
 *
 * 由 libusb 事件线程调用，与 prepareTransfer 同线程，因此 scaledSamplesPerUnit_
 * 不需要同步 —— 这个前提很重要，改动线程模型时必须重新审视。
 */
void IsoPlayer::applyFeedback(uint32_t raw, int bytes) {
    if (unitsPerSecond_ == 0 || frameBytes_ <= 0) return;
    // A/B 开关关闭时只统计反馈读数，不改速率
    if (!cfg_.followFeedback) return;

    // 高速用 Q16.16（4 字节），全速用 Q10.14（3 字节）
    const double fm = (bytes == 3)
            ? static_cast<double>(raw & 0x00FFFFFFu) / 16384.0
            : static_cast<double>(raw) / 65536.0;
    if (!(fm > 0.0)) return;

    // Fm 的单位在不同固件间并不统一：USB 2.0 规范要求高速端点上报
    // 「每 microframe 的样本数」，但不少 XMOS 方案按「每 1ms 帧」上报，
    // 两者差 unitsPerSecond_/1000 倍。标称速率是已知的，用它反推。
    const double nominalPerUnit =
        static_cast<double>(cfg_.sampleRate) / static_cast<double>(unitsPerSecond_);
    const double nominalPerFrame = static_cast<double>(cfg_.sampleRate) / 1000.0;
    const bool perFrame =
        std::fabs(fm - nominalPerFrame) < std::fabs(fm - nominalPerUnit);

    const double perUnit =
        perFrame ? fm / (static_cast<double>(unitsPerSecond_) / 1000.0) : fm;

    /*
     * 限幅 ±1000 ppm。
     *
     * 反馈值本身可能不可信（这台设备对不存在的实体也照答不误，见 README），
     * 而一个跑飞的速率会让包长瞬间变成天文数字。1000 ppm 已能覆盖所有
     * 正常晶振（实测这台是 +20 ppm，差了两个数量级）。
     */
    const double maxDev = nominalPerUnit * 1000e-6;
    double clamped = perUnit;
    if (clamped > nominalPerUnit + maxDev) clamped = nominalPerUnit + maxDev;
    if (clamped < nominalPerUnit - maxDev) clamped = nominalPerUnit - maxDev;

    const uint64_t scaled = static_cast<uint64_t>(clamped * 1e6 + 0.5);
    if (scaled == 0) return;

    scaledSamplesPerUnit_ = scaled;

    const int ppm = static_cast<int>((clamped - nominalPerUnit) / nominalPerUnit * 1e6);
    stats_.ratePpm.store(ppm);
    stats_.ratePpmSum.fetch_add(ppm);

    // 取 min/max 用的是非原子的读-改-写。这里安全，因为 applyFeedback 只在
    // libusb 事件线程上跑，是唯一写者；读者最多看到稍旧的值。
    const uint64_t seen = stats_.feedbackApplied.fetch_add(1);
    if (seen == 0) {
        stats_.ratePpmMin.store(ppm);
        stats_.ratePpmMax.store(ppm);
    } else {
        if (ppm < stats_.ratePpmMin.load()) stats_.ratePpmMin.store(ppm);
        if (ppm > stats_.ratePpmMax.load()) stats_.ratePpmMax.store(ppm);
    }
}

void IsoPlayer::onFeedbackComplete(libusb_transfer* tr) {
    if (tr->status == LIBUSB_TRANSFER_COMPLETED) {
        const int actual = static_cast<int>(tr->iso_packet_desc[0].actual_length);
        if (actual >= 3) {
            uint32_t raw = 0;
            std::memcpy(&raw, feedbackBuffer_.data(),
                        static_cast<size_t>(actual < 4 ? actual : 4));
            if (actual == 3) {
                raw &= 0x00FFFFFFu;  // 全速 Q10.14 只占 3 字节
            }
            stats_.feedbackLastRaw.store(raw);
            stats_.feedbackLastActual.store(actual);
            stats_.feedbackReads.fetch_add(1);

            // ★ 关键：接进速率控制。没有这一步，异步 DAC 的 FIFO 会被慢慢抽干。
            applyFeedback(raw, actual);
        } else {
            stats_.feedbackErrors.fetch_add(1);
        }
    } else if (tr->status != LIBUSB_TRANSFER_CANCELLED) {
        stats_.feedbackErrors.fetch_add(1);
    }

    if (!stopRequested_.load(std::memory_order_acquire)) {
        libusb_submit_transfer(tr);
    }
}

// ---------------------------------------------------------------------------
//  停止与释放
// ---------------------------------------------------------------------------
void IsoPlayer::stop() {
    /*
     * ★ 必须整体串行化。
     *
     * `if (joinable()) join();` 这个写法**不是原子的**，两个线程同时进来会
     * 双双看到 joinable()==true，然后一个 join 成功、另一个对着已经被 join
     * 过的 thread 再调 join —— libc++ 抛 std::system_error，
     * 没人接就是 abort()。实测崩溃栈：
     *
     *     std::__ndk1::thread::join(): Invalid argument
     *       hifi::IsoPlayer::stop()
     *       Java_..._nativeStopPlayback
     *       PlayerSession.closeCurrentLocked
     *
     * 触发路径是 Kotlin 侧把"停止播放"改成了异步派发到 worker，
     * 而有些调用方紧接着又同步调 nativeClose —— 两条线程撞在一起。
     * 与其在上层逐个排查调用顺序，不如在原生层一次性堵死：
     * 不管谁从哪条线程调，这里都只有一个能进。
     */
    std::lock_guard<std::mutex> lk(lifecycleMutex_);
    if (isoThread_.joinable()) {
        stopRequested_.store(true, std::memory_order_release);
        isoThread_.join();
    }
    running_.store(false, std::memory_order_release);
    releaseAll();
}

/*
 * 把转储缓冲落盘。
 *
 * ★ 必须在 stop() **之后**调用 —— 那时 libusb 事件线程已经退出，
 *   dumpBuf_ 不再有人写。播放中调用会和回调抢 vector。
 */
size_t IsoPlayer::writeDump(const std::string& path, bool* truncated,
                            std::string* err) {
    if (truncated != nullptr) *truncated = dumpTruncated_;
    if (dumpBuf_.empty()) {
        if (err != nullptr) *err = "转储为空（开关未开，或本次没有有效数据）";
        return 0;
    }

    FILE* f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) {
        if (err != nullptr) *err = "无法打开转储文件：" + path;
        return 0;
    }
    const size_t n = std::fwrite(dumpBuf_.data(), 1, dumpBuf_.size(), f);
    std::fclose(f);

    if (n != dumpBuf_.size()) {
        if (err != nullptr) *err = "写入不完整";
    }
    // 写完就放掉，32MB 没必要一直占着
    std::vector<uint8_t>().swap(dumpBuf_);
    return n;
}

/*
 * 立刻丢弃在途 URB 里的旧数据（seek 用）。
 *
 * 这里只负责取消；重新填充与提交由 onIsoComplete 完成 —— 回调本来就会
 * 从环形缓冲重新取数据，取消之后再走一遍同样的路径即可，不必另写一套。
 */
void IsoPlayer::flushQueue() {
    // 与 stop() 互斥：它可能在另一条线程上释放 transfers_
    std::lock_guard<std::mutex> lk(lifecycleMutex_);
    if (!running_.load(std::memory_order_acquire)) return;
    if (paused_.load(std::memory_order_acquire)) return;  // 暂停时本就没有在途的

    stats_.flushEvents.fetch_add(1, std::memory_order_relaxed);
    for (auto* tr : transfers_) {
        if (tr != nullptr) libusb_cancel_transfer(tr);
    }
}

/*
 * 暂停 / 恢复。
 *
 * 暂停 = 置 paused_（数据源从此只送静音）+ 取消在途 URB（丢掉里面装着的真实音频）。
 * 流本身**继续跑**：回调会用静音重新填充并提交，DAC 因此保持时钟锁定，
 * 恢复时不需要重新锁定，也就没有那声咔哒。
 *
 * 恢复只需清掉 paused_ —— 下一个回调自然会从环形缓冲取到真实音频。
 * 代价是恢复后最多还有「一个队列深度」的静音已经排在路上（32ms，听不出来）。
 *
 * 这里直接从调用线程取消是安全的：libusb_cancel_transfer 是线程安全的，
 * 回调会在 libusb 事件线程上被派发。
 */
void IsoPlayer::setPaused(bool p) {
    // 与 stop() 互斥：它可能在另一条线程上释放 transfers_
    std::lock_guard<std::mutex> lk(lifecycleMutex_);
    if (!running_.load(std::memory_order_acquire)) return;

    if (p) {
        if (paused_.exchange(true, std::memory_order_acq_rel)) return;  // 已经暂停
        stats_.pauseEvents.fetch_add(1, std::memory_order_relaxed);
        /*
         * ★★ 这里**刻意不取消在途 URB** —— 以前会 `libusb_cancel_transfer` 掉全部 32 个。
         *
         *   为什么取消是有害的：
         *
         *   · isochronous 端点要求**每个 microframe 一个包**。在途 URB 已经在
         *     内核的传输计划里排好了；把它们撤下来重排，就是在这条时间线上
         *     撕一道口子 —— 设备那一侧表现为一次数据断档，也就是**咔哒**。
         *
         *   · 而且 Linux 上**已经提交的 iso URB 根本取消不掉**
         *     （`*_discardurb` 对 iso 返回 -EINVAL）。于是「取消」的结果是
         *     一部分留下、一部分被标成 CANCELLED，回调再补一份重提交 ——
         *     顺序一乱，口子更多。
         *
         *   · 每按一次暂停撕一次。实测用户连点暂停（~180ms 一次，约 5.5Hz），
         *     一串咔哒听起来就是**一个音调随点击频率升高的声音** ——
         *     他报的正是"随着暂停频率提高变高"。
         *
         *   不取消的代价：**已经排上队的约 32ms 真实音频会照常放完**。
         *   32ms 听不出来，而它换来的是干净的暂停。何况数据源那侧现在会
         *   在 5ms 内淡到零（见 RingBufferSource 的淡入淡出），
         *   那 32ms 之后接的就是一段平滑的衰减，不是硬切。
         *
         *   ★ seek 那条路（flushQueue）**保留取消** —— 那里本来就要求
         *     立刻丢掉旧音频，代价是一次 seek 的断档，可以接受。
         */
    } else {
        paused_.store(false, std::memory_order_release);
    }
}

void IsoPlayer::releaseAll() {
    for (auto* tr : transfers_) {
        if (tr != nullptr) libusb_free_transfer(tr);
    }
    transfers_.clear();
    buffers_.clear();
    bufferCapacity_ = 0;

    if (feedbackTransfer_ != nullptr) {
        libusb_free_transfer(feedbackTransfer_);
        feedbackTransfer_ = nullptr;
    }
    feedbackBuffer_.clear();
    activeTransfers_.store(0);
}

/*
 * 由 feedback 端点反推设备实际速率。
 *
 * Fm 的单位在不同固件间并不统一：USB 2.0 规范要求高速端点按「每 microframe
 * 的样本数」上报，但不少 XMOS 方案（含实测的 MOONDROP Dawn Pro）按「每 1ms 帧」
 * 上报，两者数值恰好差 8 倍。用**标称采样率**反推设备用的是哪种单位。
 *
 * 这个启发式在"设备跑错速率"时依然成立：请求 44000 而设备实际跑 48000 时，
 * 标称每 µframe = 5.5、标称每帧 = 44.0，而 Fm 报 6.0001 —— 与 5.5 差 0.5、
 * 与 44.0 差 37.99，于是判为 microframe 单位，算出 48001Hz，正确。
 * 反过来若设备按帧上报，44.0 与 48.0 差 4、与 5.5 差 38.5，同样判对。
 */
bool IsoPlayer::measuredRate(double* hz, double* ppm, bool* perFrame) const {
    if (stats_.feedbackReads.load(std::memory_order_relaxed) == 0) return false;
    if (cfg_.sampleRate <= 0 || unitsPerSecond_ == 0) return false;

    const uint32_t raw = stats_.feedbackLastRaw.load(std::memory_order_relaxed);
    if (raw == 0) return false;
    const double fm = static_cast<double>(raw) / 65536.0;

    const double nominalPerMicroframe =
        static_cast<double>(cfg_.sampleRate) /
        static_cast<double>(unitsPerSecond_);
    const double nominalPerFrame = static_cast<double>(cfg_.sampleRate) / 1000.0;
    const bool pf = std::fabs(fm - nominalPerFrame) <
                    std::fabs(fm - nominalPerMicroframe);

    const double measured =
        pf ? fm * 1000.0 : fm * static_cast<double>(unitsPerSecond_);

    if (hz != nullptr) *hz = measured;
    if (perFrame != nullptr) *perFrame = pf;
    if (ppm != nullptr) {
        *ppm = (measured - static_cast<double>(cfg_.sampleRate)) /
               static_cast<double>(cfg_.sampleRate) * 1e6;
    }
    return true;
}

std::string IsoPlayer::statsReport() const {
    std::ostringstream os;
    const uint64_t bytes = stats_.bytesTotal.load();
    const uint64_t reads = stats_.feedbackReads.load();

    double seconds = 0.0;
    if (cfg_.sampleRate > 0 && frameBytes_ > 0) {
        seconds = static_cast<double>(bytes) /
                  (static_cast<double>(cfg_.sampleRate) * frameBytes_);
    }

    // 先把本次的配置回显出来 —— 报告要能自证是哪个配置跑出来的
    os << "队列深度=" << cfg_.urbCount << " URB（余量 "
       << (cfg_.urbCount * cfg_.packetsPerUrb / 8) << " ms）"
       << "   seek 清队=" << (cfg_.flushQueueOnSeek ? "开" : "关")
       << "   feedback 跟随=" << (cfg_.followFeedback ? "开" : "关") << "\n";

    os << "端点=0x" << std::hex << static_cast<int>(cfg_.epAddr) << std::dec
       << "  采样率=" << cfg_.sampleRate
       << "Hz  声道=" << cfg_.channels
       << "  子帧=" << cfg_.subframeSize << "B"
       << "  有效位=" << cfg_.bitResolution << "\n";

    /*
     * ★★ 提交请求 vs 实际完成 —— 定位「音频少了一截」的判据。
     *
     *   这两个计数器（packetsSubmitted_ / bytesSubmitted_）记录的是
     *   **每次 prepareTransfer 的请求量**，而上面的「URB 完成 / 累计字节」
     *   记的是**回调报回来的实际量**。两者一比，责任立刻分开：
     *
     *     · 提交请求 > 完成        → 有 URB 填了却没提交/没回来（数据蒸发）
     *     · 相等但请求字节 > 累计字节 → URB 都回来了，是传输被截短
     *                                （actual_length < length）
     *
     *   实测背景：冷启动首播会少 3~26 个 URB 的数据，而环形缓冲那边
     *   「进环 = 出环、残留 0」完全对得上 —— 矛盾就在这一段，加这行判它。
     */
    const int ppu = cfg_.packetsPerUrb > 0 ? cfg_.packetsPerUrb : 1;
    os << "提交请求=" << (packetsSubmitted_ / static_cast<uint64_t>(ppu))
       << " URB（请求 " << bytesSubmitted_ << " 字节）\n";
    os << "提交成功=" << stats_.submitOk.load()
       << "  回调=" << stats_.callbacks.load() << "\n";

    os << "URB 完成=" << stats_.urbsCompleted.load()
       << "  URB 失败=" << stats_.urbsFailed.load()
       << "  包错误=" << stats_.packetErrors.load();
    // 主动取消单独列出，且只在非 0 时出现 —— 它是停止流程的正常产物，
    // 不是故障，没必要时时占一行
    const uint64_t cancelled = stats_.urbsCancelled.load();
    if (cancelled > 0) {
        os << "  主动取消=" << cancelled << "（正常，不算失败）";
    }
    os << "\n";

    // 两个新功能的触发次数 —— 确认它们真的跑过，而不是"开关拨了但没生效"
    const uint64_t flushes = stats_.flushEvents.load();
    const uint64_t pauses = stats_.pauseEvents.load();
    if (flushes > 0) {
        os << "seek 清队=" << flushes << " 次（丢弃旧位置的在途数据）\n";
    }
    if (pauses > 0) {
        os << "暂停=" << pauses << " 次（暂停时主动停流，声音立即停止）\n";
    }

    os << "累计包数=" << stats_.packetsTotal.load()
       << "  累计字节=" << bytes << "\n";

    os << "单包字节 min/max=" << stats_.packetBytesMin.load()
       << " / " << stats_.packetBytesMax.load() << "\n";

    os << "已送音频时长≈" << seconds << " 秒\n";

    /*
     * 墙上耗时 vs 已送时长 —— 「短暂静音」唯一的量化手段。
     *
     * 「已送音频时长」是从字节数反推的，等于"我们发出了多少"，区分不了
     * "连续发"和"中间有洞"。而墙上耗时是真实流逝的时间，两者相减就是
     * 设备累计没收到数据的时长 —— 那正是用户听到的断音。
     */
    uint64_t wallMs = stats_.wallClockMs.load(std::memory_order_relaxed);
    if (wallMs == 0 && running_.load(std::memory_order_acquire)) {
        /*
         * 流还在跑 —— 说明这次统计是在 stop() **之前**取的
         * （nativeStopPlayback 就是先取统计再停，为的是自动播完的情况也能拿到数）。
         * 此时 wallClockMs 尚未写入，现算一个当时的耗时。
         * 少了这一句，用户手动点停止时这段就整段消失（实测就是这么丢的）。
         */
        wallMs = nowMs() - startTimeMs_.load(std::memory_order_relaxed);
    }
    if (wallMs > 0 && unitsPerSecond_ > 0) {
        const double wallSec = static_cast<double>(wallMs) / 1000.0;
        const double audioSec =
            static_cast<double>(stats_.packetsTotal.load()) /
            static_cast<double>(unitsPerSecond_);
        const double gapSec = wallSec - audioSec;
        os << "实际耗时≈" << wallSec << " 秒\n";
        os << "→ 缺口 " << gapSec << " 秒";
        if (gapSec < 0.05) {
            os << "（设备全程都在收数据，没有断供）\n";
        } else {
            const double ms = gapSec * 1000.0;
            os << "  ← 这么长的时间里设备没收到任何数据，就是听到的静音；\n";
            os << "    折合 " << ms << " ms，" << (gapSec / wallSec * 100.0)
               << "% 的时间在断供\n";
        }
    }

    /*
     * 提交间隔 —— 「缺口」说明结果，这个说明原因。
     * 正常应稳定在 1ms 左右（每 URB 覆盖 8 个 microframe）。
     */
    const uint64_t maxGapUs = stats_.maxSubmitGapUs.load(std::memory_order_relaxed);
    if (maxGapUs > 0) {
        const uint64_t queueUs = (unitsPerSecond_ > 0)
                ? static_cast<uint64_t>(cfg_.urbCount) *
                  static_cast<uint64_t>(cfg_.packetsPerUrb) * 1000000ULL / unitsPerSecond_
                : 0;
        const uint64_t g4 = stats_.gapsOver4ms.load(std::memory_order_relaxed);
        const uint64_t g8 = stats_.gapsOver8ms.load(std::memory_order_relaxed);

        os << "提交间隔: 最大 " << (static_cast<double>(maxGapUs) / 1000.0) << " ms"
           << "（正常 1ms，队列余量 " << (queueUs / 1000) << " ms）\n";

        if (g4 == 0) {
            os << "  USB 提交线程全程没有被饿死\n";
        } else {
            os << "  超过 4ms: " << g4 << " 次，超过 8ms: " << g8 << " 次\n";

            /*
             * 关键的一行：只有**超过队列余量**的部分才会真的让设备断粮。
             * 一个 20ms 的卡顿在 32ms 余量面前完全被吸收、听不出来；
             * 58ms 的卡顿则会造成 26ms 静音。只看"最大多少"是分不出的。
             */
            const uint64_t overQ = stats_.gapsOverQueue.load(std::memory_order_relaxed);
            const uint64_t punctureMs =
                stats_.punctureTotalUs.load(std::memory_order_relaxed) / 1000;
            if (overQ == 0) {
                os << "  穿透队列: 0 次 —— 所有卡顿都被队列吸收了，没有造成断音\n";
            } else {
                os << "  穿透队列: " << overQ << " 次，累计断供 " << punctureMs << " ms"
                   << "   ← 这个数直接对应听到的静音\n";
            }

            if (stallCount_ > 0) {
                os << "  最严重的几次（带时刻，便于和听感对上）:\n";
                for (int i = 0; i < stallCount_; ++i) {
                    os << "    #" << (i + 1)
                       << "  第 " << (static_cast<double>(stalls_[i].atMs) / 1000.0) << " 秒"
                       << "   卡 " << (static_cast<double>(stalls_[i].gapUs) / 1000.0) << " ms";
                    if (stalls_[i].overQueueUs > 0) {
                        os << "   穿透 "
                           << (static_cast<double>(stalls_[i].overQueueUs) / 1000.0)
                           << " ms  ← 这条造成了断音";
                    } else {
                        os << "   （被队列吸收）";
                    }
                    os << "\n";
                }
            }
        }
    }

    // underrun 是判断环形缓冲水位够不够的直接指标。
    // 短测里出现少量可以接受（起播瞬间），持续增长说明解码跟不上。
    // 计数取自数据源 —— 它已经按「是否暂停」分好类了。
    const uint64_t ur = (source_ != nullptr) ? source_->underrunBytes() : 0;
    os << "缓冲欠载(underrun)=" << ur << " 字节";
    if (ur > 0 && frameBytes_ > 0 && cfg_.sampleRate > 0) {
        const double ms = static_cast<double>(ur) /
                          (static_cast<double>(cfg_.sampleRate) * frameBytes_) * 1000.0;
        os << "  ≈ " << ms << " ms 的静音";
    }
    os << "\n";

    // 暂停期间喂的静音是故意的，单独列出，不混进欠载。
    // 计数由数据源负责（谁补的静音谁分类），这里只取来显示。
    const uint64_t ps = (source_ != nullptr) ? source_->pauseSilenceBytes() : 0;
    if (ps > 0 && frameBytes_ > 0 && cfg_.sampleRate > 0) {
        const double sec = static_cast<double>(ps) /
                           (static_cast<double>(cfg_.sampleRate) *
                            static_cast<double>(frameBytes_));
        os << "暂停静音=" << ps << " 字节 ≈ " << sec
           << " 秒（暂停期间输出，不计入欠载）\n";
    }

    double measured = 0.0, ppm = 0.0;
    bool perFrame = false;
    if (reads > 0 && measuredRate(&measured, &ppm, &perFrame)) {
        const uint32_t raw = stats_.feedbackLastRaw.load();
        const double fm = static_cast<double>(raw) / 65536.0;

        const double nominalPerMicroframe =
            static_cast<double>(cfg_.sampleRate) /
            static_cast<double>(unitsPerSecond_);
        const double nominalPerFrame =
            static_cast<double>(cfg_.sampleRate) / 1000.0;

        os << "feedback 读取=" << reads
           << "  错误=" << stats_.feedbackErrors.load()
           << "  实际长度=" << stats_.feedbackLastActual.load() << "B\n";
        os << "  原始=0x" << std::hex << raw << std::dec
           << "  Fm=" << fm << "\n";
        os << "  单位为「每" << (perFrame ? "1ms 帧" : "microframe")
           << "的样本数」，标称应为 "
           << (perFrame ? nominalPerFrame : nominalPerMicroframe) << "\n";
        os << "  实测速率≈" << measured << " Hz   偏差 "
           << (ppm >= 0 ? "+" : "") << ppm << " ppm\n";

        /*
         * 速率自适应状态 —— 这是「卡顿有没有被修好」最直接的证据。
         *
         * 之前读了 feedback 却不用，包长按标称速率算死，异步 DAC 的 FIFO
         * 每 60~90 秒被抽干一次，听感就是周期性卡顿，而这一侧的欠载/URB/
         * 包错误三项全是干净的。现在这行会告诉我们回路是否真的在跑。
         */
        const uint64_t applied = stats_.feedbackApplied.load();
        if (!cfg_.followFeedback) {
            os << "  速率自适应: 已关闭（A/B 开关），按标称 "
               << cfg_.sampleRate << "Hz 定速下发\n";
        } else if (applied > 0) {
            const int lo = stats_.ratePpmMin.load();
            const int hi = stats_.ratePpmMax.load();
            const double avg = static_cast<double>(stats_.ratePpmSum.load()) /
                               static_cast<double>(applied);
            os << "  速率自适应: 已生效 " << applied << " 次\n";
            os << "    下发偏差 " << lo << " ~ " << hi << " ppm，平均 "
               << (avg >= 0 ? "+" : "") << avg << " ppm"
               << (lo == hi ? "（全程恒定）" : "（有波动）") << "\n";
        } else {
            os << "  ⚠ 速率自适应未生效：反馈值没被采用，"
                  "异步 DAC 会逐渐失步（表现为周期性卡顿）\n";
        }
        if (std::fabs(ppm) > 1000.0) {
            os << "  ⚠ 偏差过大：采样率可能没真正切过去，"
                  "或 URB 调度跟不上设备消耗\n";
        }
    } else if (cfg_.feedbackEp != 0) {
        os << "feedback 端点已配置，但没拿到可信读数（读取=" << reads
           << " 错误=" << stats_.feedbackErrors.load() << "）\n";
    } else {
        os << "本设备无 feedback 端点（非异步模式）\n";
    }

    const int st = stats_.lastTransferStatus.load();
    if (st != 0) {
        os << "最近传输状态=" << st << " (" << libusb_error_name(st) << ")\n";
    }

    return os.str();
}

}  // namespace hifi
