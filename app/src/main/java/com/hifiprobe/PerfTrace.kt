package com.hifiprobe

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量性能埋点。
 *
 * 设计约束（重要）：
 *   · 播放/解码线程 **只做一次入队**，绝不做文件 I/O、绝不等锁；
 *   · 文件写入在单独的 perf-writer 线程上；
 *   · 队列有界，满了丢最旧的并计数，绝不阻塞调用方；
 *   · 正式版默认关闭；debug 或显式打开调试开关时才启动。
 *
 * 用法：
 *   PerfTrace.span("play.nativeOpen") { nativeOpenFile(...) }
 *   PerfTrace.mark("cache.miss", "key" to rel, "reason" to "mtime")
 *
 * 输出：
 *   <external files>/logs/perf-YYYYMMDD.log
 *   logcat tag = HiFiPerf
 */
object PerfTrace {

    private const val TAG = "HiFiPerf"
    private const val QUEUE_CAP = 4096
    private const val RING_CAP = 512
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_FIELD_CHARS = 320

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var running = false

    private var baseDir: File? = null
    private var writerThread: Thread? = null
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAP)

    /** 给界面/诊断报告读的最近事件；与 writer 队列分开，避免界面拖慢落盘 */
    private val ring = ArrayDeque<String>()
    private val ringLock = Any()
    private val dropped = AtomicLong(0)

    private val writerLock = Any()
    private var out: BufferedWriter? = null
    private var currentFile: File? = null
    private var currentDay: String = ""
    private var currentBytes: Long = 0

    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * 在 Application.onCreate 调一次。
     *
     * ★ 关闭时不创建线程、不创建目录；span/mark 变成近乎空操作。
     */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        enabled = debuggable || Settings.debugEnabled(app)
        if (!enabled) {
            Log.i(TAG, "性能埋点关闭（release 且未打开调试开关）")
            return
        }
        if (running) return

        val base = app.getExternalFilesDir(null) ?: app.filesDir
        baseDir = File(base, "logs").apply { mkdirs() }
        running = true
        writerThread = Thread({ writerLoop() }, "perf-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        mark("perf.start", "dir" to (baseDir?.absolutePath ?: ""))
    }

    /** 一行普通事件（无耗时）。 */
    fun mark(name: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        emit(name, -1, fields, "mark")
    }

    /** 包住一段代码并记录耗时。 */
    fun <T> span(name: String, vararg fields: Pair<String, Any?>, block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
            emit(name, ms, fields, "span")
        }
    }

    /** 只读：最近事件文本，给诊断页/报告用。 */
    fun recentText(limit: Int = 60): String {
        val n = limit.coerceIn(1, RING_CAP)
        synchronized(ringLock) {
            if (ring.isEmpty()) return "（无性能事件）"
            return ring.toList().takeLast(n).joinToString("\n")
        }
    }

    fun droppedCount(): Long = dropped.get()

    fun logPath(): String = currentFile?.absolutePath
        ?: baseDir?.let { File(it, "perf-*.log").path }
        ?: "（未启用）"

    fun flush() {
        synchronized(writerLock) {
            runCatching { out?.flush() }
        }
    }

    fun shutdown() {
        running = false
        writerThread?.interrupt()
        runCatching { writerThread?.join(1200) }
        synchronized(writerLock) {
            runCatching { out?.flush() }
            runCatching { out?.close() }
            out = null
        }
        writerThread = null
    }

    // ------------------------------------------------------------------

    private fun emit(
        name: String,
        durationMs: Long,
        fields: Array<out Pair<String, Any?>>,
        kind: String
    ) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val ts = Instant.ofEpochMilli(now).atZone(zone).format(timeFmt)
        val thread = Thread.currentThread().name
        val detail = fields.joinToString(" ") { (k, v) ->
            "${clean(k)}=${clean(v)}"
        }
        val line = buildString {
            append(ts)
            append(" [").append(thread).append("] ")
            append(name)
            append(' ').append(kind)
            if (durationMs >= 0) append(' ').append(durationMs).append("ms")
            if (detail.isNotEmpty()) append(' ').append(detail)
        }

        // 给 UI / 报告用的环形缓冲。这里只做一次短字符串入队，不碰磁盘。
        synchronized(ringLock) {
            ring.addLast(line)
            while (ring.size > RING_CAP) ring.removeFirst()
        }

        // 非阻塞入队；满则丢弃并计数，绝不等待 writer。
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    private fun writerLoop() {
        var lastFlush = 0L
        while (running || queue.isNotEmpty()) {
            val line = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                if (!running) break
                continue
            }
            if (line != null) {
                runCatching {
                    ensureOpen()
                    val w = out ?: return@runCatching
                    w.write(line)
                    w.newLine()
                    currentBytes += line.length + 1
                    if (currentBytes >= MAX_FILE_BYTES) rotate()
                }.onFailure {
                    // 日志失败不能影响播放；最多丢掉这一条。
                    Log.w(TAG, "写性能日志失败: ${it.message}")
                }
                // logcat 也由 writer 线程输出，避免调用方被 logd 拖住。
                runCatching { Log.i(TAG, line) }
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush >= 1000) {
                lastFlush = now
                synchronized(writerLock) { runCatching { out?.flush() } }
            }
        }
        synchronized(writerLock) {
            runCatching { out?.flush() }
            runCatching { out?.close() }
            out = null
        }
    }

    private fun ensureOpen() {
        val dir = baseDir ?: return
        val day = LocalDate.now().format(dayFmt)
        if (out != null && day == currentDay) return
        rotate(day)
    }

    private fun rotate(day: String = LocalDate.now().format(dayFmt)) {
        synchronized(writerLock) {
            runCatching { out?.flush() }
            runCatching { out?.close() }
            out = null

            val dir = baseDir ?: return
            dir.mkdirs()
            var f = File(dir, "perf-$day.log")
            if (f.exists() && f.length() >= MAX_FILE_BYTES) {
                f = File(dir, "perf-$day-${System.currentTimeMillis()}.log")
            }
            currentFile = f
            currentDay = day
            currentBytes = if (f.exists()) f.length() else 0L
            out = BufferedWriter(
                OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8)
            )
        }
    }

    private fun clean(v: Any?): String {
        if (v == null) return "null"
        val s = v.toString().replace('\n', ' ').replace('\r', ' ')
        return if (s.length <= MAX_FIELD_CHARS) s else s.take(MAX_FIELD_CHARS) + "…"
    }
}
