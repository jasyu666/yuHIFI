package com.hifiprobe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvReport: TextView
    private lateinit var spinnerRate: Spinner
    private var rateOptions: List<Int> = emptyList()

    private var selectedDevice: UsbDevice? = null

    /*
     * 连接也归 PlayerSession —— 它和原生句柄是一体的，必须同生共死。
     * 现在媒体库页也会开设备，各持一份必然出现"一边关掉了另一边还在用"。
     */
    private var connection: UsbDeviceConnection?
        get() = PlayerSession.connection
        set(value) { PlayerSession.connection = value }

    private var claimedIface: Int = -1
    private var claimedAcIface: Int = -1

    private var lastRaw: ByteArray? = null

    /*
     * ★ 设备句柄与描述符解析结果**归 PlayerSession 管**，不再是界面的私有状态。
     *
     * 原因：后台连播要靠服务驱动队列，服务同样需要句柄和描述符才能打开
     * 下一首。两份状态一定会分叉，所以留一份在这里转发。
     * 下面是只读转发 —— 写入统一走 PlayerSession.setDevice() / clearDevice()。
     */
    private val nativeHandle: Long get() = PlayerSession.handle

    private var lastParse: ParseResult?
        get() = PlayerSession.parse
        set(value) = PlayerSession.setDevice(PlayerSession.handle, value)

    // ---- P1 文件播放状态 ----
    private lateinit var tvFileInfo: TextView
    private lateinit var tvPlayStats: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var btnStopFile: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var cbFollowFeedback: android.widget.CheckBox
    private lateinit var cbFlushOnSeek: android.widget.CheckBox
    private lateinit var cbOutputDump: android.widget.CheckBox
    private lateinit var cbAllowAllRates: android.widget.CheckBox
    private lateinit var btnScanRatesLive: Button
    private lateinit var btnScanDsd: Button
    private lateinit var spinnerUrb: Spinner

    /** 与 spinnerUrb 的顺序一一对应 */
    private val urbCountOptions = listOf(8, 16, 32, 64, 128)

    /**
     * 默认 32 —— 实测这是能消除断音的最小值。
     * 取最小值是因为 seek 后旧音频的尾巴长度**等于**队列深度，够用就好；
     * 界面上保留到 128，卡顿余量不够时可以现场往上调。
     */
    private var urbCount: Int = 32

    /*
     * 播放状态也统一从 PlayerSession 读 —— 界面不再自己记账。
     * 曾经这里有一份 fileSessionOpen / filePaused / fileDurationMs，
     * 那是"播放逻辑长在界面里"的产物；现在推进队列的是服务，
     * 界面留着这些副本只会和服务里的真值对不上。
     */
    private val fileSessionOpen: Boolean get() = PlayerSession.isActive
    private val filePaused: Boolean get() = PlayerSession.state == PlayerSession.State.PAUSED
    private val fileDurationMs: Long get() = PlayerSession.durationMs

    /** 用户正在拖动进度条时不能用播放位置回写，否则手指会被反复"抢走" */
    private var draggingSeek = false

    // ---- DAC 硬件音量状态 ----
    private lateinit var tvVolumeInfo: TextView
    private lateinit var seekVolume: SeekBar

    private var volumeReady = false
    private var volMin = 0L
    private var volMax = 0L
    private var volRes = 0L
    private var volCur = 0L

    /** 上一次真正下发过的值，用来滤掉拖动过程中的重复请求 */
    private var volLastSent = Int.MIN_VALUE

    /**
     * 音量下发走单独一条线程：拖滑条会连续产生几十次请求，
     * 每次现开一个 Thread 太浪费，而这又必须离开 UI 线程（控制传输要等设备回）。
     */
    private val volumeExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor()

    private val log = StringBuilder()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            append("USB 权限回调: ${device?.deviceName} granted=$granted")
            if (granted && device != null) {
                selectedDevice = device
                openDevice(device)
            } else {
                setStatus("权限被拒绝，无法继续")
            }
        }
    }

    /**
     * 前台服务的启动结果。
     *
     * 防冻结有没有真的生效不该靠猜 —— 它直接决定了切屏后会不会出现静音，
     * 所以结果要写进导出的报告里，事后能核对。
     */
    private val fgsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PlaybackService.ACTION_FGS_STATUS) return
            val ok = intent.getBooleanExtra(PlaybackService.EXTRA_FGS_OK, false)
            val detail = intent.getStringExtra(PlaybackService.EXTRA_FGS_DETAIL) ?: "?"
            append("前台服务: ${if (ok) "✓ 已生效" else "✗ 启动失败"} —— $detail")
            if (!ok) {
                append("  ⚠ 进程可能被系统当作缓存进程冻结，切屏后解码线程会停摆，")
                append("    表现为缓冲耗尽后的一段静音。")
                append("    小米 / HyperOS 还需要额外把本应用的省电策略设为「无限制」——")
                append("    那一层不看标准的前台服务状态。")
            }
        }
    }

    private val statsTicker = object : Runnable {
        override fun run() {
            if (nativeHandle != 0L && NativePlayer.nativeIsPlaying(nativeHandle)) {
                tvStatus.text = "播放中…\n" + NativePlayer.nativeStats(nativeHandle)
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    /*
     * 原来这里有个 playTicker（每 500ms 读一次引擎信息、顺便判断"放完了没有"）。
     *
     * 它被两件事取代了：
     *   · 界面刷新 —— 改由 PlayerSession 的监听回调驱动（renderSessionState）
     *   · 结束判定 —— **搬进了 PlaybackService 的心跳**，因为界面一停
     *     （切后台、锁屏、被回收）轮询就断了，自动下一首也就没了，
     *     而那正是整个功能的意义所在。
     */

    /*
     * 用 SAF 选文件。
     *
     * 走 ACTION_OPEN_DOCUMENT 而不是直接拿路径：scoped storage 下 App 没有
     * 任意路径的读权限，而 SAF 给的 content:// URI 能通过
     * ContentResolver.openFileDescriptor() 变成 fd，再 detachFd() 把所有权
     * 交给原生层 —— FFmpeg 于是可以像读普通文件一样读它，全程不需要任何
     * 存储权限。
     *
     * MIME 过滤用 "audio/" 通配：.flac 与 .m4a 在系统文档提供者里分别是
     * audio/flac 与 audio/mp4，都落在这个范围内。若某个文件在选择器里是灰的，
     * 说明它的提供者上报了非音频 MIME，改这里的过滤串即可。
     *
     * 注意别在注释里写出斜杠加星号的组合 —— Kotlin 的块注释可以嵌套，
     * 那样会又开一层注释，把后面的代码整个吞掉。
     */
    /*
     * API 33+ 通知需用户授权才可见。
     *
     * 被拒绝也**不影响**前台服务的防冻结作用，只是那条常驻通知不显示 ——
     * 所以这里是尽力索取，不阻塞播放，也不拿它当播放的前提。
     */
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        append("通知权限: ${if (granted) "已授予" else "被拒绝（前台服务照常运行，仅通知不可见）"}")
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /*
     * 选音频文件。用 ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE 拿多选 ——
     * 选中的一批直接成为播放队列，这样"播完自动下一首"立刻就有东西可放。
     *
     * 用 OpenMultipleDocuments 而不是 OpenDocument：后者只能选一个，
     * 队列永远只有一首，连播功能根本验证不了。
     */
    private val pickAudioFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) {
            append("未选择文件")
            return@registerForActivityResult
        }
        val tracks = uris.map { uri ->
            // 标题先取文件名。精确的标签和规格要打开文件才知道，
            // 播到那一首时由 PlayerSession 回填 —— 逐首开 FFmpeg 太慢。
            Track(uri = uri.toString(), title = displayNameOf(uri))
        }
        append("已选 ${tracks.size} 个文件，开始播放第一个")
        PlayerSession.setQueue(tracks, 0)
    }

    /** 从 SAF URI 取一个能看的文件名。取不到就退回 URI 的末段。 */
    private fun displayNameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放会话需要 applicationContext 去开 SAF 的 fd；这里尽早初始化，
        // 免得后台自动下一首时（界面可能已经没了）拿不到 context
        PlayerSession.init(applicationContext)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvReport = findViewById(R.id.tvReport)
        spinnerRate = findViewById(R.id.spinnerRate)
        tvFileInfo = findViewById(R.id.tvFileInfo)
        tvPlayStats = findViewById(R.id.tvPlayStats)
        seekBar = findViewById(R.id.seekBar)
        tvVolumeInfo = findViewById(R.id.tvVolumeInfo)
        seekVolume = findViewById(R.id.seekVolume)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStopFile = findViewById(R.id.btnStopFile)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(
                fgsStatusReceiver, IntentFilter(PlaybackService.ACTION_FGS_STATUS),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(fgsStatusReceiver, IntentFilter(PlaybackService.ACTION_FGS_STATUS))
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.btnPermission).setOnClickListener { requestPermission() }
        findViewById<Button>(R.id.btnParse).setOnClickListener { runParse() }
        findViewById<Button>(R.id.btnNativeProbe).setOnClickListener { runNativeProbe() }
        findViewById<Button>(R.id.btnClaim).setOnClickListener { runClaimTest() }
        findViewById<Button>(R.id.btnBitPerfect).setOnClickListener { runBitPerfectCheck() }
        findViewById<Button>(R.id.btnScanRates).setOnClickListener { runRateScan() }
        btnScanRatesLive = findViewById(R.id.btnScanRatesLive)
        btnScanRatesLive.setOnClickListener { runRateScanLive() }
        btnScanDsd = findViewById(R.id.btnScanDsd)
        btnScanDsd.setOnClickListener { runDsdRateScan() }
        findViewById<Button>(R.id.btnTone).setOnClickListener { startTone() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopTone() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportReport() }

        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            if (nativeHandle == 0L) {
                toast("请先申请 USB 权限并打开设备")
                return@setOnClickListener
            }
            if (lastParse == null) {
                append("尚未解析描述符，先自动执行 ①…")
                runParse()
                if (lastParse == null) {
                    toast("描述符解析失败，无法确定设备支持哪些格式")
                    return@setOnClickListener
                }
            }
            pickAudioFiles.launch(arrayOf("audio/*"))
        }
        cbFollowFeedback = findViewById(R.id.cbFollowFeedback)
        cbFollowFeedback.setOnCheckedChangeListener { _, checked ->
            val h = nativeHandle
            if (h != 0L) {
                NativePlayer.nativeSetFollowFeedback(h, checked)
                append("A/B 开关: feedback 跟随 ${if (checked) "开" else "关"}（下次起播生效）")
            }
        }

        cbFlushOnSeek = findViewById(R.id.cbFlushOnSeek)
        cbFlushOnSeek.setOnCheckedChangeListener { _, checked ->
            val h = nativeHandle
            if (h != 0L) {
                NativePlayer.nativeSetFlushQueueOnSeek(h, checked)
                append("seek 清队 ${if (checked) "开——去掉旧音频尾巴，代价是一小段静音" else "关——seek 后最多还有队列深度那么长的旧音频"}（下次起播生效）")
            }
        }

        cbAllowAllRates = findViewById(R.id.cbAllowAllRates)
        /*
         * ★★ 和设置页共用同一个值（`Settings.allowAllRates`），而且是**反语义**：
         *   勾上 = **禁用** 44.1k 家族。
         *
         *   这里原来是个**孤岛**：只调 `nativeSetAllowAllStandardRates`，
         *   既不读设置也不写回去，XML 里还写死 `checked="true"`。后果两个：
         *     · 在设置页禁用了 44.1k，进诊断页一看还是勾着的 —— **显示的值是错的**
         *     · 在诊断页改它不进设置，重启 App 又变回去 —— **改了留不住**
         *
         * ★ 顺序要紧：**先把界面对到真实值、再接监听器**。
         *   反过来的话，下面那句 `isChecked = ...` 会被当成"用户改动"，
         *   触发一次写回 —— 把刚读出来的值又写一遍，还可能相互打架。
         */
        cbAllowAllRates.isChecked = !Settings.allowAllRates(this)
        cbAllowAllRates.setOnCheckedChangeListener { _, checked ->
            val allow = !checked                      // 反语义：勾上 = 禁用
            Settings.setAllowAllRates(this, allow)
            NativePlayer.nativeSetAllowAllStandardRates(allow)
            append(
                if (checked) {
                    "禁用 44.1k 家族 开——退回旧策略：只走 48k 家族，" +
                            "44.1k 家族重采样到对应速率（下次打开文件生效）"
                } else {
                    "禁用 44.1k 家族 关——8 个标准速率全部直通，" +
                            "44.1k 曲库不再重采样（下次打开文件生效）"
                }
            )
        }
        // 原生层默认也是开，这里把真实值同步过去
        NativePlayer.nativeSetAllowAllStandardRates(Settings.allowAllRates(this))

        cbOutputDump = findViewById(R.id.cbOutputDump)
        cbOutputDump.setOnCheckedChangeListener { _, checked ->
            val h = nativeHandle
            if (h != 0L) {
                NativePlayer.nativeSetOutputDump(h, checked, dumpFilePath())
                append(
                    if (checked) {
                        "输出字节转储 开——停止播放时把送进 USB 的字节写到 " +
                            "${dumpFilePath()}（上限 32MB，约 111 秒 @48k/24bit）"
                    } else {
                        "输出字节转储 关"
                    }
                )
            }
        }

        spinnerUrb = findViewById(R.id.spinnerUrb)
        spinnerUrb.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item,
            urbCountOptions.map { "$it 个 URB（余量 ${it} ms）" }
        ) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent) as TextView
                v.setTextColor(0xFFD6E1EC.toInt()); v.textSize = 13f
                v.setPadding(16, 8, 16, 8)
                return v
            }

            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(pos, cv, parent) as TextView
                v.setTextColor(0xFFD6E1EC.toInt()); v.setBackgroundColor(0xFF1B2430.toInt())
                v.textSize = 13f; v.setPadding(16, 20, 16, 20)
                return v
            }
        }
        spinnerUrb.setSelection(urbCountOptions.indexOf(urbCount))
        spinnerUrb.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                urbCount = urbCountOptions[pos]
                val h = nativeHandle
                if (h != 0L) {
                    NativePlayer.nativeSetUrbCount(h, urbCount)
                    append("在途 URB 深度: $urbCount（余量 $urbCount ms，下次起播生效）")
                }
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnPlayPause.setOnClickListener { togglePause() }
        btnStopFile.setOnClickListener { stopFilePlayback() }
        btnPrev.setOnClickListener { playPrev() }
        btnNext.setOnClickListener { playNext() }
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) applyVolume(quantizeVolume(volMin + progress))
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    draggingSeek = true
                    tvPlayStats.text = "松开手指跳到 ${fmtTime(progress.toLong())}"
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                draggingSeek = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                draggingSeek = false
                if (nativeHandle == 0L || !fileSessionOpen) return
                /*
                 * 走 PlayerSession 而不是直接调原生：seek 会碰解码器和环形缓冲，
                 * 而此刻会话的 worker 线程可能正在切歌或收尾 —— 两条线程同时
                 * 操作同一个引擎就是数据竞争。排进同一条队列，天然串行。
                 *
                 * 它也不占 UI 线程：会话在自己的线程上做，做完回调刷新界面。
                 */
                PlayerSession.seekTo(sb.progress)
            }
        })

        // 第一行就报版本 —— 界面上也能直接看到自己装的是哪个构建
        append(appVersionLine())
        append("libusb 版本: ${runCatching { NativePlayer.nativeVersion() }.getOrElse { "原生库未加载: ${it.message}" }}")
        append("CPU 分组: ${cpusetGroups()}")
        append("当前: ${cpuAffinity()}")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  ${Build.SUPPORTED_ABIS.joinToString()}")

        refreshDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(statsTicker)
        runCatching { unregisterReceiver(permissionReceiver) }
        runCatching { unregisterReceiver(fgsStatusReceiver) }

        // 顺序要紧：先把音量线程停干净，再释放原生句柄。
        // 队列里可能还压着 SET_CUR，而它要用的句柄马上就会被 nativeClose 释放 ——
        // 让它在句柄失效后跑起来就是 use-after-free。
        //
        // 等待上限取 2.5 秒：一次 SET_CUR + 回读最多各占 libusb 的 1000ms 超时，
        // 所以任何任务都不可能比这更久。正常情况这里瞬间返回。
        volumeExecutor.shutdownNow()
        runCatching {
            volumeExecutor.awaitTermination(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        closeEverything()
    }

    // ---------------------------------------------------------------------
    //  日志与状态
    // ---------------------------------------------------------------------
    private fun append(text: String) {
        log.append(text).append('\n')
        tvReport.text = log.toString()
    }

    private fun setStatus(text: String) {
        tvStatus.text = text
    }

    private fun section(title: String) {
        append("")
        append("════════════════════════════════")
        append("  $title")
        append("════════════════════════════════")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------------
    //  设备发现与权限
    // ---------------------------------------------------------------------
    private fun isAudioDevice(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).interfaceClass == 0x01) return true
        }
        // 有些设备把音频接口藏在第二个配置里，退而求其次看设备类
        return d.deviceClass == 0x01 || d.deviceClass == 0xEF
    }

    private fun refreshDevices() {
        val all = usbManager.deviceList.values.toList()
        section("USB 设备枚举")
        if (all.isEmpty()) {
            append("未发现任何 USB 设备。请确认：")
            append("  1. 小尾巴已插好（换个 OTG 线试试，劣质线只供电不传数据）")
            append("  2. 手机支持 USB Host（部分机型需在开发者选项里打开）")
            append("  3. 没有被其他 App 独占（先杀掉其他播放器）")
            setStatus("未发现 USB 设备")
            return
        }

        for (d in all) {
            val audio = if (isAudioDevice(d)) "  ← 音频设备" else ""
            append(
                "%s  VID:PID=%04X:%04X  接口数=%d%s".format(
                    d.deviceName, d.vendorId, d.productId, d.interfaceCount, audio
                )
            )
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                append(
                    "    接口 %d: class=0x%02X sub=0x%02X proto=0x%02X 端点=%d".format(
                        itf.id, itf.interfaceClass, itf.interfaceSubclass,
                        itf.interfaceProtocol, itf.endpointCount
                    )
                )
            }
        }

        val candidates = all.filter { isAudioDevice(it) }.ifEmpty { all }
        if (candidates.size == 1) {
            selectedDevice = candidates[0]
            append("")
            append("自动选中: ${candidates[0].deviceName}")
            setStatus("已选中 ${candidates[0].deviceName}")
        } else {
            setStatus("发现 ${candidates.size} 个候选设备，请点「申请 USB 权限」选择")
        }
    }

    private fun requestPermission() {
        val candidates = usbManager.deviceList.values.filter { isAudioDevice(it) }
            .ifEmpty { usbManager.deviceList.values.toList() }
        if (candidates.isEmpty()) {
            toast("没有可用设备，请先刷新")
            return
        }
        if (candidates.size == 1) {
            askPermission(candidates[0])
            return
        }

        val labels = candidates.map {
            "%s  %04X:%04X".format(it.deviceName, it.vendorId, it.productId)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要测试的 USB 设备")
            .setItems(labels) { _, which -> askPermission(candidates[which]) }
            .show()
    }

    private fun askPermission(device: UsbDevice) {
        selectedDevice = device
        if (usbManager.hasPermission(device)) {
            append("已有权限: ${device.deviceName}")
            openDevice(device)
        } else {
            setStatus("正在申请权限…")
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun openDevice(device: UsbDevice) {
        section("打开设备")
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            append("openDevice 返回 null。常见原因：")
            append("  · 权限未授予")
            append("  · 设备已被其他进程独占（另一个播放器正在用）")
            setStatus("打开设备失败")
            return
        }
        connection = conn
        val fd = conn.fileDescriptor
        append("openDevice 成功: ${device.deviceName}")
        append("原生 fd = $fd  ${if (fd >= 0) "✓ 有效" else "✗ 无效，后续方案无法进行"}")
        append("")
        append("⚠ 接下来直到点「停止播放/释放接口」之前，不要退出本页，")
        append("  也不要让其他 App 抢占该设备。")

        // 建立原生句柄，这一步就是 P0 的生死线
        if (nativeHandle == 0L) {
            val h = NativePlayer.nativeOpen(fd)
            if (h == 0L) {
                append("")
                append("✗ nativeOpen 失败: ${NativePlayer.nativeLastError()}")
                setStatus("libusb 包裹 fd 失败 — P0 不通过")
            } else {
                PlayerSession.setDevice(h, lastParse)
                // 把 UI 上的三个设置同步给原生层（原生默认值一致，这里只是对齐）
                NativePlayer.nativeSetFollowFeedback(h, cbFollowFeedback.isChecked)
                NativePlayer.nativeSetUrbCount(h, urbCount)
                NativePlayer.nativeSetFlushQueueOnSeek(h, cbFlushOnSeek.isChecked)
                NativePlayer.nativeSetOutputDump(h, cbOutputDump.isChecked, dumpFilePath())
                append("✓ nativeOpen 成功，libusb 已接管该 fd")
                setStatus("就绪，建议依次点 ① → ② → ③ → ④ → ⑤")
            }
        }
        // 让音量区显示下一步该做什么（要拿到 Feature Unit 的实体 ID 才能查）
        refreshVolume()
    }

    // ---------------------------------------------------------------------
    //  ① 纯 Java 描述符解析
    // ---------------------------------------------------------------------
    private fun runParse() {
        val conn = connection ?: run { toast("请先申请权限并打开设备"); return }
        section("① UAC 描述符解析（纯 Java，不依赖原生库）")

        val raw = conn.rawDescriptors
        if (raw == null || raw.isEmpty()) {
            append("getRawDescriptors() 返回空，无法解析")
            return
        }
        lastRaw = raw
        append("取得原始描述符 ${raw.size} 字节")
        append("")

        val result = try {
            UacParser(raw).parse()
        } catch (e: Exception) {
            append("解析异常: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        lastParse = result
        append(Report.uac(result))

        append("")
        append("── 原始描述符十六进制 ──")
        append(Report.hexDump(raw))

        // 不在这里查音量 —— 查询要 claim AudioControl，会干扰 ③ 的抢占测试。
        // 音量在播放起播后自动启用，见 refreshVolume() 的说明。
        refreshVolume()

        setStatus("解析完成，见下方报告")
    }

    // ---------------------------------------------------------------------
    //  ② 原生探针
    // ---------------------------------------------------------------------
    private fun runNativeProbe() {
        section("② 原生探针（libusb_wrap_sys_device）")
        if (nativeHandle == 0L) {
            append("✗ 原生句柄未建立。这说明 libusb_wrap_sys_device 没能接管这个 fd。")
            append("  最后错误: ${NativePlayer.nativeLastError()}")
            append("")
            append("  影响：整条自研 UAC 驱动路线走不通，需要退回考虑其他方案。")
            setStatus("P0 关键步骤失败")
            return
        }
        /*
         * ★★ 从 p60 起"句柄非 0"**不再等于**"有 USB 设备"。
         *
         *   系统音频模式下上下文是**无设备**的：句柄非 0（引擎在里面），
         *   但 `devh` 是空的。原来这里只判 `nativeHandle == 0L` 就往下走，
         *   紧接着 nativeDescribe 会解引用空指针 —— 直接崩。
         */
        if (PlayerSession.parse == null) {
            append("✗ 当前是**系统音频模式** —— 上下文里没有 USB 设备，这一页的探针都用不了。")
            append("  要跑诊断：插上小尾巴，并在 设置 → 输出方式 里切到「USB 直连」")
            setStatus("需要 USB 直连")
            return
        }
        append("✓ 原生句柄有效（且已接管 USB 设备）")
        append("")
        append(NativePlayer.nativeDescribe(nativeHandle))
        setStatus("原生探针完成")
    }

    // ---------------------------------------------------------------------
    //  ③ 抢占接口测试
    // ---------------------------------------------------------------------
    private fun runClaimTest() {
        val conn = connection ?: run { toast("请先打开设备"); return }
        val device = selectedDevice ?: run { toast("未选择设备"); return }
        section("③ 接口抢占测试（Java 侧 claimInterface force=true）")

        append("这一步验证能否把小尾巴从系统音频栈手里抢过来。")
        append("Android 的 snd-usb-audio / AudioService 通常会占用音频接口，")
        append("必须先解绑才能做 bit-perfect 直通。")
        append("")

        var anyClaimed = false
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            val tag = "接口 ${itf.id} (class=0x%02X sub=0x%02X)".format(
                itf.interfaceClass, itf.interfaceSubclass
            )
            val ok = try {
                conn.claimInterface(itf, true)
            } catch (e: Exception) {
                append("$tag → 异常 ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (ok) {
                anyClaimed = true
                append("$tag → 抢占成功 ✓")
                conn.releaseInterface(itf)
                append("$tag → 已释放（交给原生层）")
            } else {
                append("$tag → 抢占失败 ✗（可能被系统占用或 SELinux 拦截）")
            }
        }

        append("")
        if (anyClaimed) {
            append("结论：至少有一个接口可以被抢占，独占输出可行。")
            setStatus("接口抢占测试通过")
        } else {
            append("结论：所有接口都抢不到。需要检查 SELinux 策略或换机型。")
            setStatus("接口抢占失败")
        }
    }

    // ---------------------------------------------------------------------
    //  ④ BIT_PERFECT 混音属性检测（低成本备选路线）
    //
    //  API 归属容易记错：AudioTrack 上并没有 setMixerAttributes()。
    //  正确入口是 AudioManager：
    //    getSupportedMixerAttributes(device)  查询 HAL 声明支持哪些混音配置
    //    setPreferredMixerAttributes(...)     申请启用
    //  后者需要 MODIFY_AUDIO_ROUTING，这是 signature|privileged 级权限，
    //  普通三方应用拿不到，所以实测结论通常是「此路线不可用」。
    // ---------------------------------------------------------------------
    private fun runBitPerfectCheck() {
        section("④ BIT_PERFECT 混音属性检测")
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        append("── 系统可见的输出设备 ──")
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (d in outs) {
            append(
                "  id=${d.id} type=${d.type} (${deviceTypeName(d.type)})  " +
                        "名称=${d.productName}"
            )
            append("     采样率=${d.sampleRates.joinToString()}")
            append("     声道=${d.channelCounts.joinToString()}")
            append("     编码=${d.encodings.joinToString { encodingName(it) }}")
        }

        val usbDev = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        append("")
        if (usbDev == null) {
            append("未发现 USB 音频输出设备。")
            append("若②已成功，这反而是好事：说明音频接口空闲，抢占用不着。")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            append("API < 29，不支持 AudioMixerAttributes，跳过。")
            return
        }

        // 先问系统：HAL 到底提供了哪些可选混音配置
        append("── 查询 HAL 支持的混音配置 ──")
        val supported: List<AudioMixerAttributes>? = try {
            am.getSupportedMixerAttributes(usbDev)
        } catch (e: Exception) {
            append("  调用抛异常: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (supported != null) {
            if (supported.isEmpty()) {
                append("  返回空列表 —— HAL 未声明任何可选混音配置")
            }
            for (m in supported) {
                append(
                    "  ${mixerBehaviorName(m.mixerBehavior)}  " +
                            "${m.format.sampleRate}Hz / " +
                            "${encodingName(m.format.encoding)} / " +
                            "${m.format.channelCount}ch"
                )
            }
        }

        val halBitPerfect = supported?.firstOrNull {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
        append("")
        append("HAL 是否提供 BIT_PERFECT 配置: ${if (halBitPerfect != null) "是 ✓" else "否 ✗"}")

        // 再尝试申请。预期会被权限挡掉，这本身就是结论。
        append("")
        append("── 尝试申请 BIT_PERFECT ──")
        val aa = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val encodings = mutableListOf<Pair<Int, String>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            encodings.add(AudioFormat.ENCODING_PCM_24BIT_PACKED to "24bit_packed")
        }
        encodings.add(AudioFormat.ENCODING_PCM_16BIT to "16bit")

        val candidates = mutableListOf<Pair<String, AudioMixerAttributes>>()
        halBitPerfect?.let { candidates.add("HAL 自带的" to it) }
        for ((enc, encName) in encodings) {
            for (rate in listOf(96000, 48000, 44100)) {
                try {
                    val f = AudioFormat.Builder()
                        .setEncoding(enc)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    candidates.add(
                        "自建 ${rate}Hz/$encName" to
                                AudioMixerAttributes.Builder(f)
                                    .setMixerBehavior(
                                        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                                    )
                                    .build()
                    )
                } catch (e: Exception) {
                    append("  构造 ${rate}Hz/$encName 失败: ${e.javaClass.simpleName}")
                }
            }
        }

        var anyOk = false
        for ((label, attrs) in candidates) {
            val r = try {
                if (am.setPreferredMixerAttributes(aa, usbDev, attrs)) {
                    anyOk = true
                    "成功 ✓"
                } else {
                    "被拒绝（返回 false）"
                }
            } catch (e: SecurityException) {
                "SecurityException：缺少 MODIFY_AUDIO_ROUTING 签名权限"
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            append("  $label → $r")
        }

        append("")
        if (anyOk) {
            append("结论：BIT_PERFECT 路线可用，可以考虑省掉自研 UAC 驱动。")
        } else {
            append("结论：BIT_PERFECT 对普通三方应用不可用。")
            append("  setPreferredMixerAttributes 需要 MODIFY_AUDIO_ROUTING 签名级权限，")
            append("  除非应用预装进 system 分区，否则申请必定失败。")
            append("  → 低成本路线可以排除，必须走自研 UAC 驱动 + libusb 独占输出。")
        }
        setStatus("BIT_PERFECT 检测完成")
    }

    private fun mixerBehaviorName(b: Int): String = when (b) {
        AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT -> "DEFAULT"
        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "BIT_PERFECT"
        else -> "0x%X".format(b)
    }

    private fun deviceTypeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        else -> "OTHER($t)"
    }

    private fun encodingName(e: Int): String = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "16bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit_packed"
        AudioFormat.ENCODING_PCM_32BIT -> "32bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "float32"
        else -> "0x%X".format(e)
    }

    // ---------------------------------------------------------------------
    //  ⑤ 出声测试
    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    //  ⑥ 采样率接受度扫描
    //
    //  实测教训：设备描述符里缺 44100（只有 88200/176400/352800），
    //  而 44100 是 CD 标准、最普遍的采样率。必须问清楚设备到底接受哪些。
    // ---------------------------------------------------------------------
    private fun runRateScan() {
        section("⑥ 扫描设备实际接受的采样率")

        if (nativeHandle == 0L) {
            append("✗ 原生句柄无效，无法进行。")
            return
        }
        if (lastParse == null) {
            append("尚未解析描述符，先自动执行 ①…")
            runParse()
            if (lastParse == null) return
        }
        if (NativePlayer.nativeIsPlaying(nativeHandle)) {
            append("先停止当前播放。")
            stopTone()
        }

        val parsed = lastParse!!
        val acIf = parsed.acInterface
        val clockId = parsed.clocks.firstOrNull()?.clockId ?: 1

        // 扫描会改动设备状态，先把 AudioControl 以空闲态拿下来
        if (acIf >= 0 && claimedAcIface < 0) {
            append(NativePlayer.nativeClaim(nativeHandle, acIf, -1))
            claimedAcIface = acIf
        }

        append(
            NativePlayer.nativeScanRates(
                nativeHandle, acIf, clockId, STANDARD_RATES.toIntArray()
            )
        )
        append("")
        append("⚠ 这个结果**不能**用来判断设备支持哪些速率 —— 设备对任何速率都回答")
        append("「接受」，还会把设定值原样回读。实测请求 44000 时回读也是 44000，")
        append("而设备其实根本没换、还停在 48000 上。要查真伪请用下面的 ⑧。")
        setStatus("采样率扫描完成")
    }

    // ---------------------------------------------------------------------
    //  ⑧ 速率真伪验证
    //
    //  ⑥ 只问「设备收不收」，而设备对什么都说收。这里改成**真的发一段流**，
    //  然后读**设备自己**的 feedback 端点 —— 它报的是设备消耗样本的速率，
    //  与我们怎么组包无关，是唯一能识破设备的依据。
    //
    //  实测（2026-09-22）：8 个标准速率全部为真；44000/50000/56000 请求被
    //  「接受」但设备静默忽略、继续按上一次的速率跑，反馈值差着几万 ppm。
    // ---------------------------------------------------------------------
    private fun runRateScanLive() {
        section("⑧ 速率真伪验证（实际发流 + 读设备 feedback 端点）")

        if (nativeHandle == 0L) {
            append("✗ 原生句柄无效，无法进行。")
            return
        }
        if (lastParse == null) {
            append("尚未解析描述符，先自动执行 ①…")
            runParse()
            if (lastParse == null) return
        }
        if (NativePlayer.nativeIsPlaying(nativeHandle)) {
            append("先停止当前播放。")
            stopTone()
        }
        if (fileSessionOpen) {
            append("先结束正在进行的文件播放。")
            PlayerSession.stopBlocking()
        }

        val rec = lastParse!!.recommendTone()
        if (rec == null) {
            append("✗ 描述符里找不到可用的 OUT 数据端点，无法验证。")
            return
        }

        val si = rec.streaming
        val ep = rec.endpoint
        val fmt = rec.format
        val acIf = lastParse!!.acInterface
        val clockId =
            if (si.uacVersion >= 2) lastParse!!.clocks.firstOrNull()?.clockId ?: 1 else 0

        // 验证要独占设备：时钟实体挂在 AudioControl 上，数据流挂在 AudioStreaming 上
        if (acIf >= 0 && claimedAcIface < 0) {
            val r = NativePlayer.nativeClaim(nativeHandle, acIf, -1)
            append(r)
            if (!r.contains("失败")) claimedAcIface = acIf
        }
        if (claimedIface != si.interfaceNumber) {
            val r = NativePlayer.nativeClaim(nativeHandle, si.interfaceNumber, -1)
            append(r)
            if (r.contains("失败")) {
                append("claim 失败，无法继续。若提示 BUSY，请先点「停止播放/释放接口」重来。")
                return
            }
            claimedIface = si.interfaceNumber
        }

        val rates = (STANDARD_RATES + PROBE_RATES).distinct().sorted().toIntArray()
        val settleMs = 400

        append("对 ${rates.size} 个速率逐个实际发流 ${settleMs}ms 再读设备回报，")
        append("全程约 ${rates.size * settleMs / 1000} 秒，期间会听到一串短促的 1kHz 蜂鸣（正常）。")
        append("")

        // 扫描期间不允许再点，避免和正在跑的发流抢设备
        val h = nativeHandle
        btnScanRatesLive.isEnabled = false
        setStatus("速率验证中…")

        // ★ 内部会阻塞 settleMs × 速率个数，必须在后台线程跑，否则 ANR
        Thread {
            val out = try {
                NativePlayer.nativeScanRatesLive(
                    h, acIf, clockId,
                    si.interfaceNumber, rec.alt.alt, ep.address,
                    rec.feedbackEndpoint?.address ?: 0,
                    fmt.channels, fmt.subframeSize, fmt.bitResolution,
                    ep.effectiveMaxPacket, urbCount, 8,
                    settleMs, rates
                )
            } catch (t: Throwable) {
                "✗ 验证过程异常：${t.message}"
            }
            runOnUiThread {
                append(out)
                btnScanRatesLive.isEnabled = true
                setStatus("速率真伪验证完成")
            }
        }.start()
    }

    // ------------------------------------------------------------------
    //  ⑨ DSD 速率实测
    // ------------------------------------------------------------------

    /**
     * 在 **raw DSD 通道**（alt 4）上实测设备真正接受的速率。
     *
     * ★ 为什么必须实测：
     *   这台设备的 FORMAT_TYPE_I 是 **6 字节截断**的，`bSamFreqType` 字段
     *   压根不存在 —— **它根本不告诉我们 DSD 该请求多少 Hz**。
     *   而 `SET_CUR` 又任何值都回「成功」（设备会撒谎），回读没用。
     *
     *   唯一说实话的地方是 **feedback 端点**，而它必须真的发流才会动。
     *
     * ★ 机制完全复用 ⑧：`nativeScanRatesLive` 不关心数据是什么含义，
     *   它只负责「设速率 → 发流 → 读 feedback」。这里只是换成 DSD 的 alt
     *   和 DSD 的候选速率。
     *
     * ⚠ 会出声，而且**是噪音** —— 送过去的是按 32bit PCM 生成的测试音，
     *   设备按 DSD 解。测之前先把音量调低。
     */
    private fun runDsdRateScan() {
        section("⑨ DSD 速率实测（挂 raw DSD 通道，读 feedback 定案）")

        if (nativeHandle == 0L) {
            append("✗ 原生句柄无效，无法进行。")
            return
        }
        if (lastParse == null) {
            append("尚未解析描述符，先自动执行 ①…")
            runParse()
            if (lastParse == null) return
        }
        if (NativePlayer.nativeIsPlaying(nativeHandle)) {
            append("先停止当前播放。")
            stopTone()
        }
        if (fileSessionOpen) {
            append("先结束正在进行的文件播放。")
            PlayerSession.stopBlocking()
        }

        val rec = lastParse!!.rawDsdAlt()
        if (rec == null) {
            append("✗ 这台设备没有 raw DSD 通道（AS_GENERAL.bmFormats 的 bit31 没置位）。")
            append("  那它的原生 DSD 只能靠 DoP，或者根本不支持 DSD。")
            return
        }

        val si = rec.streaming
        val ep = rec.endpoint
        val fmt = rec.format
        val acIf = lastParse!!.acInterface
        val clockId =
            if (si.uacVersion >= 2) lastParse!!.clocks.firstOrNull()?.clockId ?: 1 else 0

        append("找到 raw DSD 通道：接口 ${si.interfaceNumber} alt ${rec.alt.alt}")
        append("  格式：${fmt.channels}ch ${fmt.bitResolution}bit 子帧${fmt.subframeSize}B")
        append("  端点 0x%02X，每 microframe 有效容量 ${ep.effectiveMaxPacket}B"
            .format(ep.address))
        append("")

        /*
         * 候选分两组：
         *   前四个 = DSD64/128/256/512 的「位率 ÷ 32」—— 规范推断的那一组
         *   后三个 = 位率本身 —— 用来**证伪**「每个 32-bit 字装 1 位」那种解释。
         *            按那种算法 DSD256 要 22.6 MB/s，而本端点每 microframe 只有
         *            ${ep.effectiveMaxPacket}B（≈6.2 MB/s），带宽上根本装不下。
         */
        val candidates = intArrayOf(88200, 176400, 352800, 705600, 2822400, 5644800, 11289600)
        append("候选速率：${candidates.joinToString("  ")}")
        append("  88200/176400/352800/705600 = DSD64/128/256/512 的位率÷32")
        append("  2822400/5644800/11289600 = 位率本身（用于证伪，带宽上不可能）")
        append("")
        append("⚠ 接下来会听到刺耳噪音，先把音量调低。判定看每一项后面的 feedback 实测值。")
        append("")

        if (acIf >= 0 && claimedAcIface < 0) {
            val r = NativePlayer.nativeClaim(nativeHandle, acIf, -1)
            append(r)
            if (!r.contains("失败")) claimedAcIface = acIf
        }
        if (claimedIface != si.interfaceNumber) {
            val r = NativePlayer.nativeClaim(nativeHandle, si.interfaceNumber, -1)
            append(r)
            if (r.contains("失败")) {
                append("claim 失败，无法继续。先点「停止播放/释放接口」重来。")
                return
            }
            claimedIface = si.interfaceNumber
        }

        val h = nativeHandle
        btnScanDsd.isEnabled = false
        setStatus("DSD 速率实测中…")

        // 内部会阻塞 settleMs × 速率个数，必须在后台线程跑，否则 ANR
        Thread {
            val out = try {
                NativePlayer.nativeScanRatesLive(
                    h, acIf, clockId,
                    si.interfaceNumber, rec.alt.alt, ep.address,
                    rec.feedbackEndpoint?.address ?: 0,
                    fmt.channels, fmt.subframeSize, fmt.bitResolution,
                    ep.effectiveMaxPacket, urbCount, 8,
                    500, candidates
                )
            } catch (t: Throwable) {
                "✗ 实测过程异常：${t.message}"
            }
            runOnUiThread {
                append(out)
                btnScanDsd.isEnabled = true
                setStatus("DSD 速率实测完成")
            }
        }.start()
    }

    /**
     * 按设备能力填充采样率下拉框，并追加两个探针速率。
     *
     * 实测：48000/96000/192000/384000 出声正常，88200/176400/352800 出声异常。
     * 前者都是 4000 的倍数（每 microframe 字节数为整数），后者都不是 ——
     * 两种解释在标准采样率里完全重合，无法区分：
     *   假说 A：设备处理不了非整数包长
     *   假说 B：设备处理不了 44.1k 倍频时钟
     *
     * 探针速率用来拆开它们：
     *   50000 → 12.5 字节/µframe（非整数），但不属于 44.1k 家族
     *   40000 → 10   字节/µframe（整数），  既非 44.1k 也非 48k 家族
     */
    private fun refreshRateOptions(rec: ToneRecommendation) {
        val deviceRates = rec.format.allRates.distinct().sorted()
        rateOptions = deviceRates + PROBE_RATES.filter { it !in deviceRates }

        val labels = rateOptions.map {
            when {
                it in PROBE_RATES -> "$it Hz（探针）"
                it in COMMON_RATES -> "$it Hz（常用）"
                else -> "$it Hz"
            }
        }
        spinnerRate.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, labels
        ) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent) as TextView
                v.setTextColor(0xFFD6E1EC.toInt())
                v.textSize = 13f
                v.setPadding(16, 8, 16, 8)
                return v
            }

            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(pos, cv, parent) as TextView
                v.setTextColor(0xFFD6E1EC.toInt())
                v.setBackgroundColor(0xFF1B2430.toInt())
                v.textSize = 13f
                v.setPadding(16, 20, 16, 20)
                return v
            }
        }
        val idx = rateOptions.indexOf(rec.rate)
        if (idx >= 0) spinnerRate.setSelection(idx)
    }

    private fun startTone() {
        // 换采样率重测时不必手动先点停止，这里自动收尾上一次
        if (nativeHandle != 0L && NativePlayer.nativeIsPlaying(nativeHandle)) {
            append("检测到上一次仍在播放，先自动停止并释放接口。")
            stopTone()
        }
        // 文件播放和测试音走的是同一批接口，必须先收掉前者
        if (fileSessionOpen) {
            append("先结束正在进行的文件播放。")
            PlayerSession.stopBlocking()
        }

        section("⑤ 出声测试（1kHz 正弦，isochronous 直通）")

        if (nativeHandle == 0L) {
            append("✗ 原生句柄无效，无法进行。请先解决 ② 的问题。")
            return
        }
        if (lastParse == null) {
            append("尚未解析描述符，先自动执行 ①…")
            runParse()
            if (lastParse == null) return
        }

        val rec = lastParse!!.recommendTone()
        if (rec == null) {
            append("✗ 描述符里找不到可用的 OUT 数据端点，无法播放。")
            return
        }

        if (rateOptions.isEmpty()) refreshRateOptions(rec)
        val chosenRate = rateOptions.getOrNull(spinnerRate.selectedItemPosition) ?: rec.rate

        val si = rec.streaming
        val ep = rec.endpoint
        val fmt = rec.format
        val clockId = if (si.uacVersion >= 2) {
            lastParse!!.clocks.firstOrNull()?.clockId ?: 1
        } else 0

        append("选中参数：接口=${si.interfaceNumber} alt=${rec.alt.alt} " +
                "端点=0x%02X 采样率=${chosenRate}Hz ${fmt.channels}ch ${fmt.bitResolution}bit"
            .format(ep.address))

        // 预告包长形态。关键指标是「每包几个样本」——必须是整数。
        // 44100Hz 每 microframe 只有 5.5125 个样本，若按字节凑整会让包内
        // 出现半截样本，设备侧样本流错位，听感是持续失真。
        val frameBytes = fmt.channels * fmt.subframeSize
        val samplesPerUnit = chosenRate.toDouble() / 8000.0
        val sLo = Math.floor(samplesPerUnit).toInt()
        val sHi = Math.ceil(samplesPerUnit).toInt()
        append(
            "  每 microframe %.4f 个样本 → %s".format(
                samplesPerUnit,
                if (sLo == sHi) "恒定 $sLo 个样本，即 ${sLo * frameBytes} 字节"
                else "$sLo / $sHi 个样本交替，即 ${sLo * frameBytes} / " +
                        "${sHi * frameBytes} 字节"
            )
        )
        append("  每包均含整数个样本（设备正常工作的前提）")
        append("")

        // 1) 先 claim AudioControl 接口。
        //    UAC2 的采样率请求打在它持有的 Clock Source 实体上，实测不 claim
        //    这个接口的话 SET_CUR 会返回 LIBUSB_ERROR_IO。
        val acIf = lastParse!!.acInterface
        if (acIf >= 0 && acIf != si.interfaceNumber) {
            val r = NativePlayer.nativeClaim(nativeHandle, acIf, -1)
            append(r)
            if (!r.contains("失败")) claimedAcIface = acIf
        }

        // 2) claim AudioStreaming 接口，但**先不激活** alt setting。
        //    顺序是关键：时钟必须在接口空闲(alt 0)时切换。若先激活 alt 再改采样率，
        //    设备会因时钟切换复位内部缓冲，而已装载的 iso 端点不会自动重新装载，
        //    结果是 URB 零错误、包长完美，却完全没有声音。
        val claimResult = NativePlayer.nativeClaim(nativeHandle, si.interfaceNumber, -1)
        append(claimResult)
        if (claimResult.contains("失败")) {
            append("claim 失败，无法继续。若提示 BUSY，请先点「停止播放/释放接口」重来。")
            setStatus("claim 失败")
            return
        }
        claimedIface = si.interfaceNumber

        // 3) 在空闲态下发采样率（内部会依次尝试多种 wIndex 组合并报告哪种成功）
        append(
            NativePlayer.nativeSetSampleRate(
                nativeHandle, si.uacVersion, acIf, si.interfaceNumber, rec.alt.alt,
                clockId, ep.address, chosenRate
            )
        )

        // 4) 时钟就位后再激活 alt setting，让 iso 端点以正确的时基启动
        append(NativePlayer.nativeSetAltSetting(nativeHandle, si.interfaceNumber, rec.alt.alt))

        // 5) 启动 iso 数据流
        append(
            NativePlayer.nativeStartTone(
                nativeHandle, ep.address,
                rec.feedbackEndpoint?.address ?: 0,
                chosenRate, fmt.channels, fmt.subframeSize, fmt.bitResolution,
                0, ep.effectiveMaxPacket, 8, 8
            )
        )

        append("")
        append("请听耳机 / 看小尾巴的采样率指示灯。")
        setStatus("播放中…")

        mainHandler.removeCallbacks(statsTicker)
        mainHandler.postDelayed(statsTicker, 1000)
    }

    private fun stopTone() {
        section("停止播放 / 释放接口")
        if (nativeHandle == 0L) {
            append("原生句柄无效，无需停止。")
            return
        }
        mainHandler.removeCallbacks(statsTicker)

        if (NativePlayer.nativeIsPlaying(nativeHandle)) {
            append("── 最终统计 ──")
            append(NativePlayer.nativeStop(nativeHandle))
        } else {
            append("当前未在播放。")
        }

        if (claimedIface >= 0) {
            // 先降回空闲态，避免下次运行时残留上次的 alt 设置
            append(NativePlayer.nativeSetAltSetting(nativeHandle, claimedIface, 0))
            append(NativePlayer.nativeRelease(nativeHandle, claimedIface))
            claimedIface = -1
        }
        if (claimedAcIface >= 0) {
            append(NativePlayer.nativeRelease(nativeHandle, claimedAcIface))
            claimedAcIface = -1
        }
        setStatus("已停止并释放接口")
    }

    // ---------------------------------------------------------------------
    //  DAC 硬件音量
    //
    //  独占模式下这是唯一还能用的音量控制：
    //    · 系统音量够不着 —— 我们的采样点从不经过 AudioFlinger，
    //      实测按音量键对响度毫无影响
    //    · 软件增益会破坏 bit-perfect —— 那是拿有效位去换响度
    //  硬件音量则是让 DAC 收到满分辨率数据后自行衰减，音质最优。
    // ---------------------------------------------------------------------

    /**
     * 把值夹进 [lo, hi]。
     *
     * 不能用 coerceIn —— 它在 lo > hi（空区间）时会抛
     * IllegalArgumentException 直接闪退。而设备完全可能报出一组非法范围
     * （实测撞到过 min=1 / max=0），这种输入必须被容忍，不是崩溃的理由。
     */
    private fun clamp(v: Long, lo: Long, hi: Long): Long = when {
        v < lo -> lo
        v > hi -> hi
        else -> v
    }

    /** 把任意值吸附到设备自己的步进上，避免下发它不认的中间值 */
    private fun quantizeVolume(raw: Long): Int {
        if (!volumeReady) return raw.toInt()
        val clamped = clamp(raw, volMin, volMax)
        if (volRes <= 0) return clamped.toInt()
        val steps = Math.round((clamped - volMin).toDouble() / volRes.toDouble())
        return clamp(volMin + steps * volRes, volMin, volMax).toInt()
    }

    /**
     * 查询 Feature Unit 的音量范围。
     *
     * 必须在 ① 解析出 Feature Unit 的实体 ID 之后调用 —— 音量请求要打在它身上。
     * 三种 wIndex 构造都试过仍失败时，说明该设备的 Feature Unit 不接受控制
     * （音量可能做在模拟域，只能用它自己的按键），这时如实报告，不要假装可用。
     */
    private fun refreshVolume() {
        val h = nativeHandle
        val parsed = lastParse
        val fu = parsed?.featureUnits?.firstOrNull { it.hasMasterVolume }

        /*
         * 只在播放会话内启用。
         *
         * 查询音量要 claim 住 AudioControl 接口，而 ③ 的抢占测试会逐个接口去
         * claim —— 两边撞上会让 ③ 的报告出现一条误导性的「接口 0 抢占失败」。
         * 播放时 AC 本来就由原生层合法持有（nativeStartPlayback 会 claim），
         * 所以把音量限制在播放中，既不干扰诊断流程，也正好是真正需要它的时刻。
         */
        if (!fileSessionOpen) {
            volumeReady = false
            seekVolume.isEnabled = false
            tvVolumeInfo.text = when {
                h == 0L -> "音量: 需先打开设备"
                parsed == null -> "音量: 需先执行 ① 解析描述符"
                fu == null -> "音量: 该设备未提供可控的 Feature Unit（无 master 音量）"
                else -> "音量: 播放中可调（避免干扰 ③ 的接口抢占测试）"
            }
            return
        }

        if (h == 0L || parsed == null || fu == null) {
            volumeReady = false
            seekVolume.isEnabled = false
            tvVolumeInfo.text = "音量: 无可用设备或描述符"
            return
        }

        val r = NativePlayer.nativeVolumeRange(h, parsed.acInterface, fu.unitId)
        val ok = r.size >= NativePlayer.VolumeInfo.COUNT &&
                r[NativePlayer.VolumeInfo.OK] == 1L
        if (!ok) {
            volumeReady = false
            seekVolume.isEnabled = false
            tvVolumeInfo.text = "音量: 查询失败，该设备 Feature Unit 不接受音量请求"
            append("")
            append("硬件音量不可用：所有 wIndex × 通道组合都给不出一组自洽的音量范围。")
            append("合格条件是 max > min 且当前值落在范围内 —— 只看「GET_CUR 有响应」不够，")
            append("这台设备对不存在的实体也照答不误。")
            append("该设备的音量可能做在模拟域（只能用机身按键），或固件未实现 Feature Unit 控制。")
            append("此时独占模式下无法从手机侧调节响度。")
            // 把三个候选各自读到的原始字节打出来 —— 这是判断"到底能不能做"的唯一依据
            append("")
            append(NativePlayer.nativeVolumeProbe(h, parsed.acInterface, fu.unitId))
            return
        }

        val lo = r[NativePlayer.VolumeInfo.MIN]
        val hi = r[NativePlayer.VolumeInfo.MAX]
        val res = r[NativePlayer.VolumeInfo.RES]
        val cur = r[NativePlayer.VolumeInfo.CUR]
        val wIdx = r[NativePlayer.VolumeInfo.WIN_INDEX]
        val cn = r[NativePlayer.VolumeInfo.CHANNEL]

        /*
         * 原生层已经筛过一轮，这里再兜一次。
         *
         * 理由不是「不信任原生层」，而是这个区间一旦非法（max <= min），
         * 它流到 clamp / 滑条范围里就是闪退 —— 实测已经因为 min=1/max=0
         * 崩过一次。这种输入必须被容忍并如实报告，不能变成崩溃。
         */
        if (hi <= lo) {
            volumeReady = false
            seekVolume.isEnabled = false
            tvVolumeInfo.text = "音量: 设备返回的范围非法（min=$lo max=$hi），已禁用"
            append("")
            append("硬件音量已禁用：设备报出空区间 min=$lo max=$hi（wIndex=0x%04X）。".format(wIdx))
            append("继续使用会构造出非法区间并导致崩溃，因此不启用。")
            return
        }

        volMin = lo
        volMax = hi
        volRes = res
        volCur = clamp(cur, lo, hi)
        volLastSent = Int.MIN_VALUE
        volumeReady = true
        seekVolume.isEnabled = true
        seekVolume.max = (hi - lo).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        seekVolume.progress = clamp(volCur - lo, 0L, seekVolume.max.toLong()).toInt()
        renderVolumeInfo()

        // 原始值全部记进报告：万一还是不对，这些数字就是唯一的线索
        append("")
        append("硬件音量可用: Feature Unit ID=${fu.unitId}  wIndex=0x%04X  CN=%d%s"
            .format(wIdx, cn, if (cn == 0L) "（master）" else "（逐声道）"))
        append("  范围 %d ~ %d（%.2f ~ %.2f dB）".format(lo, hi, lo / 256.0, hi / 256.0))
        append("  步进 %d（%.2f dB）%s".format(
            res, res / 256.0, if (res <= 0) " ← 设备未上报，按 1/256 dB 兜底" else ""
        ))
        append("  当前 %d（%.2f dB）".format(cur, cur / 256.0))
    }

    private fun renderVolumeInfo() {
        tvVolumeInfo.text = "音量: %.2f dB    （范围 %.1f ~ %.1f dB，步进 %.2f dB）".format(
            volCur / 256.0, volMin / 256.0, volMax / 256.0, volRes / 256.0
        )
    }

    /** 下发音量。相同值会被滤掉；实际下发在后台线程。 */
    private fun applyVolume(value: Int) {
        val h = nativeHandle
        val parsed = lastParse
        val fu = parsed?.featureUnits?.firstOrNull { it.hasMasterVolume }
        if (h == 0L || parsed == null || fu == null || !volumeReady) return

        // 再夹一次：调用方可能来自滑条、音量键或设备回读，每条路径都不该
        // 有能力把越界值送进 USB 请求
        val safe = clamp(value.toLong(), volMin, volMax).toInt()
        if (safe == volLastSent) return

        volLastSent = safe
        volCur = safe.toLong()
        renderVolumeInfo()

        val target = safe
        volumeExecutor.execute {
            val r = NativePlayer.nativeSetVolume(h, parsed.acInterface, fu.unitId, target)
            val ok = r.size >= 2 && r[1] == 1L
            val actual = if (r.isNotEmpty()) r[0] else target.toLong()
            runOnUiThread {
                if (!ok) {
                    tvVolumeInfo.text = "音量: 下发失败（详见日志）"
                    return@runOnUiThread
                }
                // 设备可能把值吸附到了它自己的格点上，回读不一致时以设备为准
                if (actual != target.toLong()) {
                    volCur = actual
                    volLastSent = actual.toInt()
                    seekVolume.progress = (actual - volMin).toInt()
                }
                renderVolumeInfo()
            }
        }
    }

    /*
     * 音量键改控 DAC 硬件音量。
     *
     * 独占模式下系统音量对我们完全无效，音量键原本按下去什么都不会发生。
     * 这里把它接管过来转成 UAC 音量请求，并返回 true 吃掉事件 ——
     * 否则系统还会弹一个对这个 App 毫无作用的音量条。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeReady &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val step = if (volRes > 0) volRes else 64L   // 拿不到步进时按 0.25dB
            val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) step else -step
            val target = clamp(volCur + delta, volMin, volMax)
            seekVolume.progress = clamp(target - volMin, 0L, seekVolume.max.toLong()).toInt()
            applyVolume(target.toInt())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------------
    //  ⑦ 真实文件播放
    // ---------------------------------------------------------------------

    /**
     * 打开文件 → 决策输出参数 → 匹配设备 alt setting → 起播。
     *
     * 整个过程放在后台线程：openFileDescriptor 要过 ContentProvider，FFmpeg 要
     * 解文件头，起播前还要预灌约 1 秒音频（可能含 VHQ 重采样）。这些放在主线程
     * 足以让界面卡顿甚至 ANR，而它们又不涉及任何 UI 状态。
     */
    // ---------------------------------------------------------------------
    //  ⑦ 播放控制 —— 全部转发给 PlayerSession
    //
    //  ★ 这里**不再自己管播放**。队列、当前曲目、"播完自动下一首"都在
    //    PlayerSession 里，由 PlaybackService 的心跳驱动 —— 只有那样
    //    切后台、锁屏之后还能接着连播。界面只负责发请求和显示状态。
    // ---------------------------------------------------------------------

    private fun togglePause() = PlayerSession.togglePause()
    private fun stopFilePlayback() = PlayerSession.stop()
    private fun playNext() = PlayerSession.next()
    private fun playPrev() = PlayerSession.prevSmart()

    /**
     * 播放会话状态变了，重画界面。
     *
     * PlayerSession 保证在主线程回调（每 200ms 一次 + 状态变化时），
     * 所以这里可以直接碰控件。
     */
    private fun renderSessionState() {
        val h = nativeHandle
        val active = PlayerSession.isActive
        val paused = filePaused
        val track = PlayerSession.current
        val q = PlayerSession.queue

        btnPlayPause.isEnabled = active
        btnStopFile.isEnabled = active
        btnPrev.isEnabled = q.size > 0
        btnNext.isEnabled = q.size > 0
        btnPlayPause.text = if (paused) "继续" else "暂停"

        tvFileInfo.text = buildString {
            if (track == null) {
                append(
                    when (PlayerSession.state) {
                        PlayerSession.State.OPENING -> "正在打开…"
                        else -> "未在播放"
                    }
                )
                PlayerSession.lastError?.let { append("\n✗ ").append(it) }
                return@buildString
            }
            append(track.displayTitle).append("  —  ").append(track.displayArtist)
            append("\n").append(track.specLabelOrDefault)
            if (q.size > 1) {
                append("     队列 ").append(q.currentIndex + 1).append(" / ").append(q.size)
                if (q.shuffle) append("  随机")
                append(
                    when (q.repeatMode) {
                        RepeatMode.ONE -> "  单曲循环"
                        RepeatMode.ALL -> "  列表循环"
                        RepeatMode.OFF -> ""
                    }
                )
            }
        }

        if (!active || h == 0L) {
            tvPlayStats.text = ""
            seekBar.progress = 0
            seekBar.max = 0
            return
        }

        val info = NativePlayer.nativeEngineInfo(h)
        val posMs = info[NativePlayer.EngineInfo.POSITION_MS]
        val buffered = info[NativePlayer.EngineInfo.BUFFERED_BYTES]
        val underrun = info[NativePlayer.EngineInfo.UNDERRUN_BYTES]
        val target = info[NativePlayer.EngineInfo.TARGET_BYTES]
        val resampling = info[NativePlayer.EngineInfo.RESAMPLING] == 1L

        // 拖动进度条时不能被回写抢走手指
        if (!draggingSeek) {
            seekBar.max = fileDurationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            seekBar.progress = posMs.toInt()
        }

        /*
         * 真实缓冲水位 = 积压字节 ÷ 目标水位。
         *
         * 这里原来写的是 posMs/时长，算出来的是**播放进度**却被标成「缓冲水位」，
         * 用户照着这个数描述卡顿，等于在报"歌播到哪儿"，完全误导。
         */
        val fillPct = if (target > 0) buffered * 100 / target else 0L
        tvPlayStats.text = buildString {
            append("传输: ").append(if (resampling) "重采样" else "直通（bit-perfect）")
            append("\n缓冲: ").append(buffered).append(" / ").append(target)
            append(" 字节（").append(fillPct).append("%）")
            append("   欠载累计: ").append(underrun).append(" 字节")
            // 水位见底前先给个警示 —— 卡顿就发生在这一段
            if (fillPct < 25) append("   ⚠ 水位偏低")
            if (paused) append("   [已暂停]")
        }

        tvStatus.text = buildString {
            append(if (paused) "已暂停" else "播放中")
            append("  ").append(fmtTime(posMs))
            if (fileDurationMs > 0) append(" / ").append(fmtTime(fileDurationMs))
            append("\n输出 ").append(info[NativePlayer.EngineInfo.OUTPUT_RATE])
            append("Hz / ").append(info[NativePlayer.EngineInfo.BIT_RESOLUTION]).append("bit")
            append("   欠载 ").append(underrun).append(" 字节")
            append("   ").append(PlayerSession.wakeLockState())
        }
    }

    // ---------------------------------------------------------------------
    //  播放会话的事件回调（主线程）
    // ---------------------------------------------------------------------
    private val sessionListener = object : PlayerSession.Listener {
        override fun onSessionLog(text: String) = append(text)
        override fun onSessionStateChanged() = renderSessionState()
    }


    private fun appVersionLine(): String = try {
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        @Suppress("DEPRECATION")
        val code = pi.versionCode
        val installed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(pi.lastUpdateTime))
        "yuHIFI ${pi.versionName} (versionCode $code)   安装于 $installed"
    } catch (e: Exception) {
        "yuHIFI 版本信息读取失败: ${e.message}"
    }

    /**
     * 当前进程被允许使用的 CPU，以及所处的 cpuset / cpu 分组。
     *
     * 为什么值得记：实测这台机器对 `background` 分组**只留 CPU 0-2**（共 8 核），
     * 而应用一旦离开前台就可能被挪进这个组。DAC 内部 FIFO 只有约 1.5 ms 深
     * （由卡顿间隔反推），USB 提交线程在少数慢核上被延迟几毫秒就足以让设备断粮。
     *
     * 而这一切**从欠载统计上完全看不出来** —— 缓冲有 1 秒深，解码侧被延迟
     * 几百毫秒也不欠载；USB 线程被延迟时 `fill()` 根本不会被调用，
     * 所以既不计数也不报错。只能直接看进程被允许跑在哪几个核上。
     *
     * 读 /proc/self 不需要任何权限，也不需要 adb。
     */
    private fun cpuAffinity(): String {
        val cpuset = runCatching {
            File("/proc/self/cgroup").readLines()
                .firstOrNull { it.contains("cpuset") }
                ?.substringAfterLast(':')          // 形如 3:cpuset:/background
        }.getOrNull() ?: "?"

        val cpus = runCatching {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Cpus_allowed_list:") }
                ?.substringAfter(':')?.trim()
        }.getOrNull() ?: "?"

        return "cpuset=$cpuset  cpus=$cpus"
    }

    // ---------------------------------------------------------------------
    //  播放期间的 wakelock
    // ---------------------------------------------------------------------

    /*
     * 播放期间持有的 PARTIAL_WAKE_LOCK。
     *
     * Android 的音频栈在放音时会自动替应用持有它 —— 这就是普通音乐 App 息屏后
     * 还能继续放的原因，开发者从来不用自己管。而我们绕开了 AudioFlinger，
     * **没有任何人会替我们拿**。
     *
     * 前台服务挡不住 CPU 挂起：前台服务解决的是"进程被冻结"，挂起是另一个机制。
     * 实测用户报告"息屏后卡顿更多"，而 `dumpsys power` 显示我们从未持有过 wakelock。
     */
    /*
     * wakelock 本身**搬进了 PlayerSession** —— 它必须和播放会话同生共死。
     * 留在界面里的话，界面一销毁锁就跟着没了（或者更糟：漏了释放），
     * 而播放还在继续。
     *
     * 这里只保留一个只读查询供报告核对：CPU 会不会被挂起，取决于这个。
     */
    private fun wakeLockState(): String = PlayerSession.wakeLockState()

    /**
     * 各 cpuset 分组分别允许用哪些核。
     *
     * 一次性记录，让报告能自解释 —— 否则只看到 `cpuset=/background` 根本不知道
     * 那意味着几个核、是不是大小核。这台机器实测：top-app 能用全部 8 核，
     * 而 background **只有 CPU 0-2**。
     */
    private fun cpusetGroups(): String = runCatching {
        listOf("top-app", "foreground", "background", "system-background")
            .mapNotNull { g ->
                runCatching { File("/dev/cpuset/$g/cpus").readText().trim() }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { "$g=$it" }
            }
            .joinToString("  ")
            .ifEmpty { "读取不到（权限或路径不同）" }
    }.getOrDefault("读取失败")

    /**
     * 记录切后台/回前台，并带上当时的播放位置。
     *
     * 位置这个数就是对齐用的：欠载事件也是按「第几秒」报的，两边一比就能
     * 看出卡顿到底发生在**离开期间**（进程被冻结）还是**回来那一刻**
     * （Activity 恢复的开销压住了音频线程）。这两种机制要查的方向完全不同。
     */
    override fun onPause() {
        super.onPause()
        /*
         * ★ 注销会话监听。PlayerSession 是进程内的单例，比 Activity 活得久，
         *   不注销就会一直持有这个 Activity —— 转屏或反复进出就是一次泄漏。
         *
         * 注销**不影响播放**：推进队列的是服务，界面只是一块显示屏。
         */
        PlayerSession.removeListener(sessionListener)
        if (fileSessionOpen) {
            append("⏸ 离开应用   播放位置 ${fmtTime(currentPositionMs())}   ${cpuAffinity()}  ${wakeLockState()}")
        }
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.addListener(sessionListener)
        // 回到前台先按当前状态重画一次，否则界面上是离开时的旧样子
        renderSessionState()
        if (fileSessionOpen) {
            append("▶ 回到前台   播放位置 ${fmtTime(currentPositionMs())}   ${cpuAffinity()}  ${wakeLockState()}")
        }
    }

    /** 当前播放位置（毫秒）；未播放或句柄无效时返回 0 */
    private fun currentPositionMs(): Long {
        val h = nativeHandle
        if (h == 0L) return 0
        return runCatching {
            NativePlayer.nativeEngineInfo(h)[NativePlayer.EngineInfo.POSITION_MS]
        }.getOrDefault(0L)
    }

    /** 毫秒 → m:ss，用于进度显示 */
    private fun fmtTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ---------------------------------------------------------------------
    //  导出
    // ---------------------------------------------------------------------
    /**
     * 转储文件路径。
     *
     * 用**固定文件名**而不是加时间戳：路径要在起播前就交给原生层，
     * 而那时还不知道报告会叫什么；固定的名字好记也好 pull，每次覆盖上一次。
     * 报告开头会写明这次到底写没写、写了多少字节。
     */
    private fun dumpFilePath(): String =
        File(getExternalFilesDir(null) ?: filesDir, "hifiprobe_dump.raw").absolutePath

    private fun exportReport() {
        if (log.isEmpty()) {
            toast("报告为空")
            return
        }
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "hifiprobe_$ts.txt")
            f.writeText(
                buildString {
                    appendLine("HiFi USB 探针报告")
                    appendLine(appVersionLine())
                    appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine()
                    append(log)
                }
            )
            append("")
            append("报告已导出: ${f.absolutePath}")
            toast("已导出到 ${f.absolutePath}")
            setStatus("报告已导出")
        } catch (e: Exception) {
            append("导出失败: ${e.message}")
            toast("导出失败: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    //  清理
    // ---------------------------------------------------------------------
    private fun closeEverything() {
        mainHandler.removeCallbacks(statsTicker)
        /*
         * 播放会话要显式收掉：队里可能还有歌，服务还在跑心跳。
         * 顺序不能反 —— 必须先把播放停干净、丢掉队列，再 nativeClose，
         * 否则服务的心跳会在句柄失效后去读引擎信息。
         */
        /*
         * ★ 必须用 stopBlocking 而不是 stop。
         *
         * stop() 只是把停止动作**排进 worker 队列就返回**，而紧接着下面
         * 就要 nativeClose —— 那会立刻销毁原生 ctx。于是 worker 线程还在
         * IsoPlayer::stop() 里 join，UI 线程已经在拆对象了。
         *
         * 实测就是这样崩的：
         *     std::__ndk1::thread::join(): Invalid argument → abort()
         * 原生层现已加锁兜底，但调用顺序本身也得对 —— 锁只能防崩，
         * 防不了"对象在另一条线程手上被拆掉"这种逻辑错误。
         */
        PlayerSession.stopBlocking()
        PlayerSession.queue.clear()
        volumeReady = false
        seekVolume.isEnabled = false
        if (nativeHandle != 0L) {
            runCatching { NativePlayer.nativeClose(nativeHandle) }
            PlayerSession.clearDevice()
        }
        // 必须等原生层完全释放后才能关连接，否则 fd 提前失效会导致原生层崩溃
        runCatching { connection?.close() }
        connection = null
        claimedIface = -1
        claimedAcIface = -1
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.hifiprobe.USB_PERMISSION"

        /** 音乐文件里真正常见的采样率；其余（88200/176400/352800）属少见 */
        private val COMMON_RATES = setOf(44100, 48000, 96000, 192000, 384000)

        /**
         * 诊断用探针速率。
         *
         * 实测规律：能正常出声的 40000/48000/96000/192000/384000 全是 8000 的
         * 整数倍（每 microframe 样本数恒定）；异常的 44100/50000/88200/176400/
         * 352800 全都不是（每 microframe 样本数在交替）。
         *
         * 这四个探针用来验证「必须是 8000 的整数倍」这条规律：
         *   40000 → 是 8000 倍数，预期正常（已知）
         *   56000 → 是 8000 倍数，但极其冷门 → 若正常，说明规律成立
         *   44000 → 不是 8000 倍数，但与 44100 仅差 100Hz → 若异常，说明规律成立
         *   50000 → 不是 8000 倍数（已知异常）
         */
        private val PROBE_RATES = listOf(40000, 44000, 50000, 56000)
    }
}
