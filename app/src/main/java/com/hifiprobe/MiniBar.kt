package com.hifiprobe

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 底部迷你播放条的行为。
 *
 * 布局在 `view_mini_bar.xml`，这里只负责"把当前状态画上去"和"按钮接哪去"。
 * 抽出来的理由和布局一样：门户页、曲目列表页、音乐库三处都要，逻辑抄三遍
 * 迟早会有一处忘了更新按钮图标。
 *
 * 用法：Activity 的 onCreate 里 `miniBar = MiniBar(this)`，
 * 然后在 onSessionStateChanged / onResume 里 `miniBar.render()`。
 */
class MiniBar(
    private val activity: AppCompatActivity,
    /**
     * 没歌的时候也常驻（显示空态）。
     *
     * ★ 只**首页**传 true。首页是门户，常驻的播放条是通用做法，
     *   也是进「正在播放」的入口；其他四页（音乐库 / 所有歌曲 / 专辑 / 歌单）
     *   把这点高度留给列表 —— 那里信息密度高，一条空的播放条纯属浪费。
     */
    private val alwaysVisible: Boolean = false
) {

    private val root: View = activity.findViewById(R.id.miniBar)
    private val cover: ImageView = activity.findViewById(R.id.ivMiniCover)
    private val title: TextView = activity.findViewById(R.id.tvMiniTitle)
    private val spec: TextView = activity.findViewById(R.id.tvMiniSpec)
    private val btnPlay: ImageButton = activity.findViewById(R.id.btnMiniPlay)
    private val btnNext: ImageButton = activity.findViewById(R.id.btnMiniNext)
    private val btnPrev: ImageButton = activity.findViewById(R.id.btnMiniPrev)

    /** 多选时让位。底部同时摆两条长得差不多的白条，用户分不清谁是谁 */
    private var suppressed = false

    init {
        btnPlay.setOnClickListener { PlayerSession.togglePause() }
        btnNext.setOnClickListener { PlayerSession.next() }
        btnPrev.setOnClickListener { PlayerSession.prevSmart() }
        // 整条都可以点，进正在播放页 —— 按钮之外的地方也要能点，
        // 只让 42dp 的封面可点会让人以为这条是死的
        root.setOnClickListener {
            activity.startActivity(Intent(activity, NowPlayingActivity::class.java))
        }
    }

    /** 进/出多选模式时调。让位是暂时的，退出后会自己回来 */
    fun setSuppressed(on: Boolean) {
        if (suppressed == on) return
        suppressed = on
        render()
    }

    fun render() {
        /*
         * ★★ 这个判断必须和 NowPlayingActivity.render() **一字不差**。
         *
         *   播放条常驻之后，用户是"看着条上的字决定要不要点进去"的。
         *   两处各写一套条件，迟早会出现"条上说没有歌、点进去却有一首"
         *   （或者反过来）—— 那种自相矛盾比单纯的 bug 更难解释。
         *
         * ★★ 条件只看 `current`，**不带 isActive**。
         *
         *   从磁盘恢复出来的队列是"有当前曲目、但没在放"（state 还是 IDLE）。
         *   带上 isActive 的话，用户千辛万苦恢复回来的队列，播放条却写
         *   「没有歌曲在播放」，点进去也一样 —— 等于白恢复。
         *
         *   真正"没有歌"只由 `current == null` 表示：stopLocked 会把它置空。
         */
        val t = PlayerSession.current
        if (suppressed || (t == null && !alwaysVisible)) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
        if (t == null) {
            renderEmpty()
            return
        }

        title.text = t.displayTitle
        spec.text = buildString {
            append(t.specLabelOrDefault)
            when {
                PlayerSession.state == PlayerSession.State.PAUSED -> append("   已暂停")
                PlayerSession.isActive -> append("   播放中")
                /*
                 * 恢复出来的队列：有曲目、但没在放。**这里刻意不写状态词** ——
                 * 写"已暂停"是撒谎（根本没有文件被加载），写"播放中"更不对。
                 * 播放键的图标已经说明了"按下去会开始放"。
                 */
            }
            val q = PlayerSession.queue
            if (q.size > 1) append("   ${q.currentIndex + 1}/${q.size}")
        }
        /*
         * ★ 判据是"**在放吗**"，不是"暂停了吗"。
         *
         *   原来写的是 `if (PAUSED) 播放图标 else 暂停图标` —— 那样
         *   IDLE（恢复出来的队列）会显示**暂停**图标，可按下去是"开始放"，
         *   自相矛盾。按"在放吗"分就不会有这个歧义。
         */
        btnPlay.setImageResource(
            if (PlayerSession.state == PlayerSession.State.PLAYING) R.drawable.ic_pause
            else R.drawable.ic_play
        )
        cover.background = null
        cover.setImageBitmap(CoverLoader.get(activity, t, Ui.dp(activity, 42)))
        setControlsEnabled(true)
    }

    /**
     * 没有歌曲在播放时的样子（只有 [alwaysVisible] 打开时才会走到）。
     *
     * ★ 按钮**置灰**，不做成"能点但没反应"。项目里已经踩过这一类：
     *   「空文件夹没东西可选。给个反馈，不然点了没反应像是坏了」。
     *   整条本身仍然可点 —— 进去是「正在播放」页，那边说的是同一句话。
     *
     * ★★ 每一项都得**显式清掉**。只换文字的话，封面会留着**上一首**那张，
     *    看起来像"正在播一首没有名字的歌"，比空着更让人困惑。
     */
    private fun renderEmpty() {
        title.text = activity.getString(R.string.mini_none)
        spec.text = ""
        cover.setImageDrawable(null)
        cover.background = activity.getDrawable(R.drawable.bg_cover_empty)
        btnPlay.setImageResource(R.drawable.ic_play)
        setControlsEnabled(false)
    }

    private fun setControlsEnabled(on: Boolean) {
        btnPlay.isEnabled = on
        btnNext.isEnabled = on
        btnPrev.isEnabled = on
    }
}
