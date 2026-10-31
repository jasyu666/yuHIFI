package com.hifiprobe

import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 「定位到正在播放」—— 列表右下角那个浮在卡片上的圆按钮。
 *
 * 四个列表页共用：歌单详情、所有歌曲、音乐库、播放队列。
 * 专辑页**没有**（专辑是一张一张听的，点进去那页的曲目就那么几首，
 * 一眼看得完，按钮纯属占地方）。
 *
 * ★★ 它是**一次性**的：点一下跳过去，之后播下一首、下下首，列表都不再动。
 *
 *   这条是刻意的，不是偷懒。自动跟随（换歌就滚到新位置）听着更"智能"，
 *   实际用起来是灾难：用户正翻到列表中间找一首歌，播完当前这首，
 *   列表自己滑走了 —— 手在哪、看到哪，全废。音乐是背景，
 *   列表是用户正在读的东西，**不能为了播放把用户正在读的东西拽走**。
 *
 *   想看当前那首了就再点一次按钮，代价是伸一下手指。
 *
 * ★ 按钮的显隐只看"有没有正在播放/暂停的曲目"，**不看目标在不在当前列表里**。
 *   位置固定、规则一条，用户永远知道它在哪、点了会发生什么；
 *   点了发现不在，就明说一句"不在这个列表里"。
 *   反过来（找不到就藏起来）会让按钮时有时无，用户只会觉得是 bug。
 *   —— 音乐库是唯一的例外：它找得到（能进文件夹），所以不留这句话，
 *      见 [fallback]。
 *
 * ★ [render] 挂在各页的心跳（`onSessionStateChanged`，200ms 一次）上。
 *   它只改一个 visibility，不碰列表，不会把滚动位置搅乱。
 */
class LocateCurrent(
    private val activity: AppCompatActivity,
    private val fab: View,
    private val rv: RecyclerView,
    /**
     * 目标行的 **adapter position**。< 0 表示"当前列表里没有正在播的那首"。
     *
     * 由各页自己给 —— "当前列表"是什么，只有那一页知道
     * （歌单页是歌单内容、音乐库是当前这一层目录、队列页是队列本身）。
     */
    private val target: () -> Int,
    /**
     * target < 0 时的兜底，返回 true 表示这一页自己把事办了。
     * 只有音乐库用得上：它会自动进到那首歌所在的文件夹。
     */
    private val fallback: (() -> Boolean)? = null
) {

    /**
     * 临时收起（多选态、空列表）。
     *
     * ★ 多选时必须收：底部那条操作栏和多选是"一个语境"，
     *   而定位跟"我选了哪几首"毫无关系 —— 顶栏那几个按钮在多选时
     *   也是这么让位的，这里跟着同一条规矩走。
     */
    var suppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            render()
        }

    init {
        fab.setOnClickListener { locate() }
    }

    /** 有没有东西可定位。没有就整条收起来 —— 空列表上杵个按钮最像 bug */
    fun render() {
        val has = PlayerSession.isActive && PlayerSession.current != null
        fab.visibility = if (has && !suppressed) View.VISIBLE else View.GONE
    }

    /**
     * 避开右侧那条字母滑动块。
     *
     * 「所有歌曲」页的滑动块是**整条右边缘**（26dp 宽，见 activity_track_list.xml），
     * 圆按钮按默认的 16dp 右边距会正好压住它最下面几个字母 ——
     * 而且滑动块和按钮都吃触摸，压住之后那几个字母就按不到了。
     * 只有那一页需要让，所以做成一个开关而不是写死在布局里。
     */
    fun avoidIndexBar(avoid: Boolean) {
        val lp = fab.layoutParams as? FrameLayout.LayoutParams ?: return
        val want = Ui.dp(activity, if (avoid) 34 else 16)
        if (lp.marginEnd == want) return
        lp.marginEnd = want
        fab.layoutParams = lp
    }

    private fun locate() {
        if (!PlayerSession.isActive || PlayerSession.current == null) {
            toast(activity.getString(R.string.locate_none))
            return
        }

        val i = target()
        if (i >= 0) {
            scrollTo(i)
            return
        }
        if (fallback?.invoke() == true) return
        toast(activity.getString(R.string.locate_not_in_list))
    }

    /**
     * 滚到第 [i] 行，**贴着列表顶**留一点余量。
     *
     * ★ 不用 `scrollToPosition`：它只保证"这一行可见"，落在屏幕最上沿
     *   还是最下沿全看当前滚到哪了 —— 同一首歌点两次可能停在两个地方。
     *   `scrollToPositionWithOffset` 每次都把它放在同一个高度，
     *   "定位"这件事才有确定的落点。
     */
    private fun scrollTo(i: Int) {
        val lm = rv.layoutManager as? LinearLayoutManager
        if (lm != null) lm.scrollToPositionWithOffset(i, Ui.dp(activity, 8))
        else rv.scrollToPosition(i)
    }

    private fun toast(m: String) =
        Toast.makeText(activity, m, Toast.LENGTH_SHORT).show()
}
