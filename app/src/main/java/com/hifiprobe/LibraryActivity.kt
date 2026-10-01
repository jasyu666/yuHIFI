package com.hifiprobe

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/**
 * 音乐库 —— App 的主界面。
 *
 * ★ 支持**进入子目录**。库里既然能建文件夹，列表就得分层 ——
 *   把几百首扁平铺开、再让用户自己去认路径，等于没有文件夹。
 *
 *   返回键是"退上一级"而不是退出应用，这是文件管理器的通用心智；
 *   进了文件夹按返回直接退出，用户会以为把东西弄丢了。
 *
 * 播放本身不在这里跑（那是 [PlayerSession] + [PlaybackService] 的事），
 * 这里只是一块显示屏加几个入口。
 */
class LibraryActivity : AppCompatActivity(), PlayerSession.Listener {

    /** 列表里的一行：可能是文件夹，也可能是曲目 */
    private sealed interface Row {
        data class Folder(val name: String, val rel: String) : Row
        data class Song(val track: Track) : Row
    }

    private lateinit var rvTracks: RecyclerView
    private lateinit var tvLibCount: TextView
    private lateinit var tvScanHint: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvDeviceHint: TextView
    private lateinit var tvBreadcrumb: TextView
    private lateinit var pbScan: android.widget.ProgressBar
    private lateinit var miniBar: MiniBar

    // ---- 多选 ----
    private val sel = Selection()
    private lateinit var selBar: SelectionBar
    private lateinit var selHeader: SelectionHeader
    private lateinit var btnSelect: View
    private lateinit var btnSelectAll: android.widget.Button

    /**
     * 选中键。
     *
     * ★ **只有曲目，一律用 uri。文件夹不占键。**
     *   某个文件夹选没选中，是从它树里的叶子**算出来**的
     *   （见 [selFolders] 和 bindFolder 的三态圈）。
     *
     * ★ 这里原来还有一个 "d:" + 相对路径的容器键。但它**只有「全选」
     *   写得进去**，手动点文件夹写进去的是叶子 uri —— 两条路对不上，
     *   坑了一整轮（详见 [selFolders]）。容器键已删。
     */
    private fun songKey(t: Track) = t.uri

    /** 上一次画高亮时正在播的是哪首。心跳 200ms 一次，内容没变就别重画 */
    private var lastPlayingUri: String? = null

    private val adapter = TrackAdapter()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "library-io") }

    /** 当前所在目录（库内相对路径，空 = 根） */
    private var folder: String = ""

    // ---- 导入 ----

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        // 导入到**当前所在目录** —— 用户刚建了文件夹进来，东西就该落在这儿
        importThenRefresh("导入 ${uris.size} 个文件") { Library.importUris(uris, folder) }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        importThenRefresh("导入整个文件夹") { Library.importTree(uri) }
    }

    private fun importThenRefresh(label: String, work: () -> Library.ImportResult) {
        tvScanHint.text = "$label…"
        io.execute {
            val r = work()
            Library.refresh()
            runOnUiThread {
                val msg = buildString {
                    append("$label：成功 ${r.ok} 个")
                    if (r.failed.isNotEmpty()) {
                        append("　⚠ 另有 ${r.failed.size} 个无法读取：")
                        append(r.failed.take(3).joinToString("、"))
                        if (r.failed.size > 3) append(" …")
                    }
                }
                tvScanHint.text = msg
                /*
                 * ★ 有失败时额外弹一次 toast。
                 *
                 *   tvScanHint 是列表上方那行小字，导完文件夹的人通常直接去翻列表，
                 *   很容易错过。而"少了几个文件"这件事**必须被看见** ——
                 *   "成功了"不等于"传全了"，这个项目已经在这上面栽过一次。
                 */
                if (r.failed.isNotEmpty()) toast(msg)
                refreshList()
            }
        }
    }

    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        PlayerSession.init(applicationContext)
        Library.init(this)

        rvTracks = findViewById(R.id.rvTracks)
        tvLibCount = findViewById(R.id.tvLibCount)
        tvScanHint = findViewById(R.id.tvScanHint)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvDeviceHint = findViewById(R.id.tvDeviceHint)
        tvBreadcrumb = findViewById(R.id.tvBreadcrumb)
        pbScan = findViewById(R.id.pbScan)

        rvTracks.layoutManager = LinearLayoutManager(this)
        rvTracks.adapter = adapter

        Ui.applySystemBars(findViewById(R.id.rootLibrary))

        findViewById<Button>(R.id.btnImport).setOnClickListener { showImportDialog() }
        findViewById<Button>(R.id.btnNewFolder).setOnClickListener { askNewFolder() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        tvBreadcrumb.setOnClickListener { goUp() }

        val btnBack = findViewById<ImageButton>(R.id.btnLibBack)
        btnBack.setOnClickListener { if (sel.active) exitSelect() else finish() }
        val btnImport = findViewById<Button>(R.id.btnImport)
        val btnNewFolder = findViewById<Button>(R.id.btnNewFolder)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        selBar = SelectionBar(this)
        btnSelect = findViewById(R.id.btnSelect)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        // 多选时导入/新建/设置全收起来 —— 它们都会改动列表，
        // 一边选着一边列表被改了，"我选了哪几首"立刻说不清
        selHeader = SelectionHeader(btnBack, tvLibCount, listOf(btnImport, btnNewFolder, btnSettings))
        btnSelect.setOnClickListener { enterSelect() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        miniBar = MiniBar(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sel.active) {
                    // 多选优先：选到一半按返回，用户要的是"取消选择"，
                    // 不是"退到上一级、选择也一起没了"
                    exitSelect()
                } else if (folder.isNotEmpty()) {
                    goUp()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (Library.isEmpty()) scanFirstTime()
    }

    private fun scanFirstTime() {
        io.execute {
            runOnUiThread { pbScan.visibility = View.VISIBLE }
            Library.onScanProgress = { done, total ->
                runOnUiThread { tvScanHint.text = "扫描中 $done / $total" }
            }
            Library.refresh()
            runOnUiThread {
                Library.onScanProgress = null
                pbScan.visibility = View.GONE
                tvScanHint.text = ""
                refreshList()
                // ★ 扫完才知道 uri 对应哪首曲目 —— 队列恢复要等这一刻
                PlayerSession.onLibraryReady()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.addListener(this)
        refreshList()
        renderSession()
        /*
         * 只为刷新设备提示行。真正保证"插上就能用"的是 [App.onCreate] 里的
         * DeviceGate.install 和播放前的兜底，这里删掉也不会影响播放。
         * ensureOpen 是幂等的 —— 设备已就绪时立刻回调成功，没有额外开销。
         */
        DeviceGate.ensureOpen(this) { ok, msg ->
            tvDeviceHint.visibility = if (ok) View.GONE else View.VISIBLE
            if (!ok) tvDeviceHint.text = msg
        }
    }

    override fun onPause() {
        super.onPause()
        PlayerSession.removeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        /*
         * ★ 这里原来有 DeviceGate.detach(this)，已经删掉。
         *
         *   插拔广播现在挂在 applicationContext 上（见 DeviceGate.install），
         *   生命周期跟进程走。以前挂在 Activity 上、onDestroy 时注销，
         *   注销的还是**全局唯一**那个接收器 —— 于是本页一销毁，
         *   别的页面正在等的 USB 授权回调也一起没了。
         */
        io.shutdown()
    }

    // ------------------------------------------------------------------
    //  多选
    // ------------------------------------------------------------------

    private fun enterSelect() {
        sel.start()
        applySelectMode()
    }

    private fun exitSelect() {
        sel.stop()
        applySelectMode()
    }

    private fun applySelectMode() {
        val on = sel.active
        btnSelect.visibility = if (on) View.GONE else View.VISIBLE
        btnSelectAll.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            selHeader.enter()
            miniBar.setSuppressed(true)
        } else {
            selHeader.exit()
            miniBar.setSuppressed(false)
        }
        adapter.notifyDataSetChanged()
        renderSelection()
    }

    private fun renderSelection() {
        if (!sel.active) {
            selBar.hide()
            /*
             * ★ 退出多选必须把副标题改回来。
             *
             *   这里原来直接 return —— 于是那行字一直停在上面设的「已选 N 项」，
             *   直到列表因为别的原因重画（进一次文件夹、或离开再回来）。
             */
            renderLibCount()
            return
        }
        tvLibCount.text = getString(R.string.sel_count_fmt, sel.size)

        // 文字按钮的标签必须说实话：全选状态下点下去是**取消**全选，
        // 还写着「全选」的话用户不敢点
        btnSelectAll.text = getString(
            if (sel.containsAll(allKeys())) R.string.action_deselect_all
            else R.string.action_select_all
        )
        val tracks = selectedTracks()
        selBar.show(sel.size, listOf(
            /*
             * ★ 「播放选中」放在第一位，和其它列表页保持一致。
             *
             *   队列 = 选中的这些，**含被勾中文件夹里的全部歌**（selectedTracks
             *   本来就是递归取的），按列表顺序，从第一首开始。
             *   语义和"点进文件夹点歌"是一套，不引入特例。
             */
            SelectionBar.Action(getString(R.string.action_play_selected)) {
                if (tracks.isEmpty()) {
                    toast("此处无可播放的曲目")
                } else {
                    PlayerSession.setQueue(tracks, 0)
                    startActivity(Intent(this@LibraryActivity, NowPlayingActivity::class.java))
                }
                exitSelect()
            },
            SelectionBar.Action(getString(R.string.action_add_to_playlist_short)) {
                BatchActions.addToPlaylist(this, tracks)
                exitSelect()
            },
            SelectionBar.Action(getString(R.string.action_move_to)) {
                /*
                 * ★ 传**散歌**，不是 tracks。
                 *
                 *   整棵树被勾中的文件夹已经由 selFolders() 交给 moveFolders
                 *   整体搬走了；再把这些歌单独列一遍，轮到 moveTracks 时
                 *   文件已经不在原位，只会一节节静默跳过，报出来的数字也是错的。
                 */
                BatchActions.moveTo(this, selectedLooseSongs(), selFolders()) { exitSelect() }
            },

            SelectionBar.Action(getString(R.string.action_delete_selected)) {
                confirmDelete()
            }
        ))
    }

    /**
     * 删除确认。
     *
     * ★ 两件事必须说清楚，否则用户不知道自己按下去会丢什么：
     *
     *   1. **文件夹和歌分别是多少**。只报一个总数的话，勾了一个文件夹
     *      （里面有 20 首）的人看到"删除 3 项"，会以为只是三首歌。
     *   2. **文件夹是连子目录一起删的**。这是不可逆里最不可逆的一种。
     */
    private fun confirmDelete() {
        val folders = selFolders()
        val loose = selectedLooseSongs()
        val inFolders = folders.sumOf { Library.allTracksIn(it).size }
        val total = loose.size + inFolders

        val detail = buildString {
            if (loose.isNotEmpty()) append("单曲 ${loose.size} 首")
            if (folders.isNotEmpty()) {
                if (isNotEmpty()) append("、")
                append("文件夹 ${folders.size} 个（含其中 $inFolders 首）")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("删除 $total 首？")
            .setMessage(
                "音乐库中将删除：$detail。\n\n" +
                        "文件夹将连同子目录一并删除。文件无法恢复，" +
                        "引用它们的歌单将一并移除这些条目。"
            )
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    // ★ 顺序：**先删歌再删文件夹**。反过来的话，文件夹里的文件
                    //   已经随目录没了，再逐首删只会一路失败，报出来的数字也是错的
                    val ns = Library.deleteAll(loose)
                    val nf = Library.deleteFolders(folders)
                    runOnUiThread {
                        toast(
                            if (nf > 0) "已删除 $ns 首、$nf 个文件夹" else "已删除 $ns 首"
                        )
                        exitSelect()
                        refreshList()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 选中的曲目。
     *
     * ★ 顺序 = **界面顺序**：本层各个子文件夹（各自整棵树）在前、
     *   本层散歌在后 —— 和 refreshList 的 `folders + songs` 一致。
     *   队列顺序和屏幕上看到的顺序对不上，是很显眼的别扭。
     *
     * ★ 不问"哪些文件夹被勾中了"，而是**逐个可见曲目问一句"你被选了吗"**。
     *   因为选中集合里只有叶子，这样**部分勾选**的文件夹也能自然地
     *   只贡献它被选中的那几首，且落在正确的顺序位上。
     *
     * ★ 进/出子目录**不清空选择**（enterFolder / goUp 只改 folder），
     *   所以集合里可能留着别的目录里的歌。`out.size < keys.size` 就说明
     *   有没接上的，补一遍全库 —— 否则"进去勾几首、退回来再勾个文件夹"
     *   会把前面那几首静默吞掉，界面上还显示着"已选 12 项"。
     */
    private fun selectedTracks(): List<Track> {
        val keys = sel.snapshot()
        if (keys.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Track>()
        for (sub in Library.subFolders(folder)) {
            Library.allTracksIn(joinRel(folder, sub))
                .filter { songKey(it) in keys }
                .forEach { out[it.uri] = it }
        }
        Library.tracksIn(folder).filter { songKey(it) in keys }.forEach { out[it.uri] = it }
        if (out.size < keys.size) {
            Library.allTracksIn("").forEach {
                if (it.uri !in out && songKey(it) in keys) out[it.uri] = it
            }
        }
        return out.values.toList()
    }

    /**
     * 选中的歌里，**不属于那些"整棵树都被勾中"的文件夹**的那些。
     *
     * 「移动到…」和「删除」要的是这个数，不是 [selectedTracks]：
     * 文件夹是**整体**搬 / 整体删的，再把它里面的歌单独列一遍，
     * 动作就做重了 —— 文件夹搬走之后那些文件已经不在原位。
     */
    private fun selectedLooseSongs(): List<Track> {
        val grouped = HashSet<String>()
        for (rel in selFolders()) Library.allTracksIn(rel).forEach { grouped.add(it.uri) }
        return selectedTracks().filter { it.uri !in grouped }
    }

    /**
     * 本层里**整棵树都被勾中**的文件夹（库内相对路径）。
     *
     * ★ 必须这么推导，不能去查某个容器键 —— 选中集合里只有叶子
     *   （见 Selection.setGroup 的说明：容器不记自己的选中态，否则会
     *   制造第二份真相）。bindFolder 的三态圈也是按叶子数画的
     *   （countIn(allTracksIn)），这里口径必须和它一致。
     *
     * ★ 修之前这里是 `filter { it.startsWith("d:") }`，而 "d:" 键只有
     *   「全选」写得进去。于是**手动勾一个文件夹**之后：
     *   「播放选中项」拿到空队列，只弹一句「此处无可播放的曲目」；
     *   「删除」弹「删除 0 首？」；「移动到…」和「添加到歌单」什么都不做。
     *   同一个根因，四个动作一起坏。
     */
    private fun selFolders(): List<String> =
        Library.subFolders(folder).map { joinRel(folder, it) }.filter { rel ->
            val group = Library.allTracksIn(rel).map { it.uri }
            sel.containsAll(group)
        }

    /** 当前列表里所有可选的键 —— **全是叶子 uri**，一个容器键都没有 */
    private fun allKeys(): List<String> =
        Library.subFolders(folder).flatMap { sub ->
            Library.allTracksIn(joinRel(folder, sub)).map { songKey(it) }
        } + Library.tracksIn(folder).map { songKey(it) }

    private fun toggleSelectAll() {
        val all = allKeys()
        sel.setGroup(all, !sel.containsAll(all))
        adapter.notifyDataSetChanged()
        renderSelection()
    }

    private fun toggleSong(t: Track, position: Int) {
        sel.toggle(songKey(t))
        adapter.notifyItemChanged(position)
        renderSelection()
    }

    /**
     * 勾一个文件夹 = 勾中它**整棵树**里的歌。
     *
     * 界面上只显示本层的直接子项，所以子目录里的歌看不见 ——
     * 因此必须整表重画：本层如果有直接放在这个文件夹里的歌，它们的状态也变了。
     */
    private fun toggleFolder(rel: String, position: Int) {
        val group = Library.allTracksIn(rel).map { it.uri }
        if (group.isEmpty()) {
            // 空文件夹没东西可选。给个反馈，不然点了没反应像是坏了
            toast("该文件夹内无曲目")
            return
        }
        sel.setGroup(group, !sel.containsAll(group))
        adapter.notifyDataSetChanged()
        renderSelection()
    }

    // ------------------------------------------------------------------
    //  目录导航
    // ------------------------------------------------------------------

    private fun enterFolder(rel: String) {
        folder = rel
        refreshList()
    }

    private fun goUp() {
        folder = folder.substringBeforeLast('/', "")
        refreshList()
    }

    /**
     * 重画列表。
     *
     * 曲目**只取当前目录直接包含的**（不递归）—— 递归的话子文件夹里的歌
     * 会同时出现在上下两层，用户会以为文件被复制了一份。
     */
    private fun refreshList() {
        /*
         * ★ 计时观测。
         *
         *   这一页卡过一次，根因是每行 bind 都去全库扫一遍（O(文件夹 × 全库)），
         *   而那是**看不见的** —— 用户只会说"卡"，说不出卡在哪。
         *   把"这一次列目录花了几毫秒、列了几项"变成一行字，下次再卡就有的对。
         *
         *   ★ 判据是**曲目数**：几百首的时候这行应该是 1~3ms。要是哪天看到
         *     几百毫秒，说明又有人往这条路径上加了按曲目数增长的东西。
         */
        val t0 = SystemClock.elapsedRealtime()

        val folders = Library.subFolders(folder).map { Row.Folder(it, joinRel(folder, it)) }
        val songs = Library.tracksIn(folder).map { Row.Song(it) }
        adapter.submit(folders + songs)

        Log.i(
            "HiFiLib",
            "列目录「${folder.ifEmpty { "/" }}」：${folders.size} 个子文件夹 + ${songs.size} 首" +
                "，耗时 ${SystemClock.elapsedRealtime() - t0} ms"
        )

        tvBreadcrumb.text = if (folder.isEmpty()) "" else "‹  返回上级        /$folder"
        tvBreadcrumb.visibility = if (folder.isEmpty()) View.GONE else View.VISIBLE

        renderLibCount(folders.size)

        val empty = folders.isEmpty() && songs.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvTracks.visibility = if (empty) View.GONE else View.VISIBLE
        tvEmpty.text = if (folder.isEmpty()) getString(R.string.empty_library)
        else "该文件夹为空\n\n长按曲目选择「移动到…」可将其移入"
    }

    private fun joinRel(parent: String, name: String) =
        if (parent.isEmpty()) name else "$parent/$name"

    /**
     * 顶栏那行计数。**它是这个 TextView 的唯一源头** —— [refreshList] 和
     * [renderSelection] 都必须走它，否则两处会各写各的。
     *
     * ★★ 它以前有两个毛病，都是"口径不一致"造成的：
     *
     *   一、**库根永远显示「0 首」**。用的是 `tracksIn`（只数直接躺在本层的
     *       文件），而音乐都在专辑文件夹里；偏偏"音乐库内共 N 首"那句又写在
     *       `if (folder.isNotEmpty())` 里，库根压根看不到总数。两个一凑，
     *       库根永远是「0 首 · N 个文件夹」。
     *
     *   二、**退出多选后卡在「已选 N 项」**。[renderSelection] 在 `!sel.active`
     *       时直接 return，没把文字改回来。而当初为此存的 `titleBeforeSelect`
     *       **存了却从没被读过**，是死代码 —— 线索就摆在那儿。
     *
     * 口径统一用**递归**：和下面每一行文件夹显示的数量、多选勾中的范围一致。
     * （行内显示递归、顶栏显示直接子项，摆在一起就是自相矛盾。）
     *
     * @param subCount 子文件夹个数。调用方已经算过就传进来，省一次目录列举；
     *                 不传则自己查（如退出多选时）。
     */
    private fun renderLibCount(subCount: Int = Library.subFolders(folder).size) {
        // ★ 库根那一下是零成本的：allTracksIn("") 直接 early-return tracks
        val n = Library.allTracksIn(folder).size
        tvLibCount.text = buildString {
            append("$n 首")
            if (subCount > 0) append(" · $subCount 个文件夹")
            if (folder.isNotEmpty()) append("     音乐库内共 ${Library.size()} 首")
        }
    }

    // ------------------------------------------------------------------
    //  文件夹操作
    // ------------------------------------------------------------------

    private fun askNewFolder() {
        val input = EditText(this).apply { hint = "文件夹名" }
        AlertDialog.Builder(this)
            .setTitle(if (folder.isEmpty()) "新建文件夹" else "在 /$folder 下新建")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) {
                    io.execute {
                        val ok = Library.createFolder(folder, name)
                        runOnUiThread {
                            if (ok) refreshList() else toast("创建失败：名称为空或已存在")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 文件夹的长按菜单。
     *
     * ★ 「加到歌单」和「加到队尾」是对**整棵子树**说的，不是本层 ——
     *   和文件夹行上显示的数量、以及多选勾文件夹的口径保持一致。
     *
     *   这两项以前没有：文件夹只能重命名或删掉，想把它里面的歌攒成一个歌单
     *   就得先勾选（还要知道多选在哪）。而专辑那边早就有
     *   「整张加到队尾 / 整张加到歌单」了 —— 同样是"一堆歌"，入口不该两样。
     */
    private fun folderMenu(row: Row.Folder) {
        AlertDialog.Builder(this)
            .setTitle(row.name)
            .setItems(arrayOf("添加至歌单…", "添加至播放队列", "重命名", "删除（连同其中的曲目）")) { _, which ->
                // allTracksIn 是在内存索引上过滤，很快，不必下 io 线程
                val songs = Library.allTracksIn(row.rel)
                when (which) {
                    0 -> if (songs.isEmpty()) toast("该文件夹内无曲目")
                    else BatchActions.addToPlaylist(this, songs)

                    1 -> if (songs.isEmpty()) toast("该文件夹内无曲目")
                    else {
                        PlayerSession.addToQueue(songs)
                        toast("已将 ${songs.size} 首添加至播放队列")
                    }

                    2 -> askRenameFolder(row)
                    3 -> confirmDeleteFolder(row)
                }
            }
            .show()
    }

    private fun askRenameFolder(row: Row.Folder) {
        val input = EditText(this).apply { setText(row.name) }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val n = input.text.toString()
                io.execute {
                    val ok = Library.renameFolder(row.rel, n)
                    runOnUiThread { if (ok) refreshList() else toast("重命名失败：名称为空或已存在") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteFolder(row: Row.Folder) {
        // ★ 用递归数量，和列表行上显示的、以及实际会删掉的保持一致。
        //   原来显示的是 tracksIn（只数直接子文件），文案却写着"子文件夹里的也算" ——
        //   自相矛盾，而且和行上那个数字对不上。
        val n = Library.subtreeCount(row.rel)
        AlertDialog.Builder(this)
            .setTitle("删除文件夹「${row.name}」？")
            .setMessage(
                "其中的曲目也将一并删除，无法恢复。" +
                        if (n > 0) "\n\n（直接包含 $n 首，子文件夹中的亦计入）" else ""
            )
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    val ok = Library.deleteFolder(row.rel)
                    runOnUiThread { if (ok) refreshList() else toast("删除失败") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把一首曲目移到别的目录。列出所有目录让用户挑。 */
    private fun askMoveTo(track: Track) {
        val dirs = buildList {
            add("" to "（音乐库根目录）")
            Library.folders().forEach { add(it to it) }
        }
        AlertDialog.Builder(this)
            .setTitle("移动「${track.displayTitle}」到")
            .setItems(dirs.map { it.second }.toTypedArray()) { _, which ->
                io.execute {
                    val ok = Library.moveTrack(track, dirs[which].first)
                    runOnUiThread {
                        if (ok) {
                            toast("已移动")
                            refreshList()
                        } else toast("移动失败（可能已在该位置）")
                    }
                }
            }
            .show()
    }

    private fun showImportDialog() {
        AlertDialog.Builder(this)
            .setTitle("导入音乐" + if (folder.isEmpty()) "" else "（到 /$folder）")
            .setItems(
                arrayOf("选择文件（可多选）", "选择整个文件夹", "无线传输（电脑浏览器上传）")
            ) { _, which ->
                when (which) {
                    0 -> pickFiles.launch(arrayOf("audio/*"))
                    1 -> pickFolder.launch(null)
                    2 -> startActivity(Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_OPEN_WIRELESS, true))
                }
            }
            .show()
    }

    // ---- PlayerSession.Listener ----

    override fun onSessionLog(text: String) {
        // 主界面不铺日志 —— 那是诊断页的事。只留错误，避免和迷你条说同一件事
        if (text.startsWith("✗")) tvScanHint.text = text
    }

    override fun onSessionStateChanged() = renderSession()

    private fun renderSession() {
        miniBar.render()

        /*
         * 列表只在**真的换歌**时才重画。
         *
         * 播放中心跳 200ms 一次，每次都会通知到这里。无脑重画的话一秒五次，
         * 滚动位置会闪、封面会反复解码 —— 而换歌本身是低频事件。
         */
        val now = PlayerSession.current?.uri?.takeIf { PlayerSession.isActive }
        if (now != lastPlayingUri) {
            lastPlayingUri = now
            adapter.notifyDataSetChanged()
        }
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    private inner class TrackAdapter : RecyclerView.Adapter<TrackAdapter.VH>() {

        private var rows: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_track, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            when (val r = rows[position]) {
                is Row.Folder -> bindFolder(holder, r, position)
                is Row.Song -> bindSong(holder, r.track, position)
            }
        }

        private fun bindFolder(holder: VH, r: Row.Folder, position: Int) {
            /*
             * ★ 数量必须是**递归**的（整棵子树）。
             *
             *   这里原来用 tracksIn —— 它只看本层的**直接子文件**，
             *   于是「专辑A/」这种只放子文件夹的目录会显示成「0 首」，
             *   而同一个文件里多选、删除、长按菜单用的都是递归的 allTracksIn，
             *   口径根本不一致。
             *
             *   只算一次，下面三态勾选复用它 —— 别在 bind 里把全库扫两遍。
             *
             * ★★ 现在改成**查表**（subtreeCount，O(1)）。这一行曾经是音乐库
             *    卡顿的根因：每个文件夹行、每次 bind 都 allTracksIn 全库扫一遍，
             *    一屏就是「文件夹数 × 全库曲目数」—— 2000 首 / 100 个文件夹
             *    ≈ 20 万次 File 分配，而且全在 onBindViewHolder 里（主线程）。
             *
             * ★ 但**勾选范围**仍然要 allTracksIn：数量口径和勾选口径必须一致。
             *    它现在只扫子树（O(子树)），而且只在多选状态下才算。
             */
            val songCount = Library.subtreeCount(r.rel)
            val subs = Library.subFolders(r.rel).size
            holder.cover.imageTintList =
                android.content.res.ColorStateList.valueOf(Ui.c(this@LibraryActivity, R.color.brand))
            holder.cover.setImageResource(R.drawable.ic_folder)
            holder.title.text = r.name
            holder.subtitle.text = buildString {
                append("$songCount 首")
                if (subs > 0) append("  ·  $subs 个子文件夹")
            }
            holder.spec.text = "›"
            holder.spec.setTextColor(Ui.c(this@LibraryActivity, R.color.brand))
            holder.spec.background = null

            /*
             * 文件夹的勾选圈是**三态**的：全选 / 半选 / 未选。
             *
             * ★ 半选态不能省。勾文件夹会把里面的歌全选上，如果半选也画成
             *   空心圈，用户就看不出"再点一下是全选还是全取消"。
             */
            holder.check.visibility = if (sel.active) View.VISIBLE else View.GONE
            if (sel.active) {
                val group = Library.allTracksIn(r.rel).map { it.uri }
                val picked = sel.countIn(group)
                val icon = when {
                    group.isEmpty() -> R.drawable.ic_check_off
                    picked == 0 -> R.drawable.ic_check_off
                    picked == group.size -> R.drawable.ic_check_on
                    else -> R.drawable.ic_check_partial
                }
                val tone = if (picked == 0) R.color.text_secondary else R.color.brand
                holder.check.setImageResource(icon)
                holder.check.imageTintList =
                    android.content.res.ColorStateList.valueOf(Ui.c(this@LibraryActivity, tone))
            }

            holder.itemView.setOnClickListener {
                if (sel.active) toggleFolder(r.rel, position) else enterFolder(r.rel)
            }
            holder.itemView.setOnLongClickListener {
                if (sel.active) toggleFolder(r.rel, position) else folderMenu(r)
                true
            }
        }

        private fun bindSong(holder: VH, t: Track, position: Int) {
            val playing = PlayerSession.current?.uri == t.uri && PlayerSession.isActive
            holder.title.text = t.displayTitle
            holder.title.setTextColor(Ui.c(this@LibraryActivity,
                if (playing) R.color.brand else R.color.text_primary))
            holder.subtitle.text = buildString {
                append(t.displayArtist)
                if (t.album != null && t.album != t.displayArtist) {
                    append("  ·  ").append(t.album)
                }
                if (t.durationMs > 0) append("  ·  ").append(fmt(t.durationMs))
            }
            holder.spec.text = t.specLabelOrDefault
            holder.cover.imageTintList = null
            CoverLoader.load(this@LibraryActivity, t, dp(52), holder.cover)
            if (t.specLabel == null) Ui.neutralBadge(holder.spec) else Ui.okBadge(holder.spec)

            holder.check.visibility = if (sel.active) View.VISIBLE else View.GONE
            if (sel.active) {
                val on = sel.contains(songKey(t))
                holder.check.setImageResource(
                    if (on) R.drawable.ic_check_on else R.drawable.ic_check_off
                )
                holder.check.imageTintList = android.content.res.ColorStateList.valueOf(
                    Ui.c(this@LibraryActivity,
                        if (on) R.color.brand else R.color.text_secondary)
                )
            }

            holder.itemView.setOnClickListener {
                if (sel.active) {
                    toggleSong(t, position)
                    return@setOnClickListener
                }
                /*
                 * 队列 = **当前文件夹**里的曲目，从这首开始。
                 *
                 * 比"整个库"更符合直觉：用户进了某个专辑目录点第一首，
                 * 期待的是顺着这个目录听下去，而不是把整个库排进队列。
                 */
                val songs = Library.tracksIn(folder)
                val idx = songs.indexOfFirst { it.uri == t.uri }
                if (idx >= 0) PlayerSession.setQueue(songs, idx)
                startActivity(Intent(this@LibraryActivity, NowPlayingActivity::class.java))
            }
            holder.itemView.setOnLongClickListener {
                if (sel.active) toggleSong(t, position) else songMenu(t)
                true
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val check: ImageView = v.findViewById(R.id.ivCheck)
            val cover: ImageView = v.findViewById(R.id.ivCover)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val subtitle: TextView = v.findViewById(R.id.tvSubtitle)
            val spec: TextView = v.findViewById(R.id.tvSpec)
        }
    }

    private fun songMenu(t: Track) {
        AlertDialog.Builder(this)
            .setTitle(t.displayTitle)
            .setItems(arrayOf(
                "下一首播放", "添加至播放队列", "添加至歌单…", "移动到…", "从音乐库删除"
            )) { _, which ->
                when (which) {
                    0 -> {
                        PlayerSession.playNextInQueue(t)
                        toast("已添加：下一首播放")
                    }
                    1 -> {
                        PlayerSession.addToQueue(listOf(t))
                        toast("已添加至播放队列")
                    }
                    2 -> PlaylistPicker.show(this, listOf(t))
                    3 -> askMoveTo(t)
                    4 -> AlertDialog.Builder(this)
                        .setTitle("从音乐库删除？")
                        .setMessage("文件将被彻底删除，无法恢复。")
                        .setPositiveButton("删除") { _, _ ->
                            io.execute {
                                Library.delete(t)
                                runOnUiThread { refreshList() }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
}