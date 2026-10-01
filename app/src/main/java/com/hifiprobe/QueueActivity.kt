package com.hifiprobe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放队列 —— 可编辑。
 *
 * 队列不是"库的镜像"，而是用户自己排的一份顺序，所以得能加、能删、能拖。
 *
 * ★ **随机播放开着时编辑会自动关掉它**（在 [PlayerSession] 里做的）。
 *   因为开着随机时播放顺序是另一套排列，用户拖完发现还是乱着放，
 *   比不让编辑更让人困惑。这里如实地把这件事写在提示里。
 *
 * 拖动排序用 [ItemTouchHelper]，但**只有左侧把手能拖** ——
 * 整行可拖的话，想点一下跳过去就会误触成排序。
 */
class QueueActivity : AppCompatActivity(), PlayerSession.Listener {

    private lateinit var rvQueue: RecyclerView
    private lateinit var tvQueueCount: TextView
    private lateinit var tvQueueHint: TextView

    private val adapter = QueueAdapter()
    /** 拖动把手当前是否按下 —— 决定 ItemTouchHelper 允不允许这次拖动 */
    private var dragEnabled = false

    // ---- 多选 ----
    private val sel = Selection()
    private lateinit var selBar: SelectionBar
    private lateinit var selHeader: SelectionHeader
    private lateinit var btnSelect: View
    private lateinit var btnSelectAll: android.widget.Button
    private lateinit var tvQueueHeading: TextView
    private var titleBeforeSelect: CharSequence = ""

    /**
     * 队列里**不能用 uri 当键** —— 同一首歌可以被加进队列两次，
     * uri 会撞。这里的身份就是"第几个位置"。
     *
     * 位置做键通常是危险的（列表一重排就错位），但队列在这一页是稳定的：
     * 选中期间不会有别的东西改队列，而拖动排序在多选时是关掉的。
     */
    private fun keyOf(index: Int) = "q$index"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)

        rvQueue = findViewById(R.id.rvQueue)
        tvQueueCount = findViewById(R.id.tvQueueCount)
        tvQueueHint = findViewById(R.id.tvQueueHint)

        Ui.applySystemBars(findViewById(R.id.rootQueue))

        rvQueue.layoutManager = LinearLayoutManager(this)
        rvQueue.adapter = adapter
        attachDragHelper()

        val btnBack = findViewById<ImageButton>(R.id.btnQueueBack)
        btnBack.setOnClickListener { if (sel.active) exitSelect() else finish() }

        tvQueueHeading = findViewById(R.id.tvQueueHeading)
        val btnClear = findViewById<android.widget.Button>(R.id.btnClearUpcoming)
        btnClear.setOnClickListener { PlayerSession.clearUpcoming() }

        selBar = SelectionBar(this)
        btnSelect = findViewById(R.id.btnSelect)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        // 多选时「清空未播放」要收起来：它的语义和多选的"移除"重叠，
        // 而且一个不看选中集合的批量操作混在多选栏旁边很容易误点
        selHeader = SelectionHeader(btnBack, tvQueueHint, listOf(btnClear))
        btnSelect.setOnClickListener { enterSelect() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        render()
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.addListener(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        PlayerSession.removeListener(this)
    }

    override fun onSessionLog(text: String) = Unit

    override fun onSessionStateChanged() {
        render()
        // 心跳 200ms 一次；不在多选里的话 renderSelection 会直接返回，
        // 不会去动底部那条操作栏
        renderSelection()
    }

    // ------------------------------------------------------------------
    //  多选
    // ------------------------------------------------------------------

    private fun enterSelect() {
        sel.start()
        titleBeforeSelect = tvQueueHeading.text
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
        if (!on) tvQueueHeading.text = titleBeforeSelect
        render()
        renderSelection()
    }

    private fun renderSelection() {
        if (!sel.active) {
            selBar.hide()
            return
        }
        tvQueueHeading.text = getString(R.string.sel_count_fmt, sel.size)

        // 文字按钮的标签必须说实话：全选状态下点下去是**取消**全选，
        // 还写着「全选」的话用户不敢点
        btnSelectAll.text = getString(
            if (sel.containsAll(allKeys())) R.string.action_deselect_all
            else R.string.action_select_all
        )
        selBar.show(sel.size, listOf(
            SelectionBar.Action(getString(R.string.action_remove)) {
                val idx = sel.snapshot().mapNotNull { it.removePrefix("q").toIntOrNull() }
                PlayerSession.removeFromQueue(idx)
                exitSelect()
            }
        ))
    }

    /** 当前列表里所有可选的键 */
    private fun allKeys(): List<String> = (0 until PlayerSession.queue.size).map { keyOf(it) }

    /** 全选 / 取消全选。正在播的那首也能选 —— 移除它会让播放跳到下一首，这是合理的 */
    private fun toggleSelectAll() {
        val all = (0 until PlayerSession.queue.size).map { keyOf(it) }
        sel.setGroup(all, !sel.containsAll(all))
        adapter.notifyDataSetChanged()
        renderSelection()
    }

    private fun toggleOne(index: Int, position: Int) {
        sel.toggle(keyOf(index))
        adapter.notifyItemChanged(position)
        renderSelection()
    }

    // ------------------------------------------------------------------

    private fun attachDragHelper() {
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false   // 只认把手

            override fun canDropOver(
                rv: RecyclerView, cur: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = dragEnabled

            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                // 用 getChildAdapterPosition 而不是 bindingAdapterPosition：
                // 后者是 RecyclerView 1.2 才有的，这里走的是 material 传递进来的 1.1.0
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
                // 松手才真正写回队列 —— 拖动过程中每帧都改的话，
                // 队列会被反复重排，正在播的那首可能被挤到别处
                adapter.commitMove()
            }
        })
        helper.attachToRecyclerView(rvQueue)
        this.dragHelper = helper
    }

    private var dragHelper: ItemTouchHelper? = null

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

    private fun render() {
        val q = PlayerSession.queue

        /*
         * ★ 拖动期间绝不重载列表。
         *
         * 播放中心跳 200ms 一次，每次都会通知到这里。要是照单全收地
         * submit()，会有两个后果：notifyDataSetChanged 把正在拖的 item
         * 从 ItemTouchHelper 手里抢走；而且 submit() 会清掉
         * pendingFrom/pendingTo —— 松手时 commitMove() 认为"没动过"，
         * 用户拖了半天等于白拖。
         */
        if (!dragEnabled) adapter.submit(q.tracks(), q.playedFlags(), q.currentIndex)

        tvQueueCount.text = buildString {
            append("${q.size} 首")
            if (q.shuffle) append("   ·   随机播放中")
            when (q.repeatMode) {
                RepeatMode.ALL -> append("   ·   列表循环")
                RepeatMode.ONE -> append("   ·   单曲循环")
                RepeatMode.OFF -> {}
            }
        }
        tvQueueHint.text = if (q.shuffle) {
            "随机播放中：播放顺序已被打乱，下面的列表是队列本身的内容。" +
                    "编辑（拖动、移除、添加曲目）会自动关闭随机播放。"
        } else {
            "按住左侧把手可以拖动排序。点击一行直接跳转播放。"
        }
    }

    // ------------------------------------------------------------------

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.VH>() {

        private val items = ArrayList<Track>()

        /** 和 [items] 一一对应的「已播过」标记。来源见 PlayQueue.Item 的说明 */
        private var played = ArrayList<Boolean>()

        private var currentIdx = -1

        /** 上次提交时的内容指纹，用来判断"真的变了吗" */
        private var lastUris: List<String> = emptyList()
        private var lastPlayed: List<Boolean> = emptyList()
        private var lastIdx = -2

        /** 拖动过程中的临时顺序，松手才写回 PlayerSession */
        private var pendingFrom = -1
        private var pendingTo = -1

        /**
         * 提交新内容。**内容没变就什么都不做。**
         *
         * 心跳每 200ms 通知一次，但队列绝大多数时候根本没动。
         * 无脑 notifyDataSetChanged 会让列表每秒重建五次 ——
         * 滚动位置会跳，封面会反复解码，正在播那首的高亮也会闪。
         */
        fun submit(list: List<Track>, flags: List<Boolean>, current: Int) {
            val uris = list.map { it.uri }
            if (uris == lastUris && flags == lastPlayed && current == lastIdx) return

            val sameOrder = uris == lastUris
            val prevIdx = lastIdx
            val prevPlayed = lastPlayed
            lastUris = uris
            lastPlayed = flags
            lastIdx = current
            currentIdx = current
            pendingFrom = -1
            pendingTo = -1

            if (sameOrder) {
                /*
                 * 顺序没变 —— 只重画**真的变了**的那几行。
                 *
                 * ★ 别忘了「已播」标记也会变：一首歌**开播的瞬间**就被标成已播，
                 *   只按 currentIdx 重画的话，那一行会停在"未播"的样式上，
                 *   直到别的原因触发重画。所以还要比一遍 played。
                 */
                val dirty = HashSet<Int>()
                if (prevIdx in items.indices) dirty.add(prevIdx)
                if (current in items.indices) dirty.add(current)
                for (i in items.indices) {
                    if (flags.getOrElse(i) { false } != prevPlayed.getOrElse(i) { false }) dirty.add(i)
                }
                played = ArrayList(flags)
                dirty.forEach { if (it in items.indices) notifyItemChanged(it) }
                return
            }

            items.clear()
            items.addAll(list)
            played = ArrayList(flags)
            notifyDataSetChanged()
        }

        fun moveLocal(from: Int, to: Int) {
            if (from !in items.indices || to !in items.indices) return
            val t = items.removeAt(from)
            items.add(to, t)
            // ★ 标记也要跟着挪，否则拖一下排序，"已播"就跑到别的歌身上了
            if (from < played.size && to <= played.size) {
                played.add(to, played.removeAt(from))
            }
            if (pendingFrom < 0) pendingFrom = from
            pendingTo = to
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            val f = pendingFrom
            val t = pendingTo
            pendingFrom = -1
            pendingTo = -1
            if (f >= 0 && t >= 0 && f != t) PlayerSession.moveInQueue(f, t)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_queue, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            val playing = position == currentIdx
            val wasPlayed = played.getOrElse(position) { false }

            holder.title.text = t.displayTitle
            holder.title.setTextColor(Ui.c(this@QueueActivity,
                if (playing) R.color.brand else R.color.text_primary))
            holder.subtitle.text = buildString {
                if (playing) append("正在播放   ·   ")
                else {
                    append("第 ${position + 1} 首   ·   ")
                    if (wasPlayed) append("已播   ·   ")
                }
                append(t.specLabelOrDefault)
            }

            /*
             * ★「已播过」变暗。
             *
             *   · **正在播的那首不参与** —— 它有自己的高亮色（brand），
             *     再叠一层透明会显得脏，而且"正在播"本来就隐含"播过了"。
             *   · 用 **alpha 而不是换文字颜色**：队列本来就信息密集，
             *     再引入一档文字颜色会把"标题/副标题"那套三级层次搅乱。
             *     副标题里的「已播」两个字负责说清楚，变暗负责一眼扫出来。
             *   · 只压内容和封面，**不压右侧那几个控件** —— 那是能点的东西，
             *     变暗会看着像被禁用了。
             */
            val dim = if (wasPlayed && !playing) 0.45f else 1f
            holder.title.alpha = dim
            holder.subtitle.alpha = dim
            holder.cover.alpha = dim
            CoverLoader.load(this@QueueActivity, t, Ui.dp(this@QueueActivity, 42), holder.cover)

            // 只有把手能发起拖动 —— 整行可拖会让人点一下就误触排序
            // 多选时行首让给勾选圈，拖动排序整体停掉 ——
            // 拖拽和"点一下切换选中"抢同一个手势
            val selecting = sel.active
            holder.drag.visibility = if (selecting) View.GONE else View.VISIBLE
            holder.remove.visibility = if (selecting) View.GONE else View.VISIBLE
            holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
            if (selecting) {
                val on = sel.contains(keyOf(position))
                holder.check.setImageResource(
                    if (on) R.drawable.ic_check_on else R.drawable.ic_check_off
                )
                holder.check.imageTintList = android.content.res.ColorStateList.valueOf(
                    Ui.c(this@QueueActivity, if (on) R.color.brand else R.color.text_secondary)
                )
            }
            dragEnabled = false
            if (!selecting) holder.drag.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    startDrag(holder)
                }
                // 返回 false：不消费事件，让 ItemTouchHelper 自己接管
                false
            }
            holder.itemView.setOnClickListener {
                if (sel.active) toggleOne(position, position) else PlayerSession.playIndex(position)
            }
            holder.itemView.setOnLongClickListener {
                if (sel.active) { toggleOne(position, position); true } else false
            }
            holder.remove.setOnClickListener { PlayerSession.removeFromQueue(position) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val drag: ImageView = v.findViewById(R.id.ivDrag)
            val check: ImageView = v.findViewById(R.id.ivCheck)
            val cover: ImageView = v.findViewById(R.id.ivCover)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val subtitle: TextView = v.findViewById(R.id.tvSubtitle)
            val remove: ImageButton = v.findViewById(R.id.btnRemove)
        }
    }
}
