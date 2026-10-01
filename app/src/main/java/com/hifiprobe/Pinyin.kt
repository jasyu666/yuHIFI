package com.hifiprobe

import android.icu.text.Transliterator
import java.text.Collator
import java.util.Locale

/**
 * 曲名的字母索引键 —— 给「所有歌曲」的字母序排列和右侧滑动块用。
 *
 * ★ 汉字转拼音走系统自带的 ICU 音译器（`Han-Latin`），**不内置字表**。
 *   Android 从 API 24 起就自带 android.icu，本项目 minSdk 26 覆盖得到。
 *   自己维护一张几千字的映射表既占体积、又一定会漏生僻字，不划算。
 *   万一某个 ROM 缺这个音译器，[bucket] 会兜底成 '#'，界面照样能用，
 *   只是中文曲目全挤在最后一组 —— 功能降级，不会崩。
 *
 * ★ 排序用 `Collator(Locale.CHINA)`，也就是**按拼音排**，不是按码点。
 *   按码点排的话「陈」会排在「张」后面，组内顺序是乱的，字母块就白做了。
 *
 * ★ 转拼音和 collator 比较都是慢操作（一个字一次音译、一次比较一次
 *   规则求值），整个列表排下来必须**放在后台线程**，见 [sort] 的调用方。
 *
 * ★ 线程安全：ICU 的 Transliterator 和 java.text.Collator 都**不是**
 *   线程安全的。这里一个用 @Synchronized 包住、一个用 ThreadLocal 各持
 *   一份。缓存本身也要同步 —— 排序在后台线程跑，界面线程同时在查。
 */
object Pinyin {

    /** 字母块的数量：A-Z 共 26 个，加上非字母的 '#' */
    const val BUCKET_COUNT = 27

    /** 字母块列表，顺序即界面右侧滑动块从上到下的顺序 */
    val LETTERS: List<Char> = ('A'..'Z').toList() + '#'

    /**
     * 汉字音译器。
     *
     * ★ 初始化时**用一个已知字试转一次**，不只看"有没有抛异常" ——
     *   个别 ROM 的 ICU 数据被裁过，`getInstance` 会成功返回一个
     *   恒等音译器（原样吐出汉字），不试的话会一路静默降级成"全是 #"。
     */
    private val hanToLatin: Transliterator? by lazy {
        runCatching {
            val t = Transliterator.getInstance("Han-Latin")
            val probe = t.transliterate("中")
            if (probe.any { it in 'a'..'z' || it in 'A'..'Z' }) t else null
        }.getOrNull()
    }

    /** 拼音首字母到底能不能用。界面据此如实告诉用户，而不是让中文悄悄堆进 '#' */
    val hanAvailable: Boolean get() = hanToLatin != null

    private val collatorLocal: ThreadLocal<Collator> =
        ThreadLocal.withInitial { Collator.getInstance(Locale.CHINA) }

    /** 汉字 → 首字母。值为 '#' 表示查不到，**这个结果也要缓存**，免得反复重试 */
    private val hanCache = HashMap<Char, Char>()

    @Synchronized
    private fun hanBucket(ch: Char): Char {
        hanCache[ch]?.let { return it }
        val out = runCatching { hanToLatin?.transliterate(ch.toString()) }.getOrNull()
        // `Han-Latin` 输出形如 "qíng" / "qing"，取第一个字母即可；
        // zh / ch / sh 这类双字母声母，首字母也正是要归的那个字母
        val c = out?.firstOrNull { it.isLetter() }?.uppercaseChar() ?: '#'
        hanCache[ch] = c
        return c
    }

    /**
     * 一个标题归到哪个字母块。
     *
     * 规则：跳过前导空白，看第一个**有决定意义**的字符 ——
     *   · 拉丁字母       → 它的大写
     *   · 数字           → '#'（「1989」这类不该混进字母区）
     *   · 汉字           → 拼音首字母
     *   · 假名 / 韩文等  → '#'
     *   · 半角标点       → 跳过，继续看下一个
     *
     * 跳过标点是为了让「(Live) Yesterday」正确归到 Y —— 前导括号和空格
     * 是排版噪声，不是标题的一部分。
     */
    fun bucket(title: String): Char {
        for (ch in title) {
            if (ch.isWhitespace()) continue
            if (ch.isDigit()) return '#'
            if (ch.code < 128) {
                if (ch.isLetter()) return ch.uppercaseChar()
                continue                       // 半角标点，跳过继续找
            }
            if (Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN) {
                return hanBucket(ch)
            }
            if (ch.isLetter()) return '#'      // 假名 / 韩文 / 全角字母
        }
        return '#'
    }

    /** 排序时的块序号：'#' 永远排最后 */
    private fun rank(b: Char): Int = if (b == '#') 26 else (b - 'A')

    /**
     * 按拼音比较两个标题。Collator 不是线程安全的，所以各线程一份。
     *
     * `!!` 是把这个不变量写出来：ThreadLocal 要么返回之前存的，要么现调
     * initialValue()，**永远不会是 null**。Kotlin 把 get() 看成可空，
     * 但不写 !! 就得在比较器最里层塞一个永远走不到的空分支 ——
     * 而那儿每排一次序要跑几千次。
     */
    private fun compareTitle(a: String, b: String): Int =
        collatorLocal.get()!!.compare(a, b)

    /** 一个字母块：从第 [start] 项开始 */
    data class Section(val letter: Char, val start: Int)

    /** 排好序的结果：条目本身、每条的字母块、以及每个字母块的首项下标 */
    data class Sorted<T>(val items: List<T>, val buckets: List<Char>, val sections: List<Section>)

    /**
     * 按 (字母块, 拼音) 排序，并算出字母索引所需的分组信息。
     *
     * [titleOf] 会被调用两次（排序键一次、分组一次）—— 传进来的应该是
     * `Track::displayTitle` 这种便宜的函数，别在里面读文件。
     *
     * **慢，必须在后台线程调用。**
     */
    fun <T> sort(items: List<T>, titleOf: (T) -> String): Sorted<T> {
        // 先把字母块算好存下来 —— 放进比较器里算的话，每次都重新转一遍拼音
        val keyed = items.map { item ->
            val title = titleOf(item)
            Keyed(item, title, bucket(title))
        }
        val ordered = keyed.sortedWith(Comparator { x, y ->
            val r = rank(x.bucket) - rank(y.bucket)
            if (r != 0) r else compareTitle(x.title, y.title)
        })

        val buckets = ordered.map { it.bucket }
        val sections = ArrayList<Section>(BUCKET_COUNT)
        buckets.forEachIndexed { i, b ->
            if (sections.isEmpty() || sections.last().letter != b) {
                sections.add(Section(b, i))
            }
        }
        return Sorted(ordered.map { it.item }, buckets, sections)
    }

    private data class Keyed<T>(val item: T, val title: String, val bucket: Char)
}
