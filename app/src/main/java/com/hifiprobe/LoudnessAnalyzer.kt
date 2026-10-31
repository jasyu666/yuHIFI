package com.hifiprobe

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 响度分析的**唯一**调度器。
 *
 * 线程模型：一条低优先级后台线程 + 优先级队列。所有任务串行执行 ——
 * 同时跑两个解码器只会和正在播放的解码线程抢 CPU，没有收益。
 *
 * 优先级：
 *   P0 当前曲目（起播前最多等 3 秒）
 *   P1 当前专辑（最终要用的是专辑增益）
 *   P2 队列里接下来几首 / 歌单其余部分
 *   P3 全库补齐（打开开关时入队，播放开始立刻让路）
 *
 * ★★ 专辑任务用**一次连续解码**同时产出两个结果：
 *   连续的 albumMeter 算专辑响度，逐曲重置的 trackMeter 算单曲响度。
 *   所以"先做专辑"和"再做单曲"不是两趟活，是一趟活的两个产物。
 *
 * ★ 所有测量都走**独立的文件描述符/解码器**，绝不碰正在播放的那条流。
 *   代价是当前曲目多解码一次（实测中位 0.56 秒），换来实时线程零风险。
 */
object LoudnessAnalyzer {

    private const val TAG = "HiFiLoudness"

    private const val P_CURRENT = 0
    private const val P_ALBUM = 1
    private const val P_QUEUE = 2
    private const val P_LIBRARY = 3

    private data class Job(
        val key: String,
        val album: String?,
        val albumSig: String?,
        val tracks: List<Track>,
        val priority: Int,
        val seq: Long,
        val createdAtMs: Long = SystemClock.elapsedRealtime()
    ) {
        @Volatile var cancelled: Boolean = false

        /** 后台任务被播放"提权"后，完成时要通知 PlayerSession 更新增益 */
        @Volatile var playbackRelevant: Boolean = false

        // 监控用；只有分析线程写，snapshot 读
        @Volatile var startedAtMs: Long = 0L
        @Volatile var doneTracks: Int = 0
        @Volatile var failedTracks: Int = 0
        @Volatile var lastTrackMs: Long = 0L
        @Volatile var lastError: String? = null

        val kind: String get() = if (album != null) "专辑" else "单曲"
        val label: String
            get() = if (album != null) "专辑：$album"
            else "曲目：${tracks.firstOrNull()?.displayTitle ?: key}"
    }

    private val lock = Object()
    private val queue = PriorityQueue<Job>(
        compareBy<Job>({ it.priority }, { it.seq })
    )
    private val pending = HashMap<String, Job>()
    private val waiters = HashMap<String, MutableList<CountDownLatch>>()
    private var seq = 0L

    /** 上一次把全库任务排进队列时的 [Library.version]。-1 = 需要重排 */
    private var lastScheduledVersion = -1L

    /** 空闲巡检只启动一次。它负责在"队列空了但还有未测曲目"时补排。 */
    @Volatile private var nudgeStarted = false

    /** 内存中的库为空时，最多自动加载一次（防止在非首页冷启动时永远没数据） */
    @Volatile private var libraryLoadTried = false

    @Volatile private var running = false
    private var loopActive = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var currentJob: Job? = null
    @Volatile private var currentNative = 0L

    // 监控统计：只在分析线程增量写，snapshot 读；AtomicLong 避免撕裂
    private val statCompletedTracks = AtomicLong(0)
    private val statFailedTracks = AtomicLong(0)
    private val statPreempted = AtomicLong(0)
    private val statTrackCount = AtomicLong(0)
    private val statTrackTotalMs = AtomicLong(0)
    private val statTrackLastMs = AtomicLong(0)
    private val statTrackMaxMs = AtomicLong(0)
    private val statAlbumCount = AtomicLong(0)
    private val statAlbumTotalMs = AtomicLong(0)
    private val statAlbumLastMs = AtomicLong(0)
    private val statAlbumMaxMs = AtomicLong(0)
    @Volatile private var lastErrorText: String? = null

    data class JobInfo(
        val key: String,
        val label: String,
        val kind: String,
        val priority: Int,
        val priorityLabel: String,
        val tracks: Int,
        val done: Int,
        val queuedMs: Long,
        val runningMs: Long,
        val status: String
    )

    data class Snapshot(
        val running: Boolean,
        val modeLabel: String,
        val targetLufs: Float,
        val albumMaxTracks: Int,
        val current: JobInfo?,
        val pendingCount: Int,
        val p0: Int,
        val p1: Int,
        val p2: Int,
        val p3: Int,
        val next: List<JobInfo>,
        val cacheTracks: Int,
        val cacheAlbums: Int,
        val completedTracks: Long,
        val failedTracks: Long,
        val preempted: Long,
        val lastTrackMs: Long,
        val avgTrackMs: Long,
        val maxTrackMs: Long,
        val lastAlbumMs: Long,
        val avgAlbumMs: Long,
        val maxAlbumMs: Long,
        val lastError: String?
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "loudness-analyzer").apply { isDaemon = true }
    }

    /**
     * 只负责"把全库任务排进队列"的线程。
     *
     * ★★ start() 是设置页开关/首页 onResume 直接调的 —— **主线程**。
     *    2800 首的遍历、180 张专辑的指纹计算放进主线程会卡住界面 1~2 秒
     *    （2026-10-17 实测用户报告"打开功能时无法操作"）。
     *    所以扫描的**排队工作**也必须离开主线程；它和真正的解码线程分开，
     *    这样排队不会排在长任务后面，解码也不会被排队挡住。
     */
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "loudness-scheduler").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------
    //  生命周期
    // ------------------------------------------------------------------

    /**
     * 启动/补齐后台分析。
     *
     * ★ 可以反复调：已经在跑时只会把库里新出现的未测曲目补进 P3，
     *   不会重置队列。库扫描完成后由 [PlayerSession.onLibraryReady] 调一次。
     */
    fun start(ctx: Context) {
        val app = ctx.applicationContext

        /*
         * 功能关着时**绝不启动扫描**。
         *
         * ★ 响度平衡已和音量控制解耦：音量控制关着也照常分析/生效。
         * ★ 设置页改"模式/专辑上限"时会调 restart() —— 如果用户只是改个值
         *   而功能原本是关的，没有这道闸就会悄悄开始跑全库分析。
         */
        if (!Settings.loudnessEnabled(app)) return

        appCtx = app
        LoudnessStore.init(app)

        var startedNow = false
        synchronized(lock) {
            if (!running) {
                running = true
                startedNow = true
                if (!loopActive) {
                    loopActive = true
                    executor.execute(::loop)
                }
            }
            lock.notifyAll()
        }
        if (startedNow) Log.i(TAG, "响度分析线程启动请求已受理")

        /*
         * ★★ 排队工作放到 scheduler 线程 —— 这里绝不能遍历全库/算指纹。
         *    用户 2026-10-17 实测：打开开关后约 2 秒无法操作，
         *    根因就是这段 O(全库 + 全专辑指纹) 的活跑在主线程上。
         */
        scheduleLibrary(app)
        ensureNudge()
        ensureLibraryLoaded()
    }

    /**
     * 如果内存里的库是空的，后台加载/扫描一次。
     *
     * ★ 场景：系统把 App 恢复到设置页/正在播放页，没经过 HomeActivity 的扫描。
     *   没有这一步，分析器虽然活着，但 `Library.all()` 是空的，什么也排不进去。
     * ★ 只做一次；如果 HomeActivity 正在扫描（Library.scanning），直接让路。
     */
    private fun ensureLibraryLoaded() {
        if (!Library.isEmpty() || Library.scanning) return
        synchronized(lock) {
            if (libraryLoadTried) return
            libraryLoadTried = true
        }
        scheduler.execute {
            if (!running || !Library.isEmpty() || Library.scanning) return@execute
            runCatching {
                Log.i(TAG, "内存中的音乐库为空，后台加载一次")
                Library.refresh()
                // 库就绪后重新排队。★ 这里不调 PlayerSession.onLibraryReady()：
                //   App 冷启动时 PlayerSession.init() 可能还没跑，队列恢复交给界面。
                appCtx?.let { c -> start(c) }
            }.onFailure {
                Log.e(TAG, "后台加载音乐库失败", it)
            }
        }
    }

    /**
     * 在后台线程把全库任务排进队列。
     *
     * ★ 先给当前曲目/当前专辑提权，再排 P3 全库 —— 这样分析线程即使立刻
     *   开工，也不会先抓到一个全库任务而把用户正在听的歌拖到 3 秒超时。
     * ★ 库版本没变就跳过全库重排（首页 onResume 会反复调 start()）。
     */
    private fun scheduleLibrary(app: Context, force: Boolean = false) {
        scheduler.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

            if (!running) return@execute

            // 当前曲目/专辑先入队（只读 PlayerSession.current，不碰队列）
            PlayerSession.current?.let { prioritizeCurrent(it) }

            val t0 = android.os.SystemClock.elapsedRealtime()
            var queued = 0
            synchronized(lock) {
                if (running && (force || lastScheduledVersion != Library.version)) {
                    enqueueLibraryLocked(app)
                    lastScheduledVersion = Library.version
                    queued = pending.size
                }
                lock.notifyAll()
            }
            if (queued > 0) {
                Log.i(
                    TAG,
                    "全库任务排队完成（force=$force）：$queued 个，耗时 " +
                            "${android.os.SystemClock.elapsedRealtime() - t0}ms"
                )
            }
        }
    }

    /**
     * 空闲巡检：每 30 秒看一眼，如果分析线程闲着（没有待办）而库里还有
     * 未测曲目，就强制重排一次。
     *
     * ★ 防的是"启动时库还没就绪 / 排队任务因为异常丢了 / 首页没来得及
     *   onLibraryReady"这几种情况。正常情况下它什么都不做，成本可以忽略。
     * ★ 它只做排队，不占分析线程；不会和播放抢 CPU。
     */
    private fun ensureNudge() {
        synchronized(lock) {
            if (nudgeStarted) return
            nudgeStarted = true
        }
        scheduler.scheduleWithFixedDelay({
            runCatching {
                if (!running) return@scheduleWithFixedDelay
                val idle = synchronized(lock) { pending.isEmpty() }
                if (!idle) return@scheduleWithFixedDelay
                val ctx = appCtx ?: return@scheduleWithFixedDelay

                // 全都测过了就什么都不做，别每 30 秒空转一遍全库
                val total = Library.all().count { !LoudnessPolicy.isDsd(it) }
                if (total <= 0) return@scheduleWithFixedDelay
                if (LoudnessStore.measuredCount() >= total) return@scheduleWithFixedDelay

                scheduleLibrary(ctx, force = true)
            }.onFailure {
                Log.e(TAG, "响度空闲巡检失败", it)
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** 只把当前曲目和当前专辑提权（不碰队列） */
    private fun prioritizeCurrent(track: Track) {
        val ctx = appCtx ?: return
        if (!running) return
        synchronized(lock) {
            enqueueTrackLocked(track, P_CURRENT)
            val album = LoudnessPolicy.albumFor(ctx, track)
            if (album != null) enqueueAlbumLocked(album, P_ALBUM)
            lock.notifyAll()
        }
    }

    /** 关闭功能时调用：取消在途任务，保留已经落盘的测量结果 */
    fun stop() {
        val cancelHandle: Long
        synchronized(lock) {
            running = false
            currentJob?.cancelled = true
            cancelHandle = currentNative
            queue.clear()
            pending.clear()
            waiters.values.flatten().forEach { it.countDown() }
            waiters.clear()
            lastScheduledVersion = -1L
            lock.notifyAll()
        }
        if (cancelHandle != 0L) {
            runCatching { NativePlayer.nativeLoudnessJobCancel(cancelHandle) }
        }
        LoudnessStore.flush()
    }

    /** 模式/专辑上限改了：清空队列按新规则重排。已测结果不用重算 */
    fun restart(ctx: Context) {
        stop()
        start(ctx)
    }

    /** 库扫描完成后由 PlayerSession 调。没开功能就什么也不做 */
    fun onLibraryReady(ctx: Context) {
        if (!Settings.loudnessEnabled(ctx)) return
        start(ctx)
    }

    fun isRunning(): Boolean = running

    /** 还在排队的任务数（含正在跑的那一个），设置页显示进度用 */
    fun pendingCount(): Int = synchronized(lock) { pending.size }

    // ------------------------------------------------------------------
    //  监控快照
    // ------------------------------------------------------------------

    /**
     * 只读快照。锁只做拷贝/计数，不做文件 I/O、不碰解码。
     * UI 1.5 秒读一次完全够用。
     */
    fun snapshot(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        val cur: Job?
        val jobs: List<Job>
        val runningNow: Boolean
        synchronized(lock) {
            cur = currentJob
            jobs = pending.values.filter { it !== cur }
                .sortedWith(compareBy<Job>({ it.priority }, { it.seq }))
            runningNow = running
        }

        fun info(j: Job, status: String): JobInfo {
            val runningMs = if (j.startedAtMs > 0L) (now - j.startedAtMs).coerceAtLeast(0L) else 0L
            return JobInfo(
                key = j.key,
                label = j.label,
                kind = j.kind,
                priority = j.priority,
                priorityLabel = priorityLabel(j.priority),
                tracks = j.tracks.size,
                done = j.doneTracks,
                queuedMs = (now - j.createdAtMs).coerceAtLeast(0L),
                runningMs = runningMs,
                status = status
            )
        }

        val ctx = appCtx
        val modeLabel = if (ctx == null) {
            "—"
        } else if (Settings.loudnessMode(ctx) == Settings.LOUDNESS_MODE_TRACK) {
            "全部单曲"
        } else {
            "自动（< ${Settings.loudnessAlbumMaxTracks(ctx)} 首按专辑）"
        }
        val target = ctx?.let { Settings.loudnessTargetLufs(it) } ?: Settings.DEFAULT_LOUDNESS_LUFS
        val albumMax = ctx?.let { Settings.loudnessAlbumMaxTracks(it) } ?: Settings.DEFAULT_ALBUM_MAX_TRACKS

        val completed = statCompletedTracks.get()
        val trackN = statTrackCount.get()
        val albumN = statAlbumCount.get()
        return Snapshot(
            running = runningNow,
            modeLabel = modeLabel,
            targetLufs = target,
            albumMaxTracks = albumMax,
            current = cur?.let { info(it, "测量中") },
            pendingCount = jobs.size + if (cur != null) 1 else 0,
            p0 = jobs.count { it.priority == P_CURRENT } + if (cur?.priority == P_CURRENT) 1 else 0,
            p1 = jobs.count { it.priority == P_ALBUM } + if (cur?.priority == P_ALBUM) 1 else 0,
            p2 = jobs.count { it.priority == P_QUEUE } + if (cur?.priority == P_QUEUE) 1 else 0,
            p3 = jobs.count { it.priority == P_LIBRARY } + if (cur?.priority == P_LIBRARY) 1 else 0,
            next = jobs.take(10).map { info(it, "排队") },
            cacheTracks = LoudnessStore.trackMeasureCount(),
            cacheAlbums = LoudnessStore.albumMeasureCount(),
            completedTracks = completed,
            failedTracks = statFailedTracks.get(),
            preempted = statPreempted.get(),
            lastTrackMs = statTrackLastMs.get(),
            avgTrackMs = if (trackN > 0) statTrackTotalMs.get() / trackN else 0L,
            maxTrackMs = statTrackMaxMs.get(),
            lastAlbumMs = statAlbumLastMs.get(),
            avgAlbumMs = if (albumN > 0) statAlbumTotalMs.get() / albumN else 0L,
            maxAlbumMs = statAlbumMaxMs.get(),
            lastError = lastErrorText ?: cur?.lastError
        )
    }

    /** 给设置页/诊断页/报告用的可读文本。 */
    fun snapshotText(): String {
        val s = snapshot()
        return buildString {
            appendLine("── 响度分析监控 ──")
            appendLine("运行=${if (s.running) "是" else "否"}  模式=${s.modeLabel}  " +
                    "目标=${Settings.fmtLufs(s.targetLufs)}  专辑上限=${s.albumMaxTracks} 首")
            appendLine("缓存：曲目 ${s.cacheTracks} 首 / 专辑 ${s.cacheAlbums} 张")
            appendLine("队列：共 ${s.pendingCount} 项  " +
                    "P0=${s.p0} P1=${s.p1} P2=${s.p2} P3=${s.p3}")
            val c = s.current
            if (c == null) {
                appendLine("当前：空闲")
            } else {
                appendLine("当前：${c.priorityLabel} · ${c.kind} · ${c.label}  " +
                        "${c.done}/${c.tracks} 首 · 已用 ${c.runningMs} ms")
            }
            appendLine("单曲耗时：最近 ${s.lastTrackMs} ms / 平均 ${s.avgTrackMs} ms / " +
                    "最慢 ${s.maxTrackMs} ms")
            appendLine("专辑耗时：最近 ${s.lastAlbumMs} ms / 平均 ${s.avgAlbumMs} ms / " +
                    "最慢 ${s.maxAlbumMs} ms")
            appendLine("累计：完成 ${s.completedTracks} 首 / 失败 ${s.failedTracks} 首 / " +
                    "抢占 ${s.preempted} 次")
            s.lastError?.let { appendLine("最近错误：$it") }
            if (s.next.isNotEmpty()) {
                appendLine("下一批：")
                s.next.forEach {
                    appendLine("  · ${it.priorityLabel} ${it.kind} ${it.label}  " +
                            "排队 ${it.queuedMs} ms")
                }
            }
        }
    }

    private fun priorityLabel(p: Int): String = when (p) {
        P_CURRENT -> "P0 当前曲目"
        P_ALBUM -> "P1 当前专辑"
        P_QUEUE -> "P2 队列"
        else -> "P3 全库"
    }

    // ------------------------------------------------------------------
    //  播放时的优先级调整
    // ------------------------------------------------------------------

    /**
     * 用户点了歌/歌单/播放队列 → 立刻把当前、当前专辑、接下来几首提权。
     *
     * ★ P0 当前曲目只测**单曲值**，用于起播桥；
     *   P1 当前专辑测的是**最终会用到的专辑增益**。
     */
    fun prioritizeForPlayback(track: Track, upcoming: List<Track>) {
        val ctx = appCtx ?: return
        if (!running) return

        synchronized(lock) {
            enqueueTrackLocked(track, P_CURRENT)

            val album = LoudnessPolicy.albumFor(ctx, track)
            if (album != null) enqueueAlbumLocked(album, P_ALBUM)

            // 只预分析接下来的 3 首；再多就是在跟当前专辑抢时间
            for (t in upcoming.take(3)) {
                if (LoudnessPolicy.isDsd(t)) continue
                val a = LoudnessPolicy.albumFor(ctx, t)
                if (a != null) {
                    if (a.name != album?.name) enqueueAlbumLocked(a, P_QUEUE)
                } else {
                    enqueueTrackLocked(t, P_QUEUE)
                }
            }
            lock.notifyAll()
        }
    }

    /**
     * 起播前同步等一首的测量结果。最多等 [timeoutMs]。
     *
     * 超时返回 null，但任务**不会取消** —— 它继续跑，完成后由
     * PlayerSession 的增益更新路径把斜坡补上。
     */
    fun ensureTrack(track: Track, timeoutMs: Long): LoudnessStore.TrackMeasure? {
        if (LoudnessPolicy.isDsd(track)) return null
        LoudnessStore.getTrack(track)?.let { return it }

        if (!running) return null

        val key = trackKey(track) ?: return null
        val started = SystemClock.elapsedRealtime()
        PerfTrace.mark("analyzer.ensure.enter", "key" to key, "timeoutMs" to timeoutMs)
        val latch = CountDownLatch(1)

        synchronized(lock) {
            waiters.getOrPut(key) { mutableListOf() }.add(latch)
            val existing = pending[key]
            if (existing == null || existing.priority > P_CURRENT) {
                enqueueTrackLocked(track, P_CURRENT)
            }
            lock.notifyAll()
        }

        /*
         * 竞态兜底：任务可能刚好在我们注册 waiter **之前**完成并
         * notifyTrackWaiters() 过了 —— 那样 latch 永远不会被 countDown。
         * 再查一次 Store：已经有结果就直接返回，不要白等满 3 秒。
         */
        LoudnessStore.getTrack(track)?.let { hit ->
            synchronized(lock) {
                waiters[key]?.remove(latch)
                if (waiters[key]?.isEmpty() == true) waiters.remove(key)
            }
            PerfTrace.mark("analyzer.ensure.exit", "key" to key,
                "ms" to (SystemClock.elapsedRealtime() - started),
                "hit" to true, "path" to "second-check")
            return hit
        }

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        synchronized(lock) {
            waiters[key]?.remove(latch)
            if (waiters[key]?.isEmpty() == true) waiters.remove(key)
        }
        val result = LoudnessStore.getTrack(track)
        PerfTrace.mark("analyzer.ensure.exit", "key" to key,
            "ms" to (SystemClock.elapsedRealtime() - started),
            "hit" to (result != null), "path" to "wait")
        return result
    }

    // ------------------------------------------------------------------
    //  入队
    // ------------------------------------------------------------------

    private fun enqueueLibraryLocked(ctx: Context) {
        val all = Library.all().filterNot { LoudnessPolicy.isDsd(it) }
        if (all.isEmpty()) return

        if (Settings.loudnessMode(ctx) == Settings.LOUDNESS_MODE_AUTO) {
            val limit = Settings.loudnessAlbumMaxTracks(ctx)
            val covered = HashSet<String>()

            for (a in Library.albums()) {
                if (a.size >= limit) continue
                // 含 DSD 的专辑不能整张测（DSD 不走 FFmpeg），退回逐曲
                if (a.tracks.any { LoudnessPolicy.isDsd(it) }) continue
                val sig = LoudnessStore.albumSig(a)
                if (LoudnessStore.getAlbum(a.name, sig) != null) {
                    a.tracks.forEach { covered.add(it.uri) }
                    continue
                }
                // 指纹**只算一次**：传进去，避免 enqueueAlbumLocked 里再算一遍
                enqueueAlbumLocked(a, P_LIBRARY, sig)
                a.tracks.forEach { covered.add(it.uri) }
            }

            for (t in all) {
                if (t.uri !in covered) enqueueTrackLocked(t, P_LIBRARY)
            }
        } else {
            for (t in all) enqueueTrackLocked(t, P_LIBRARY)
        }
    }

    private fun enqueueTrackLocked(track: Track, priority: Int) {
        if (LoudnessPolicy.isDsd(track)) return
        if (LoudnessStore.getTrack(track) != null) return

        val key = trackKey(track) ?: return

        /*
         * 队列预分析阶段：如果这一首已经被一张待分析的专辑覆盖，不要重复入队。
         *
         * ★ 只对 P2 做这个判断。P3（全库扫描）在 [enqueueLibraryLocked] 里
         *   已经算好 covered 集合；如果这里再对每一首调 albumFor()，
         *   那就是 O(曲目数 × 全库分组) —— 1325 首的大杂烩会卡住主线程。
         */
        if (priority == P_QUEUE) {
            appCtx?.let { ctx ->
                val album = LoudnessPolicy.albumFor(ctx, track)
                if (album != null && pending["A:${album.name}"] != null) return
            }
        }

        addJobLocked(Job(key, null, null, listOf(track), priority, ++seq))
    }

    private fun enqueueAlbumLocked(
        album: Library.Album,
        priority: Int,
        sig: String = LoudnessStore.albumSig(album)
    ) {
        val tracks = album.tracks.filterNot { LoudnessPolicy.isDsd(it) }
        if (tracks.isEmpty()) return

        if (LoudnessStore.getAlbum(album.name, sig) != null) return

        val key = "A:${album.name}"
        addJobLocked(Job(key, album.name, sig, tracks, priority, ++seq))
    }

    /**
     * 加入任务，并处理"同 key 提权"和"播放抢占后台扫描"。
     */
    private fun addJobLocked(job: Job) {
        val old = pending[job.key]

        if (old != null) {
            if (job.priority >= old.priority) return

            /*
             * 同 key、优先级更高：
             *   · 已经在一首一首地跑 → 不重来，直接标记成"播放相关"，
             *     完成时通知 PlayerSession（专辑任务可能跑到一半了）
             *   · 还在队列里 → 换成一个更高优先级的任务
             */
            if (currentJob === old) {
                old.playbackRelevant = true
                return
            }
            queue.remove(old)
            pending.remove(job.key)
        }

        pending[job.key] = job
        queue.add(job)

        /*
         * 不同 key 的高优先级任务进来 → 抢占正在跑的全库扫描（P3）。
         *
         * ★ P1 专辑不抢 P0 当前曲目：专辑是有用的活，且单曲任务最多在
         *   一首曲目的解码时间之后就能开始。
         */
        val cur = currentJob
        if (cur != null && cur !== job && !cur.cancelled && job.priority <= P_ALBUM) {
            /*
             * 回放相关的任务进来 → 抢占 P2/P3；如果当前跑的是**另一张专辑**的
             * P1 任务，也抢占并降级重排 —— 用户已经跳到别的专辑了，
             * 把旧专辑算完没有意义，还会让新曲目的 P0 白等三秒。
             *
             * ★ 同一张专辑的 P1 不抢：专辑测量是最有价值的活，而且它每测完
             *   一首都会通知等待者，正在等的曲目可能马上就有结果。
             */
            val newAlbum = job.album ?: job.tracks.firstOrNull()?.let { t ->
                appCtx?.let { LoudnessPolicy.albumFor(it, t)?.name }
            }
            val differentAlbumP1 = cur.priority == P_ALBUM &&
                    !(cur.album != null && cur.album == newAlbum)
            val preempt = cur.priority >= P_QUEUE || differentAlbumP1

            if (preempt) {
                cur.cancelled = true
                statPreempted.incrementAndGet()
                PerfTrace.mark("analyzer.preempt", "old" to cur.key,
                    "new" to job.key, "newP" to priorityLabel(job.priority))
                val repl = cur.copy(
                    // 旧专辑已经不在当前上下文里了 → 降回全库扫描
                    priority = if (cur.priority == P_ALBUM) P_LIBRARY else cur.priority,
                    seq = ++seq
                )
                pending[cur.key] = repl
                queue.add(repl)
                val h = currentNative
                if (h != 0L) {
                    runCatching { NativePlayer.nativeLoudnessJobCancel(h) }
                }
            }
        }

        lock.notifyAll()
    }

    private fun trackKey(track: Track): String? {
        val rel = Library.relPathOf(track)
        return if (rel != null) "T:$rel" else "T:${track.uri}"
    }

    // ------------------------------------------------------------------
    //  工作线程
    // ------------------------------------------------------------------

    private fun loop() {
        // 低于解码（-16）和 USB（-19）—— 分析宁可慢，也不能让播放卡
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        Log.i(TAG, "响度分析线程启动")

        try {
            while (true) {
                val job = takeJob() ?: break
                currentJob = job
                job.startedAtMs = SystemClock.elapsedRealtime()

                if (job.cancelled) {
                    finishJob(job)
                    currentJob = null
                    continue
                }

                // 播放缓冲不健康时暂停分析，避免和播放抢 CPU 造成 underrun
                var healthLogged = false
                while (running && !job.cancelled && !playbackHealthy()) {
                    if (!healthLogged) {
                        healthLogged = true
                        Log.i(TAG, "播放缓冲偏低，响度分析暂停")
                    }
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }

                if (!running || job.cancelled) {
                    finishJob(job)
                    currentJob = null
                    continue
                }

                var native = 0L
                try {
                    native = NativePlayer.nativeLoudnessJobCreate()
                    currentNative = native
                    runJob(job, native)
                } catch (e: Throwable) {
                    lastErrorText = "分析任务失败：${job.key} ${e.message}"
                    Log.e(TAG, "分析任务失败: ${job.key}", e)
                    PerfTrace.mark("analyzer.job.error", "key" to job.key,
                        "error" to (e.message ?: e.javaClass.simpleName))
                } finally {
                    if (native != 0L) {
                        runCatching { NativePlayer.nativeLoudnessJobDestroy(native) }
                    }
                    if (currentNative == native) currentNative = 0L
                    currentJob = null
                    finishJob(job)
                }
            }

            /*
             * 退出前再看一眼：有可能 takeJob 刚返回 null、用户就把功能又打开了。
             * 这种情况下 loopActive 还是 true，start() 不会再投递，所以这里续上。
             */
            synchronized(lock) {
                if (running) executor.execute(::loop) else loopActive = false
            }
        } catch (e: Throwable) {
            /*
             * ★★ 分析线程绝不能因为一个未捕获异常永久死掉：
             *    running 还是 true、loopActive 还是 true，start() 以为线程还活着，
             *    结果用户看到的就是"功能开着但闲置时完全不分析"。
             *    这里兜住并重新投递（单线程 executor 会串行执行）。
             */
            Log.e(TAG, "响度分析线程异常退出，尝试重启", e)
            synchronized(lock) {
                if (running) executor.execute(::loop) else loopActive = false
            }
        } finally {
            Log.i(TAG, "响度分析线程退出")
        }
    }

    private fun takeJob(): Job? = synchronized(lock) {
        while (running && queue.isEmpty()) {
            try {
                lock.wait(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (!running) return null
        queue.poll()
    }

    private fun finishJob(job: Job) {
        synchronized(lock) {
            // ★ 只删自己：任务被抢占时，同 key 可能已经排了一个新的
            if (pending[job.key] === job) pending.remove(job.key)
        }
    }

    private fun runJob(job: Job, native: Long) {
        val jobStart = SystemClock.elapsedRealtime()
        var allOk = true

        PerfTrace.mark("analyzer.job.start",
            "key" to job.key, "kind" to job.kind,
            "priority" to priorityLabel(job.priority),
            "tracks" to job.tracks.size, "album" to (job.album ?: ""))

        /*
         * ★★ 不能在任务开始时把"要不要通知播放"缓存下来：
         *    一个正在跑的全库专辑任务可能被播放**中途提权**（playbackRelevant
         *    从 false 变 true）。缓存了旧值的话，结果会落盘但界面/增益不更新，
         *    用户看到的就是"专辑测完了但播放页还停在单曲增益"。
         */
        fun playbackRelevant(): Boolean =
            job.priority <= P_QUEUE || job.playbackRelevant

        for (t in job.tracks) {
            if (!running || job.cancelled) {
                PerfTrace.mark("analyzer.job.abort", "key" to job.key,
                    "done" to job.doneTracks, "total" to job.tracks.size)
                return
            }

            val t0 = SystemClock.elapsedRealtime()
            val r = analyzeTrack(t, native)
            val trackMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)
            job.doneTracks++
            job.lastTrackMs = trackMs
            statTrackCount.incrementAndGet()
            statTrackTotalMs.addAndGet(trackMs)
            statTrackLastMs.set(trackMs)
            statTrackMaxMs.accumulateAndGet(trackMs) { a, b -> maxOf(a, b) }

            if (r == null || r.size < 3 || r[2] < 0.5) {
                /*
                 * 单首失败（坏文件/不支持）不中断整张专辑：
                 * 专辑值已经注定无效，但其余曲目的单曲值仍然有用。
                 * 取消是例外 —— 直接退出，别继续占 CPU。
                 */
                allOk = false
                job.failedTracks++
                statFailedTracks.incrementAndGet()
                job.lastError = "分析失败：${t.displayTitle}"
                lastErrorText = job.lastError
                PerfTrace.mark("analyzer.track", "key" to t.uri,
                    "ms" to trackMs, "ok" to false, "kind" to job.kind)
                if (!running || job.cancelled) return
                continue
            }

            statCompletedTracks.incrementAndGet()
            LoudnessStore.putTrack(t, r[0], r[1])
            notifyTrackWaiters(t)
            PerfTrace.mark("analyzer.track", "key" to t.uri,
                "ms" to trackMs, "ok" to true, "kind" to job.kind,
                "lufs" to "%.2f".format(r[0]))

            if (playbackRelevant() && PlayerSession.current?.uri == t.uri) {
                PlayerSession.onLoudnessMeasured(t.uri, null)
            }
        }

        if (job.album != null && allOk && !job.cancelled && running) {
            val ar = NativePlayer.nativeLoudnessJobFinishAlbum(native)
            if (ar.size >= 3 && ar[2] >= 0.5) {
                LoudnessStore.putAlbum(
                    job.album!!, job.albumSig ?: "", ar[0], ar[1]
                )
                if (playbackRelevant()) {
                    PlayerSession.onLoudnessMeasured(null, job.album)
                }
            }
        }

        val jobMs = (SystemClock.elapsedRealtime() - jobStart).coerceAtLeast(0L)
        if (job.album != null) {
            statAlbumCount.incrementAndGet()
            statAlbumTotalMs.addAndGet(jobMs)
            statAlbumLastMs.set(jobMs)
            statAlbumMaxMs.accumulateAndGet(jobMs) { a, b -> maxOf(a, b) }
        }
        PerfTrace.mark("analyzer.job.end", "key" to job.key, "kind" to job.kind,
            "ms" to jobMs, "done" to job.doneTracks, "failed" to job.failedTracks,
            "ok" to allOk)

        // 播放相关的任务立刻落盘；全库扫描交给 Store 自己的 5 秒节流
        if (playbackRelevant()) LoudnessStore.flush()
    }

    private fun analyzeTrack(track: Track, native: Long): DoubleArray? {
        val ctx = appCtx ?: return null
        return try {
            val pfd = if (track.isLocalFile) {
                val path = track.localPath ?: return null
                ParcelFileDescriptor.open(
                    File(path), ParcelFileDescriptor.MODE_READ_ONLY
                )
            } else {
                ctx.contentResolver.openFileDescriptor(
                    Uri.parse(track.uri), "r"
                ) ?: return null
            }

            // ★ 原生层内部 dup 一份自己用；这里 use 关掉的是我们自己的 fd
            pfd.use {
                NativePlayer.nativeLoudnessJobRunTrack(native, it.fd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "分析 ${track.uri} 失败: ${e.message}")
            null
        }
    }

    private fun notifyTrackWaiters(track: Track) {
        val key = trackKey(track) ?: return
        val list = synchronized(lock) { waiters.remove(key) } ?: return
        list.forEach { it.countDown() }
    }

    /**
     * 播放健康才跑分析。
     *
     * 判据：缓冲低于目标水位的一半 → 让路。
     * 这个项目在 CPU 抢占上栽过（切屏能让解码停摆 1 秒），宁可慢慢测。
     */
    private fun playbackHealthy(): Boolean {
        if (PlayerSession.state != PlayerSession.State.PLAYING) return true
        val h = PlayerSession.handle
        if (h == 0L) return true

        return try {
            val info = NativePlayer.nativeEngineInfo(h)
            val target = info[NativePlayer.EngineInfo.TARGET_BYTES]
            val buffered = info[NativePlayer.EngineInfo.BUFFERED_BYTES]
            target <= 0L || buffered * 2 >= target
        } catch (e: Throwable) {
            Log.w(TAG, "读播放缓冲失败，暂不暂停分析: ${e.message}")
            true
        }
    }
}
