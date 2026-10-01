package com.hifiprobe

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * 歌单页 —— 整页一份歌单列表，第一行固定是「新建歌单」。
 *
 * ★ 「播放队列」和「我的歌单」是**两个概念**，界面上必须分开摆：
 *     · 播放队列   临时的，关掉 app 就没了，描述"接下来听什么"
 *     · 我的歌单   存盘的，长期留着，描述"我想收藏哪些"
 *
 *   这两样原来一上一下并排放在这一页里。现在队列挪去了**正在播放页**
 *   （控制行里那个「队列」按钮）—— 队列属于"正在播放"的语境，
 *   而这一页既然叫歌单，进来就该看到歌单。
 *   **分开摆这条没变，只是换了位置。**
 *
 * ★ 歌单封面取歌单里第一首的封面。**索引只建一次** ——
 *   [Playlists.tracksOf] 每次调用都要遍历整个库建映射表，
 *   一屏十个歌单就是十次全库遍历。这里自己建一次映射然后用。
 */
class PlaylistsActivity : AppCompatActivity(), PlayerSession.Listener {

    private lateinit var rvPlaylists: RecyclerView
    private lateinit var tvPlSubtitle: TextView
    private lateinit var tvPlaylistsEmpty: TextView
    private lateinit var miniBar: MiniBar

    private val listAdapter = PlaylistAdapter()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "playlists-io") }

    private var playlists: List<Playlist> = emptyList()

    /** uri → 曲目。整页只建一次，别在每次 bind 里遍历全库 */
    private var trackIndex: Map<String, Track> = emptyMap()

    // ---- 多选 ----
    private val sel = Selection()
    private lateinit var selBar: SelectionBar
    private lateinit var selHeader: SelectionHeader
    private lateinit var btnSelect: View
    private lateinit var btnSelectAll: android.widget.Button
    private lateinit var tvPlHeading: TextView
    private var titleBeforeSelect: CharSequence = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)

        PlayerSession.init(applicationContext)
        Library.init(this)
        Playlists.init(this)

        rvPlaylists = findViewById(R.id.rvPlaylists)
        tvPlSubtitle = findViewById(R.id.tvPlSubtitle)
        tvPlaylistsEmpty = findViewById(R.id.tvPlaylistsEmpty)

        miniBar = MiniBar(this)
        Ui.applySystemBars(findViewById(R.id.rootPlaylists))

        rvPlaylists.layoutManager = LinearLayoutManager(this)
        rvPlaylists.adapter = listAdapter

        val btnBack = findViewById<ImageButton>(R.id.btnPlBack)
        btnBack.setOnClickListener { if (sel.active) exitSelect() else finish() }
        val btnNew = findViewById<Button>(R.id.btnNewPlaylist)
        btnNew.setOnClickListener { askNewPlaylist() }

        tvPlHeading = findViewById(R.id.tvPlHeading)
        selBar = SelectionBar(this)
        btnSelect = findViewById(R.id.btnSelect)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        // 多选时「新建歌单」收起来 —— 正选着要删哪些，旁边杵一个新建按钮容易误点
        selHeader = SelectionHeader(btnBack, tvPlSubtitle, listOf(btnNew))
        btnSelect.setOnClickListener { enterSelect() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        load()
    }

    private fun load() {
        io.execute {
            val idx = Library.all().associateBy { it.uri }
            val lists = Playlists.all()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                trackIndex = idx
                playlists = lists
                listAdapter.notifyDataSetChanged()
                renderHeader()
            }
        }
    }

    private fun renderHeader() {
        // 列表**永远可见** —— 第一行是「新建歌单」，没有歌单时它才是最该看到的东西
        val empty = playlists.isEmpty()
        tvPlaylistsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tvPlSubtitle.text =
            if (empty) "还没有歌单"
            else "${playlists.size} 个歌单 · 共 ${playlists.sumOf { it.size }} 首"
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

    /**
     * 心跳 200ms 一次都会走到这儿。
     *
     * ★ **不要**在这里 notifyDataSetChanged 歌单列表 —— 白费力气，而且会
     *   把滚动位置和"正在按住的那一项"一起搅乱。（队列那一行搬走之后，
     *   这里就只剩迷你播放条需要更新了。）
     */
    override fun onSessionStateChanged() {
        miniBar.render()
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    //  多选
    // ------------------------------------------------------------------

    /**
     * 这一页的多选是勾**歌单本身**（用来删），不是勾歌单里的歌 ——
     * 上面那张队列卡片也不参与：它是个导航入口，不是一个可操作的对象。
     */
    private fun enterSelect() {
        sel.start()
        titleBeforeSelect = tvPlHeading.text
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
        if (!on) tvPlHeading.text = titleBeforeSelect
        listAdapter.notifyDataSetChanged()
        renderSelection()
    }

    private fun renderSelection() {
        if (!sel.active) {
            selBar.hide()
            return
        }
        tvPlHeading.text = getString(R.string.sel_count_fmt, sel.size)

        // 文字按钮的标签必须说实话：全选状态下点下去是**取消**全选，
        // 还写着「全选」的话用户不敢点
        btnSelectAll.text = getString(
            if (sel.containsAll(allKeys())) R.string.action_deselect_all
            else R.string.action_select_all
        )
        selBar.show(sel.size, listOf(
            SelectionBar.Action(getString(R.string.action_delete_selected)) {
                val n = sel.size
                AlertDialog.Builder(this)
                    .setTitle("删除 $n 个歌单？")
                    .setMessage("只是删掉这几份歌单，不会删除音乐文件。")
                    .setPositiveButton("删除") { _, _ ->
                        for (id in sel.snapshot()) Playlists.delete(id)
                        exitSelect()
                        load()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        ))
    }

    /** 当前列表里所有可选的键 */
    private fun allKeys(): List<String> = playlists.map { it.id }

    private fun toggleSelectAll() {
        val all = playlists.map { it.id }
        sel.setGroup(all, !sel.containsAll(all))
        listAdapter.notifyDataSetChanged()
        renderSelection()
    }

    private fun toggleOne(id: String, position: Int) {
        sel.toggle(id)
        listAdapter.notifyItemChanged(position)
        renderSelection()
    }

    private fun askNewPlaylist() {
        val input = EditText(this).apply { hint = "歌单名" }
        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val p = Playlists.create(input.text.toString())
                if (p == null) toast("名字为空或已存在") else load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openPlaylist(p: Playlist) {
        startActivity(Intent(this, TrackListActivity::class.java)
            .putExtra(TrackListActivity.EXTRA_SOURCE, TrackListActivity.SOURCE_PLAYLIST)
            .putExtra(TrackListActivity.EXTRA_PLAYLIST_ID, p.id)
            .putExtra(TrackListActivity.EXTRA_TITLE, p.name)
            .putExtra(TrackListActivity.EXTRA_SUBTITLE, "${p.size} 首"))
    }

    private fun playlistMenu(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("播放", "重命名", "删除歌单")) { _, which ->
                when (which) {
                    0 -> {
                        val id = p.id
                        io.execute {
                            val list = Playlists.tracksOf(id)
                            runOnUiThread {
                                if (list.isEmpty()) {
                                    toast("歌单是空的")
                                } else {
                                    PlayerSession.setQueue(list, 0)
                                    startActivity(Intent(this, NowPlayingActivity::class.java))
                                }
                            }
                        }
                    }
                    1 -> {
                        val input = EditText(this).apply { setText(p.name) }
                        AlertDialog.Builder(this)
                            .setTitle("重命名")
                            .setView(input)
                            .setPositiveButton("确定") { _, _ ->
                                if (Playlists.rename(p.id, input.text.toString())) load()
                                else toast("名字为空或已存在")
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    2 -> AlertDialog.Builder(this)
                        .setTitle("删除歌单「${p.name}」？")
                        .setMessage("只是删掉这份歌单，不会删除音乐文件。")
                        .setPositiveButton("删除") { _, _ ->
                            Playlists.delete(p.id)
                            load()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------

    /** 歌单列表。第一行固定是「新建歌单」 */
    private inner class PlaylistAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) =
            if (position == 0) TYPE_NEW else TYPE_LIST

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_NEW) {
                NewVH(inf.inflate(R.layout.item_new_playlist, parent, false))
            } else {
                ListVH(inf.inflate(R.layout.item_playlist, parent, false))
            }
        }

        override fun getItemCount() = playlists.size + 1

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is NewVH) {
                /*
                 * 多选态下点这一行不该弹新建对话框 —— 那和当前正在做的事
                 * （挑几个歌单来删）没关系，弹出来只会让人以为点错了。
                 */
                holder.itemView.setOnClickListener {
                    if (!sel.active) askNewPlaylist()
                }
                return
            }
            val p = playlists[position - 1]
            val vh = holder as ListVH

            vh.name.text = p.name
            /*
             * ★ 数量按**还找得到的歌**算，不再显示"N 首已失效"。
             *
             *   原来是 "${p.size} 首 · N 首已失效" —— 报给用户一个还得自己做
             *   减法的数，而且"已失效"那截每次都要重新理解一遍。
             *   更糟的是**详情页显示的本来就只是有效数**（tracksOf 会跳过悬空的
             *   uri），两处对不上，看起来更像坏了。
             *
             *   现在两边一致：列表上写几首，点进去就是几首。
             */
            vh.meta.text = "${p.uris.count { it in trackIndex }} 首"

            // 封面取第一首还能找到的歌；一首都没有就现画一张黑胶
            val first = p.uris.firstNotNullOfOrNull { trackIndex[it] }
            if (first != null) {
                CoverLoader.load(this@PlaylistsActivity, first, dp(44), vh.cover)
            } else {
                /*
                 * 一首有效曲目都没有的歌单：按歌单 id 现画一张黑胶。
                 *
                 * ★ 这条继续走**同步**：它是"库里没有这首歌"的少数情况，
                 *   一屏碰不到几个，而且数量只会越来越少。
                 *
                 * ★★ 但必须先 `cancel` —— 上面那条异步可能正飞在路上，
                 *    不注销的话它回来时会把这张占位黑胶盖掉，显示成一首
                 *    根本不在这个歌单里的歌的封面。
                 */
                CoverLoader.cancel(vh.cover)
                val base = Settings.currentVinyl(this@PlaylistsActivity)
                vh.cover.setImageBitmap(VinylArt.render(dp(44),
                    base.copy(seed = (p.id.hashCode().toLong() and 0x7FFFFFFF) + base.seed)))
            }

            // 多选时「更多」收起来：删歌单这件事操作栏里有，
            // 两个入口同时开着会让人不确定按哪个
            vh.more.visibility = if (sel.active) View.GONE else View.VISIBLE
            vh.check.visibility = if (sel.active) View.VISIBLE else View.GONE
            if (sel.active) {
                val on = sel.contains(p.id)
                vh.check.setImageResource(
                    if (on) R.drawable.ic_check_on else R.drawable.ic_check_off
                )
                vh.check.imageTintList = android.content.res.ColorStateList.valueOf(
                    Ui.c(this@PlaylistsActivity, if (on) R.color.brand else R.color.text_secondary)
                )
            }

            /*
             * ★★ 传的是 **adapter position**，不是「playlists 数组下标」。
             *
             *   这两个空间差 1：`getItemCount()` 是 `playlists.size + 1`，
             *   第 0 项固定是「新建歌单」。上面 bind 时 `playlists[position - 1]`
             *   已经减过一次了，这里**不能再减** —— `toggleOne` 要拿它去
             *   `notifyItemChanged`，那边认的是 adapter position。
             *
             *   曾经这里传的是 `position - 1`，于是刷新永远落在**上一行**：
             *   点第 1 个歌单，圈不变；点第 2 个，第 1 个反而绿了
             *   （用户报的「选择第一项圈没有变绿，选两项只要绿第一项」）。
             */
            vh.itemView.setOnClickListener {
                if (sel.active) toggleOne(p.id, position) else openPlaylist(p)
            }
            vh.itemView.setOnLongClickListener {
                // 平时长按不用（菜单在右边的 ⋮ 上），多选时切换选中
                if (sel.active) { toggleOne(p.id, position); true } else false
            }
            vh.more.setOnClickListener { playlistMenu(p) }
        }

        inner class NewVH(v: View) : RecyclerView.ViewHolder(v)

        inner class ListVH(v: View) : RecyclerView.ViewHolder(v) {
            val check: ImageView = v.findViewById(R.id.ivCheck)
            val cover: ImageView = v.findViewById(R.id.ivPlCover)
            val name: TextView = v.findViewById(R.id.tvPlName)
            val meta: TextView = v.findViewById(R.id.tvPlMeta)
            val more: ImageButton = v.findViewById(R.id.btnPlMore)
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private companion object {
        const val TYPE_NEW = 0
        const val TYPE_LIST = 1
    }
}
