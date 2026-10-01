package com.hifiprobe

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 封面母版去重 —— **一次性**的整理。
 *
 * ══ 为什么要做 ══
 *
 * [Library.coverFileFor] 用**曲目相对路径**的哈希给母版命名，于是同一张专辑的
 * 12 首歌 = 同一张 3000×3000 的图，存 12 遍。实测（1675 首的库）：
 *
 * ```
 *   1675 个文件 / 1541.6 MB    →    188 份唯一内容 / 219.0 MB
 * ```
 *
 * **省 1.29 GB，而且一个像素都不用动。**
 *
 * ★ 为什么不是"把原图缩成小尺寸"：那样收益更小（219MB → 约 150MB）却**不可逆**
 *   —— 以后想要比 1280 更大的图（更大的屏幕、封面全屏查看），得把 1676 个
 *   音频文件全部重新提取一遍。按内容去重是无损、可逆的：图还是那张图。
 *
 * ══ 为什么只能是"改名 + 改指针" ══
 *
 * 硬链接是最省事的解法（名字不变、数据只占一份），但 `getExternalFilesDir`
 * 走的是 FUSE，`link()` 直接 **Permission denied**（实测过，toybox ln 报的）。
 * 所以只能把母版改成按内容命名，再回头改 [Track.coverPath]。
 *
 * ══ ★★ 三步都幂等、可中断、可恢复 ══
 *
 * 这件事最危险的形态是"**文件改了名、json 里还是旧路径**"—— 那会让一批封面
 * 直接变空。所以顺序是死的：
 *
 * ```
 *   ① 只读扫描：算出「旧名 → 内容指纹」，**先落一份 dedupe.map**
 *      （原子写 .part→rename。这一步一个文件都不动）
 *   ② 改名：按指纹分组，每组留一份改成 <指纹>.img，其余删掉；缩略图跟着合并
 *   ③ 落库：按 map 改 Track.coverPath 并存 library.json，
 *      **最后**才删 map
 * ```
 *
 * 「先备好依据 → 搬文件 → 改指针 → 删依据」，所以任何一步被杀，下次启动
 * 都能从 map 接着做；而 map 一旦消失，就说明指针已经落库了。
 * 唯一需要的重启条件是 [Settings.coverDedupeVersion] 还没写 —— 它只在
 * **完整成功**之后才写。
 *
 * ★ 最坏情况也不是崩溃：`coverPath` 悬空时 [CoverLoader.decodeCover] 返回 null，
 *   那一首退回**现画黑胶**，重新扫描音乐库即可恢复。
 */
object CoverDedupe {

    private const val TAG = "HiFiDedupe"
    private const val MAP_NAME = "cover_dedupe.map"
    const val VERSION = 1

    /**
     * ★★ 映射表**刻意不放在 `covers/` 里**。
     *
     *   `Library.pruneCovers()` 会删掉 covers 下所有"没有曲目引用"的文件，
     *   而这张表在它眼里就是垃圾 —— 放在里面会在下一次刷新时被静默删掉，
     *   而那正是"文件改了名、指针还是旧的"唯一能自救的依据。
     *
     *   放在它旁边（和 library.json 同一个目录）——两者本来就是配套的。
     */
    private fun mapFile(): File? =
        Library.coversDir.parentFile?.let { File(it, MAP_NAME) }

    /**
     * 迁移用的线程 —— **和封面解码分开**。
     *
     * ★ 不能借用 [CoverLoader] 那条线程：那是滚动时贴封面用的，
     *   把 1675 次读盘+哈希堆进去，用户一滚就是几百毫秒的空白。
     */
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cover-dedupe").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val scheduled = AtomicBoolean(false)

    /**
     * 由 [Library.refresh] 末尾调。**立刻返回** —— 迁移在后台跑，
     * 不拖慢这次扫描，也不拖慢它后面的 `onLibraryReady()`（队列恢复在等它）。
     */
    fun schedule(ctx: Context) {
        if (scheduled.getAndSet(true)) return
        val app = ctx.applicationContext
        exec.execute {
            try {
                if (Settings.coverDedupeVersion(app) >= VERSION) return@execute
                if (run()) {
                    Settings.setCoverDedupeVersion(app, VERSION)
                }
            } catch (e: Exception) {
                // 不写版本号 —— 下次启动会从 dedupe.map 接着做
                Log.w(TAG, "封面去重中断（下次启动继续）: ${e.message}")
            } finally {
                scheduled.set(false)
            }
        }
    }

    /** @return true 表示这一轮做完了（含"没什么可做"）；false 表示中途放弃，要重试 */
    private fun run(): Boolean {
        val covers = Library.coversDir
        if (!covers.isDirectory) return true
        val mapFile = mapFile() ?: return false

        // ① 只读扫描 —— 只在 map 不存在时做
        if (!mapFile.isFile) {
            val pairs = scan(covers) ?: return false
            if (pairs.isEmpty()) return true
            writeMap(mapFile, pairs)
        }

        val pairs = readMap(mapFile)
        if (pairs == null) {
            // 表本身读坏了：此时文件还一个都没动过，删掉重来是安全的
            mapFile.delete()
            return false
        }
        if (pairs.isEmpty()) {
            mapFile.delete()
            return true
        }

        // ② 改名。★ 库还没加载完就别动 —— 后面要按它改指针
        if (Library.all().isEmpty()) return false

        val groups = pairs.groupBy({ it.second }, { it.first })   // 指纹 → 旧文件名
        val thumbs = File(covers, "thumbs")

        for ((hash, names) in groups) {
            val newBase = "$hash.img"
            val target = File(covers, newBase)

            names.forEachIndexed { i, old ->
                val src = File(covers, old)
                if (i == 0) {
                    // 保留的那一份。★ 目标已存在 = 上一次跑到这儿被杀过，跳过
                    when {
                        target.isFile -> if (src.isFile) src.delete()
                        src.isFile -> if (!src.renameTo(target)) {
                            Log.w(TAG, "改名失败 $old → $newBase，本轮放弃")
                            return false
                        }
                    }
                } else {
                    if (src.isFile) src.delete()
                }
            }
            // 缩略图跟着合并：组里任何一份存在就搬过去，目标已有就删掉自己
            mergeThumbs(thumbs, names, newBase)
        }

        // ③ 落库。★ Library.remapCovers 内部会存盘，所以它返回之后 map 才可以删
        val oldToNew = HashMap<String, String>(pairs.size)
        pairs.forEach { (old, hash) -> oldToNew[old] = "$hash.img" }
        val changed = Library.remapCovers(oldToNew)

        Log.i(
            TAG,
            "封面去重完成：${pairs.size} 个母版 → ${groups.size} 份唯一内容" +
                "（曲目指针更新 $changed 条）"
        )
        mapFile.delete()
        return true
    }

    // ------------------------------------------------------------------

    /**
     * 只读扫描：算出每个母版的 [`文件名` → 内容指纹]。**一个文件都不动。**
     *
     * 1675 个文件、1.5GB 的读盘 + 哈希，实测约十几秒 —— 所以它在后台线程上，
     * 而且还是"先算完再动"：万一算到一半被杀，什么都没被破坏。
     */
    private fun scan(covers: File): List<Pair<String, String>>? {
        val files = covers.listFiles { f -> f.isFile && f.name.endsWith(".img") } ?: return null
        if (files.isEmpty()) return emptyList()

        val buf = ByteArray(1 shl 16)
        val out = ArrayList<Pair<String, String>>(files.size)
        for (f in files) {
            val fp = fingerprint(f, buf) ?: return null
            out.add(f.name to fp)
        }
        return out
    }

    /**
     * 内容指纹：SHA-256 取**前 8 字节**（16 个 hex 字符 = 64 位）。
     *
     * ★ 用 SHA-256 而不是 CRC32：CRC32 只有 32 位，188 份内容下碰撞概率
     *   虽然也低（约 4×10⁻⁶），但**碰撞的后果是显示成别人的专辑封面** ——
     *   这种错用户根本查不出来。多花的那点 CPU 换"不用想这件事"，值。
     */
    private fun fingerprint(f: File, buf: ByteArray): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val d = md.digest()
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append("%02x".format(d[i]))
        sb.toString()
    }.onFailure { Log.w(TAG, "算指纹失败 ${f.name}: ${it.message}") }.getOrNull()

    /**
     * 组内所有缩略图合并到新名字下。
     *
     * ★ 文件名怎么拼**问 [CoverLoader.thumbNamesFor] 要**，不在这里另写一份 ——
     *   和 `Library.pruneCovers` 是同一个理由：两处各写一份，改命名规则那天
     *   就会开始误删。
     *
     * ★ 必须**每一份都看**，不能只搬保留的那一份：用户可能只滚过这张专辑的
     *   第 5 首，于是缩略图挂在下标 5 的旧名字下，而"保留"的是下标 0 那个。
     */
    private fun mergeThumbs(thumbs: File, oldNames: List<String>, newBase: String) {
        if (!thumbs.isDirectory) return
        val targets = CoverLoader.thumbNamesFor(newBase)
        for (old in oldNames) {
            CoverLoader.thumbNamesFor(old).forEachIndexed { i, srcName ->
                val src = File(thumbs, srcName)
                if (!src.isFile) return@forEachIndexed
                val dst = File(thumbs, targets[i])
                if (dst.isFile || !src.renameTo(dst)) src.delete()
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 原子写映射表（`.part` → rename）—— 和无线传输、歌单落盘是同一套。
     *
     * ★ 这张表是**唯一的恢复依据**：它必须在任何文件被改动**之前**就完整落盘。
     *   写到一半被杀留下的是 `.part`，下次启动会重新扫一遍（文件还没动，安全）。
     */
    private fun writeMap(f: File, entries: List<Pair<String, String>>) {
        val part = File(f.parentFile, f.name + ".part")
        part.bufferedWriter().use { w ->
            entries.forEach { (name, hash) -> w.append(name).append(' ').append(hash).append('\n') }
        }
        if (!part.renameTo(f)) part.delete()
    }

    private fun readMap(f: File): List<Pair<String, String>>? = runCatching {
        f.readLines().mapNotNull { line ->
            val i = line.indexOf(' ')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
        }
    }.getOrNull()
}
