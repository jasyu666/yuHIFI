package com.hifiprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 列表封面 —— 那一张小圆图。
 *
 * ★ 抽成进程级单例，是因为音乐库、队列、所有歌曲、专辑四个页面都要它。
 *   之前在两处各抄了一份，各自持一个 LruCache —— 同一首歌从曲库切到队列
 *   要重新解码一次，白白浪费。这里是**一份缓存**，跨页面命中。
 *
 * ★ 缓存键必须带上 uri。没有内嵌封面的曲目 coverPath 是空串，只用它做键
 *   的话，库里所有无封面曲目会共用同一个键，全部显示成同一张黑胶。
 *   —— 这个坑踩过一次了。
 *
 * ★ 尺寸也进键：迷你条要 96px、列表要 52px，同尺寸才能复用。
 *
 * 没有内嵌封面就现画一张黑胶，种子按曲目取 —— 每首都有自己的纹样，
 * 一排看过去像一叠唱片，而不是同一张图的复印件。
 *
 * ══ 两条入口：同步 [get] 与异步 [load] ══
 *
 * 一条封面要么**解码一张 jpg**（`BitmapFactory.decodeFile`），要么**现画一张黑胶**
 * （`VinylArt.render` 要跑上百次 canvas 绘制）—— 两样都是毫秒级的 CPU 活。
 * 放在 `onBindViewHolder` 里同步做，一屏十行就是十次；滚动时这些活全压在
 * 主线程上，帧预算（16ms）一下就被吃光。**列表一律走 [load]，只有"全屏就这一张"
 * 的地方走 [get]**（见各自的说明）。
 */
object CoverLoader {

    private const val TAG = "HiFiCover"

    /** 单张封面超过这个耗时就往日志里记一笔。**只报慢的**，不然一次滚动几百行 */
    private const val SLOW_COVER_MS = 16L

    /**
     * 缓存上限，**按字节算**。
     *
     * ★ 不能按"张数"算：这里的图尺寸差一个数量级 —— 列表缩略图 52dp
     *   （约 182px，0.13MB），专辑网格 601px（1.44MB），正在播放页的大封面
     *   280dp（约 980px，3.8MB）。按张数限制的话，翻过 96 首歌就是几百 MB。
     *
     * ★ 48MB 是量出来的，不是拍的：专辑页一屏 8 张 × 1.44MB ≈ 12MB，
     *   回滚一屏还要留住上一屏 —— 24MB 滚两屏就冲干净，于是每一屏都在重解。
     *   48MB 能装下约 33 张专辑封面 / 360 张列表缩略图，够覆盖来回滚的窗口。
     *   （真要装下整个专辑库得上百 MB，那不是缓存该干的事 —— 靠把单张解码
     *    成本压到 1~3ms，让"没命中"这件事本身不再疼。）
     */
    private const val MAX_BYTES = 48 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * 解码用的线程池 —— **特意只有一条线程**。
     *
     * · 不占主线程：理由见文件头。
     * · 也不丢进公共池、更不丢进解码线程：解码线程的活儿是喂满环形缓冲，
     *   欠载是听得见的；封面晚 30ms 出现，没人听得出来。一条线程还顺带保证了
     *   **同一张封面不会被并发解两次**。
     * · 优先级调低一档同理：让路给音频。
     *
     * ★ `isDaemon` —— 别让它吊住进程退出。
     */
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cover").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val ui = Handler(Looper.getMainLooper())

    /**
     * 每个 ImageView **最近一次**要的那张封面。
     *
     * ★★ 两个用途，缺一不可：
     *
     *   1. **回来晚了就丢掉** —— ViewHolder 是复用的，一张 40ms 才解完的图
     *      回来时，那个位置早就换人了。不用这个比对，滚动时就会看到
     *      "上一行的封面贴在这一行上"。
     *   2. **还没轮到就别做了** —— 快速滑过几十行会瞬间排几十个任务，
     *      挨个解完纯属白烧 CPU 和电。开工前先看一眼：已经不是这一行了，跳过。
     *
     * 用 `ImageView` 本身做键而不是往 View 上挂 tag：读 tag 要在主线程
     * （View 不是线程安全的），而这份判断要在后台线程上做。
     */
    private val wanted = ConcurrentHashMap<ImageView, String>()

    // ------------------------------------------------------------------
    //  磁盘缩略图
    // ------------------------------------------------------------------

    /**
     * 缩略图档位（像素边长）。
     *
     * ★★ 为什么非要有它 —— 实测数字说话：
     *
     *   内嵌封面是 **3000×3000 ~ 4000×4000**（见 covers/ 下的母版），
     *   而列表那行只要 182px。就算 [sampleSizeFor] 已经把解码量砍到 1/4，
     *   一张仍要 **20~54 ms**；一屏八张专辑就是 ~280ms 的排队，
     *   于是**帧率是满的、图却比手指慢半拍** —— 用户说的"卡"就是这个。
     *
     *   缩略图把"每显示一次解一次大图"变成"第一次解一次、之后读小图"：
     *   182px 从 20~54ms 降到 **~1ms**，601px 降到 **~3ms**。
     *
     * ★ 档位**固定成几个值**，而不是按 sizePx 精确生成 ——
     *   只有档位固定，不同调用点（列表 182px、专辑 601px）才可能命中同一个文件。
     *
     * ★ 只到 640：再大的只有正在播放页那一张（280dp ≈ 980px）。
     *   它一屏就一张、不在滚动路径上，为它多存一档不划算
     *   （1676 首 × 150KB ≈ 250MB）。**超过 640 的请求直接从母版解，不落盘。**
     */
    private val TIERS = intArrayOf(192, 640)

    /** 这个尺寸该用哪一档；**0 表示不落盘**，直接从母版解 */
    private fun tierFor(sizePx: Int): Int = TIERS.firstOrNull { sizePx <= it } ?: 0

    @Volatile
    private var thumbsDir: File? = null

    /**
     * 由 [App.onCreate] 调一次 —— **必须在任何列表 bind 之前**。
     *
     * ★ 和 [Library.coversDir] 用同一个根、挂在它下面：两者是配套的，
     *   母版被清掉时缩略图要跟着一起清（见 `Library.pruneCovers`）。
     */
    fun init(ctx: Context) {
        if (thumbsDir != null) return
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        thumbsDir = File(base, "covers/thumbs").apply { runCatching { mkdirs() } }
    }

    /**
     * 某张母版对应的所有缩略图文件名。
     *
     * ★ 命名规则**只有这一处** —— [Library.pruneCovers] 靠它算"哪些缩略图还活着"，
     *   两处各写一份的话，改名那天就会开始误删。
     */
    fun thumbNamesFor(masterName: String): List<String> =
        TIERS.map { "${masterName}_$it.jpg" }

    /** 缩略图落在哪。**按母版文件名取名** —— 同一张母版永远对应同一个缩略图 */
    private fun thumbFileFor(master: File, tier: Int): File? =
        thumbsDir?.let { File(it, "${master.name}_$tier.jpg") }

    // ------------------------------------------------------------------
    //  同步：全屏就这一张的地方用
    // ------------------------------------------------------------------

    /**
     * ★ 什么时候该用这个而不是 [load]：
     *
     *   正在播放页的大封面（一屏只有它）、首页播放条（同上）。
     *   这些地方**一次只解一张**，而且都是回看率极高的（切歌、暂停、恢复都会
     *   重画），同步拿反而更好 —— 异步会让封面"闪一下才出来"，比多花几毫秒难受。
     */
    fun get(ctx: Context, t: Track, sizePx: Int): Bitmap {
        val base = Settings.currentVinyl(ctx)
        val key = keyOf(t, sizePx, base)
        cache.get(key)?.let { return it }
        val out = decodeOrRender(t, sizePx, base)
        cache.put(key, out)
        return out
    }

    // ------------------------------------------------------------------
    //  异步：列表用
    // ------------------------------------------------------------------

    /**
     * 给列表的一行贴封面：命中缓存立刻贴，没命中先贴占位图、解好了再换。
     *
     * ★ 占位图不是可选项。不先清掉的话，ViewHolder 复用时会短暂显示**上一行**的
     *   封面 —— 比空白更像"数据错了"。
     */
    fun load(ctx: Context, t: Track, sizePx: Int, into: ImageView) {
        // 样式在主线程取一次：它既是键的一部分，也是画黑胶的参数，
        // 两边必须来自同一次读取
        val base = Settings.currentVinyl(ctx)
        val key = keyOf(t, sizePx, base)

        cache.get(key)?.let {
            wanted.remove(into)
            into.setImageBitmap(it)
            return
        }

        wanted[into] = key
        into.setImageResource(R.drawable.bg_cover_empty)

        exec.execute {
            // 还没轮到就已经滚走了 —— 这次解码根本不需要发生
            if (wanted[into] != key) return@execute

            val bmp = decodeOrRender(t, sizePx, base)
            cache.put(key, bmp)

            ui.post {
                // ★★ 回来的时候这一行还得是它。`remove` 顺带把登记清掉，
                //    不然这张 ImageView 会被一直强引用着
                if (wanted.remove(into) == key) into.setImageBitmap(bmp)
            }
        }
    }

    /**
     * 放弃这个 ImageView 上还没回来的那张。
     *
     * ★ 场景：歌单列表里，某一行上一帧还在异步取封面，这一帧变成"一首有效曲目
     *   都没有"、直接**同步**贴了一张按歌单 id 画的占位黑胶。不同步注销的话，
     *   那条已经没有意义的异步结果回来时会把它盖掉。
     */
    fun cancel(into: ImageView) {
        wanted.remove(into)
    }

    // ------------------------------------------------------------------

    /**
     * 缓存键。**全 App 只有这一处拼键** —— 键不一致的后果是缓存永不命中，
     * 而且是静默的：功能照常，只是每次 bind 都重解一遍。
     */
    private fun keyOf(t: Track, sizePx: Int, base: VinylArt.Style): String {
        /*
         * ★ 键里**必须带上样式**。
         *
         *   之前只用 "封面路径或 uri + 尺寸" 做键，于是用户改了「默认封面样式」
         *   之后，页面上已经画过的那些盘还是旧样式 —— 缓存命中就直接返回了，
         *   根本不会重画。表现就是"封面不跟设置走"，而且要等进程重启才恢复。
         *
         *   内嵌封面（coverPath 非空）不参与绘制，不带样式也能复用，
         *   所以只在现画黑胶这条路上加，免得改一次样式就把真封面也全部重解码。
         */
        return if (t.coverPath.isNotEmpty()) {
            "${t.coverPath}@$sizePx"
        } else {
            "v:${t.uri}@$sizePx@${base.hashCode()}"
        }
    }

    /** 有内嵌封面就解它，没有就现画一张黑胶 */
    private fun decodeOrRender(t: Track, sizePx: Int, base: VinylArt.Style): Bitmap {
        if (t.coverPath.isNotEmpty()) {
            decodeCover(t.coverPath, sizePx)?.let { return it }
        }
        val t0 = SystemClock.elapsedRealtime()
        val out = VinylArt.render(sizePx, base.copy(seed = vinylSeedFor(t, base.seed)))
        val cost = SystemClock.elapsedRealtime() - t0
        if (cost >= SLOW_COVER_MS) Log.w(TAG, "现画黑胶偏慢：${sizePx}px，耗时 ${cost} ms")
        return out
    }

    /**
     * 取一张内嵌封面，**优先读缩略图**。
     *
     * 三级：
     *   1. 缩略图在 → 直接解它（192 档 ~1ms / 640 档 ~3ms）
     *   2. 不在 → 从**母版**解，顺手落一份缩略图（这一次 20~54ms，只此一次）
     *   3. 这个尺寸没有档位（> 640，只有正在播放页那张）→ 每次从母版解，不落盘
     *
     * ★★ 母版是 covers/ 下的 `.img`：**未经缩放的原始内嵌图**，实测
     *   3000×3000 ~ 4000×4000。这是"每显示一次就解一次大图"的根源 ——
     *   光靠 [sampleSizeFor] 砍不够（一屏八张仍要 ~280ms 的排队）。
     *
     * ★ 缩略图存的是**方形**，不存裁好的圆：圆是按 sizePx 定的，
     *   同一张母版要服务 182px 和 601px 两个调用点，存圆就存两份了。
     */
    private fun decodeCover(path: String, sizePx: Int): Bitmap? {
        val master = File(path)
        if (!master.isFile) return null

        val t0 = SystemClock.elapsedRealtime()
        val tier = tierFor(sizePx)

        if (tier > 0) {
            val thumb = thumbFileFor(master, tier)
            if (thumb != null) {
                if (thumb.isFile) {
                    // 缩略图本来就是按这一档存的，直接解 —— 正常路径走这里
                    decodeSquare(thumb, tier, null)?.let { return circle(it, sizePx) }
                    // 读不出来（写坏了 / 被截断）→ 删掉，下面走母版重建
                    runCatching { thumb.delete() }
                }

                val dims = IntArray(3)
                val sq = decodeSquare(master, tier, dims) ?: return null
                // ★ 先落盘、再裁圆 —— 裁圆要 recycle 掉这张方形图
                writeThumb(sq, tier, thumb)
                logSlow(t0, dims, tier, sizePx)
                return circle(sq, sizePx)
            }
        }

        val dims = IntArray(3)
        val sq = decodeSquare(master, sizePx, dims) ?: return null
        logSlow(t0, dims, 0, sizePx)
        return circle(sq, sizePx)
    }

    /** 裁成圆，并把用过的方形原图还回去 */
    private fun circle(src: Bitmap, sizePx: Int): Bitmap {
        val out = VinylArt.circleCrop(src, sizePx)
        // ★ circleCrop 总会新建一张，方形那张到这儿就没人要了。
        //   它可能有好几 MB —— 快速滚动就是不停分配，不还回去就是等 GC
        if (out !== src) src.recycle()
        return out
    }

    /**
     * 两遍解码：`inJustDecodeBounds` 只读文件头量真实尺寸（不解像素，很快），
     * 算出 [sampleSizeFor] 之后才按需解。
     *
     * ★ 这里原来是一句裸的 `BitmapFactory.decodeFile(path)` —— 按**原始尺寸**
     *   全解出来再缩。3000×3000 解出来是 36MB，缩到 182px 只剩 0.13MB，
     *   **99.6% 的像素白解了**。加上缩略图这一层之后，这句话一辈子只跑一次。
     *
     * @param dims 可选出参：`[源宽, 源高, 用了的 inSampleSize]`，只给日志用
     */
    private fun decodeSquare(f: File, target: Int, dims: IntArray?): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching null

        val sample = sampleSizeFor(w, h, target)
        dims?.let { it[0] = w; it[1] = h; it[2] = sample }
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
        })
    }.getOrNull()

    /**
     * 把方形原图落一份缩略图。
     *
     * ★ 先写 `.part` 再改名 —— 和无线传输、歌单落盘是同一套：
     *   中途被杀掉留下的是 `.part`，不会让下次读到半张图。
     *
     * ★ 存 **JPEG 不存 PNG**：同一张 192px 的图，PNG ~70KB、JPEG ~8KB，
     *   1676 首差出 100MB。代价是透明像素会变黑 —— 封面图里几乎不存在，
     *   而且圆外那一圈本来就会被 [VinylArt.circleCrop] 裁掉。
     */
    private fun writeThumb(sq: Bitmap, tier: Int, out: File) {
        runCatching {
            val scaled = if (sq.width != tier || sq.height != tier) {
                Bitmap.createScaledBitmap(sq, tier, tier, true)
            } else sq
            val part = File(out.parentFile, out.name + ".part")
            FileOutputStream(part).use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            if (!part.renameTo(out)) part.delete()
            if (scaled !== sq) scaled.recycle()
        }.onFailure { Log.w(TAG, "缩略图写入失败 ${out.name}: ${it.message}") }
    }

    /**
     * 只报慢的 —— 不刷屏，但下次再卡有账可查。
     *
     * ★ 这行现在是**一次性**的：同一张封面第二次走的就是缩略图，不会再报。
     *   所以一条都看不见 = 缩略图已经建好了。
     */
    private fun logSlow(t0: Long, dims: IntArray, tier: Int, sizePx: Int) {
        val cost = SystemClock.elapsedRealtime() - t0
        if (cost < SLOW_COVER_MS) return
        val which = if (tier > 0) "第 $tier 档，已建缩略图" else "无档位（不落盘）"
        Log.w(
            TAG,
            "封面解码偏慢：源 ${dims[0]}×${dims[1]} → ${sizePx}px（$which，" +
                "inSampleSize=${dims[2]}），耗时 $cost ms"
        )
    }

    /**
     * 最省的下采样倍数：**2 的幂**，且保证解出来的短边仍然 ≥ [sizePx]。
     *
     * ★ 必须是 2 的幂。`BitmapFactory` 对非 2 的幂会**向下取整到最近的 2 的幂**
     *   （2、4、8…… 而不是 3、5、6），自己算一个非 2 的幂只会以为省了、其实没省。
     *
     * ★ 按**短边**判：封面基本是方的，但万一遇到非方的，按短边算能保证
     *   [VinylArt.circleCrop] 缩放时不会糊。
     */
    private fun sampleSizeFor(w: Int, h: Int, sizePx: Int): Int {
        if (sizePx <= 0) return 1
        var s = 1
        while (w / (s * 2) >= sizePx && h / (s * 2) >= sizePx) s *= 2
        return s
    }

    /**
     * 曲目的黑胶种子。
     *
     * ★ 全 App 只有这一处算种子 —— 列表、队列、正在播放页必须用同一个公式，
     *   否则同一首歌在两页显示成两张不同的唱片。之前就是两处各写了一份，
     *   结果对不上。
     */
    fun vinylSeedFor(t: Track, baseSeed: Long): Long =
        (t.uri.hashCode().toLong() and 0x7FFFFFFF) + baseSeed
}
