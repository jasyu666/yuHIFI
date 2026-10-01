package com.hifiprobe

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * 设置。
 *
 * 分两块：**常规**（用户真会调的）和**测试功能**（出问题才动的）。
 * 这样翻设置时不会面对一堆看不懂的开关，排查时它们又都在手边。
 *
 * 界面用代码搭而不是 XML：全是同构的行，代码比 XML 短也好改。
 *
 * ★ 两个必须守住的约定：
 *
 *   一、**颜色一律从主题取**（[Ui.c]），绝不写字面量。写死了的话，
 *       浅色主题下就会冒出一堆看不清的浅色文字。
 *
 *   二、构建函数**把父容器当参数传**，不要用 `apply` 式的接收者。
 *       用接收者的话，lambda 里的 `this` 会变成 LinearLayout，
 *       于是 `Intent(this, X::class.java)` 悄悄传进去一个 View ——
 *       编译器未必报错，运行时才崩。这个坑踩过一次。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /*
         * ★ 必须调 —— [PlayerSession.init] 是**读入累计收听时长**的地方。
         *
         *   本页原来没有这一行（其他 7 个页面都有）。平时看不出来，
         *   因为从首页进来时首页已经 init 过了；但进程被杀之后系统
         *   直接把用户恢复到设置页时，没人 init，"统计"那行就会显示 0 秒 ——
         *   看着像数据丢了。
         */
        PlayerSession.init(applicationContext)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.c(this@SettingsActivity, R.color.bg))
            isFillViewport = true
            addView(container)
        }
        Ui.applySystemBars(scroll)
        setContentView(scroll)

        build()

        if (intent.getBooleanExtra(EXTRA_OPEN_WIRELESS, false)) {
            container.post { toggleWireless() }
        }
    }

    /*
     * 音量那三根滑条 + 三个输入框 + 锁状态那一行。
     *
     * ★ 存成字段是为了**改一根能同步另外两根** —— 上下限一动，音量滑条的
     *   可拖范围就得跟着收；不重建整页（重建会把页面弹回顶部，
     *   用户正拖着的时候很恼人）。
     * ★ `build()` 每次都会重新赋值，所以不需要谁记得清空。
     */
    private var volSeek: SeekBar? = null
    private var volEditor: EditText? = null
    private var ceilSeek: SeekBar? = null
    private var ceilEditor: EditText? = null
    private var floorSeek: SeekBar? = null
    private var floorEditor: EditText? = null

    /** 锁上 / 解锁时那一行提示。★ 每次 [syncVolumeSliders] 都要刷，见那儿 */
    private var volLockNote: TextView? = null

    /**
     * "正在程序化更新"的哨兵位。
     *
     * ★★ 滑条动 → 改输入框文本；把输入框 `isEnabled = false` → 它失焦 →
     *    失焦又触发提交 → 提交里再调同步…… 没有这道闸就会绕回来。
     *    有它之后：[syncVolumeSliders] 全程置位，[commitVolume] / [commitLimit]
     *    进门先看它，是就立刻返回。
     */
    private var editingProgrammatically = false

    private fun build() {
        container.removeAllViews()
        /*
         * ★★ 必须先把上一轮那几个引用清掉。不清的话，下面**先建的那一行**
         *    会去设已经随视图树移除的旧滑条 —— 本身无害，但会让
         *    "谁先建、谁后建"莫名其妙地影响结果，排错时极难看出来。
         */
        volSeek = null; volEditor = null
        ceilSeek = null; ceilEditor = null
        floorSeek = null; floorEditor = null
        volLockNote = null

        header()

        section("外观")
        card { c ->
            row(c, "主题", "深色适合暗环境，浅色适合白天",
                themeModeName(Settings.themeMode(this))) { pickTheme() }
            divider(c)
            row(c, "默认封面样式",
                if (Settings.vinylIsCustom(this))
                    "当前用的是「彩胶设计」里存下的那张。选下面任意预设会换掉它"
                else
                    "没有内嵌封面的曲目用这个。都是程序化画的，不是图片资源",
                // ★ 自己存的设计显示它的名字；加名字之前存的老设计没有名字，
                //   退回「自定义」—— 不能让 trailing 空着
                Settings.vinylCustomName(this)
                    ?: if (Settings.vinylIsCustom(this)) "自定义"
                    else presetName(Settings.vinylPreset(this))) { pickVinylPreset() }
            divider(c)
            row(c, "彩胶设计",
                "自己造一张盘 —— 调底色、喷溅颜色与份量，再调压片机的真实参数" +
                        "（投料区大小、料块大小）。保存时给它起个名字，\n" +
                        "存下来的会和内置样式并列，可以随时切回去",
                "›") { startActivity(Intent(this, VinylDesignActivity::class.java)) }
        }

        section("音乐库")
        card { c ->
            row(c, "无线传输",
                "在 App 内起一个网页服务，电脑浏览器打开就能上传和管理音乐库。\n" +
                        "⚠ 只在本次运行期间有效 —— App 重启后需要重新打开一次",
                if (WirelessServer.isRunning()) "已开启" else "已关闭") { toggleWireless() }
            divider(c)
            row(c, "端口", "浏览器里用 http://手机IP:端口 访问。被占用时换一个",
                Settings.wirelessPort(this).toString()) { editPort() }
        }

        section("输出方式")
        card { c ->
            check(c, "USB 直连（bit-perfect）",
                "开启：独占 USB 解码器，bit-perfect 输出。DSD 仅可经此方式播放。\n" +
                "关闭（默认）：使用系统音频，支持蓝牙、外接耳机与扬声器，无需解码器。\n" +
                "注意：系统音频经由 AudioFlinger 混音，不属于 bit-perfect；" +
                "蓝牙路径另有有损编码（SBC / AAC / LDAC）。\n" +
                "切换输出方式将重新初始化音频输出，当前播放会中断。",
                Settings.usbDirect(this)) { v ->
                Settings.setUsbDirect(this, v)
                DeviceGate.reopen(this) { ok, msg ->
                    runOnUiThread { toast(if (ok) msg else "切换失败：$msg") }
                }
            }
        }

        section("音质")
        card { c ->
            check(c, "信任 44.1k 家族",
                "8 个标准速率全部直通，44.1k 曲库不再重采样。实测这 8 个设备都真的支持" +
                        "（判据是设备自己的 feedback 端点，不是 SET_CUR 回读）",
                Settings.allowAllRates(this)) { v ->
                Settings.setAllowAllRates(this, v)
                NativePlayer.nativeSetAllowAllStandardRates(v)
                toast("下次打开文件生效")
            }
        }

        section("交叉馈送")
        card { c ->
            check(c, "交叉馈送",
                "戴耳机时左右耳各听各的，声像会挤在脑袋里（头中效应）。开启后把一部分" +
                        "对侧声道延迟一点、滤掉高频再混进来，模拟声音绕过头部 —— 声像会往外推，" +
                        "代价是左右分离度下降。\n" +
                        "★ 开启后不再适用 bit-perfect：它就是在改送往解码器的样本。\n" +
                        "★ 对 DSD 无效（DSD 走原生位流，不经过 PCM 域），对单声道也无效。",
                Settings.crossfeedEnabled(this)) { v ->
                Settings.setCrossfeedEnabled(this, v)
                PlayerSession.refreshCrossfeed()
            }
            divider(c)
            row(c, "强度",
                "延迟 0.20 / 0.35 / 0.50 ms（两耳声程差），低通 700Hz（头影效应），" +
                        "交叉量 −6 / −4.5 / −3 dB。越强声场越窄。",
                crossfeedLevelName(Settings.crossfeedLevel(this))) { pickCrossfeedLevel() }
        }

        section("音量控制")
        card { c ->
            check(c, "音量控制",
                "USB 直连绕过了系统混音，所以系统音量条对它不起作用；而很多小尾巴" +
                        "（Dawn Pro、TANCHJIM BUNNY DSP 都是）只实现了静音、没有硬件音量 ——" +
                        "这类设备在独占模式下就彻底没法调响度。开启后音量滑条出现在正在播放页。\n" +
                        "★ 开启后不再适用 bit-perfect：它是在样本上做乘法。\n" +
                        "★ 但输出位深会提到 24bit —— 16bit 音源在 −48dB 以内不丢有效位。\n" +
                        "★ 音量停在 0dB 时一个样本都不碰；对 DSD 无效。",
                Settings.volumeControl(this)) { v ->
                Settings.setVolumeControl(this, v)
                PlayerSession.refreshSoftwareVolume()
                // 位深是开文件时定的，切换要等下一首
                toast(if (v) "已开启 · 输出位深下次打开文件时切换" else "已关闭 · 恢复 bit-perfect")
                build()          // 滑条的可用状态跟着开关走，重画一下
            }
            divider(c)
            volumeRow(c)
            divider(c)
            limitRow(c, ceiling = true)
            divider(c)
            limitRow(c, ceiling = false)
            divider(c)
            /*
             * ★ 锁放在**三根滑条之后** —— 用户先看到能调什么，再看到"要不要锁住"。
             *   摆最上面的话，没开过这个功能的人会先撞见一个看不懂的开关。
             */
            check(c, getString(R.string.volume_lock),
                "锁上之后三根滑条和三个输入框都不能动了 —— 防的不是别人，是自己手滑。\n" +
                        "★ 它和上限、下限不是一回事：上下限管能调到哪，锁管能不能调。\n" +
                        "★ 会一直记着，重启也还在。",
                Settings.volumeLocked(this)) { v ->
                Settings.setVolumeLocked(this, v)
                syncVolumeSliders()      // 不整页重建：重建会把页面弹回顶部
            }
            lockNote(c)
            dsdWarn(c)
        }

        section("后台")
        card { c ->
            row(c, getString(R.string.bg_unrestricted),
                "不设置的话，系统会在后台把进程杀掉、音乐就断了 —— 所以这一项是" +
                        "后台播放的前提，不是可选项。点这里看怎么开。\n" +
                        "★ 页面里读到的状态在小米上不一定准，以系统里实际显示为准",
                if (batteryUnrestricted()) "已开启" else "未开启") { showBatteryHint() }
        }

        section("统计")
        card { c ->
            row(c, "累计收听",
                "真正在播放的时间总和。暂停和停止不计；音频卡顿计（人还在听）。" +
                        "每首放完记一次。点这里查看或清空。",
                fmtListened(PlayerSession.listenedTotalMs())) { showListened() }
        }

        section("测试功能")
        hint("下面这些都有安全的默认值。没遇到对应的问题就别动 —— " +
                "每一项都写清了它在什么情况下该被调整。")

        card { c ->
            row(c, "在途 URB 深度",
                "抗调度卡顿的余量（=深度 ms），同时决定 seek 后旧音频的尾巴长度。\n" +
                        "默认 32 是实测能消除断音的最小值；后台卡顿多就往上调",
                "${Settings.urbCount(this)} 个") { pickUrb() }
            divider(c)
            check(c, "跟随设备反馈速率",
                "异步 DAC 必须跟随，否则它的 FIFO 会被慢慢抽干、每 60~90 秒卡一次。\n" +
                        "留这个开关只为 A/B 对比，正常使用请保持开启",
                Settings.followFeedback(this)) { v ->
                Settings.setFollowFeedback(this, v)
                PlayerSession.handle.takeIf { it != 0L }
                    ?.let { NativePlayer.nativeSetFollowFeedback(it, v) }
            }
            divider(c)
            check(c, "seek 时丢弃在途数据",
                "去掉 seek 之后残留的旧音频尾巴，代价是有一小段静音",
                Settings.flushQueueOnSeek(this)) { v ->
                Settings.setFlushQueueOnSeek(this, v)
                PlayerSession.handle.takeIf { it != 0L }
                    ?.let { NativePlayer.nativeSetFlushQueueOnSeek(it, v) }
            }
            divider(c)
            check(c, "无缝切歌（同速率不停流）",
                "专辑连播时不留空隙。⚠ 尚未实现 —— 打开也不会有任何效果，等后续版本",
                Settings.gapless(this), enabled = false) { v -> Settings.setGapless(this, v) }
            divider(c)
            check(c, "输出字节转储",
                "把送进 USB 的字节存成文件，用于和电脑上的参考 PCM 逐字节比对。" +
                        "验证 bit-perfect 用，正常听歌不必开（占 32MB 内存）",
                Settings.outputDump(this)) { v -> Settings.setOutputDump(this, v) }
        }

        section("诊断")
        card { c ->
            row(c, "打开诊断页",
                "手动接管 USB 设备、扫描采样率、验证速率真伪、导出完整报告",
                "›") { startActivity(Intent(this, MainActivity::class.java)) }
        }

        section("调试")
        card { c ->
            row(c, "只读调试端口",
                "在局域网里暴露当前界面截图和播放状态，供开发时远程查看。" +
                        "⚠ 同网络下任何人都能访问，包括你的屏幕内容 —— 不用时请关掉",
                if (Settings.debugEnabled(this)) "已开启" else "已关闭") { toggleDebug() }
        }

        hint("yuHIFI · 自研 UAC 驱动 + libusb 独占输出")
    }

    // ------------------------------------------------------------------
    //  动作
    // ------------------------------------------------------------------

    private fun pickTheme() {
        val modes = listOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        AlertDialog.Builder(this)
            .setTitle("主题")
            .setItems(modes.map { themeModeName(it) }.toTypedArray()) { _, which ->
                Settings.setThemeMode(this, modes[which])
                // 这一步会让所有存活的 Activity 重建，界面自然切过去
                AppCompatDelegate.setDefaultNightMode(modes[which])
            }
            .show()
    }

    private fun themeModeName(mode: Int): String = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
        AppCompatDelegate.MODE_NIGHT_YES -> "深色"
        else -> "跟随系统"
    }

    /**
     * 无线传输开关。
     *
     * ★★ 状态**只问服务本身**（[WirelessServer.isRunning]），不存 SharedPreferences。
     *
     *   以前存了一个 `wireless_on` 标志，但**没有任何地方在启动时把它兑现** ——
     *   App 一重启，服务没了，标志还留着。于是设置里显示「已开启」，
     *   而实际上 8765 根本没在监听；用户点一下想打开，反而走的是「关闭」
     *   分支，**得点两次才真能开起来**。
     *
     *   现在服务状态是唯一事实来源：重启 = 关，显示 = 关，点一下就开。
     *   代价是每次重启 App 都要重新打开一次 —— 但这个代价是**看得见的**，
     *   比一个会撒谎的开关好。
     */
    private fun toggleWireless() {
        if (WirelessServer.isRunning()) {
            WirelessServer.stop(this)
            toast("无线传输已关闭")
        } else {
            if (WirelessServer.start(this)) {
                AlertDialog.Builder(this)
                    .setTitle("无线传输已开启")
                    .setMessage(
                        "在电脑浏览器里打开：\n\n${WirelessServer.url(this)}\n\n" +
                                "手机和电脑要在同一个 WiFi 下。\n" +
                                "上传的文件会直接进音乐库，传完自动刷新。\n\n" +
                                "⚠ App 重启后需要重新打开一次。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                toast("启动失败：端口 ${Settings.wirelessPort(this)} 可能被占用")
            }
        }
        build()
    }

    private fun editPort() {
        val input = EditText(this).apply {
            setText(Settings.wirelessPort(this@SettingsActivity).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("无线传输端口")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val p = input.text.toString().toIntOrNull()
                if (p != null && p in 1024..65535) {
                    Settings.setWirelessPort(this, p)
                    // 改了端口要重启服务。只在它确实开着的时候才需要
                    if (WirelessServer.isRunning()) {
                        WirelessServer.stop(this)
                        WirelessServer.start(this)
                    }
                    build()
                } else {
                    toast("端口要在 1024 ~ 65535 之间")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 封面样式选择：**内置预设 + 用户自己存的设计**，并列在一张列表里。
     *
     * ★ 自己的设计带「★」前缀 —— 不然和内置的分不出来，
     *   而这两者的来源完全不同（一个是代码里的常量，一个是用户的作品）。
     *
     * ★★ 选中任意一项走的还是原来那条路：
     *   选预设 = 清掉 `vinyl_custom`；选自己的设计 = 把它的 json 写进 `vinyl_custom`。
     *   `currentVinyl()` 里「自定义压着预设」的判断一个字都没改 ——
     *   那段逻辑以前踩过坑（存过自定义之后选任何预设都没反应），不能碰。
     */
    private fun pickVinylPreset() {
        val saved = Settings.savedVinyls(this)
        val labels = ArrayList<String>(VinylArt.PRESETS.size + saved.size + 1)
        VinylArt.PRESETS.indices.forEach { labels.add(presetName(it)) }
        saved.forEach { labels.add("★ ${it.name}") }
        if (saved.isNotEmpty()) labels.add("删除设计…")

        AlertDialog.Builder(this)
            .setTitle("默认封面样式")
            .setItems(labels.toTypedArray()) { _, which ->
                val nPreset = VinylArt.PRESETS.size
                when {
                    which < nPreset -> applyPreset(which)

                    which < nPreset + saved.size -> {
                        val s = saved[which - nPreset]
                        Settings.setVinylCustom(this, s.json)
                        Settings.setVinylCustomName(this, s.name)
                        toast("已切换为「${s.name}」")
                        build()
                    }

                    else -> pickVinylToDelete()
                }
            }
            .show()
    }

    private fun applyPreset(which: Int) {
        // ★ 选预设 = 放弃自定义。不清的话，之前存过的自定义会继续压着预设，
        //   用户点了没反应，还以为设置坏了
        val hadCustom = Settings.vinylIsCustom(this)
        Settings.setVinylCustom(this, null)
        Settings.setVinylCustomName(this, null)
        Settings.setVinylPreset(this, which)
        /*
         * ★ 措辞从「之前的自定义设计已取消」改掉了：现在自己存的设计是**存档**，
         *   切到预设只是不再用它，那张设计还在列表里，随时切得回去 ——
         *   说"已取消"会让人以为丢了。
         */
        if (hadCustom) toast("已切换为预设（你自己的设计还留着）")
        build()
    }

    private fun pickVinylToDelete() {
        val saved = Settings.savedVinyls(this)
        if (saved.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除哪个设计")
            .setItems(saved.map { it.name }.toTypedArray()) { _, which ->
                val name = saved[which].name
                Settings.removeSavedVinyl(this, name)
                // 删掉的正好是当前在用的那张 → 退回第一个内置预设，
                // 免得 `vinyl_custom` 指着一条已经不存在的存档
                if (Settings.vinylCustomName(this) == name) {
                    Settings.setVinylCustom(this, null)
                    Settings.setVinylCustomName(this, null)
                    Settings.setVinylPreset(this, 0)
                }
                toast("已删除「$name」")
                build()
            }
            .show()
    }

    private fun presetName(i: Int): String = when (i) {
        0 -> "经典（红标黑盘）"
        1 -> "紫青喷溅"
        2 -> "绿金喷溅"
        3 -> "米白蓝红"
        4 -> "黑底三色"
        5 -> "透明盘（红白）"
        else -> "样式 $i"
    }

    private fun pickUrb() {
        val options = listOf(8, 16, 32, 64, 128)
        val labels = options.map {
            when (it) {
                8 -> "$it 个（余量 8ms）—— 会卡，留作复现旧问题"
                32 -> "$it 个（余量 32ms）—— 默认，实测能消除断音的最小值"
                128 -> "$it 个（余量 128ms）—— seek 会明显迟钝"
                else -> "$it 个（余量 ${it}ms）"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("在途 URB 深度")
            .setItems(labels) { _, which ->
                val v = options[which]
                Settings.setUrbCount(this, v)
                PlayerSession.handle.takeIf { it != 0L }
                    ?.let { NativePlayer.nativeSetUrbCount(it, v) }
                build()
            }
            .show()
    }

    /**
     * 音量滑条。
     *
     * ★ 设置页和**正在播放页**各有一个 —— 这里方便"开播前先调好"，
     *   那边方便"边听边调"。刻度和换算都走 [Settings.volumeDbOfStep] 那一份，
     *   两边不会对不上。
     * ★ 开关关着时滑条置灰（而不是藏起来）—— 让用户看得见"这里有这个功能"。
     */
    /**
     * 音量滑条。
     *
     * ★ 设置页和**正在播放页**各有一个 —— 这里方便"开播前先调好"，
     *   那边方便"边听边调"。刻度和换算都走 [Settings.volumeDbOfStep] 那一份，
     *   两边不会对不上。
     * ★ 开关关着时滑条置灰（而不是藏起来）—— 让用户看得见"这里有这个功能"。
     * ★★ 它的**两头不是 −80/0，是用户自己设的下限/上限**（见 [limitRow]）。
     */
    /**
     * 音量一行：**滑条 + 可输入的数值框**（用户 2026-09-29 定，两个都要）。
     *
     * ★ 设置页和**正在播放页**各有一个 —— 这里方便"开播前先调好"，
     *   那边方便"边听边调"。刻度和换算都走 [Settings.volumeDbOfStep] 那一份，
     *   两边不会对不上。
     * ★ 关着 / 锁着时置灰（而不是藏起来）—— 让用户看得见"这里有这个功能"。
     * ★★ 它的**两头不是 −80/0，是用户自己设的下限/上限**（见 [limitRow]）。
     */
    private fun volumeRow(parent: LinearLayout) {
        val on = Settings.volumeControl(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
        val label = TextView(this).apply {
            text = getString(R.string.volume_control)
            textSize = 15f
            setTextColor(Ui.c(this@SettingsActivity,
                if (on) R.color.text_primary else R.color.text_tertiary))
        }
        val sb = SeekBar(this).apply { isEnabled = on }
        val ed = valueEditor { commitVolume(it) }
        volSeek = sb
        volEditor = ed

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val db = Settings.volumeDbOfStep(p)
                Settings.setVolumeDb(this@SettingsActivity, db)
                // 原子变量，下个块就生效 —— 另一页那个滑条下次 onResume 会读到同一个值
                PlayerSession.refreshSoftwareVolume()
                /*
                 * ★ 这里**只改输入框的文本**，不调 syncVolumeSliders ——
                 *   那个会把滑条自己的 progress 又设一遍，手指底下的滑块会跳。
                 */
                setEditorText(ed, db)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        row.addView(label, LinearLayout.LayoutParams(dp(40), wrap))
        row.addView(sb, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(ed, LinearLayout.LayoutParams(dp(76), wrap))
        parent.addView(row)

        /* 范围和位置统一由 syncVolumeSliders 按存储算，这里不另存一份 */
        syncVolumeSliders()

        if (!Settings.volumeLocked(this)) {
            hint(
                if (on) "滑条和右边的框都能改，改哪个都一样。两头由下面的上限、下限决定 ——" +
                        "拖到底也只会到上限，不会到 0dB。−48dB 以内不丢有效位，再低就开始丢，但到那个响度早就听不出来了。"
                else "开关打开后才能调。"
            )
        }
    }

    /**
     * 上限 / 下限各一行。**也是滑条 + 输入框**。
     *
     * ★★ 它们定义的只是**音量滑条的两头** —— 用户不用理解"夹取"这种词，
     *    看到的就是"推到头最多这么响"。
     * ★ 三根滑条共用同一把尺子（0..VOLUME_STEPS 就是 −80..0dB），
     *   只把各自的 min/max 收进允许范围；**尺子本身不动**
     *   （见 [Settings.VOLUME_STEPS] 那条注释）。
     * ★ 上限的左边、下限的右边都要给对面留出 [Settings.MIN_SPAN_DB] ——
     *   否则两根能叠在一起，范围塌成 0 就锁死了。
     */
    private fun limitRow(parent: LinearLayout, ceiling: Boolean) {
        val on = Settings.volumeControl(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
        val label = TextView(this).apply {
            text = getString(if (ceiling) R.string.volume_ceiling else R.string.volume_floor)
            textSize = 15f
            setTextColor(Ui.c(this@SettingsActivity,
                if (on) R.color.text_primary else R.color.text_tertiary))
        }
        val sb = SeekBar(this).apply { isEnabled = on }
        val ed = valueEditor { commitLimit(it, ceiling) }
        if (ceiling) { ceilSeek = sb; ceilEditor = ed } else { floorSeek = sb; floorEditor = ed }

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val asked = Settings.volumeDbOfStep(p)
                /*
                 * ★★ 存进去的可能是**被夹过**的值（撞上对面那根滑条了），
                 *    所以显示和滑块位置都得用**读回来的实际值**，不能直接用 asked ——
                 *    否则标签写着 −78、滑块实际停在 −75，对不上。
                 */
                val real = if (ceiling) {
                    Settings.setVolumeCeilingDb(this@SettingsActivity, asked)
                    Settings.volumeCeilingDb(this@SettingsActivity)
                } else {
                    Settings.setVolumeFloorDb(this@SettingsActivity, asked)
                    Settings.volumeFloorDb(this@SettingsActivity)
                }
                // 上限压低 / 下限抬高都可能把当前音量一起带走，得立刻推给解码线程
                PlayerSession.refreshSoftwareVolume()
                setEditorText(ed, real)
                val step = Settings.volumeStepOfDb(real)
                if (s.progress != step) s.progress = step     // 顶住，别越过对面那根
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            /* 松手才同步三根 —— 拖动中去动别人的 min/max 会让手指下的滑块跳 */
            override fun onStopTrackingTouch(s: SeekBar) { syncVolumeSliders() }
        })

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        row.addView(label, LinearLayout.LayoutParams(dp(40), wrap))
        row.addView(sb, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(ed, LinearLayout.LayoutParams(dp(76), wrap))
        parent.addView(row)

        syncVolumeSliders()

        if (!Settings.volumeLocked(this)) {
            hint(
                if (!on) "开关打开后才能调。"
                else if (ceiling) "音量最多只能调到这里。设它是为了让手一滑也不至于炸耳朵。"
                else "音量最少只能调到这里。设它可以把最左端那截听不见的区间直接砍掉。"
            )
        }
    }

    /**
     * 数值输入框。**和滑条并存**，两个改的是同一个值。
     *
     * ★★ 两条纪律，缺一条就没法用：
     *
     *   1. **只在提交时解析**（失焦 / IME Done），**绝不逐字符实时**：
     *      逐字符的话用户打到 `-` 或 `-1` 这种中间态就被夹一次、
     *      输入框被自己改写 —— **根本打不进去**。
     *   2. **单位不进框**：框里只放数字，`dB` 在标签上。带单位的话
     *      每次解析都得先剥掉它，凭空多一层出错的地方。
     */
    private fun valueEditor(onCommit: (EditText) -> Unit): EditText {
        val on = Settings.volumeControl(this)
        val locked = Settings.volumeLocked(this)
        val ed = EditText(this).apply {
            textSize = 13f
            gravity = Gravity.END
            maxLines = 1
            background = null
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Ui.c(this@SettingsActivity,
                if (on && !locked) R.color.brand else R.color.text_tertiary))
            isEnabled = on && !locked
            setSelectAllOnFocus(true)
        }
        ed.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onCommit(ed) }
        ed.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { onCommit(ed); true } else false
        }
        return ed
    }

    /**
     * 把 [db] 写进输入框。**唯一允许改这个文本的地方**。
     *
     * ★ 用的是**不带单位**的 [Settings.fmtVolumeInput] —— 带「dB」的话
     *   下次解析就得多剥一刀。
     */
    private fun setEditorText(ed: EditText?, db: Float) {
        ed ?: return
        val s = Settings.fmtVolumeInput(db)
        if (ed.text.toString() == s) return
        ed.setText(s)
        ed.setSelection(s.length)
    }

    /**
     * 提交输入框里的音量。
     *
     * ★ **非法输入不弹错**（空 / 非数字 / 越界）—— 夹到合法值、回写规范格式就完了。
     *   为一次手滑弹个对话框，比直接夹一下烦人得多。
     * ★ 夹的范围由 [Settings.setVolumeDb] 内部收口（它问 [Settings.volumeRange]），
     *   这里不重复判一遍。
     */
    private fun commitVolume(ed: EditText) {
        if (editingProgrammatically) return
        val v = ed.text.toString().trim().toFloatOrNull()
        if (v != null) {
            Settings.setVolumeDb(this, v)
            PlayerSession.refreshSoftwareVolume()
        }
        syncVolumeSliders()
    }

    /** 同上，给上限 / 下限用。`ceiling = true` 是上限 */
    private fun commitLimit(ed: EditText, ceiling: Boolean) {
        if (editingProgrammatically) return
        val v = ed.text.toString().trim().toFloatOrNull()
        if (v != null) {
            if (ceiling) Settings.setVolumeCeilingDb(this, v)
            else Settings.setVolumeFloorDb(this, v)
            PlayerSession.refreshSoftwareVolume()
        }
        syncVolumeSliders()
    }

    /**
     * 锁状态那一行。
     *
     * ★★ **必须在每次 [syncVolumeSliders] 里刷新**，不能只在 build 时定死 ——
     *    用户就在这一页上开关锁，看不到反馈会以为没生效。
     * ★ 这是"光变灰不够，得让它看得见"那条：滑条变灰容易被当成"坏了"。
     */
    private fun lockNote(parent: LinearLayout) {
        volLockNote = TextView(this).apply {
            textSize = 11f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), 0, dp(4), dp(10))
            visibility = View.GONE
        }
        parent.addView(volLockNote)
    }

    /**
     * 红色的 DSD 警告。
     *
     * ★★ 用 `error` 色，不是普通的灰色 hint —— 这条不是"补充说明"，
     *    是**这个功能对一整类文件完全无效**。不写醒目点，
     *    用户放 DSD 时发现滑条不动，会当成 bug 报回来。
     */
    private fun dsdWarn(parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            text = getString(R.string.volume_dsd_warning)
            setTextColor(Ui.c(this@SettingsActivity, R.color.error))
            textSize = 11f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), 0, dp(4), dp(10))
        })
    }

    /**
     * 按存储把三根滑条 + 三个输入框的范围、位置、可用状态对齐。
     *
     * ★★ 每根滑条的 min/max 都**现算**，不缓存 —— 拖动上限要让音量滑条的
     *    可拖范围立刻跟着收，这才是"上限"该有的手感。
     * ★ 顺序必须是 **min/max 先、progress 后**：progress 会被夹进当前范围，
     *   反过来设就会被**旧范围**夹一次，位置就错了。
     * ★★ **可用状态是 `volumeControl && !volumeLocked`** —— 两个条件是 `&&`，
     *    直接写成 `!locked` 会把「音量控制」这个总开关吃掉。
     * ★★ 整段套在 [editingProgrammatically] 里当**重入保护**：把输入框
     *    `isEnabled = false` 会让它失焦，失焦又触发提交流程 ——
     *    没有这道闸就会绕回来（能收敛，但没必要绕）。
     */
    private fun syncVolumeSliders() {
        if (editingProgrammatically) return
        editingProgrammatically = true
        try {
            val usable = Settings.volumeControl(this) && !Settings.volumeLocked(this)
            val locked = Settings.volumeLocked(this)
            val floor = Settings.volumeFloorDb(this)
            val ceilDb = Settings.volumeCeilingDb(this)
            val volDb = Settings.volumeDb(this)

            volSeek?.let {
                it.min = Settings.volumeStepOfDb(floor)
                it.max = Settings.volumeStepOfDb(ceilDb)
                it.progress = Settings.volumeStepOfDb(volDb)
                it.isEnabled = usable
            }
            volEditor?.isEnabled = usable
            setEditorText(volEditor, volDb)

            ceilSeek?.let {
                it.min = Settings.volumeStepOfDb(
                    (floor + Settings.MIN_SPAN_DB).coerceAtMost(Settings.MAX_VOLUME_DB))
                it.max = Settings.volumeStepOfDb(Settings.MAX_VOLUME_DB)
                it.progress = Settings.volumeStepOfDb(ceilDb)
                it.isEnabled = usable
            }
            ceilEditor?.isEnabled = usable
            setEditorText(ceilEditor, ceilDb)

            floorSeek?.let {
                it.min = Settings.volumeStepOfDb(Settings.MIN_VOLUME_DB)
                it.max = Settings.volumeStepOfDb(
                    (ceilDb - Settings.MIN_SPAN_DB).coerceAtLeast(Settings.MIN_VOLUME_DB))
                it.progress = Settings.volumeStepOfDb(floor)
                it.isEnabled = usable
            }
            floorEditor?.isEnabled = usable
            setEditorText(floorEditor, floor)

            volLockNote?.let {
                it.visibility = if (locked) View.VISIBLE else View.GONE
                if (locked) {
                    it.text = getString(R.string.volume_locked_hint)
                    it.setTextColor(Ui.c(this@SettingsActivity, R.color.warn))
                }
            }
        } finally {
            editingProgrammatically = false
        }
    }

    // ------------------------------------------------------------------
    //  后台无限制（待办⑥）
    // ------------------------------------------------------------------

    /**
     * 系统里"本应用是否免电池优化"。
     *
     * ★★ **这个值在小米上不一定准** —— MIUI 的「省电策略」是独立的一套，
     *    AOSP 这个 API 返回 true 的时候 MIUI 照样可能杀。所以界面上
     *    **必须把这一点写出来**，别让用户以为"显示已开启 = 稳了"。
     * ★ 读状态**不需要任何权限**（只有"直接弹系统授权框"才需要）。
     */
    private fun batteryUnrestricted(): Boolean = runCatching {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    }.onFailure {
        // ★ 包 runCatching 就必须留痕 —— 吞掉的异常会变成"点了没反应"
        Log.e("HiFiSettings", "读电池优化状态失败: $it")
    }.getOrDefault(false)

    /**
     * 后台无限制的提示。
     *
     * ★★ **刻意不做自动跳转**（用户 2026-09-29 定）：小米那套厂商页面的
     *    `ComponentName` 是**私有的、随版本变**，写死了跳错地方比不跳更让人困惑。
     *    所以这里把路径**用文字写清楚**，再给一个通用的兜底入口。
     */
    private fun showBatteryHint() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bg_unrestricted_title)
            .setMessage(R.string.bg_unrestricted_msg)
            .setPositiveButton(R.string.action_app_details) { _, _ -> openAppDetails() }
            .setNegativeButton("知道了", null)
            .show()
    }

    /**
     * 跳到本应用的系统详情页。
     *
     * ★ 这个 Intent **通用、不要权限、任何机型都不会失败**，而且正好就是
     *   小米"应用管理 → 本应用"那一页的入口 —— 用户从那儿再走两步就到位了。
     * ★★ 必须写全 `android.provider.Settings`：本工程自己有个 [Settings] 对象，
     *   不写包名会被解析成我们那一个。
     */
    private fun openAppDetails() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        }.onFailure { Log.e("HiFiSettings", "打不开应用详情页: $it") }
    }
    /**
     * 档位短名。
     *
     * ★ 从 strings.xml 取，**不要在这儿再写一份字面量** ——
     *   状态条那边（[PlayerSession.crossfeedLabel]）用的是同一份资源，
     *   两处各写一份迟早会不一致。参数表在 `cpp/crossfeed.h`，三边对齐。
     */
    private fun crossfeedLevelName(i: Int) = getString(
        when (i) {
            0 -> R.string.crossfeed_level_weak
            2 -> R.string.crossfeed_level_strong
            else -> R.string.crossfeed_level_mid
        }
    )

    private fun pickCrossfeedLevel() {
        val labels = arrayOf(
            "弱 —— 延迟 0.20ms，交叉 −6dB（最保守）",
            "中 —— 延迟 0.35ms，交叉 −4.5dB（默认）",
            "强 —— 延迟 0.50ms，交叉 −3dB（声场最窄）"
        )
        AlertDialog.Builder(this)
            .setTitle("交叉馈送强度")
            .setItems(labels) { _, which ->
                Settings.setCrossfeedLevel(this, which)
                // ★ 引擎内部是原子变量，推一下就立刻生效 —— 不用重建、不用重开文件
                PlayerSession.refreshCrossfeed()
                build()
            }
            .show()
    }

    // ------------------------------------------------------------------
    //  界面构件
    // ------------------------------------------------------------------

    /**
     * 顶栏：返回 + 标题。
     *
     * 本页不是启动页，主题又是 NoActionBar —— 没有这一行的话，
     * 用户只能靠系统返回手势出去，而界面上没有任何提示。
     * 这是个纯粹的可用性缺口，不是装饰。
     */
    private fun header() {
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(android.widget.ImageButton(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Ui.c(this@SettingsActivity, R.color.text_primary))
                background = null
                contentDescription = "返回"
                setPadding(0, 0, 0, 0)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(this@SettingsActivity).apply {
                text = "设置"
                setTextColor(Ui.c(this@SettingsActivity, R.color.text_primary))
                textSize = 21f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), 0, 0, 0)
            })
        })
    }

    private fun toggleDebug() {
        if (Settings.debugEnabled(this)) {
            Settings.setDebugEnabled(this, false)
            DebugServer.stop()
            toast("调试端口已关闭")
            build()
            return
        }
        val ok = DebugServer.start(this)
        Settings.setDebugEnabled(this, ok)
        if (!ok) {
            toast("启动失败：端口 ${Settings.debugPort(this)} 可能被占用")
            build()
            return
        }
        val url = DebugServer.url(this) ?: "（没取到本机 IP）"
        AlertDialog.Builder(this)
            .setTitle("调试端口已开启")
            .setMessage(
                "同网络下用浏览器打开：\n\n$url\n\n" +
                        "/debug/state   当前页面与播放状态\n" +
                        "/debug/shot    当前界面截图\n" +
                        "/debug/crash   最后一次崩溃堆栈\n\n" +
                        "任何人都能访问这些地址，包括你的屏幕内容。用完请回来关掉。"
            )
            .setPositiveButton("知道了", null)
            .show()
        build()
    }

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            text = title
            setTextColor(Ui.c(this@SettingsActivity, R.color.brand))
            textSize = 12f
            letterSpacing = 0.12f
            setPadding(dp(4), dp(22), 0, dp(8))
        })
    }

    private fun hint(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(Ui.c(this@SettingsActivity, R.color.text_tertiary))
            textSize = 11f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), 0, dp(4), dp(8))
        })
    }

    /** 一张卡片。区块内的行都塞进去，用分隔线断开 —— 不要每行一张卡，太碎 */
    private fun card(build: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
        }
        container.addView(c, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        build(c)
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply {
            setBackgroundColor(Ui.c(this@SettingsActivity, R.color.border))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
    }

    private fun row(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        trailing: String,
        onClick: () -> Unit
    ) {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(2), dp(14), dp(2), dp(14))
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
            setOnClickListener { onClick() }
        }
        v.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(Ui.c(this@SettingsActivity, R.color.text_primary))
                textSize = 15f
            })
            addView(TextView(this@SettingsActivity).apply {
                this.text = subtitle
                setTextColor(Ui.c(this@SettingsActivity, R.color.text_tertiary))
                textSize = 11f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        v.addView(TextView(this).apply {
            text = trailing
            setTextColor(Ui.c(this@SettingsActivity, R.color.brand))
            textSize = 13f
            setPadding(dp(10), 0, dp(2), 0)
        })
        parent.addView(v)
    }

    private fun check(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(CheckBox(this).apply {
            text = title
            isChecked = checked
            this.isEnabled = enabled
            setTextColor(Ui.c(this@SettingsActivity,
                if (enabled) R.color.text_primary else R.color.text_tertiary))
            textSize = 15f
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        col.addView(TextView(this).apply {
            this.text = subtitle
            setTextColor(Ui.c(this@SettingsActivity, R.color.text_tertiary))
            textSize = 11f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(34), 0, dp(2), dp(10))
        })
        parent.addView(col)
    }

    /**
     * 把毫秒说成人话。
     *
     * ★ 不到 1 分钟要报**秒**，不能四舍五入成「0 分钟」——
     *   那看着像功能坏了，而不像"你刚听了半分钟"。
     */
    private fun fmtListened(ms: Long): String {
        val minutes = ms / 60000
        return when {
            ms < 60000 -> "${ms / 1000} 秒"
            minutes < 60 -> "$minutes 分钟"
            else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        }
    }

    private fun showListened() {
        AlertDialog.Builder(this)
            .setTitle("累计收听")
            .setMessage(
                "到目前为止一共听了 ${fmtListened(PlayerSession.listenedTotalMs())}。\n\n" +
                        "统计的是真正在播放的时间：暂停和停止不计，音频卡顿计（人还在听）。" +
                        "每首放完记一次盘，所以进程被杀最多丢当前这一首。\n\n" +
                        "清空之后从零重新计 —— 已听过的曲目不受影响，这只是个计数器。"
            )
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ -> confirmResetListened() }
            .show()
    }

    private fun confirmResetListened() {
        AlertDialog.Builder(this)
            .setTitle("清空累计收听时长？")
            .setMessage("清空后从零开始统计。这个操作不可撤销。")
            .setPositiveButton("清空") { _, _ ->
                PlayerSession.resetListened()
                toast("已清空")
                build()          // 重画，让那行 trailing 立刻显示 0
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        const val EXTRA_OPEN_WIRELESS = "open_wireless"
    }
}
