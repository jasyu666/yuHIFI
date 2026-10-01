package com.hifiprobe

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * 内嵌歌词 —— 读取、解析、缓存。
 *
 * ★★ 最要紧的一条：**拿到的那段文本可能是两种东西，只能靠嗅探内容分辨。**
 *
 *   · **LRC**（每行带 `[mm:ss.xx]` 时间戳）→ 能逐行同步滚动
 *   · **纯文本** → 只能整页静态显示
 *
 *   而**同一个字段名两种都可能装**：m4a 那个 `©lyr` 在规范里被定义成
 *   "无时间戳纯文本"，实测里面塞的却全是 LRC。
 *   **按字段名去猜必然错**，所以这里一律解析后看有没有时间戳。
 *   （用户库里 1111 首有歌词，其中 1054 首带时间戳 —— 绝大多数是 LRC。）
 */
object Lyrics {

    private const val TAG = "HiFiLyrics"

    /** 一行歌词。[atMs] < 0 表示这行没有时间信息（纯文本歌词） */
    class Line(val atMs: Long, val text: String)

    /** 解析结果。[timed] = true 表示可以逐行同步 */
    class Doc(val lines: List<Line>, val timed: Boolean) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    val NONE = Doc(emptyList(), false)

    /**
     * `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss:xx]`。
     *
     * ★ 冒号前必须是**数字** —— 这样 `[ar:艺术家]`、`[ti:标题]` 这类
     *   元信息标签天然不会被匹配到，不必单独排除。
     */
    private val TS = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    // ------------------------------------------------------------------
    //  缓存
    // ------------------------------------------------------------------

    /*
     * 很小的一层缓存：歌词是**按需**读的（一次一首），但用户会来回切歌。
     * 上限按**条数**就够 —— 一首歌词几 KB，几十条也就百来 KB，
     * 不像封面那样差一个数量级（所以 CoverLoader 才必须按字节算）。
     */
    private const val MAX_ENTRIES = 24

    private val cache = object : LinkedHashMap<String, Doc>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Doc>?) =
            size > MAX_ENTRIES
    }

    /** 换掉队列/换了歌单之后想强制重读时用 */
    @Synchronized
    fun clearCache() = cache.clear()

    // ------------------------------------------------------------------
    //  读取
    // ------------------------------------------------------------------

    /**
     * 读一首歌的歌词。**不要在界面线程调** —— 它要开文件、跑一遍 FFmpeg 探测。
     *
     * ★ 只支持**库内本地文件**（`file://`）。SAF 的 `content://` 现在只出现在
     *   导入阶段，导入之后都落到库里变成普通路径了。
     */
    @Synchronized
    fun load(track: Track): Doc {
        cache[track.uri]?.let { return it }

        val path = track.localPath
        if (path == null) return NONE

        val raw = runCatching {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                .use { pfd ->
                    val bytes = NativePlayer.nativeProbeLyrics(pfd.fd)
                    if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
                }
        }.getOrElse { "" }

        val doc = if (raw.isBlank()) NONE else parse(raw)
        cache[track.uri] = doc

        /*
         * ★ 落一行日志。
         *
         *   歌词是最典型的"看不见的状态"：界面上显示为空，可能是
         *   **这首本来就没有歌词**，也可能是**读失败了** —— 两者看起来一模一样。
         *   把"读到几行、有没有时间戳"写出来，一眼就能分清，
         *   而且不必去翻界面（正在播放页每 200ms 重绘，uiautomator 拿不到静止态）。
         */
        Log.i(TAG, "歌词「${track.displayTitle}」：" + when {
            doc.isEmpty -> "没有内嵌歌词"
            doc.timed -> "${doc.lines.size} 行（带时间戳，可同步）"
            else -> "${doc.lines.size} 行（纯文本）"
        })

        return doc
    }

    // ------------------------------------------------------------------
    //  解析
    // ------------------------------------------------------------------

    /**
     * 把歌词原文解析成行。
     *
     * ★ 有时间戳的行**按时间排序** —— 有的歌词文件不是顺序写的，
     *   不排的话同步滚动会来回跳。
     *
     * ★ 一旦判定是 LRC，**没有时间戳的行就丢掉**：那些多半是
     *   `[ar:]` `[ti:]` `[by:]` 这类元信息（它们躲过了上面的正则是因为
     *   冒号前不是数字）。留着会在列表里冒出一堆奇怪的短行。
     */
    fun parse(raw: String): Doc {
        val out = ArrayList<Line>()
        var sawTime = false

        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val marks = TS.findAll(line).toList()
            if (marks.isEmpty()) {
                out.add(Line(-1L, line))
                continue
            }
            sawTime = true
            // 一行可以挂多个时间戳（副歌复用），取最后一个之后的部分当文本
            val text = line.substring(marks.last().range.last + 1).trim()
            for (m in marks) out.add(Line(toMs(m), text))
        }

        if (!sawTime) return Doc(out, false)
        return Doc(out.filter { it.atMs >= 0 }.sortedBy { it.atMs }, true)
    }

    private fun toMs(m: MatchResult): Long {
        val minutes = m.groupValues[1].toLong()
        val seconds = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        // 小数部分位数不定：[00:12.3] / [00:12.34] / [00:12.345] 都见过，
        // 统一按"它是秒的小数"来算，而不是当成毫秒
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.substring(0, 3).toLong()
        }
        return (minutes * 60 + seconds) * 1000 + ms
    }

    // ------------------------------------------------------------------
    //  同步
    // ------------------------------------------------------------------

    /**
     * [positionMs] 时刻该高亮第几行。**-1 = 还没到第一行**（前奏）。
     *
     * 二分查找：歌词几百行，而这个是跟着 200ms 心跳走的，别线性扫。
     */
    fun indexAt(doc: Doc, positionMs: Long): Int {
        if (!doc.timed || doc.lines.isEmpty()) return -1
        var lo = 0
        var hi = doc.lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (doc.lines[mid].atMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
