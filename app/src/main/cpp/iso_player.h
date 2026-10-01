/*
 * iso_player.h —— USB Audio Class isochronous 输出引擎
 *
 * 这一层负责把 PCM 数据按 USB 的节奏送进 DAC。它**不关心数据从哪来**，
 * 只通过 PcmSource 接口取数据 —— 测试音和真实解码器都是它的数据源。
 *
 * 设计要点（P0 已验证，勿轻改）：
 *  - iso 端点不重传，必须按 microframe 节奏持续喂数据；
 *  - 每个 URB 承载 packetsPerUrb 个包（高速下 1 包 = 1 microframe = 125µs）；
 *  - 包长按**样本数**做 Bresenham 分配再换算成字节，保证每包含整数个样本
 *    （44100Hz 这类非整数速率下若按字节分配，包里会出现半截样本 → 持续失真）；
 *  - 异步 DAC 的 feedback 端点复用同一个 libusb 事件循环，不另起线程。
 */
#pragma once

#include <libusb.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "pcm_source.h"

namespace hifi {

/* 输出端的参数（与音频内容无关，只描述"怎么送给 DAC"） */
struct OutputConfig {
    uint8_t epAddr = 0;          // iso 数据端点地址（OUT 通常为 0x01）
    uint8_t feedbackEp = 0;      // feedback 端点地址，0 表示设备无此端点
    int sampleRate = 48000;      // 送给 DAC 的速率（未必等于源文件速率）
    int channels = 2;
    int subframeSize = 2;        // 每样本每声道占用的字节数（1/2/3/4）
    int bitResolution = 16;      // 有效位
    int seconds = 0;             // 0 表示一直播到手动停止
    int packetsPerUrb = 8;
    int urbCount = 8;
    int maxPacketSize = 64;      // 端点描述符里的 wMaxPacketSize，用于缓冲分配

    /*
     * 是否跟随设备的 feedback 速率。
     *
     * 留成开关是为了 A/B 对比：设备反馈实测在 0~40 ppm 之间抖动，
     * 跟着它下发意味着我们的速率也在抖。理论上异步 DAC 就该这么喂，
     * 但如果这台设备的反馈本身不可信，跟随反而会把它的 FIFO 搞乱。
     * 只在 start() 时读取 —— 中途变速会造成速率跳变。
     */
    bool followFeedback = true;

    /*
     * seek 时是否立即丢弃在途 URB（即上面说的"取消并重填"）。
     *
     * 开：seek 后不会再有旧位置的声音，代价是重填时缓冲可能还没灌上，
     *     会有一小段静音（就是 seek 时那声"咔哒"）。
     * 关：seek 后最多还有「队列深度」毫秒的旧音频放完。
     * 两种都要实测听感才好定，所以做成开关。
     */
    bool flushQueueOnSeek = false;
};

struct ToneStats {
    std::atomic<uint64_t> urbsCompleted{0};
    std::atomic<uint64_t> urbsFailed{0};
    /*
     * 我方主动停止时取消的在途 URB。单独计数，不算失败 ——
     * 把它们混进 urbsFailed 会让「一首歌正常播完」看起来像传输出错。
     */
    std::atomic<uint64_t> urbsCancelled{0};
    std::atomic<uint64_t> packetsTotal{0};
    std::atomic<uint64_t> bytesTotal{0};
    std::atomic<uint64_t> packetBytesMin{0};
    std::atomic<uint64_t> packetBytesMax{0};
    std::atomic<uint64_t> packetErrors{0};

    /*
     * ★★ 提交成功次数 与 回调进来次数 —— 专为「提交请求 ≠ URB 完成」这个
     *    自相矛盾的现象加的。四个数一摆就能切开：
     *
     *      提交请求 > 提交成功   → 有 URB 填好了却没走到 submit（代码路径问题）
     *      提交成功 > 回调       → 提交上去了，但 libusb 没把回调送回来
     *      回调 > 完成           → 回调进来了、状态却不是 COMPLETED
     *      回调 = 完成 = 提交成功 → 说明上面几层都没问题，问题在别处
     *
     *    实测（p55，DSD256 冷启动首播）：提交请求 20001 / 完成 19983 /
     *    排空到 0 / 无失败无取消 —— 这四个数在代码里**不可能同时成立**，
     *    所以只能靠再加一层观测来定案，不能靠推理。
     */
    std::atomic<uint64_t> submitOk{0};
    std::atomic<uint64_t> callbacks{0};

    std::atomic<int> lastTransferStatus{0};
    std::atomic<uint64_t> feedbackReads{0};
    std::atomic<uint32_t> feedbackLastRaw{0};
    std::atomic<uint64_t> feedbackErrors{0};
    std::atomic<int> feedbackLastActual{0};

    /*
     * 这里故意**没有**欠载计数：它属于数据源（PcmSource::underrunBytes），
     * 因为只有数据源知道补了多少静音、以及当时是否处于暂停。
     * 曾经在这里也记过一份，两份账必然分叉 —— 实测暂停静音被重复计进欠载。
     */

    /*
     * 速率自适应：生效次数，以及下发偏差（ppm）的当前值 / 范围 / 累计。
     *
     * 只记最后一个采样点是不够的 —— 实测报告里末次读到的是整数 6.0（+0 ppm），
     * 而同一轮里包长出现过 28 字节（说明速率确实偏离过标称值）。两者矛盾，
     * 光看末次值无从判断设备是稳定在标称还是持续在 +20ppm 附近波动。
     */
    std::atomic<uint64_t> feedbackApplied{0};
    std::atomic<int> ratePpm{0};
    std::atomic<int> ratePpmMin{0};
    std::atomic<int> ratePpmMax{0};
    std::atomic<int64_t> ratePpmSum{0};

    /*
     * 本次播放的**墙上耗时**（从 URB 开始提交到流结束）。
     *
     * 为什么必须单独记：报告里的「已送音频时长」是从字节数反推的，
     * 它永远等于"我们发出了多少"，**无法区分"连续发"和"中间有洞"** ——
     * 实测两者算出来是同一个数（87418 URB × 8 包 ÷ 8000 = 87.418 秒，
     * 而报告里写的 87.4197 秒就是这么来的），属于循环论证。
     *
     * 只有拿它和墙上时间相减，才能量出设备累计有多久没收到数据 ——
     * 那正是用户听到的"短暂静音"。
     */
    std::atomic<uint64_t> wallClockMs{0};

    /*
     * URB 提交间隔的统计（微秒）。
     *
     * 正常节奏是每个 URB 覆盖 8 个 microframe = 1ms，所以相邻两次提交应稳定在
     * 1000µs 左右。**这个值一旦拉长，就说明 USB 提交线程被饿死了** ——
     * 而它才是"设备断粮"的直接原因。
     *
     * 「缺口」只能说明结果（总共少送了多少），这个才能说明原因（卡了多久）。
     */
    std::atomic<uint64_t> maxSubmitGapUs{0};
    std::atomic<uint64_t> gapsOver4ms{0};
    std::atomic<uint64_t> gapsOver8ms{0};

    /*
     * **穿透**：卡顿时长超过队列余量的部分 —— 那才是真正让设备断粮的量。
     *
     * 只统计"超过 4ms / 8ms"是不够的：队列有 32ms 余量，一个 20ms 的卡顿
     * 完全被吸收、听不出来；只有超过余量的那部分才会变成断音。
     * 这两个数字直接对应"卡了几次、一共断了多久"。
     */
    std::atomic<uint64_t> gapsOverQueue{0};
    std::atomic<uint64_t> punctureTotalUs{0};

    /* seek 清队次数与暂停次数，用于确认这两个功能真的被触发了 */
    std::atomic<uint64_t> flushEvents{0};
    std::atomic<uint64_t> pauseEvents{0};

    void reset() {
        urbsCompleted = 0; urbsFailed = 0; urbsCancelled = 0;
        packetsTotal = 0; bytesTotal = 0;
        packetBytesMin = 0; packetBytesMax = 0; packetErrors = 0;
        submitOk = 0; callbacks = 0;
        lastTransferStatus = 0; feedbackReads = 0; feedbackLastRaw = 0;
        feedbackErrors = 0; feedbackLastActual = 0;
        feedbackApplied = 0; ratePpm = 0;
        ratePpmMin = 0; ratePpmMax = 0; ratePpmSum = 0;
        wallClockMs = 0;
        maxSubmitGapUs = 0; gapsOver4ms = 0; gapsOver8ms = 0;
        gapsOverQueue = 0; punctureTotalUs = 0;
        flushEvents = 0; pauseEvents = 0;
    }
};

class IsoPlayer {
public:
    IsoPlayer();
    ~IsoPlayer();

    IsoPlayer(const IsoPlayer&) = delete;
    IsoPlayer& operator=(const IsoPlayer&) = delete;

    /*
     * 启动播放。source 的生命周期必须覆盖整个播放期间（调用方持有）。
     * 调用前必须已完成 claim_interface 与 set_interface_alt_setting。
     */
    bool start(libusb_context* ctx, libusb_device_handle* devh,
               const OutputConfig& cfg, PcmSource* source, std::string* err);

    /* 停止并回收全部 URB（阻塞到所有回调返回） */
    void stop();

    /*
     * 立刻丢弃在途 URB 里的旧数据（seek 时用）。
     *
     * 在途 URB 已经被填好、提交给主机控制器了，里面装的是**旧位置**的音频。
     * 只清环形缓冲是不够的 —— 那些 URB 会照常发出去，于是 seek 之后还会听到
     * 最多「队列深度」毫秒的原位置声音。取消它们，回调会用新数据重新填充。
     *
     * 代价：重新填充时环形缓冲可能还没被解码器灌上（seek 刚清空过），
     * 于是那一小段是静音 —— 也就是 seek 时听到的"咔哒"。
     */
    void flushQueue();

    /*
     * 暂停 / 恢复。
     *
     * 暂停不只是"停止解码" —— 那样环形缓冲里那一秒还会照常放完，
     * 按了暂停要等一秒才真的静音。这里把在途 URB 一并丢弃并停止提交，
     * 声音立刻停；恢复时把 URB 重新灌上再提交，环形缓冲原封不动，
     * 所以恢复是瞬时的、音频内容也接得上。
     */
    void setPaused(bool p);

    /*
     * 输出字节转储 —— bit-perfect 验证用。
     *
     * 把交给 USB 的**有效字节**（不含欠载补的静音）原样记下来，
     * 停止时落盘。拿它和 PC 上用 ffmpeg 解出的参考 PCM 逐字节比对，
     * 就能证明（或证伪）"送进 DAC 的字节 == 文件里的样本"。
     *
     * ★ 只累积在内存里，**绝不在 USB 提交线程上做文件 I/O** ——
     *   那个线程是实时的，一次阻塞就会制造出我们刚花大力气修好的卡顿。
     *   上限 kDumpLimitBytes，超出后停止累积并在报告里注明。
     */
    void setDumpEnabled(bool e) { dumpEnabled_.store(e, std::memory_order_relaxed); }
    /* 把累积的字节写到 path。返回实际写入的字节数，0 表示没东西可写。 */
    size_t writeDump(const std::string& path, bool* truncated, std::string* err);

    bool isRunning() const { return running_.load(std::memory_order_acquire); }

    /*
     * 由 feedback 端点反推出的**设备实际速率**（Hz）。
     *
     * ★ 这是唯一能识破设备的办法。设备对任何 SET_CUR 都回读成功
     *   （44000 也照样"一致 ✓"），但它**只认 8 个标准速率**，其余
     *   默默忽略、继续按上一次的速率跑。只有 feedback 端点会说实话 ——
     *   它报的是设备自己消耗样本的速率，与我们的组包方式无关。
     *
     * 返回 false 表示没有可信读数（无 feedback 端点 / 一次都没读到 / 值为 0）。
     * perFrame 回传设备用的是哪种单位（每 1ms 帧，还是每 microframe）。
     */
    bool measuredRate(double* hz, double* ppm, bool* perFrame) const;

    std::string statsReport() const;

    /* 供 C 回调转发，勿直接调用 */
    void onIsoComplete(libusb_transfer* tr);
    void onFeedbackComplete(libusb_transfer* tr);

private:
    void isoWorkerLoop();
    void prepareTransfer(libusb_transfer* tr);

    /*
     * 取下一个 microframe 该发的字节数。
     *
     * 用带状态的小数累加器，而不是原来的「按包序号算 floor((n+1)S)-floor(nS)」——
     * 那个公式要求速率恒定，而速率现在会随设备反馈在流中途变化。
     * 累加器天然支持变速，且同样不会累积漂移。
     *
     * 只在 libusb 事件线程上调用（prepareTransfer 的调用者都在这个线程）。
     */
    int nextPacketBytes();

    /*
     * 把设备的 feedback 值接进速率控制。
     *
     * ★ 这是异步 DAC 的关键回路，之前一直是断的：我们读了 feedback 却只拿去
     *   打印报告，包长照启动时的标称速率算死。实测这台设备要求 6.00012
     *   样本/µframe（+20.3 ppm）而我们只发 6.00000，于是每秒少 0.977 个样本，
     *   DAC 内部 FIFO 被慢慢抽干，每 60~90 秒见底一次 = 一次可闻的卡顿 ——
     *   而这一侧所有计数器（欠载、URB 失败、包错误）全都是干净的，
     *   因为故障发生在设备的 FIFO 里，我们看不见。
     */
    void applyFeedback(uint32_t raw, int bytes);

    /*
     * 记一次提交卡顿。按 gapUs 降序保留前 kMaxStalls 条，并统计
     * **超过队列余量**的次数与累计穿透时长。
     */
    void recordStall(uint64_t gapUs);

    void releaseAll();

    OutputConfig cfg_{};
    PcmSource* source_ = nullptr;

    libusb_context* ctx_ = nullptr;
    libusb_device_handle* devh_ = nullptr;

    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};

    /*
     * 暂停状态。数据源据此改为"只送静音、不读环形缓冲"。
     *
     * 注意它**不阻止 URB 的提交** —— 流必须一直跑着，DAC 才不会失锁。
     * 暂停的"立刻静音"是靠取消在途 URB 实现的（那几个里面装的是真实音频）。
     */
    std::atomic<bool> paused_{false};

    std::vector<libusb_transfer*> transfers_;
    std::vector<std::vector<uint8_t>> buffers_;
    int bufferCapacity_ = 0;

    libusb_transfer* feedbackTransfer_ = nullptr;
    std::vector<uint8_t> feedbackBuffer_;

    std::thread isoThread_;

    /*
     * 串行化 start / stop / flushQueue / 析构这些会动线程和 URB 的操作。
     *
     * ★ 这不是"防御性编程"，是**实测崩溃的直接原因**：
     *
     *     std::__ndk1::thread::join(): Invalid argument  →  abort()
     *
     *   `stop()` 里那句 `if (isoThread_.joinable()) isoThread_.join();` **不是
     *   原子的**。两个线程同时进来会双双看到 joinable()==true，然后一个
     *   join 成功、另一个对着**已经被 join 过**的 thread 再调 join ——
     *   libc++ 直接抛 system_error，没人接就 abort。
     *
     *   触发路径：Kotlin 侧把"停止播放"改成了异步派发到 worker 线程，
     *   而某些调用方紧接着又同步调 nativeClose/nativeStop —— 两条线程
     *   同时动同一个 IsoPlayer。
     *
     *   在原生层堵住它，上层怎么调都不会崩。mutable 是因为 stop() 是 const
     *   语义上不需要，但析构路径要拿。
     */
    mutable std::mutex lifecycleMutex_;

    uint64_t packetsSubmitted_ = 0;
    uint64_t bytesSubmitted_ = 0;

    /* 上一次提交 URB 的时刻（微秒）。0 = 尚未提交过，用于跳过第一次。 */
    uint64_t lastSubmitUs_ = 0;

    /*
     * 最严重的几次提交卡顿，按严重程度降序保留。
     *
     * 只记"最大 691ms"是不够的 —— 不知道它发生在第几秒，就无法和用户
     * 听到的那一次对上。这里把时刻一起记下来，才能真正定位。
     * 只有 libusb 事件线程写，读者（报告）最多看到稍旧的内容。
     */
    struct StallRecord {
        uint64_t atMs = 0;        // 距本次播放开始
        uint64_t gapUs = 0;       // 卡了多久
        uint64_t overQueueUs = 0; // 其中超过队列余量的部分（0 = 被队列吸收了）
    };
    static constexpr int kMaxStalls = 8;
    StallRecord stalls_[kMaxStalls];
    int stallCount_ = 0;

    /*
     * 输出字节转储缓冲。32MB ≈ 48k/24bit 下 111 秒，够验证用；
     * 只由 libusb 事件线程写、JNI 线程在停止后读，两者不会重叠。
     *
     * ★ 启动时 reserve 到 kDumpLimitBytes —— 这**不是优化，是正确性要求**。
     *   append 会在这个实时线程上跑，一旦 vector 需要扩容就会原地复制
     *   几十 MB，把提交间隔卡出几百毫秒（实测 477ms）。详见 start() 里的说明。
     */
    static constexpr size_t kDumpLimitBytes = 32u * 1024u * 1024u;
    std::atomic<bool> dumpEnabled_{false};
    std::vector<uint8_t> dumpBuf_;
    bool dumpTruncated_ = false;

    // 每 microframe(高速) / frame(全速) 的样本数，以 1e-6 样本为单位的定点数。
    //
    // 注意单位是「样本」而不是「字节」—— 这是必须的。44100Hz 时每 microframe
    // 是 5.5125 个样本，若按字节分配会得到 33/34 字节的包，而这两个长度分别
    // 对应 5.5 和 5.667 个样本，**没有一个包装着整数个样本**，样本被反复截断，
    // 设备侧样本流错位后表现为持续失真。
    // 按样本分配则得到 5 或 6 个样本（30 或 36 字节），每个包都完整，
    // 平均值仍是精确的 5.5125 样本/µframe。
    /* scaledSamplesPerUnit_ 与 sampleAccumulator_ 共用的定点标度：1e-6 样本 */
    static constexpr uint64_t kSampleScale = 1000000ULL;

    uint64_t scaledSamplesPerUnit_ = 0;

    /*
     * 小数累加器，定点单位 1e-6 样本。
     * 每分配一个 microframe 就加上一次 scaledSamplesPerUnit_，
     * 取整数部分发出、余数留下 —— Bresenham 的流式写法。
     */
    uint64_t sampleAccumulator_ = 0;

    uint64_t unitsPerSecond_ = 8000;
    int frameBytes_ = 0;

    ToneStats stats_;
    std::atomic<int> activeTransfers_{0};
    std::atomic<uint64_t> startTimeMs_{0};
};

}  // namespace hifi
