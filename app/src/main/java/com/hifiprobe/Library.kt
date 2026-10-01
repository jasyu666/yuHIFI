package com.hifiprobe

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文件库 —— app 私有的音乐目录。
 *
 * ★ 为什么用私有目录而不是引用外部文件夹：
 *
 *   · **播放路径最干净** —— 库内文件就是普通路径，直接开 fd 交给原生层，
 *     完全不碰 SAF 的 URI 授权（那东西会在清缓存、换机、卸载重装后失效）
 *   · **无线传输天然可用** —— HTTP 上传直接往这个目录落盘就完事
 *   · **播放列表里的"文件夹"语义清楚** —— 就是库里的真实子目录
 *
 *   代价是占双份空间（导入 = 复制）。对"精选曲库"这个定位是划算的。
 *
 * 目录布局：
 * ```
 * getExternalFilesDir(null)/
 *   ├── library/      音乐文件（SAF 导入 / 无线上传都落这里）
 *   │     └── 任意层级的子目录，列表按它分组
 *   ├── covers/       封面缓存（从内嵌图提取出来的）
 *   └── library.json  元数据索引缓存
 * ```
 */
object Library {

    private const val TAG = "HiFiLibrary"
    private const val INDEX_VERSION = 2

    /**
     * 支持的扩展名 —— **这是全工程唯一的一份权威名单**。
     *
     * 加新格式只要加在这里。但注意两点：
     *
     *  ① 「探测本身走 FFmpeg，不限格式」**只对了一半**：
     *     `third_party/_ff/build_ffmpeg.sh` 是 `--disable-everything` + 白名单，
     *     **没编进去的格式连探测都过不去**。加后缀之前先确认 FFmpeg 里有这个
     *     demuxer + decoder（查 `third_party/_ff/ffmpeg-7.1/config_components.h`，
     *     `=1` 才算真编进去了。★ 别搜 .so 符号，符号表被 strip 过）。
     *  ② 网页那边的 JS 白名单**从这里生成**（[audioExtRegex]），不要再各写一份。
     *
     * 详见 docs/08-音频格式支持.md。
     */
    val AUDIO_EXT = setOf(
        "flac", "alac", "m4a", "mp3", "aac", "wav", "aif", "aiff",
        "ape", "wv", "ogg", "opus", "dsf", "dff",
        // 2026-09-25 加：OGG / MATROSKA 容器本来就是开的，这两个纯粹是名单漏写
        "oga", "mka",
        // 2026-09-25 加：这两个要先把 asf demuxer + WMA 解码器编进 FFmpeg
        "wma",
    )

    /** 给网页 JS 用的扩展名正则（`\.(flac|alac|…)$` 里那一段）—— 一份名单两处用。 */
    fun audioExtRegex(): String = AUDIO_EXT.joinToString("|")

    private var appCtx: Context? = null

    lateinit var root: File
        private set
    lateinit var coversDir: File
        private set
    private lateinit var indexFile: File

    /**
     * 曲目 + 由它派生的目录索引，**打包成一个不可变对象**。
     *
     * ★★ 为什么必须打包：如果 tracks 和索引是两个各自 @Volatile 的字段，
     *   读者可能读到"**新的 tracks + 旧的索引**"—— 那种错位比单纯慢难查得多。
     *   一次 volatile 写换掉整组，读者永远看到自洽的一份。
     */
    private class Snap(
        val tracks: List<Track>,
        /** 目录（库内相对路径）→ **直接**放在它里的曲目 */
        val byDir: Map<String, List<Track>>,
        /** 目录 → 它的直接子目录。只含"子树里有曲目"的目录 */
        val childDirs: Map<String, List<String>>,
        /** 目录 → **递归**曲目数。音乐库每行显示的就是它，预先算好 */
        val count: Map<String, Int>
    ) {
        companion object {
            val EMPTY = Snap(emptyList(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    /** 当前快照。一次 volatile 写整体替换 —— 见 [Snap] 的说明 */
    @Volatile
    private var snap: Snap = Snap.EMPTY

    /**
     * 库里的曲目，按路径排序。**顺序稳定**，列表不会每次刷新都跳。
     *
     * ★ 只读。改曲目**必须**走 [replaceTracks]，那条路会顺手重建索引 ——
     *   写成 `private val` 就是让编译器帮忙挡住"忘了同步索引"的写法。
     */
    private val tracks: List<Track> get() = snap.tracks

    @Volatile
    var scanning: Boolean = false
        private set

    /** 扫描进度回调：(已处理, 总数)。在后台线程调用。 */
    var onScanProgress: ((Int, Int) -> Unit)? = null

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        root = File(base, "library").apply { mkdirs() }
        coversDir = File(base, "covers").apply { mkdirs() }
        indexFile = File(base, "library.json")
    }

    // ------------------------------------------------------------------
    //  曲目 + 目录索引
    // ------------------------------------------------------------------

    /** **唯一**修改曲目的入口。索引跟着一起重建，两者不可能不同步 */
    private fun replaceTracks(list: List<Track>) {
        snap = buildSnap(list)
    }

    /**
     * 建一份快照（曲目 + 目录索引）。
     *
     * ★★ 老实现就是卡顿的根因：`tracksIn` / `allTracksIn` 都是
     *   `tracks.filter { … }` —— **递归语义、全库实现**。而音乐库列表**每一行**
     *   都要算一次递归数量，于是画一屏 = 文件夹数 × 全库曲目数。
     *   2000 首 / 100 个文件夹 ≈ 20 万次 File 分配 + relativeTo，
     *   而且全在 `onBindViewHolder` 里 —— **主线程**。
     *
     *   建好索引之后：`tracksIn` 变 O(1)，`allTracksIn` 只扫子树，
     *   递归数量直接查表 —— 主线程上不再有跟库大小成正比的活。
     *
     * 建索引用的是**一次**遍历，且是在 `refresh()` 那条 IO 线程上做的，
     * 不额外多走一遍文件系统（曲目列表本来就拿到了）。
     */
    private fun buildSnap(list: List<Track>): Snap {
        val byDir = HashMap<String, MutableList<Track>>()
        for (t in list) {
            val p = t.localPath ?: continue
            val parent = File(p).parentFile ?: continue
            byDir.getOrPut(relativeOf(parent)) { ArrayList() }.add(t)
        }

        /*
         * 父子关系**直接从目录名推**，不查文件系统。
         *
         * ★ 这是有意的：这份索引只回答"哪些**曲目**在某目录下"。一个目录哪怕
         *   真实存在，只要整棵子树里没有曲目，就与它无关。
         *
         * ★★ 目录**本身**的存在性必须由 subFolders() / folders() 实时查盘回答，
         *    **不能进这份缓存** —— 否则新建的空文件夹会立刻看不见
         *    （createFolder 不调 refresh），而且上传页的 HAVE 重名预检、
         *    「上传到」下拉框、「移动到…」目标列表都会跟着少目录。
         */
        /*
         * ★★ 每一层祖先都要连上，**不能只连直接父目录**。
         *
         *   只连直接父目录的话，中间层会断：`柏林爱乐乐团/布列兹/` 自己一个
         *   文件都没有（文件在 `…/拉威尔/` 里），它不在 byDir 里，于是
         *   **没人建出「柏林爱乐乐团 → 布列兹」这条边** —— 递归数量从中间
         *   断开，界面显示「0 首」，多选勾中它也会"没有曲目"。
         *
         *   ★ 这个 bug 实测抓到过：柏林爱乐乐团「0 首 · 7 个子文件夹」。
         *     旧实现用字符串前缀匹配，任意深度都对，所以这是索引引入的**回归**。
         *     判据是**深度 ≥ 3**（中间层目录自己没有直接文件）。
         *
         * ★ 用 Set 去重：不同子目录会共享同一串祖先，逐条 add 必然重复。
         */
        val childDirs = HashMap<String, MutableSet<String>>()
        for (dir in byDir.keys) {
            var d = dir
            while (d.isNotEmpty()) {
                val parent = d.substringBeforeLast('/', "")
                childDirs.getOrPut(parent) { LinkedHashSet() }.add(d)
                d = parent
            }
        }

        /*
         * 递归数量：自底向上累加。**路径更长必然是更深的后代**，所以按长度倒序
         * 就是合法的拓扑序，不必真去算深度。
         *
         * ★★ 遍历的必须是 byDir 与 childDirs **键的并集**，不能只遍历 byDir：
         *   「专辑A/」里只放子文件夹、自己一个文件都没有时，byDir 里**没有**
         *   专辑A 这个键 —— 只遍历 byDir 的话它的数量算不出来，界面又会显示
         *   「0 首」。这个坑本项目踩过一次（见 bindFolder 的注释）。
         */
        val allDirs = HashSet<String>(byDir.size + childDirs.size)
        allDirs.addAll(byDir.keys)
        allDirs.addAll(childDirs.keys)

        val count = HashMap<String, Int>(allDirs.size)
        for (dir in allDirs.sortedByDescending { it.length }) {
            var n = byDir[dir]?.size ?: 0
            childDirs[dir]?.forEach { n += count[it] ?: 0 }
            count[dir] = n
        }

        // childDirs 建的时候用 Set 去重，这里固化成 List —— 快照必须不可变
        return Snap(list, byDir, childDirs.mapValues { it.value.toList() }, count)
    }

    fun all(): List<Track> = tracks

    fun size(): Int = tracks.size

    fun isEmpty(): Boolean = tracks.isEmpty()

    /** 库里所有子目录（相对 root 的路径），用于播放列表"加文件夹" */
    fun folders(): List<String> {
        val out = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isDirectory && it != root }
            .forEach { out += it.relativeTo(root).path }
        return out.sorted()
    }

    /**
     * 按专辑分组。**整个库，不分目录**。
     *
     * 「专辑」和「音乐库」是两种切法，不是一回事：
     * 音乐库按文件放在哪切，专辑按标签切。同一个专辑的文件被用户
     * 拆到两个文件夹里，在专辑页仍然应该是一张专辑。
     *
     * ★ `album` 标签缺失时，回落到**所在文件夹名**而不是一律叫"未知专辑" ——
     *   用户自己按专辑建的文件夹，名字就是最靠谱的专辑名。
     *   只有文件直接躺在库根目录、又没写标签时，才真的无从判断。
     *
     * 返回按专辑名排序，组内按音轨号排不了（Track 里没存音轨号），
     * 退而用文件名 —— 至少同一张专辑的顺序是稳定的。
     */
    fun albums(): List<Album> {
        val groups = LinkedHashMap<String, MutableList<Track>>()
        for (t in tracks) {
            val key = albumKeyOf(t)
            groups.getOrPut(key) { mutableListOf() }.add(t)
        }
        return groups.map { (name, list) ->
            Album(
                name = name,
                artist = list.mapNotNull { it.artist?.takeIf { a -> a.isNotBlank() } }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                tracks = list.sortedBy { it.localPath ?: it.uri }
            )
        }.sortedWith(compareBy({ it.name == UNKNOWN_ALBUM }, { it.name }))
    }

    const val UNKNOWN_ALBUM = "未知专辑"

    /** 专辑的归并键：优先标签，其次文件夹名 */
    private fun albumKeyOf(t: Track): String {
        t.album?.takeIf { it.isNotBlank() }?.let { return it }
        val parent = t.localPath?.let { File(File(it).absolutePath).parentFile }
        val folderName = parent?.name
        return if (!folderName.isNullOrBlank() && parent.absolutePath != root.absolutePath) {
            folderName
        } else UNKNOWN_ALBUM
    }

    // ------------------------------------------------------------------
    //  扫描
    // ------------------------------------------------------------------

    /**
     * 读缓存 + 增量扫描。
     *
     * **不要在主线程调**。首次扫描要给每个文件开一次 FFmpeg 读标签，
     * 几百首就是几十秒；之后靠缓存，只处理新增和变更的文件。
     */
    fun refresh() {
        val ctx = appCtx ?: return
        if (scanning) return
        scanning = true
        try {
            val cached = loadIndex()
            val files = collectAudioFiles()

            val result = ArrayList<Track>(files.size)
            var processed = 0
            for (f in files) {
                val rel = f.relativeTo(root).path
                val old = cached[rel]
                // 大小和修改时间都没变就直接用缓存 —— 这是扫描快的关键
                if (old != null && old.fileSize == f.length() && old.fileModified == f.lastModified()) {
                    result += old
                } else {
                    result += probe(ctx, f, rel)
                }
                processed++
                if (processed % 8 == 0 || processed == files.size) {
                    onScanProgress?.invoke(processed, files.size)
                }
            }

            replaceTracks(result.sortedBy { it.localPath ?: it.uri })
            saveIndex()
            // 清掉已删除文件遗留的封面缓存
            pruneCovers()
            /*
             * 封面母版去重（一次性）。
             *
             * ★ 放在这里、而且立刻返回：它要读 1.5GB 再加哈希，十几秒起步，
             *   同步做会把这次扫描和它后面的 onLibraryReady()（队列恢复在等）
             *   一起拖住。真正的活在它自己的线程上跑。
             */
            CoverDedupe.schedule(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "扫描失败: ${e.message}")
        } finally {
            scanning = false
        }
    }

    private fun collectAudioFiles(): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }
            .sortedBy { it.absolutePath }
            .toList()

    /** 探测单个文件。封面顺带提取到 covers/ 下，只回传字节数，不走 JNI 数组。 */
    private fun probe(ctx: Context, f: File, rel: String): Track {
        val uri = "file://${Uri.encode(f.absolutePath)}"
        val fallback = Track(uri = uri, title = f.nameWithoutExtension,
            fileSize = f.length(), fileModified = f.lastModified())

        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val coverPath = coverFileFor(rel).absolutePath
                val arr = NativePlayer.nativeProbeFile(pfd.fd, coverPath)
                if (arr.size < 9) return fallback

                val coverWritten = arr[8].toLongOrNull() ?: 0L
                fallback.copy(
                    title = arr[0].ifBlank { f.nameWithoutExtension },
                    artist = arr[1].ifBlank { null },
                    album = arr[2].ifBlank { null },
                    codec = arr[3],
                    sampleRate = arr[4].toIntOrNull() ?: 0,
                    channels = arr[5].toIntOrNull() ?: 0,
                    bitDepth = arr[6].toIntOrNull() ?: 0,
                    durationMs = arr[7].toLongOrNull() ?: 0L,
                    coverPath = if (coverWritten > 0) coverPath else ""
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "探测失败 ${f.name}: ${e.message}")
            fallback
        }
    }

    /**
     * 封面母版改名之后，把曲目指针换过来。**由 [CoverDedupe] 在后台调**。
     *
     * ★★ 这里必须**先落盘再返回** —— 调用方拿到返回值之后就会删掉映射表，
     *   顺序反了的话，中途被杀就是"文件改了名、json 还是旧路径"，那一批封面全丢。
     *
     * ★ 走 [replaceTracks] 而不是直接改 list：它会把 `byDir` / `count` 那套
     *   索引一起重建。绕过去的话，封面是对的、音乐库的文件夹计数会开始错乱。
     *
     * @param oldToNew 旧文件名 → 新文件名（**只是文件名，不含目录**）
     * @return 真正改掉的曲目数
     */
    fun remapCovers(oldToNew: Map<String, String>): Int {
        var changed = 0
        val next = tracks.map { t ->
            val name = t.coverPath.takeIf { it.isNotEmpty() }?.let { File(it).name }
            val newName = name?.let { oldToNew[it] }
            if (name == null || newName == null || newName == name) t
            else {
                changed++
                t.copy(coverPath = File(coversDir, newName).absolutePath)
            }
        }
        if (changed > 0) {
            replaceTracks(next)
            saveIndex()
        }
        return changed
    }

    /** 封面缓存文件名：用相对路径的哈希，避免同名文件互相覆盖 */
    private fun coverFileFor(rel: String): File {
        val name = rel.hashCode().toUInt().toString(16) + ".img"
        return File(coversDir, name)
    }

    /** 删掉已经没有对应曲目的封面缓存，别让它无限涨 */
    private fun pruneCovers() {
        /*
         * ★★ 去重没做完之前**一律不清理**。
         *
         *   迁移的中间状态恰好是「母版文件已改成内容名、曲目的 coverPath 还是旧名」
         *   —— 在下面那份 alive 看来，刚改好名的文件**全都是没人要的垃圾**，
         *   于是一次刷新就能把它们全删光，封面直接没了。
         *   （映射表被删同理：它是这个半截状态唯一能自救的依据。）
         *
         *   代价只是"去重做完之前磁盘不会回收"，而那是几分钟的事。
         */
        // ★ 括号不能省：`?:` 的优先级**低于** `<`，不括起来会解析成
        //   `ctx?.let{…} ?: (0 < VERSION)`，直接类型不匹配
        if ((appCtx?.let { Settings.coverDedupeVersion(it) } ?: 0) < CoverDedupe.VERSION) {
            Log.i(TAG, "封面去重尚未完成，跳过封面清理")
            return
        }

        val alive = tracks.mapNotNull { it.coverPath.takeIf { p -> p.isNotEmpty() } }
            .map { File(it).name }.toSet()
        coversDir.listFiles()?.forEach { if (it.isFile && it.name !in alive) it.delete() }

        /*
         * 缩略图（covers/thumbs/）跟着母版一起清。
         *
         * ★ 文件名怎么拼**问 CoverLoader 要**，不在这里另写一份 ——
         *   两处各写一份的话，哪天改命名规则，这里就会开始误删（或者漏删到磁盘涨满）。
         *
         * ★ `.part` 是上次写到一半被杀掉留下的残骸，一并清掉。
         */
        val aliveThumbs = HashSet<String>(alive.size * 2)
        alive.forEach { aliveThumbs += CoverLoader.thumbNamesFor(it) }
        File(coversDir, "thumbs").listFiles()?.forEach {
            if (it.name.endsWith(".part") || it.name !in aliveThumbs) it.delete()
        }
    }

    // ------------------------------------------------------------------
    //  导入
    // ------------------------------------------------------------------

    /**
     * 导入结果。
     *
     * ★ 为什么不是只回一个 Int：失败的文件原来**只进 logcat**，界面上只说
     *   「成功 N 个」—— 用户根本不知道少了几个。
     *   和网页上传那条"拖拽静默跳过读不到的文件"是**一模一样**的毛病。
     *   **"成功了"不等于"传全了"**，失败必须能报出来。
     */
    class ImportResult(val ok: Int, val failed: List<String>) {
        val total: Int get() = ok + failed.size
    }

    /**
     * 把一批 SAF URI 复制进库。
     *
     * @param subDir 放到库里的哪个子目录（空 = 根目录）
     */
    fun importUris(uris: List<Uri>, subDir: String = ""): ImportResult {
        val ctx = appCtx ?: return ImportResult(0, uris.map { it.toString() })
        val destDir = if (subDir.isBlank()) root else File(root, subDir).apply { mkdirs() }
        var ok = 0
        val failed = mutableListOf<String>()
        for (u in uris) {
            val name = runCatching { displayName(ctx, u) }.getOrNull()
                ?: "unnamed_${System.currentTimeMillis()}"
            var dest: File? = null
            try {
                dest = uniqueFile(destDir, name)
                ctx.contentResolver.openInputStream(u)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("打不开输入流")
                ok++
            } catch (e: Exception) {
                /*
                 * ★ 半截文件必须删掉。
                 *
                 *   copyTo 中途失败（U 盘被拔、空间不足）会在库里留下一个
                 *   **不完整**的文件，紧接着 Library.refresh() 会把它当成一首
                 *   完整的歌索引进去 —— 症状是"这首歌能列出来，播到一半就没了"，
                 *   而且完全看不出哪里不对。
                 */
                dest?.let { d -> runCatching { d.delete() } }
                failed += name
                Log.w(TAG, "导入失败 $u: ${e.message}")
            }
        }
        return ImportResult(ok, failed)
    }

    /**
     * 递归导入一个目录（SAF 的 tree URI）。
     *
     * 保留原有的子目录结构 —— 用户按专辑分好的目录不该被拍平，
     * 那正是后面"播放列表加文件夹"要用到的东西。
     */
    fun importTree(treeUri: Uri): ImportResult {
        val ctx = appCtx ?: return ImportResult(0, emptyList())
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return ImportResult(0, emptyList())
        var count = 0
        val failed = mutableListOf<String>()

        /*
         * ★ 被选中的那个文件夹**本身**也要在库里建出来。
         *
         *   原来 walk(rootDocId, "") 把选中的文件夹直接映射成"库根"，
         *   它的名字被丢掉了 —— 选 `贝多芬/` 导入，15 个文件全落在库根，
         *   不会有一层 `贝多芬/`。而网页拖拽**是保留的**（靠 filename 里的路径），
         *   两条路规则不一致，用户按专辑分好的目录就这么散了。
         *
         * ★ 例外：选的是**存储卷的根**（U 盘根 / 内部存储根）时不建这一层。
         *   那种 docId 形如 `1A2B-3C4D:` 或 `primary:`（冒号后面是空的），
         *   建出来会多一层卷标名（UDISK 之类），没有意义。
         */
        val topName = rootDocId.substringAfter(':', "")
            .trim('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() && it != "." && it != ".." }

        fun walk(dirDocId: String, relPath: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
            val destDir = if (relPath.isBlank()) root else File(root, relPath).apply { mkdirs() }
            ctx.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(docId, if (relPath.isBlank()) name else "$relPath/$name")
                    } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXT) {
                        var dest: File? = null
                        try {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            dest = uniqueFile(destDir, name)
                            ctx.contentResolver.openInputStream(fileUri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            } ?: throw IllegalStateException("打不开输入流")
                            count++
                        } catch (e: Exception) {
                            // 半截文件必须删掉，否则会被当成一首完整的歌索引进去（同 importUris）
                            dest?.let { d -> runCatching { d.delete() } }
                            failed += name
                            Log.w(TAG, "导入失败 $name: ${e.message}")
                        }
                    }
                }
            }
        }

        walk(rootDocId, topName.orEmpty())
        return ImportResult(count, failed)
    }

    fun delete(track: Track): Boolean {
        val p = track.localPath ?: return false
        val f = File(p)
        val gone = runCatching { f.delete() }.getOrDefault(false)
        if (gone) {
            track.coverPath.takeIf { it.isNotEmpty() }?.let { runCatching { File(it).delete() } }
            replaceTracks(tracks.filterNot { it.uri == track.uri })
            saveIndex()
        }
        return gone
    }

    /** 导入之后刷新单首（上传完 / 复制完立刻能用，不必全库重扫） */
    fun refreshOne(f: File): Track? {
        if (!f.isFile || f.extension.lowercase() !in AUDIO_EXT) return null
        // ★ 统一走 Paths.inside —— 手写的字符串前缀会放行 `…/files/library.json`
        if (!Paths.inside(root, f)) return null
        val rel = f.relativeTo(root).path
        val t = probe(appCtx ?: return null, f, rel)
        replaceTracks((tracks.filterNot { it.uri == t.uri } + t).sortedBy { it.localPath ?: it.uri })
        saveIndex()
        return t
    }

    // ------------------------------------------------------------------
    //  索引缓存
    // ------------------------------------------------------------------

    private fun loadIndex(): Map<String, Track> {
        if (!indexFile.isFile) return emptyMap()
        return runCatching {
            val json = JSONObject(indexFile.readText())
            if (json.optInt("version") != INDEX_VERSION) return emptyMap()
            val arr = json.optJSONArray("tracks") ?: return emptyMap()
            val map = HashMap<String, Track>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rel = o.optString("rel")
                if (rel.isBlank()) continue
                map[rel] = Track(
                    uri = o.optString("uri"),
                    title = o.optString("title"),
                    artist = o.optString("artist").ifBlank { null },
                    album = o.optString("album").ifBlank { null },
                    durationMs = o.optLong("durationMs"),
                    sampleRate = o.optInt("sampleRate"),
                    bitDepth = o.optInt("bitDepth"),
                    channels = o.optInt("channels"),
                    codec = o.optString("codec"),
                    coverPath = o.optString("coverPath"),
                    fileSize = o.optLong("fileSize"),
                    fileModified = o.optLong("fileModified")
                )
            }
            map
        }.getOrElse {
            Log.w(TAG, "索引读取失败，将全量重扫: ${it.message}")
            emptyMap()
        }
    }

    private fun saveIndex() {
        runCatching {
            val arr = JSONArray()
            for (t in tracks) {
                val rel = t.localPath?.let { File(it).relativeTo(root).path } ?: continue
                arr.put(JSONObject().apply {
                    put("rel", rel)
                    put("uri", t.uri)
                    put("title", t.title)
                    put("artist", t.artist ?: "")
                    put("album", t.album ?: "")
                    put("durationMs", t.durationMs)
                    put("sampleRate", t.sampleRate)
                    put("bitDepth", t.bitDepth)
                    put("channels", t.channels)
                    put("codec", t.codec)
                    put("coverPath", t.coverPath)
                    put("fileSize", t.fileSize)
                    put("fileModified", t.fileModified)
                })
            }
            indexFile.writeText(
                JSONObject().apply {
                    put("version", INDEX_VERSION)
                    put("tracks", arr)
                }.toString()
            )
        }.onFailure { Log.w(TAG, "索引写入失败: ${it.message}") }
    }

    // ------------------------------------------------------------------

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    //  文件夹
    // ------------------------------------------------------------------

    /**
     * [rel] 目录下的直接子目录名（不递归）。
     *
     * ★★ **实时查盘，刻意不走索引** —— 见 [buildSnap] 里的说明。
     *    目录的**存在性**必须问文件系统：新建的空文件夹要立刻看得见
     *    （`createFolder` 不调 `refresh`），上传页的 `HAVE` 重名预检、
     *    「上传到」下拉框、「移动到…」目标列表也都靠它。
     *    进索引的话，空目录会集体消失 —— 那正是这个改动最容易踩的坑。
     *
     *    代价是每个**可见行**一次 `listFiles()`（RecyclerView 只绑可见行，
     *    约十来行），可以接受；真正致命的是过去每行一次的 O(全库) 扫描。
     */
    fun subFolders(rel: String): List<String> {
        val dir = resolveDir(rel) ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /** [rel] 目录**直接**包含的曲目（不递归）。查表，O(1) */
    fun tracksIn(rel: String): List<Track> = snap.byDir[rel] ?: emptyList()

    /**
     * [rel] 目录**递归**包含的所有曲目。
     *
     * 和 [tracksIn]（只看直接子项）的区别，正是"多选一个文件夹"要的语义：
     * 勾中文件夹 = 勾中它整棵树里的歌。
     *
     * ★ 代价从 O(全库) 降到 **O(子树)** —— 这是索引带来的关键改善。
     */
    fun allTracksIn(rel: String): List<Track> {
        if (rel.isBlank()) return tracks
        val out = ArrayList<Track>()
        fun collect(dir: String) {
            snap.byDir[dir]?.let { out.addAll(it) }
            snap.childDirs[dir]?.forEach { collect(it) }
        }
        collect(rel)
        return out
    }

    /**
     * [rel] 目录**递归**包含的曲目数。查表，O(1)。
     *
     * ★ 音乐库列表每行显示的就是它，**必须和 `allTracksIn(rel).size` 一致** ——
     *   界面上的数量、删除确认里的数量、多选勾中的范围，三者一个口径。
     *   （曾经行内用递归、顶栏用直接子项，于是「专辑A/」显示成「0 首」。）
     */
    fun subtreeCount(rel: String): Int = snap.count[rel] ?: 0

    /** 曲目所在的库内相对目录 */
    fun folderOf(track: Track): String {
        val path = track.localPath ?: return ""
        val parent = File(path).parentFile ?: return ""
        return relativeOf(parent)
    }

    private fun relativeOf(dir: File): String =
        runCatching { dir.relativeTo(root).path }.getOrDefault("")

    /** 把相对路径解析成库内目录。**越界一律拒绝** —— 不能让上层拼出 ../.. */
    private fun resolveDir(rel: String): File? {
        if (rel.isBlank()) return root
        val d = File(root, rel)
        /*
         * ★ 用 Paths.inside，不要手写 startsWith。
         *
         *   原来这里少了分隔符：`…/files/library` 这个前缀会匹配上
         *   `…/files/library.json` —— 那是**索引文件本身**。
         *   输入只来自 App 自己的界面（列表里显示的路径），远程够不到，
         *   但同一个 bug 不该在工程里留两份写法。
         */
        return if (Paths.inside(root, d)) d else null
    }

    /**
     * 目录搬家之后收尾：把子树里所有曲目的 uri 改成新路径，并同步歌单。
     *
     * ★ 前缀比较**必须带分隔符**。直接 `p.startsWith(prefix)` 的话，
     *   目录「AB」的前缀会匹配上「ABC/xxx.flac」——
     *   重命名或删除「AB」就会连带改动「ABC」里的歌。
     *   这是那种平时不出现、一旦出现就丢数据的 bug。
     *
     * @return 改动的曲目条数
     */
    private fun rewriteSubtree(srcDir: File, destDir: File): Int {
        val oldPrefix = srcDir.canonicalPath + File.separator
        val newPrefix = destDir.canonicalPath + File.separator
        val remap = HashMap<String, String>()
        var n = 0
        replaceTracks(tracks.map { t ->
            val p = t.localPath ?: return@map t
            if (!p.startsWith(oldPrefix)) return@map t
            val nu = "file://" + Uri.encode(newPrefix + p.removePrefix(oldPrefix))
            remap[t.uri] = nu
            n++
            t.copy(uri = nu)
        })
        if (n > 0) {
            saveIndex()
            // 歌单存的是 uri —— 不同步的话，被挪动的歌会从歌单里静默消失
            Playlists.remapUris(remap)
        }
        return n
    }

    fun createFolder(parentRel: String, name: String): Boolean {
        // 剔除路径分隔符：用户输入里不该有路径成分，有就是恶意的
        val clean = name.trim().filter { it != '/' && it.code != 92 }.trim()
        if (clean.isEmpty()) return false
        val parent = resolveDir(parentRel) ?: return false
        return runCatching { File(parent, clean).mkdirs() }.getOrDefault(false)
    }

    /** 重命名或移动文件夹。目录为空时返回 true。 */
    fun renameFolder(rel: String, newName: String): Boolean {
        val clean = newName.trim().filter { it != '/' && it.code != 92 }.trim()
        if (clean.isEmpty()) return false
        val src = resolveDir(rel) ?: return false
        if (!src.isDirectory) return false
        val dest = File(src.parentFile, clean)
        if (dest.exists()) return false
        if (!runCatching { src.renameTo(dest) }.getOrDefault(false)) return false
        rewriteSubtree(src, dest)
        return true
    }

    /** 删掉一个文件夹**连同里面的音乐**。界面必须先确认。 */
    fun deleteFolder(rel: String): Boolean {
        val dir = resolveDir(rel) ?: return false
        if (dir == root) return false
        // 前缀必须带分隔符：不然删「AB」会把「ABC」里的歌也从索引里抹掉
        val prefix = dir.canonicalPath + File.separator
        val gone = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (gone) {
            val dead = tracks.filter { it.localPath?.startsWith(prefix) == true }
            replaceTracks(tracks.filterNot { it.localPath?.startsWith(prefix) == true })
            saveIndex()
            Playlists.removeUris(dead.map { it.uri }.toSet())
        }
        return gone
    }

    /** 批量删文件夹（连同里面的音乐）。返回真正删掉的个数 */
    fun deleteFolders(rels: List<String>): Int = rels.count { deleteFolder(it) }

    /**
     * 把一首曲目移到另一个目录。
     *
     * 用 renameTo 而不是"复制 + 删除" —— 同一个文件系统内是一次元数据操作，
     * 再大的文件也是瞬间完成。
     */
    fun moveTrack(track: Track, toRel: String): Boolean {
        val srcPath = track.localPath ?: return false
        val src = File(srcPath)
        if (!src.isFile) return false
        val destDir = resolveDir(toRel) ?: return false
        if (!destDir.exists() && !destDir.mkdirs()) return false
        if (src.parentFile?.canonicalPath == destDir.canonicalPath) return false   // 已经在那儿了

        val dest = uniqueFile(destDir, src.name)
        if (!runCatching { src.renameTo(dest) }.getOrDefault(false)) return false

        // 封面缓存是按"库内相对路径"取名的，路径变了缓存就失效了 —— 删掉旧的
        track.coverPath.takeIf { it.isNotEmpty() }?.let { runCatching { File(it).delete() } }
        val moved = probe(appCtx ?: return true, dest, dest.relativeTo(root).path)
        replaceTracks((tracks.filterNot { it.uri == track.uri } + moved)
            .sortedBy { t -> t.localPath ?: t.uri })
        saveIndex()
        // 歌单里存的是 uri —— 不跟着改的话，那首歌会从歌单里静默消失
        Playlists.remapUris(mapOf(track.uri to moved.uri))
        return true
    }

    /**
     * 批量删除。返回**真正删掉**的条数 —— 界面上要如实报，
     * 不能因为大部分成功就报"已全部删除"。
     *
     * 和逐首调 [delete] 的区别：索引只写一次盘。几百首的话，
     * 逐首写会卡好几秒（每次都是整个 JSON 序列化 + 落盘）。
     */
    fun deleteAll(list: List<Track>): Int {
        val gone = HashSet<String>(list.size)
        for (t in list) {
            val path = t.localPath ?: continue
            if (!runCatching { File(path).delete() }.getOrDefault(false)) continue
            t.coverPath.takeIf { it.isNotEmpty() }?.let { runCatching { File(it).delete() } }
            gone.add(t.uri)
        }
        if (gone.isEmpty()) return 0
        replaceTracks(tracks.filterNot { it.uri in gone })
        saveIndex()
        // 文件没了，歌单里引用的条目也一并去掉，否则会一直显示"N 首已失效"
        Playlists.removeUris(gone)
        return gone.size
    }

    /**
     * 批量把曲目移到另一个目录。返回真正移动的条数。
     *
     * 用 renameTo 而不是复制+删除 —— 同一个文件系统内是一次元数据操作，
     * 整张专辑也是瞬间完成。
     */
    fun moveTracks(list: List<Track>, toRel: String): Int {
        val ctx = appCtx ?: return 0
        val destDir = resolveDir(toRel) ?: return 0
        if (!destDir.exists() && !destDir.mkdirs()) return 0

        val remap = HashMap<String, String>()          // 旧 uri → 新 uri
        val landed = ArrayList<Pair<Track, File>>()

        for (t in list) {
            val path = t.localPath ?: continue
            val src = File(path)
            if (!src.isFile) continue
            // 已经在那儿了，跳过。不算失败也不算成功 —— 所以不计数
            if (src.parentFile?.canonicalPath == destDir.canonicalPath) continue
            val dest = uniqueFile(destDir, src.name)
            if (!runCatching { src.renameTo(dest) }.getOrDefault(false)) continue
            // 封面缓存按"库内相对路径"取名，路径变了缓存就失效了
            t.coverPath.takeIf { it.isNotEmpty() }?.let { runCatching { File(it).delete() } }
            remap[t.uri] = "file://" + Uri.encode(dest.absolutePath)
            landed.add(t to dest)
        }

        if (landed.isEmpty()) return 0

        val movedUris = landed.mapTo(HashSet()) { it.first.uri }
        /*
         * ★ 探测和排序都挪到循环**外面** —— 原来是在循环里
         *   `tracks = (tracks + probe()).sortedBy{…}`，**每移动一首就全量排序一次**，
         *   移 50 首就是 50 次全量排序。曲库一大（几千首）这里会明显卡住。
         */
        val after = tracks.filterNot { it.uri in movedUris }.toMutableList()
        for ((_, dest) in landed) after.add(probe(ctx, dest, dest.relativeTo(root).path))
        replaceTracks(after.sortedBy { it.localPath ?: it.uri })
        saveIndex()
        // ★ 歌单存的是 uri，文件挪了地方就悬空了。不重映射的话，
        //   用户会发现歌单莫名其妙少了歌，而文件其实好好躺在库里
        Playlists.remapUris(remap)
        return landed.size
    }

    /**
     * 批量把**文件夹**移到另一个目录下（作为它的子目录）。
     *
     * 和 [moveTracks] 是两回事：歌曲是平铺着挪过去，文件夹是**整棵子树**
     * 搬过去、内部结构保持不动。多选时如果勾了文件夹，就得走这条路，
     * 否则用户精心分好的专辑目录会被拍平。
     *
     * @return 真正搬走的文件夹个数
     */
    fun moveFolders(rels: List<String>, toRel: String): Int {
        val destParent = resolveDir(toRel) ?: return 0
        if (!destParent.exists() && !destParent.mkdirs()) return 0

        var n = 0
        for (rel in rels) {
            val src = resolveDir(rel) ?: continue
            if (!src.isDirectory) continue
            val srcPath = src.canonicalPath
            val destPath = destParent.canonicalPath
            // 不能把文件夹移进它自己或它的子孙里 —— 那会把整棵树弄丢
            if (destPath == srcPath || destPath.startsWith(srcPath + File.separator)) continue
            if (src.parentFile?.canonicalPath == destPath) continue      // 已经在那儿了

            val dest = File(destParent, src.name)
            if (dest.exists()) continue                                   // 同名，不覆盖
            if (!runCatching { src.renameTo(dest) }.getOrDefault(false)) continue
            rewriteSubtree(src, dest)
            n++
        }
        return n
    }

    /*
     * 这里原来有个 uniqueFilePublic，是给无线上传"同名加序号"用的。
     * 上传改成"同名直接覆盖"（用户明确要求）之后它就没人调了 —— 已删。
     * 下面私有的 uniqueFile 还在用：库内的导入/移动仍然不该覆盖。
     */

    /** 一张专辑：名字 + 主要艺术家 + 曲目 */
    data class Album(val name: String, val artist: String?, val tracks: List<Track>) {
        val size: Int get() = tracks.size
    }

    /** 同名文件加序号，不覆盖已有内容 */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return f
    }
}
