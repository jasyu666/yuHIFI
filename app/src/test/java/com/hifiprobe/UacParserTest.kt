package com.hifiprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实设备导出的原始描述符做回归测试。
 *
 * 数据来源：MOONDROP Dawn Pro（VID 2FC6 / PID F06A，XMOS 方案，UAC2）
 * 在 Xiaomi 23013RK75C / Android 15 上通过 UsbDeviceConnection.getRawDescriptors()
 * 取得，共 361 字节。
 *
 * 这组数据覆盖了两个曾经把解析器带偏的坑：
 *   1. UAC2 AS_GENERAL 的 bNrChannels 在偏移 10（跳过 4 字节 bmFormats），
 *      误按偏移 6 读会把声道数错判成 1
 *   2. FORMAT_TYPE_I 描述符被截断成 6 字节，连 bSamFreqType 都没有，
 *      越界读会拿到下一个描述符的字节（端点 bLength=7）
 */
class UacParserTest {

    private val dawnPro = """
        12 01 00 02 EF 02 01 40  C6 2F 6A F0 AE 9C 01 02
        03 01 09 02 57 01 03 01  00 80 32 08 0B 00 02 01
        00 20 00 09 04 00 00 00  01 01 20 02 09 24 01 00
        02 04 40 00 00 08 24 0A  05 03 07 00 00 11 24 02
        01 01 01 00 05 02 03 00  00 00 00 00 00 00 12 24
        06 03 01 0F 00 00 00 0C  00 00 00 0C 00 00 00 00
        0C 24 03 04 01 03 00 03  05 00 00 00 09 04 01 00
        00 01 02 20 04 09 04 01  01 02 01 02 20 00 10 24
        01 01 05 01 01 00 00 00  02 03 00 00 00 00 06 24
        02 01 02 10 07 05 01 05  08 03 01 08 25 01 00 00
        00 00 00 07 05 81 11 04  00 04 09 04 01 02 02 01
        02 20 00 10 24 01 01 05  01 01 00 00 00 02 03 00
        00 00 00 06 24 02 01 03  18 07 05 01 05 08 03 01
        08 25 01 00 00 00 00 00  07 05 81 11 04 00 04 09
        04 01 03 02 01 02 20 00  10 24 01 01 05 01 01 00
        00 00 02 03 00 00 00 00  06 24 02 01 04 20 07 05
        01 05 08 03 01 08 25 01  00 00 00 00 00 07 05 81
        11 04 00 04 09 04 01 04  02 01 02 20 00 10 24 01
        01 05 01 00 00 00 80 02  03 00 00 00 00 06 24 02
        01 04 20 07 05 01 05 08  03 01 08 25 01 00 00 00
        00 00 07 05 81 11 04 00  04 09 04 02 00 02 03 00
        00 07 09 21 11 01 00 01  22 2B 00 07 05 05 03 08
        00 00 07 05 86 03 08 00  0A
    """.toHexBytes()

    private fun String.toHexBytes(): ByteArray {
        val clean = filter { it.isLetterOrDigit() }
        require(clean.length % 2 == 0) { "十六进制串长度必须是偶数，实际 ${clean.length}" }
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or
                    Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    @Test
    fun `描述符长度应为 361 字节`() {
        assertEquals(361, dawnPro.size)
    }

    @Test
    fun `应识别为 UAC2 且 AudioControl 接口为 0`() {
        val r = UacParser(dawnPro).parse()
        assertEquals(2, r.uacVersion)
        assertEquals(0, r.acInterface)
    }

    @Test
    fun `应解析出 4 个 AudioStreaming alt 且声道数均为 2`() {
        val r = UacParser(dawnPro).parse()
        assertEquals(1, r.streaming.size)

        val si = r.streaming[0]
        assertEquals(1, si.interfaceNumber)
        // alt 0 没有端点（空闲态），alt 1..4 是四种位深
        assertEquals(5, si.alts.size)

        for (alt in si.alts.filter { it.alt != 0 }) {
            val fmt = alt.formats.single()
            // 回归点：这曾经被错读成 1ch（读到了 bmFormats 的低字节 0x01）
            assertEquals("alt ${alt.alt} 声道数错误", 2, fmt.channels)
        }
    }

    @Test
    fun `各 alt 的位深与子帧长度应正确对应`() {
        val si = UacParser(dawnPro).parse().streaming[0]
        val byAlt = si.alts.associateBy { it.alt }

        val a1 = byAlt.getValue(1).formats.single()
        assertEquals(16, a1.bitResolution)
        assertEquals(2, a1.subframeSize)

        val a2 = byAlt.getValue(2).formats.single()
        assertEquals(24, a2.bitResolution)
        assertEquals(3, a2.subframeSize)

        val a3 = byAlt.getValue(3).formats.single()
        assertEquals(32, a3.bitResolution)
        assertEquals(4, a3.subframeSize)

        val a4 = byAlt.getValue(4).formats.single()
        assertEquals(32, a4.bitResolution)
        assertEquals(4, a4.subframeSize)
    }

    @Test
    fun `截断的 FORMAT_TYPE_I 应回退为时钟控制而不是报出错误的速率个数`() {
        val si = UacParser(dawnPro).parse().streaming[0]
        for (alt in si.alts.filter { it.alt != 0 }) {
            val fmt = alt.formats.single()
            // 描述符只有 6 字节，没有速率表
            assertTrue("alt ${alt.alt} 应标记为速率由时钟控制", fmt.ratesFromClock)
            assertTrue("alt ${alt.alt} 不应解析出离散速率", fmt.discreteRates.isEmpty())
        }
    }

    @Test
    fun `不应再产生声明 7 个采样率却读不出的警告`() {
        val r = UacParser(dawnPro).parse()
        val bogus = r.warnings.filter { it.contains("采样率") && it.contains("只够") }
        assertTrue("越界读导致的伪警告仍在: $bogus", bogus.isEmpty())
    }

    @Test
    fun `应解析出 Clock Source 且支持采样率控制`() {
        val r = UacParser(dawnPro).parse()
        assertEquals(1, r.clocks.size)
        val c = r.clocks.single()
        assertEquals(5, c.clockId)
        assertTrue("采样率应可控", c.supportsFreqControl)
    }

    @Test
    fun `应解析出硬件音量支持`() {
        val r = UacParser(dawnPro).parse()
        assertEquals(1, r.featureUnits.size)
        val f = r.featureUnits.single()
        assertEquals(3, f.unitId)
        assertEquals(2, f.channelCount)
        assertTrue("应支持硬件音量", f.hasMasterVolume)
    }

    @Test
    fun `应识别异步模式与 feedback 端点`() {
        val si = UacParser(dawnPro).parse().streaming[0]
        val alt = si.alts.first { it.alt == 2 }

        val ep = alt.dataOutEndpoint
        assertNotNull("应找到 OUT 数据端点", ep)
        assertEquals(0x01, ep!!.address)
        assertEquals("应为异步模式", 1, ep.syncType)

        val fb = alt.feedbackEndpoint
        assertNotNull("应找到 feedback 端点", fb)
        assertEquals(0x81, fb!!.address)
    }

    @Test
    fun `推荐参数应可用且落在设备真实支持的范围内`() {
        val rec = UacParser(dawnPro).parse().recommendTone()
        assertNotNull("必须能给出推荐参数，否则出声测试无法进行", rec)

        // 报告中 AudioManager 列出该设备支持:
        // 48000 / 88200 / 96000 / 176400 / 192000 / 352800 / 384000
        assertTrue("推荐采样率 ${rec!!.rate} 不在设备支持范围内",
            rec.rate in listOf(48000, 88200, 96000, 176400, 192000, 352800, 384000))

        assertEquals(0x01, rec.endpoint.address)
        assertEquals(2, rec.format.channels)
        // 同分情况下优先 24bit，更贴近 HiFi 使用场景
        assertEquals(24, rec.format.bitResolution)
        assertEquals(3, rec.format.subframeSize)
        assertTrue("24bit/3字节属于无歧义容器", rec.format.exactContainer)
    }

    @Test
    fun `DoP 应判定为可用`() {
        // 设备支持 176.4k 与 352.8k，DoP 走标准 PCM 通道即可
        val dop = UacParser(dawnPro).parse().dopCapability()
        assertTrue("DoP 判定不应为不可用，实际: $dop", !dop.contains("不可用"))
    }

    // -----------------------------------------------------------------------
    //  播放选路：与 recommendTone 不同，这里的输入是引擎已定好的输出参数
    // -----------------------------------------------------------------------

    @Test
    fun `16bit 源应选到 16bit 的 alt`() {
        val rec = UacParser(dawnPro).parse().selectForPlayback(
            rate = 48000, channels = 2, sourceBits = 16, subframeSize = 2
        )
        assertNotNull("必须能选到可用的 alt", rec)
        assertEquals(1, rec!!.alt.alt)
        assertEquals(16, rec.format.bitResolution)
        assertEquals(2, rec.format.subframeSize)
        assertTrue("16bit/2字节应是无歧义容器", rec.format.exactContainer)
    }

    @Test
    fun `24bit 源应选到 24bit 的 alt`() {
        val rec = UacParser(dawnPro).parse().selectForPlayback(
            rate = 48000, channels = 2, sourceBits = 24, subframeSize = 3
        )
        assertNotNull(rec)
        assertEquals(2, rec!!.alt.alt)
        assertEquals(24, rec.format.bitResolution)
        assertEquals(3, rec.format.subframeSize)
    }

    @Test
    fun `32bit 源应选到 32bit 的 alt`() {
        val rec = UacParser(dawnPro).parse().selectForPlayback(
            rate = 48000, channels = 2, sourceBits = 32, subframeSize = 4
        )
        assertNotNull(rec)
        assertTrue("应落在 32bit 的 alt 上（3 或 4）", rec!!.alt.alt in listOf(3, 4))
        assertEquals(32, rec.format.bitResolution)
        assertEquals(4, rec.format.subframeSize)
    }

    @Test
    fun `44_1k 源重采样到 48k 后仍应能选到 alt`() {
        // rate_policy 把 44100 家族映射到 48k 家族，所以这里查询的是 48000。
        // 该设备的描述符里没有速率表（ratesFromClock），48000 来自标准集合兜底。
        val rec = UacParser(dawnPro).parse().selectForPlayback(
            rate = 48000, channels = 2, sourceBits = 16, subframeSize = 2
        )
        assertNotNull("44.1k 重采样到 48k 后必须仍可播", rec)
    }

    @Test
    fun `设备不支持的速率应返回 null 而不是硬凑一个`() {
        // 40000 不在标准速率集合里，而该设备没有速率表可依据
        val rec = UacParser(dawnPro).parse().selectForPlayback(
            rate = 40000, channels = 2, sourceBits = 16, subframeSize = 2
        )
        assertNull("宁可返回 null 让上层报错，也不该自作主张换速率", rec)
    }

    @Test
    fun `声道数对不上时应返回 null`() {
        // 声道数不一致是死结：不能靠换容器或补零糊过去
        val rec = UacParser(dawnPro).parse().selectForPlayback(
            rate = 48000, channels = 1, sourceBits = 16, subframeSize = 2
        )
        assertNull(rec)
    }

    @Test
    fun `线格式与设备不符时应优先匹配容器而不是掉位深`() {
        // 引擎按 24bit 源给出 3 字节容器，即便源只有 16bit 有效位，
        // 也应该选 24bit/3字节的 alt（高位补零，仍无损），
        // 而不是退回 16bit/2字节 —— 那样容器对不上，还得反过来改引擎线格式
        val rec = UacParser(dawnPro).parse().selectForPlayback(
            rate = 48000, channels = 2, sourceBits = 16, subframeSize = 3
        )
        assertNotNull(rec)
        assertEquals(3, rec!!.format.subframeSize)
        assertTrue("位深不应低于源位深", rec.format.bitResolution >= 16)
    }

    @Test
    fun `全部常用速率与位深组合都应能选到 alt`() {
        val r = UacParser(dawnPro).parse()
        for (rate in listOf(48000, 96000, 192000, 384000)) {
            for ((bits, sub) in listOf(16 to 2, 24 to 3, 32 to 4)) {
                val rec = r.selectForPlayback(rate, 2, bits, sub)
                assertNotNull("${rate}Hz/${bits}bit 选不到 alt", rec)
                assertTrue(
                    "选中的 alt 位深 ${rec!!.format.bitResolution} 低于源 ${bits}bit，会掉位",
                    rec.format.bitResolution >= bits
                )
                assertEquals("选中的 alt 必须支持目标速率", rate, rec.rate)
            }
        }
    }

    @Test
    fun `空输入不应崩溃`() {
        val r = UacParser(ByteArray(0)).parse()
        assertNull(r.recommendTone())
        assertTrue(r.streaming.isEmpty())
    }

    @Test
    fun `截断到一半的输入不应越界崩溃`() {
        // 逐字节截断，确保任何长度下都不会抛数组越界
        for (n in 0 until dawnPro.size) {
            val r = UacParser(dawnPro.copyOf(n)).parse()
            r.recommendTone()   // 只要求不抛异常
        }
    }
}
