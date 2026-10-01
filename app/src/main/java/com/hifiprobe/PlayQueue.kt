package com.hifiprobe

import kotlin.random.Random

/** 循环模式 */
enum class RepeatMode { OFF, ALL, ONE }

/**
 * 播放队列。
 *
 * ★ **纯逻辑，不依赖任何 Android 类** —— 因此可以在 JVM 上跑单元测试。
 *   队列的边界情况（单曲、末尾、三种循环模式、开关洗牌）全是容易写错
 *   又不容易在真机上碰到的角落，靠测试锁住比上真机试靠谱得多。
 *
 * 两个容易混淆的概念，这里刻意分开：
 *
 *   · **自动播完**（[onFinished]）—— 受循环模式约束。OFF 且到末尾时要停下。
 *   · **手动操作**（[manualNext] / [manualPrev]）—— **永远有响应**。
 *     用户明确按了「下一首」，说明就是想继续听，这时因为"到头了"而什么都不做
 *     是很突兀的。所以手动操作在两端环绕，不受循环模式影响。
 *
 *   [manualPrev] 还有一层：多数播放器的习惯是"播了超过几秒就先回到本曲开头，
 *   再按才真的退到上一首"。那是**界面层的策略**（需要知道播放进度），
 *   不放在这里 —— 这里只提供 [restartCurrent]，由界面决定何时用。
 */
class PlayQueue(private val random: Random = Random.Default) {

    /**
     * 队列里的一项。
     *
     * ★★ 为什么把"播过没有"塞进项里，而不是单开一个「已播下标集合」：
     *
     *   队列是**可编辑的** —— 插入、删除、拖动排序都会让下标整体错位。
     *   用下标记的话，每一次编辑都得跟着搬一遍；漏一处就是"标记跑到别的歌
     *   身上"，而且**完全看不出来**（界面只是某一行的颜色不对）。
     *   挂在项上就自动跟着歌走，零维护。
     *
     *   （不能改用 uri 当键：队列**允许重复**，同一首歌可以在里面出现两次。）
     */
    private class Item(val track: Track, var played: Boolean = false)

    private val items = ArrayList<Item>()

    /** 播放顺序：元素是 [items] 的下标。洗牌时是打乱的，否则就是 0..n-1。 */
    private var order: MutableList<Int> = ArrayList()

    /** 当前在 [order] 里的位置。-1 表示没有在播的。 */
    private var pos = -1

    var repeatMode: RepeatMode = RepeatMode.OFF

    var shuffle: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuildOrderKeepingCurrent()
        }

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    /** 队列里的曲目，**按用户添加的顺序**（不是播放顺序）。界面列表用这个。 */
    fun tracks(): List<Track> = items.map { it.track }

    fun trackAt(index: Int): Track? = items.getOrNull(index)?.track

    /**
     * 就地替换某一首（用探测到的精确规格回填）。
     *
     * [order] 存的是下标，所以替换元素**不影响播放顺序** ——
     * 正在播的那一首换了对象也不会跳。
     */
    fun replaceAt(index: Int, track: Track) {
        // ★ 保留 played —— 这里换的是**同一首歌**（探测到精确规格之后回填），
        //   不是换了一首歌
        if (index in items.indices) items[index] = Item(track, items[index].played)
    }

    /** 当前曲目在 [tracks] 里的下标。-1 表示没有。 */
    val currentIndex: Int
        get() = order.getOrNull(pos) ?: -1

    fun current(): Track? = order.getOrNull(pos)?.let { items.getOrNull(it)?.track }

    /** 是不是最后一首（按播放顺序）。界面可以用来把「下一首」按钮置灰。 */
    val isAtEnd: Boolean get() = pos >= order.size - 1

    /**
     * 用一批曲目替换整个队列。
     *
     * @param startAt 从 [items] 的哪个下标开始播
     */
    fun setTracks(list: List<Track>, startAt: Int = 0) {
        items.clear()
        items.addAll(list.map { Item(it) })      // 换一批歌 = 全新的未播状态
        order = items.indices.toMutableList()
        pos = -1
        if (items.isEmpty()) return

        val start = startAt.coerceIn(0, items.size - 1)
        if (shuffle) shuffleOrder(keepingFirst = start)
        pos = order.indexOf(start).coerceAtLeast(0)
    }

    fun clear() {
        items.clear()
        order.clear()
        pos = -1
    }

    /** 直接跳到 [tracks] 里的某个下标 */
    fun jumpTo(index: Int): Track? {
        if (index !in items.indices) return null
        val p = order.indexOf(index)
        if (p < 0) return null
        pos = p
        return current()
    }

    /** 重播当前曲目（「上一首」的短按语义，界面按播放进度决定是否用它） */
    fun restartCurrent(): Track? = current()

    // ------------------------------------------------------------------
    //  已播标记
    // ------------------------------------------------------------------

    /**
     * 标记某一首"播过了"。**开播时就标，不是放完才标**。
     *
     * ★ 为什么是"开播"而不是"放完"：只在放完时标的话，**跳着播的那首**和
     *   **中途退出的那首**都不算播过 —— 可用户明明听了。开播即标更贴直觉。
     *
     * ★ 它同时解决了恢复时的定位问题：存一个"当前下标"是不够的，
     *   因为用户可能手动跳着播（播了 1、2、3、5，而 4 没播过），
     *   一个位置表达不了这件事。
     */
    fun markPlayed(index: Int) {
        items.getOrNull(index)?.played = true
    }

    fun isPlayed(index: Int): Boolean = items.getOrNull(index)?.played == true

    /** 逐项的已播标记，和 [tracks] 一一对应。界面一次性取走，别在 bind 里逐个查 */
    fun playedFlags(): List<Boolean> = items.map { it.played }

    // ------------------------------------------------------------------
    //  序列化（给 QueueStore 用）
    // ------------------------------------------------------------------

    /**
     * 一处可存盘的快照。
     *
     * ★ 存 **uri 而不是 Track**：Track 里有封面路径、文件大小这些会变的东西，
     *   存下来第二天就过期了 —— 和 [Playlists] 是同一个理由，
     *   而且那边已经写清楚了。
     */
    class Saved(
        val uris: List<String>,
        val played: List<Boolean>,
        val currentUri: String?,
        val order: List<Int>,
        val repeat: RepeatMode,
        val shuffle: Boolean
    )

    fun snapshot(): Saved = Saved(
        uris = items.map { it.track.uri },
        played = items.map { it.played },
        currentUri = current()?.uri,
        order = order.toList(),
        repeat = repeatMode,
        shuffle = shuffle
    )

    /**
     * 从磁盘恢复。
     *
     * @param tracks   已经由调用方按 uri 映射好的曲目，**失效条目已在外部剔除**
     * @param played   与 [tracks] 一一对应的已播标记
     * @param savedOrder 播放顺序（下标指向 [tracks]）。空则按添加顺序
     * @param startUri  上次在播的那首。找不到（被删了）就回到队首 ——
     *                  恢复出来的队列应该**能直接按播放键**，而不是卡在"没有当前曲目"
     */
    fun restore(
        tracks: List<Track>,
        played: List<Boolean>,
        savedOrder: List<Int>,
        savedRepeat: RepeatMode,
        savedShuffle: Boolean,
        startUri: String?
    ) {
        items.clear()
        tracks.forEachIndexed { i, t -> items.add(Item(t, played.getOrElse(i) { false })) }
        repeatMode = savedRepeat

        /*
         * ★ 顺序有讲究：shuffle 的 setter 会**重建 order**（它要保持当前曲目不变），
         *   所以必须先让它跑完，再用磁盘里的顺序覆盖它 —— 否则恢复出来的
         *   播放顺序是"重新洗一次"的结果，和用户上次听到的不是同一个。
         */
        shuffle = savedShuffle

        order = savedOrder.filter { it in items.indices }.toMutableList()
        if (order.isEmpty()) order = items.indices.toMutableList()

        val at = if (startUri == null) -1 else items.indexOfFirst { it.track.uri == startUri }
        pos = if (order.isEmpty()) -1 else if (at < 0) 0 else order.indexOf(at).coerceAtLeast(0)

        // 顺带把"顺序"和"内容"对齐：order 只该是 items 下标的一个排列
        if (order.toSet() != items.indices.toSet()) order = items.indices.toMutableList()
    }

    /**
     * 用户按「下一首」。**永远前进**，末尾环绕。
     * 队列为空时返回 null。
     */
    fun manualNext(): Track? = step(+1)

    /** 用户按「上一首」。**永远后退**，开头环绕。 */
    fun manualPrev(): Track? = step(-1)

    /**
     * 一首放完了，决定接下来放什么。
     *
     * 返回 null 表示**应该停下来**（循环关、且已经是最后一首）。
     */
    fun onFinished(): Track? = when (repeatMode) {
        RepeatMode.ONE -> current()                       // 单曲循环：重播
        RepeatMode.ALL -> step(+1)                        // 列表循环：末尾回到开头
        RepeatMode.OFF -> {
            if (isAtEnd) null else step(+1)               // 到头就停
        }
    }

    // ------------------------------------------------------------------
    //  编辑 —— 队列是用户的东西，得能改
    // ------------------------------------------------------------------

    /**
     * 编辑前的统一准备：**关掉随机播放**。
     *
     * ★ 开着随机时，"队列顺序"是没有意义的 —— 播放顺序是另一套排列
     *   （[order]）。这时让用户拖顺序，拖完发现还是乱着放，比不让编辑
     *   更让人困惑。所以直接关掉，并让调用方提示一次。
     *
     * @return true 表示这次确实关掉了随机，调用方应该给个提示
     */
    private fun prepareForEdit(): Boolean {
        if (!shuffle) return false
        shuffle = false          // setter 会把 order 恢复成恒等排列
        return true
    }

    /**
     * 插到**当前播放这首的后面**（"下一首播放"）。
     * 没有正在播的就加到队首。
     */
    fun insertNext(track: Track): Boolean {
        val changed = prepareForEdit()
        val at = if (currentIndex >= 0) currentIndex + 1 else 0
        items.add(at, Item(track))
        normalizeOrder()
        // 插在当前之后，当前的下标没变，pos 自然也不变
        if (pos < 0) pos = 0
        return changed
    }

    /** 加到队尾 */
    fun append(track: Track): Boolean {
        val changed = prepareForEdit()
        items.add(Item(track))
        normalizeOrder()
        if (pos < 0) pos = 0
        return changed
    }

    /**
     * 从队列里删掉一首。
     *
     * 删的如果是**正在播的那首**，就把 pos 留在原地 —— 它自然指向了
     * 后面那首，也就是"删掉当前这首，接着放下一首"的常见行为。
     *
     * @return 是否真的删掉了
     */
    fun removeAt(index: Int): Boolean {
        if (index !in items.indices) return false
        prepareForEdit()
        items.removeAt(index)
        when {
            items.isEmpty() -> pos = -1
            index < pos -> pos--
            // index == pos：pos 不动，正好指向原来的下一首
            // index > pos：不影响
        }
        if (pos >= items.size) pos = items.size - 1
        normalizeOrder()
        return true
    }

    /** 把队列里的第 [from] 首挪到第 [to] 首的位置 */
    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        prepareForEdit()
        val cur = currentIndex
        val t = items.removeAt(from)
        items.add(to, t)
        // 当前这首的位置要跟着走，否则拖一下顺序就跳到别的歌去了
        pos = when {
            cur < 0 -> pos
            from == cur -> to
            from < cur && to >= cur -> cur - 1
            from > cur && to <= cur -> cur + 1
            else -> cur
        }
        pos = pos.coerceIn(0, items.size - 1)
        normalizeOrder()
        return true
    }

    /** 清空队列里**还没播的部分**，保留当前这首 */
    fun clearUpcoming(): Boolean {
        if (items.isEmpty() || currentIndex < 0) return false
        prepareForEdit()
        val keep = items[currentIndex]
        items.clear()
        items.add(keep)
        pos = 0
        normalizeOrder()
        return true
    }

    /** 全清 */
    fun clearAll() {
        items.clear()
        order.clear()
        pos = -1
    }

    /** 队列是不是"还没被用户编辑过的整库快照"——界面用它决定是否提示 */
    val isEditable: Boolean get() = items.isNotEmpty()

    /** 把 order 归位成恒等排列。编辑操作全部在"无随机"前提下做，所以必然是恒等 */
    private fun normalizeOrder() {
        order = items.indices.toMutableList()
    }

    // ---- 内部 ----

    /** 在 order 里前进/后退一格，两端环绕 */
    private fun step(delta: Int): Track? {
        if (order.isEmpty()) return null
        val n = order.size
        pos = ((pos + delta) % n + n) % n
        return current()
    }

    /**
     * 重建播放顺序但**保持当前曲目不变** —— 开关洗牌不该让正在放的歌跳掉。
     */
    private fun rebuildOrderKeepingCurrent() {
        if (items.isEmpty()) return
        val cur = currentIndex
        if (shuffle) {
            shuffleOrder(keepingFirst = if (cur >= 0) cur else 0)
            pos = 0                                   // 洗牌时当前曲目被放在第一位
        } else {
            order = items.indices.toMutableList()
            pos = if (cur >= 0) cur else 0
        }
    }

    /** 生成一个以 [keepingFirst] 打头的随机排列 */
    private fun shuffleOrder(keepingFirst: Int) {
        val rest = items.indices.filter { it != keepingFirst }.toMutableList()
        rest.shuffle(random)
        order = ArrayList<Int>(items.size).apply {
            add(keepingFirst)
            addAll(rest)
        }
    }
}
