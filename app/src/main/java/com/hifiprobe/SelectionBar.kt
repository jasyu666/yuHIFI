package com.hifiprobe

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * 底部操作栏 —— 多选时从底下升起来的那一条。
 *
 * 每个页面能做的操作不一样（曲库能删文件，歌单只能移除），所以按钮在代码里
 * 加，不走 XML。这里只负责"把一组动作摆成等宽的一排"和"没选东西时置灰"。
 *
 * ★ 没选中任何东西时按钮置灰，而不是隐藏 ——
 *   隐藏的话操作栏会忽长忽短，用户刚看清按钮在哪就没了；
 *   置灰至少能说明"这里有这个功能，只是现在用不了"。
 */
class SelectionBar(activity: AppCompatActivity) {

    /** 一个可执行的动作。[label] 是按钮上的字 */
    class Action(val label: String, val run: () -> Unit)

    private val act = activity
    private val bar: LinearLayout = activity.findViewById(R.id.selBar)

    /**
     * 上一次画出来的样子。
     *
     * ★ 播放中心跳 200ms 一次，各页面的 onSessionStateChanged 都会走到
     *   renderSelection。不比对就重建的话，一秒拆装五次按钮 ——
     *   用户按下去的那一下很可能正好落在"按钮被移除"的空档里，
     *   表现就是"点了没反应"或者点到了旁边那个。
     */
    private var lastSignature: String? = null

    /**
     * 显示操作栏。[count] 是当前选中项数 —— 为 0 时所有按钮置灰。
     *
     * 签名没变就什么都不做（见 [lastSignature]）；变了才整体重建，
     * 因为每页的动作集是跟着页面走的常量，重建比"复用按钮再逐项改状态"
     * 更不容易出错。
     */
    fun show(count: Int, actions: List<Action>) {
        // 签名里带上选中数：数量变了按钮的可用状态就变了，必须重画
        val sig = count.toString() + "|" + actions.joinToString("|") { it.label }
        if (sig == lastSignature && bar.visibility == View.VISIBLE) return
        lastSignature = sig

        bar.removeAllViews()
        bar.visibility = View.VISIBLE

        val usable = count > 0
        for (a in actions) {
            val b = Button(act, null, 0).apply {
                text = a.label
                isAllCaps = false
                background = act.getDrawable(R.drawable.bg_bar)
                setTextColor(Ui.c(act, R.color.brand))
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                isEnabled = usable
                // 置灰时把字色也压暗：只靠 isEnabled 的话，自定义背景色
                // 不会跟着变，看起来还是可点的
                alpha = if (usable) 1f else 0.35f
                setOnClickListener { if (usable) a.run() }
            }
            bar.addView(b, LinearLayout.LayoutParams(0, Ui.dp(act, 42), 1f).apply {
                marginStart = Ui.dp(act, 4)
                marginEnd = Ui.dp(act, 4)
            })
        }
    }

    fun hide() {
        if (bar.visibility == View.GONE && lastSignature == null) return
        lastSignature = null
        bar.visibility = View.GONE
        bar.removeAllViews()
    }
}
