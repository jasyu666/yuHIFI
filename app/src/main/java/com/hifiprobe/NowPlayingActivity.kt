package com.hifiprobe

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * 正在播放。
 *
 * 这一页的核心不是"大封面"，而是那块 **bit-perfect 徽章** ——
 * 它告诉用户这首歌到底有没有被动手脚：
 *
 * ```
 * 直通 · bit-perfect      源 44.1k/16bit → 输出 44.1k/16bit
 * 重采样 44.1k → 48k      源 44.1k/16bit → 输出 48k/24bit
 * ```
 *
 * 别的播放器给不出这个 —— 它们不知道设备实际跑在什么速率上。
 * 我们有 feedback 端点这个真判据，所以能给出**可信的**结论。
 */
class NowPlayingActivity : AppCompatActivity(), PlayerSession.Listener {

    private lateinit var ivBigCover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvBadge: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvDetail: TextView
    private lateinit var seek: SeekBar

    // ---- 音量（只在设置里开了「音量控制」时可见）----
    private lateinit var rowVolume: android.view.View
    private lateinit var seekVolume: SeekBar
    private lateinit var tvVolume: TextView

    /** 音量锁开关（两态图标）。★ 见 [renderVolume] */
    private lateinit var btnVolLock: android.widget.ImageButton

    /** ④ DSD 警告。★ 只在"音量控制开着 + 当前这首是 DSD"时可见 */
    private lateinit var tvVolWarn: TextView

    /*
     * ★ 这里原来有个 `volSteps = 200` 的常量，已删除 ——
     *   刻度的唯一来源是 [Settings.VOLUME_STEPS]，而且滑条的两头现在
     *   由用户在设置里定的上下限决定（见 [renderVolume]），
     *   在本页再存一份只会跟设置页对不上。
     */
    private lateinit var btnPlay: android.widget.ImageButton
    private lateinit var btnShuffle: Button
    private lateinit var btnRepeat: Button
    private lateinit var btnOrientation: Button

    // ---- 底部：源 / 歌词 ----
    private lateinit var tabSource: TextView
    private lateinit var tabLyrics: TextView
    private lateinit var tvLyricsEmpty: TextView
    private lateinit var rvLyrics: RecyclerView

    private val lyricsAdapter = LyricsAdapter()

    /** 读歌词要开文件 + 跑一遍 FFmpeg 探测，**不能占主线程** */
    private val lyricsIo = Executors.newSingleThreadExecutor { r -> Thread(r, "lyrics-io") }

    /** 底部现在显示的是不是歌词页。存 Settings，下次进来停在原处 */
    private var showLyrics = false

    /** 内存里这份歌词是哪一首的（uri）—— 用来判断"该换歌词了" */
    private var lyricsUri: String? = null
    private var lyricsDoc: Lyrics.Doc = Lyrics.NONE

    /** 上一次高亮的行号。-2 = 还没算过（换歌时重置） */
    private var lyricLine = -2

    /**
     * 用户正在自己翻歌词 —— 这期间**不自动滚动**。
     *
     * ★★ 光靠"行号变了就滚"会撞上这个场景：用户往上翻回去看副歌，
     *    几秒后下一句到了，界面**把他硬拽回当前行** —— 读一半被拽走，很恼人。
     *
     * ★ 高亮**照常更新**（用户仍然能看出唱到哪了），掐掉的只有"替他滚"这个动作。
     *   所以这个标志在 [syncLyricLine] 里只挡 smoothScrollToPosition。
     */
    private var userBrowsing = false

    /**
     * 用户**停手**之后多久把"自动跟随"还回去。
     *
     * ★ 不还的话，用户随手划一下，这一整首歌就不再跟了 —— 那也不对。
     *   所以"暂时让位"要有个尽头。
     *
     * ★ 取值是个手感问题：太短会在人还在读的时候把人拽走，太长又像"卡住不跟了"。
     *   5 秒是常见播放器的取值，觉得不合适改这一个数就行。
     */
    private val resumeFollowMs = 5000L

    private val ui = Handler(Looper.getMainLooper())

    /** 停手计时到点 —— 收回控制权，并把视线带回当前唱的那句 */
    private val resumeFollow = Runnable {
        if (!userBrowsing) return@Runnable
        resumeFollowNow()
    }

    private var dragging = false
    private var lastCoverUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        Ui.applySystemBars(findViewById(R.id.rootNowPlaying))

        ivBigCover = findViewById(R.id.ivBigCover)
        tvTitle = findViewById(R.id.tvNpTitle)
        tvArtist = findViewById(R.id.tvNpArtist)
        tvBadge = findViewById(R.id.tvBadge)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        tvDetail = findViewById(R.id.tvNpDetail)
        seek = findViewById(R.id.seekNp)
        btnPlay = findViewById(R.id.btnNpPlay)
        btnShuffle = findViewById(R.id.btnNpShuffle)
        btnRepeat = findViewById(R.id.btnNpRepeat)

        // ---- 底部：源 / 歌词 ----
        tabSource = findViewById(R.id.tabSource)
        tabLyrics = findViewById(R.id.tabLyrics)
        tvLyricsEmpty = findViewById(R.id.tvLyricsEmpty)
        rvLyrics = findViewById(R.id.rvLyrics)
        rvLyrics.layoutManager = LinearLayoutManager(this)
        rvLyrics.adapter = lyricsAdapter
        /*
         * ★ 关掉 item 动画。高亮是跟着 200ms 心跳换的，默认的渐变/位移动画
         *   会让整块歌词一直在"呼吸"，看着像卡顿。
         */
        rvLyrics.itemAnimator = null
        /*
         * ★★ 把"要不要自动跟随"的开关交给用户的手势，暂借之后再还回去：
         *
         *     用户一翻 → 让位（不拽他）；**停手一段时间** → 自动交还。
         *
         *   · `DRAGGING` 一出现就交出控制权。**不等松手** —— 拖到一半被拽回同样难受
         *   · 翻的过程中不断把计时往后推，"一段时间不继续翻"才算数
         *   · 停手（`IDLE`）时先看一眼：当前那句已经回到视野里了就别等了，立刻交还
         *   · 到点还没回来 → 交还，并把视线带回当前唱的那句
         *
         * ★ 为什么用 DRAGGING 而不是 onScrolled 来判定"用户在翻"：
         *   程序化的 smoothScrollToPosition 也会触发 onScrolled，
         *   用它做判据的话，**自动跟随会把自己误判成用户翻页然后停住**。
         *   DRAGGING 只有真人手指才会产生。
         */
        rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userBrowsing = true
                    scheduleResumeFollow()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && userBrowsing) {
                    if (currentLineVisible()) resumeFollowNow() else scheduleResumeFollow()
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 只有在"用户翻页"状态下才续期 —— 见上面那条说明
                if (userBrowsing) scheduleResumeFollow()
            }
        })
        showLyrics = Settings.showLyrics(this)

        /*
         * ★★ 横屏布局里右边那块**恒为歌词**，所以这里强制打开 showLyrics。
         *
         *   不这么做的话，竖屏下停在「源」的人一转横屏，右边会是一块空白
         *   （横屏布局里 tabSource/tabLyrics 是 GONE，他没地方切回去）。
         *
         *   ★ 这个强制**不写回 Settings** —— 它不是用户的偏好，
         *     只是这个布局下没有第二个页可切。转回竖屏时 onCreate 会重新
         *     从 Settings 读，用户原来选的那页原样回来。
         */
        if (isLandscape()) showLyrics = true

        btnOrientation = findViewById(R.id.btnOrientation)
        btnOrientation.setOnClickListener {
            /*
             * ★ 用**显式方向**，不用 SCREEN_ORIENTATION_UNSPECIFIED ——
             *   后者是"交还给系统"，会跟着系统的自动旋转开关走，
             *   同一次点击在不同人的手机上结果不一样，行为不可预期。
             *
             * ★ 横竖屏各有一份布局（layout/ 与 layout-land/），
             *   系统按限定符自动挑，所以这里只要设方向就行。
             */
            requestedOrientation = if (isLandscape()) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        tabSource.setOnClickListener { setTab(false) }
        tabLyrics.setOnClickListener { setTab(true) }
        applyTab()

        findViewById<android.widget.ImageButton>(R.id.btnNpPlay).setOnClickListener { PlayerSession.togglePause() }
        findViewById<android.widget.ImageButton>(R.id.btnNpNext).setOnClickListener { PlayerSession.next() }
        findViewById<android.widget.ImageButton>(R.id.btnNpPrev).setOnClickListener { PlayerSession.prevSmart() }
        findViewById<Button>(R.id.btnNpQueue).setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }

        /*
         * ★ 这两个都**不要在这里直接改 queue** —— 队列的增删改全在
         *   PlayerSession 的 worker 线程上跑，直接改就是数据竞争。
         *   交给 session 的方法：它们顺带把队列状态落盘。
         *   界面刷新由 postState() → onSessionStateChanged 带回来，不必手动 render()
         */
        btnShuffle.setOnClickListener { PlayerSession.toggleShuffle() }
        btnRepeat.setOnClickListener { PlayerSession.cycleRepeat() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    dragging = true
                    tvPos.text = fmt(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                PlayerSession.seekTo(sb.progress)
            }
        })

        /*
         * 音量滑条（默认隐藏，设置里开了「音量控制」才出现）。
         *
         * ★★ 和上面的进度条相反：**拖动中每一格都要立刻推下去**。
         *    进度条必须等松手（seek 要重解码），而音量只是解码线程里乘一个
         *    原子变量，下个块就读到了 —— 所以能做到完全实时，不用等松手。
         */
        rowVolume = findViewById(R.id.rowVolume)
        seekVolume = findViewById(R.id.seekVolumeNp)
        tvVolume = findViewById(R.id.tvVolumeNp)
        btnVolLock = findViewById(R.id.btnVolLock)
        tvVolWarn = findViewById(R.id.tvVolWarn)

        /*
         * 锁：点一下翻转。★ 它是**播放页唯一的开锁入口** ——
         * 锁上之后滑条拖不动，用户要是只能回设置页解锁，这个锁就成了陷阱。
         */
        btnVolLock.setOnClickListener {
            Settings.setVolumeLocked(this, !Settings.volumeLocked(this))
            renderVolume()
        }

        // 范围和位置由 renderVolume() 按存储统一设，这里不写死
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val db = volDbOf(progress)
                Settings.setVolumeDb(this@NowPlayingActivity, db)
                PlayerSession.refreshSoftwareVolume()
                tvVolume.text = fmtVol(db)
                // 徽章要跟着变（0dB 和 −12dB 的口径不同）
                PlayerSession.current?.let { renderBadge(it) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // 刻度和换算都在 Settings 里（设置页那个滑条共用同一份，别在这儿另写一套）
    private fun volDbOf(step: Int) = Settings.volumeDbOfStep(step)
    private fun volStepOf(db: Float) = Settings.volumeStepOfDb(db)
    private fun fmtVol(db: Float) = Settings.fmtVolumeDb(db)

    private fun renderVolume() {
        val on = Settings.volumeControl(this)
        val locked = Settings.volumeLocked(this)
        rowVolume.visibility = if (on) View.VISIBLE else View.GONE

        /*
         * ④ DSD 警告：只在**音量控制开着**、而且**引擎里确实开着文件**时出现。
         * ★ 判据是 handle != 0（文件已打开），**暂停也算** —— 文件还是 DSD，
         *   一按播放照样滑条不管用，这时候才最该看得见这句话。
         * ★ 关掉音量控制时它毫无意义（没有任何东西会失效），所以一起藏。
         * ★ 用 currentIsDsd() 现取 —— Track 上没有这个字段。
         */
        tvVolWarn.visibility =
            if (on && PlayerSession.handle != 0L && PlayerSession.currentIsDsd())
                View.VISIBLE else View.GONE

        if (!on) return

        val db = Settings.volumeDb(this)

        /*
         * ② 音量锁：滑条拖不动，图标两态可辨。
         *
         * ★★ 图标**必须换图**（闭合的锁 / 开的锁），不能只改颜色 ——
         *    只变灰的话看不出"是锁着还是坏了"。
         * ★ 顺手把字也写上：这一行本来就窄，图标是主要信号，"已锁定"是补充。
         */
        seekVolume.isEnabled = !locked
        btnVolLock.setImageResource(
            if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        btnVolLock.imageTintList = android.content.res.ColorStateList.valueOf(
            Ui.c(this, if (locked) R.color.warn else R.color.text_secondary))
        btnVolLock.contentDescription =
            getString(if (locked) R.string.volume_locked_prefix else R.string.volume_lock)
        /*
         * ★★ 两头跟着设置页定的上限/下限走。
         *   不跟的话，用户在设置里把上限压到 −20dB，播放页这根还能拖到 0dB ——
         *   那道闸就形同虚设，而这一页恰恰是"手一滑"最容易发生的地方。
         *
         * ★ 顺序必须是 min/max 先、progress 后：progress 会被**当前**范围夹一次，
         *   反过来的话先按旧范围夹了，位置就不是当前音量了。
         */
        seekVolume.min = volStepOf(Settings.volumeFloorDb(this))
        seekVolume.max = volStepOf(Settings.volumeCeilingDb(this))
        val step = volStepOf(db)
        if (seekVolume.progress != step) seekVolume.progress = step
        tvVolume.text =
            if (locked) getString(R.string.volume_locked_prefix) + " · " + fmtVol(db)
            else fmtVol(db)
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.addListener(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        PlayerSession.removeListener(this)
    }

    override fun onSessionLog(text: String) = Unit   // 这一页不铺日志

    override fun onSessionStateChanged() = render()

    override fun onDestroy() {
        super.onDestroy()
        // 单线程池不关就是一条泄漏的线程 —— 这一页会来回进很多次
        lyricsIo.shutdown()
        // 挂起的延时任务同理：不清掉的话 Activity 销毁后它还会回来碰已经没了的 View
        ui.removeCallbacks(resumeFollow)
    }

    // ------------------------------------------------------------------

    private fun render() {
        /*
         * ★★ 判断条件必须和 MiniBar.render() **一字不差**。
         *
         *   播放条在首页常驻之后，用户是"看着条上的字决定要不要点进来"的。
         *   两边条件不一样，就会出现"条上说没有歌、点进去却有一首"（或反过来）
         *   —— 这种自相矛盾比单纯的 bug 更难解释。
         */
        // ★ 条件只看 current，不带 isActive —— 恢复出来的队列"有曲目但没在放"，
        //   带上 isActive 会让它显示成空态。理由和 MiniBar.render() 里那段一样。
        val t = PlayerSession.current
        if (t == null) {
            renderEmpty()
            return
        }

        tvTitle.text = t.displayTitle
        tvArtist.text = buildString {
            append(t.displayArtist)
            if (t.album != null) append("  ·  ").append(t.album)
        }

        /*
         * 封面只在"换曲**或换了封面样式**"时重取，别每 200ms 来一次。
         *
         * ★ 判断里带上样式的指纹：只比 uri 的话，用户在设置里换了
         *   「默认封面样式」再回来，同一首歌不会重画 —— 大封面还是旧样式，
         *   看起来就是"设置没生效"。缓存那边也按同样的键存，正好对上。
         */
        val coverKey = t.uri + "#" + Settings.currentVinyl(this).hashCode()
        if (lastCoverUri != coverKey) {
            lastCoverUri = coverKey
            ivBigCover.background = null          // 去掉空态留下的那个占位圆
            ivBigCover.setImageBitmap(bigCover(t))
        }

        /*
         * ★ 时长优先用会话里的（引擎实测的，准）；没有就回落到曲目自己的。
         *
         *   恢复出来的队列**还没加载文件**，会话里是 0 ——
         *   但库里的元数据本来就存着时长（扫描时探测的），
         *   直接用，别显示 --:--，那看着像文件坏了。
         */
        val dur = if (PlayerSession.durationMs > 0) PlayerSession.durationMs else t.durationMs
        tvDur.text = if (dur > 0) fmt(dur) else "--:--"

        val pos = PlayerSession.currentPositionMs()
        if (!dragging) {
            seek.max = dur.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            seek.progress = pos.toInt()
            tvPos.text = fmt(pos)
        }

        // ★ 按"在放吗"分，不是按"暂停了吗" —— 否则恢复出来的队列（IDLE）
        //   会显示暂停图标，按下去却是开始放。和 MiniBar 保持同一套判断。
        btnPlay.setImageResource(
            if (PlayerSession.state == PlayerSession.State.PLAYING) R.drawable.ic_pause
            else R.drawable.ic_play
        )
        btnShuffle.text = if (PlayerSession.queue.shuffle) "随机：开" else "随机"
        btnRepeat.text = when (PlayerSession.queue.repeatMode) {
            RepeatMode.OFF -> "循环：关"
            RepeatMode.ALL -> "循环：全部"
            RepeatMode.ONE -> "循环：单曲"
        }

        setControlsEnabled(true)
        renderVolume()
        renderBadge(t)
        // 歌词页开着时不必拼那串规格文本 —— 它每 200ms 跑一次，白算
        if (!showLyrics) renderDetail(t)
        syncLyrics(t)
    }

    /**
     * 没有歌曲在播放时的样子。
     *
     * ★★ 这里原来是"设几行文字就 return" —— **封面、进度条、按钮全留在
     *    上一首的状态**。表现是：放完歌停下来，再进这一页，还挂着那张封面、
     *    进度条停在一半、按钮亮着 —— 看着像卡住了，而不像"没在放"。
     *
     *    空态必须**每一项都清到位**，否则"空"和"坏"分不出来。
     */
    private fun renderEmpty() {
        /*
         * ★ 必须清掉封面指纹。
         *
         *   不清的话，停止后再播**同一首**，coverKey 会和上次相等 →
         *   跳过重画 → 空态那个占位圆就一直留在那儿，而标题已经有歌名了。
         */
        lastCoverUri = null
        tvTitle.text = getString(R.string.mini_none)
        tvArtist.text = ""
        tvDetail.text = ""
        tvBadge.visibility = View.GONE
        ivBigCover.setImageDrawable(null)
        ivBigCover.background = getDrawable(R.drawable.bg_cover_empty)
        seek.max = 0
        seek.progress = 0
        tvPos.text = fmt(0)
        tvDur.text = "--:--"
        btnPlay.setImageResource(R.drawable.ic_play)
        setControlsEnabled(false)
    }

    /** 没歌可放时把这些控件置灰 —— "能点但没反应"比置灰更让人困惑 */
    private fun setControlsEnabled(on: Boolean) {
        seek.isEnabled = on
        btnPlay.isEnabled = on
        btnShuffle.isEnabled = on
        btnRepeat.isEnabled = on
    }

    // ------------------------------------------------------------------
    //  底部：源 / 歌词
    // ------------------------------------------------------------------

    /** 当前是不是横屏。横竖屏各有一份布局，行为差异都从这里判 */
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun setTab(lyrics: Boolean) {
        if (showLyrics == lyrics) return
        showLyrics = lyrics
        Settings.setShowLyrics(this, lyrics)
        applyTab()
        // 刚切到歌词页时可能一次都没读过 —— 立刻补一次
        PlayerSession.current?.let { syncLyrics(it) }
    }

    /** 按 [showLyrics] 摆好两个页的显隐和标签配色。换歌、切页、读完歌词都要调 */
    private fun applyTab() {
        tabSource.setTextColor(Ui.c(this, if (showLyrics) R.color.text_tertiary else R.color.brand))
        tabLyrics.setTextColor(Ui.c(this, if (showLyrics) R.color.brand else R.color.text_tertiary))

        // ★ 有歌词才显示列表；没有就显式说一句"这首没有内嵌歌词"，
        //   而不是给一块空白 —— 空白看着像加载失败
        val has = !lyricsDoc.isEmpty
        tvDetail.visibility = if (showLyrics) View.GONE else View.VISIBLE
        rvLyrics.visibility = if (showLyrics && has) View.VISIBLE else View.GONE
        tvLyricsEmpty.visibility = if (showLyrics && !has) View.VISIBLE else View.GONE
    }

    /**
     * 换歌就重读歌词；没换就只对一下高亮。
     *
     * ★★ **读盘和 FFmpeg 探测必须离开主线程** —— 这个函数是从 200ms 心跳
     *    那条路进来的（`onSessionStateChanged` → `render`）。
     */
    private fun syncLyrics(t: Track) {
        if (!showLyrics) return

        if (lyricsUri != t.uri) {
            lyricsUri = t.uri
            lyricsDoc = Lyrics.NONE
            lyricLine = -2
            // ★ 换歌了 —— 之前那首翻到哪儿都作废，新歌要正常跟随
            userBrowsing = false
            ui.removeCallbacks(resumeFollow)
            lyricsAdapter.submit(Lyrics.NONE, -1)
            applyTab()
            lyricsIo.execute {
                val doc = Lyrics.load(t)              // ★ 后台
                runOnUiThread {
                    // 读的过程中可能又换歌了 —— 丢弃过期结果，别盖掉新的那次
                    if (lyricsUri != t.uri) return@runOnUiThread
                    lyricsDoc = doc
                    lyricLine = -2
                    lyricsAdapter.submit(doc, -1)
                    applyTab()
                    syncLyricLine()
                }
            }
            return
        }
        syncLyricLine()
    }

    /**
     * 把高亮挪到当前该唱的那一行。
     *
     * ★★ **只在行号真的变了才动** —— 它跟着 200ms 心跳走，
     *    每次都滚一下的话列表会一直抖，用户手动往上翻也会被硬拽回去。
     */
    private fun syncLyricLine() {
        if (!showLyrics || !lyricsDoc.timed) return
        val idx = Lyrics.indexAt(lyricsDoc, PlayerSession.currentPositionMs())
        if (idx == lyricLine) return
        lyricLine = idx
        // ★ 高亮**照常**更新 —— 用户在翻的时候也需要知道唱到哪了。
        //   被掐掉的只有"替他滚"这个动作。
        lyricsAdapter.highlight(idx)
        if (idx >= 0 && !userBrowsing) rvLyrics.smoothScrollToPosition(idx)
    }

    /** 把"交还跟随"的计时往后推 [resumeFollowMs] —— 每次翻动都调，所以是"停手"而不是"翻过"才开始算 */
    private fun scheduleResumeFollow() {
        ui.removeCallbacks(resumeFollow)
        ui.postDelayed(resumeFollow, resumeFollowMs)
    }

    /**
     * 立刻把控制权交还给自动跟随，并把视线带回当前唱的那句。
     *
     * ★ **"交还"要看得见才算数**：光清掉标志的话，用户还停在别的地方，
     *   得等下一句才会被带走 —— 那中间这段时间界面是"不跟"的样子，看着像坏了。
     *   直接滚回去更干脆。
     */
    private fun resumeFollowNow() {
        ui.removeCallbacks(resumeFollow)
        if (!userBrowsing) return
        userBrowsing = false
        if (lyricLine >= 0) rvLyrics.smoothScrollToPosition(lyricLine)
    }

    /** 当前唱的那句在不在歌词列表的可见范围里 */
    private fun currentLineVisible(): Boolean {
        if (lyricLine < 0) return false
        val lm = rvLyrics.layoutManager as? LinearLayoutManager ?: return false
        return lyricLine in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()
    }

    /**
     * 歌词列表。
     *
     * ★ 整体提交用 `notifyDataSetChanged` —— 一首歌词几百行、而且只在换歌时
     *   提交一次，为它上 DiffUtil 不值得。**高亮走 `notifyItemChanged`**，
     *   一次两行（旧的、新的）。
     */
    private inner class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {

        private var lines: List<Lyrics.Line> = emptyList()
        private var current = -1

        fun submit(doc: Lyrics.Doc, highlight: Int) {
            lines = doc.lines
            current = highlight
            notifyDataSetChanged()
        }

        fun highlight(idx: Int) {
            val old = current
            current = idx
            if (old in lines.indices) notifyItemChanged(old)
            if (idx in lines.indices) notifyItemChanged(idx)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_lyric, parent, false))

        override fun getItemCount() = lines.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val on = position == current
            holder.tv.text = lines[position].text
            holder.tv.setTextColor(Ui.c(this@NowPlayingActivity,
                if (on) R.color.brand else R.color.text_secondary))
            /*
             * ★ 高亮**只改颜色和透明度，不改字号** —— 字号一变行高就变，
             *   列表会整片重排，而这是 200ms 一次的高频路径。
             */
            holder.tv.alpha = if (on) 1f else 0.75f
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvLyricLine)
        }
    }

    /**
     * 徽章 —— 直通还是重采样，以及源→输出的实际规格。
     *
     * 数据源是**引擎自己报的**（nativeEngineInfo），不是我们推测的：
     * 引擎按源文件决定输出速率，所以这个结论和真正送给 DAC 的东西一致。
     */
    private fun renderBadge(t: Track) {
        /*
         * ★ 没在放就不要读引擎。
         *
         *   恢复出来的队列就是这种情况：曲目有了，但一个文件都没加载，
         *   引擎里是**上一次会话的残留值** —— 照着报出来是错的
         *   （会显示上一首的速率和位深）。
         */
        if (!PlayerSession.isActive) {
            tvBadge.visibility = View.GONE
            return
        }
        val h = PlayerSession.handle
        if (h == 0L) {
            tvBadge.visibility = View.GONE
            return
        }
        val info = NativePlayer.nativeEngineInfo(h)
        val srcRate = info[NativePlayer.EngineInfo.SOURCE_RATE]
        val srcBits = info[NativePlayer.EngineInfo.SOURCE_BITS]
        val outRate = info[NativePlayer.EngineInfo.OUTPUT_RATE]
        val outBits = info[NativePlayer.EngineInfo.BIT_RESOLUTION]
        val resampling = info[NativePlayer.EngineInfo.RESAMPLING] == 1L

        val systemAudio = info[NativePlayer.EngineInfo.IS_SYSTEM_AUDIO] == 1L

        /*
         * ★★ 徽章要如实反映"是不是 bit-perfect"。
         *
         *   两条并列的输出通路（系统音频 / 重采样）本来就不是，各自有专门的文案；
         *   而**交叉馈送**和**音量控制**是我们主动加的处理，一旦开启，
         *   送进 DAC 的就不再是文件里的原始样本了 —— 「直通 · bit-perfect」
         *   那句话会变成假话，所以必须去掉、颜色从绿降成中性。
         *
         *   ★ 用「后缀列表」而不是逐个 if：这两项可以同时开，
         *     而且以后再加别的处理项时只要往 extra 里塞一个就行。
         */
        val extra = listOfNotNull(
            PlayerSession.crossfeedLabel(),
            volumeBadgeLabel(),
        )

        tvBadge.visibility = View.VISIBLE
        when {
            /*
             * ★★ 系统音频**优先判**，绝不能落到下面那个"直通 · bit-perfect"。
             *
             *   这条路经过 AudioFlinger 混音，走蓝牙时还有一层**真有损的编码**。
             *   哪怕引擎报的是"没重采样"，那也只说明**我们**没重采样 ——
             *   后面的路照样不是无损的。把这种情况标成 bit-perfect 是撒谎。
             */
            systemAudio -> {
                tvBadge.text = withExtras(getString(R.string.state_system_audio), extra)
                Ui.neutralBadge(tvBadge)
            }
            resampling -> {
                tvBadge.text = withExtras("重采样 ${k(srcRate)} → ${k(outRate)}", extra)
                Ui.warnBadge(tvBadge)
            }
            extra.isEmpty() -> {
                tvBadge.text = getString(R.string.state_passthrough)
                Ui.okBadge(tvBadge)
            }
            else -> {
                // 有额外处理 → 不再是 bit-perfect，绿徽章得让位
                tvBadge.text = withExtras("直通", extra)
                Ui.neutralBadge(tvBadge)
            }
        }
    }

    /** 把「交叉馈送 · 中」「音量 −12 dB」这类后缀接在主文案后面 */
    private fun withExtras(base: String, extra: List<String>): String =
        extra.fold(base) { acc, s -> "$acc · $s" }

    /**
     * 徽章上那截音量。
     *
     * ★ 没开音量控制时返回 null —— 那时一个样本都没碰，不该在徽章上吓人。
     * ★ **0dB 也要显示**：虽然没衰减，但输出位深已经被提到 24bit，
     *   严格说已经不是"原样搬运"了，标出来才对得起这一栏的意义。
     */
    private fun volumeBadgeLabel(): String? {
        if (!Settings.volumeControl(this)) return null
        val db = Settings.volumeDb(this)
        return if (db >= -0.05f) "音量控制" else "音量 %.0f dB".format(db)
    }

    private fun renderDetail(t: Track) {
        val h = PlayerSession.handle
        val q = PlayerSession.queue
        tvDetail.text = buildString {
            append("源        ").append(k(t.sampleRate)).append(" / ").append(t.bitDepth).append("bit")
            t.codec.takeIf { it.isNotEmpty() }?.let { append("   ").append(it) }
            append("\n")

            /*
             * ★ 判据里带上 isActive，理由同 renderBadge：
             *   恢复出来的队列 handle 非 0（系统音频模式下引擎上下文一直都在），
             *   但里面一个文件都没加载 —— 引擎报的是**上一次会话的残留值**，
             *   照着显示会让人以为"输出是 352.8k/32bit"。
             *   没有就只显示「源」，那是从曲目元数据来的，是真的。
             */
            if (h != 0L && PlayerSession.isActive) {
                val info = NativePlayer.nativeEngineInfo(h)
                append("输出      ")
                    .append(k(info[NativePlayer.EngineInfo.OUTPUT_RATE].toInt()))
                    .append(" / ")
                    .append(info[NativePlayer.EngineInfo.BIT_RESOLUTION]).append("bit")
                append("   ").append(info[NativePlayer.EngineInfo.CHANNELS]).append("ch\n")

                val underrun = info[NativePlayer.EngineInfo.UNDERRUN_BYTES]
                val buffered = info[NativePlayer.EngineInfo.BUFFERED_BYTES]
                val target = info[NativePlayer.EngineInfo.TARGET_BYTES]
                val pct = if (target > 0) buffered * 100 / target else 0L
                append("缓冲      ").append(pct).append("%")
                if (pct < 25) append("  ⚠ 偏低")
                append("   欠载 ").append(underrun).append(" 字节\n")
            }

            if (q.size > 0) {
                append("队列      ").append(q.currentIndex + 1).append(" / ").append(q.size)
                if (q.shuffle) append("   随机播放")
                append("\n")
            }
        }.trimEnd()
    }

    /** 队列一览，点一首直接跳过去 */
    private fun showQueue() {
        val q = PlayerSession.queue
        if (q.size == 0) return
        val labels = q.tracks().mapIndexed { i, t ->
            val mark = if (i == q.currentIndex) "▶ " else "    "
            "$mark${t.displayTitle}  ·  ${t.specLabelOrDefault}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("播放队列（${q.size}）")
            .setItems(labels) { _, which -> PlayerSession.playIndex(which) }
            .show()
    }

    /**
     * 大封面。
     *
     * 走和列表缩略图同一个 [CoverLoader] —— 种子公式和缓存都在那儿一份，
     * 两处各写一遍的话，同一首歌会出现"列表里一张纹样、点进来另一张"。
     */
    private fun bigCover(t: Track): Bitmap =
        CoverLoader.get(this, t, (280 * resources.displayMetrics.density).toInt())

    private fun k(rate: Long): String = k(rate.toInt())

    private fun k(rate: Int): String = when {
        rate <= 0 -> "?"
        rate % 1000 == 0 -> "${rate / 1000}k"
        else -> String.format("%.1fk", rate / 1000.0)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
