package com.hifiprobe

/*
 * UAC1 / UAC2 描述符解析器
 *
 * 输入是 UsbDeviceConnection.getRawDescriptors() 拿到的原始描述符字节流。
 * 之所以不用 Android 的 UsbInterface/UsbEndpoint 对象，是因为它们会把
 * 类专属描述符（CS_INTERFACE / CS_ENDPOINT）塞进 extra 里不做解析，而
 * 采样率表、时钟实体、音量控制位恰恰全都在那些字节里。
 *
 * 参考：USB Audio Class 1.0 规范 §3.7、UAC 2.0 规范 §4.7
 */

// ---------------------------------------------------------------------------
// 描述符常量
// ---------------------------------------------------------------------------
private object DT {
    const val DEVICE = 0x01
    const val CONFIG = 0x02
    const val INTERFACE = 0x04
    const val ENDPOINT = 0x05
    const val CS_INTERFACE = 0x24
    const val CS_ENDPOINT = 0x25
}

private object AudioSubclass {
    const val CONTROL = 0x01
    const val STREAMING = 0x02
}

/** AudioControl 接口下的类专属描述符子类型 */
private object AcSub {
    const val HEADER = 0x01
    const val INPUT_TERMINAL = 0x02
    const val OUTPUT_TERMINAL = 0x03
    const val MIXER_UNIT = 0x04
    const val SELECTOR_UNIT = 0x05
    const val FEATURE_UNIT = 0x06
    const val CLOCK_SOURCE = 0x0A       // 仅 UAC2
    const val CLOCK_SELECTOR = 0x0B     // 仅 UAC2
}

/** AudioStreaming 接口下的类专属描述符子类型 */
private object AsSub {
    const val AS_GENERAL = 0x01
    const val FORMAT_TYPE = 0x02
}

private val TERMINAL_TYPES = mapOf(
    0x0100 to "USB Undefined",
    0x0101 to "USB Streaming",
    0x0200 to "Input Undefined",
    0x0201 to "Microphone",
    0x0300 to "Output Undefined",
    0x0301 to "Speaker",
    0x0302 to "Headphones",
    0x0303 to "Head Mounted Display Audio",
    0x0304 to "Desktop Speaker",
    0x0305 to "Room Speaker",
    0x0306 to "Communication Speaker",
    0x0307 to "Low Frequency Effects Speaker",
    0x0601 to "Analog Connector",
    0x0602 to "Digital Audio Interface",
    0x0603 to "Line Connector",
    0x0604 to "Legacy Audio Connector",
    0x0605 to "SPDIF Interface",
    0x0606 to "1394 DA Stream",
    0x0607 to "1394 DV Stream",
    0x0701 to "Handset",
    0x0702 to "Headset",
    0x0703 to "Speakerphone",
    0x0704 to "Echo Suppressing Speakerphone",
    0x0705 to "Echo Canceling Speakerphone"
)

// ---------------------------------------------------------------------------
// 数据模型
// ---------------------------------------------------------------------------
/** 描述符没给速率表时用来兜底的标准速率集合 */
val STANDARD_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)

/**
 * UAC2 `AS_GENERAL.bmFormats` 的 bit 31 —— `UAC2_FORMAT_TYPE_I_RAW_DATA`
 * （Linux 内核 `include/linux/usb/audio-v2.h` 里的定义，ALSA 认作 `fp->dsd_raw`）。
 *
 * 置位表示这个 alt setting 走的是**裸 DSD 位流**，不是 PCM。
 */
const val RAW_DATA_BIT: Int = Int.MIN_VALUE

data class AudioFormat(
    val formatType: Int,
    val channels: Int,
    val subframeSize: Int,
    val bitResolution: Int,
    val discreteRates: List<Int>,
    val continuousMin: Int,
    val continuousMax: Int,
    /**
     * true 表示本描述符里没有速率表，采样率完全由 Clock Source 的
     * SAMPLING_FREQ_CONTROL 决定。部分 XMOS/Cmedia 方案的固件会发出
     * bLength=6 的截断 FORMAT_TYPE_I（标准要求至少 7 字节头），
     * MOONDROP Dawn Pro 就是这种。此时只能用标准速率集合兜底。
     */
    val ratesFromClock: Boolean = false,
    /**
     * AS_GENERAL 里的 `bmFormats` 位图（UAC2 才有）。
     *
     * ★★ 这是**区分 PCM 和 raw DSD 的唯一依据** —— 必须留着。
     *
     *   实测这台设备（MOONDROP Dawn Pro）：
     *     alt 3（32bit PCM）的 FORMAT_TYPE = `06 24 02 01 04 20`
     *     alt 4（raw DSD） 的 FORMAT_TYPE = `06 24 02 01 04 20`   ← **字节完全相同**
     *   只有 AS_GENERAL 的 bmFormats 不同：0x00000001 vs 0x80000000。
     *
     *   以前解析器把 bmFormats 丢掉了，于是 alt 3 和 alt 4 在模型里
     *   一模一样 —— `selectForPlayback` 给 32bit PCM 打分时两者**同分**，
     *   全靠"先遍历到的赢"侥幸选中 alt 3。换台设备把 raw DSD 放在更小的
     *   alt 号上，就会把 PCM 数据灌进 DSD 通道。
     */
    val bmFormats: Int = 1
) {
    /** raw DSD 专用通道（不是 PCM）。选 PCM 的 alt setting 时必须排除 */
    val isRawDsd: Boolean get() = bmFormats and RAW_DATA_BIT != 0

    val isContinuous: Boolean get() = discreteRates.isEmpty() && continuousMax > 0

    /** 无采样率控制能力时，设备只支持单一固定速率 */
    val allRates: List<Int>
        get() = when {
            isContinuous -> listOf(continuousMin, continuousMax)
            ratesFromClock -> STANDARD_RATES
            else -> discreteRates
        }

    /** 子帧字节数正好等于有效位所需字节数，格式无歧义，优先用于测试 */
    val exactContainer: Boolean get() = subframeSize * 8 == bitResolution

    /** 24bit 装在 4 字节容器里时，对齐方式存在歧义，需要额外留意 */
    val ambiguousContainer: Boolean
        get() = !exactContainer && subframeSize == 4 && bitResolution == 24

    fun supports(rate: Int): Boolean = when {
        isContinuous -> rate in continuousMin..continuousMax
        ratesFromClock -> rate in STANDARD_RATES
        else -> discreteRates.contains(rate)
    }
}

class AudioEndpoint(
    val address: Int,
    val attributes: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val refresh: Int,
    val synchAddress: Int,
    val csExtraHex: String
) {
    val isIso: Boolean get() = (attributes and 0x03) == 0x01
    val isOut: Boolean get() = (address and 0x80) == 0

    /** 0=异步(等同 None) 1=Asynchronous 2=Adaptive 3=Synchronous */
    val syncType: Int get() = (attributes shr 2) and 0x03

    /** 0=Data 1=Feedback 2=Implicit Feedback */
    val usage: Int get() = (attributes shr 4) and 0x03

    /** 高带宽 iso 端点的每 microframe 实际容量 */
    val effectiveMaxPacket: Int
        get() = (maxPacketSize and 0x07FF) * (1 + ((maxPacketSize shr 11) and 0x03))

    val syncTypeText: String
        get() = when (syncType) {
            0 -> "None（异步，需读 feedback）"
            1 -> "Asynchronous（异步，需读 feedback）"
            2 -> "Adaptive（自适应）"
            else -> "Synchronous（同步）"
        }

    val usageText: String
        get() = when (usage) {
            0 -> "Data"
            1 -> "Feedback"
            2 -> "Implicit Feedback Data"
            else -> "保留"
        }
}

class AltSetting(
    val alt: Int,
    val terminalLink: Int,
    val endpoints: List<AudioEndpoint>,
    val formats: List<AudioFormat>
) {
    val dataOutEndpoint: AudioEndpoint?
        get() = endpoints.firstOrNull { it.isIso && it.isOut && it.usage == 0 }

    val feedbackEndpoint: AudioEndpoint?
        get() = endpoints.firstOrNull { it.usage == 1 }
}

class StreamingInterface(
    val interfaceNumber: Int,
    val uacVersion: Int,
    val alts: List<AltSetting>
)

class ClockSource(
    val clockId: Int,
    val attributes: Int,
    val controls: Int,
    val assocTerminal: Int
) {
    val isInternalFixed: Boolean get() = (attributes and 0x01) != 0
    val supportsFreqControl: Boolean get() = (controls and 0x01) != 0
}

class FeatureUnit(
    val unitId: Int,
    val sourceId: Int,
    val masterControls: Int,
    val channelCount: Int
) {
    /** bmaControls 的 bit1 表示支持音量控制 */
    val hasMasterVolume: Boolean get() = (masterControls and 0x02) != 0
    val hasMasterMute: Boolean get() = (masterControls and 0x01) != 0
}

class Terminal(val terminalId: Int, val type: Int, val isInput: Boolean) {
    val typeName: String get() = TERMINAL_TYPES[type] ?: "未知(0x%04X)".format(type)
}

/**
 * 设备在描述符里声明的速率能力（所有 alt setting 的并集）。
 *
 * [isEmpty] = 离散表空 **且** 没有连续范围 —— 设备什么都没说。
 */
data class DeclaredRates(
    val discrete: IntArray,
    val contMin: Int,
    val contMax: Int
) {
    val isEmpty: Boolean get() = discrete.isEmpty() && contMax <= 0

    // IntArray 是引用类型，data class 默认的 equals 是按引用比 —— 补上内容比较
    override fun equals(other: Any?): Boolean =
        other is DeclaredRates && discrete.contentEquals(other.discrete) &&
                contMin == other.contMin && contMax == other.contMax

    override fun hashCode(): Int =
        discrete.contentHashCode() * 31 * 31 + contMin * 31 + contMax
}

/** 从能力表里挑出最适合做 P0 出声测试的组合 */
class ToneRecommendation(
    val streaming: StreamingInterface,
    val alt: AltSetting,
    val endpoint: AudioEndpoint,
    val format: AudioFormat,
    val rate: Int,
    val feedbackEndpoint: AudioEndpoint?
)

class ParseResult(
    val acInterface: Int,
    val uacVersion: Int,
    val streaming: List<StreamingInterface>,
    val clocks: List<ClockSource>,
    val featureUnits: List<FeatureUnit>,
    val terminals: List<Terminal>,
    val warnings: MutableList<String>
) {
    /**
     * 选出一组最稳妥的出声测试参数。
     *
     * 优先级：OUT 方向的数据端点 > 采样率控制能力 > 格式无歧义(子帧字节数与
     * 位深匹配) > 速率常见(48000/44100 优先)。若设备只支持高采样率，退而求其次。
     */
    fun recommendTone(): ToneRecommendation? {
        var best: ToneRecommendation? = null
        var bestScore = Int.MIN_VALUE

        for (si in streaming) {
            for (alt in si.alts) {
                val ep = alt.dataOutEndpoint ?: continue
                val fb = alt.feedbackEndpoint
                for (fmt in alt.formats) {
                    val candidates = buildList {
                        if (fmt.supports(48000)) add(48000)
                        if (fmt.supports(44100)) add(44100)
                        addAll(fmt.allRates.filter { it != 48000 && it != 44100 })
                    }
                    for (rate in candidates.distinct()) {
                        var score = 0
                        if (fmt.exactContainer) score += 100
                        if (rate == 48000) score += 50
                        if (rate == 44100) score += 40
                        if (fmt.bitResolution in 16..24) score += 20
                        // 同样安全的前提下优先 24bit —— 更贴近 HiFi 实际使用场景
                        if (fmt.bitResolution == 24) score += 5
                        if (fmt.channels == 2) score += 10
                        // 采样率越低越容易一次成功
                        score -= rate / 10000
                        if (score > bestScore) {
                            bestScore = score
                            best = ToneRecommendation(si, alt, ep, fmt, rate, fb)
                        }
                    }
                }
            }
        }
        return best
    }

    /**
     * 找 **raw DSD** 的 alt setting（`AS_GENERAL.bmFormats` bit 31）。
     *
     * ★ [recommendTone] 是挑 PCM 用的，而且现在会**显式排除** raw DSD；
     *   这个函数专门挑那条 DSD 通道，给诊断页的「⑨ DSD 速率实测」用。
     *
     * ★ 返回的 [ToneRecommendation.rate] **没有意义** —— DSD 该请求多少 Hz
     *   正是我们要测的东西。调用方自己带候选速率过去。
     */
    fun rawDsdAlt(): ToneRecommendation? = selectForDsdPlayback(0)

    /**
     * 为 **DSD 文件**挑 alt setting —— 就是那条 raw DSD 通道。
     *
     * ★★ 和 [selectForPlayback] 是**互斥**的两条路：
     *     · `selectForPlayback` 挑 PCM，而且**显式排除** raw DSD
     *     · 这个专门挑 raw DSD
     *
     *   挑错了两边都是噪音 —— 把 PCM 数据灌进 DSD 通道，DAC 会按 DSD 去解，
     *   出来是刺耳的嘶声；反过来也一样。
     *
     * @param channels 要求的声道数；<=0 表示不限（诊断页只是找一个通道来测速率）
     * @return 返回的 [ToneRecommendation.rate] **没有意义** —— DSD 的速率
     *         由引擎按"位率 ÷ 32"算好，调用方用 `info[OUTPUT_RATE]` 那个值
     */
    fun selectForDsdPlayback(channels: Int): ToneRecommendation? {
        for (si in streaming) {
            for (alt in si.alts) {
                val ep = alt.dataOutEndpoint ?: continue
                val fmt = alt.formats.firstOrNull {
                    it.isRawDsd && (channels <= 0 || it.channels == channels)
                } ?: continue
                return ToneRecommendation(si, alt, ep, fmt, 0, alt.feedbackEndpoint)
            }
        }
        return null
    }

    /**
     * 设备**自己声明**的速率能力 —— 所有 alt setting 的并集。
     *
     * ★ [isEmpty] 为真 = 设备压根没在描述符里写速率表
     *   （MOONDROP Dawn Pro 就是：FORMAT_TYPE_I 是 6 字节截断的）。
     *   这时调用方要回落到标准速率白名单 —— 那也是为它准备的。
     */
    fun declaredRates(): DeclaredRates {
        val discrete = LinkedHashSet<Int>()
        var lo = 0
        var hi = 0
        for (si in streaming) {
            for (alt in si.alts) {
                for (f in alt.formats) {
                    if (f.isContinuous) {
                        lo = if (lo == 0) f.continuousMin else minOf(lo, f.continuousMin)
                        hi = maxOf(hi, f.continuousMax)
                    } else {
                        discrete.addAll(f.discreteRates)
                    }
                }
            }
        }
        return DeclaredRates(discrete.sorted().toIntArray(), lo, hi)
    }

    /**
     * 为「播放某个具体文件」挑 alt setting。
     *
     * 与 [recommendTone] 的分工完全不同：那个是从设备能力出发、挑一组最稳妥的
     * 测试参数；这个的输入是**引擎已经定好的输出参数**（速率由 rate_policy 决策，
     * 线格式由源位深决定），设备这边只能去适配，不能反过来。
     *
     * 因此它不做「哪个更容易成功」的权衡，只做匹配，匹配不上就返回 null：
     * 与其自作主张换一个速率，不如让调用方把原因显示出来。
     *
     * 评分依据（从高到低）：
     *   · 子帧字节数与引擎当前线格式一致 —— 格式完全对齐，一个字节都不用改
     *   · 有效位 ≥ 源位深 —— 容器更宽时高位补零，仍然无损
     *   · 有效位 == 源位深 —— 相等即 bit-perfect
     *   · 子帧字节数与有效位匹配（无对齐歧义）
     *
     * @param rate        引擎决定的输出速率
     * @param channels    声道数，必须与设备一致（不一致无解，直接跳过）
     * @param sourceBits  源文件位深，用于判断是否会掉位
     * @param subframeSize 引擎当前线格式的子帧字节数
     */
    fun selectForPlayback(
        rate: Int,
        channels: Int,
        sourceBits: Int,
        subframeSize: Int
    ): ToneRecommendation? {
        var best: ToneRecommendation? = null
        var bestScore = Int.MIN_VALUE

        for (si in streaming) {
            for (alt in si.alts) {
                val ep = alt.dataOutEndpoint ?: continue
                for (fmt in alt.formats) {
                    if (!fmt.supports(rate)) continue
                    // 声道数对不上是死结：不能靠换容器或补零糊过去
                    if (fmt.channels != channels) continue
                    /*
                     * ★★ 排除 raw DSD 通道。
                     *
                     *   这台设备的 alt 4（raw DSD）和 alt 3（32bit PCM）解析出来
                     *   **一模一样**（子帧 4B / 32bit / 无速率表），打分也同分。
                     *   现在靠"先遍历到的赢"侥幸选中 alt 3 —— 换台设备把 raw DSD
                     *   放在更小的 alt 号上，就会把 PCM 数据灌进 DSD 通道，
                     *   DAC 按 DSD 解 PCM → 刺耳噪音。
                     *
                     *   挑 PCM 的时候必须显式把它排除，不能靠运气。
                     */
                    if (fmt.isRawDsd) continue

                    var score = 0
                    if (fmt.subframeSize == subframeSize) score += 1000
                    when {
                        fmt.bitResolution == sourceBits -> score += 800
                        fmt.bitResolution > sourceBits -> score += 500
                        // 位深不够 = 要截断低位，直接掉出 bit-perfect
                        else -> score -= 500
                    }
                    if (fmt.exactContainer) score += 100
                    // 速率恰好等于源速率时可少一层疑虑（引擎已保证这点，仅作记录）
                    if (fmt.bitResolution in 16..32) score += 10

                    if (score > bestScore) {
                        bestScore = score
                        best = ToneRecommendation(si, alt, ep, fmt, rate,
                            alt.feedbackEndpoint)
                    }
                }
            }
        }
        return best
    }

    /** DoP 需要 24bit 的 176.4k / 352.8k 通道 */
    fun dopCapability(): String {
        val hiRes = streaming.flatMap { it.alts }
            .flatMap { it.formats }
            .filter { it.bitResolution >= 24 }
        val rates = hiRes.flatMap { it.allRates }.toSet()
        val assumed = hiRes.any { it.ratesFromClock }

        val base = when {
            // DoP 是标准 PCM 封装，设备本身不需要懂 DSD —— 有 176.4k/24bit 通道即可
            352800 in rates -> "支持到 DSD128（DoP，占用 352.8kHz 通道）"
            176400 in rates -> "支持到 DSD64（DoP，占用 176.4kHz 通道）"
            else -> "未发现 176.4kHz 通道，DoP 不可用"
        }
        return if (assumed) "$base（速率取自标准集合推断，设备未在描述符中显式声明）" else base
    }
}

// ---------------------------------------------------------------------------
// 解析器
// ---------------------------------------------------------------------------
class UacParser(private val raw: ByteArray) {

    private fun u8(o: Int): Int = raw[o].toInt() and 0xFF
    private fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
    private fun u24(o: Int): Int = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16)
    private fun u32(o: Int): Int = u24(o) or (u8(o + 3) shl 24)

    private val terminals = mutableListOf<Terminal>()
    private val clocks = mutableListOf<ClockSource>()
    private val featureUnits = mutableListOf<FeatureUnit>()
    private val warnings = mutableListOf<String>()

    private var acInterface = -1
    private var detectedUacVersion = 1

    private class AltBuilder(val iface: Int, val alt: Int, val uacVersion: Int) {
        val endpoints = mutableListOf<AudioEndpoint>()
        val formats = mutableListOf<AudioFormat>()
        var terminalLink = -1
        /** UAC2 的声道数来自 AS_GENERAL，而不是 FORMAT_TYPE */
        var channelsFromAsGeneral = 0
        /**
         * AS_GENERAL 的 `bmFormats` 位图（UAC2）。
         *
         * ★ 以前这里根本不存在 —— 解析器取了偏移 10 的声道数，
         *   却把偏移 6~9 的位图跳过了。而它是区分 PCM / raw DSD 的**唯一**依据。
         */
        var bmFormats: Int = 1
    }

    fun parse(): ParseResult {
        val streamingMap = LinkedHashMap<Int, MutableList<AltBuilder>>()

        var off = 0
        var curSubclass = -1
        var curAlt: AltBuilder? = null
        var curUacVersion = 1
        var lastEndpointIndex = -1

        while (off + 2 <= raw.size) {
            val len = u8(off)
            val type = u8(off + 1)
            if (len < 2 || off + len > raw.size) {
                warnings.add("描述符在偏移 $off 处长度非法(len=$len)，停止解析")
                break
            }

            when (type) {
                DT.INTERFACE -> {
                    if (len >= 9) {
                        val ifaceNum = u8(off + 2)
                        val altNum = u8(off + 3)
                        val cls = u8(off + 5)
                        val subclass = u8(off + 6)
                        val protocol = u8(off + 7)
                        curSubclass = subclass
                        // bInterfaceProtocol = 0x20 表示 UAC2，0x00 表示 UAC1
                        curUacVersion = if (protocol == 0x20) 2 else 1
                        if (curUacVersion == 2) detectedUacVersion = 2

                        if (cls == 0x01 && subclass == AudioSubclass.CONTROL) {
                            acInterface = ifaceNum
                        }

                        curAlt = if (cls == 0x01 && subclass == AudioSubclass.STREAMING) {
                            AltBuilder(ifaceNum, altNum, curUacVersion).also { b ->
                                streamingMap.getOrPut(ifaceNum) { mutableListOf() }.add(b)
                            }
                        } else null
                        lastEndpointIndex = -1
                    }
                }

                DT.ENDPOINT -> {
                    if (len >= 7) {
                        val ep = AudioEndpoint(
                            address = u8(off + 2),
                            attributes = u8(off + 3),
                            maxPacketSize = u16(off + 4),
                            interval = u8(off + 6),
                            refresh = if (len >= 8) u8(off + 7) else 0,
                            synchAddress = if (len >= 9) u8(off + 8) else 0,
                            csExtraHex = ""
                        )
                        curAlt?.endpoints?.add(ep)
                        lastEndpointIndex = curAlt?.endpoints?.size?.minus(1) ?: -1
                    }
                }

                DT.CS_ENDPOINT -> {
                    // 端点专属描述符，暂只记录不影响主流程
                    if (lastEndpointIndex >= 0) {
                        // 保留占位：后续如需 EP_GENERAL 的 bmAttributes 可在此扩展
                    }
                }

                DT.CS_INTERFACE -> {
                    if (len >= 3) {
                        val sub = u8(off + 2)
                        when (curSubclass) {
                            AudioSubclass.CONTROL -> parseAudioControl(sub, off, len, curUacVersion)
                            AudioSubclass.STREAMING -> parseAudioStreaming(sub, off, len, curAlt)
                        }
                    }
                }
            }
            off += len
        }

        if (streamingMap.isEmpty()) {
            warnings.add("未发现任何 AudioStreaming 接口——该设备可能不是 USB 音频设备，" +
                    "或者它的音频接口在另一个未激活的配置里")
        }

        val streaming = streamingMap.map { (iface, builders) ->
            StreamingInterface(
                interfaceNumber = iface,
                uacVersion = builders.firstOrNull()?.uacVersion ?: 1,
                alts = builders.map { b ->
                    AltSetting(b.alt, b.terminalLink, b.endpoints, b.formats)
                }
            )
        }

        return ParseResult(
            acInterface = acInterface,
            uacVersion = detectedUacVersion,
            streaming = streaming,
            clocks = clocks,
            featureUnits = featureUnits,
            terminals = terminals,
            warnings = warnings
        )
    }

    private fun parseAudioControl(sub: Int, off: Int, len: Int, uacVersion: Int) {
        when (sub) {
            AcSub.INPUT_TERMINAL, AcSub.OUTPUT_TERMINAL -> {
                if (len >= 7) {
                    terminals.add(
                        Terminal(
                            terminalId = u8(off + 3),
                            type = u16(off + 4),
                            isInput = sub == AcSub.INPUT_TERMINAL
                        )
                    )
                }
            }

            AcSub.FEATURE_UNIT -> {
                if (uacVersion >= 2) {
                    // UAC2: bUnitID(3) bSourceID(4) 之后是若干个 4 字节 bmaControls
                    if (len >= 10) {
                        val count = (len - 6) / 4
                        featureUnits.add(
                            FeatureUnit(
                                unitId = u8(off + 3),
                                sourceId = u8(off + 4),
                                masterControls = u32(off + 5),
                                channelCount = (count - 1).coerceAtLeast(0)
                            )
                        )
                    }
                } else {
                    // UAC1: bUnitID(3) bSourceID(4) bControlSize(5) bmaControls[][bControlSize]
                    if (len >= 8) {
                        val ctrlSize = u8(off + 5)
                        val count = if (ctrlSize > 0) (len - 7) / ctrlSize else 0
                        var master = 0
                        for (b in 0 until ctrlSize.coerceAtMost(2)) {
                            master = master or (u8(off + 6 + b) shl (8 * b))
                        }
                        featureUnits.add(
                            FeatureUnit(
                                unitId = u8(off + 3),
                                sourceId = u8(off + 4),
                                masterControls = master,
                                channelCount = (count - 1).coerceAtLeast(0)
                            )
                        )
                    }
                }
            }

            AcSub.CLOCK_SOURCE -> {
                if (len >= 8) {
                    clocks.add(
                        ClockSource(
                            clockId = u8(off + 3),
                            attributes = u8(off + 4),
                            controls = u8(off + 5),
                            assocTerminal = u8(off + 6)
                        )
                    )
                }
            }

            AcSub.CLOCK_SELECTOR -> {
                if (len >= 5) {
                    val n = u8(off + 4)
                    warnings.add(
                        "检测到 Clock Selector（ID=${u8(off + 3)}，$n 个输入），" +
                                "UAC2 下采样率需打到它引用的 Clock Source 上"
                    )
                }
            }
        }
    }

    private fun parseAudioStreaming(sub: Int, off: Int, len: Int, alt: AltBuilder?) {
        if (alt == null) return
        when (sub) {
            AsSub.AS_GENERAL -> {
                if (len >= 4) {
                    alt.terminalLink = u8(off + 3)
                }
                if (alt.uacVersion >= 2) {
                    // UAC2 AS_GENERAL 布局（bLength 通常为 16）：
                    //   [3] bTerminalLink
                    //   [4] bmControls
                    //   [5] bFormatType
                    //   [6..9]  bmFormats        4 字节位图，极易被漏掉
                    //   [10]    bNrChannels
                    //   [11..14] bmChannelConfig 4 字节位图
                    //   [15]    iChannelNames
                    // 注意 bNrChannels 在偏移 10 而不是 6 —— 偏移 6 处是 bmFormats，
                    // 误读会得到 PCM 位图的低字节（通常是 0x01），从而把声道数错判为 1。
                    if (len >= 11) {
                        alt.channelsFromAsGeneral = u8(off + 10)
                        /*
                         * ★ bmFormats 必须留下来。
                         *
                         *   实测这台设备的 alt 3（32bit PCM）和 alt 4（raw DSD）
                         *   的 FORMAT_TYPE 描述符**字节完全相同**，
                         *   只有这里的 bmFormats 不同：0x01 vs 0x80000000。
                         *   丢掉它就等于分不清哪个是 DSD 通道。
                         */
                        alt.bmFormats = u32(off + 6)
                    }
                }
                // UAC1 的 AS_GENERAL 无声道数，取自 FORMAT_TYPE_I
            }

            AsSub.FORMAT_TYPE -> {
                if (len < 5) return
                val fmtType = u8(off + 3)
                if (fmtType != 0x01) {
                    warnings.add(
                        "遇到非 FORMAT_TYPE_I 的格式（type=$fmtType），" +
                                "可能是 DSD/原始数据格式，本工具暂不解析"
                    )
                    return
                }
                alt.formats.add(parseFormatTypeI(off, len, alt))
            }
        }
    }

    /**
     * FORMAT_TYPE_I 的布局在 UAC1 与 UAC2 下不同：
     *
     *   UAC1: bFormatType(3) bNrChannels(4) bSubframeSize(5)
     *         bBitResolution(6) bSamFreqType(7) tSamFreq[] 每项 3 字节
     *         头部共 8 字节
     *
     *   UAC2: bFormatType(3) bSubslotSize(4) bBitResolution(5)
     *         bSamFreqType(6) tSamFreq[] 每项 4 字节
     *         头部共 7 字节；声道数不在本描述符里，取自 AS_GENERAL
     *
     * bSamFreqType == 0 表示连续范围，此时只有 min/max 两个端点值。
     *
     * 所有字段读取都必须做长度校验：实测 MOONDROP Dawn Pro（XMOS 方案）
     * 发出的 FORMAT_TYPE_I 只有 6 字节，连 bSamFreqType 都没有。若不做
     * 边界检查，会越界读到下一个描述符的字节（那里恰好是端点描述符的
     * bLength=7），把速率个数误判成 7。
     */
    private fun parseFormatTypeI(off: Int, len: Int, alt: AltBuilder): AudioFormat {
        val uac2 = alt.uacVersion >= 2

        if (!uac2) {
            // UAC1 没有 Clock Source 实体，描述符被截断就无从得知速率，不做兜底猜测
            if (len < 8) {
                warnings.add("UAC1 的 FORMAT_TYPE_I 长度仅 $len 字节（至少需 8），无法解析")
                return AudioFormat(1, 2, 2, 16, emptyList(), 0, 0)
            }
            return buildFormat(
                channels = u8(off + 4),
                subframeSize = u8(off + 5),
                bitResolution = u8(off + 6),
                samFreqType = u8(off + 7),
                ratesStart = off + 8,
                rateBytes = 3,
                end = off + len,
                allowClockFallback = false
            )
        }

        val subframeSize = if (len > 4) u8(off + 4) else 0
        val bitResolution = if (len > 5) u8(off + 5) else 0
        val channels = if (alt.channelsFromAsGeneral > 0) alt.channelsFromAsGeneral else 2

        if (len < 7) {
            // 截断：bSamFreqType 字段不存在。速率完全由 Clock Source 的
            // SAMPLING_FREQ_CONTROL 决定，描述符里没有速率表。
            return AudioFormat(
                formatType = 1,
                channels = channels,
                subframeSize = subframeSize,
                bitResolution = bitResolution,
                discreteRates = emptyList(),
                continuousMin = 0,
                continuousMax = 0,
                ratesFromClock = true,
                bmFormats = alt.bmFormats
            )
        }

        return buildFormat(
            channels = channels,
            subframeSize = subframeSize,
            bitResolution = bitResolution,
            samFreqType = u8(off + 6),
            ratesStart = off + 7,
            rateBytes = 4,
            end = off + len,
            allowClockFallback = true,
            bmFormats = alt.bmFormats
        )
    }

    /** 解析速率表区。end 为描述符末尾的绝对偏移，用于界定可读范围。 */
    private fun buildFormat(
        channels: Int,
        subframeSize: Int,
        bitResolution: Int,
        samFreqType: Int,
        ratesStart: Int,
        rateBytes: Int,
        end: Int,
        allowClockFallback: Boolean,
        /** 所属 alt setting 的 `bmFormats`。区分 PCM / raw DSD 的唯一依据 */
        bmFormats: Int = 1
    ): AudioFormat {
        val available = ((end - ratesStart) / rateBytes).coerceAtLeast(0)

        fun fallback() = AudioFormat(
            formatType = 1, channels = channels,
            subframeSize = subframeSize, bitResolution = bitResolution,
            discreteRates = emptyList(), continuousMin = 0, continuousMax = 0,
            ratesFromClock = allowClockFallback, bmFormats = bmFormats
        )

        if (samFreqType == 0) {
            if (available < 2) return fallback()
            val lo = if (rateBytes == 4) u32(ratesStart) else u24(ratesStart)
            val hi = if (rateBytes == 4) u32(ratesStart + 4) else u24(ratesStart + 3)
            return AudioFormat(
                formatType = 1, channels = channels,
                subframeSize = subframeSize, bitResolution = bitResolution,
                discreteRates = emptyList(), continuousMin = lo, continuousMax = hi,
                bmFormats = bmFormats
            )
        }

        val count = samFreqType.coerceAtMost(available)
        val rates = (0 until count).map { i ->
            val o = ratesStart + i * rateBytes
            if (rateBytes == 4) u32(o) else u24(o)
        }

        // 声明了速率个数却一个都读不出来 —— 描述符被截断，退回时钟控制假设
        if (rates.isEmpty() && allowClockFallback) return fallback()

        if (count < samFreqType) {
            warnings.add("FORMAT_TYPE_I 声明 $samFreqType 个采样率，但描述符长度只够 $count 个")
        }
        return AudioFormat(
            formatType = 1, channels = channels,
            subframeSize = subframeSize, bitResolution = bitResolution,
            discreteRates = rates, continuousMin = 0, continuousMax = 0,
            bmFormats = bmFormats
        )
    }
}
