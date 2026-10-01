package com.hifiprobe

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * 通用曲目列表页 —— 三种数据来源共用一套界面：
 *
 * | source    | 内容         | 字母索引 | 可拖动排序 | 右上角   |
 * |-----------|--------------|----------|------------|----------|
 * | all       | 整个库       | 有       | 否         | —        |
 * | album     | 一张专辑     | 无       | 否         | 播放全部 |
 * | playlist  | 一个歌单     | 无       | **是**     | 播放全部 + ⋮ |
 *
 * ★ 三处共用是为了**样式一致**：封面大小、徽章、空态文案、长按菜单
 *   只在这一份代码里定义。拆成三个页面的话，改一处忘两处是必然的。
 *
 * ★ 拖动排序**只在歌单里开放**。
 *   「所有歌曲」的顺序是排出来的（拼音序），允许改的话，下次进来又变回去，
 *   用户会以为改动没保存。专辑内的顺序由文件的音轨顺序决定，同理。
 *   只有歌单的顺序是"用户自己的意图"，值得让它可改、并且存下来。
 */
class TrackListActivity : AppCompatActivity(), PlayerSession.Listener {

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        const val SOURCE_ALL = "all"
        const val SOURCE_ALBUM = "album"
        const val SOURCE_PLAYLIST = "playlist"

        private const val TYPE_LETTER = 0
        private const val TYPE_SONG = 1
    }

    /** 列表里的一行：字母分组头，或者一首歌 */
    private sealed interface Row {
        data class Letter(val ch: Char) : Row
        data class Song(val track: Track, val index: Int) : Row
    }

    private lateinit var rvTracks: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var tvTlTitle: TextView
    private lateinit var tvTlSubtitle: TextView
    private lateinit var tvTlEmpty: TextView
    private lateinit var tvIndexBubble: TextView
    private lateinit var indexBar: IndexBar
    private lateinit var btnTlAction: Button
    private lateinit var btnTlMore: ImageButton
    private lateinit var miniBar: MiniBar

    private val adapter = TrackAdapter()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tracklist-io") }

    private var source = SOURCE_ALL
    private var playlistId: String? = null
    private var albumName: String? = null

    /** 当前的曲目顺序（不含分组头），点第 index 首时要用它建队列 */
    private var tracks: List<Track> = emptyList()

    /** 字母 → 它在列表里的行号，字母索引跳转用 */
    private var letterRow = mapOf<Char, Int>()

    private var dragEnabled = false
    private var dragHelper: ItemTouchHelper? = null

    // ---- 多选 ----
    private val sel = Selection()
    private lateinit var selBar: SelectionBar
    private lateinit var selHeader: SelectionHeader
    private lateinit var btnSelect: View
    private lateinit var btnSelectAll: android.widget.Button

    /** 进多选前的标题。退出时要原样还回去 */
    private var titleBeforeSelect: CharSequence = ""

    private val editable: Boolean get() = source == SOURCE_PLAYLIST

    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_list)

        PlayerSession.init(applicationContext)
        Library.init(this)
        Playlists.init(this)

        source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_ALL
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        albumName = intent.getStringExtra(EXTRA_ALBUM)

        rvTracks = findViewById(R.id.rvTracks)
        tvTlTitle = findViewById(R.id.tvTlTitle)
        tvTlSubtitle = findViewById(R.id.tvTlSubtitle)
        tvTlEmpty = findViewById(R.id.tvTlEmpty)
        tvIndexBubble = findViewById(R.id.tvIndexBubble)
        indexBar = findViewById(R.id.indexBar)
        btnTlAction = findViewById(R.id.btnTlAction)
        btnTlMore = findViewById(R.id.btnTlMore)

        miniBar = MiniBar(this)
        Ui.applySystemBars(findViewById(R.id.rootTrackList))

        layoutManager = LinearLayoutManager(this)
        rvTracks.layoutManager = layoutManager
        rvTracks.adapter = adapter
        if (editable) attachDragHelper()

        val btnBack = findViewById<ImageButton>(R.id.btnTlBack)
        // 多选时返回键是"退出多选"，不是"退出页面" —— 这是全 App 统一的约定，
        // 不然用户选了一半按返回，整个页面没了、选择也没了
        btnBack.setOnClickListener { if (sel.active) exitSelect() else finish() }

        selBar = SelectionBar(this)
        btnSelect = findViewById(R.id.btnSelect)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        selHeader = SelectionHeader(btnBack, tvTlSubtitle, listOf(btnTlAction, btnTlMore))
        btnSelect.setOnClickListener { enterSelect() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        setupHeader()
        setupIndexBar()
        load()
    }

    private fun setupHeader() {
        tvTlTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.nav_all_songs)
        tvTlSubtitle.text = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""

        when (source) {
            SOURCE_ALL -> {
                indexBar.visibility = View.VISIBLE
            }
            SOURCE_ALBUM -> {
                btnTlAction.visibility = View.VISIBLE
                btnTlAction.text = "播放全部"
                btnTlAction.setOnClickListener { playFrom(0) }
            }
            SOURCE_PLAYLIST -> {
                btnTlAction.visibility = View.VISIBLE
                btnTlAction.text = "播放全部"
                btnTlAction.setOnClickListener { playFrom(0) }
                btnTlMore.visibility = View.VISIBLE
                btnTlMore.setOnClickListener { playlistMenu() }
            }
        }
    }

    private fun setupIndexBar() {
        indexBar.onLetterSelected = { ch ->
            letterRow[ch]?.let { layoutManager.scrollToPositionWithOffset(it, 0) }
            tvIndexBubble.text = ch.toString()
            tvIndexBubble.visibility = View.VISIBLE
        }
        // 松手才收起气泡。跟着触摸走会抖得厉害，而且手指正好挡着中间
        indexBar.onReleased = { tvIndexBubble.visibility = View.GONE }
    }

    // ------------------------------------------------------------------
    //  加载
    // ------------------------------------------------------------------

    private fun load() {
        io.execute {
            val rows: List<Row>
            val letters: Map<Char, Int>
            val list: List<Track>

            when (source) {
                SOURCE_ALBUM -> {
                    val name = albumName.orEmpty()
                    list = Library.albums().firstOrNull { it.name == name }?.tracks ?: emptyList()
                    rows = list.mapIndexed { i, t -> Row.Song(t, i) }
                    letters = emptyMap()
                }

                SOURCE_PLAYLIST -> {
                    val id = playlistId.orEmpty()
                    list = Playlists.tracksOf(id)
                    rows = list.mapIndexed { i, t -> Row.Song(t, i) }
                    letters = emptyMap()
                }

                else -> {
                    // ★ 拼音排序很慢（每个汉字一次 ICU 音译），只能在后台线程做
                    val sorted = Pinyin.sort(Library.all()) { it.displayTitle }
                    list = sorted.items
                    val built = ArrayList<Row>(sorted.items.size + sorted.sections.size)
                    val map = HashMap<Char, Int>()
                    for ((i, t) in sorted.items.withIndex()) {
                        val b = sorted.buckets[i]
                        if (map[b] == null) {
                            map[b] = built.size
                            built.add(Row.Letter(b))
                        }
                        built.add(Row.Song(t, i))
                    }
                    rows = built
                    letters = map
                }
            }

            val subtitle = when (source) {
                SOURCE_ALL -> buildString {
                    append("${list.size} 首 · ${letters.size} 个字母组")
                    // 音译器不可用时中文会全落到 '#'。与其让用户对着一堆 #
                    // 猜是不是坏了，不如直接说明 —— 这不是崩溃，是降级
                    if (Pinyin.hanAvailable) append(" · 按拼音排序")
                    else append(" · 本机拼音字库不可用，中文归入 #")
                }
                SOURCE_ALBUM -> "${list.size} 首 · 来自「${albumName.orEmpty()}」"
                else -> buildString {
                    /*
                     * ★ list 已经是**解析出来的**曲目（tracksOf 跳过了悬空的 uri），
                     *   所以这个数就是实际能放的数 —— 不再另报"N 首已失效"。
                     *   列表页现在也用同一个口径，两边一致。
                     */
                    append("${list.size} 首")
                    if (editable) append(" · 按住左侧把手可排序")
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tracks = list
                letterRow = letters
                indexBar.present = letters.keys
                tvTlSubtitle.text = subtitle
                adapter.submit(rows)
                renderEmpty()
            }
        }
    }

    private fun renderEmpty() {
        val empty = tracks.isEmpty()
        rvTracks.visibility = if (empty) View.GONE else View.VISIBLE
        tvTlEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        // 多选时也要收起来：字母滑动块和底部操作栏抢同一条手势，
        // 而且选到一半跳走会让用户不知道自己选的是哪些
        indexBar.visibility =
            if (empty || source != SOURCE_ALL || sel.active) View.GONE else View.VISIBLE
        tvTlEmpty.text = when (source) {
            SOURCE_ALL -> getString(R.string.empty_all_songs)
            SOURCE_ALBUM -> "这张专辑里没有可播放的曲目"
            else -> "该歌单尚为空\n\n在曲目上长按，选择「添加至歌单…」"
        }
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.addListener(this)
        miniBar.render()
        // 回到本页时重拉一次：可能在别的页面里把歌加进来了，或改了歌单名。
        // 内容没变的话 adapter.submit 会直接跳过，不会把滚动位置弹回顶部
        load()
    }

    override fun onPause() {
        super.onPause()
        PlayerSession.removeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    override fun onSessionLog(text: String) = Unit
    override fun onSessionStateChanged() {
        miniBar.render()
        /*
         * 只在高亮真的换行时才重画列表。
         *
         * 播放中心跳 200ms 一次都会走到这里。无脑 notifyDataSetChanged 的话，
         * 一秒重画五次：滚动位置会闪，而且**正在拖动排序时会被直接打断**
         * （ViewHolder 被回收，ItemTouchHelper 手里那个引用就废了）。
         * 换歌是低频事件，那时候再重画完全来得及。
         */
        adapter.refreshHighlight()
    }

    // ------------------------------------------------------------------
    //  拖动排序（只在歌单里）
    // ------------------------------------------------------------------

    private fun attachDragHelper() {
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false    // 只认把手

            override fun canDropOver(
                rv: RecyclerView, cur: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ) = dragEnabled

            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val from = rv.getChildAdapterPosition(vh.itemView)
                val to = rv.getChildAdapterPosition(target.itemView)
                if (from < 0 || to < 0) return false
                adapter.moveLocal(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                dragEnabled = false
                adapter.commitMove()
            }
        })
        helper.attachToRecyclerView(rvTracks)
        dragHelper = helper
    }

    /**
     * 开始拖动。
     *
     * dragEnabled 必须**在拖动期间保持为真** —— 它挂在 canDropOver 上，
     * 中途置回 false 的话 ItemTouchHelper 会认为"哪儿都不能放"，直接拖不动。
     * 复位放在 clearView（松手）里。
     */
    private fun startDrag(holder: RecyclerView.ViewHolder) {
        dragEnabled = true
        dragHelper?.startDrag(holder)
    }

    // ------------------------------------------------------------------
    //  操作
    // ------------------------------------------------------------------

    private fun playFrom(index: Int) {
        if (tracks.isEmpty()) return
        val i = index.coerceIn(0, tracks.size - 1)

        /*
         * ★ 「所有歌曲」是**单曲播放**。
         *
         *   它是个总览列表 —— 点一首的意思是"听这首"，不是"从这首开始
         *   把整个库播一遍"。专辑和歌单则相反：点进去本来就是为了连着听一整张。
         *
         * ★ 单曲也是**进队列**（一个只有一首的队列），不是绕过队列直接放 ——
         *   否则单曲循环（repeat=ONE）会失效，而且"下一首"没有落点。
         */
        val single = source == SOURCE_ALL
        PlayerSession.setQueue(
            if (single) listOf(tracks[i]) else tracks,
            if (single) 0 else i
        )
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    // ------------------------------------------------------------------
    //  多选
    // ------------------------------------------------------------------

    /**
     * 长按**保持弹菜单不变**（这是用户明确要的），多选从顶栏「选择」进。
     *
     * 进了多选之后长按改成"切换选中" —— 那时候再弹菜单会让人以为
     * 自己选的东西丢了。
     */
    private fun enterSelect() {
        sel.start()
        titleBeforeSelect = tvTlTitle.text
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
            tvTlTitle.text = titleBeforeSelect
        }
        renderEmpty()
        renderSelection()
        adapter.refreshSelection()
    }

    private fun renderSelection() {
        if (!sel.active) {
            selBar.hide()
            return
        }
        tvTlTitle.text = getString(R.string.sel_count_fmt, sel.size)

        // 文字按钮的标签必须说实话：全选状态下点下去是**取消**全选，
        // 还写着「全选」的话用户不敢点
        btnSelectAll.text = getString(
            if (sel.containsAll(allKeys())) R.string.action_deselect_all
            else R.string.action_select_all
        )
        selBar.show(sel.size, selectionActions())
    }

    /** 当前列表里所有可选的键 */
    private fun allKeys(): List<String> = tracks.map { it.uri }

    /** 全选 / 取消全选。已经全选了就取消 —— 一个按钮管两个方向，少占一个位置 */
    private fun toggleSelectAll() {
        val all = allKeys()
        sel.setGroup(all, !sel.containsAll(all))
        adapter.refreshSelection()
        renderSelection()
    }

    private fun toggleOne(uri: String, position: Int) {
        sel.toggle(uri)
        adapter.notifyItemChanged(position)
        renderSelection()
    }

    /** 选中的曲目。**按列表顺序**取，不是按勾选顺序 —— 加进歌单/队列后顺序要可预期 */
    private fun selectedTracks(): List<Track> {
        val keys = sel.snapshot()
        return tracks.filter { it.uri in keys }
    }

    private fun selectionActions(): List<SelectionBar.Action> = buildList {
        /*
         * ★ 「播放选中」放在第一位 —— 多选之后最常见的意图就是"把这几首放来听"。
         *
         *   语义和**点单曲完全一致**：队列 = 选中的这些（按列表顺序），
         *   从第一首开始。不引入"选中就变成一个临时列表"这种特例 ——
         *   否则单曲循环和"下一首"的落点都会跟着变形。
         *
         *   这个页面同时承担「所有歌曲 / 专辑 / 歌单」，三种来源共用这一处，
         *   所以歌单页也就一并有了。
         */
        add(SelectionBar.Action(getString(R.string.action_play_selected)) {
            val list = selectedTracks()
            if (list.isEmpty()) {
                toast("此处无可播放的曲目")
            } else {
                PlayerSession.setQueue(list, 0)
                startActivity(Intent(this@TrackListActivity, NowPlayingActivity::class.java))
            }
            exitSelect()
        })
        add(SelectionBar.Action(getString(R.string.action_add_to_playlist_short)) {
            BatchActions.addToPlaylist(this@TrackListActivity, selectedTracks())
            exitSelect()
        })
        if (source == SOURCE_PLAYLIST) {
            add(SelectionBar.Action("从歌单移除") {
                val uris = sel.snapshot()
                io.execute {
                    for (u in uris) Playlists.removeUri(playlistId.orEmpty(), u)
                    runOnUiThread { exitSelect(); load() }
                }
            })
        } else {
            add(SelectionBar.Action(getString(R.string.action_delete_selected)) {
                BatchActions.deleteFiles(this@TrackListActivity, selectedTracks()) {
                    exitSelect()
                    load()
                }
            })
        }
    }

    private fun trackMenu(t: Track) {
        val items = buildList {
            add("下一首播放")
            add("添加至播放队列")
            add("添加至歌单…")
            if (editable) add("从这个歌单移除")
        }
        AlertDialog.Builder(this)
            .setTitle(t.displayTitle)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        PlayerSession.playNextInQueue(t)
                        toast("已添加：下一首播放")
                    }
                    1 -> {
                        PlayerSession.addToQueue(listOf(t))
                        toast("已添加至播放队列")
                    }
                    2 -> addToPlaylist(listOf(t))
                    3 -> {
                        io.execute {
                            Playlists.removeUri(playlistId.orEmpty(), t.uri)
                            runOnUiThread {
                                toast("已移除")
                                load()
                            }
                        }
                    }
                }
            }
            .show()
    }

    /** 曲目列表页的"加到歌单"：没有歌单时顺手引导建一个 */
    private fun addToPlaylist(picked: List<Track>) {
        val lists = Playlists.all()
        if (lists.isEmpty()) {
            val input = EditText(this).apply { hint = "歌单名" }
            AlertDialog.Builder(this)
                .setTitle("尚无歌单，请先创建")
                .setView(input)
                .setPositiveButton("创建并添加") { _, _ ->
                    val p = Playlists.create(input.text.toString())
                    if (p == null) {
                        toast("名称为空或已存在")
                    } else {
                        val n = Playlists.add(p.id, picked)
                        toast("「${p.name}」已添加 $n 首")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val names = lists.map { "${it.name}（${it.size} 首）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("添加至哪个歌单")
            .setItems(names) { _, which ->
                val n = Playlists.add(lists[which].id, picked)
                toast(if (n == 0) "这些曲目均已在「${lists[which].name}」中"
                else "「${lists[which].name}」已添加 $n 首")
            }
            .show()
    }

    private fun playlistMenu() {
        val id = playlistId.orEmpty()
        val p = Playlists.byId(id) ?: return
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("重命名", "清空歌单", "删除歌单")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(p.name) }
                        AlertDialog.Builder(this)
                            .setTitle("重命名")
                            .setView(input)
                            .setPositiveButton("确定") { _, _ ->
                                if (Playlists.rename(id, input.text.toString())) {
                                    title = input.text.toString()
                                    tvTlTitle.text = input.text.toString()
                                } else toast("名称为空或已存在")
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    1 -> AlertDialog.Builder(this)
                        .setTitle("清空「${p.name}」？")
                        .setMessage("仅将曲目从歌单中移除，不会删除音乐文件。")
                        .setPositiveButton("清空") { _, _ ->
                            Playlists.clear(id)
                            load()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    2 -> AlertDialog.Builder(this)
                        .setTitle("删除歌单「${p.name}」？")
                        .setMessage("仅删除这份歌单，不会删除音乐文件。")
                        .setPositiveButton("删除") { _, _ ->
                            Playlists.delete(id)
                            finish()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------

    private inner class TrackAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        private var pendingFrom = -1
        private var pendingTo = -1

        /** 上一次画高亮时正在播的是哪首，用来判断"真的换歌了吗" */
        private var lastPlayingUri: String? = null

        /**
         * 选中态变了。
         *
         * 行的**内容**没变（还是那首歌），变的是行首那个圈，所以
         * [submit] 的内容指纹比不出来 —— 必须显式重画一遍。
         */
        fun refreshSelection() = notifyDataSetChanged()

        fun refreshHighlight() {
            val now = PlayerSession.current?.uri?.takeIf { PlayerSession.isActive }
            if (now == lastPlayingUri) return
            lastPlayingUri = now
            notifyDataSetChanged()
        }

        /**
         * 提交新内容。**内容完全一样就什么都不做。**
         *
         * onResume 每次都会重新加载一遍（可能在别处把歌加进来了），
         * 但绝大多数时候内容没变。无脑 notifyDataSetChanged 会把滚动位置
         * 弹回顶部 —— 用户从正在播放页返回，列表却跳回开头，很恼人。
         */
        fun submit(list: List<Row>) {
            if (list == rows) return
            lastPlayingUri = PlayerSession.current?.uri?.takeIf { PlayerSession.isActive }
            rows = list
            pendingFrom = -1
            pendingTo = -1
            notifyDataSetChanged()
        }

        fun moveLocal(from: Int, to: Int) {
            if (from !in rows.indices || to !in rows.indices) return
            val list = rows.toMutableList()
            list.add(to, list.removeAt(from))
            rows = list
            if (pendingFrom < 0) pendingFrom = from
            pendingTo = to
            notifyItemMoved(from, to)
        }

        /** 松手才写回歌单 —— 拖动过程中每帧都写的话会反复落盘 */
        fun commitMove() {
            val f = pendingFrom
            val t = pendingTo
            pendingFrom = -1
            pendingTo = -1
            if (f >= 0 && t >= 0 && f != t) {
                io.execute {
                    Playlists.moveIn(playlistId.orEmpty(), f, t)
                    Playlists.flush()
                }
            }
        }

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Letter) TYPE_LETTER else TYPE_SONG

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_LETTER) {
                LetterVH(inf.inflate(R.layout.item_section, parent, false))
            } else {
                SongVH(inf.inflate(R.layout.item_track, parent, false))
            }
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Letter -> (holder as LetterVH).letter.text = r.ch.toString()
                is Row.Song -> bindSong(holder as SongVH, r, position)
            }
        }

        private fun bindSong(holder: SongVH, r: Row.Song, position: Int) {
            val t = r.track
            val playing = PlayerSession.current?.uri == t.uri && PlayerSession.isActive

            holder.title.text = t.displayTitle
            holder.title.setTextColor(Ui.c(this@TrackListActivity,
                if (playing) R.color.brand else R.color.text_primary))
            holder.subtitle.text = buildString {
                // 歌单/所有歌曲里显示专辑名更有用；专辑页里显示艺术家
                if (source == SOURCE_ALBUM) append(t.displayArtist)
                else append(t.album?.takeIf { it.isNotBlank() } ?: t.displayArtist)
                if (t.durationMs > 0) append("   ·   ").append(fmt(t.durationMs))
            }
            holder.spec.text = t.specLabelOrDefault
            if (t.specLabel == null) Ui.neutralBadge(holder.spec) else Ui.okBadge(holder.spec)
            // ★ 列表一律走异步 —— 同步解码会把滚动帧预算吃光（见 CoverLoader 文件头）
            CoverLoader.load(this@TrackListActivity, t, dp(52), holder.cover)

            /*
             * 行首那块地方，多选时归勾选圈、平时归拖动把手。
             *
             * 两个都是"行首的圆形控件"，同时出现的话用户会以为
             * 一个是另一个的选中态。所以是**让位**，不是并排。
             */
            val selecting = sel.active
            holder.drag.visibility = if (editable && !selecting) View.VISIBLE else View.GONE
            holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
            if (selecting) {
                holder.check.setImageResource(
                    if (sel.contains(t.uri)) R.drawable.ic_check_on else R.drawable.ic_check_off
                )
                // 圈本身是白色的（同一套图标），靠 tint 上色。
                // 选中的用主色，没选中的用弱化灰 —— 差距要够大，
                // 在 26dp 的尺寸下才分得清
                holder.check.imageTintList = android.content.res.ColorStateList.valueOf(
                    Ui.c(this@TrackListActivity,
                        if (sel.contains(t.uri)) R.color.brand else R.color.text_secondary)
                )
            }
            if (editable && !selecting) {
                holder.drag.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) startDrag(holder)
                    // 返回 false：不消费事件，让 ItemTouchHelper 自己接管
                    false
                }
            }

            // 多选时拖动排序整体停掉：拖拽和"点一下切换选中"抢同一个手势，
            // 同时开着的话想选一首很容易变成把它拖走
            dragEnabled = false

            holder.itemView.setOnClickListener {
                if (sel.active) toggleOne(t.uri, position) else playFrom(r.index)
            }
            holder.itemView.setOnLongClickListener {
                // 长按在平时弹菜单（用户要求保持不变），多选时切换选中
                if (sel.active) toggleOne(t.uri, position) else trackMenu(t)
                true
            }
        }

        inner class LetterVH(v: View) : RecyclerView.ViewHolder(v) {
            val letter: TextView = v.findViewById(R.id.tvSection)
        }

        inner class SongVH(v: View) : RecyclerView.ViewHolder(v) {
            val drag: ImageView = v.findViewById(R.id.ivDrag)
            val check: ImageView = v.findViewById(R.id.ivCheck)
            val cover: ImageView = v.findViewById(R.id.ivCover)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val subtitle: TextView = v.findViewById(R.id.tvSubtitle)
            val spec: TextView = v.findViewById(R.id.tvSpec)
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

}
