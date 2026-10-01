package com.hifiprobe

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 界面小工具。
 *
 * ★ 存在的理由：**代码里不许再出现颜色字面量**。
 *
 *   XML 布局能直接引用 `@color/brand`，但代码搭的界面（设置页）和
 *   运行时改色的地方（徽章）只能拿到 int。如果那些地方写死
 *   `0xFF6FCF97`，就又多出一份"跟着主题走不了"的颜色 ——
 *   之前界面看着不统一，一半原因就在这儿。
 *
 *   所以统一走 [c]：从主题取色，浅色/深色自动正确。
 */
object Ui {

    /** 从主题取色。所有运行时用到的颜色都该从这里来。 */
    fun c(ctx: Context, @ColorRes id: Int): Int = ContextCompat.getColor(ctx, id)

    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 把 TextView 变成一枚徽章：换背景 + 换字色 */
    fun badge(tv: TextView, @DrawableRes bg: Int, @ColorRes fg: Int) {
        tv.setBackgroundResource(bg)
        tv.setTextColor(c(tv.context, fg))
    }

    /** 语义徽章：直通 / 重采样 / 中性 */
    fun okBadge(tv: TextView) = badge(tv, R.drawable.bg_badge_ok, R.color.ok)
    fun warnBadge(tv: TextView) = badge(tv, R.drawable.bg_badge_warn, R.color.warn)
    fun neutralBadge(tv: TextView) = badge(tv, R.drawable.bg_badge_neutral, R.color.text_tertiary)

    /** 设置页那种「标题 + 说明」的左栏 */
    fun setVisible(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 给根布局留出状态栏 / 导航栏的位置。
     *
     * ★ 这不是"美化"，是 targetSdk 35 的硬性后果：Android 15 起
     *   **强制 edge-to-edge**，应用内容会被顶到状态栏底下。
     *   不处理的话，标题会和时钟、信号图标叠在一起（实测截图就是这样）。
     *
     * 用 insets 监听而不是 `fitsSystemWindows="true"`：后者在某些机型 +
     * 手势导航的组合下算不准底部，而音乐播放器的底部正好有迷你播放条，
     * 压到导航条上会按不着。
     *
     * 保留原有的左右内边距 —— 布局里已经设了，别覆盖掉。
     */
    fun applySystemBars(
        root: View,
        top: Boolean = true,
        bottom: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                if (top) bars.top else 0,
                v.paddingRight,
                if (bottom) bars.bottom else 0
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
