package com.hifiprobe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 右侧字母滑动块 —— 所有歌曲页那一竖排 A-Z。
 *
 * ★ RecyclerView **没有**内置这个东西（`FastScroller` 是老 support 库里的
 *   半成品，只画一条拖动条、不出字母）。所以这里手写一个：
 *   竖排字母 + 命中判定 + 触摸反馈，逻辑一共不到一百行。
 *
 * ★ 库里没有的字母画淡、不能点。全部画成一样的话，用户点 D 发现没反应，
 *   会以为滑动块坏了，而不是"没有 D 开头的歌"。
 *
 * ★ 触摸区域就是整个 View 的宽度（比字宽），10dp 的字如果只有字宽可点，
 *   在手机上基本点不中。
 */
class IndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 显示哪些字母。默认 A-Z + '#' */
    var letters: List<Char> = Pinyin.LETTERS

    /** 库里实际存在的字母。不在里面的画淡且不响应 */
    var present: Set<Char> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    /** 选中的字母变化时回调（按住不放会连续触发同一字母，这里会去重） */
    var onLetterSelected: ((Char) -> Unit)? = null

    /** 松手。用来收起中央的气泡 */
    var onReleased: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var active: Char? = null

    /** 每个字母占的高度，onSizeChanged 时算 */
    private var slotH = 0f

    /** 用户手指当前落在哪个字母上（-1 = 没有） */
    private var touched = -1

    /** 手指正停在上面的那个字母 */
    private val colorActive = Ui.c(context, R.color.brand)

    /**
     * 库里**有**这个字母。
     *
     * 用二级色而不是三级：它们是可点的目标，得看得清。
     * 库里没有的靠 alpha 压到 80 变淡（见 onDraw），不用另开一档颜色 ——
     * 写死一个"更淡的灰"在深浅两套主题下总有一边不对。
     */
    private val colorPresent = Ui.c(context, R.color.text_secondary)

    init {
        paint.textSize = 10f * resources.displayMetrics.scaledDensity
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(Ui.dp(context, 26), widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 上下各留 8dp 的余量，否则 A 和 # 会贴着边缘，最后一行容易被系统手势区吃掉
        val pad = Ui.dp(context, 8) * 2
        slotH = if (letters.isEmpty()) 0f else (h - pad).toFloat() / letters.size
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slotH <= 0f) return
        val cx = width / 2f
        // 文字垂直居中：ascent 是负的，减掉它和 descent 的一半才真的居中
        val baselineFix = -(paint.ascent() + paint.descent()) / 2f
        val top = Ui.dp(context, 8).toFloat()

        letters.forEachIndexed { i, ch ->
            val isActive = ch == active
            paint.color = if (isActive) colorActive else colorPresent
            paint.alpha = when {
                isActive -> 255
                ch in present -> 255
                else -> 80
            }
            canvas.drawText(ch.toString(), cx, top + slotH * (i + 0.5f) + baselineFix, paint)
        }
    }

    private fun indexAt(y: Float): Int {
        if (slotH <= 0f) return -1
        val i = ((y - Ui.dp(context, 8)) / slotH).toInt()
        return i.coerceIn(0, letters.size - 1)
    }

    /**
     * 找离 [y] 最近的、**库里确实有**的字母。
     *
     * 用户按住滑动块往下划时手指会经过空字母（比如没有 Q 开头的歌），
     * 严格按命中判定的话滑动会一顿一顿的。这里往前找最近的一个，
     * 找不到再往后找 —— 滑动过程始终有反馈。
     */
    private fun nearestPresent(i: Int): Int {
        if (letters.getOrNull(i) in present) return i
        for (d in 1 until letters.size) {
            val a = i - d
            if (a >= 0 && letters[a] in present) return a
            val b = i + d
            if (b < letters.size && letters[b] in present) return b
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val i = indexAt(event.y)
                if (i < 0) return true
                if (i == touched) return true
                touched = i

                val hit = nearestPresent(i)
                if (hit < 0) return true

                val ch = letters[hit]
                if (ch != active) {
                    active = ch
                    invalidate()
                    // 划过每个字母震一下。这是"我确实划到这儿了"的唯一触觉反馈 ——
                    // 手指按着滑动块时，视线在列表上不在滑动块上
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                onLetterSelected?.invoke(ch)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touched = -1
                active = null
                invalidate()
                onReleased?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 无障碍：滑动块本身没有可点的"内容"，把它让给父容器做整体描述 */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
