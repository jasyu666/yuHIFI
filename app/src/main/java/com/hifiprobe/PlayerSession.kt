package com.hifiprobe

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * 播放会话 —— 队列、当前曲目、以及"播完自动下一首"全都归它管。
 *
 * ★ 为什么要有这个东西：原来这套逻辑长在 `MainActivity` 里，于是
 *   **界面一没就没法连播** —— 切后台、锁屏、或者系统回收 Activity，
 *   自动下一首就停了。而我们要绕开 AudioFlinger 自己推 USB，
 *   前台服务只保证进程活着，管不了播放推进。
 *
 *   所以把播放会话从界面里剥出来放在这里，由 [PlaybackService] 驱动它的
 *   心跳（[tick]）。界面变成纯粹的观察者：注册一个 [Listener]，
 *   收到通知就重新读状态画界面，自己不再持有任何播放状态。
 *
 * ★ 进程内单例。同一进程里服务和界面共用一份 —— 这正是我们要的，
 *   不需要 Binder/IPC 那一套。代价是它活得和进程一样久，
 *   所以**注册的 Listener 必须在 onStop 里注销**，否则会泄漏 Activity。
 *
 * 线程模型：所有**改变播放状态**的操作都排在一条单线程队列上（[worker]），
 * 天然串行，不必为"两个 next 同时点"这类情况加锁。状态字段用 @Volatile
 * 暴露给界面读；事件回主线程投递。
 */
object PlayerSession {

    private const val TAG = "HiFiSession"

    /** 界面通过它接收日志与状态变化。**必须成对注册/注销。** */
    interface Listener {
        /** 一行诊断文本，界面原样追加到日志区 */
        fun onSessionLog(text: String)
        /** 状态、当前曲目或进度相关的东西变了；界面自行重新读一遍 */
        fun onSessionStateChanged()
    }

    enum class State { IDLE, OPENING, PLAYING, PAUSED }

    // ------------------------------------------------------------------
    //  设备侧 —— 由诊断流程（①~⑧）写入，播放与音量都依赖它
    // ------------------------------------------------------------------

    @Volatile
    var handle: Long = 0L
        private set

    @Volatile
    var parse: ParseResult? = null
        private set

    /*
     * UsbDeviceConnection 也放这里。
     *
     * 它和句柄是一体的：nativeClose 之后必须 close 它（顺序不能反 ——
     * libusb 包裹的就是这个 fd，提前关闭会让原生层后续 ioctl 全部 EBADF）。
     * 所以两者必须同生共死，放在一处才不会出现"一个 Activity 关掉了
     * 另一个还在用的连接"。
     *
     * 现在有两个界面都可能开设备（媒体库页自动打开、诊断页手动打开），
     * 各持一份必然出事。
     */
    @Volatile
    var connection: android.hardware.usb.UsbDeviceConnection? = null

    fun setDevice(handle: Long, parse: ParseResult?) {
        this.handle = handle
        this.parse = parse
        applyCrossfeed(handle)
    }

    /**
     * 把交叉馈送设置推给引擎。
     *
     * ★★ 引擎是**跟着 handle 一起新建**的（切输出方式、拔插后重建都会换一个），
     *    所以每拿到新 handle 都必须重设一遍 —— 不然用户开了交叉馈送，
     *    切一次输出方式它就悄悄没了，而设置页还显示"开着"。
     *
     * ★ 两条设 handle 的路径（[setDevice] / [attachEngineOnly]）都调了它，
     *   所以这里是唯一的入口，不会漏。
     */
    private fun applyCrossfeed(h: Long) {
        if (h == 0L) return
        val ctx = appContext ?: return
        /*
         * ★★ 这里**绝不能静默吞异常**。
         *
         *   第一版就是光秃秃的 `runCatching {}`，结果 .so 里没有这个 JNI 函数
         *   （UnsatisfiedLinkError）时一点动静都没有 —— 表现是"开关拨了没反应"，
         *   查了半天才想到是原生产物没重编。FACT 里那条
         *   「错误信息不能在半路被吞掉」说的就是这个。
         */
        runCatching {
            NativePlayer.nativeSetCrossfeed(
                h, Settings.crossfeedEnabled(ctx), Settings.crossfeedLevel(ctx)
            )
        }.onFailure {
            android.util.Log.e("HiFiSession", "交叉馈送设置没推下去: $it")
        }
        applySoftwareVolume(h)
    }

    /**
     * 把软件音量设置推给引擎。
     *
     * ★ 和交叉馈送一样是**引擎级**状态，handle 一换就得重设 ——
     *   所以 [setDevice] / [attachEngineOnly] 两条路都调了它。
     */
    private fun applySoftwareVolume(h: Long) {
        if (h == 0L) return
        val ctx = appContext ?: return
        runCatching {
            NativePlayer.nativeSetSoftwareVolume(
                h, Settings.volumeControl(ctx), Settings.volumeDb(ctx)
            )
        }.onFailure {
            android.util.Log.e("HiFiSession", "音量设置没推下去: $it")
        }
    }

    /** 音量滑条动了 → 立刻推下去（原子变量，解码线程下个块就生效） */
    fun refreshSoftwareVolume() = applySoftwareVolume(handle)

    /**
     * 用户在设置页改了开关或档位 → 立刻推给当前引擎。
     *
     * ★ 不用重建引擎：引擎内部是原子变量，解码线程下个块就看到了。
     */
    fun refreshCrossfeed() = applyCrossfeed(handle)

    /**
     * 当前打开的文件是不是 DSD。
     *
     * ★ **只能从引擎现取** —— `Track` 上没有这个字段（列表里那些还没播过的
     *   曲目连规格都是 `—`，更不会知道是不是 DSD）。
     * ★ 给正在播放页标"音量控制对这首无效"用。没打开文件时返 false。
     * ★ 包了 runCatching 就**必须留痕** —— 吞掉的异常会变成"提示该出现却没出现"。
     */
    fun currentIsDsd(): Boolean {
        val h = handle
        if (h == 0L) return false
        return runCatching {
            NativePlayer.nativeEngineInfo(h)[NativePlayer.EngineInfo.IS_DSD] == 1L
        }.onFailure {
            Log.e("HiFiSession", "读 DSD 标志失败: $it")
        }.getOrDefault(false)
    }

    /**
     * 状态条上那截「交叉馈送 · 中」。没开就返回 null，调用方直接拼在后面。
     *
     * ★ 读的是**用户设置**，不是引擎的实时状态 —— 每次心跳都去问一遍 JNI
     *   太浪费，而"用户想不想要"就是设置本身。
     * ★ 当前文件用不上（DSD / 单声道）时这里照样显示 ——
     *   设置卡片的小字已经写明"对 DSD 无效"，不必在状态条上再解释一遍。
     */
    fun crossfeedLabel(): String? {
        val ctx = appContext ?: return null
        if (!Settings.crossfeedEnabled(ctx)) return null
        val lv = when (Settings.crossfeedLevel(ctx)) {
            0 -> ctx.getString(R.string.crossfeed_level_weak)
            2 -> ctx.getString(R.string.crossfeed_level_strong)
            else -> ctx.getString(R.string.crossfeed_level_mid)
        }
        return ctx.getString(R.string.crossfeed_fmt, lv)
    }

    /**
     * 只挂上**原生引擎**，不带 USB 设备 —— 系统音频模式用。
     *
     * ★ 和 [setDevice] 分开是有意的：这条路 `parse` 是 null（没有 UAC 描述符），
     *   任何依赖描述符的代码都不该在系统音频模式下被走到。
     */
    fun attachEngineOnly(h: Long) {
        handle = h
        parse = null
        applyCrossfeed(h)
    }

    fun clearDevice() {
        handle = 0L
        parse = null
    }

    // ------------------------------------------------------------------
    //  队列与播放状态
    // ------------------------------------------------------------------

    val queue = PlayQueue()

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var current: Track? = null
        private set

    @Volatile
    var durationMs: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 等着设备就绪再播的那一首。
     *
     * 非 null = 正在开设备。它同时是**去重标记** —— 见 [waitForDeviceThenPlay]。
     */
    @Volatile
    private var pendingPlay: Track? = null

    /**
     * 引擎里**当前确实挂着一个会话**（openFile 成功过、还没收尾）。
     *
     * ★ 只给 [closeCurrentLocked] 判断"要不要打那份播放统计"用。
     *   原来那个判据是 `current != null` —— 因为 current 一停就清空，
     *   两者恰好等价。但 current 现在**停了也不清**（见 [stopLocked]），
     *   于是切一次输出方式就会打一份全零的报告出来。所以单独记一个。
     */
    @Volatile
    private var sessionOpen = false

    /**
     * 重建系统音频输出的**专用线程**。
     *
     * ★★ 为什么不复用 [worker]：重建内部要 `AAudioStreamBuilder_openStream`，
     *    **它会阻塞**（实测在 USB 拔插那一下再也没返回过）。放在 worker 上，
     *    整条播放链路 —— 起播、暂停、切歌、心跳 —— 会一起卡死，
     *    界面停在"播放中"、位置冻住、点什么都没反应。
     *    放进自己的线程里，最坏情况也只是这条线程没了。
     *
     * ★ 只有一条线程，且配了 [restartInFlight] 挡住重复投递：
     *   万一它真卡住了，不会越堆越多。
     */
    private val outputFixer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "output-fix").apply { isDaemon = true }
    }

    /** 这条线程当前正在修。防止每次心跳都堆一个任务 */
    private val outputFixRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 到目前为止**看见过几次断开**（单调递增）。
     *
     * ★ 用它而不是一个布尔标志：`nativeSystemOutputDead` 是"取走即清"，
     *   而路由变化会**连续来好几次**（拔掉一次、插回又一次）。
     *   用布尔量的话，第二次很容易被"正在修"那个门挡住、信息就丢了 ——
     *   这正是第一版自动重建的 bug：标志被取走，却什么都没做。
     *   单调计数不会丢，修完只要比一下有没有涨就行。
     */
    private val disconnectSeq = java.util.concurrent.atomic.AtomicLong(0)

    val isActive: Boolean get() = state == State.PLAYING || state == State.PAUSED

    /**
     * 当前播放位置（毫秒）。未播放或句柄无效时返回 0。
     *
     * 通知栏和锁屏的进度条要用 —— 但它是直接读引擎的，**每 200ms 调一次就够**，
     * 别在什么绘制回调里反复调。
     */
    fun currentPositionMs(): Long {
        val h = handle
        if (h == 0L) return 0L
        return runCatching {
            NativePlayer.nativeEngineInfo(h)[NativePlayer.EngineInfo.POSITION_MS]
        }.getOrDefault(0L)
    }

    // ------------------------------------------------------------------
    //  累计收听时长
    // ------------------------------------------------------------------

    /**
     * 已结算的部分（毫秒）。**-1 = 还没从 SharedPreferences 读进来**。
     *
     * ★ 结算发生在 [worker] 线程（setState 从那儿调），界面在主线程读，
     *   所以这两个字段都是 @Volatile。
     */
    @Volatile
    private var listenedAccMs = -1L

    /** 本段开始时刻（elapsedRealtime）。**0 = 没在计时**，只有 PLAYING 期间非 0 */
    @Volatile
    private var listenedSegStart = 0L

    /**
     * 累计收听时长（毫秒）。**含正在计的这一段**，所以界面拿到的是活值。
     *
     * ★ 口径是**墙钟**：从进入 PLAYING 到离开 PLAYING 之间真实流逝的时间。
     *   暂停、停止、出错都不计。
     *
     * ★ 欠载卡顿**计** —— 这是刻意的。"听了多久"问的是人的感受，
     *   音频卡了一下但你还在听。另一个候选口径是"输出端实际送出的帧数"
     *   （`IsoPlayer` / `SystemAudioSink` 本来就在数），那个回答的是
     *   "送出去了多少音频"，是**另一个指标**，别把两者混起来。
     *
     * ★ 为什么用 elapsedRealtime 而不是 currentTimeMillis：它不受用户
     *   改系统时间影响，也不会在被 NTP 校正时跳变。
     */
    fun listenedTotalMs(): Long {
        val s = listenedSegStart
        val acc = if (listenedAccMs < 0) 0L else listenedAccMs
        return if (s == 0L) acc else acc + (SystemClock.elapsedRealtime() - s)
    }

    /** 清空累计时长（用户主动触发）。立即落盘，不留进程内副本 */
    fun resetListened() {
        synchronized(this) {
            listenedAccMs = 0L
            // 正在放的话表要重新起 —— 否则清零后这一段会被算成负数时长
            listenedSegStart = if (state == State.PLAYING) SystemClock.elapsedRealtime() else 0L
        }
        appContext?.let { Settings.setListenedMs(it, 0L) }
    }

    /**
     * 把"上次结算到现在"记进累计并落盘，**表继续走**。
     *
     * ★ 这是**检查点**，不是停表：一首放完换下一首时流没停，收听也应该连着算。
     *   它的唯一作用是**别让最后一段在进程被杀时蒸发**。
     *
     * ★★ 调用时机是这块最要紧的地方 —— **必须低频**：
     *   每曲一次（playLocked）+ 离开 PLAYING + 服务销毁。
     *   **绝不能跟着 200ms 心跳写**，那是每秒 5 次 SharedPreferences。
     *   （项目里已经栽过一次同类：心跳驱动的 notifyDataSetChanged 打断拖动排序。）
     */
    @Synchronized
    private fun checkpointListened() {
        if (listenedAccMs < 0) return          // 还没读进来过，别用脏值覆盖磁盘
        val s = listenedSegStart
        if (s == 0L) return                    // 没在计时，没什么可结算
        val now = SystemClock.elapsedRealtime()
        listenedAccMs += now - s
        listenedSegStart = now                 // 重新起表，接着走
        appContext?.let { Settings.setListenedMs(it, listenedAccMs) }
    }

    /** 进程要走之前把最后一段落盘（[PlaybackService.onDestroy] 调） */
    fun flushListened() = checkpointListened()

    // ------------------------------------------------------------------
    //  内部
    // ------------------------------------------------------------------

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    /**
     * 播放操作专用线程。
     *
     * 打开文件、claim 接口、下发速率全是阻塞调用（原来在 Activity 里
     * 用一个临时 Thread 跑），这里用一条常驻单线程：既避免占用主线程，
     * 又保证这些操作**严格串行** —— 否则连点两下「下一首」就会有两个
     * 线程同时 claim 同一个接口。
     */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "player-session").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        QueueStore.init(appContext!!)
        // ★ 只在第一次读进来。init 会被多个 Activity 反复调，
        //   每次都重新读的话会把内存里已经攒的那段冲掉（数字变小）。
        if (listenedAccMs < 0) listenedAccMs = Settings.listenedMs(appContext!!)
        restoreQueueIfNeeded()
    }

    /** 队列恢复只做一次 */
    @Volatile
    private var queueRestored = false

    /**
     * 从磁盘恢复上次的播放队列。**幂等**，可以在任何"库已就绪"的时机反复调。
     *
     * ★★ 为什么不能只在 [init] 里做：恢复要把 uri 映射回曲目，
     *   而那需要遍历整个库（`Library.all()`）。可 [init] 是在各 Activity 的
     *   onCreate 里调的 —— 首次扫描那时**可能还没跑完，库是空的**。
     *   库空着硬恢复只会得到一个空队列，而且会把"恢复过"标记掉，
     *   用户就再也等不到它了。
     *
     * ★ 所以：库还没内容就**跳过、不标记**，等下次再试
     *   （界面扫描完之后会调 [onLibraryReady]）。
     *
     * ★ 恢复本身放在 [worker] 上跑 —— `Library.all()` 建索引是 O(库大小)，
     *   放界面线程会卡一下（[Playlists.tracksOf] 那边为同一件事写过警告）。
     */
    private fun restoreQueueIfNeeded() {
        if (queueRestored) return
        if (Library.isEmpty()) return          // 库还没扫完，等下次
        queueRestored = true
        worker.execute {
            if (QueueStore.restore(queue)) {
                /*
                 * ★★ 光恢复队列**不够** —— [current] 是**另一个字段**，
                 *    而界面（播放条、正在播放页）看的全是它。
                 *
                 *    不同步的话就是这个症状：日志说"已恢复 10 首"，
                 *    队列页里也确实有 10 首，可首页播放条写着
                 *    「没有歌曲在播放」，点进去也一样 —— 看着像恢复失败了。
                 *
                 *    （这个坑是上真机才发现的：队列对象对了，界面字段没跟上。）
                 */
                current = queue.current()
                durationMs = current?.durationMs ?: 0L
                postLog("已恢复上次的播放队列（${queue.size} 首）")
                postState()
            }
        }
    }

    /**
     * 库扫描完成之后由界面调一次 —— 把队列恢复提前到用户看得见的时候，
     * 而不是干等下一次进首页。
     */
    fun onLibraryReady() = restoreQueueIfNeeded()

    fun addListener(l: Listener) {
        synchronized(listeners) { if (l !in listeners) listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun postLog(text: String) {
        /*
         * ★ 同时写 logcat —— 这不是可选项。
         *
         *   以前只推给界面监听器：用户不在诊断页（或者那个页面根本没注册
         *   监听器）时，出事的现场在 logcat 里**一个字都没有**。
         *   "点了歌没反应"就这么被藏了整整一个版本，排查时无从下手。
         *   ✗/⚠ 开头的是故障，用 warn 级别，grep 时一眼能挑出来。
         */
        if (text.startsWith("✗") || text.startsWith("⚠")) Log.w(TAG, text)
        else Log.i(TAG, text)

        mainHandler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onSessionLog(text) }
        }
    }

    /**
     * 起播失败要**让用户看见原因**，不能只丢一行日志。
     *
     * ★★ 为什么单独做这件事
     *
     *   除了音乐库页（写进列表上方那行小字）和诊断页，**所有页面的
     *   `onSessionLog` 都是 `Unit`** —— 首页、正在播放页、专辑页、歌单页、
     *   队列页统统忽略。于是"点了一首歌什么都没发生"又回来了，
     *   而这一次原因甚至不在界面上、只在 logcat 里。
     *
     *   这个项目已经在这上面栽过一次（见上面 postLog 的注释），不重蹈。
     *   失败**必须**有一条用户看得见的说明。
     *
     * ★ 用**对话框**而不是 Toast：Toast 一闪而过，而"为什么没播"往往需要
     *   几行字才说得清 —— 用户还没读完就没了，等于没说。
     *   对话框还有个「关闭」按钮，用户自己决定什么时候收起来。
     *
     * ★ 对话框需要 Activity，所以这里靠 [Foreground] 拿当前页面。
     *   拿不到（后台自动连播时失败）就退回 Toast —— 那总比什么都不说强。
     */
    private fun notifyFailed(reason: String) {
        val ctx = appContext ?: return
        mainHandler.post {
            val act = Foreground.activity
            if (act == null || act.isFinishing || act.isDestroyed) {
                android.widget.Toast.makeText(ctx, reason, android.widget.Toast.LENGTH_LONG).show()
                return@post
            }
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(R.string.playback_failed_title)
                .setMessage(reason)
                .setPositiveButton(R.string.action_close, null)
                .show()
        }
    }

    /**
     * 从会话层弹提示。
     *
     * ★ 用 applicationContext 是刻意的：点歌失败时用户可能在任何一个页面，
     *   而会话层**拿不到也不该持有**当前 Activity（那会把 Activity 泄漏到
     *   进程级的单例里）。Toast 用 applicationContext 是合法的。
     */
    private fun toast(text: String) {
        val ctx = appContext ?: return
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun postState() {
        mainHandler.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onSessionStateChanged() }
        }
    }

    private fun setState(s: State) {
        /*
         * ★★ 收听时长挂在这里是刻意的：[setState] 是所有状态变化的**唯一漏斗**。
         *
         *   写在 togglePause / stopLocked / onFinished / playLocked 里各一份的话，
         *   只要将来多一条进入或离开播放的路，就必然漏一处 —— 而且漏了不报错，
         *   只是数字偏小，没人会发现。
         *
         * ★ 只有 PLAYING 走表。暂停 / 停止 / 出错一律停表并结算。
         *   暂停期间**不算收听** —— 人没在听。
         */
        if (s == State.PLAYING) {
            if (listenedSegStart == 0L) listenedSegStart = SystemClock.elapsedRealtime()
        } else {
            checkpointListened()      // 把最后一段记进去
            listenedSegStart = 0L     // 再停表
        }
        state = s
        postState()
    }

    // ------------------------------------------------------------------
    //  对外操作
    // ------------------------------------------------------------------

    /** 用一批曲目替换队列并从 [startAt] 开始播 */
    fun setQueue(tracks: List<Track>, startAt: Int = 0) {
        worker.execute {
            queue.setTracks(tracks, startAt)
            persistQueue()
            postState()
            playCurrentLocked()
        }
    }

    fun playIndex(index: Int) {
        worker.execute {
            queue.jumpTo(index)?.let { playLocked(it) }
        }
    }

    fun next() = worker.execute {
        queue.manualNext()?.let { playLocked(it) }
    }

    fun prev() = worker.execute {
        queue.manualPrev()?.let { playLocked(it) }
    }

    /**
     * 「上一首」的完整语义（多数播放器的习惯）：
     * 已经播了 3 秒以上就先回到本曲开头，否则才真的退到上一首。
     */
    fun prevSmart() = worker.execute {
        val h = handle
        val posMs = if (h != 0L) NativePlayer.nativeEngineInfo(h)[NativePlayer.EngineInfo.POSITION_MS] else 0L
        if (posMs > 3000 && state != State.IDLE) {
            NativePlayer.nativeSeekTo(h, 0)
            postLog("回到本曲开头")
            postState()
        } else {
            queue.manualPrev()?.let { playLocked(it) }
        }
    }

    fun togglePause() = worker.execute {
        if (!isActive) {
            /*
             * ★★ 从磁盘恢复出来的队列就是这个状态：队列里有当前曲目，
             *    但**一个文件都还没加载**（state 是 IDLE）。
             *
             *    这时按播放键的语义应该是"开始放它"，而不是什么都不做 ——
             *    否则用户辛辛苦苦恢复回来的队列，点播放没反应，等于白恢复。
             *
             *    ★ 恢复出来的队列**不会自动播放**（用户要的），
             *      所以这里是它唯一的"接着听"入口。
             */
            if (queue.current() != null) playCurrentLocked()
            return@execute
        }
        val h = handle
        if (h == 0L) return@execute
        val nowPaused = state != State.PAUSED
        NativePlayer.nativeSetPaused(h, nowPaused)
        setState(if (nowPaused) State.PAUSED else State.PLAYING)
        postLog(if (nowPaused) "已暂停" else "继续播放")
    }

    fun seekTo(ms: Int) = worker.execute {
        val h = handle
        if (h == 0L || !isActive) return@execute
        NativePlayer.nativeSeekTo(h, ms)
        postState()
    }

    // ------------------------------------------------------------------
    //  队列编辑
    //
    //  队列是用户的东西，得能加、能删、能排。全部排在 worker 上 ——
    //  删掉正在播的那首会牵动播放本身，和切歌必须串行。
    // ------------------------------------------------------------------

    /** 「下一首播放」 */
    fun playNextInQueue(track: Track) = worker.execute {
        noteShuffleOff(queue.insertNext(track))
        persistQueue()
        postState()
    }

    /** 加到队尾 */
    fun addToQueue(tracks: List<Track>) = worker.execute {
        tracks.forEach { noteShuffleOff(queue.append(it)) }
        persistQueue()
        postState()
    }

    /**
     * 一次移除多项。
     *
     * ★ 必须在**一个** worker 任务里做完，而且**从大到小**删。
     *   逐条调用 removeFromQueue 的话，每次删除都会让后面的下标前移，
     *   前面排队的那些任务执行时用的还是老下标 —— 删掉的就是别的歌了。
     *   先排序再倒着删，就与"从后往前一个个删"完全等价。
     */
    fun removeFromQueue(indices: Collection<Int>) = worker.execute {
        val targets = indices.distinct().filter { it in 0 until queue.size }.sortedDescending()
        if (targets.isEmpty()) return@execute

        val wasCurrent = queue.currentIndex in targets
        for (i in targets) queue.removeAt(i)
        noteShuffleOff(false)
        persistQueue()
        postLog("已从队列移除 ${targets.size} 首")

        if (wasCurrent) {
            val next = queue.current()
            if (next == null || queue.isEmpty) stopLocked(auto = false) else playLocked(next)
        }
        postState()
    }

    fun removeFromQueue(index: Int) = worker.execute {
        val wasCurrent = index == queue.currentIndex
        if (queue.removeAt(index)) {
            noteShuffleOff(false)
            persistQueue()
            postLog("已从队列移除")
            if (wasCurrent) {
                // 删掉的正是正在播的：接着放下一首（队列里 pos 已经指过去了）
                val next = queue.current()
                if (next == null || queue.isEmpty) {
                    stopLocked(auto = false)
                } else {
                    playLocked(next)
                }
            }
            postState()
        }
    }

    fun moveInQueue(from: Int, to: Int) = worker.execute {
        noteShuffleOff(queue.move(from, to))
        persistQueue()
        postState()
    }

    fun clearUpcoming() = worker.execute {
        if (queue.clearUpcoming()) {
            persistQueue()
            postLog("已清空未播放的队列")
        }
        postState()
    }

    /**
     * 切换随机播放。
     *
     * ★ **走 worker**，界面不要直接 `queue.shuffle = …`。
     *   原来 NowPlayingActivity 就是在界面线程直接改的，而队列的增删改
     *   全在 [worker] 上跑 —— [PlayQueue] 不是线程安全的，
     *   两边同时写就是数据竞争（表现是"偶尔顺序乱了"，极难复现）。
     *
     * ★ 顺带把队列落盘：随机开关是队列状态的一部分，不存的话重启就丢。
     */
    fun toggleShuffle() = worker.execute {
        queue.shuffle = !queue.shuffle
        persistQueue()
        postState()
    }

    /** 循环模式 OFF → ALL → ONE → OFF。同样走 worker + 落盘 */
    fun cycleRepeat() = worker.execute {
        queue.repeatMode = when (queue.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        persistQueue()
        postState()
    }

    /** 队列被编辑时如果关掉了随机，提示一次 —— 否则用户不知道发生了什么 */
    private fun noteShuffleOff(changed: Boolean) {
        if (changed) postLog("编辑队列已自动关闭随机播放（开着随机时队列顺序没有意义）")
    }

    /**
     * 把队列落到磁盘。
     *
     * ★★ **低频是硬要求**：只在用户真的改了队列、或者切歌的时候调。
     *   跟着 200ms 心跳写的话就是每秒 5 次文件写。
     *
     * ★ 拖动排序那种连续操作不在中途写 —— 界面**松手时**调 [flushQueue]
     *   一次就够，和 [Playlists.moveIn] 是同一套办法。
     */
    private fun persistQueue() {
        QueueStore.save(queue)
    }

    /** 拖动排序松手时由界面调一次 —— 中途每帧写盘是没必要的 */
    fun flushQueue() = persistQueue()

    /** 用户主动停止：收掉播放，但**保留设备**（还能再选一首接着播） */
    fun stop() = worker.execute { stopLocked(auto = false) }

    /**
     * 同步停止 —— 等真正停干净了才返回。
     *
     * ★ 给诊断流程（测试音、速率扫描）用。它们停掉文件播放之后**紧接着**
     *   就要 claim 接口独占设备，而 [stop] 是排队异步执行的：不同步等的话，
     *   两边会同时去 claim 同一个接口，轻则 BUSY，重则把设备的 alt 状态搞乱。
     *
     * 超时是保底：worker 上若正卡在一次慢打开上，宁可让诊断流程报错，
     * 也不能把界面线程永久挂住。
     */
    fun stopBlocking(timeoutMs: Long = 3000) {
        val task = java.util.concurrent.FutureTask {
            stopLocked(auto = false)
        }
        worker.execute(task)
        runCatching {
            task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure { Log.w(TAG, "同步停止超时/失败: ${it.message}") }
    }

    // ------------------------------------------------------------------
    //  心跳 —— 由 PlaybackService 每 200ms 调一次
    // ------------------------------------------------------------------

    /**
     * 输出被系统掐了之后收拾现场：**先暂停，再把输出流重建起来**。
     *
     * 跑在 [outputFixer] 那条专用线程上（**不是 worker**）—— 重建内部要
     * `openStream`，它会阻塞；放在 worker 上会把整条播放链路一起卡死。
     *
     * ★★ 为什么"暂停"之后还必须重建：
     *   AAudio 的流被掐掉之后，`nativeSetPaused(false)` **只是清标志位，
     *   不会重开流**。不重建的话，用户按播放看到的是"界面在走、没有声音" ——
     *   也就是他报的"插回来要按一次暂停播放才有声"（那一下恰好让上层
     *   重新走了一遍起播）。
     *
     * ★ 循环最多四轮：一次拔插会产生**两次**路由变化（拔一次、插一次），
     *   重建完可能马上又被掐。拿 [disconnectSeq] 比有没有新断开，没有就收手。
     */
    private fun dispatchOutputFix() {
        outputFixer.execute {
            var round = 0
            try {
                /*
                 * ★ 从这一刻起重新计数。
                 *   不清的话它就一直是正的，心跳每 200ms 都会以为"又有断开"，
                 *   于是没完没了地派任务。
                 *   清零之后，"修的过程中又来了一次断开"仍然能被下面那个
                 *   `disconnectSeq != seen` 捕捉到。
                 */
                disconnectSeq.set(0)

                /*
                 * ★★ 先分清是"**拔掉**"还是"**只是路由变了**" —— 这两件
                 *    事的正确处理是**相反**的：
                 *
                 *      · 解码器不在了 → 输出设备没了 → **暂停**（拔耳机自动暂停的惯例）
                 *      · 解码器还在   → 只是路由在动（刚插上、HAL 正在接管）
                 *                     → **静默重建，继续放**，别打断用户
                 *
                 *    ★ 用户实测："刚插入就按播放，有声音然后会再暂停一次" ——
                 *      就是插入引发的第二次路由变化把刚起的流掐了，
                 *      而上一版不分青红皂白一律暂停。
                 *
                 *    判据用 [DeviceGate.presentAudioDeviceName]：它就是查
                 *    UsbManager 的设备列表，和插拔广播是同一条真相。
                 */
                val dacPresent = appContext?.let { DeviceGate.presentAudioDeviceName(it) } != null

                while (round < 4) {
                    val hh = handle
                    if (hh == 0L || current == null) break
                    if (state != State.PLAYING && state != State.PAUSED) break
                    // 已经切到直连了就别重建 —— 那条路归 DeviceGate 管
                    if (appContext?.let { Settings.usbDirect(it) } == true) break

                    val seen = disconnectSeq.get()

                    if (round == 0 && state == State.PLAYING) {
                        if (!dacPresent) {
                            NativePlayer.nativeSetPaused(hh, true)
                            setState(State.PAUSED)
                            postLog("⚠ USB 解码器已拔出，音频输出被系统重置 —— 已暂停，按播放继续。")
                        } else {
                            // ★ 路由在动而已（刚插上 / HAL 在接管）—— 不打断
                            postLog("音频输出已切换，正在重建…")
                        }
                    }

                    val err = NativePlayer.nativeRestartSystemOutput(hh)
                    if (err.isNotBlank()) {
                        // ★ 绝不静默 —— 重建不了也得让用户知道，并给出出路
                        lastError = "音频输出重建失败：$err"
                        postLog("✗ $lastError（重新点一次播放即可恢复）")
                        break
                    }
                    round++

                    // 路由可能还在变（拔一次、插一次）—— 给一点时间再看
                    Thread.sleep(250)
                    if (disconnectSeq.get() == seen) break      // 期间没新断开 → 稳了
                }
                if (round >= 4 && disconnectSeq.get() != 0L) {
                    // 反复被掐、修不动 —— 说清楚，别让它永远静默
                    postLog("✗ 音频输出反复被系统重置，重建 $round 次仍未稳定")
                } else if (round > 1) {
                    postLog("音频输出重建了 $round 次才稳住（路由连续变化）")
                }
            } catch (e: Exception) {
                postLog("✗ 音频输出修复异常：${e.message}")
            } finally {
                outputFixRunning.set(false)
            }
        }
    }

    /**
     * 检查当前曲目是否放完了，放完就按循环模式推进。
     *
     * ★ 这个判断**必须在服务里做**，不能靠界面轮询 —— 界面一停，
     *   自动下一首就没了，而那恰恰是"后台连播"的全部意义。
     */
    fun tick() {
        val h = handle
        if (h == 0L) return

        /*
         * ★★ 输出被系统掐掉了 —— **先处理它，再管播放状态**。
         *
         *   AAudio 在**任何一次音频路由变化**时都会掐掉现有流。这条路
         *   一次拔、一次插就是**两次**，而这两次性质不同：
         *
         *     · 拔掉时我们还在播 → 该暂停（和"拔耳机自动暂停"是同一个惯例）
         *     · **插回时已经是暂停态了**，可那一次路由变化同样会掐流
         *
         *   ★ 所以这一段必须在下面 `state != PLAYING` 那个早退**之前** ——
         *     否则第二次断开永远走不到处理逻辑，用户看到的就是
         *     "插回来按播放没声音，非得再点一次暂停/播放"。
         *
         * `nativeSystemOutputDead` 是"取走即清"，所以这里只是把它转成
         * 一个**单调计数**，真正的活交给 [dispatchOutputFix]。
         */
        if (NativePlayer.nativeSystemOutputDead(h)) {
            disconnectSeq.incrementAndGet()
        }
        if (disconnectSeq.get() > 0 &&
            outputFixRunning.compareAndSet(false, true)) {
            dispatchOutputFix()
        }

        if (state != State.PLAYING) return
        val info = NativePlayer.nativeEngineInfo(h)
        val finished = info[NativePlayer.EngineInfo.FINISHED] == 1L
        val buffered = info[NativePlayer.EngineInfo.BUFFERED_BYTES]
        val posMs = info[NativePlayer.EngineInfo.POSITION_MS]

        /*
         * 播放结束的判定：解码到末尾，**且**缓冲已排空或位置已抵达时长。
         * 两个条件取或 —— FLAC 的时长是估算值，位置可能永远差最后几毫秒。
         */
        val drained = finished &&
                (buffered <= 0L || (durationMs > 0 && posMs >= durationMs))
        if (!drained) {
            postState()          // 进度更新，界面要用
            return
        }

        worker.execute {
            // 重新确认一次：排队期间可能已经被用户停掉了
            if (state != State.PLAYING) return@execute
            val nextTrack = queue.onFinished()
            if (nextTrack == null) {
                postLog("队列播放完毕")
                stopLocked(auto = true)
            } else {
                postLog("—— 自动播放下一首 ——")
                playLocked(nextTrack)
            }
        }
    }

    // ------------------------------------------------------------------
    //  真正干活的（只在 worker 线程上调用）
    // ------------------------------------------------------------------

    private fun playCurrentLocked() {
        val t = queue.current()
        if (t == null) {
            postLog("队列是空的")
            return
        }
        playLocked(t)
    }

    /**
     * 打开一首并起播。
     *
     * 这段流程原来长在 `MainActivity.beginFilePlayback` 里，逐字搬过来 ——
     * 顺序是踩过坑才定下来的，别随意调整：
     *
     *   1. 先收掉上一个会话（释放接口、关掉旧 fd），否则 claim 会 BUSY
     *   2. 打开 fd 交给原生层，由**引擎**决定输出速率与线格式
     *   3. 再拿引擎的决定去挑设备的 alt setting
     *   4. claim AudioControl（时钟实体在它上面）
     *   5. **在空闲态**下发采样率，然后才切 alt —— 顺序反了会没声音
     */
    private fun playLocked(track: Track) {
        /*
         * ★ 收听时长的**每曲一次**检查点。
         *
         *   为什么在这里：换曲是最自然的低频时间点，而且它覆盖了
         *   "一直放着不停、中途进程被杀"这种情况 —— 最坏只丢一首歌的时长。
         *
         *   ★ 注意它是**检查点不是停表**：一首放完换下一首时流没断，
         *     收听应该连着算。表在 checkpoint 里重新起，接着走。
         */
        checkpointListened()

        /*
         * ★★ 设备没就绪 —— **不要报错了事**。
         *
         *   这里原来是 `postLog("✗ 尚未打开 USB 设备")` 然后 IDLE 收场，
         *   而且没有任何重试。于是从首页走「所有歌曲」点歌永远是"点了没反应"：
         *   那个页面不像音乐库那样顺手开设备，handle 一直是 0，再点还是一样。
         *
         *   现在改成：先记下要播的这首，把设备开起来，开好了自动续播。
         */
        if (!DeviceGate.isReady) {
            waitForDeviceThenPlay(track)
            return
        }

        val h = handle
        val parsed = parse
        val appCtx0 = appContext
        if (h == 0L || appCtx0 == null) {
            lastError = "原生上下文不可用（会话未初始化？）"
            postLog("✗ $lastError")
            setState(State.IDLE)
            return
        }

        /*
         * ★★ 输出模式在这里定下来，而且**必须在 nativeOpenFile 之前**告诉引擎 ——
         *   速率决策发生在 open() 里面，它得先知道目标速率。
         *   （和 nativeSetDeviceRates 是同一个套路。）
         *
         *   ★ 系统速率**每次都重新问**：用户可能中途连了蓝牙、换了耳机，
         *     那个速率是会变的。问一次几毫秒，不值得为省这点钱埋一个变调的坑。
         *
         *   返回 0 表示这次走 **USB 直连**（bit-perfect），需要 UAC 描述符。
         */
        val sysRate = if (Settings.usbDirect(appCtx0)) 0
        else NativePlayer.nativeProbeSystemAudioRate().takeIf { it > 0 } ?: 48000

        if (sysRate == 0 && parsed == null) {
            // 直连模式却拿不到描述符 —— 多半是两次读之间被拔了
            lastError = "USB 解码器已断开"
            postLog("✗ $lastError")
            setState(State.IDLE)
            return
        }

        // 测试音走的是同一条 USB 通路，先停掉
        if (NativePlayer.nativeIsPlaying(h)) {
            runCatching { NativePlayer.nativeStop(h) }
        }
        // 上一个文件会话要收干净，否则 claim 会 BUSY
        closeCurrentLocked()

        setState(State.OPENING)
        current = track
        durationMs = track.durationMs
        lastError = null

        /*
         * ★ 开播就标「已播过」，**不是放完才标**。
         *
         *   只在放完时标的话，**跳着播的那首**和**中途退出的那首**都不算 ——
         *   可用户明明听了。开播即标更贴直觉。
         *
         * ★ 顺带把队列落一次盘：换歌是最自然的低频时间点，
         *   它同时覆盖了"一直放着不停、中途进程被杀"这种情况。
         */
        val playingIdx = queue.currentIndex
        if (playingIdx >= 0) queue.markPlayed(playingIdx)
        persistQueue()

        postLog("▶ ${track.displayTitle}  —  ${track.displayArtist}")

        try {
            val ctx = appContext
                ?: throw IllegalStateException("会话未初始化（缺 applicationContext）")

            /*
             * 两种来源都要能开：
             *   · file://    库内文件（私有目录里的普通文件）
             *   · content:// SAF 选中的外部文件
             *
             * 库内文件**不要**绕 ContentResolver —— 它对 file:// 的支持在
             * 各版本上并不一致，而 ParcelFileDescriptor.open 是直路。
             */
            val pfd = if (track.isLocalFile) {
                ParcelFileDescriptor.open(
                    java.io.File(track.localPath!!),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
            } else {
                ctx.contentResolver.openFileDescriptor(Uri.parse(track.uri), "r")
            } ?: throw IllegalStateException("打不开这个文件")

            /*
             * detachFd 把 fd 的所有权整个交给原生层，此后由引擎负责 close。
             * **不要**再调 pfd.close()：detach 之后这个 fd 已经不归它管，
             * 再关一次就会把原生层正在读的 fd 关掉。
             */
            val fd = pfd.detachFd()

            // ★ 输出模式必须在开文件之前交给引擎（速率决策发生在 open() 里面）
            NativePlayer.nativeSetOutputMode(h, sysRate)

            val openMsg = NativePlayer.nativeOpenFile(h, fd)
            if (openMsg.isNotBlank()) postLog(openMsg.trim())
            if (!NativePlayer.nativeEngineReady(h)) {
                throw IllegalStateException("引擎无法打开该文件")
            }
            // 从这里起引擎里真的挂着一个会话了 —— 收尾时要打它的统计
            sessionOpen = true

            // 引擎已按源文件决定输出速率与线格式，设备这边只能去适配
            val info = NativePlayer.nativeEngineInfo(h)
            val outRate = info[NativePlayer.EngineInfo.OUTPUT_RATE].toInt()
            val ch = info[NativePlayer.EngineInfo.CHANNELS].toInt()
            val srcBits = info[NativePlayer.EngineInfo.SOURCE_BITS].toInt()
            val subframe = info[NativePlayer.EngineInfo.SUBFRAME_SIZE].toInt()
            val srcRate = info[NativePlayer.EngineInfo.SOURCE_RATE].toInt()
            durationMs = info[NativePlayer.EngineInfo.DURATION_MS]

            /*
             * 把探测到的精确规格回填到队列里那一首上。
             *
             * 列表在没有播放之前是拿不到规格的（逐首开 FFmpeg 太慢），
             * 所以界面上先显示 "—"；播过一次之后就补上真值。
             * 用 replaceAt 而不是改 current 就完事 —— 否则列表里那一行
             * 永远是空的，用户得播一遍全部才看得到规格。
             */
            val filled = track.withSpec(srcRate, srcBits, ch).withDuration(durationMs)
            val idx = queue.currentIndex
            if (idx >= 0) queue.replaceAt(idx, filled)
            current = filled

            val isDsd = info[NativePlayer.EngineInfo.IS_DSD] == 1L

            /*
             * ★★ 输出走哪条路，在这里分岔。
             *
             *   系统音频（sysRate > 0）：交给 AAudio，**完全不碰 USB** ——
             *   不需要 UAC 描述符，也就不需要 alt setting 那一套。
             *
             *   USB 直连（sysRate == 0）：老路，挑 alt setting + iso 传输。
             *   下面那段 PCM/DSD 互斥的注释说的就是这条路。
             */
            if (sysRate > 0) {
                /*
                 * ★ **DSD 不准走系统音频。**
                 *
                 *   DSD 是裸位流，AAudio 只吃 PCM —— 要送进去必须先转 PCM，
                 *   而那是**有损**的（FIR 低通 + 抽取）。与其偷偷降质，
                 *   不如直接说清楚，让用户决定插不插小尾巴。
                 */
                if (isDsd) {
                    // 文案在 strings.xml：它是要弹给用户看的，不是程序日志。
                    // ★ 别在代码里拼 markdown —— 对话框和 Toast 都不渲染它（踩过一次）
                    throw IllegalStateException(appCtx0.getString(R.string.err_dsd_system_audio))
                }
                val msg = NativePlayer.nativeStartSystemPlayback(h)
                if (msg.isNotBlank()) postLog(msg.trim())
                if (!NativePlayer.nativeIsPlaying(h)) {
                    throw IllegalStateException("系统音频未能启动（原因见上面一行）")
                }
            } else {
                /*
                 * ★★ PCM 和 DSD 走**两条互斥**的 alt 选择路径。
                 *
                 *   DSD 必须挂 raw DSD 通道（`bmFormats` bit31）；
                 *   而 `selectForPlayback` 挑 PCM 时会**显式排除**那条通道。
                 *
                 *   挑错了两边都是噪音 —— 把 PCM 数据灌进 DSD 通道，
                 *   DAC 会按 DSD 去解，出来是刺耳嘶声。
                 */
                val p = parsed!!
                val rec = if (isDsd) {
                    p.selectForDsdPlayback(ch) ?: throw IllegalStateException(
                        "这台设备没有 raw DSD 通道（AS_GENERAL.bmFormats 的 bit31 没置位）" +
                                " —— DSD 文件无法原生播放"
                    )
                } else {
                    p.selectForPlayback(outRate, ch, srcBits, subframe)
                        ?: throw IllegalStateException(
                            "设备没有任何 alt setting 能接受 ${outRate}Hz / ${ch}ch —— 该文件无法播放"
                        )
                }

                val si = rec.streaming
                val fmt = rec.format
                val ep = rec.endpoint
                val clockId = if (si.uacVersion >= 2) {
                    p.clocks.firstOrNull()?.clockId ?: 1
                } else 0

                val startMsg = NativePlayer.nativeStartPlayback(
                    h, si.uacVersion, p.acInterface,
                    si.interfaceNumber, rec.alt.alt, clockId,
                    ep.address, rec.feedbackEndpoint?.address ?: 0,
                    fmt.subframeSize, fmt.bitResolution, ep.effectiveMaxPacket
                )
                if (startMsg.isNotBlank()) postLog(startMsg.trim())

                if (!NativePlayer.nativeIsPlaying(h)) {
                    throw IllegalStateException("iso 传输未能启动")
                }
            }

            if (track.durationMs == 0L && durationMs > 0) {
                postLog("时长: ${durationMs / 1000} 秒")
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            postLog("✗ $lastError")
            // 用户看得见的那一份 —— 光有日志等于"点了没反应"
            e.message?.let { notifyFailed(it) }
            // 失败路径要自己收尾：fd 可能已经交给原生层，接口也可能 claim 了一部分
            runCatching { NativePlayer.nativeStopPlayback(h) }
            runCatching { NativePlayer.nativeCloseFile(h) }
            setState(State.IDLE)
            releaseResources()
            return
        }

        startResources()
        setState(State.PLAYING)
    }

    /**
     * 设备还没就绪：把这首记下来，开好设备再自动播。
     *
     * 覆盖三种现实情况 —— 冷启动后第一次点歌、解码器刚插上正在授权、
     * 点歌时设备正好被拔了又插回来。它们对用户应该是同一件事：**我点了，它播**。
     */
    private fun waitForDeviceThenPlay(track: Track) {
        val ctx = appContext
        if (ctx == null) {
            lastError = "会话未初始化（缺 applicationContext）"
            postLog("✗ $lastError")
            setState(State.IDLE)
            return
        }

        /*
         * ★ 已经在等设备了 → 只把待播曲目换成最新点的那首，**不要再发起一次**。
         *
         *   [DeviceGate] 里也有一道串行化，两道都要有，防的不是同一件事：
         *   那边防"两条路同时打开设备"（会拿到两个 connection 撞同一个 fd），
         *   这边防"同一个会话重复排队"（第二首会把第一首顶掉，但顺序错乱）。
         */
        if (pendingPlay != null) {
            pendingPlay = track
            return
        }
        pendingPlay = track
        lastError = "没有连接 USB 解码器"
        postLog("⏳ 正在连接 USB 解码器…")
        setState(State.OPENING)

        // ensureOpen 的注册/授权都要在主线程，回调则不保证在哪个线程
        mainHandler.post {
            DeviceGate.ensureOpen(ctx) { ok, msg ->
                val t = pendingPlay
                pendingPlay = null
                if (ok && t != null) {
                    worker.execute { playLocked(t) }
                } else if (!ok) {
                    lastError = msg
                    postLog("✗ $msg")
                    setState(State.IDLE)
                    // 用户点了歌却什么都没发生，必须说一声
                    toast(msg)
                }
            }
        }
    }

    /**
     * 设备被拔掉时由 [DeviceGate] 调用。
     *
     * ★ 不能静默变 IDLE —— 用户得知道刚才为什么停了。
     *   设备侧（句柄、连接、原生对象）已经同步收干净了，这里只负责说清楚。
     */
    fun notifyDeviceDetached() {
        lastError = "USB 解码器已断开"
        postLog("⚠ USB 解码器已断开 —— 插回后重新点歌即可")
        setState(State.IDLE)
    }

    /** 收掉当前会话（释放接口 / 关 fd），但不动设备句柄 */
    private fun closeCurrentLocked() {
        val h = handle
        if (h != 0L) {
            /*
             * ★ nativeStopPlayback 的返回值得留下来。
             *
             * 它包含这一首的完整统计（缺口、提交间隔、穿透队列、欠载、
             * feedback 实测速率、引擎帧数），是排查切歌质量唯一的依据。
             *
             * 把逻辑搬进会话时我一度把这个返回值丢了 —— 结果导出的报告里
             * 再也看不到「播放统计」，诊断能力直接退回到几个月前。
             * 这类"搬代码时漏掉一个返回值"的退步很隐蔽：功能照常工作，
             * 只是出事时没有数据可看。
             */
            val hadSession = sessionOpen
            sessionOpen = false
            val report = runCatching { NativePlayer.nativeStopPlayback(h) }.getOrNull()
            /*
             * ★ 只有**真的跑过会话**才打这份报告。
             *
             *   冷启动后第一次播放时，这里根本没有"上一个会话"可收 ——
             *   照样打的话是一份**全零**的（URB 完成=0、累计字节=0、端点=0x0、
             *   已送音频时长≈0 秒），纯噪音。以前这份噪音只在诊断页看得见，
             *   p41 起 postLog 也写 logcat 了，就变得很碍眼。
             *
             *   ★ 判据是 [sessionOpen]，**不是 `current != null`**。
             *     原来用后者是因为 current 一停就清空、两者恰好等价；
             *     自从 stopLocked 不再清 current（见那里的说明），
             *     切一次输出方式就会打一份全零报告出来 —— 又变回噪音了。
             */
            if (hadSession && !report.isNullOrBlank()) postLog(report.trim())
            runCatching { NativePlayer.nativeCloseFile(h) }
        }
    }

    private fun stopLocked(auto: Boolean) {
        closeCurrentLocked()
        /*
         * ★★ 这里**刻意不清 [current]，也不清 [durationMs]**。
         *
         *   原来有一句 `current = null`，它的语义是"用户主动按了停止"。
         *   但这条路同时被**切换输出方式**（DeviceGate.reopen）和
         *   **拔出解码器**（DeviceGate.onDetached）走着 —— 于是一拔小尾巴、
         *   或者直连↔系统音频切一下，**正在播放页和首页播放条就整页空了**。
         *
         *   队列其实一首没少：落盘用的是 queue.current()，跟这个字段无关
         *   （实测拔掉后 queueSize 仍是 1095，只有 title 变成了空串）。
         *   用户看到的却是"歌没了"，而且不知道该按哪里继续 ——
         *   按播放键也没有对象。
         *
         *   留着它，界面就是"**有曲目、已停止**"。这正是恢复队列后的那个
         *   状态，MiniBar 和正在播放页的判断条件本来就是按它写的
         *   （只看 current，不看 isActive）；togglePause() 里也早有
         *   "!isActive 但队列有当前项 → 接着放"那一条路。
         *
         *   ★ "下次启动恢复出来是暂停态"这件事**不受影响**：
         *     落盘存的是 queue.current()，不是这个字段。
         */
        durationMs = if (current != null) durationMs else 0L
        // ★ 停了也要落盘：队列本身还在（用户还能重新挑一首接着放），
        //   而"当前曲目"变成空了 —— 下次启动恢复出来是暂停态，不自动播
        persistQueue()
        setState(State.IDLE)
        releaseResources()
        if (auto) postLog("播放结束")
    }

    // ------------------------------------------------------------------
    //  前台服务 + wakelock
    //
    //  两件不同的事：前台服务防的是"进程被冻结"，wakelock 防的是"CPU 挂起"。
    //  我们绕开了 AudioFlinger，系统看不出这个 App 在放音，两样都得自己来。
    // ------------------------------------------------------------------

    private fun startResources() {
        val ctx = appContext ?: return
        runCatching { PlaybackService.start(ctx) }
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "hifiprobe:usb-playback"
            ).apply {
                setReferenceCounted(false)   // 重复 acquire/release 不会失衡
                // 6 小时上限纯粹是安全网：万一某条路径漏了释放，
                // 也不至于让电池被悄悄耗干
                acquire(6 * 60 * 60 * 1000L)
            }
        }.onFailure { postLog("wakelock 申请失败: ${it.message}") }
    }

    private fun releaseResources() {
        appContext?.let { runCatching { PlaybackService.stop(it) } }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** 供报告核对：CPU 会不会被挂起，取决于这个 */
    fun wakeLockState(): String =
        if (wakeLock?.isHeld == true) "wakelock=已持有" else "wakelock=未持有"
}
