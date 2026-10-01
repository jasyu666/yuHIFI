package com.hifiprobe

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 播放队列的持久化。
 *
 * ★★ 这**推翻了当初刻意的设计**。[Playlists] 的注释里写着：
 *
 *     · 播放队列是临时的，**关掉 app 就没了**，它描述的是"接下来听什么"
 *     · 歌单是长期的，存在磁盘上
 *
 *   现在按用户要求改掉一半 —— 队列也存盘了。理由是"每次退出都要重新
 *   选一遍曲目太烦"，而队列和歌单**仍然是两个东西**（一个描述"接下来听
 *   什么"、一个描述"我想留着的"，界面也分开摆），只是前者不再是一次性的。
 *
 *   ★ 那段注释必须跟着改，否则下次有人读到会按旧语义改代码。
 *
 * ★ 为什么不写进 [PlayQueue]：那个类是**纯逻辑、可以在 JVM 上跑单元测试**
 *   （队列的边界情况全是容易写错又不容易在真机上碰到的角落）。
 *   引进 org.json 就破坏了这个性质。这里和 [Playlists] 一样，只管存盘。
 */
object QueueStore {

    private const val TAG = "HiFiQueue"
    private const val VERSION = 1

    private lateinit var file: File

    @Synchronized
    fun init(ctx: Context) {
        if (::file.isInitialized) return
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        file = File(base, "queue.json")
    }

    /**
     * 存盘。
     *
     * ★★ **调用时机是这块最要紧的事** —— 必须低频：
     *   队列被编辑之后（都是用户手动操作，本来就不频繁）+ 切歌 + 服务销毁。
     *
     *   **绝不能跟着 200ms 心跳写**。拖动排序那种连续操作用不着每帧写，
     *   松手时 [flush] 一次就够（和 [Playlists.moveIn] 是同一套办法）。
     */
    @Synchronized
    fun save(q: PlayQueue) {
        if (!::file.isInitialized) return
        val s = q.snapshot()
        runCatching {
            val items = JSONArray()
            for (i in s.uris.indices) {
                items.put(JSONObject().apply {
                    put("uri", s.uris[i])
                    put("played", s.played.getOrElse(i) { false })
                })
            }
            file.writeText(JSONObject().apply {
                put("version", VERSION)
                put("items", items)
                // 空串 = 没有当前曲目。用空串而不是 JSONObject.NULL，
                // 读回来 optString 直接给 ""，少一种空值形态要处理
                put("current", s.currentUri ?: "")
                put("order", JSONArray().apply { s.order.forEach { put(it) } })
                put("repeat", s.repeat.name)
                put("shuffle", s.shuffle)
            }.toString())
        }.onFailure { Log.w(TAG, "队列写入失败: ${it.message}") }
    }

    /**
     * 读回来并恢复进 [into]。
     *
     * ★★ **不要在界面线程调** —— 它要 `Library.all()` 遍历整个库建索引，
     *   和 [Playlists.tracksOf] 是同一个问题（那边注释里已经警告过）。
     *   库大起来就是几百毫秒，卡一下很明显。
     *
     * @return 是否真的恢复了东西（文件不存在、格式不对、条目全失效都返回 false）
     */
    @Synchronized
    fun restore(into: PlayQueue): Boolean {
        if (!::file.isInitialized || !file.isFile) return false
        val root = runCatching { JSONObject(file.readText()) }.getOrElse {
            // 读坏了就当没有队列，而不是让整个 app 起不来 —— 和 Playlists 一致
            Log.w(TAG, "队列读取失败，按空处理: ${it.message}")
            return false
        }
        if (root.optInt("version") != VERSION) return false
        val arr = root.optJSONArray("items") ?: return false
        if (arr.length() == 0) return false

        // 库里现在有什么。**只建一次索引** —— 循环里逐个查库是 O(N²)
        val index = Library.all().associateBy { it.uri }

        val tracks = ArrayList<Track>(arr.length())
        val played = ArrayList<Boolean>(arr.length())
        val remap = HashMap<Int, Int>()          // 磁盘上的下标 → 恢复后的下标

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = index[o.optString("uri")] ?: continue   // ★ 失效条目静默跳过
            remap[i] = tracks.size
            tracks.add(t)
            played.add(o.optBoolean("played"))
        }
        if (tracks.isEmpty()) {
            Log.i(TAG, "队列里的曲目在库中一个都找不到，不恢复")
            return false
        }

        /*
         * ★★ 播放顺序的下标要**跟着重映射**。
         *
         *   上面跳过了失效条目，items 的下标整体前移了 ——
         *   直接照搬磁盘上的 order 会把顺序指到别的歌身上。
         *   （[Playlists] 里有一条同类注释：歌曲列表缩水之后，
         *     "界面上第 3 行"和"歌单里第 3 条"不再是同一条。）
         */
        val order = ArrayList<Int>()
        root.optJSONArray("order")?.let { o ->
            for (i in 0 until o.length()) remap[o.optInt(i, -1)]?.let { order.add(it) }
        }

        val repeat = runCatching { RepeatMode.valueOf(root.optString("repeat")) }
            .getOrDefault(RepeatMode.OFF)
        val startUri = root.optString("current").takeIf { it.isNotEmpty() }

        into.restore(
            tracks = tracks,
            played = played,
            savedOrder = order,
            savedRepeat = repeat,
            savedShuffle = root.optBoolean("shuffle"),
            startUri = startUri,
        )

        // ★ 跳过几个也要报出来 —— 不报的话"队列怎么少了几首"永远查不出原因
        val dropped = arr.length() - tracks.size
        Log.i(TAG, "队列已恢复：${tracks.size} 首" +
                if (dropped > 0) "（跳过 $dropped 个已失效的曲目）" else "")
        return true
    }

    /** 拖动排序松手时调一次，把内存里的新顺序落盘 */
    @Synchronized
    fun flush(q: PlayQueue) = save(q)
}
