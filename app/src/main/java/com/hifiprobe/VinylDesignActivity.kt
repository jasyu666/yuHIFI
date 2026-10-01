package com.hifiprobe

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 彩胶设计 —— 单独的一个界面，用来"造"一张自己的盘。
 *
 * ★ 这里的每一个滑块都对应**压片机上的一个真实变量**，不是滤镜：
 *
 * | 控件 | 工艺含义 |
 * |---|---|
 * | 投料区大小 | 彩料堆在多大的圆里。越小压得越狠，条纹越细长 |
 * | 胶粒数量 | 压之前撒了多少粒颜色料 |
 * | 料块大小 | 每块料有多大。大块成粗条纹，小块成细针 |
 * | 流动扰动 | 模具温度不匀 / 料流受阻，越大条纹越歪 |
 * | 份量 | 每种颜色的料占多少 |
 *
 * 这么设计的好处是调参有方向感：想更像实物就调小投料区、调小扰动，
 * 而不是在一堆看不懂的参数里瞎试。
 *
 * ★ 「重放压制」把这张盘是怎么压出来的重演一遍。用的是成品那套物理模型，
 *   不是另做的动画 —— 每一帧都是那一刻真实的压制状态，所以它同时也是一个
 *   工艺自检：料从中心出去、越往外越细、标签最后才贴上，缺哪一条都说明模型不对。
 *
 * 预览用后台线程渲染并**丢弃过期帧** —— 拖滑块时会连续产生十几个请求，
 * 全渲染一遍既卡又浪费，只画最后一个就够了。
 */
class VinylDesignActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var container: LinearLayout
    private lateinit var tvSaveHint: TextView
    private lateinit var btnReplay: android.widget.Button

    private var style: VinylArt.Style = VinylArt.CLASSIC

    private val renderExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vinyl-render").apply { priority = Thread.MIN_PRIORITY }
    }
    private var renderSeq = 0

    /** 压制动画的场次号。改参数或重开一次都会 +1，旧的那场自己发现后退出 */
    private var pressSeq = 0
    private var pressing = false

    /**
     * 动画的双缓冲。
     *
     * 每帧新建一张 640×640 的位图就是 1.6MB，一秒三四十帧等于一秒上百兆垃圾，
     * GC 会把动画拖出锯齿。两张轮换：正在显示的那张不动，画的是另一张。
     */
    private var pressBufA: Bitmap? = null
    private var pressBufB: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 从当前生效的样式开始改 —— 用户进来看见的是"我现在用的这张"，
        // 而不是一个莫名其妙的默认值
        style = Settings.currentVinyl(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.c(this@VinylDesignActivity, R.color.bg))
        }
        Ui.applySystemBars(root)

        ivPreview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val previewBox = FrameLayout(this).apply {
            addView(ivPreview, FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER))
        }
        root.addView(previewBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        tvSaveHint = TextView(this).apply {
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_tertiary))
            textSize = 11f
            gravity = Gravity.CENTER
        }
        root.addView(tvSaveHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        /*
         * 「重放压制」—— 把这张盘是怎么压出来的重演一遍。
         *
         * 放在预览正下方而不是塞进下面的参数区：它解释的是**上面这张图**，
         * 隔着一屏参数按钮就失去意义了。
         */
        btnReplay = android.widget.Button(this, null, 0).apply {
            text = "▶  重放压制"
            isAllCaps = false
            background = getDrawable(R.drawable.bg_card)
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.brand))
            textSize = 13f
            setOnClickListener { playPress() }
        }
        val replayRow = FrameLayout(this).apply {
            addView(btnReplay, FrameLayout.LayoutParams(dp(190), dp(38), Gravity.CENTER))
        }
        root.addView(replayRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(28))
        }
        root.addView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        build()
        scheduleRender()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 先让动画那一场自己退出，再关线程池 —— 否则它会往一个已经没了的界面上贴帧
        pressSeq++
        renderExec.shutdownNow()
    }

    // ------------------------------------------------------------------

    private fun build() {
        container.removeAllViews()

        header()

        // ---- 换一批 ----
        row(
            "换一批",
            "同一个种子永远画出同一张盘。不满意的就换种子里再抽",
            "🎲"
        ) {
            style = style.copy(seed = System.currentTimeMillis() and 0x7FFFFFFF)
            afterChange()
        }

        section("底色")
        card { c ->
            swatchRow(c, style.baseColor) { style = style.copy(baseColor = it); afterChange() }
            divider(c)
            slider(
                c, "盘底不透明度", pctOf(style.baseAlpha),
                0f, 255f, style.baseAlpha.toFloat()
            ) { v ->
                style = style.copy(baseAlpha = v.roundToInt()); afterChange()
            }
            hint("拉到 100% 以下就是透明盘 —— Clear / Ultra Clear 那一类，能透过盘面看见底下的东西。真实彩胶里透明盘占了一大半。")
        }

        section("中心标签")
        card { c -> swatchRow(c, style.labelColor) { style = style.copy(labelColor = it); afterChange() } }

        section("喷溅颜色")
        hint("每种的「份量」决定这种料撒多少。点色块可以改颜色、调份量或删掉。")
        card { c -> splatList(c) }
        spacer()
        card { c ->
            val a0 = style.splat.firstOrNull()?.alpha ?: 255
            slider(c, "彩料不透明度", pctOf(a0), 0f, 255f, a0.toFloat()) { v ->
                val a = v.roundToInt()
                style = style.copy(splat = style.splat.map { it.copy(alpha = a) })
                afterChange()
            }
            hint("一次设定所有彩料。半透的料压在别的颜色上，下层会透出来 —— 真实的「色中色」就是这么来的。想单独调某一种，点上面那一行进去调。")
        }
        spacer()
        outlineButton("＋ 添加一种颜色") {
            style = style.copy(splat = style.splat + VinylArt.Splat(PALETTE.random(), 1f))
            afterChange()
        }

        section("工艺参数")
        hint("这四个是压片机上的真实变量，不是滤镜。条纹的形状全部由它们推出来 —— 想更像实物，把投料区调小（压得更狠）、扰动调小。")
        card { c ->
            slider(c, "投料区大小", "${(style.chargeRatio * 100).roundToInt()}%", 0.15f, 0.55f, style.chargeRatio) { v ->
                style = style.copy(chargeRatio = v); afterChange()
            }
            hint("彩料堆在多大的一个圆里。越小 = 压开时拉伸越狠，条纹越细长；越大 = 条纹越短越粗。")
            divider(c)
            slider(c, "胶粒数量", "${style.count} 粒", 10f, 400f, style.count.toFloat()) { v ->
                style = style.copy(count = v.roundToInt()); afterChange()
            }
            divider(c)
            slider(c, "料块大小", "${(style.granuleSize * 100).roundToInt()}%", 0.10f, 0.80f, style.granuleSize) { v ->
                style = style.copy(granuleSize = v); afterChange()
            }
            hint("每块料有多大（相对投料区）。大块压开是粗条纹，小块是细针 —— 真实的彩料大小差一个量级，这里用幂律还原了这种参差。")
            /*
             * ★★ 「流动扰动」那根滑条**去掉了**（用户 2026-10-01 定的）。
             *
             *   `Style.turbulence` 这个字段、以及它背后的整套「角向速度场」
             *   （Harmonics / buildHarmonics / fieldAt）**都一起删掉了** ——
             *   值恒为 0，所以条纹现在是笔直的放射线，不再弯。
             *
             *   ★ 副作用：随机数的取用顺序变了，**同一个种子画出来的盘会和
             *     以前不一样**（整套花纹重排，不只是变直）。种子本来就是个
             *     随机数，「换一批」一下就行。
             */
        }

        spacer()
        outlineButton("恢复默认（红标黑盘）") {
            style = VinylArt.CLASSIC.copy(seed = style.seed)
            afterChange()
        }
        spacer()
        primaryButton("保存这张盘…") { save() }

        hint("保存时会给它起个名字，存下来的设计进入「默认封面样式」列表，和内置样式并列。刚存完的那张会立刻生效 —— 没有内嵌封面的曲目都用它。")
    }

    // ------------------------------------------------------------------

    /** 0~255 的不透明度换成百分比文字。★ 四舍五入，255 一定是「100%」 */
    private fun pctOf(a: Int): String = "${(a.coerceIn(0, 255) * 100 + 127) / 255}%"

    private fun afterChange() {
        // 改了参数就把正在放的压制动画掐掉，否则它每 25ms 覆一帧，
        // 这里的静态预览根本显示不出来
        pressSeq++
        pressing = false
        btnReplay.text = "▶  重放压制"
        scheduleRender()
        // 每次改动只重建列表那几块，别整页重建 —— 否则滑块会被重置、手感全断
        rebuildSplats()
    }

    /**
     * 重放一次压制过程：中心那坨料被摊开、拉长成放射纹，最后贴上标签。
     *
     * ★ 用的就是**成品那套物理模型**（[VinylArt.renderPress]），不是另做一套
     *   动画。每一帧都是那个时刻真实的压制状态，所以它不只是好看 ——
     *   它能直接说明工艺对不对：料该从中心出去、该越往外越细、
     *   标签该是最后才出现的。看得出不对劲就是模型有问题，不是"动画效果"。
     */
    private fun playPress() {
        val seq = ++pressSeq
        pressing = true
        btnReplay.text = "压制中…"
        val snapshot = style

        // 双缓冲只建一次，之后一直复用 —— 见 pressBufA 的说明
        if (pressBufA == null) {
            pressBufA = Bitmap.createBitmap(PREVIEW_PX, PREVIEW_PX, Bitmap.Config.ARGB_8888)
            pressBufB = Bitmap.createBitmap(PREVIEW_PX, PREVIEW_PX, Bitmap.Config.ARGB_8888)
        }

        renderExec.execute {
            val t0 = SystemClock.uptimeMillis()
            var useA = true
            for (f in 0..PRESS_FRAMES) {
                if (seq != pressSeq) return@execute        // 被新的一次取代了
                val buf = (if (useA) pressBufA else pressBufB) ?: return@execute
                useA = !useA

                runCatching { VinylArt.renderPressInto(buf, snapshot, f / PRESS_FRAMES.toFloat()) }
                    .onFailure { return@execute }

                runOnUiThread { if (seq == pressSeq) ivPreview.setImageBitmap(buf) }

                // 按目标时刻睡，而不是固定间隔 —— 渲染快的时候才不会越播越快
                val wait = t0 + PRESS_MS * f / PRESS_FRAMES - SystemClock.uptimeMillis()
                if (wait > 0) runCatching { Thread.sleep(wait) }
            }
            runOnUiThread {
                if (seq == pressSeq) {
                    pressing = false
                    btnReplay.text = "▶  重放压制"
                }
            }
        }
    }

    /**
     * 后台渲染 + 丢弃过期帧。
     *
     * 拖滑块会连续产生十几个请求。串行执行保证不并发抢内存，
     * 序号比对保证只有最新那一帧被显示 —— 中间的全部丢掉。
     */
    private fun scheduleRender() {
        val seq = ++renderSeq
        val snapshot = style
        renderExec.execute {
            val bmp: Bitmap = runCatching { VinylArt.render(PREVIEW_PX, snapshot) }
                .getOrElse { return@execute }
            runOnUiThread { if (seq == renderSeq) ivPreview.setImageBitmap(bmp) }
        }
    }

    private fun rebuildSplats() {
        // 找到「喷溅颜色」那张卡片重建。用 tag 标记，避免按索引找而错位
        val holder = container.findViewWithTag<LinearLayout>("splatCard") ?: return
        holder.removeAllViews()
        splatList(holder)
    }

    private fun splatList(parent: LinearLayout) {
        parent.tag = "splatCard"
        if (style.splat.isEmpty()) {
            parent.addView(TextView(this).apply {
                text = "还没有颜色 —— 点下面的「添加一种颜色」"
                setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_tertiary))
                textSize = 12f
                setPadding(dp(2), dp(8), dp(2), dp(8))
            })
            return
        }
        val total = style.splat.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.001f)

        for ((i, sp) in style.splat.withIndex()) {
            if (i > 0) divider(parent)
            val pct = (sp.weight / total * 100).roundToInt()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(2), dp(12), dp(2), dp(12))
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener { editSplat(i) }
            }
            row.addView(swatch(sp.color, 34), LinearLayout.LayoutParams(dp(34), dp(34)))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(this@VinylDesignActivity).apply {
                    text = colorName(sp.color)
                    setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_primary))
                    textSize = 15f
                })
                addView(TextView(this@VinylDesignActivity).apply {
                    // 不透明的那种不用特意标出来（那是绝大多数），只有半透的才显示
                    text = if (sp.alpha >= 255) "份量 $pct%"
                    else "份量 $pct% · 不透明度 ${pctOf(sp.alpha)}"
                    setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_tertiary))
                    textSize = 11f
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "›"
                setTextColor(Ui.c(this@VinylDesignActivity, R.color.brand))
                textSize = 15f
            })
            parent.addView(row)
        }
    }

    private fun editSplat(index: Int) {
        val sp = style.splat.getOrNull(index) ?: return
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        var picked = sp.color
        var weight = sp.weight
        var alpha = sp.alpha

        val preview = swatch(picked, 44)
        col.addView(preview, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })

        col.addView(TextView(this).apply {
            text = "颜色"
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_secondary))
            textSize = 12f
        })
        val grid = colorGrid(picked) {
            picked = it
            (preview.background as GradientDrawable).setColor(it)
        }
        col.addView(grid)

        col.addView(TextView(this).apply {
            text = "份量（这种料撒多少）"
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(14), 0, 0)
        })
        val label = TextView(this).apply {
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.brand))
            textSize = 13f
        }
        val bar = SeekBar(this).apply {
            max = 40
            progress = ((sp.weight.coerceIn(0.1f, 4f) - 0.1f) / 3.9f * 40).roundToInt()
        }
        fun sync() {
            weight = 0.1f + bar.progress / 40f * 3.9f
            label.text = String.format("×%.1f", weight)
        }
        sync()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = sync()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        col.addView(label)
        col.addView(bar)

        // ---- 这一种料自己有多透 ----
        col.addView(TextView(this).apply {
            text = "不透明度（越低越透，下面的颜色透得出来）"
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(14), 0, 0)
        })
        val aLabel = TextView(this).apply {
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.brand))
            textSize = 13f
        }
        val aBar = SeekBar(this).apply {
            max = 255
            progress = sp.alpha.coerceIn(0, 255)
        }
        fun syncAlpha() {
            alpha = aBar.progress
            aLabel.text = pctOf(alpha)
        }
        syncAlpha()
        aBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = syncAlpha()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        col.addView(aLabel)
        col.addView(aBar)

        val dlg = AlertDialog.Builder(this)
            .setView(col)
            .setPositiveButton("确定") { _, _ ->
                val list = style.splat.toMutableList()
                list[index] = VinylArt.Splat(picked, weight, alpha)
                style = style.copy(splat = list)
                afterChange()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(
                Ui.c(this, R.color.error)
            )
            dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val list = style.splat.toMutableList()
                list.removeAt(index)
                style = style.copy(splat = list)
                afterChange()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /**
     * 保存这张盘。
     *
     * ★★ 不再**无声地**覆盖「默认封面」，而是先让用户起个名字。
     *
     *   原来一点「保存为默认封面」就直接生效，用户设计第二张的时候第一张
     *   没有任何提示地消失 —— 而且两张都没有名字，事后根本想不起来丢了什么。
     *   现在存下来的是**具名条目**，进「默认封面样式」列表和内置预设并列，
     *   可以存很多张、随时切回去。
     *
     * ★ 名字预填成「底色 + 喷溅颜色」（如「墨黑·正红蜜橘」）——
     *   绝大多数人不会想名字，给一个说得出所以然的默认值比空着强。
     */
    private fun save() {
        val input = android.widget.EditText(this).apply {
            setText(defaultName())
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        AlertDialog.Builder(this)
            .setTitle("保存这张盘")
            .setMessage("给它起个名字。存下来的设计会出现在「默认封面样式」列表里，和内置预设并列。")
            .setView(input)
            .setPositiveButton("保存") { _, _ -> saveAs(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存时预填的名字：底色名 + 最多三种彩料名。例：`墨黑·正红蜜橘` */
    private fun defaultName(): String {
        val base = colorName(style.baseColor)
        if (style.splat.isEmpty()) return base
        return base + "·" + style.splat.take(3).joinToString("") { colorName(it.color) }
    }

    private fun saveAs(raw: String) {
        val name = raw.trim().ifEmpty { defaultName() }
        if (Settings.savedVinyls(this).any { it.name == name }) {
            AlertDialog.Builder(this)
                .setTitle("已有同名设计")
                .setMessage("「$name」已经存在。用它替换掉旧的那张吗？")
                .setPositiveButton("替换") { _, _ -> commitSave(name) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            commitSave(name)
        }
    }

    private fun commitSave(name: String) {
        val json = VinylArt.toJson(style)
        Settings.putSavedVinyl(this, name, json)
        /*
         * 存完立刻用上。
         *
         * ★ 走的是原来那条路：只写 `vinyl_custom`，不碰预设下标 ——
         *   `currentVinyl()` 里「自定义压着预设」的判断因此一行都不用改。
         */
        Settings.setVinylCustom(this, json)
        Settings.setVinylCustomName(this, name)
        tvSaveHint.text = "已保存为「$name」"
        android.widget.Toast.makeText(this, "已保存为「$name」", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    //  界面构件
    // ------------------------------------------------------------------

    private fun header() {
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(ImageButton(this@VinylDesignActivity).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Ui.c(this@VinylDesignActivity, R.color.text_primary))
                background = null
                contentDescription = "返回"
                setPadding(0, 0, 0, 0)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(this@VinylDesignActivity).apply {
                text = "彩胶设计"
                setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_primary))
                textSize = 21f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), 0, 0, 0)
            })
        })
    }

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            text = title
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.brand))
            textSize = 12f
            letterSpacing = 0.12f
            setPadding(dp(4), dp(18), 0, dp(8))
        })
    }

    private fun hint(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_tertiary))
            textSize = 11f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), 0, dp(4), dp(8))
        })
    }

    private fun spacer() {
        container.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
    }

    private fun card(build: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
        }
        container.addView(c, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        build(c)
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply {
            setBackgroundColor(Ui.c(this@VinylDesignActivity, R.color.border))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(2); marginEnd = dp(2)
        })
    }

    private fun row(title: String, subtitle: String, trailing: String, onClick: () -> Unit) {
        card { c ->
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(2), dp(14), dp(2), dp(14))
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener { onClick() }
            }
            v.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@VinylDesignActivity).apply {
                    text = title
                    setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_primary))
                    textSize = 15f
                })
                addView(TextView(this@VinylDesignActivity).apply {
                    this.text = subtitle
                    setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_tertiary))
                    textSize = 11f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            v.addView(TextView(this).apply {
                text = trailing
                textSize = 17f
            })
            c.addView(v)
        }
    }

    private fun slider(
        parent: LinearLayout, title: String, valueLabel: String,
        min: Float, max: Float, cur: Float, onChange: (Float) -> Unit
    ) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(12), dp(2), 0)
        }
        head.addView(TextView(this).apply {
            text = title
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.text_primary))
            textSize = 15f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valText = TextView(this).apply {
            text = valueLabel
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.brand))
            textSize = 13f
        }
        head.addView(valText)
        col.addView(head)

        val bar = SeekBar(this).apply { this.max = 100 }
        bar.progress = ((cur - min) / (max - min) * 100).roundToInt().coerceIn(0, 100)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(min + (max - min) * (p / 100f))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        col.addView(bar)
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        parent.addView(col)
    }

    private fun swatchRow(parent: LinearLayout, current: Int, onPick: (Int) -> Unit) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (col in PALETTE.take(8)) {
            val v = swatch(col, 34)
            if (col == current) v.background = ringDrawable(col, true)
            v.setOnClickListener { onPick(col); rebuildAll() }
            row.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(8)
            })
        }
        wrap.addView(row)
        wrap.addView(TextView(this).apply {
            text = "更多颜色 ›"
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.brand))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                // dialog 要先建后 show，而网格的点击回调里又要用到它 —— 用数组兜一下
                val holder = arrayOfNulls<AlertDialog>(1)
                val grid = colorGrid(current) { col ->
                    onPick(col)
                    rebuildAll()
                    holder[0]?.dismiss()
                }
                holder[0] = AlertDialog.Builder(this@VinylDesignActivity)
                    .setTitle("选择颜色")
                    .setView(grid)
                    .setNegativeButton("取消", null)
                    .create()
                holder[0]?.show()
            }
        })
        parent.addView(wrap)
    }

    /** 整页重建（改底色/标签色这类会动到其它色块选中态的操作才用） */
    private fun rebuildAll() {
        build()
        scheduleRender()
    }

    private fun swatch(color: Int, sizeDp: Int): View = View(this).apply {
        background = ringDrawable(color, false)
    }

    /** 色块：圆角方块 + 一圈描边。选中的加一圈更粗的品牌色环 */
    private fun ringDrawable(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(color)
            setStroke(
                dp(if (selected) 3 else 1),
                Ui.c(this@VinylDesignActivity,
                    if (selected) R.color.brand else R.color.border)
            )
        }

    /** 调色板网格。用 GridLayout 手动排 —— 只有几十个色块，不值得上 RecyclerView */
    private fun colorGrid(current: Int, onPick: (Int) -> Unit): View {
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 6
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        val cell = dp(40)
        for (col in PALETTE) {
            val v = swatch(col, 40)
            if (col == current) v.background = ringDrawable(col, true)
            v.setOnClickListener { onPick(col) }
            grid.addView(v, android.widget.GridLayout.LayoutParams().apply {
                width = cell; height = cell
                marginEnd = dp(8); bottomMargin = dp(8)
            })
        }
        return grid
    }

    private fun primaryButton(text: String, onClick: () -> Unit) {
        container.addView(android.widget.Button(this, null, 0).apply {
            this.text = text
            isAllCaps = false
            background = getDrawable(R.drawable.bg_play)   // 胶囊底，和主播放键同一套
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Ui.c(this@VinylDesignActivity, R.color.brand))
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.on_brand))
            textSize = 14f
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ))
    }

    private fun outlineButton(text: String, onClick: () -> Unit) {
        container.addView(android.widget.Button(this, null, 0).apply {
            this.text = text
            isAllCaps = false
            background = getDrawable(R.drawable.bg_card)
            setTextColor(Ui.c(this@VinylDesignActivity, R.color.brand))
            textSize = 13f
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ))
    }

    private fun colorName(c: Int): String =
        NAMES.entries.minByOrNull { dist(it.key, c) }?.value ?: "自定义"

    private fun dist(a: Int, b: Int): Int =
        Math.abs(Color.red(a) - Color.red(b)) +
                Math.abs(Color.green(a) - Color.green(b)) +
                Math.abs(Color.blue(a) - Color.blue(b))

    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        /** 预览渲染尺寸。够大够清晰，又不至于拖滑块时卡 */
        private const val PREVIEW_PX = 640

        /** 压制动画：多少帧、放多久。34 帧 / 900ms ≈ 38fps，够顺了 */
        private const val PRESS_FRAMES = 34
        private const val PRESS_MS = 900L

        /**
         * 调色板。不是随便凑的彩虹色 —— 按"能真的压到唱片上"选的：
         * 黑胶颜料以饱和的深色为主（浅色会被底料吃掉），所以这里深色偏多。
         */
        private val PALETTE = listOf(
            0xFF111111.toInt(), 0xFF3A3A3A.toInt(), 0xFF8A8A8A.toInt(), 0xFFF2F2F2.toInt(),
            0xFF7F1D1D.toInt(), 0xFFDC2626.toInt(), 0xFFF87171.toInt(), 0xFFF2AFC0.toInt(),
            0xFF9A3412.toInt(), 0xFFEA580C.toInt(), 0xFFFB923C.toInt(), 0xFFFDD835.toInt(),
            0xFF3F6212.toInt(), 0xFF65A30D.toInt(), 0xFF4ADE80.toInt(), 0xFF26A69A.toInt(),
            0xFF0C4A6E.toInt(), 0xFF0369A1.toInt(), 0xFF38BDF8.toInt(), 0xFF7DD3FC.toInt(),
            0xFF1B2E8C.toInt(), 0xFF3D5AC4.toInt(), 0xFF6366F1.toInt(), 0xFFA5B4FC.toInt(),
            0xFF4C1D95.toInt(), 0xFF7B3FA8.toInt(), 0xFFB07BD0.toInt(), 0xFFE9D5FF.toInt(),
            0xFF831843.toInt(), 0xFFDB2777.toInt(), 0xFFF472B6.toInt(), 0xFFFFD6E7.toInt(),
            0xFF78350F.toInt(), 0xFFB45309.toInt(), 0xFFD4A24C.toInt(), 0xFFFDE68A.toInt(),
        )

        /** 给颜色起个中文名，让用户看得懂自己选了什么 */
        private val NAMES = linkedMapOf(
            0xFF111111.toInt() to "墨黑", 0xFF3A3A3A.toInt() to "岩灰",
            0xFF8A8A8A.toInt() to "银灰", 0xFFF2F2F2.toInt() to "瓷白",
            0xFF7F1D1D.toInt() to "酒红", 0xFFDC2626.toInt() to "正红",
            0xFFF87171.toInt() to "珊瑚红", 0xFFF2AFC0.toInt() to "樱粉",
            0xFF9A3412.toInt() to "焦棕", 0xFFEA580C.toInt() to "橙", 0xFFFB923C.toInt() to "蜜橘",
            0xFFFDD835.toInt() to "柠檬黄", 0xFF3F6212.toInt() to "橄榄", 0xFF65A30D.toInt() to "草绿",
            0xFF4ADE80.toInt() to "薄荷", 0xFF26A69A.toInt() to "青碧",
            0xFF0C4A6E.toInt() to "深海蓝", 0xFF0369A1.toInt() to "钴蓝",
            0xFF38BDF8.toInt() to "天蓝", 0xFF7DD3FC.toInt() to "冰蓝",
            0xFF1B2E8C.toInt() to "藏青", 0xFF3D5AC4.toInt() to "宝蓝",
            0xFF6366F1.toInt() to "靛蓝", 0xFFA5B4FC.toInt() to "雾蓝",
            0xFF4C1D95.toInt() to "深紫", 0xFF7B3FA8.toInt() to "紫罗兰",
            0xFFB07BD0.toInt() to "丁香紫", 0xFFE9D5FF.toInt() to "薰衣草",
            0xFF831843.toInt() to "紫红", 0xFFDB2777.toInt() to "品红",
            0xFFF472B6.toInt() to "桃粉", 0xFFFFD6E7.toInt() to "藕粉",
            0xFF78350F.toInt() to "可可", 0xFFB45309.toInt() to "琥珀",
            0xFFD4A24C.toInt() to "黄铜", 0xFFFDE68A.toInt() to "香槟",
        )
    }
}
