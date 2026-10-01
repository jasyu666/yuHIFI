package com.hifiprobe

/**
 * 原生层入口。
 *
 * 生命周期约定（很重要，违反会直接崩）：
 *   1. nativeOpen(fd) 之前必须先通过 UsbManager.openDevice() 拿到 UsbDeviceConnection
 *   2. nativeOpen() 到 nativeClose() 之间，绝不能调用 connection.close()
 *      —— libusb 包裹的就是这个 fd，关掉它之后原生层所有 ioctl 都会 EBADF
 *   3. 原生层工作期间不要在 Java 侧 claimInterface()，claim 统一由原生层做
 */
object NativePlayer {

    init {
        System.loadLibrary("hifiprobe")
    }

    /** 返回原生句柄，0 表示失败，失败原因用 lastError() 取 */
    external fun nativeOpen(fd: Int): Long

    external fun nativeLastError(): String

    external fun nativeVersion(): String

    /** libusb 视角的设备/配置/接口/端点全量描述 */
    external fun nativeDescribe(handle: Long): String

    /** claim 接口并切换 alt setting（altSetting < 0 表示只 claim 不切换） */
    external fun nativeClaim(handle: Long, ifaceNum: Int, altSetting: Int): String

    external fun nativeRelease(handle: Long, ifaceNum: Int): String

    /**
     * 单独切换 alt setting。必须与 claim 分开调用：
     * 时钟要在接口空闲(alt 0)时切换，切完再激活工作 alt。
     */
    external fun nativeSetAltSetting(handle: Long, ifaceNum: Int, alt: Int): String

    /**
     * 逐个尝试采样率，报告设备接受哪些。
     *
     * ⚠ 这个只问「设备收不收」，**查不出设备实际在跑什么速率** ——
     * SET_CUR 对任何速率都成功，GET_CUR 还会把设定值原样回读
     * （请求 44000 也照样显示「一致 ✓」，而设备根本没换）。
     * 要查真伪用 [nativeScanRatesLive]。
     */
    external fun nativeScanRates(
        handle: Long, acIface: Int, clockId: Int, rates: IntArray
    ): String

    /**
     * 速率真伪验证 —— 逐个速率**实际发流**，读设备 feedback 端点，
     * 报出设备**实际**在跑的速率。
     *
     * 这是唯一能识破设备的办法：feedback 报的是设备自己消耗样本的速率，
     * 与我们的组包方式无关。设备对不支持的速率不会报错，只会静默忽略、
     * 继续按上一次的速率跑 —— 只有这里能看出来。
     *
     * ⚠ **会阻塞 settleMs × rates.size 毫秒（默认约 4 秒），必须在后台线程调用。**
     * 期间会听到一串短促的 1kHz 蜂鸣，属正常。
     */
    external fun nativeScanRatesLive(
        handle: Long, acIface: Int, clockId: Int,
        asIface: Int, asAlt: Int, epAddr: Int, feedbackEp: Int,
        channels: Int, subframeSize: Int, bitResolution: Int,
        maxPacketSize: Int, urbCount: Int, packetsPerUrb: Int,
        settleMs: Int, rates: IntArray
    ): String

    /**
     * 探测一首曲目的元信息（文件库扫描用）。**不要在主线程调** ——
     * 它要开一次 FFmpeg 读文件头，几百首就是几十秒。
     *
     * 返回 `String[9]`：
     * ```
     * [0] 标题  [1] 艺术家  [2] 专辑  [3] 编码
     * [4] 采样率 [5] 声道数 [6] 位深 [7] 时长(ms)
     * [8] 封面写出的字节数（0 = 没有封面）
     * ```
     *
     * [coverOutPath] 给一个磁盘路径，原生层会把内嵌封面**直接写进去**，
     * 只回传字节数。这样几百首封面的传输不必经过 JNI 数组，
     * 而且顺带完成了磁盘缓存。传 null / 空串表示不提取封面。
     */
    external fun nativeProbeFile(fd: Int, coverOutPath: String?): Array<String>

    /**
     * 按需读取**一首**曲目的内嵌歌词。返回原始字节，没有歌词返回空数组。
     *
     * ★★ **一次只读一首，不要拿它去扫描全库** —— 歌词动辄几 KB，
     *   一千多首全量回传就是好几 MB 的无谓 JNI 拷贝，而它只在正在播放页
     *   用得上。扫描走 [nativeProbeFile]，那条路不碰歌词。
     *
     * ★★ 返回 byte[] 而不是 String：标签编码不规范的不少
     *   （ID3v2.3 不带编码标志时按 Latin-1 存的很常见），在原生层转
     *   `jstring` 碰到非法 UTF-8 会**直接 abort**。字节传回来让 Kotlin 解，
     *   非法字节被替换成 U+FFFD —— 最坏是乱码，不会崩。
     */
    external fun nativeProbeLyrics(fd: Int): ByteArray

    /**
     * 是否信任 44.1k 家族（8 个标准速率全部直通）。
     *
     * 默认开。关掉退回旧的「只走 48k 家族、44.1k 家族一律重采样」策略，
     * 行为与 p17 及以前逐字节一致 —— 留作现场回退。
     *
     * 不加 handle：标志是全局的，且必须在**打开文件之前**设好。
     */
    external fun nativeSetAllowAllStandardRates(allow: Boolean)

    /**
     * 把**设备自己声明的速率能力**交给引擎。
     *
     * ★ 必须在 [nativeOpenFile] **之前**调 —— 速率决策发生在 openFile 里面。
     *
     * ★ [discrete] 传空数组、[contMin]/[contMax] 传 0 = 设备没声明速率
     *   （描述符被截断，Dawn Pro 就是），引擎回落到标准速率白名单，
     *   **行为与以前逐字节一致**。
     *
     * 设备声明了就听它的 —— 换任何设备都自动适配，见 `rate_policy.h`。
     */
    external fun nativeSetDeviceRates(h: Long, discrete: IntArray, contMin: Int, contMax: Int)

    /**
     * 下发采样率并回读校验。UAC1 打在端点上、UAC2 打在 Clock Source 实体上。
     * 不同固件对 wIndex 高字节该填 AC 还是 AS 接口并不一致，因此内部会依次
     * 尝试多种组合，报告里会指出哪一种成功。
     */
    external fun nativeSetSampleRate(
        handle: Long, uacVersion: Int, acIface: Int, asIface: Int, asAlt: Int,
        clockId: Int, epAddr: Int, rate: Int
    ): String

    /** 启动 1kHz 测试音 */
    external fun nativeStartTone(
        handle: Long, epAddr: Int, feedbackEp: Int, rate: Int,
        channels: Int, subframeSize: Int, bitResolution: Int,
        seconds: Int, maxPacketSize: Int, urbCount: Int, packetsPerUrb: Int
    ): String

    external fun nativeStats(handle: Long): String

    external fun nativeIsPlaying(handle: Long): Boolean

    /**
     * 系统音频输出**被系统掐掉了**（取走即清）。
     *
     * ★ 和 [nativeIsPlaying] 是两件事：那个回答"此刻在不在放"，
     *   这个回答"刚刚被掐了、这条流已经废了"。
     *
     * AAudio 在任何一次音频路由变化时都会掐掉现有流 —— 插拔 USB 解码器、
     * 直连↔系统音频互相切换都算。而且 error 回调里不能关流（AAudio 明令禁止），
     * 所以那边只记了个标志，由 [PlayerSession.tick] 发现后**把会话停下来并告知用户**。
     *
     */
    external fun nativeSystemOutputDead(handle: Long): Boolean

    /**
     * 重建系统音频输出，**引擎和播放位置都不动**，从当前位置接着放。
     *
     * ★★ 它会阻塞（内部要 `openStream`），**必须放在一条专用线程上调用**，
     *    绝不能是 [PlayerSession] 那条 worker —— 上一版把它放在 worker 上，
     *    结果 USB 拔插那一下它再也没返回，整条播放链路连同界面一起卡死。
     *    （sink 内部现在已不持锁做阻塞调用了，所以它卡住不会拖累 stop()；
     *      但"不拖累别人"不等于"不会卡"。）
     *
     * @return 空串 = 成功，否则是失败原因
     */
    external fun nativeRestartSystemOutput(handle: Long): String

    /** 停止播放，返回最终统计 */
    external fun nativeStop(handle: Long): String

    /** 释放全部资源。调用后 handle 失效，随后才可以 close() 那个 Connection */
    external fun nativeClose(handle: Long)

    // =======================================================================
    //  真实文件播放
    // =======================================================================

    /**
     * 打开音频文件。fd 的所有权自此转交原生层，由它负责关闭。
     * 因此 Kotlin 侧要用 ParcelFileDescriptor.detachFd() 而不是 getFd()。
     */
    external fun nativeOpenFile(handle: Long, fd: Int): String

    /**
     * 开始播放。claim 接口、下发采样率、激活 alt setting、启动数据流
     * 全部在原生层一次完成 —— 这几步有严格顺序要求，不宜分散调用。
     */
    external fun nativeStartPlayback(
        handle: Long, uacVersion: Int, acIface: Int, asIface: Int, asAlt: Int,
        clockId: Int, epAddr: Int, feedbackEp: Int,
        subframeSize: Int, bitResolution: Int, maxPacketSize: Int
    ): String

    /**
     * 探测系统实际会给的采样率（0 = 失败，调用方回落到 48000）。
     *
     * ★ 必须在 [nativeOpenFile] **之前**调：引擎的速率决策发生在 open() 里面，
     *   它得先知道目标速率。开一个临时 AAudio 流读回来再关掉，几毫秒的事。
     */
    external fun nativeProbeSystemAudioRate(): Int

    /**
     * 设定输出模式。**必须在 [nativeOpenFile] 之前调。**
     *
     * @param systemRate 非 0 = 走**系统音频**（AAudio，非 bit-perfect），
     *                   值是 [nativeProbeSystemAudioRate] 拿到的速率；
     *                   0 = 走 **USB 直连**（bit-perfect）
     */
    external fun nativeSetOutputMode(handle: Long, systemRate: Int)

    /**
     * 起播：系统音频。不需要任何 USB 参数。
     *
     * ★ DSD 会被**明确拒绝** —— 裸位流要转成有损 PCM 才能给 AAudio，
     *   而这个是明确排除在外的。失败原因在返回的字符串里。
     */
    external fun nativeStartSystemPlayback(handle: Long): String

    external fun nativeSetPaused(handle: Long, paused: Boolean)

    /*
     * 交叉馈送：开关 + 档位（0=弱 1=中 2=强）。
     *
     * ★ **引擎级**状态，handle 一换就得重设 —— 走 PlayerSession.applyCrossfeed()。
     * ★ 原子变量，解码线程每块读一次，改设置立刻生效，不用重建引擎。
     * ★ 对 DSD 和单声道无效，用 nativeCrossfeedState 的 usable 位问。
     */
    external fun nativeSetCrossfeed(handle: Long, on: Boolean, level: Int)

    /** 返回 {开着吗, 这个文件能不能用, 档位} —— 界面拿来决定状态条怎么写 */
    external fun nativeCrossfeedState(handle: Long): IntArray

    /*
     * 软件音量：开关 + 增益（分贝，<= 0）。
     *
     * ★★ 注意和上面那组 [nativeVolumeRange] / [nativeSetVolume] **不是一回事**：
     *    · [nativeSetVolume]  = **设备的 UAC 硬件音量**，不碰样本，**保持 bit-perfect**，
     *      但设备要支持（Dawn Pro 和 TANJIM BUNNY DSP 都**不支持**）。
     *    · 这一个             = **软件数字音量**，在样本上做乘法，**必然不是 bit-perfect**。
     *      好处是任何设备都能用；而且开着时输出位深会提到 24bit，
     *      对 16bit 源来说 −48dB 以内**不丢有效位**。
     *
     * ★ 原子变量，解码线程每块读一次 → 拖动实时生效，不用重建引擎。
     * ★ 对 DSD 无效（DSD 走原生位流）。
     */
    external fun nativeSetSoftwareVolume(handle: Long, on: Boolean, gainDb: Float)

    /** 返回 {开着吗, 当前 dB × 100, 实际输出位深} */
    external fun nativeSoftwareVolumeState(handle: Long): LongArray

    /**
     * A/B 开关：是否跟随设备的 feedback 速率。
     *
     * **只在下次起播时生效** —— 中途变速会让下发速率跳变，那本身就会引入一次
     * 卡顿，把对比结果污染掉。默认开。
     */
    external fun nativeSetFollowFeedback(handle: Long, follow: Boolean)

    /**
     * 在途 URB 深度。**下次起播生效。**
     *
     * 这一个参数同时决定两件事，且两者**绑死**：
     *   · 抗调度卡顿能力 —— 实测 USB 提交线程会被挂起 16~25ms，
     *     队列余量必须大于它，否则设备断粮（就是听到的断音）
     *   · seek 迟钝程度 —— seek 后最多还有「深度」毫秒的旧音频放完
     */
    external fun nativeSetUrbCount(handle: Long, count: Int)

    /**
     * seek 时是否丢弃在途 URB。**下次起播生效。**
     *
     * 开：seek 后不会再有旧位置的声音，代价是重填时可能有一小段静音
     * 关：seek 后最多还有「队列深度」毫秒的旧音频
     */
    external fun nativeSetFlushQueueOnSeek(handle: Long, enable: Boolean)

    /**
     * 输出字节转储（bit-perfect 验证用）。
     *
     * 开启后，原生层把**交给 USB 的有效字节**（不含欠载补的静音）记在内存里
     * （上限 32MB），停止播放时写到 [path]。
     *
     * path 用 getExternalFilesDir(null)，那个目录不需要任何存储权限，
     * adb pull 也能直接取 —— 和诊断报告放一起。
     */
    external fun nativeSetOutputDump(handle: Long, enable: Boolean, path: String?)

    external fun nativeSeekTo(handle: Long, ms: Int): String

    /** 停止播放并释放接口；文件仍保持打开，可再次播放或 seek */
    external fun nativeStopPlayback(handle: Long): String

    external fun nativeEngineStatus(handle: Long): String

    external fun nativeEngineReady(handle: Long): Boolean

    // =======================================================================
    //  UAC 硬件音量（Feature Unit）
    //
    //  这是独占模式下唯一还能用的音量控制。系统音量够不着（我们的采样点
    //  从不经过 AudioFlinger），软件增益又会破坏 bit-perfect ——
    //  而硬件音量是让 DAC 在收到满分辨率数据之后自行衰减，音质最优。
    // =======================================================================

    /**
     * 查询音量范围与当前值，返回 long[5]：
     * `[min, max, res, cur, ok]`，前四项单位是 1/256 dB，ok=0 表示不支持。
     */
    external fun nativeVolumeRange(handle: Long, acIface: Int, unitId: Int): LongArray

    /**
     * 设置音量（单位 1/256 dB）并回读，返回 long[2]：`[回读值, 是否成功]`。
     * 调用前应先用 [nativeVolumeRange] 拿到范围。
     */
    external fun nativeSetVolume(
        handle: Long, acIface: Int, unitId: Int, value: Int
    ): LongArray

    /**
     * 音量探测诊断：列出三个 wIndex 候选各自读到的原始字节。
     *
     * [nativeVolumeRange] 判定不可用时调这个，用来区分「固件没实现 Feature Unit
     * 音量」和「wIndex 还没试对」—— 这台设备对不存在的实体也照答不误，
     * 只看「有没有响应」区分不出来，必须看原始值。
     */
    external fun nativeVolumeProbe(handle: Long, acIface: Int, unitId: Int): String

    /**
     * 引擎状态快照，UI 每秒轮询用。下标见 [EngineInfo]。
     * 句柄无效时返回全 0 数组，不会抛异常。
     */
    external fun nativeEngineInfo(handle: Long): LongArray

    external fun nativeCloseFile(handle: Long)

    /**
     * [nativeEngineInfo] 返回数组的下标。
     *
     * 顺序与 native_bridge.cpp 里 nativeEngineInfo 的写入顺序一致，
     * 改动其中一边必须同步另一边。
     */
    object EngineInfo {
        const val SOURCE_RATE = 0
        const val OUTPUT_RATE = 1
        const val CHANNELS = 2
        const val SOURCE_BITS = 3
        const val SUBFRAME_SIZE = 4
        const val BIT_RESOLUTION = 5
        const val RESAMPLING = 6
        const val DURATION_MS = 7
        const val POSITION_MS = 8
        const val BUFFERED_BYTES = 9
        const val UNDERRUN_BYTES = 10
        const val FINISHED = 11
        const val PAUSED = 12
        /** 目标缓冲水位（字节），配合 [BUFFERED_BYTES] 算真实水位百分比 */
        const val TARGET_BYTES = 13

        /**
         * 当前打开的是不是 DSD 文件（1 = 是）。
         *
         * ★ 界面侧据此**换一条 alt setting 选择路径**：
         *   `selectForPlayback` 挑 PCM 时会**显式排除** raw DSD 通道，
         *   所以 DSD 必须走 `selectForDsdPlayback`。
         */
        const val IS_DSD = 14

        /**
         * 这次播放是不是走**系统音频**（1 = 是）。
         *
         * ★ 界面据此**不能**再显示"直通 · bit-perfect" ——
         *   系统那条路经过 AudioFlinger 混音，蓝牙时还有一层有损编码。
         */
        const val IS_SYSTEM_AUDIO = 15

        const val COUNT = 16
    }

    /**
     * [nativeVolumeRange] 返回数组的下标。前四项单位均为 1/256 dB，
     * 这是 UAC 规范采用的定点表示（1 LSB = 1/256 dB）。
     */
    object VolumeInfo {
        const val MIN = 0
        const val MAX = 1
        const val RES = 2
        const val CUR = 3
        const val OK = 4
        /** 实际生效的 wIndex，仅用于诊断输出 */
        const val WIN_INDEX = 5
        /** 实际生效的通道号（0=master，1/2=逐声道），仅用于诊断输出 */
        const val CHANNEL = 6
        const val COUNT = 7
    }
}
