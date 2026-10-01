package com.hifiprobe

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 门户首页 —— 启动页。
 *
 * 四个功能各是一张卡片，点进去各自是一个独立页面。
 *
 * ★ 为什么用门户而不是底部导航栏：四个入口的**使用频次差很多** ——
 *   日常是"进音乐库找歌"，专辑和歌单是偶尔翻一翻。底部四个等权的 Tab
 *   会把低频功能摆到和高频一样显眼的位置，还得常驻占掉一条底部空间。
 *   门户页只在进来时看一眼，进去之后整屏都是内容。
 *
 * ★ 首次扫描放在这里，不放在音乐库页 —— 库是空的时侯用户可能直接点了
 *   「所有歌曲」，那边也得分一份扫描逻辑。启动页扫一次，四个页面看到的
 *   就是同一份结果。
 */
class HomeActivity : AppCompatActivity(), PlayerSession.Listener, DeviceGate.Listener {

    private lateinit var miniBar: MiniBar
    private lateinit var tvHomeStat: TextView
    private lateinit var tvAllSongsCount: TextView
    private lateinit var tvLibraryCount: TextView
    private lateinit var tvPlaylistCount: TextView
    private lateinit var tvAlbumCount: TextView

    private lateinit var barDevice: View
    private lateinit var ivDevice: ImageView
    private lateinit var tvDevice: TextView
    private lateinit var tvDeviceAction: TextView

    /**
     * 用户点过「连接」但没连上。
     *
     * ★ 用它区分**启动时的被动探测**和**用户主动尝试**：冷启动时没插解码器，
     *   App 起来那次自动探测也会失败并写下 `lastMessage` —— 但那不是"错误"，
     *   直接显示成红色的「连接失败」会平白吓人一跳。只有用户真的点过了才转红。
     */
    private var triedConnect = false

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "home-io") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        PlayerSession.init(applicationContext)
        Library.init(this)
        Playlists.init(this)

        tvHomeStat = findViewById(R.id.tvHomeStat)
        tvAllSongsCount = findViewById(R.id.tvAllSongsCount)
        tvLibraryCount = findViewById(R.id.tvLibraryCount)
        tvPlaylistCount = findViewById(R.id.tvPlaylistCount)
        tvAlbumCount = findViewById(R.id.tvAlbumCount)

        barDevice = findViewById(R.id.barDevice)
        ivDevice = findViewById(R.id.ivDevice)
        tvDevice = findViewById(R.id.tvDevice)
        tvDeviceAction = findViewById(R.id.tvDeviceAction)
        /*
         * 状态条点击的含义**随模式变**：
         *   · 系统音频模式 → "切直连"（这条是默认态，绝大多数时候看到的就是它）
         *   · 直连模式     → 手动重连（原来那套）
         */
        barDevice.setOnClickListener {
            if (Settings.usbDirect(this)) {
                /*
                 * ★ 直连模式下**一律走硬重连**（先拆再建），不再分"刚失败要重试"
                 *   还是"连着但要抢回来" —— 这两种情况的本质是同一个：
                 *   我们这边的设备状态已经不可信了，只有拆了重开才算数。
                 */
                reconnectDevice()
            } else {
                val name = DeviceGate.presentAudioDeviceName(this)
                if (name != null) showDirectPrompt(name)
                else android.widget.Toast.makeText(
                    this, "未检测到 USB 解码器", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // ★ 首页传 true：没歌时也常驻，显示空态。其他四页用默认值 false（没歌就藏）
        miniBar = MiniBar(this, alwaysVisible = true)
        Ui.applySystemBars(findViewById(R.id.rootHome))

        findViewById<View>(R.id.cardAllSongs).setOnClickListener {
            startActivity(Intent(this, TrackListActivity::class.java)
                .putExtra(TrackListActivity.EXTRA_SOURCE, TrackListActivity.SOURCE_ALL))
        }
        findViewById<View>(R.id.cardLibrary).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<View>(R.id.cardPlaylists).setOnClickListener {
            startActivity(Intent(this, PlaylistsActivity::class.java))
        }
        findViewById<View>(R.id.cardAlbums).setOnClickListener {
            startActivity(Intent(this, AlbumsActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btnHomeSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Library.isEmpty()) firstScan()
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.addListener(this)
        DeviceGate.addListener(this)
        miniBar.render()
        refreshStats()
        renderDevice()
        /*
         * ★ 把上次的播放队列恢复回来（幂等，恢复过就不再动）。
         *
         *   放在 onResume 是因为它**必须等库扫完**才做得成 ——
         *   恢复要把 uri 映射回曲目。首次扫描是异步的，onCreate 那时
         *   库还是空的，所以第一次 onResume 多半会跳过、下一次才成。
         */
        PlayerSession.onLibraryReady()
    }

    override fun onPause() {
        super.onPause()
        // ★ 成对注销。会话和设备都是**进程级**单例，忘了注销就把 Activity 泄漏出去了
        PlayerSession.removeListener(this)
        DeviceGate.removeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    override fun onSessionLog(text: String) = Unit
    override fun onSessionStateChanged() = miniBar.render()
    override fun onDeviceStateChanged() = renderDevice()

    /**
     * 刚插上小尾巴 —— 弹一次「要不要走直连」。
     *
     * ★ 只在**系统音频模式**下问：已经在直连了就别烦他。
     * ★ 只在"刚插上"时弹，**启动时不弹** —— 否则每次开 App 都挨一次，
     *   而开发期重装频繁，那就成了纯骚扰。插着启动的话，
     *   点状态条上的「切直连」是同一个对话框。
     */
    override fun onAudioDeviceAttached(name: String) {
        if (isFinishing || isDestroyed) return
        if (Settings.usbDirect(this)) return
        showDirectPrompt(name)
    }

    /** 「要不要走直连」。插入时自动弹，点状态条也能叫出来 */
    private fun showDirectPrompt(name: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.direct_prompt_title)
            .setMessage(getString(R.string.direct_prompt_msg, name))
            .setPositiveButton(R.string.direct_prompt_yes) { _, _ -> switchToDirect() }
            .setNegativeButton(R.string.direct_prompt_no, null)
            .show()
    }

    // ------------------------------------------------------------------
    //  解码器状态条
    // ------------------------------------------------------------------

    /**
     * 按 [DeviceGate.linkState] 渲染那条状态条。四种样子：
     *
     * ```
     *  已连接  绿  已连接 MOONDROP Dawn Pro          （不可点，没有可做的事）
     *  连接中  橙  正在连接…                        （不可点）
     *  失败    红  连接失败：没有找到 USB 解码器   重试
     *  未连接  橙  未连接 USB 解码器              点这里连接
     * ```
     *
     * ★ 只有**用户点过之后**才用红色 —— 见 [triedConnect]。
     * ★ 颜色一律从主题取（`Ui.c`），不写字面量：浅色主题下写死的颜色会看不见。
     */
    private fun renderDevice() {
        /*
         * ★★ 系统音频模式 —— **第五态**，而且是默认态。
         *
         *   这条状态条原来只回答"有没有连上解码器"。但走系统音频时，
         *   声音压根不经过我们独占的 USB 通路（蓝牙、外放都行），
         *   再说"未连接 USB 解码器"就是答非所问了。
         *
         *   橙色而不是绿色：**能用，但不是 bit-perfect** ——
         *   经过 AudioFlinger 混音，走蓝牙时还有一层真有损的编码。
         *
         *   插着小尾巴的话顺手把名字报出来，并且**这一条可以直接点**去切直连 ——
         *   不必再翻设置。
         */
        if (!Settings.usbDirect(this)) {
            /*
             * ★ 这条状态条只当**输出方式指示**用，不再做设备状态报告。
             *
             *   · 不写"非 bit-perfect"：这条路本来就是这样，天天顶着一句
             *     免责声明看，只会变成噪音。
             *   · 不写"检测到 XXX"：插上小尾巴时会**弹窗问一次**，
             *     用户选完这里就只该安静地说是哪种方式。
             *
             *   插着小尾巴时右侧给一个「切直连」—— 那是个**动作**不是状态，
             *   而且是不翻设置页就能改的唯一入口。
             */
            val hasDevice = DeviceGate.presentAudioDeviceName(this) != null
            applyDeviceBar(
                bg = R.drawable.bg_status_ok,
                fg = R.color.ok,
                text = withCrossfeed(getString(R.string.device_system_audio)),
                actionText = if (hasDevice) getString(R.string.action_switch_direct) else null
            )
            return
        }

        when (DeviceGate.linkState) {
            DeviceGate.LinkState.READY -> {
                triedConnect = false
                /*
                 * 直连时**带上型号** —— 这时"哪台设备在独占"是有用的信息，
                 * 尤其手上不止一只小尾巴的时候。
                 * 系统音频那边不带：那条路可能走蓝牙、外放，
                 * 报一个 USB 型号反而误导。
                 */
                val name = DeviceGate.connectedName
                applyDeviceBar(
                    bg = R.drawable.bg_status_ok,
                    fg = R.color.ok,
                    text = withCrossfeed(
                        if (name != null) getString(R.string.device_usb_direct_fmt, name)
                        else getString(R.string.device_usb_direct)
                    ),
                    /*
                     * ★★ **拿到独占这一态不给按钮**（用户 2026-10-01 定）。
                     *
                     *   「重新连接」的语义是"我怀疑现在这个连接不作数，重来一次" ——
                     *   而我们**确实拿到独占**的时候没有这个疑问，摆个按钮在那儿
                     *   只会让人以为随时该点一下。
                     *
                     *   ★ 它的判据是 [DeviceGate.LinkState]：READY = 设备活着
                     *     **而且**我们成功打开过。按钮只出现在**没拿到独占**的那两态。
                     */
                    actionText = null
                )
            }

            /*
             * ★★ 正在连接也**给一个**「重新连接」。
             *
             *   这一态原本没有动作，但它恰恰是最会卡住的一态 —— 等 USB 授权时
             *   广播要是不回来，[DeviceGate] 那边的 busy 会一直是 true，
             *   状态条就永远停在「正在连接…」，而且点什么都没反应。
             *   （2026-10-01 实测栽过，拔插都救不回来。）
             *
             *   ★ 那条路现在有 20 秒超时兜底（会自己落到 IDLE + 失败原因），
             *     这个按钮是**立刻**脱身的另一条路 —— 它会强制复位状态机重来。
             */
            DeviceGate.LinkState.CONNECTING -> applyDeviceBar(
                bg = R.drawable.bg_status_warn,
                fg = R.color.warn,
                text = getString(R.string.device_connecting),
                actionText = getString(R.string.action_reconnect)
            )

            DeviceGate.LinkState.IDLE -> {
                val failed = triedConnect
                applyDeviceBar(
                    bg = if (failed) R.drawable.bg_status_error else R.drawable.bg_status_warn,
                    fg = if (failed) R.color.error else R.color.warn,
                    text = if (failed) {
                        getString(
                            R.string.device_fail_fmt,
                            DeviceGate.lastMessage ?: getString(R.string.device_unknown)
                        )
                    } else {
                        getString(R.string.device_none)
                    },
                    actionText = getString(
                        if (failed) R.string.action_retry else R.string.action_connect
                    )
                )
            }
        }
    }

    /**
     * 状态条的统一上色。
     *
     * @param actionText 右侧那个按钮的字。**null = 这一态没有可做的事**，那就不给点。
     *   ★ 原来是传 `R.string` 资源 id，改成直接给字串，是为了让"已检测到 XXX · 切直连"
     *     这种**要拼设备名**的按钮能共用同一套。
     */
    /**
     * 状态条文字后面接上交叉馈送那截。
     *
     * 例：`USB 直连 · MOONDROP Dawn Pro · 交叉馈送 · 中`
     *      `系统音频 · 交叉馈送 · 弱`
     *
     * ★ 关着的时候 [PlayerSession.crossfeedLabel] 返回 null，原样返回 ——
     *   状态条上不会凭空多一截。
     */
    private fun withCrossfeed(base: String): String =
        PlayerSession.crossfeedLabel()?.let { "$base · $it" } ?: base

    private fun applyDeviceBar(bg: Int, fg: Int, text: String, actionText: String?) {
        val color = Ui.c(this, fg)
        barDevice.visibility = View.VISIBLE
        barDevice.setBackgroundResource(bg)
        barDevice.isClickable = actionText != null
        barDevice.isFocusable = actionText != null
        ivDevice.imageTintList = ColorStateList.valueOf(color)
        tvDevice.setTextColor(color)
        tvDevice.text = text
        tvDeviceAction.visibility = if (actionText == null) View.GONE else View.VISIBLE
        if (actionText != null) {
            tvDeviceAction.text = actionText
            tvDeviceAction.setTextColor(color)
        }
    }

    /**
     * 从状态条一键切到 **USB 直连（bit-perfect）**。
     *
     * ★ 会重建原生上下文（系统音频用的是无设备上下文，直连用的是带设备上下文，
     *   两者不通用）—— 所以**当前播放会中断**。这是有意的：一路放着一路换
     *   输出端，状态很容易对不上。
     */
    private fun switchToDirect() {
        Settings.setUsbDirect(this, true)
        renderDevice()
        DeviceGate.reopen(this) { ok, msg ->
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (ok) "已切换至 USB 直连（bit-perfect）：$msg" else "切换失败：$msg",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                renderDevice()
            }
        }
    }

    /**
     * ⑤ 重新连接 —— 把被抢走的独占 USB 拿回来。
     *
     * ★★ 用户 2026-09-29 的诉求：DSP 的配套软件（**跑在手机上**）会抢走独占接口，
     *    用它改完 DSP 参数退出来之后，hifiprobe 这边拿不回来 ——
     *    原来的唯一出路是**拔插一次线**。
     *
     * ★★ 走 [DeviceGate.reopen] 而不是 [DeviceGate.ensureOpen]：
     *    前者是**先拆再建**（收尾里带着一次 `libusb_reset_device`），
     *    后者在"我以为还开着"的时候会直接返回 —— 而设备被别的 App
     *    抢走过之后，我们这边的状态恰恰是**不可信的**。
     *
     * ★★ 拆建会中断当前播放（`shutdown` 里会 `stopBlocking`），所以正在放的时候
     *    **先问一句** —— 别让用户随手一点，音乐就没了。
     */
    private fun reconnectDevice() {
        val h = PlayerSession.handle
        if (h != 0L && NativePlayer.nativeIsPlaying(h)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.action_reconnect))
                .setMessage("重新连接将先中断当前会话，播放会停止。是否继续？")
                .setPositiveButton(getString(R.string.action_reconnect)) { _, _ -> doReconnect() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        doReconnect()
    }

    private fun doReconnect() {
        triedConnect = false
        renderDevice()
        DeviceGate.reopen(this) { ok, msg ->
            // ★ 回调**不保证在主线程**（插拔广播那条路就不是）
            runOnUiThread {
                // ★ 失败了要记下来，状态条才会转成红色的「失败 + 重试」——
                //   不设的话用户看到的是"没检测到解码器"，像是从来没试过
                if (!ok) triedConnect = true
                toast(if (ok) "已重新连接：$msg" else "重新连接失败：$msg")
                renderDevice()
            }
        }
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------

    /**
     * 首次扫描。
     *
     * 只更新顶端那一行统计，**不弹进度条** —— 首页的四张卡片本来就还没数字，
     * 盖一层遮罩反而挡住用户去点「音乐库」（那里才有导入入口）。
     */
    private fun firstScan() {
        io.execute {
            Library.onScanProgress = { done, total ->
                runOnUiThread { tvHomeStat.text = "正在扫描 $done / $total" }
            }
            Library.refresh()
            runOnUiThread {
                Library.onScanProgress = null
                refreshStats()
                // ★ 扫完才知道 uri 对应哪首曲目 —— 队列恢复要等这一刻
                PlayerSession.onLibraryReady()
            }
        }
    }

    /**
     * 四张卡片上的数字。
     *
     * 放后台线程算：专辑分组要遍历整个库并给每首算一次归并键，
     * 歌单数量还要读一次磁盘。曲库小的时候主线程也扛得住，
     * 但这不是"能不能"的问题 —— onResume 每回来一次都跑，
     * 攒到几百首就是每次切页都卡一下。
     */
    private fun refreshStats() {
        io.execute {
            val tracks = Library.size()
            val folders = Library.folders().size
            val albums = Library.albums().size
            /*
             * 歌单卡片上**不再显示队列**。
             *
             * 队列是临时的、属于"正在播放"的语境，入口在正在播放页；
             * 摆在这里会让人以为它是歌单的一部分。这里只说歌单自己的事。
             */
            val playlists = Playlists.all()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvAllSongsCount.text = if (tracks == 0) "音乐库为空" else "$tracks 首"
                tvLibraryCount.text =
                    if (folders == 0) "$tracks 首 · 未分文件夹" else "$tracks 首 · $folders 个文件夹"
                tvPlaylistCount.text =
                    if (playlists.isEmpty()) "尚未创建歌单"
                    else "${playlists.size} 个 · 共 ${playlists.sumOf { it.size }} 首"
                tvAlbumCount.text = if (albums == 0) "尚未创建专辑" else "$albums 张"
                tvHomeStat.text =
                    if (tracks == 0) "点击「音乐库」导入音乐"
                    else "$tracks 首 · $albums 张专辑 · ${playlists.size} 个歌单"
            }
        }
    }
}
