package com.hifiprobe

import android.view.View
import android.widget.ImageButton

/**
 * 多选时顶栏的换装。
 *
 * 进入多选要做三件事：把返回箭头换成 ✕、把副标题收掉（位置让给"已选 N 项"）、
 * 把页面上原有的那几个按钮藏起来。收回来的动作必须**原样还原** ——
 * 所以这里记的是每个控件当时的 visibility，而不是一律设成 VISIBLE：
 * 曲目列表页的「播放全部」在「所有歌曲」下本来就是 GONE 的，
 * 无脑还原会让它冒出来。
 *
 * 标题文字由调用方管：各页面的标题来源不同（有的是常量字符串，
 * 有的是从 intent 带过来的专辑名），塞进来只会变成一堆分支。
 */
class SelectionHeader(
    private val back: ImageButton,
    /** 可以为 null —— 专辑页的顶栏没有副标题 */
    private val subtitle: View?,
    /** 多选时要藏起来的那些按钮 */
    private val extras: List<View>
) {

    private val savedVisibility = HashMap<View, Int>()

    fun enter() {
        back.setImageResource(R.drawable.ic_close)
        back.contentDescription = back.context.getString(R.string.action_cancel_selection)
        subtitle?.let {
            it.visibility = View.GONE
        }
        for (v in extras) {
            savedVisibility[v] = v.visibility
            v.visibility = View.GONE
        }
    }

    fun exit() {
        back.setImageResource(R.drawable.ic_back)
        back.contentDescription = back.context.getString(R.string.action_back)
        subtitle?.visibility = View.VISIBLE
        for (v in extras) v.visibility = savedVisibility[v] ?: View.VISIBLE
        savedVisibility.clear()
    }
}
