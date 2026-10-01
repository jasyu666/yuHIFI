package com.hifiprobe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 黑胶封面 —— 形状是**压出来的**，不是画出来的。
 *
 * ★ 这一版和上一版的根本区别：
 *
 *   上一版是**在成品盘面上摆形状**：每条条纹的起点半径、长度、宽度全由
 *   一个公式直接给出，再拿 clipPath 把标签区裁掉。画的是"喷溅应该长什么样"，
 *   本质上是一张蒙版 —— 所以不自然。
 *
 *   这一版按真实压片工艺走，四步都不省：
 *
 *     ① **投料**     彩料堆在盘的最中央，只有 chargeRatio 这么大一块
 *     ② **合模加压** 材料从中心被挤向边缘，径向铺开
 *     ③ **流动拉伸** 流动中彩料被径向拉长、切向压窄（面积守恒）
 *     ④ **贴中心标** 压完才贴的，所以它盖在彩料上面
 *
 *   条纹「内端粗、外端尖、越往外越细长」**不是调出来的参数，是第 ③ 步的
 *   必然结果**。改任何一个工艺旋钮，所有条纹按同一个物理一起变。
 *
 * ★ 流动的数学：
 *
 *   初始半径 x0（料在投料区的位置）→ 成品半径
 * ```
 *       X(x0) = (x0 / chargeRatio) ^ P
 * ```
 *   局部拉伸率就是这条映射的导数
 * ```
 *       λ(x0) = P / chargeRatio · (x0 / chargeRatio) ^ (P - 1)
 * ```
 *
 *   P = 1 是理想的均匀扩张 —— 所有材料拉伸一样多，画出来是一堆**等长等宽的
 *   短棒**，一眼假。真实模腔里，外圈的料要让开的体积大得多（面积 ∝ r），
 *   流速更快。取 **P = 2**，λ 就随 x0 线性增长：内圈拉得少（短而粗）、
 *   外圈拉得多（长而细）。实物上「中心糊成一团、边缘细成丝」的层次感，
 *   整个来自 λ 这一项。
 *
 *   一团初始直径 s 的料，压开后：
 * ```
 *       径向长度 = s · λ          切向宽度 = s / λ
 * ```
 *   两者乘积恒为 s² —— 面积守恒。**「外端细」不是画细的，是被压细的。**
 *
 * ★ 条纹是**笔直的放射线**，没有弯曲。
 *
 *   早期版本还有一套「角向速度场」（几个低阶正弦谐波叠加成 ω(θ)），
 *   让相邻的彩料成组弯成弧形。**已经删掉了**（用户 2026-10-01 定）：
 *   那一项调大了会把条纹扭到接近切向、糊成横杠，调到 0 又完全没有弯，
 *   好用的区间很窄；而真实彩胶的撒料本来多数就是直的放射状。
 *
 *   所以现在的形状只有两个来源：**流动映射**（内粗外尖）和**颗粒分布**
 *   （长短参差）。每一条都是直的，角度恒等于投料时的位置。
 *
 * 整套是**种子驱动**的：同一个种子永远画出同一张盘，所以「换一批」就是换种子。
 * ★ 注意：扰动删掉之后随机数的取用顺序变了，**同一个种子画出来的盘会和
 *   以前不一样**（整套花纹都会重排，不只是变直）。
 */
object VinylArt {

    /**
     * 一种彩料色及其份量。
     *
     * [alpha] 是**这块料自己的不透明度**（0~255）。
     *
     * ★ 真实彩胶里「色中色」（Color-in-color —— Clear 里裹一团 Blue）靠的就是它：
     *   外层的料半透，里层的颜色能透出来。默认 255（不透明），
     *   也就是加这个字段**之前**的行为。
     */
    data class Splat(val color: Int, val weight: Float = 1f, val alpha: Int = 255)

    /** 一张彩胶的全部工艺参数 */
    data class Style(
        /** 饼料底色 */
        val baseColor: Int = 0xFF121215.toInt(),

        /**
         * **盘底的不透明度**（0~255）。
         *
         * 255 = 不透明，就是实心色盘（绝大多数彩胶长这样）；
         * 拉到 255 以下就是**透明盘** —— Clear / Ultra Clear / Smoke 那一类，
         * 能透过盘面看见底下的东西。真实彩胶里透明盘占了一大半：
         * 「Clear base with Red splatter」「Blue(transp.) in Clear(transp.)」
         * 「Ultra Clear / Black」全都要靠它才做得出来。
         *
         * ★ 这份透明度能一路活到屏幕上：封面画在一张 ARGB_8888 位图上，
         *   而且**现画的黑胶不落盘**（只有内嵌封面的缩略图走 JPEG，
         *   见 `CoverLoader.decodeOrRender`）—— 走 JPEG 的话 alpha 会被压掉。
         */
        val baseAlpha: Int = 255,
        /** 中心标签色 */
        val labelColor: Int = 0xFFC62828.toInt(),
        val labelAccentColor: Int = 0xFFF5F0E6.toInt(),

        /** 彩料色。空 = 纯色盘 */
        val splat: List<Splat> = emptyList(),

        /**
         * **投料区半径**占成品半径的比例。
         *
         * 彩料就堆在这么大的一个圆里，然后被摊满整张盘，所以挤出比是
         * `1 / chargeRatio`：值越小料堆得越集中，压开时拉伸越剧烈、
         * 条纹越细长；值越大条纹越短越粗。
         */
        val chargeRatio: Float = 0.30f,

        /** 彩料颗粒数 */
        val count: Int = 80,

        /**
         * 颗粒大小，相对投料区半径。
         *
         * 真实彩料是筛出来的碎块，大小差很多：大块压开成长条，小块压开成
         * 细针，混在一起才像实物。这里还会再乘一个幂律分布，
         * 让「小的多、大的少」。
         */
        val granuleSize: Float = 0.40f,

        /** 纹样种子 */
        val seed: Long = 20260922L,
        /** 中心孔占半径的比例 */
        val holeRatio: Float = 0.028f,
        /** 标签占半径的比例 */
        val labelRatio: Float = 0.30f,
        /** 是否画唱片反光（斜向柔光）。真实黑胶在灯下有这道光 */
        val glossy: Boolean = true
    ) {
        val splatColors: List<Int> get() = splat.map { it.color }
    }

    /**
     * 流动剪切指数 P。
     *
     * 固定 2.0 而不是做成旋钮：它不是"风格"，是模腔几何决定的。
     * 1 = 理想均匀扩张（所有条纹等长等宽，一眼假），2 = 观感对得上实物。
     */
    private const val FLOW_SHEAR = 2.0f

    /** 一条彩料的采样点数。12 点足够让锥度看起来是平滑的 */
    private const val JET_SAMPLES = 12

    /** 内圈最容易糊成一坨，切向半宽不超过投料区半径的这个比例 */
    private const val MAX_HALF_WIDTH = 0.55f

    // ---- 沟槽（凹痕）----

    /** 沟槽间距占成品半径的比例 */
    private const val GROOVE_PITCH = 0.024f

    /**
     * 最小特征小于这么多**像素**就不画线。
     *
     * ★★ 这条是**防摩尔纹的关键**：等距同心圆一旦密到接近像素周期，就会和
     *   像素栅格拍出摩尔纹 —— 一圈圈乱纹比没有沟槽还难看，而且每换一个
     *   尺寸又是一套不同的花纹（那才是最糟的：同一张盘在列表里和在播放页
     *   长得不一样）。
     *
     * 拿两档实际尺寸验一下（手机 density 3.5）：
     *   · 列表 44dp = 154px → r≈77 → 间距 1.85px → 特征 0.9px  ✗ 不画线
     *   · 缩略图 192px     → r=96 → 间距 2.30px → 特征 1.15px ✗ 不画线
     *   · 大图 640px       → r=320 → 间距 7.68px → 特征 3.84px ✓ 画
     */
    private const val MIN_GROOVE_FEATURE_PX = 2.0f

    /** 刻痕的暗面 / 亮面。**都没有自己的颜色**，只压暗或提亮底下 */
    private const val GROOVE_DARK_ALPHA = 30
    private const val GROOVE_LIGHT_ALPHA = 34

    /** 画不出线时，「沟槽区」那圈淡环的浓度 */
    private const val GROOVE_FLAT_ALPHA = 14

    /** 内置预设 */
    val CLASSIC = Style()

    val PRESETS = listOf(
        // 经典黑胶：红标黑盘，无彩料
        CLASSIC,

        // 粉底 + 蓝紫放射
        Style(
            baseColor = 0xFFF2AFC0.toInt(),
            labelColor = 0xFFE8A0B8.toInt(),
            labelAccentColor = 0xFFFFFFFF.toInt(),
            splat = listOf(
                Splat(0xFF1B2E8C.toInt(), 1.4f),   // 深蓝
                Splat(0xFF3D5AC4.toInt(), 1.0f),   // 中蓝
                Splat(0xFF7B3FA8.toInt(), 1.1f),   // 紫
                Splat(0xFFB07BD0.toInt(), 0.6f),   // 浅紫
            ),
            chargeRatio = 0.26f, count = 100, granuleSize = 0.28f, seed = 7L
        ),

        // 绿金
        Style(
            baseColor = 0xFF0D2B1E.toInt(),
            labelColor = 0xFFFFB300.toInt(),
            splat = listOf(
                Splat(0xFF66BB6A.toInt(), 1.2f),
                Splat(0xFFFDD835.toInt(), 0.9f),
                Splat(0xFF26A69A.toInt(), 0.7f),
            ),
            chargeRatio = 0.32f, count = 80, granuleSize = 0.34f, seed = 21L
        ),

        // 米白底 + 蓝红
        Style(
            baseColor = 0xFFF5F0E6.toInt(),
            labelColor = 0xFF1565C0.toInt(),
            labelAccentColor = 0xFF0D1B2A.toInt(),
            splat = listOf(
                Splat(0xFF1565C0.toInt(), 1.1f),
                Splat(0xFFE53935.toInt(), 0.9f),
                Splat(0xFF283593.toInt(), 0.6f),
            ),
            chargeRatio = 0.28f, count = 90, granuleSize = 0.30f, seed = 99L
        ),

        // 黑底三色，高对比
        Style(
            baseColor = 0xFF2A0A0A.toInt(),
            labelColor = 0xFF37474F.toInt(),
            splat = listOf(
                Splat(0xFFE53935.toInt(), 1.0f),
                Splat(0xFFFF7043.toInt(), 0.9f),
                Splat(0xFFFFCA28.toInt(), 0.8f),
            ),
            chargeRatio = 0.24f, count = 110, granuleSize = 0.26f, seed = 404L
        ),

        /*
         * 透明盘 + 红白撒料。
         *
         * ★ 这是「透明」这个维度的示例：盘底只有 27% 不透明，能透过它看见
         *   底下的界面 —— 就是真实彩胶里 Clear / Ultra Clear 那一类的样子。
         *   白料比红料更透，红压在白上、白又压在红上，两种颜色互相透出来
         *   （Color-in-color 的入门版）。
         */
        Style(
            baseColor = 0xFFDCE6EE.toInt(),
            baseAlpha = 70,
            labelColor = 0xFFC62828.toInt(),
            labelAccentColor = 0xFFF5F0E6.toInt(),
            splat = listOf(
                Splat(0xFFD32F2F.toInt(), 1.4f, 235),   // 红：基本不透明
                Splat(0xFFFAFAFA.toInt(), 1.0f, 190),   // 白：半透，透出下面的红
            ),
            chargeRatio = 0.27f, count = 95, granuleSize = 0.30f, seed = 777L
        ),
    )

    // ------------------------------------------------------------------
    //  绘制
    // ------------------------------------------------------------------

    /** 成品。等价于压制进行到底 */
    fun render(size: Int, style: Style = CLASSIC): Bitmap = renderPress(size, style, 1f)

    /**
     * 压制到 [progress] 时的样子（0 = 刚投料，1 = 成品）。
     *
     * ★ 标签是**压完之后才贴**的，所以中途的帧上没有标签、也没有中心孔 ——
     *   这正是工艺的第 ④ 步。设计页的压制动画靠它才看得出工艺顺序：
     *   先是中心一坨料被摊开、拉长，最后标签才落到中间。
     *
     * 同一个 (style, progress) 永远画出同一张图（种子驱动），
     * 所以动画每帧重跑一遍模拟也不会闪。
     */
    fun renderPress(size: Int, style: Style, progress: Float): Bitmap {
        val s = size.coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        renderPressInto(bmp, style, progress)
        return bmp
    }

    /**
     * 把某一帧画进**已有的**位图。
     *
     * 压制动画一秒要出三四十帧，每帧新建一张 640×640 的位图就是 1.6MB ——
     * 一秒上百兆的垃圾，GC 会把动画拖出锯齿。动画那边用两张位图轮换就够。
     */
    fun renderPressInto(target: Bitmap, style: Style, progress: Float) {
        target.eraseColor(Color.TRANSPARENT)
        val s = min(target.width, target.height)
        val c = Canvas(target)
        val cx = s / 2f
        val cy = s / 2f
        val r = s / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val t = progress.coerceIn(0f, 1f)

        drawBase(c, p, cx, cy, r, style)
        drawSplatter(c, cx, cy, r, style, t)
        drawGrooves(c, p, cx, cy, r, style)

        // 贴标：最后 12% 的进度里淡入，并有一个"放下去"的缩放。
        // 这一步单独留出来，是为了让动画能明确讲出"标签是最后贴的"
        val labelT = ((t - 0.88f) / 0.12f).coerceIn(0f, 1f)
        if (labelT > 0f) {
            drawLabel(c, p, cx, cy, r, style, labelT)
            drawHole(c, p, cx, cy, r, style, labelT)
        }

        if (style.glossy) drawGloss(c, p, cx, cy, r, t)
    }

    /** 盘面。用径向渐变 —— 纯平色看起来像纸片，有一点点明暗才有"实体感" */
    private fun drawBase(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, st: Style) {
        /*
         * ★ 盘底的不透明度。0 就是全透 —— 那连"盘"都不存在了，
         *   直接不画，省掉一次四十万像素的覆盖。
         *   还有沟槽、反光和中心标会画上去，所以不是一片空白。
         */
        val a = st.baseAlpha.coerceIn(0, 255)
        if (a == 0) return

        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(lighten(st.baseColor, 0.10f), st.baseColor, darken(st.baseColor, 0.18f)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        // alpha 在 shader 之上再乘一层 —— 径向渐变自己是原样保留的
        p.alpha = a
        c.drawCircle(cx, cy, r, p)
        p.alpha = 255
        p.shader = null
    }

    // ------------------------------------------------------------------
    //  压制模拟
    // ------------------------------------------------------------------

    /**
     * 把彩料从中心压开，并画出来。
     *
     * 这里**没有**"条纹长度""条纹宽度"这类参数 —— 只有投料位置、颗粒大小，
     * 和一个把半径映射出去的流动函数。形状全是算出来的。
     */
    private fun drawSplatter(
        c: Canvas, cx: Float, cy: Float, r: Float,
        st: Style, progress: Float
    ) {
        if (st.splat.isEmpty() || st.count <= 0 || progress <= 0.001f) return

        val rnd = Random(st.seed)
        val chargeR = st.chargeRatio.coerceIn(0.05f, 0.6f)

        // ── 压制进度：半径倍率和剪切指数一起从"没压"长到"压满" ──
        // ease 用二次缓出：液压机合模是前期快、后期慢（接触面积变大、压强下降）
        val e = easeOutQuad(progress)
        val amp = chargeR + (1f - chargeR) * e          // 盘的半径倍率
        val shear = 1f + (FLOW_SHEAR - 1f) * e          // P，从 1 长到 FLOW_SHEAR

        // ── 按份量把颗粒数摊到各颜色 ──
        val palette = distributeColors(st, rnd)
        // ★ 绘制顺序打乱：真实彩胶是一层压一层，后流的盖住先流的。
        //   按颜色分组顺序画的话，某种颜色会永远在最上层，一眼假
        palette.shuffle(rnd)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL

        // 画到盘缘为止。真实的料会溢出到模腔外，修边时被切掉 ——
        // 所以这里就是硬切，不做羽化
        c.save()
        c.clipPath(Path().apply { addCircle(cx, cy, r * 0.995f, Path.Direction.CW) })

        for (ci in palette) {
            p.color = st.splat[ci].color
            /*
             * 两层透明度**相乘**：
             *
             *   · 用户给的 [Splat.alpha] —— 「这种料本身有多透」，
             *     半透的料压在别的颜色上，下层就透出来了（色中色的来源）
             *   · 每块料一点点随机抖动 —— 颜料叠在一起时能透出下层，
             *     真实感全靠这个
             *
             * ★ 是**乘**不是覆盖：alpha 取 255 时，225~254 的抖动原样保留，
             *   和老版本**逐位相同**（改动前这里就是直接写这个抖动值）。
             */
            p.alpha = st.splat[ci].alpha.coerceIn(0, 255) * (225 + rnd.nextInt(30)) / 255

            /*
             * 每条彩料的三个初始量。
             *
             * · 起点：偏向中心（指数 1.6）。投料是把料**堆**在中间，
             *   不是均匀铺满投料区，所以靠近中心的地方更密
             * · 长度：指数 2.2 的幂律 —— 短的多、长的少。
             *   甩出去时绝大多数是碎点，少数拉成长条，实物就是这样
             * · 粗细：再乘一个随机系数，让颗粒本身也有大小差
             */
            val theta0 = rnd.nextFloat() * TWO_PI
            val x0 = chargeR * (0.12f + 0.86f * rnd.nextFloat().pow(1.6f))
            /*
             * 长度分布：带一个下限的幂律。
             *
             * 纯 u^2.2 的话绝大多数条纹都短，全堆在中间一圈，看着像撒了一把
             * 碎屑而不是"喷"出来的。真实甩料里短点子和长条是混着的，
             * 长条能跨过盘面的一大半 —— 加个下限就把长条的比例补回来了。
             */
            val len0 = chargeR * (0.06f + 1.24f * rnd.nextFloat().pow(1.9f))
            // 幂律而不是均匀：真实彩料是筛出来的，小碎块占绝大多数，
            // 偶尔有一大块。均匀分布画出来所有条纹一样粗，就失去层次了
            val w0 = chargeR * st.granuleSize * (0.12f + 1.4f * rnd.nextFloat().pow(2f))

            drawJet(c, p, cx, cy, r, st, chargeR, amp, shear, e, theta0, x0, len0, w0)
        }
        c.restore()
    }

    /**
     * 画一条彩料。
     *
     * 沿它的**材料坐标** x0 采样：每个采样点独立地映射到成品半径、
     * 独立地算拉伸率。所以一条彩料的两端会因为 λ 不同而一粗一细 ——
     * 这不是给带子做了个渐变，是每一段各自被压成了不同的宽度。
     */
    private fun drawJet(
        c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, st: Style,
        chargeR: Float, amp: Float, shear: Float, e: Float,
        theta0: Float, x0: Float, len0: Float, w0: Float
    ) {
        // 太细的画不出来，但给个下限就行 —— 不为 0 是为了避免锯齿
        val minHalf = 0.32f / r

        val xs = FloatArray(JET_SAMPLES)
        val ys = FloatArray(JET_SAMPLES)
        val hw = FloatArray(JET_SAMPLES)
        // 每个采样点处的切向单位向量。算位置时顺手存下来 ——
        // 后面连带的法向就是它，不必再用 (x0, len0, i) 反推一遍角度
        val nx = FloatArray(JET_SAMPLES)
        val ny = FloatArray(JET_SAMPLES)
        var n = 0

        for (i in 0 until JET_SAMPLES) {
            val u = i / (JET_SAMPLES - 1f)
            val xx0 = x0 + len0 * u
            if (xx0 > chargeR) break

            // 流动映射：投料区里的位置 → 成品上的半径
            val q = xx0 / chargeR
            val X = amp * q.pow(shear)
            if (X > 0.999f) break                  // 修边切掉

            // 局部拉伸率 = 映射的导数
            val lambda = amp * shear / chargeR * q.pow(shear - 1f)

            /*
             * 切向半宽 = 初始半宽 / λ，面积守恒。
             *
             * 初始半宽还乘了 (1-u)^0.7：甩出去的料流根部连着料堆、末端自由，
             * 截面本来就是从粗到细的锥形（质量守恒 + 前端速度更快）。
             */
            // (1-u) 的一次方在 u=0 处导数不为 0，内端会是尖的；
            // 真实料流的内端连着料堆，是圆钝的。改这个形状让内端平缓、
            // 只在末段收尖
            val taper = (1f - u.pow(1.6f)).pow(0.55f)
            val h = (w0 * taper / lambda).coerceIn(minHalf, chargeR * MAX_HALF_WIDTH)

            /*
             * ★★ 这里原来有一项角向漂移 `omega * turbulence * e * q` ——
             *   就是它让条纹弯成弧的。**已经去掉了**（用户 2026-10-01 定），
             *   所以现在每条彩料都是**笔直的放射线**，角度恒等于投料时的 theta0。
             *
             *   去掉的理由：那一项调大了会把条纹扭到接近切向、糊成横杠，
             *   调到 0 又完全没有弯 —— 好用的区间很窄，而且真实彩胶的撒料
             *   本来多数就是直的放射状。留着它只是给"调坏"提供机会。
             */
            val th = theta0

            val rr = X * r
            xs[n] = cx + cos(th) * rr
            ys[n] = cy + sin(th) * rr
            // 切向单位向量。宽度是**切向**的，所以带子沿它向两侧摊开
            nx[n] = -sin(th)
            ny[n] = cos(th)
            hw[n] = h * r
            n++
        }

        if (n < 2) return

        /*
         * 连成一条带：沿中心线两侧各偏移半个切向宽度。
         *
         * 因为 X、th、宽度都是逐点算的，带子自然跟着流动的方向走 ——
         * 外端窄、内端宽、跟着流场弯，都是逐点算出来的结果，
         * 不是对一条预设的带子做形变。
         */
        val path = Path()
        path.moveTo(xs[0] + nx[0] * hw[0], ys[0] + ny[0] * hw[0])
        for (i in 1 until n) path.lineTo(xs[i] + nx[i] * hw[i], ys[i] + ny[i] * hw[i])
        for (i in n - 1 downTo 0) path.lineTo(xs[i] - nx[i] * hw[i], ys[i] - ny[i] * hw[i])
        path.close()

        c.drawPath(path, p)
    }

    private const val TWO_PI = 2f * PI.toFloat()

    /*
     * ★★ 这里原来有整套「角向速度场」：`Harmonics` + `buildHarmonics()` +
     *   `fieldAt()`，几个低阶正弦谐波叠加成一个 ω(θ)，用来让**相邻的彩料
     *   一起弯**（比逐条随机抖动更像实物）。
     *
     *   它随着「流动扰动」一起**删掉了**（用户 2026-10-01 定）——
     *   漂移项清零之后这三个东西就没有调用点了。
     *   要恢复弯曲的话，从这里开始重建。
     */

    /**
     * 按份量把颗粒数摊到各颜色。
     *
     * 用最大余数法补足被取整丢掉的名额 —— 直接四舍五入的话，
     * count 小的时候某种颜色会明显偏少，而用户明明给它调了大份量。
     */
    private fun distributeColors(st: Style, rnd: Random): MutableList<Int> {
        val totalW = st.splat.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        val exact = st.splat.map { st.count * it.weight / totalW }
        val base = exact.map { it.toInt() }
        val out = ArrayList<Int>(st.count)
        for (i in exact.indices) repeat(base[i]) { out.add(i) }
        val rest = exact.indices.sortedByDescending { exact[it] - base[it] }
        var k = 0
        while (out.size < st.count) {
            out.add(rest[k % rest.size])
            k++
        }
        return out
    }

    private fun easeOutQuad(t: Float): Float = 1f - (1f - t) * (1f - t)

    // ------------------------------------------------------------------

    /**
     * 同心纹路。
     *
     * ★★ 它**不是画上去的线，是刻上去的痕**。这两者的区别就是这个函数的全部：
     *
     *   旧版是在成品盘面上再画一圈圈**固定颜色**的细线，颜色按底色的明暗
     *   **二选一**（深底画白线、浅底画黑线）。只要盘面上有彩料，**总有一半
     *   的地方选错**：白线压在白料上等于没画，压在同一条白料旁边的黑底上
     *   又像贴了张网 —— 花纹和纹路各走各的，这就是「不匹配」。
     *
     *   现在每条沟槽画成**一暗一明的一对**：暗线压暗底下、亮线提亮底下。
     *   两者都只是半透明的黑白，**自己没有颜色** —— 所以底下的图案会从
     *   沟槽里透出来，纹路是长在图案里的。
     *
     * ★★ 而且必须**防摩尔纹**：缩略图上间距只有一两个像素，等距同心圆会
     *   织成一片乱纹，比没有沟槽还难看。所以最小特征小于阈值时**一条线都
     *   不画**，改画一圈极淡的「沟槽区」—— 保留「这是张唱片」的暗示，
     *   且完全不摩尔纹。
     *
     * ★ 画在彩料**之上**、中心标**之下** —— 这和工艺顺序一致：先压出图案，
     *   再刻沟槽，最后贴标。
     */
    private fun drawGrooves(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, st: Style) {
        val from = r * (st.labelRatio + 0.035f)
        val to = r * 0.985f
        if (to <= from) return

        val pitch = r * GROOVE_PITCH        // 沟槽间距
        val feature = pitch * 0.5f          // 最小特征 = 亮暗两线之间

        p.style = Paint.Style.STROKE
        p.shader = null

        if (feature < MIN_GROOVE_FEATURE_PX) {
            // 太密了 —— 画「沟槽区」而不是沟槽。一条很宽的环形描边，正好盖住那一片
            p.strokeWidth = to - from
            p.color = withAlpha(0xFF000000.toInt(), GROOVE_FLAT_ALPHA)
            c.drawCircle(cx, cy, (from + to) / 2f, p)
            return
        }

        // 线宽跟着间距走，但至少 0.7px —— 再细抗锯齿会把它抹没
        p.strokeWidth = (pitch * 0.30f).coerceAtLeast(0.7f)

        /*
         * 暗线先、亮线后。
         *
         * 顺序有讲究：亮线画在后面，压在暗线的边上 —— 看起来像光从一侧来，
         * 刻痕的同一侧永远受光。反过来刻痕的受光面会朝里，整张盘会像凹下去。
         */
        var gr = from
        p.color = withAlpha(0xFF000000.toInt(), GROOVE_DARK_ALPHA)
        while (gr < to) {
            c.drawCircle(cx, cy, gr, p)
            gr += pitch
        }

        gr = from + feature
        p.color = withAlpha(0xFFFFFFFF.toInt(), GROOVE_LIGHT_ALPHA)
        while (gr < to) {
            c.drawCircle(cx, cy, gr, p)
            gr += pitch
        }
    }

    /**
     * 中心标签 —— 一张**贴上去的纸**。
     *
     * [t] 是贴标进度 0~1，用来做淡入和轻微缩放。真实的贴标是机械臂把纸片
     * 放下去，所以这里让它从略大缩到正常 —— 比突兀地出现自然得多。
     *
     * 另外标签**不是完美同心的**：真实的贴标有零点几毫米的偏心，
     * 这个细节几乎没人会注意到，但少了它整张盘会有种说不出的"太整齐"。
     */
    private fun drawLabel(
        c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, st: Style, t: Float
    ) {
        val (lx, ly) = labelCenter(cx, cy, r, st)
        val lr = r * st.labelRatio * (1.14f - 0.14f * t)

        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            lx, ly, lr,
            intArrayOf(lighten(st.labelColor, 0.16f), st.labelColor, darken(st.labelColor, 0.10f)),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        p.alpha = (255 * t).toInt().coerceIn(0, 255)
        c.drawCircle(lx, ly, lr, p)
        p.shader = null

        // 纸标的边缘：一圈细白线，加一道极淡的投影让它"浮"在盘面上
        p.style = Paint.Style.STROKE
        p.strokeWidth = (r * 0.003f).coerceAtLeast(0.7f)
        p.color = withAlpha(st.labelAccentColor, (150 * t).toInt())
        c.drawCircle(lx, ly, lr * 0.955f, p)
        c.drawCircle(lx, ly, lr * 0.42f, p)

        p.strokeWidth = (r * 0.004f).coerceAtLeast(0.8f)
        p.color = withAlpha(0xFF000000.toInt(), (46 * t).toInt())
        c.drawCircle(lx, ly, lr * 1.008f, p)

        p.alpha = 255
    }

    private fun drawHole(
        c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, st: Style, t: Float
    ) {
        val (lx, ly) = labelCenter(cx, cy, r, st)
        val hr = r * st.holeRatio

        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        p.alpha = (255 * t).toInt().coerceIn(0, 255)
        c.drawCircle(lx, ly, hr, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = (r * 0.002f).coerceAtLeast(0.6f)
        p.color = withAlpha(0xFFFFFFFF.toInt(), (70 * t).toInt())
        c.drawCircle(lx, ly, hr, p)
        p.alpha = 255
    }

    /** 标签的圆心。带一点偏心 —— 硬件贴标做不到完美同心 */
    private fun labelCenter(cx: Float, cy: Float, r: Float, st: Style): Pair<Float, Float> {
        val rnd = Random(st.seed * 31 + 977)
        val a = rnd.nextFloat() * TWO_PI
        val d = r * 0.011f * rnd.nextFloat()
        return (cx + cos(a) * d) to (cy + sin(a) * d)
    }

    /**
     * 斜向柔光。真实黑胶在灯下有这道反光，加了它才有"盘"的立体感。
     *
     * 压制途中反光弱一些 —— 料还没压实，表面不平，反光本来就散。
     */
    private fun drawGloss(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, t: Float = 1f) {
        val k = 0.45f + 0.55f * t
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(
                0x00FFFFFF,
                scaleAlpha(0x18FFFFFF, k),
                0x00FFFFFF,
                0x00FFFFFF,
                scaleAlpha(0x10FFFFFF, k),
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.28f, 0.46f, 0.62f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, p)
        p.shader = null
    }

    // ------------------------------------------------------------------

    /*
     * ★ 这里原来有个 `grooveColor(base)`：按底色的明暗在「白线」和「黑线」里
     *   **二选一**。它已经删掉了 —— 只要盘面上有彩料，二选一就必然有一半选错
     *   （见 [drawGrooves]）。现在沟槽是一暗一明的一对，不需要挑颜色。
     */

    private fun scaleAlpha(color: Int, k: Float): Int =
        withAlpha(color, (Color.alpha(color) * k).toInt().coerceIn(0, 255))

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun lighten(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * f).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * f).toInt().coerceIn(0, 255)
    )

    private fun darken(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) * (1 - f)).toInt().coerceIn(0, 255),
        (Color.green(color) * (1 - f)).toInt().coerceIn(0, 255),
        (Color.blue(color) * (1 - f)).toInt().coerceIn(0, 255)
    )

    /** 把方图裁成圆，给缩略图用 */
    fun circleCrop(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        c.drawCircle(r, r, r, p)
        p.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        c.drawBitmap(src, null, RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
        return out
    }

    // ------------------------------------------------------------------
    //  序列化 —— 用户自己设计的盘要能存下来
    // ------------------------------------------------------------------

    fun toJson(st: Style): String = JSONObject().apply {
        put("base", st.baseColor)
        put("baseA", st.baseAlpha)
        put("label", st.labelColor)
        put("accent", st.labelAccentColor)
        put("charge", st.chargeRatio.toDouble())
        put("count", st.count)
        put("granule", st.granuleSize.toDouble())
        put("seed", st.seed)
        put("hole", st.holeRatio.toDouble())
        put("labelR", st.labelRatio.toDouble())
        put("glossy", st.glossy)
        put("splat", JSONArray().apply {
            st.splat.forEach {
                put(JSONObject().apply {
                    put("c", it.color)
                    put("w", it.weight.toDouble())
                    put("a", it.alpha)
                })
            }
        })
    }.toString()

    fun fromJson(json: String): Style? = runCatching {
        val o = JSONObject(json)

        /*
         * 老版本存的 "reach"（扩散长度）换算成 "charge"（投料区半径）。
         *
         * 两者的语义相反：reach 越大条纹越长，charge 越小拉伸越大。
         * 不做这个换算的话，用户之前存下来的自定义彩胶会全部退回默认值 ——
         * 之前的设计等于白做。反比映射能大致还原当时的观感。
         */
        val charge = if (o.has("charge")) {
            o.optDouble("charge", CLASSIC.chargeRatio.toDouble()).toFloat()
        } else if (o.has("reach")) {
            (0.50f - 0.25f * o.optDouble("reach", 0.75).toFloat()).coerceIn(0.15f, 0.50f)
        } else {
            CLASSIC.chargeRatio
        }

        val splats = ArrayList<Splat>()
        o.optJSONArray("splat")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                // ★ "a" 读不到就是 255 —— 加这个字段**之前**存的盘原样还原，
                //   用户之前调好的设计一条都不会变
                splats += Splat(
                    s.optInt("c"),
                    s.optDouble("w", 1.0).toFloat(),
                    s.optInt("a", 255)
                )
            }
        }

        Style(
            baseColor = o.optInt("base", CLASSIC.baseColor),
            // 同上：老的自定义盘没有 "baseA"，读不到就是 255（不透明）
            baseAlpha = o.optInt("baseA", CLASSIC.baseAlpha),
            labelColor = o.optInt("label", CLASSIC.labelColor),
            labelAccentColor = o.optInt("accent", CLASSIC.labelAccentColor),
            splat = splats,
            chargeRatio = charge,
            count = o.optInt("count", CLASSIC.count),
            granuleSize = o.optDouble("granule", CLASSIC.granuleSize.toDouble()).toFloat(),
            // ★ 老 JSON 里的 "turb" 直接忽略 —— 那个字段已经不存在了，
            //   忽略未知键是 org.json 的默认行为，不用特意处理
            seed = o.optLong("seed", CLASSIC.seed),
            holeRatio = o.optDouble("hole", CLASSIC.holeRatio.toDouble()).toFloat(),
            labelRatio = o.optDouble("labelR", CLASSIC.labelRatio.toDouble()).toFloat(),
            glossy = o.optBoolean("glossy", true)
        )
    }.getOrNull()
}
