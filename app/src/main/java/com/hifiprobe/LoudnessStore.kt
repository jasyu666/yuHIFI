package com.hifiprobe

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 响度测量结果的侧挂存储。
 *
 * ★ 为什么不塞进 `library.json`：那个文件已经有 1.7MB，而且是**整个重写**。
 *   响度结果是在播放/扫描过程中一首一首落地的，每首重写 1.7MB 不合适。
 *   侧挂一个 `loudness.json`，和 `CoverDedupe` 的 `dedupe.map` 是同一个模式。
 *
 * ★★ **只存绝对测量值**（LUFS + 真峰值），不存"相对 -18 LUFS 的增益"。
 *   目标响度改了只需要重算增益，不需要重新解码任何文件。
 *
 * ★ 失效判据：`size + mtime + algo`。文件换了、重压了、测量算法改了，
 *   旧值自动作废。
 */
object LoudnessStore {

    private const val TAG = "HiFiLoudnessStore"
    private const val FILE = "loudness.json"
    private const val VERSION = 1

    /**
     * 测量算法版本。**改动 K 加权/门限/真峰值实现时必须 +1** ——
     * 否则旧结果会被当成同等可信的新结果继续用。
     */
    const val ALGO = 1

    data class TrackMeasure(
        val lufs: Double,
        val truePeakDb: Double,
        val size: Long,
        val mtime: Long
    )

    data class AlbumMeasure(
        val lufs: Double,
        val truePeakDb: Double,
        val sig: String
    )

    private val lock = Any()
    private var file: File? = null
    private var loaded = false
    private var dirty = false

    private val tracks = HashMap<String, TrackMeasure>()
    private val albums = HashMap<String, AlbumMeasure>()

    private var saver: java.util.concurrent.ScheduledExecutorService? = null

    fun init(ctx: Context) {
        synchronized(lock) {
            if (file != null) return
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            file = File(base, FILE)
        }
        ensureLoaded()
        // 每 5 秒最多落一次盘 —— 全库扫描时每首写一次会太频繁
        saver = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "loudness-store").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ flush() }, 5, 5, TimeUnit.SECONDS)
        }
    }

    // ------------------------------------------------------------------
    //  读取
    // ------------------------------------------------------------------

    /** 库内文件用相对路径；SAF 引用回落到 uri —— 两者都不为空才存 */
    private fun keyOf(track: Track): String? =
        Library.relPathOf(track) ?: track.uri.takeIf { it.isNotBlank() }

    fun getTrack(track: Track): TrackMeasure? {
        ensureLoaded()
        val key = keyOf(track) ?: return null
        synchronized(lock) {
            val m = tracks[key] ?: return null
            if (!m.lufs.isFinite() || !m.truePeakDb.isFinite()) return null
            // 文件变了 → 旧测量作废（但先别删，可能只是暂时读不到大小）
            if (m.size != track.fileSize || m.mtime != track.fileModified) return null
            return m
        }
    }

    fun getAlbum(name: String, sig: String): AlbumMeasure? {
        ensureLoaded()
        synchronized(lock) {
            val a = albums[name] ?: return null
            if (!a.lufs.isFinite() || !a.truePeakDb.isFinite()) return null
            return if (a.sig == sig) a else null
        }
    }

    /**
     * 已测曲目数，给设置页进度显示用。
     *
     * ★ 刻意**不**逐首比对 size/mtime：这个函数 1.5 秒调一次，
     *   而 `Library.relPathOf()` 是路径运算。2800 首逐个做会让设置页抖。
     *   已删除/已变更文件留下的陈旧条目最多让进度提前一点点，不影响功能。
     */
    fun measuredCount(): Int {
        ensureLoaded()
        return synchronized(lock) { tracks.size }
    }

    fun trackMeasureCount(): Int {
        ensureLoaded()
        return synchronized(lock) { tracks.size }
    }

    fun albumMeasureCount(): Int {
        ensureLoaded()
        return synchronized(lock) { albums.size }
    }

    /**
     * 缓存判定的人话描述，只给监控/日志用。
     *
     * ★ 这里**不改变 [getTrack] 的行为**：只把"有没有、为什么失效"讲清楚。
     */
    fun describe(track: Track): String {
        ensureLoaded()
        val key = keyOf(track) ?: return "no-key"
        synchronized(lock) {
            val m = tracks[key] ?: return "miss:no-entry key=$key"
            if (!m.lufs.isFinite() || !m.truePeakDb.isFinite()) {
                return "miss:invalid key=$key"
            }
            if (m.size != track.fileSize || m.mtime != track.fileModified) {
                return "miss:meta key=$key stored=${m.size}/${m.mtime} " +
                        "current=${track.fileSize}/${track.fileModified}"
            }
            return "hit:lufs=${"%.2f".format(m.lufs)} tp=${"%.2f".format(m.truePeakDb)} key=$key"
        }
    }

    // ------------------------------------------------------------------
    //  写入
    // ------------------------------------------------------------------

    fun putTrack(track: Track, lufs: Double, truePeakDb: Double) {
        if (!lufs.isFinite() || !truePeakDb.isFinite()) return
        val key = keyOf(track) ?: return
        ensureLoaded()
        synchronized(lock) {
            tracks[key] = TrackMeasure(
                lufs = lufs,
                truePeakDb = truePeakDb,
                size = track.fileSize,
                mtime = track.fileModified
            )
            dirty = true
        }
    }

    fun putAlbum(name: String, sig: String, lufs: Double, truePeakDb: Double) {
        if (!lufs.isFinite() || !truePeakDb.isFinite()) return
        ensureLoaded()
        synchronized(lock) {
            albums[name] = AlbumMeasure(lufs, truePeakDb, sig)
            dirty = true
        }
    }

    /**
     * 专辑成员指纹。成员、顺序、大小、修改时间或算法任一变化都会变。
     *
     * ★ 用它而不是"专辑名"判断缓存是否有效：同名专辑可能被重扫成不同的成员集合。
     */
    fun albumSig(album: Library.Album): String {
        val sb = StringBuilder()
        sb.append("algo=").append(ALGO).append('\n')
        album.tracks.mapNotNull { t ->
            val rel = Library.relPathOf(t) ?: return@mapNotNull null
            "$rel|${t.fileSize}|${t.fileModified}"
        }.sorted().forEach { sb.append(it).append('\n') }

        return try {
            val md = MessageDigest.getInstance("SHA-1")
            md.digest(sb.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // SHA-1 在 Android 上必然存在；真出错就退回字符串本身（只影响缓存命中）
            Log.e(TAG, "计算专辑指纹失败: $e")
            sb.toString()
        }
    }

    fun flush() {
        ensureLoaded()
        synchronized(lock) {
            if (!dirty) return
            saveLocked()
        }
    }

    // ------------------------------------------------------------------
    //  磁盘
    // ------------------------------------------------------------------

    private fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val f = file ?: return
            if (!f.isFile) return

            try {
                val root = JSONObject(f.readText())
                if (root.optInt("version") != VERSION) {
                    Log.w(TAG, "loudness.json 版本不匹配，忽略旧数据")
                    return
                }
                if (root.optInt("algo") != ALGO) {
                    Log.i(TAG, "测量算法版本变了，旧结果全部作废")
                    return
                }

                root.optJSONObject("tracks")?.let { obj ->
                    for (key in obj.keys()) {
                        val o = obj.optJSONObject(key) ?: continue
                        val lufs = o.optDouble("lufs")
                        val tp = o.optDouble("tp")
                        if (!lufs.isFinite() || !tp.isFinite()) continue
                        tracks[key] = TrackMeasure(
                            lufs = lufs,
                            truePeakDb = tp,
                            size = o.optLong("size"),
                            mtime = o.optLong("mtime")
                        )
                    }
                }
                root.optJSONObject("albums")?.let { obj ->
                    for (key in obj.keys()) {
                        val o = obj.optJSONObject(key) ?: continue
                        val lufs = o.optDouble("lufs")
                        val tp = o.optDouble("tp")
                        if (!lufs.isFinite() || !tp.isFinite()) continue
                        albums[key] = AlbumMeasure(
                            lufs = lufs,
                            truePeakDb = tp,
                            sig = o.optString("sig")
                        )
                    }
                }
            } catch (e: Exception) {
                // ★ 绝不能静默：否则表现是"测过的歌全没了"，却没有任何线索
                Log.e(TAG, "读 loudness.json 失败，将重新测量: $e")
                tracks.clear()
                albums.clear()
            }
        }
    }

    private fun saveLocked() {
        val f = file ?: return
        try {
            val root = JSONObject()
                .put("version", VERSION)
                .put("algo", ALGO)

            val t = JSONObject()
            for ((key, m) in tracks) {
                t.put(key, JSONObject().apply {
                    put("lufs", m.lufs)
                    put("tp", m.truePeakDb)
                    put("size", m.size)
                    put("mtime", m.mtime)
                })
            }
            root.put("tracks", t)

            val a = JSONObject()
            for ((key, m) in albums) {
                a.put(key, JSONObject().apply {
                    put("lufs", m.lufs)
                    put("tp", m.truePeakDb)
                    put("sig", m.sig)
                })
            }
            root.put("albums", a)

            // 临时文件 + rename：写到一半被杀不会留下半截 JSON
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                // 某些文件系统上目标存在时 rename 会失败，先删再试一次
                f.delete()
                if (!tmp.renameTo(f)) {
                    Log.w(TAG, "loudness.json 落盘失败")
                    return
                }
            }
            dirty = false
        } catch (e: Exception) {
            Log.e(TAG, "写 loudness.json 失败: $e")
        }
    }
}
