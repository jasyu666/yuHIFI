package com.hifiprobe

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 一个命名歌单。
 *
 * 存的是 **uri 列表**，不是 Track 对象 —— Track 里有封面路径、文件大小
 * 这些会变的东西，存下来第二天就过期了。uri 是稳定标识，播放时拿它去
 * [Library] 里换回当前的 Track。
 */
data class Playlist(
    val id: String,
    var name: String,
    val uris: MutableList<String>
) {
    val size: Int get() = uris.size
}

/**
 * 命名歌单 —— 用户自己收藏的、长期存在的那一份顺序。
 *
 * ★ 和「播放队列」（[PlayQueue]）是**两回事**，界面上也分开摆：
 *   · 播放队列描述的是"**接下来听什么**"，歌单描述的是"**我想留着的那些歌**"
 *   · 队列可以被一次点歌整个换掉；歌单只会被用户明确地增删
 *
 *   ⚠ 这里原来还写着"播放队列是临时的，**关掉 app 就没了**"—— 那句**已经过时**：
 *     用户要求队列也持久化（每次退出都要重新选一遍曲目太烦），现在由
 *     [QueueStore] 存到 `queue.json`。
 *
 *   ★ 但**两个概念仍然是分开的**，别因为都存盘了就合并 —— 合并之后用户
 *     就永远搞不清"我上次加到队列里的歌去哪了"（它是被下一张专辑顶掉了，
 *     不是被删了）。
 *
 * ★ 存 uri 而不是 Track 的代价：库里删了或挪了文件，uri 就悬空了。
 *   [tracksOf] 的做法是**静默跳过找不到的**，而不是报错或留着空行 ——
 *   歌单少一首歌不奇怪，冒出一行"已失效"才奇怪。
 *   本来也可以在删除文件时顺手清理所有歌单，但那要遍历全部歌单，
 *   而这里反正每次读都要过一遍库索引，顺带过滤是零成本的。
 */
object Playlists {

    private const val TAG = "HiFiPlaylists"

    private lateinit var file: File
    private val lists = mutableListOf<Playlist>()

    @Synchronized
    fun init(ctx: Context) {
        if (::file.isInitialized) return
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        file = File(base, "playlists.json")
        load()
    }

    /** 快照。返回的是拷贝，调用方拿着它慢慢画界面，不会被后台的增删改到 */
    @Synchronized
    fun all(): List<Playlist> = lists.map { it.copy(uris = it.uris.toMutableList()) }

    @Synchronized
    fun size(): Int = lists.size

    @Synchronized
    fun byId(id: String): Playlist? = lists.firstOrNull { it.id == id }?.let {
        it.copy(uris = it.uris.toMutableList())
    }

    // ------------------------------------------------------------------

    /** 建歌单。重名或空名返回 null —— 界面上要能在列表里认出谁是谁 */
    @Synchronized
    fun create(name: String): Playlist? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        if (lists.any { it.name == clean }) return null
        val p = Playlist(UUID.randomUUID().toString(), clean, mutableListOf())
        lists.add(p)
        save()
        return p.copy(uris = mutableListOf())
    }

    @Synchronized
    fun rename(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        if (lists.any { it.id != id && it.name == clean }) return false
        val p = lists.firstOrNull { it.id == id } ?: return false
        p.name = clean
        save()
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val gone = lists.removeAll { it.id == id }
        if (gone) save()
        return gone
    }

    /**
     * 往歌单里加歌，**按 uri 去重**。
     *
     * 同一个歌单里出现两遍同一首歌没有意义（除非是刻意做循环列表，
     * 那种需求该由队列的循环模式解决），而且用户会以为加失败了。
     *
     * @return 真正加进去的条数 —— 界面要如实显示"加了 3 首"还是"都已经在歌单里了"
     */
    @Synchronized
    fun add(id: String, tracks: List<Track>): Int {
        val p = lists.firstOrNull { it.id == id } ?: return 0
        var n = 0
        for (t in tracks) {
            if (p.uris.contains(t.uri)) continue
            p.uris.add(t.uri)
            n++
        }
        if (n > 0) save()
        return n
    }

    /**
     * 按 uri 移除。
     *
     * ★ 界面必须用这个，不能用下标 —— [tracksOf] 会**跳过失效的 uri**，
     *   所以"界面上第 3 行"和"歌单里第 3 条"在歌单缩水时不是同一条。
     *   按下标移除会删错歌，而且用户完全看不出为什么。
     */
    @Synchronized
    fun removeUri(id: String, uri: String): Boolean {
        val p = lists.firstOrNull { it.id == id } ?: return false
        val gone = p.uris.remove(uri)
        if (gone) save()
        return gone
    }

    @Synchronized
    fun removeAt(id: String, index: Int): Boolean {
        val p = lists.firstOrNull { it.id == id } ?: return false
        if (index !in p.uris.indices) return false
        p.uris.removeAt(index)
        save()
        return true
    }

    /** 拖动排序用。写盘交给调用方在松手时统一触发 —— 见 [flush] */
    @Synchronized
    fun moveIn(id: String, from: Int, to: Int): Boolean {
        val p = lists.firstOrNull { it.id == id } ?: return false
        if (from !in p.uris.indices || to !in p.uris.indices || from == to) return false
        p.uris.add(to, p.uris.removeAt(from))
        return true
    }

    /**
     * 批量删条目。文件被删掉时调用。
     *
     * 不删也能跑（[tracksOf] 会跳过悬空的 uri），但那些条目会一直躺在
     * JSON 里 —— 界面上的数量是按"还找得到的歌"算的，所以**看不出来**，
     * 但 JSON 会越积越多。App 内删除文件时一定要调它。
     *
     * ★ 注意：App **外**删掉的文件（网页版、adb、电脑）不会走到这里，
     *   那些会留下悬空条目。目前不自动清理 —— 见 README 的说明。
     */
    @Synchronized
    fun removeUris(uris: Collection<String>): Int {
        if (uris.isEmpty()) return 0
        var n = 0
        for (p in lists) {
            val before = p.uris.size
            p.uris.removeAll(uris.toSet())
            n += before - p.uris.size
        }
        if (n > 0) save()
        return n
    }

    /**
     * 曲目挪了位置之后把歌单里的 uri 跟着改过来。
     *
     * ★ 不做这一步就是静默的数据丢失：文件好好地躺在库里，
     *   但引用了它的歌单会莫名其妙少一首，而且**没有任何提示** ——
     *   用户只会觉得"我明明加过这首"。[Library] 移动文件时务必调它。
     */
    @Synchronized
    fun remapUris(remap: Map<String, String>): Int {
        if (remap.isEmpty()) return 0
        var n = 0
        for (p in lists) {
            for (i in p.uris.indices) {
                val nu = remap[p.uris[i]] ?: continue
                p.uris[i] = nu
                n++
            }
        }
        if (n > 0) save()
        return n
    }

    /** 拖动结束时调一次，把内存里的新顺序落盘 */
    @Synchronized
    fun flush() = save()

    @Synchronized
    fun clear(id: String): Boolean {
        val p = lists.firstOrNull { it.id == id } ?: return false
        if (p.uris.isEmpty()) return false
        p.uris.clear()
        save()
        return true
    }

    // ------------------------------------------------------------------

    /**
     * 把歌单里的 uri 映射回库里的曲目。
     *
     * **不要在界面线程调** —— 它要遍历整个库建索引。列表几百首时还好，
     * 但曲库大起来就是几百毫秒，卡一下很明显。
     */
    fun tracksOf(id: String): List<Track> {
        val p = byId(id) ?: return emptyList()
        val index = Library.all().associateBy { it.uri }
        return p.uris.mapNotNull { index[it] }
    }

    // ------------------------------------------------------------------

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val arr = JSONArray(file.readText())
            lists.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = o.optString("name").ifBlank { "未命名歌单" }
                val uris = o.optJSONArray("uris") ?: JSONArray()
                val list = mutableListOf<String>()
                for (j in 0 until uris.length()) {
                    val u = uris.optString(j)
                    if (u.isNotEmpty()) list.add(u)
                }
                lists.add(Playlist(id, name, list))
            }
        }.onFailure {
            // 读坏了就当没有歌单，而不是让整个 app 起不来
            Log.w(TAG, "歌单读取失败，按空处理: ${it.message}")
            lists.clear()
        }
    }

    private fun save() {
        runCatching {
            val arr = JSONArray()
            for (p in lists) {
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("uris", JSONArray().apply { p.uris.forEach { put(it) } })
                })
            }
            file.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "歌单写入失败: ${it.message}") }
    }
}
