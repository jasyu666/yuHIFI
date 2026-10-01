package com.hifiprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

/**
 * 播放服务。
 *
 * ★ 它有三份职责，缺一不可：
 *
 *   **一、防冻结声明。** 我们把整条音频栈绕开了 —— 数据从 FFmpeg 直接流向
 *   libusb，不经过 AudioTrack / AudioFlinger。系统因此**完全看不出这个 App
 *   在放音**，切屏之后会把它当缓存进程，Android 12+ 的 App Freezer 会直接
 *   冻结整个进程的所有线程。实测冻结一次就让解码线程停摆 1 秒以上。
 *
 *   **二、驱动播放推进。** "播完自动下一首"的判断放在这里，由本服务的心跳
 *   线程每 200ms 调一次 [PlayerSession.tick]。**这是它必须在服务里、
 *   而不能在界面里的原因** —— 界面一停（切后台、锁屏、被回收），
 *   自动连播就断了，而那正是整个功能的意义。
 *
 *   **三、媒体会话。** 通知栏和锁屏那套播放器 UI **完全由 [MediaSession] 驱动**，
 *   普通通知进不了那个区域。具体两个后果，都是实测踩到的：
 *
 *     · 用裸 Notification.Builder 时，动作按钮被折叠，要**长按**才展开 → 必须用
 *       [Notification.MediaStyle]，它会把前 3 个动作直接摊开在收起态
 *     · 锁屏上**根本不显示**媒体控件 —— 那需要系统认出"这是个播放器"，
 *       唯一途径就是 MediaSession + `setActive(true)`
 *
 *   顺带白拿一个好处：耳机线控和蓝牙的 播放/暂停/上一首/下一首 也走
 *   MediaSession 的回调，不必自己处理 KeyEvent。
 */
class PlaybackService : Service(), PlayerSession.Listener {

    private lateinit var beatThread: HandlerThread
    private lateinit var beat: Handler
    private lateinit var mediaSession: MediaSession

    /** 心跳间隔。切歌的检测延迟上限就是它 —— 200ms 听感上察觉不到。 */
    private val tickIntervalMs = 200L

    /** 心跳是否已经在跑，防止重复排程 */
    private var beatRunning = false

    private val ticker = object : Runnable {
        override fun run() {
            runCatching { PlayerSession.tick() }
                .onFailure { Log.w(TAG, "tick 异常: ${it.message}") }
            if (PlayerSession.isActive) {
                beat.postDelayed(this, tickIntervalMs)
            } else {
                beatRunning = false
            }
        }
    }

    /**
     * 按需启停心跳。
     *
     * ★ 这里必须判重，不能每次都 removeCallbacks + post。
     *   心跳自己会在末尾续期，而状态回调（每 tick 一次）如果再来一次
     *   "取消 + 立即投递"，就会变成 tick → 回调 → 立刻 tick → ……
     *  的**忙循环**，白白把 CPU 跑满。
     */
    private fun ensureBeat() {
        val want = PlayerSession.isActive
        if (want && !beatRunning) {
            beatRunning = true
            beat.removeCallbacks(ticker)
            beat.postDelayed(ticker, tickIntervalMs)
        } else if (!want && beatRunning) {
            beatRunning = false
            beat.removeCallbacks(ticker)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    //  媒体会话
    // ------------------------------------------------------------------

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "hifiprobe").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = PlayerSession.togglePause()
                override fun onPause() = PlayerSession.togglePause()
                override fun onSkipToNext() = PlayerSession.next()
                override fun onSkipToPrevious() = PlayerSession.prevSmart()
                override fun onStop() = PlayerSession.stop()
                override fun onSeekTo(pos: Long) = PlayerSession.seekTo(pos.toInt())
            })
            // Android 14+ 要求会话带一个"点通知回到哪"的 Intent，缺了会告警
            setSessionActivity(openAppIntent())
            setActive(false)
        }
    }

    /** 通知栏 / 锁屏上显示的元数据 */
    private fun pushMetadata() {
        val t = PlayerSession.current ?: return
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.displayTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.displayArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album ?: t.displayArtist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, PlayerSession.durationMs)
            .build()
        mediaSession.setMetadata(md)
    }

    /** 上一次推给系统的播放状态摘要，用来避免每 200ms 都重推一遍 */
    private var lastPushedState: String? = null

    /**
     * 推播放状态。
     *
     * 锁屏上的进度条靠这个走 —— 位置变了就得重推，否则那条进度是死的。
     * 但 200ms 推一次太频繁，按「秒」去重就够了。
     */
    private fun pushPlaybackState(force: Boolean = false) {
        val s = PlayerSession.state
        val playing = s == PlayerSession.State.PLAYING
        val posMs = PlayerSession.currentPositionMs()

        val key = "$s|${posMs / 1000}|${PlayerSession.current?.uri}"
        if (!force && key == lastPushedState) return
        lastPushedState = key

        val state = when (s) {
            PlayerSession.State.PLAYING -> PlaybackState.STATE_PLAYING
            PlayerSession.State.PAUSED -> PlaybackState.STATE_PAUSED
            PlayerSession.State.OPENING -> PlaybackState.STATE_BUFFERING
            PlayerSession.State.IDLE -> PlaybackState.STATE_STOPPED
        }

        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP

        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, posMs, if (playing) 1f else 0f)
                .build()
        )
        mediaSession.isActive = PlayerSession.isActive || s == PlayerSession.State.OPENING
    }

    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        PlayerSession.init(applicationContext)
        PlayerSession.addListener(this)

        beatThread = HandlerThread("playback-beat").apply { start() }
        beat = Handler(beatThread.looper)

        createMediaSession()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "播放状态", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "独占 USB 输出进行中" }
        )
    }

    override fun onDestroy() {
        /*
         * ★ 进程要走之前，把最后一段收听时长落盘。
         *
         *   ⚠ onDestroy **不保证一定被调用**（被系统直接杀、被划掉任务卡片的
         *     某些路径就没有）。所以这一刀只是"能补则补" ——
         *     真正的保险是 [PlayerSession] 里**每曲一次**的检查点。
         */
        PlayerSession.flushListened()
        PlayerSession.flushQueue()
        beat.removeCallbacksAndMessages(null)
        beatThread.quitSafely()
        PlayerSession.removeListener(this)
        runCatching { mediaSession.isActive = false }
        runCatching { mediaSession.release() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏按钮走这里，而不是 Binder —— 服务只在播放期间存在，
        // 为它维护一套绑定生命周期不划算
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> PlayerSession.togglePause()
            ACTION_NEXT -> PlayerSession.next()
            ACTION_PREV -> PlayerSession.prevSmart()
            ACTION_STOP -> PlayerSession.stop()
        }

        val failure = startForegroundCompat()
        if (failure != null) {
            /*
             * 启动失败时必须主动收掉自己。
             *
             * 不能只记个日志就返回 —— 通过 startForegroundService() 启动的服务，
             * 若没能在约 5 秒内调成 startForeground()，系统会抛
             * ForegroundServiceDidNotStartInTimeException 把**整个 App** 干掉。
             * 两害相权取其轻：丢掉的是防冻结保护，而不是 App。
             */
            Log.w(TAG, "前台服务启动失败，主动停止：$failure")
            reportResult(false, failure)
            stopSelf()
            return START_NOT_STICKY
        }
        // 成功只报一次：切歌会重新走 onStartCommand，每次都报会在报告里刷屏
        if (!reportedOk) {
            reportedOk = true
            reportResult(true, "已进入前台状态，进程不会被冻结")
        }

        ensureBeat()
        updateNotification(force = true)
        return START_STICKY
    }

    private var reportedOk = false

    // ---- PlayerSession.Listener ----

    override fun onSessionLog(text: String) {
        Log.i(TAG, text)
    }

    override fun onSessionStateChanged() {
        pushMetadata()
        pushPlaybackState()
        updateNotification()
        ensureBeat()
    }

    // ------------------------------------------------------------------

    /** 返回 null 表示成功，否则是失败原因 */
    private fun startForegroundCompat(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        null
    } catch (e: Exception) {
        // 常见原因：
        //   ForegroundServiceStartNotAllowedException（API 31+，从后台启动前台服务被拒）
        //   SecurityException（清单里缺 FOREGROUND_SERVICE_MEDIA_PLAYBACK）
        // 这里刻意捕获所有异常：此处的失败代价是整个 App 崩掉，远重于掩盖问题。
        "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun buildNotification(): Notification {
        val track = PlayerSession.current
        val playing = PlayerSession.state == PlayerSession.State.PLAYING

        val title = track?.displayTitle ?: "yuHIFI"
        val text = buildString {
            if (track != null) {
                append(track.displayArtist)
                track.specLabel?.let { append("  ·  ").append(it) }
                if (PlayerSession.state == PlayerSession.State.PAUSED) append("  ·  已暂停")
            } else {
                append("正在独占 USB 输出，请勿切换到其他播放器")
            }
        }

        val actions = listOf(
            action(android.R.drawable.ic_media_previous, "上一首", ACTION_PREV),
            if (playing) action(android.R.drawable.ic_media_pause, "暂停", ACTION_PLAY_PAUSE)
            else action(android.R.drawable.ic_media_play, "播放", ACTION_PLAY_PAUSE),
            action(android.R.drawable.ic_media_next, "下一首", ACTION_NEXT),
            action(android.R.drawable.ic_menu_close_clear_cancel, "停止", ACTION_STOP),
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            /*
             * ★ VISIBILITY_PUBLIC 才能上锁屏。默认是 PRIVATE，
             *   在"隐藏敏感通知内容"开启时锁屏上根本看不到。
             */
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                /*
                 * ★ MediaStyle 是把动作按钮摊开的关键。
                 *   裸 Builder 生成的通知会把动作折叠起来，必须**长按**才展开 ——
                 *   用户实测反馈的就是这个。
                 *   setShowActionsInCompactView 指定收起态直接显示哪几个（最多 3 个）。
                 */
                setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                actions.forEach { addAction(it) }
            }
            .build()
    }

    private fun action(icon: Int, title: String, act: String): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            title,
            PendingIntent.getService(
                this, act.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        /*
         * ★★ 正式版**不能跳诊断页** —— 那是内部版的调试入口，
         *    普通用户点一下通知落到一个满是 URB、描述符的页面上，只会莫名其妙。
         *    正式版跳首页（那里有正在播放的迷你条）。
         *
         *    ★ 内部版保持原样：开发时从通知直接进诊断页挺方便。
         */
        Intent(
            this,
            if (diagnosticsEnabled(this)) MainActivity::class.java
            else HomeActivity::class.java
        ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 只在真的换了曲目/状态时才重建通知，避免每 200ms 刷一次 */
    private var lastNotified: String? = null

    private fun updateNotification(force: Boolean = false) {
        val key = "${PlayerSession.current?.uri}|${PlayerSession.state}"
        if (!force && key == lastNotified) return
        lastNotified = key
        pushMetadata()
        pushPlaybackState(force = true)
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /** 把结果回传给 Activity，好写进导出的诊断报告里 —— 防冻结是否生效不该靠猜 */
    private fun reportResult(ok: Boolean, detail: String) {
        sendBroadcast(
            Intent(ACTION_FGS_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_FGS_OK, ok)
                .putExtra(EXTRA_FGS_DETAIL, detail)
        )
    }

    companion object {
        private const val CHANNEL_ID = "hifiprobe_playback"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "HiFiProbeFGS"

        /* 启动结果回传给 Activity（仅本应用内，setPackage 限定） */
        const val ACTION_FGS_STATUS = "com.hifiprobe.FGS_STATUS"
        const val EXTRA_FGS_OK = "ok"
        const val EXTRA_FGS_DETAIL = "detail"

        /* 通知栏动作 */
        private const val ACTION_PLAY_PAUSE = "com.hifiprobe.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.hifiprobe.NEXT"
        private const val ACTION_PREV = "com.hifiprobe.PREV"
        private const val ACTION_STOP = "com.hifiprobe.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }
}
