package com.hifiprobe

/**
 * 把解析结果渲染成可读报告。刻意用等宽友好的排版，方便直接从手机屏幕抄下来。
 */
object Report {

    fun uac(r: ParseResult): String {
        val sb = StringBuilder()

        sb.appendLine("═══ UAC 描述符解析结果 ═══")
        sb.appendLine("USB Audio Class 版本: UAC${r.uacVersion}")
        sb.appendLine("AudioControl 接口号: ${if (r.acInterface >= 0) r.acInterface else "未找到"}")
        sb.appendLine()

        if (r.terminals.isNotEmpty()) {
            sb.appendLine("── 终端 (Terminal) ──")
            for (t in r.terminals) {
                sb.appendLine("  [${if (t.isInput) "IN " else "OUT"}] ID=${t.terminalId}  ${t.typeName}")
            }
            sb.appendLine()
        }

        if (r.clocks.isNotEmpty()) {
            sb.appendLine("── 时钟实体 (Clock Source, UAC2) ──")
            for (c in r.clocks) {
                sb.appendLine(
                    "  ID=${c.clockId}  " +
                            (if (c.isInternalFixed) "内部固定时钟" else "外部/可变时钟") +
                            "  采样率可控=${if (c.supportsFreqControl) "是" else "否"}"
                )
            }
            sb.appendLine()
        }

        if (r.featureUnits.isNotEmpty()) {
            sb.appendLine("── 功能单元 (Feature Unit) ──")
            for (f in r.featureUnits) {
                sb.appendLine(
                    "  ID=${f.unitId} 源=${f.sourceId} 声道数=${f.channelCount}  " +
                            "硬件音量=${if (f.hasMasterVolume) "支持 ✓" else "不支持"}" +
                            "  静音=${if (f.hasMasterMute) "支持" else "不支持"}"
                )
            }
            sb.appendLine("  ※ 支持硬件音量意味着可以在数字域满刻度输出的前提下调节音量，音质最优")
            sb.appendLine()
        }

        sb.appendLine("── 音频流接口 (AudioStreaming) ──")
        if (r.streaming.isEmpty()) {
            sb.appendLine("  （无）")
        }
        for (si in r.streaming) {
            sb.appendLine("  接口 ${si.interfaceNumber}  (UAC${si.uacVersion})")
            for (alt in si.alts.sortedBy { it.alt }) {
                val ep = alt.dataOutEndpoint
                sb.appendLine("    alt ${alt.alt}: 终端链接=${alt.terminalLink} " +
                        "数据端点=${ep?.let { "0x%02X".format(it.address) } ?: "无"}")

                if (ep != null) {
                    sb.appendLine(
                        "      端点属性: ${ep.syncTypeText}  用途=${ep.usageText}"
                    )
                    sb.appendLine(
                        "      wMaxPacketSize=${ep.maxPacketSize} " +
                                "(每 microframe 有效容量 ${ep.effectiveMaxPacket}B) " +
                                "bInterval=${ep.interval}"
                    )
                    // 用端点带宽反推该 alt 能承载的最高采样率，与格式表互相印证
                    val fb = alt.formats.firstOrNull()
                    if (fb != null && fb.channels > 0 && fb.subframeSize > 0) {
                        val frameBytes = fb.channels * fb.subframeSize
                        val maxRate = ep.effectiveMaxPacket * 8000L / frameBytes
                        sb.appendLine("      带宽上限推算: 约 ${maxRate}Hz")
                    }
                }

                for (f in alt.formats) {
                    val rateText = when {
                        f.isContinuous ->
                            "${f.continuousMin} ~ ${f.continuousMax}Hz (连续)"
                        f.ratesFromClock ->
                            "速率由 Clock Source 控制，描述符未给速率表 ⚠"
                        else ->
                            f.discreteRates.joinToString(", ") { "${it}Hz" }
                    }

                    val flag = when {
                        f.exactContainer -> "  [容器无歧义 ✓]"
                        f.ambiguousContainer -> "  [24bit 装 4 字节容器，对齐方式存疑 ⚠]"
                        else -> ""
                    }
                    val dsd = if (f.isRawDsd) "  ★ raw DSD（原生 DSD 通道，不是 PCM）" else ""
                    sb.appendLine(
                        "      格式: ${f.channels}ch  ${f.bitResolution}bit  " +
                                "子帧${f.subframeSize}B  $rateText$flag$dsd"
                    )
                    if (f.isRawDsd) {
                        /*
                         * ★ 两条只有实测过才知道的事，写在这里免得下次再推一遍：
                         *
                         *   一、请求的"采样率"是 **DSD 位率 ÷ 32**，不是位率本身。
                         *       ALSA 的 DSD_U32_BE 每个 32-bit 字装 32 个 DSD 位。
                         *       按"1 位/字"算的话 DSD256 要 22.6 MB/s，
                         *       而本端点每 microframe 只有 ${ep?.effectiveMaxPacket ?: 0}B
                         *       （≈6.2 MB/s），根本装不下 —— 带宽本身就否掉了那种解释。
                         *
                         *   二、设备**不会**告诉我们这件事：它的 FORMAT_TYPE_I
                         *       是 6 字节截断的，bSamFreqType 字段压根不存在，
                         *       PCM 那几个 alt 也一样。只能按规范推断 + 实测 feedback 验证。
                         */
                        sb.appendLine("         ↳ 请求速率 = DSD 位率 ÷ 32（每 32-bit 字装 32 位）")
                        sb.appendLine("           DSD64→88200  DSD128→176400  DSD256→352800  DSD512→705600")
                        sb.appendLine("         ⚠ 描述符里没有速率表，以上是规范推断，要用 ⑨ 实测确认")
                    }
                }

                alt.feedbackEndpoint?.let {
                    sb.appendLine("      ⤷ feedback 端点 0x%02X (异步模式必需)".format(it.address))
                }
            }
            sb.appendLine()
        }

        sb.appendLine("── 能力评估 ──")
        sb.appendLine("  ${r.dopCapability()}")

        val rec = r.recommendTone()
        if (rec != null) {
            sb.appendLine()
            sb.appendLine("── 推荐的出声测试参数 ──")
            sb.appendLine("  接口=${rec.streaming.interfaceNumber}  alt=${rec.alt.alt}")
            sb.appendLine("  端点=0x%02X".format(rec.endpoint.address))
            sb.appendLine("  采样率=${rec.rate}Hz")
            sb.appendLine("  声道=${rec.format.channels}  位深=${rec.format.bitResolution}bit")
            sb.appendLine("  子帧=${rec.format.subframeSize} 字节")
            sb.appendLine("  feedback=${rec.feedbackEndpoint?.let { "有" } ?: "无"}")
        } else {
            sb.appendLine()
            sb.appendLine("  ✗ 找不到可用于播放的 OUT 数据端点，无法进行出声测试")
        }

        if (r.warnings.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("── 警告 ──")
            r.warnings.forEach { sb.appendLine("  ⚠ $it") }
        }

        return sb.toString()
    }

    fun hexDump(data: ByteArray, limit: Int = 512): String {
        val sb = StringBuilder()
        val n = minOf(data.size, limit)
        for (i in 0 until n step 16) {
            sb.append("%04X  ".format(i))
            val end = minOf(i + 16, n)
            for (j in i until end) {
                sb.append("%02X ".format(data[j]))
                if (j == i + 7) sb.append(' ')
            }
            for (j in end until i + 16) {
                sb.append("   ")
                if (j == i + 7) sb.append(' ')
            }
            sb.append(" |")
            for (j in i until end) {
                val c = data[j].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.appendLine("|")
        }
        if (data.size > limit) {
            sb.appendLine("... 共 ${data.size} 字节，仅显示前 $limit 字节")
        }
        return sb.toString()
    }
}
