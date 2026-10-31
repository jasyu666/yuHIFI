package com.hifiprobe

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * 专辑页 —— 按标签分组的网格。
 *
 * ★ 和「音乐库」是**两种切法**，不是同一份数据换个样子：
 *   音乐库按"文件放在哪个文件夹"切，专辑按"文件里写的标签"切。
 *   用户把一张专辑的文件拆到两个文件夹，在音乐库是两组、在专辑页是一张。
 *   这正是两个入口都值得存在的原因。
 *
 * ★ 封面尺寸由代码算好塞进条目里，不写死 dp ——
 *   2 列网格在 360dp 和 430dp 的屏上，写死尺寸不是留白太多就是挤掉边距。
 *
 * ★ 顶上有个**搜索框**，按专辑名 / 歌手名过滤。
 *
 *   原本打算照搬「所有歌曲」页的右侧字母索引，**放弃了**：那个索引只认
 *   第一个字的首字母，而专辑名十有八九是中文 —— 想找「范特西」得先知道
 *   它归在 F（拼音）还是 T（英文名）下面。搜索是输入什么都能中，
 *   对中文曲库来说这才是有用的那个。
 *
 *   ★ 过滤是**纯内存**的（在 [allAlbums] 上跑），不重新扫库，见 [applyQuery]。
 */
class AlbumsActivity : AppCompatActivity(), PlayerSession.Listener {

    private lateinit var rvAlbums: RecyclerView
    private lateinit var tvAlbumsCount: TextView
    private lateinit var tvAlbumsEmpty: TextView
    private lateinit var miniBar: MiniBar

    private val adapter = AlbumAdapter()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "albums-io") }

    /**
     * **当前显示**的专辑（搜索之后的那一份）。
     *
     * ★ 多选、全选、"勾了几张"全走它 —— 屏幕上看到几张就是几张，
     *   和别的列表页一致。原始那份在 [allAlbums]，只在过滤时读。
     */
    private var albums: List<Library.Album> = emptyList()

    /** 库里全部的专辑。搜索**不重新扫库**，只在这份上过滤 */
    private var allAlbums: List<Library.Album> = emptyList()

    /** 当前搜索词。空串 = 不过滤 */
    private var query: String = ""

    private lateinit var rowSearch: View
    private lateinit var etSearch: EditText
    private lateinit var btnSearchClear: ImageButton

    /** 网格单元里封面能占的边长（px）。首帧先用屏幕宽估一个，布局出来后再校准 */
    private var cellPx = 0

    // ---- 多选 ----
    private val sel = Selection()
    private lateinit var selBar: SelectionBar
    private lateinit var selHeader: SelectionHeader
    private lateinit var btnSelect: View
    private lateinit var btnSelectAll: android.widget.Button
    private lateinit var tvAlbumsHeading: TextView
    private var titleBeforeSelect: CharSequence = ""

    /** 专辑用专辑名当键 —— 分组就是按它做的，天然唯一 */
    private fun keyOf(a: Library.Album) = a.name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_albums)

        PlayerSession.init(applicationContext)
        Library.init(this)

        rvAlbums = findViewById(R.id.rvAlbums)
        tvAlbumsCount = findViewById(R.id.tvAlbumsCount)
        tvAlbumsEmpty = findViewById(R.id.tvAlbumsEmpty)

        miniBar = MiniBar(this)
        Ui.applySystemBars(findViewById(R.id.rootAlbums))

        cellPx = estimateCell()
        rvAlbums.layoutManager = GridLayoutManager(this, 2)
        rvAlbums.adapter = adapter

        /*
         * 布局出来之后按真实宽度重算一次。
         *
         * 首帧的 estimateCell() 是按屏幕宽估的 —— 分屏、折叠屏、平板上
         * 内容区宽度和屏幕宽不是一回事。等 RecyclerView 量完再校准，
         * 封面才不会忽大忽小。重算后必须重画，否则改动要等下次滚动才生效。
         */
        rvAlbums.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w <= 0) return@addOnLayoutChangeListener
            val wanted = (w - dp(12)) / 2 - dp(12)
            if (wanted > 0 && wanted != cellPx) {
                cellPx = wanted
                adapter.notifyDataSetChanged()
            }
        }

        rowSearch = findViewById(R.id.rowAlbumSearch)
        etSearch = findViewById(R.id.etAlbumSearch)
        btnSearchClear = findViewById(R.id.btnSearchClear)
        setupSearch()

        val btnBack = findViewById<ImageButton>(R.id.btnAlbumsBack)
        btnBack.setOnClickListener { if (sel.active) exitSelect() else finish() }

        tvAlbumsHeading = findViewById(R.id.tvAlbumsHeading)
        selBar = SelectionBar(this)
        btnSelect = findViewById(R.id.btnSelect)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        selHeader = SelectionHeader(btnBack, null, emptyList())
        btnSelect.setOnClickListener { enterSelect() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        load()
    }

    /** 屏幕宽减去 RecyclerView 的左右外边距，再对半 */
    private fun estimateCell(): Int {
        val rvW = resources.displayMetrics.widthPixels - dp(32)
        return ((rvW - dp(12)) / 2) - dp(12)
    }

    private fun load() {
        io.execute {
            val list = Library.albums()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allAlbums = list
                applyQuery()
            }
        }
    }

    // ------------------------------------------------------------------
    //  搜索
    // ------------------------------------------------------------------

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                if (q == query) return
                query = q
                applyQuery()
            }
        })
        // 清空走 setText，让上面那个 watcher 统一处理 —— 两处各清各的迟早对不上
        btnSearchClear.setOnClickListener { etSearch.setText("") }
    }

    /**
     * 按当前 [query] 过滤并重画。
     *
     * ★ 纯内存过滤，**不下 io 线程**：专辑数量是"几十到几百"这个量级，
     *   比曲目少一个数量级，逐条 contains 一遍是微秒级的。
     *   （真到了几千张再挪，那时也该换成索引而不是每次都全扫。）
     *
     * ★ 每次过滤都**滚回顶部**：结果集换了一批，还停在原来的滚动位置
     *   等于让用户对着一片空白往下找。
     */
    private fun applyQuery() {
        val q = query.trim()
        albums =
            if (q.isEmpty()) allAlbums
            else allAlbums.filter { matches(it, q) }

        adapter.notifyDataSetChanged()
        renderHeader()

        if (q.isNotEmpty()) rvAlbums.scrollToPosition(0)
        btnSearchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 专辑名或歌手名里含这个词就算命中。**大小写不敏感** ——
     * 英文专辑名用户多半是随手打几个小写字母。
     */
    private fun matches(a: Library.Album, q: String): Boolean =
        a.name.contains(q, ignoreCase = true) ||
                (a.artist?.contains(q, ignoreCase = true) ?: false)

    private fun renderHeader() {
        val empty = albums.isEmpty()
        rvAlbums.visibility = if (empty) View.GONE else View.VISIBLE
        tvAlbumsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tvAlbumsEmpty.text = if (allAlbums.isEmpty()) {
            getString(R.string.empty_albums)
        } else {
            // 搜不到和"库里没有专辑"是两回事，文案必须分开说，
            // 否则用户会以为专辑全没了
            getString(R.string.empty_album_search, query.trim())
        }

        tvAlbumsCount.text = when {
            allAlbums.isEmpty() -> ""
            query.isBlank() -> "${albums.size} 张 · ${albums.sumOf { it.size }} 首"
            // 搜的时候**两个数都给** —— 只报"找到 2 张"的话，
            // 用户不知道是库里就两张，还是搜索漏了
            else -> "找到 ${albums.size} 张 · 共 ${allAlbums.size} 张"
        }
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.addListener(this)
        miniBar.render()
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
    override fun onSessionStateChanged() = miniBar.render()

    // ------------------------------------------------------------------
    //  多选
    // ------------------------------------------------------------------

    private fun enterSelect() {
        sel.start()
        titleBeforeSelect = tvAlbumsHeading.text
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
        if (on) selHeader.enter() else selHeader.exit()
        if (!on) tvAlbumsHeading.text = titleBeforeSelect
        /*
         * 多选时把搜索框收起来。和顶栏那几个按钮同一条规矩：
         * 一边选着一边把列表过滤掉，"我选了哪几张"立刻就说不清了
         * （选中的专辑可能被过滤出屏幕，但计数还把它算着）。
         */
        rowSearch.visibility = if (on) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        renderSelection()
    }

    private fun renderSelection() {
        if (!sel.active) {
            selBar.hide()
            return
        }
        tvAlbumsHeading.text = getString(R.string.sel_count_fmt, sel.size)

        // 文字按钮的标签必须说实话：全选状态下点下去是**取消**全选，
        // 还写着「全选」的话用户不敢点
        btnSelectAll.text = getString(
            if (sel.containsAll(allKeys())) R.string.action_deselect_all
            else R.string.action_select_all
        )
        selBar.show(sel.size, listOf(
            SelectionBar.Action(getString(R.string.action_add_to_playlist_short)) {
                // 勾了几张专辑 = 勾了它们里面的全部歌
                BatchActions.addToPlaylist(this, selectedTracks())
                exitSelect()
            }
        ))
    }

    /** 当前列表里所有可选的键 */
    private fun allKeys(): List<String> = albums.map { keyOf(it) }

    private fun toggleSelectAll() {
        val all = albums.map { keyOf(it) }
        sel.setGroup(all, !sel.containsAll(all))
        adapter.notifyDataSetChanged()
        renderSelection()
    }

    private fun toggleOne(a: Library.Album, position: Int) {
        sel.toggle(keyOf(a))
        adapter.notifyItemChanged(position)
        renderSelection()
    }

    /** 选中的专辑展开成曲目。去重 —— 同名同曲目在不同专辑里不该加两遍 */
    private fun selectedTracks(): List<Track> {
        val keys = sel.snapshot()
        return albums.filter { keyOf(it) in keys }.flatMap { it.tracks }.distinctBy { it.uri }
    }

    private fun openAlbum(a: Library.Album) {
        startActivity(Intent(this, TrackListActivity::class.java)
            .putExtra(TrackListActivity.EXTRA_SOURCE, TrackListActivity.SOURCE_ALBUM)
            .putExtra(TrackListActivity.EXTRA_ALBUM, a.name)
            .putExtra(TrackListActivity.EXTRA_TITLE, a.name)
            .putExtra(TrackListActivity.EXTRA_SUBTITLE,
                a.artist?.let { "$it · ${a.size} 首" } ?: "${a.size} 首"))
    }

    private fun albumMenu(a: Library.Album) {
        AlertDialog.Builder(this)
            .setTitle(a.name)
            .setItems(arrayOf("播放整张", "整张加到队尾", "整张加到歌单…")) { _, which ->
                when (which) {
                    0 -> {
                        PlayerSession.setQueue(a.tracks, 0)
                        startActivity(Intent(this, NowPlayingActivity::class.java))
                    }
                    1 -> {
                        PlayerSession.addToQueue(a.tracks)
                        toast("已把 ${a.size} 首加到队尾")
                    }
                    2 -> PlaylistPicker.show(this, a.tracks)
                }
            }
            .show()
    }

    // ------------------------------------------------------------------

    private inner class AlbumAdapter : RecyclerView.Adapter<AlbumAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_album, parent, false))

        override fun getItemCount() = albums.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = albums[position]

            /*
             * 封面尺寸是算出来的，必须显式设高度 —— 布局里是 wrap_content，
             * 不设的话图片按原图比例撑开，一列高矮不齐。
             *
             * ★★ 但**只在真的变了的时候才设**。
             *
             *   `setLayoutParams` 会无条件调 `parent.requestLayout()`。原来这行
             *   每绑一行都执行一次 —— 等于**滚动时每一帧都让整个网格重新测量
             *   加布局一遍**，而且 cellPx 从进页面到出去根本没变过。
             *
             *   这就是"专辑页特别卡"的直接原因：别的列表页没有这行，只有它卡。
             */
            val lp = holder.cover.layoutParams
            if (lp.height != cellPx) {
                lp.height = cellPx
                holder.cover.layoutParams = lp
            }
            val first = a.tracks.firstOrNull()
            if (first != null) {
                CoverLoader.load(this@AlbumsActivity, first, cellPx, holder.cover)
            }

            holder.name.text = a.name
            holder.meta.text = buildString {
                append(a.artist ?: "未知艺术家")
                append("   ·   ")
                append("${a.size} 首")
            }

            // 多选时勾选圈叠在封面右上角 —— 封面本来就在那儿，不用另找地方
            holder.check.visibility = if (sel.active) View.VISIBLE else View.GONE
            if (sel.active) {
                val on = sel.contains(keyOf(a))
                holder.check.setImageResource(
                    if (on) R.drawable.ic_check_on else R.drawable.ic_check_off
                )
                holder.check.imageTintList = android.content.res.ColorStateList.valueOf(
                    Ui.c(this@AlbumsActivity, if (on) R.color.brand else R.color.text_secondary)
                )
            }

            holder.itemView.setOnClickListener {
                if (sel.active) toggleOne(a, position) else openAlbum(a)
            }
            holder.itemView.setOnLongClickListener {
                if (sel.active) toggleOne(a, position) else albumMenu(a)
                true
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.ivAlbumCover)
            val check: ImageView = v.findViewById(R.id.ivCheck)
            val name: TextView = v.findViewById(R.id.tvAlbumName)
            val meta: TextView = v.findViewById(R.id.tvAlbumMeta)
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
}
