package com.hifiprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 队列逻辑的边界测试。
 *
 * 这些情况在真机上很难逐个试到（尤其是"最后一首播完该不该停"、
 * "开着单曲循环时手动按下一首"），但写错一次就会被用户当成 bug。
 * 放在 JVM 上跑，每次改队列逻辑都能秒级回归。
 */
class PlayQueueTest {

    private fun q(vararg names: String) = PlayQueue(Random(42)).apply {
        setTracks(names.map { Track(uri = "file:///$it.flac", title = it) })
    }

    /** 用标题判断"现在放的是哪首"，比比下标直观 */
    private fun PlayQueue.now(): String? = current()?.title

    // ---- 基本推进 ----

    @Test
    fun `空队列任何操作都返回 null`() {
        val queue = PlayQueue(Random(1))
        assertNull(queue.current())
        assertNull(queue.manualNext())
        assertNull(queue.manualPrev())
        assertNull(queue.onFinished())
        assertEquals(-1, queue.currentIndex)
    }

    @Test
    fun `setTracks 可以指定从第几首开始`() {
        val queue = PlayQueue(Random(1)).apply {
            setTracks(
                listOf(
                    Track("file:///a", title = "a"),
                    Track("file:///b", title = "b"),
                    Track("file:///c", title = "c"),
                ),
                startAt = 1
            )
        }
        assertEquals("b", queue.now())
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun `手动下一首逐个前进`() {
        val queue = q("a", "b", "c")
        assertEquals("a", queue.now())
        assertEquals("b", queue.manualNext()?.title)
        assertEquals("c", queue.manualNext()?.title)
    }

    @Test
    fun `手动下一首在末尾环绕回开头`() {
        // 用户明确按了「下一首」就是想继续听，这时什么都不做很突兀
        val queue = q("a", "b", "c")
        queue.manualNext(); queue.manualNext()   // 到 c
        assertEquals("c", queue.now())
        assertEquals("a", queue.manualNext()?.title)
    }

    @Test
    fun `手动上一首在开头环绕到末尾`() {
        val queue = q("a", "b", "c")
        assertEquals("a", queue.now())
        assertEquals("c", queue.manualPrev()?.title)
        assertEquals("b", queue.manualPrev()?.title)
    }

    // ---- 自动播完：受循环模式约束 ----

    @Test
    fun `循环关时最后一首播完返回 null 表示该停了`() {
        val queue = q("a", "b")
        queue.manualNext()                       // 到 b（最后一首）
        assertEquals("b", queue.now())
        assertNull(queue.onFinished())
    }

    @Test
    fun `循环关时中间播完继续下一首`() {
        val queue = q("a", "b", "c")
        assertEquals(RepeatMode.OFF, queue.repeatMode)
        assertEquals("b", queue.onFinished()?.title)
    }

    @Test
    fun `列表循环在末尾回到开头`() {
        val queue = q("a", "b")
        queue.repeatMode = RepeatMode.ALL
        queue.manualNext()                       // 到 b
        assertEquals("a", queue.onFinished()?.title)
    }

    @Test
    fun `单曲循环播完还是自己`() {
        val queue = q("a", "b")
        queue.repeatMode = RepeatMode.ONE
        assertEquals("a", queue.onFinished()?.title)
        assertEquals("a", queue.onFinished()?.title)   // 反复都是它
    }

    @Test
    fun `单曲循环下手动下一首仍然前进`() {
        // 这是个容易写错的地方：手动操作不该被循环模式绑住手脚
        val queue = q("a", "b")
        queue.repeatMode = RepeatMode.ONE
        assertEquals("b", queue.manualNext()?.title)
    }

    @Test
    fun `单曲队列播完在循环关时停下`() {
        val queue = q("a")
        assertTrue(queue.isAtEnd)
        assertNull(queue.onFinished())
        // 但手动下一首仍然有响应（回到自己）
        assertEquals("a", queue.manualNext()?.title)
    }

    // ---- 洗牌 ----

    @Test
    fun `开洗牌不会把当前曲目换掉`() {
        val queue = q("a", "b", "c", "d", "e")
        queue.manualNext(); queue.manualNext()   // 到 c
        assertEquals("c", queue.now())

        queue.shuffle = true
        assertEquals("c", queue.now())           // 还在 c，不跳
        assertEquals(2, queue.currentIndex)      // 下标仍是原始的 2
    }

    @Test
    fun `关洗牌恢复自然顺序且当前曲目不变`() {
        val queue = q("a", "b", "c", "d", "e")
        queue.manualNext(); queue.manualNext()   // 到 c
        queue.shuffle = true
        assertEquals("c", queue.now())
        queue.shuffle = false
        assertEquals("c", queue.now())
        assertEquals(2, queue.currentIndex)
        // 恢复之后顺序是自然的：下一首应该是 d
        assertEquals("d", queue.manualNext()?.title)
    }

    @Test
    fun `洗牌后排到所有曲目且不重复`() {
        val names = (1..20).map { "t$it" }
        val queue = q(*names.toTypedArray())
        queue.shuffle = true

        val seen = mutableListOf<String>()
        repeat(names.size) {
            seen += queue.now()!!
            queue.manualNext()
        }
        assertEquals(names.size, seen.toSet().size)
        assertEquals(names.toSet(), seen.toSet())
    }

    @Test
    fun `洗牌时自动播完在末尾按循环模式处理`() {
        val queue = q("a", "b", "c")
        queue.shuffle = true
        queue.repeatMode = RepeatMode.OFF
        // 洗牌后当前是 a（第一位），往后走两首到末尾
        queue.manualNext(); queue.manualNext()
        assertTrue(queue.isAtEnd)
        assertNull(queue.onFinished())
    }

    // ---- 跳转 ----

    @Test
    fun `jumpTo 跳到指定曲目`() {
        val queue = q("a", "b", "c")
        assertEquals("c", queue.jumpTo(2)?.title)
        assertEquals(2, queue.currentIndex)
        assertEquals("a", queue.jumpTo(0)?.title)
    }

    @Test
    fun `jumpTo 越界返回 null 且不改变现状`() {
        val queue = q("a", "b")
        queue.manualNext()
        assertNull(queue.jumpTo(5))
        assertNull(queue.jumpTo(-1))
        assertEquals("b", queue.now())
    }

    @Test
    fun `洗牌状态下 jumpTo 也能正确定位`() {
        val queue = q("a", "b", "c", "d")
        queue.shuffle = true
        assertEquals("d", queue.jumpTo(3)?.title)
        assertEquals(3, queue.currentIndex)
    }

    // ---- 替换与清空 ----

    @Test
    fun `重新 setTracks 会丢弃旧队列`() {
        val queue = q("a", "b", "c")
        queue.manualNext()
        queue.setTracks(listOf(Track("file:///x", title = "x")))
        assertEquals(1, queue.size)
        assertEquals("x", queue.now())
        assertNull(queue.onFinished())           // 单曲、循环关
    }

    @Test
    fun `clear 之后回到空状态`() {
        val queue = q("a", "b")
        queue.clear()
        assertTrue(queue.isEmpty)
        assertNull(queue.current())
        assertEquals(-1, queue.currentIndex)
    }

    // ---- 规格与显示 ----

    @Test
    fun `规格未知时不显示假的数值`() {
        val t = Track("file:///a.flac", title = "a")
        assertNull(t.specLabel)
        assertEquals("—", t.specLabelOrDefault)
    }

    @Test
    fun `规格已知时按 kHz 显示`() {
        assertEquals("44.1k / 16bit", Track("u", sampleRate = 44100, bitDepth = 16).specLabel)
        assertEquals("48k / 24bit", Track("u", sampleRate = 48000, bitDepth = 24).specLabel)
        assertEquals("176.4k / 24bit", Track("u", sampleRate = 176400, bitDepth = 24).specLabel)
    }

    @Test
    fun `回填规格不会覆盖已有的值`() {
        val t = Track("u", sampleRate = 48000, bitDepth = 24, channels = 2)
        assertSame(t, t.withSpec(48000, 24, 2))
    }

    @Test
    fun `标题为空时回落到文件名`() {
        assertEquals("song", Track("file:///music/song.flac").displayTitle)
        assertEquals("未知艺术家", Track("u").displayArtist)
    }

    // ---- 编辑队列 ----

    private fun PlayQueue.titles() = tracks().map { it.title }

    @Test
    fun `下一首播放插在当前之后`() {
        val queue = q("a", "b", "c")
        queue.manualNext()                       // 当前 = b
        queue.insertNext(Track("file:///x", title = "x"))
        assertEquals(listOf("a", "b", "x", "c"), queue.titles())
        assertEquals("b", queue.now())           // 当前这首不跳
        assertEquals("x", queue.manualNext()?.title)
    }

    @Test
    fun `下一首播放插在队首当没有任何在播时`() {
        val queue = PlayQueue(Random(1))
        queue.insertNext(Track("file:///x", title = "x"))
        assertEquals(listOf("x"), queue.titles())
        assertEquals("x", queue.now())
    }

    @Test
    fun `加到队尾不影响当前播放`() {
        val queue = q("a", "b")
        queue.append(Track("file:///z", title = "z"))
        assertEquals(listOf("a", "b", "z"), queue.titles())
        assertEquals("a", queue.now())
        assertEquals("b", queue.manualNext()?.title)
    }

    @Test
    fun `删掉前面的歌会让当前下标前移`() {
        val queue = q("a", "b", "c")
        queue.manualNext(); queue.manualNext()   // 当前 = c（下标 2）
        queue.removeAt(0)                        // 删 a
        assertEquals(listOf("b", "c"), queue.titles())
        assertEquals("c", queue.now())           // 还在 c，没跳
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun `删掉正在播的那首会落到下一首`() {
        val queue = q("a", "b", "c")
        queue.manualNext()                       // 当前 = b
        queue.removeAt(1)                        // 删掉 b
        assertEquals(listOf("a", "c"), queue.titles())
        assertEquals("c", queue.now())           // 自然指向原来的下一首
    }

    @Test
    fun `删掉末尾那首时当前落回最后一首`() {
        val queue = q("a", "b", "c")
        queue.manualNext(); queue.manualNext()   // 当前 = c
        queue.removeAt(2)                        // 删掉 c
        assertEquals(listOf("a", "b"), queue.titles())
        assertEquals("b", queue.now())
    }

    @Test
    fun `删光之后回到空状态`() {
        val queue = q("a")
        queue.removeAt(0)
        assertTrue(queue.isEmpty)
        assertNull(queue.now())
        assertEquals(-1, queue.currentIndex)
    }

    @Test
    fun `越界删除返回 false 且不改变现状`() {
        val queue = q("a", "b")
        assertFalse(queue.removeAt(5))
        assertFalse(queue.removeAt(-1))
        assertEquals(2, queue.size)
    }

    @Test
    fun `拖动排序时当前曲目跟着走`() {
        val queue = q("a", "b", "c", "d")
        queue.manualNext()                       // 当前 = b（下标 1）
        queue.move(1, 3)                         // 把 b 拖到队尾
        assertEquals(listOf("a", "c", "d", "b"), queue.titles())
        assertEquals("b", queue.now())           // 拖的是它自己，还在播它
        assertEquals(3, queue.currentIndex)
    }

    @Test
    fun `拖动别的曲目跨过当前曲目时当前下标跟着调整`() {
        val queue = q("a", "b", "c", "d")
        queue.manualNext(); queue.manualNext()   // 当前 = c（下标 2）
        queue.move(3, 0)                         // 把 d 拖到最前
        assertEquals(listOf("d", "a", "b", "c"), queue.titles())
        assertEquals("c", queue.now())           // 还在 c
        assertEquals(3, queue.currentIndex)
    }

    @Test
    fun `清空未播部分保留当前这首`() {
        val queue = q("a", "b", "c", "d")
        queue.manualNext()                       // 当前 = b
        queue.clearUpcoming()
        assertEquals(listOf("b"), queue.titles())
        assertEquals("b", queue.now())
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun `编辑队列会自动关掉随机播放`() {
        // 开着随机时"队列顺序"没有意义，拖了顺序还是乱着放更让人困惑
        val queue = q("a", "b", "c")
        queue.shuffle = true
        val changed = queue.append(Track("file:///z", title = "z"))
        assertTrue(changed)
        assertFalse(queue.shuffle)
        // 关掉之后顺序是自然的，编辑结果看得见
        assertEquals(listOf("a", "b", "c", "z"), queue.titles())
    }

    @Test
    fun `没有开随机时编辑不报告"关掉了随机"`() {
        val queue = q("a", "b")
        assertFalse(queue.append(Track("file:///z", title = "z")))
    }
}
