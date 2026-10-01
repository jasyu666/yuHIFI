package com.hifiprobe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * USB 解码器的**接入与生命周期**。
 *
 * 设备状态只有一份，放在 [PlayerSession] 里；这里只负责"把它开起来、
 * 收干净、以及盯着它还在不在"。
 *
 * ★★ 这里原来是**拉模型** —— 只有音乐库页 onResume 会去看一眼设备。
 *    三个后果，全是真的：
 *      · 从首页走「所有歌曲」点歌，handle 恒为 0，播放直接失败**且无人重试**
 *        （用户看到的就是"点了没反应"）
 *      · 插上解码器没有任何反应 —— manifest 里那句"插上就能用"是假的，
 *        因为拉起 App 的那个 intent 根本没人读
 *      · 拔掉解码器 handle 还留着旧值，下一首歌带着**死句柄**进原生层
 *
 *    现在改推模型：[install] 注册插拔广播，进程起来就接一次，
 *    播放前还会兜底（见 [PlayerSession.playLocked]）。
 *
 * 权限流程：USB 权限走系统弹窗（`UsbManager.requestPermission`），
 * **不属于 Android 危险权限体系**，不需要任何存储/媒体权限。
 */
object DeviceGate {

    private const val TAG = "HiFiDeviceGate"
    const val ACTION_USB_PERMISSION = "com.hifiprobe.USB_PERMISSION"

    private var appCtx: Context? = null
    private var plugReceiver: BroadcastReceiver? = null
    private var permReceiver: BroadcastReceiver? = null

    /**
     * 设备是不是**真的还活着**。
     *
     * ★ 和 [PlayerSession.handle] 是两件事，不要合并成一个变量。
     *   `handle != 0` 只说明"我们成功打开过"，拔掉之后它不会自己归零。
     *   这个区分有前车之鉴：**这台设备会撒谎**（`SET_CUR` 对任意速率都
     *   回读"成功"），所以"打开过"和"现在还在"从来就不是一回事。
     */
    @Volatile
    var deviceAlive: Boolean = false
        private set

    /** 最近一次失败原因。提示直接用它，不要另编文案 */
    @Volatile
    var lastMessage: String? = null
        private set

    /**
     * **能不能开播**
     *
     * ★★ 从 p60 起它不再是"设备可用吗"，因为多了一条不需要设备的输出路。
     *
     *   · **系统音频模式（默认）**：只要有原生引擎（handle）就够 ——
     *     这条路走 AAudio，**压根不碰 USB 设备**，不插小尾巴照样能放。
     *   · **USB 直连模式**：引擎 + 设备都得活着。
     *
     * ★ 播放前一律看这个，不要只看 handle 是不是 0，也不要只看 deviceAlive。
     */
    val isReady: Boolean
        get() {
            val ctx = appCtx ?: return false
            if (PlayerSession.handle == 0L) return false
            return if (Settings.usbDirect(ctx)) deviceAlive else true
        }

    /** 当前选的输出方式是不是"USB 直连（bit-perfect）" */
    private fun directMode(ctx: Context): Boolean = Settings.usbDirect(ctx)

    /**
     * 连接状态。界面照着它渲染。
     *
     * ★ 不要自己去拼 `isReady` / `lastMessage` / "是不是正在开" 这几个条件 ——
     *   组合起来有七八种，拼错一种就会显示成"看着没连上，但点了没反应"。
     */
    enum class LinkState {
        /** 没接上，也没在试 */
        IDLE,
        /** 正在打开，或正在等系统的 USB 授权弹窗 */
        CONNECTING,
        /** 已接管 */
        READY
    }

    val linkState: LinkState
        get() = when {
            isReady -> LinkState.READY
            busy || awaitingPermission -> LinkState.CONNECTING
            else -> LinkState.IDLE
        }

    /** 已接管的设备名（`MOONDROP Dawn Pro` 这种）。未连接时是 null */
    @Volatile
    var connectedName: String? = null
        private set

    /**
     * 连接状态变了。首页那条状态条注册它来实时刷新 ——
     * 不这样的话，插上解码器后按钮还停在"未连接"上，得退出去再进来。
     */
    interface Listener {
        fun onDeviceStateChanged()

        /**
         * **刚插上**一个 USB 音频设备。
         *
         * ★ 和 [onDeviceStateChanged] 分开是有意的：那个是"状态变了，重画"，
         *   会反复触发；这个是**一次性事件**，界面据此弹一次
         *   「要不要走直连」。混在一起的话每次重画都会弹一次。
         */
        fun onAudioDeviceAttached(name: String) {}
    }

    private val listeners = mutableListOf<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: Listener) {
        synchronized(listeners) { if (l !in listeners) listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    /**
     * 通知界面。
     *
     * ★ **必须回主线程**：插拔广播和 USB 授权回调都不在主线程上跑，
     *   直接回调等于让界面在非 UI 线程上碰 View。
     */
    private fun notifyState() {
        main.post {
            synchronized(listeners) { listeners.toList() }.forEach { it.onDeviceStateChanged() }
        }
    }

    /** 一次性事件：刚插上音频设备。见 [Listener.onAudioDeviceAttached] */
    private fun notifyAttached(name: String) {
        main.post {
            synchronized(listeners) { listeners.toList() }
                .forEach { it.onAudioDeviceAttached(name) }
        }
    }

    /*
     * 一次打开流程的状态机。
     *
     * ★ 用 [busy] 串行化是**正确性**要求，不是性能优化：并发的两次
     *   openDevice 会得到两个 UsbDeviceConnection，而 libusb 包裹的是
     *   同一个 fd —— 一个 close 掉，另一个立刻 EBADF，原生层直接崩。
     *
     * ★ 用 [waiting] 而不是单个回调：插拔广播、App 启动、点歌兜底、首页按钮
     *   四条路可能同时想要设备，它们要的是同一个结果，攒一起一起答复。
     */
    private var busy = false

    /** 系统的 USB 授权弹窗正开着。这期间 [busy] 保持 true，流程是"挂起"不是"结束" */
    private var awaitingPermission = false

    private val waiting = mutableListOf<(Boolean, String) -> Unit>()

    /**
     * 挂起超时：等系统授权等太久就**强制收尾**。
     *
     * ★★ 状态机只在「等 USB 授权」这一处会挂起（`drive` 里 requestPermission 之后
     *    刻意不 finish，等广播回来再走）。**广播要是没回来 —— 弹窗被关掉、
     *    被系统回收、用户切走了 —— [busy] 就永远是 true**：
     *
     *      · 首页永远是「正在连接…」，那一态还没有按钮
     *      · 之后任何 [ensureOpen] 看 `busy == true` 就只把回调排进 [waiting]，
     *        **永远不答复** —— 点了跟没点一样
     *      · **拔插也救不回来**（[onDetached] 不动 busy），只能重启 App
     *
     *    ★★ 2026-10-01 实测栽过：设备重新枚举（`002/002` → `002/003`）后是新设备、
     *       USB 权限没续上 → 弹授权框 → 广播没回来 → 卡死。
     *
     * ★ 20 秒：比人手点一下弹窗慢得多，但比"以为它还在转"短得多。
     */
    private val suspendTimeout = Runnable {
        if (busy) {
            Log.w(TAG, "连接流程挂起超过 ${SUSPEND_TIMEOUT_MS}ms，强制收尾")
            finish(false, "USB 授权超时，请重试")
        }
    }

    private const val SUSPEND_TIMEOUT_MS = 20_000L

    fun usbManager(ctx: Context): UsbManager =
        ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * 找音频设备。
     *
     * 判据是**接口的 class**（0x01 = Audio），不是 VID/PID ——
     * 这样换任何一只小尾巴都能直接认出来，不必维护白名单。
     * 顺带也排除了 U 盘、键鼠这类插在同一个 hub 上的东西。
     */
    fun findAudioDevice(ctx: Context): UsbDevice? =
        usbManager(ctx).deviceList.values.firstOrNull { isAudio(it) }

    /**
     * 当前插着的 USB 解码器叫什么（没插返回 null）。
     *
     * ★ 系统音频模式下我们**不打开**设备，所以 `connectedName` 是空的 ——
     *   但"插没插小尾巴"这件事照样能判断，走的是 UsbManager 的设备列表，
     *   **不需要打开它**。首页那条状态条就是靠这个报出名字的。
     */
    fun presentAudioDeviceName(ctx: Context): String? {
        val d = findAudioDevice(ctx.applicationContext) ?: return null
        return d.productName ?: d.deviceName
    }

    private fun isAudio(d: UsbDevice): Boolean =
        (0 until d.interfaceCount).any {
            d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
        }

    // ------------------------------------------------------------------
    //  进程级安装
    // ------------------------------------------------------------------

    /**
     * 注册插拔监听，并立刻试连一次。由 [App.onCreate] 调用，**整进程只调一次**。
     *
     * ★ 用 applicationContext 注册，不用 Activity：插拔是进程级的事。
     *   挂在 Activity 上的话它一销毁接收器就没了 —— 而用户完全可能
     *   在别的页面、甚至 App 在后台的时候插上解码器。
     */
    fun install(ctx: Context) {
        if (appCtx != null) return
        val app = ctx.applicationContext
        appCtx = app

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Log.i(TAG, "USB 插入: ${dev?.deviceName ?: "?"}")
                        // 插的是别的东西（U 盘、键盘）就别去动它
                        if (dev != null && isAudio(dev)) {
                            // 一次性事件：界面据此弹「要不要走直连」
                            notifyAttached(dev.productName ?: dev.deviceName)
                        }
                        if (dev == null || isAudio(dev)) {
                            ensureOpen(ctx) { ok, msg -> Log.i(TAG, "插入后接管: $ok · $msg") }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Log.w(TAG, "USB 拔出: ${dev?.deviceName ?: "?"}")
                        onDetached()
                    }
                }
            }
        }
        plugReceiver = r
        register(app, r, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })

        // 启动就接一次 —— 这样从任何一个入口点歌，都不会撞上"设备还没开"
        ensureOpen(app) { ok, msg -> Log.i(TAG, "启动接入: $ok · $msg") }
    }

    private fun onDetached() {
        val app = appCtx ?: return
        // 还有别的音频设备在（同时插两只小尾巴），不算断开
        if (findAudioDevice(app) != null) return

        /*
         * ★ 系统音频模式下，播放**根本不依赖小尾巴** —— 拔了不该被打断。
         *   只有直连模式、或者拔出前确实在用 USB，才需要收尾。
         */
        if (!directMode(app) && !deviceAlive) {
            Log.i(TAG, "解码器已拔出，但当前是系统音频模式 —— 播放不受影响")
            notifyState()
            return
        }

        /*
         * ★ 先把"断了"告诉用户 —— 收尾在后台跑（里面有一次 518ms 的设备复位），
         *   不让提示陪着它一起等。界面立刻就对了。
         */
        // 不要静默变 IDLE —— 用户得知道刚才为什么停了
        PlayerSession.notifyDeviceDetached()

        shutdown {
            Log.w(TAG, "解码器已断开，播放会话已收尾")
            /*
             * 系统音频模式下引擎（解码器 + 环形缓冲）是必需品，而且和小尾巴无关 ——
             * 收尾时把它一起拆了，得立刻补一个回来，否则下次点歌会撞上"没有上下文"。
             *
             * ★ 必须放在**回调里**：收尾是异步的，紧接着写在外面的话
             *   会在设备还没关干净时就重建引擎。
             */
            if (!directMode(app)) {
                ensureOpen(app) { ok, msg -> Log.i(TAG, "拔出后重建引擎: $ok · $msg") }
            }
        }
    }

    // ------------------------------------------------------------------
    //  打开
    // ------------------------------------------------------------------

    /**
     * 确保设备已打开并解析好描述符。
     *
     * 三种情况：
     *   · 已经开着且还活着 → 直接成功
     *   · 有权限但没开 → 打开并解析
     *   · 没权限 → 弹系统授权框，用户同意后自动继续
     *
     * ★ 回调**恰好被调用一次**，但**时机不定**，也**不保证在主线程**
     *   （插拔广播那条路不是主线程），调用方自己 post。
     *
     *   需要授权时它不会先答复"等待授权中"再答复结果 —— 而是**一直挂着**，
     *   等授权结果回来才答复一次。这样调用方只需要处理"成了/没成"两种结果，
     *   不必额外区分一个中间态。
     */
    fun ensureOpen(ctx: Context, onResult: (Boolean, String) -> Unit) {
        if (isReady) {
            onResult(true, "设备已就绪")
            return
        }
        waiting.add(onResult)
        if (busy) return
        busy = true
        /*
         * ★★ [drive] 里**任何一处抛异常**，[busy] 都会永远是 true ——
         *    症状和"等授权卡死"一模一样：状态条停在「正在连接…」、
         *    之后所有 ensureOpen 只排队不答复、拔插也救不回来。
         *
         *   ★ 所以这里必须兜住，而且**要留痕**：
         *     `Log.e` 是给排查用的，`finish(false, …)` 是给用户看的 ——
         *     静默吞掉的异常最后表现成"点了没反应"，那是最贵的一种 bug
         *     （FACT 里 runCatching 那条）。
         *
         *   ★ `if (busy)` 是防重复收尾：drive 正常跑完时 [finish] 已经调过了。
         */
        try {
            drive(ctx.applicationContext)
        } catch (e: Throwable) {
            Log.e(TAG, "打开流程异常: $e")
            if (busy) finish(false, "打开设备时出错: ${e.javaClass.simpleName}")
        }
    }

    /** 推进一次打开流程。结束时会清空 [waiting] 并逐个答复 */
    private fun drive(app: Context) {
        /*
         * ---- 系统音频模式：**不碰 USB 设备** ----
         *
         * 这条路走 AAudio，只要有原生引擎（解码器 + 环形缓冲）就能放，
         * 不需要小尾巴。
         *
         * ★ 刻意**不去 openDevice**：一边攥着 fd、一边让系统音频用同一个设备，
         *   是没必要冒的风险。检测小尾巴只靠 UsbManager 的设备列表和插拔广播，
         *   **不需要打开它** —— 所以"插上小尾巴弹提示"那条照样能用。
         */
        if (!directMode(app)) {
            if (PlayerSession.handle != 0L) {
                finish(true, "系统音频模式已就绪")
                return
            }
            val h = NativePlayer.nativeOpen(-1)
            if (h == 0L) {
                finish(false, "无法创建原生上下文：${NativePlayer.nativeLastError()}")
                return
            }
            PlayerSession.attachEngineOnly(h)
            connectedName = null
            Log.i(TAG, "系统音频模式：已建无设备上下文（不占用 USB）")
            finish(true, "系统音频模式（非 bit-perfect）")
            return
        }

        // 有句柄但设备已经不可用 —— 多半是漏掉了拔出广播。
        // 先收干净，否则下面 openDevice 会拿到第二个 connection 撞同一个 fd
        if (PlayerSession.handle != 0L) {
            Log.w(TAG, "句柄仍在但设备已失效，先收尾")
            // ★ 这里**必须同步**：下面紧接着就要 openDevice，
            //   旧的 connection 没关掉就会撞同一个 fd。
            //   设备已经掉线了，那次 USB 复位是立即失败的，不会真有那 518ms。
            teardownBlocking_()
        }

        val dev = findAudioDevice(app)
        if (dev == null) {
            finish(false, "直连模式需要 USB 解码器，但未检测到设备。")
            return
        }

        val usb = usbManager(app)
        if (!usb.hasPermission(dev)) {
            attachPermissionReceiver(app)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                app, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
                flags or PendingIntent.FLAG_UPDATE_CURRENT
            )
            /*
             * 这里**不** finish —— 等授权结果回来再走下一步。
             *
             * ★ [busy] 保持 true。放掉的话这期间再来一次 ensureOpen
             *   会重新 requestPermission，弹出**第二个**授权框。
             *   新的等待者只要 join [waiting] 就行，反正结果是一样的。
             */
            awaitingPermission = true
            // ★ 挂起就要有"闹钟" —— 广播不回来时靠它把状态机救回来（见 suspendTimeout）
            main.postDelayed(suspendTimeout, SUSPEND_TIMEOUT_MS)
            notifyState()
            usb.requestPermission(dev, pi)
            return
        }

        openNow(app, dev)
    }

    private fun openNow(ctx: Context, dev: UsbDevice) {
        var ok = false
        var msg = "未知错误"

        try {
            val conn = usbManager(ctx).openDevice(dev)
            if (conn == null) {
                msg = "openDevice 失败（权限被撤销？）"
            } else {
                val h = NativePlayer.nativeOpen(conn.fileDescriptor)
                if (h == 0L) {
                    runCatching { conn.close() }
                    msg = "libusb 接管失败：${NativePlayer.nativeLastError()}"
                } else {
                    // 描述符解析（纯 Java，不依赖原生库）—— 播放时要靠它挑 alt setting
                    val parsed = runCatching {
                        val raw = conn.rawDescriptors
                        if (raw == null || raw.isEmpty()) null else UacParser(raw).parse()
                    }.getOrNull()

                    if (parsed == null) {
                        runCatching { NativePlayer.nativeClose(h) }
                        runCatching { conn.close() }
                        msg = "描述符解析失败"
                    } else {
                        PlayerSession.connection = conn
                        PlayerSession.setDevice(h, parsed)
                        // 把界面上的实验开关同步过去（原生默认值与之一致，这里只是对齐）
                        /*
                         * ★ 把**设备自己声明的速率**交给引擎 —— 必须在 openFile 之前。
                         *
                         *   设备报了速率表就以它为准（换任何设备自动适配）；
                         *   没报（Dawn Pro 的描述符是截断的）就回落到白名单，
                         *   行为与以前完全一致。
                         */
                        val dr = parsed.declaredRates()
                        NativePlayer.nativeSetDeviceRates(h, dr.discrete, dr.contMin, dr.contMax)

                        NativePlayer.nativeSetAllowAllStandardRates(Settings.allowAllRates(ctx))
                        NativePlayer.nativeSetUrbCount(h, Settings.urbCount(ctx))
                        NativePlayer.nativeSetFollowFeedback(h, Settings.followFeedback(ctx))
                        NativePlayer.nativeSetFlushQueueOnSeek(h, Settings.flushQueueOnSeek(ctx))
                        deviceAlive = true
                        connectedName = dev.productName ?: dev.deviceName
                        ok = true
                        msg = "已接管 $connectedName"
                        Log.i(TAG, "设备已就绪: ${dev.deviceName}")
                    }
                }
            }
        } catch (e: Exception) {
            msg = "${e.javaClass.simpleName}: ${e.message}"
        }

        finish(ok, msg)
    }

    /** 一轮流程收尾：答复所有等待者 */
    private fun finish(ok: Boolean, msg: String) {
        // ★ 流程结束了，闹钟必须撤掉 —— 留着的话它会在 20 秒后打断**下一轮**流程
        main.removeCallbacks(suspendTimeout)
        lastMessage = if (ok) null else msg
        busy = false
        awaitingPermission = false
        val cbs = waiting.toList()
        waiting.clear()
        notifyState()
        cbs.forEach { it(ok, msg) }
    }

    private fun attachPermissionReceiver(ctx: Context) {
        if (permReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                // 不是我们在等的那一次（比如上一次请求的迟到回复），忽略
                if (!awaitingPermission) return

                val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                awaitingPermission = false

                if (granted && dev != null) {
                    // 被挂起的那轮流程接着走 —— busy 一直是 true，直接 drive
                    drive(c.applicationContext)
                } else {
                    /*
                     * ★★ 权限被拒绝 → **直接改用系统音频**，并且让这次请求成功。
                     *
                     *   系统音频那条路**根本不需要小尾巴**（走 AAudio，不碰 USB），
                     *   所以"拿不到 USB 权限"不该是死路 —— 用户点的那首歌
                     *   应该照放，只是走外放/蓝牙而已。
                     *
                     *   ★★ 刻意**不**在这里 `finish(false)`：
                     *     [waiting] 里排着的是"想开设备去把歌放起来"的请求，
                     *     我们要把它**办成**，不是把它拒掉。改了模式再走一趟
                     *     drive，由那边 `finish(true)` 一次答复所有人。
                     *     先拒一次的话，用户会收到一句"权限被拒绝"的提示、
                     *     歌还不放 —— 那正是"点了没反应"。
                     *
                     *   ★ 顺带解掉一个死循环：停在"直连模式但拿不到权限"上，
                     *     上层每次点歌都会再来问一遍，弹窗一次接一次。
                     *     转成系统音频之后这条路就通了，不会有人再问。
                     */
                    Log.w(TAG, "USB 权限被拒绝 —— 直接改用系统音频")
                    Settings.setUsbDirect(ctx, false)
                    // 把状态机从"正等着授权"里放出来，下面那趟 ensureOpen 才走得动
                    // ★ 闹钟也要撤 —— 这条路上没有 finish()，不撤的话它会在 20 秒后
                    //   打断下面那趟正在跑的流程（那时 busy 又是 true 了）
                    main.removeCallbacks(suspendTimeout)
                    busy = false
                    awaitingPermission = false
                    notifyState()

                    // 收尾现在是异步的（里面有设备复位），所以串起来：
                    // 等旧上下文关干净，再按新模式建一个
                    main.post {
                        shutdown {
                            ensureOpen(ctx) { ok, msg ->
                                Log.i(TAG, "权限被拒后转系统音频: $ok · $msg")
                            }
                        }
                    }
                }
            }
        }
        permReceiver = r
        register(ctx, r, IntentFilter(ACTION_USB_PERMISSION))
    }

    /**
     * 注册广播接收器。
     *
     * Android 14 起动态注册必须声明export行为。这里一律 NOT_EXPORTED：
     * USB 插拔是**受保护广播**（第三方应用根本发不出来），权限广播走的是
     * 我们自己创建的 PendingIntent，两条路都不需要对外暴露。
     */
    private fun register(ctx: Context, r: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, filter)
        }
    }

    // ------------------------------------------------------------------
    //  收尾
    // ------------------------------------------------------------------

    /**
     * 完全释放：先停播放、再关句柄、最后关连接。**顺序不能反**。
     *
     * ★ 停播放这一步必须是**同步**的：`PlayerSession.stop()` 只是排队，
     *   而下面马上就要 nativeClose 销毁原生对象 —— 异步的话 worker 线程
     *   会对着正在被拆的对象操作。实测崩溃栈就是这么来的。
     *
     * ★ 关连接**必须**在 nativeClose 之后：libusb 包裹的就是这个 fd，
     *   提前关闭会让原生层后续 ioctl 全部 EBADF。
     */
    /**
     * 收尾用的线程。
     *
     * ★★ 为什么收尾不能留在调用线程上：`nativeClose` 里有一次 **USB 设备复位**
     *    （交还设备前清状态，见 native_bridge.cpp），实测**耗时 518ms**。
     *    而调用它的两条路 —— 设置页的输出方式开关、USB 插拔广播 ——
     *    **都在主线程上**：界面会卡半秒，还有 ANR 风险。
     *    （那之前还有一句 `stopBlocking`，最多能等 3 秒，更不该压在主线程上。）
     */
    private val teardown = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "device-teardown").apply { isDaemon = true }
    }

    /**
     * 收尾 —— **不阻塞调用线程**，干完了在主线程回调 [onDone]。
     *
     * ★ 调用方要注意顺序：收尾是异步的，所以"收干净之后才能做"的事
     *   必须放进 [onDone]，不能写在 `shutdown()` 后面一行。
     *   —— 典型的就是 [reopen]：它得等旧 connection 关掉才能开新的，
     *   否则两个 connection 会撞同一个 fd。
     */
    fun shutdown(onDone: () -> Unit = {}) {
        teardown.execute {
            teardownBlocking_()
            main.post { onDone() }
        }
    }

    /**
     * 真正的收尾。**同步** —— 只在两种情况下直接用：
     *   · 已经在后台线程上（[shutdown] 内部）
     *   · [drive] 里那种"设备已经不可用、必须马上清干净再往下走"的场合
     *     （那颗设备早就掉线了，复位是立即失败的，不会真有那 518ms）
     */
    private fun teardownBlocking_() {
        PlayerSession.stopBlocking()
        val h = PlayerSession.handle
        if (h != 0L) runCatching { NativePlayer.nativeClose(h) }
        PlayerSession.clearDevice()
        deviceAlive = false
        connectedName = null
        runCatching { PlayerSession.connection?.close() }
        PlayerSession.connection = null
        notifyState()
    }

    /**
     * 输出方式变了 —— 把原生上下文**重建**一次。
     *
     * ★ 两种模式用的是两种上下文：系统音频是**无设备上下文**（`nativeOpen(-1)`），
     *   USB 直连是**带设备上下文**（wrap 了 fd 的）。两者不通用，必须重建。
     *
     * ★ 重建会中断当前播放 —— 这是有意的：一路放着一路换输出端，
     *   状态很容易对不上（谁的环形缓冲、谁的线格式），不如干脆重来。
     */
    fun reopen(ctx: Context, onResult: (Boolean, String) -> Unit) {
        /*
         * ★★ 先**强制复位**状态机，再往下走。
         *
         *   用户主动点「重连」的意思就是"我不管你现在卡在哪，重来一次" ——
         *   所以不能顺着一个可能已经卡死的状态往下走：卡在等授权时 [busy]
         *   一直是 true，[ensureOpen] 会**直接返回**、把回调排进 [waiting] 里
         *   永远不答复，按钮点了跟没点一模一样。
         *
         *   ★ 这一条是 2026-10-01 那个"卡在正在连接、拔插都救不回来、
         *     只能重启 App"的直接兜底 —— 超时是让它自己好，这个是**立刻**好。
         */
        forceReset("已被「重新连接」打断")
        // ★ 收尾现在是异步的（它里面有 518ms 的设备复位）—— 所以必须**串起来**：
        //   等旧的 connection 真关掉了，再开新的。写在下一行直接调
        //   ensureOpen 的话，两个 connection 会撞同一个 fd。
        shutdown { ensureOpen(ctx, onResult) }
    }

    /**
     * 把状态机强行掰回干净状态。
     *
     * ★★ 排队等着的回调**必须逐个答复**（false），不能悄悄丢掉 ——
     *    丢掉的话调用方那个"点了之后弹提示"的分支永远不执行，
     *    表现就是**点了没反应**（FACT 里那条最贵的教训）。
     */
    private fun forceReset(reason: String) {
        main.removeCallbacks(suspendTimeout)
        busy = false
        awaitingPermission = false
        val cbs = waiting.toList()
        waiting.clear()
        notifyState()
        cbs.forEach { it(false, reason) }
    }
}
