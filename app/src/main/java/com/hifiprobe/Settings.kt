package com.hifiprobe

import android.content.Context

/**
 * 用户设置。SharedPreferences 存。
 *
 * 集中在一处，是为了让"哪些是常规设置、哪些是实验开关"一目了然 ——
 * 实验开关（URB 深度、seek 清队、feedback 跟随、无缝切歌）都带着
 * "出问题时能退回默认值"的说明，改错了也知道往哪退。
 */
object Settings {

    private const val FILE = "hifiprobe_settings"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- 常规 ----

    /**
     * 信任 44.1k 家族（8 个标准速率全直通）。
     *
     * 默认 **true** —— 实测这 8 个速率设备全都真的支持（判据是设备自己的
     * feedback 端点）。关掉退回旧策略：只走 48k 家族，44.1k 家族重采样。
     * 留这个开关是给"换到某台 44.1k 真有问题的设备"时现场回退用的。
     */
    fun allowAllRates(ctx: Context): Boolean =
        sp(ctx).getBoolean("allow_all_rates", true)

    fun setAllowAllRates(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("allow_all_rates", v).apply()

    // ---- 交叉馈送 ----

    /**
     * 交叉馈送开关。**默认关闭**。
     *
     * 把一部分对侧声道延迟 + 低通之后混进本侧，减轻戴耳机时的"头中效应"
     * （声像全挤在脑袋里）。代价是左右分离度下降 —— 这是取舍，不是免费。
     *
     * ★★ **开了就不再是 bit-perfect** —— 它就是在改送往 DAC 的样本，
     *    比"重采样"还彻底。所以默认关，而且设置卡片上必须写清楚。
     * ★ 对 DSD 无效（DSD 走原生位流，不经过 PCM 域），对单声道也无效。
     */
    fun crossfeedEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean("crossfeed_enabled", false)

    fun setCrossfeedEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("crossfeed_enabled", v).apply()

    /** 档位：0=弱 1=中 2=强。默认中。参数表在 cpp/crossfeed.h */
    fun crossfeedLevel(ctx: Context): Int =
        sp(ctx).getInt("crossfeed_level", 1)

    fun setCrossfeedLevel(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("crossfeed_level", v.coerceIn(0, 2)).apply()

    // ---- 音量控制 ----

    /**
     * 音量控制开关。**默认关闭**。
     *
     * ★★ 这是**软件数字音量**（在样本上做乘法），**必然不是 bit-perfect**。
     *    所以默认关 —— 关着的时候处理链里一次乘法都没有。
     *
     * ★ 为什么需要它：USB 直连绕过了 AudioFlinger，**系统音量条对它不起作用**；
     *   而设备自己的 UAC 硬件音量又常常没实现（Dawn Pro、TANJIM BUNNY DSP
     *   都只有静音、没有音量）。这类设备在独占模式下**没有任何办法调响度**。
     *
     * ★ 开着的时候输出位深会被提到 24bit（源低于 24bit 时）——
     *   16bit 源在 −48dB 以内**不丢任何有效位**。
     */
    fun volumeControl(ctx: Context): Boolean =
        sp(ctx).getBoolean("volume_control", false)

    fun setVolumeControl(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("volume_control", v).apply()

    /**
     * 音量锁。**默认 false，而且必须持久化**。
     *
     * ★★ 锁的意义是"我调好了别动" —— 重启就失效等于没锁，
     *    所以它和别的开关不一样，**不能只在内存里存**。
     *
     * ★ 它管的是**能不能调**（权限），不是**能调到哪**（范围）——
     *   后者是 [volumeCeilingDb] / [volumeFloorDb] 的事。两者叠加时：
     *   锁上之后滑条和输入框都不可用，上下限那一对也一起锁（整张卡片只读）。
     *
     * ★★ 界面侧**必须写成 `volumeControl && !volumeLocked`**，
     *   不能直接把锁写进 `isEnabled` —— 那会把「音量控制」这个总开关吃掉。
     */
    fun volumeLocked(ctx: Context): Boolean =
        sp(ctx).getBoolean("volume_locked", false)

    fun setVolumeLocked(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("volume_locked", v).apply()

    /**
     * 当前音量增益，分贝。**夹在 [下限, 上限] 之内**。
     *
     * ★★ 上下限是用户自己设的（见 [volumeCeilingDb] / [volumeFloorDb]），
     *   默认 −80 ~ 0 就等于不限制。读的时候统一夹这一次，
     *   **两个界面那两根滑条就不会各算各的** —— 它们问的是同一个函数。
     *
     * ★ 下限为什么默认 −80 而不是更低：软件音量本身没有硬下限，
     *   但 **−48dB** 是 16bit 源在 24bit 输出里那 8 位余量用光的位置，
     *   再低就开始丢有效位；**约 −96dB** 才是数学极限（16bit 源只剩 0 位有效）。
     *   −80dB 留了余量，又远超实际能听见的阈。
     *
     * ★ 上限的绝对天花板是 0dB —— 不做增益（放大）。满量程就是满量程，
     *   再往上只会削顶，没有任何好处。
     */
    fun volumeDb(ctx: Context): Float {
        val (lo, hi) = volumeRange(ctx)
        return rawVolumeDb(ctx).coerceIn(lo, hi)
    }

    fun setVolumeDb(ctx: Context, v: Float) {
        val (lo, hi) = volumeRange(ctx)
        sp(ctx).edit().putFloat("volume_db", v.coerceIn(lo, hi)).apply()
    }

    /**
     * 存进去的原始音量，**不夹**。
     *
     * ★★ 判断「要不要把当前音量一起压下去」时**只能用它** ——
     *   用 [volumeDb] 读到的已经是夹过的值，比较结果恒为假，
     *   压不下去也托不上来，存储里会留一个越界的旧值，
     *   等用户把上下限调回去时它会**毫无预兆地弹回来**。
     */
    private fun rawVolumeDb(ctx: Context) = sp(ctx).getFloat("volume_db", 0f)

    /** 可调范围的绝对下限。下限滑条最多只能拉到这儿 */
    const val MIN_VOLUME_DB = -80f

    /** 可调范围的绝对上限（0dB = 不衰减） */
    const val MAX_VOLUME_DB = 0f

    /**
     * 上下限之间必须留的最小跨度。
     *
     * ★ 不设这条的话两根滑条能叠在一起 —— 范围塌成 0 音量就锁死了，
     *   而且 `SeekBar` 的 min 和 max 相等时滑条整个推不动，看起来像坏了。
     */
    const val MIN_SPAN_DB = 5f

    /**
     * 生效的音量范围 (下限, 上限)。
     *
     * ★★ 两个值都**先各自夹进 [−80, 0]、再排序**，不直接信任存储：
     *   `coerceIn(lo, hi)` 在 lo > hi 时**会抛异常**，`SeekBar` 的 min/max
     *   反了也一样是坏的。存储被外部改坏时这里兜住 ——
     *   界面上最坏也只是可调范围变小，不会崩。
     *
     * ★ 跨度不够时**保上限、让下限** —— 上限才是「别把耳朵炸了」的那道闸。
     */
    private fun volumeRange(ctx: Context): Pair<Float, Float> {
        val a = sp(ctx).getFloat("volume_floor_db", MIN_VOLUME_DB)
            .coerceIn(MIN_VOLUME_DB, MAX_VOLUME_DB)
        val b = sp(ctx).getFloat("volume_ceiling_db", MAX_VOLUME_DB)
            .coerceIn(MIN_VOLUME_DB, MAX_VOLUME_DB)
        val hi = maxOf(a, b)
        val lo = minOf(minOf(a, b), hi - MIN_SPAN_DB).coerceAtLeast(MIN_VOLUME_DB)
        return lo to hi
    }

    /**
     * 音量上限。**默认 0dB = 不限制**。
     *
     * ★★ 它存在的理由只有一个：**防止手一滑把音量推到最高**。
     *   所以它定义的是**滑条的最右端** —— 拖到头也只会到上限，不是 0dB。
     */
    fun volumeCeilingDb(ctx: Context) = volumeRange(ctx).second

    /**
     * 设上限。
     *
     * ★ 上限被压到当前音量之下时，**顺手把当前音量一起压下去**（存的值也改）。
     *   这是防误触功能，那么「把上限调回去」就不该有惊喜 ——
     *   否则用户为了安全把上限调到 −20，回头再放开，音量会毫无预兆地弹回 0dB。
     */
    fun setVolumeCeilingDb(ctx: Context, v: Float) {
        val (floor, _) = volumeRange(ctx)
        // 夹的左边还要再夹一次：floor 是 0 时 floor+5 越界，coerceIn 会抛
        val low = (floor + MIN_SPAN_DB).coerceAtMost(MAX_VOLUME_DB)
        val hi = v.coerceIn(low, MAX_VOLUME_DB)
        sp(ctx).edit().putFloat("volume_ceiling_db", hi).apply()
        if (rawVolumeDb(ctx) > hi) setVolumeDb(ctx, hi)
    }

    /** 音量下限。**默认 −80dB = 不限制**。定义的是滑条的**最左端** */
    fun volumeFloorDb(ctx: Context) = volumeRange(ctx).first

    /** 设下限。抬到当前音量之上时把当前音量一起托上来，理由同 [setVolumeCeilingDb] */
    fun setVolumeFloorDb(ctx: Context, v: Float) {
        val (_, ceiling) = volumeRange(ctx)
        val high = (ceiling - MIN_SPAN_DB).coerceAtLeast(MIN_VOLUME_DB)
        val lo = v.coerceIn(MIN_VOLUME_DB, high)
        sp(ctx).edit().putFloat("volume_floor_db", lo).apply()
        if (rawVolumeDb(ctx) < lo) setVolumeDb(ctx, lo)
    }

    /**
     * 音量滑条的总格数。0..VOLUME_STEPS 映射到 [MIN_VOLUME_DB, 0]。
     *
     * ★ 200 格覆盖 80dB → 每格 0.4dB，够细了。
     * ★ 设置页和播放页各有一个滑条，**刻度和换算必须共用这一份** ——
     *   两边各写一套的话迟早会不一致（滑条位置对不上实际音量）。
     * ★★ 上下限只改**滑条自己的 min/max**（拿 [volumeStepOfDb] 换算），
     *   **不动这把尺子** —— 尺子一动，已经存下来的刻度值全都要重新解释。
     */
    const val VOLUME_STEPS = 200

    /** 滑条刻度 → 分贝 */
    fun volumeDbOfStep(step: Int) =
        MIN_VOLUME_DB * (VOLUME_STEPS - step.coerceIn(0, VOLUME_STEPS)) / VOLUME_STEPS

    /** 分贝 → 滑条刻度 */
    fun volumeStepOfDb(db: Float) =
        Math.round(VOLUME_STEPS * (1f + db / -MIN_VOLUME_DB)).coerceIn(0, VOLUME_STEPS)

    /** 音量的可读文本。0dB 单独说，不然「−0.0 dB」很难看 */
    fun fmtVolumeDb(db: Float): String =
        if (db >= -0.05f) "0 dB" else "%.1f dB".format(db)

    /**
     * 输入框里用的格式：**只有数字，不带单位**。
     *
     * ★ 单位放标签上（「音量」那一格），框里只填数字 —— 带单位的话
     *   每次解析都得先剥掉「dB」，凭空多一层出错的地方。
     * ★ 0dB 写成 `0` 而不是 `0.0`：连"要不要打小数点"这点犹豫都不给用户留。
     */
    fun fmtVolumeInput(db: Float): String =
        if (db >= -0.05f) "0" else "%.1f".format(db)

    /**
     * 主题模式。存的是 AppCompatDelegate.MODE_NIGHT_* 的值。
     *
     * 默认跟随系统 —— 这是唯一"猜不错"的选项：用户白天在系统里切了浅色，
     * App 跟着变才是最不意外的心智模型。
     */
    fun themeMode(ctx: Context): Int =
        sp(ctx).getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setThemeMode(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("theme_mode", v).apply()

    /**
     * 用户自己设计的彩胶（JSON）。
     *
     * 有它就用它，没有才回落到内置预设 —— 这样"我设计了一张盘"和
     * "我选了个预设"是同一个入口，用户不必理解两者的区别。
     */
    fun vinylCustom(ctx: Context): String? = sp(ctx).getString("vinyl_custom", null)
    fun setVinylCustom(ctx: Context, json: String?) =
        sp(ctx).edit().apply {
            if (json == null) remove("vinyl_custom") else putString("vinyl_custom", json)
        }.apply()

    /**
     * 当前生效的封面样式：**自定义优先**，没有自定义才用预设。
     *
     * ★ 这两者是互斥的，不能各存各的 ——
     *   之前选预设只改预设下标，而自定义又永远压着预设，
     *   结果就是"用户在彩胶设计里存过一次之后，再选任何预设都没反应"。
     *   所以设置页选预设时会顺手清掉自定义（见 SettingsActivity）。
     *
     * ★ 带一层缓存：列表里每绑一行封面就要调一次，每次都解析一遍 JSON
     *   太浪费。键是"原始串 + 预设下标"，两者任一变了缓存自动失效，
     *   不需要谁记得来清。
     */
    private var vinylMemo: Pair<String, VinylArt.Style>? = null

    fun currentVinyl(ctx: Context): VinylArt.Style {
        val raw = vinylCustom(ctx)
        val preset = vinylPreset(ctx)
        val key = raw ?: "#$preset"
        vinylMemo?.let { if (it.first == key) return it.second }
        val s = raw?.let { VinylArt.fromJson(it) }
            ?: VinylArt.PRESETS.getOrElse(preset) { VinylArt.CLASSIC }
        vinylMemo = key to s
        return s
    }

    /** 当前的封面样式是不是用户自己设计的（不是预设） */
    fun vinylIsCustom(ctx: Context): Boolean = vinylCustom(ctx) != null

    /** 默认封面用的黑胶风格下标（见 [VinylArt.PRESETS]） */
    fun vinylPreset(ctx: Context): Int = sp(ctx).getInt("vinyl_preset", 0)
    fun setVinylPreset(ctx: Context, v: Int) = sp(ctx).edit().putInt("vinyl_preset", v).apply()

    // ------------------------------------------------------------------
    //  用户自己设计并保存的彩胶 —— **带名字，可以存很多张**
    // ------------------------------------------------------------------

    /**
     * 一张用户保存的彩胶。 [name] 是用户起的名字，[json] 是 `VinylArt.Style`。
     *
     * ★★ 为什么不是"就一个自定义槽"：
     *   原来保存就是无声地覆盖那唯一的一格 —— 用户设计第二张的时候，
     *   第一张**没有任何提示地消失**，而且它连名字都没有，事后根本想不起来
     *   覆盖了什么。多张具名并存才是这里该有的模型。
     *
     * ★ 仍然只有 **一份** `vinyl_custom`（当前生效的那份），
     *   这里是**存档**。选中某一张 = 把它的 json 拷进 `vinyl_custom` ——
     *   这样 `currentVinyl()` 那套「自定义压着预设」的判断一行都不用改，
     *   而那段逻辑以前踩过坑（存过自定义之后选任何预设都没反应）。
     */
    data class SavedVinyl(val name: String, val json: String)

    private const val KEY_SAVED = "vinyl_saved"

    /** 存档列表，按保存顺序 */
    fun savedVinyls(ctx: Context): List<SavedVinyl> {
        val raw = sp(ctx).getString(KEY_SAVED, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SavedVinyl(o.optString("n"), o.optString("s"))
            }
        } catch (e: Throwable) {
            /*
             * ★ 解析失败只能当空列表 —— 但**不能静默**。
             *   这里不 Log 的话，用户看到的现象是"我存的设计全没了"，
             *   而没有任何线索说明是 JSON 坏了。
             */
            android.util.Log.e("HiFiSettings", "读已保存的彩胶失败: $e")
            emptyList()
        }
    }

    private fun setSavedVinyls(ctx: Context, list: List<SavedVinyl>) {
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(org.json.JSONObject().apply {
                put("n", it.name)
                put("s", it.json)
            })
        }
        sp(ctx).edit().putString(KEY_SAVED, arr.toString()).apply()
    }

    /** 存一张。★ **同名即覆盖** —— 不然列表只增不减，而删除入口藏得又深 */
    fun putSavedVinyl(ctx: Context, name: String, json: String) {
        val list = savedVinyls(ctx).toMutableList()
        val i = list.indexOfFirst { it.name == name }
        if (i >= 0) list[i] = SavedVinyl(name, json) else list.add(SavedVinyl(name, json))
        setSavedVinyls(ctx, list)
    }

    fun removeSavedVinyl(ctx: Context, name: String) {
        setSavedVinyls(ctx, savedVinyls(ctx).filterNot { it.name == name })
    }

    /**
     * 当前生效的那张彩胶**叫什么名字**。
     *
     * null 有两种情况，界面上都显示成「自定义」：正在用内置预设，
     * 或者用的是**加名字之前**存下的那张老设计。
     */
    fun vinylCustomName(ctx: Context): String? = sp(ctx).getString("vinyl_custom_name", null)

    fun setVinylCustomName(ctx: Context, v: String?) =
        sp(ctx).edit().apply {
            if (v == null) remove("vinyl_custom_name") else putString("vinyl_custom_name", v)
        }.apply()

    // ---- 实验开关 ----

    /**
     * 在途 URB 深度。默认 32 —— 实测能消除断音的最小值。
     *
     * 它同时决定抗调度卡顿的余量（=深度 ms）和 seek 后旧音频的尾巴长度，
     * 两者绑死。卡顿余量不够时往上调，觉得 seek 迟钝就往回调。
     */
    fun urbCount(ctx: Context): Int = sp(ctx).getInt("urb_count", 32)
    fun setUrbCount(ctx: Context, v: Int) = sp(ctx).edit().putInt("urb_count", v).apply()

    /** 是否跟随设备的 feedback 速率。异步 DAC 必须跟随，留开关只为 A/B 对比。 */
    fun followFeedback(ctx: Context): Boolean = sp(ctx).getBoolean("follow_feedback", true)
    fun setFollowFeedback(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("follow_feedback", v).apply()

    /** seek 时是否立刻丢弃在途数据（去掉旧音频尾巴，代价是一小段静音） */
    fun flushQueueOnSeek(ctx: Context): Boolean =
        sp(ctx).getBoolean("flush_on_seek", false)

    fun setFlushQueueOnSeek(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("flush_on_seek", v).apply()

    /**
     * 无缝切歌（同速率时不停流，解码器热换源）。
     *
     * ⚠ **尚未实现**，先占位。列表里显示为灰色，避免用户以为开了没效果。
     */
    fun gapless(ctx: Context): Boolean = sp(ctx).getBoolean("gapless", false)
    fun setGapless(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("gapless", v).apply()

    /**
     * 输出方式。
     *
     *  - `false`（**默认**）= **系统音频**：走 Android 常规通路（AAudio），
     *    蓝牙 / 普通有线耳机 / 扬声器都能用，**不插小尾巴也能听**。
     *    代价是**不是 bit-perfect** —— 中间经过 AudioFlinger 混音，
     *    蓝牙时还有一层**真有损的编码**（SBC/AAC/LDAC）。
     *  - `true` = **USB 直连**：独占小尾巴，bit-perfect。
     *    **DSD 只能走这条**（DSD 转 PCM 是有损的，所以那条路明确拒绝 DSD）。
     *
     * ★ 默认值刻意是"系统音频"—— 不追求 hifi 也能用，这是产品决定，不是权宜之计。
     *
     * ★ 改这个开关会让 `DeviceGate` 收掉当前的原生上下文重新建一个
     *   （USB 的和无设备的是两种上下文），所以**最好不要在播放中切**。
     */
    fun usbDirect(ctx: Context): Boolean = sp(ctx).getBoolean("usb_direct", false)
    fun setUsbDirect(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("usb_direct", v).apply()

    /** 输出字节转储（bit-perfect 验证用） */
    fun outputDump(ctx: Context): Boolean = sp(ctx).getBoolean("output_dump", false)
    fun setOutputDump(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("output_dump", v).apply()

    // ---- 界面偏好 ----

    /**
     * 正在播放页底部显示的是「歌词」还是「源」。
     *
     * ★ 存下来是因为那是个**切换**而不是一次性动作 —— 习惯看歌词的人
     *   每次进正在播放页都要再点一次，很烦。
     */
    /**
     * 封面母版去重做到哪一版了。**0 = 没做过**。
     *
     * ★ 只在**完整成功**之后才写 —— 它是"下次还要不要再跑一遍"的唯一判据，
     *   半途而废就写上的话，那批封面会永远停在"文件改了名、指针是旧的"。
     */
    fun coverDedupeVersion(ctx: Context): Int = sp(ctx).getInt("cover_dedupe_version", 0)
    fun setCoverDedupeVersion(ctx: Context, v: Int) = sp(ctx).edit().putInt("cover_dedupe_version", v).apply()

    fun showLyrics(ctx: Context): Boolean = sp(ctx).getBoolean("show_lyrics", false)

    fun setShowLyrics(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("show_lyrics", v).apply()

    // ---- 统计 ----

    /**
     * 累计收听时长（毫秒）。
     *
     * ★ 只负责**存**。什么时候算、什么时候落盘由 [PlayerSession] 决定 ——
     *   只有它知道"此刻是不是真的在放"。
     *
     * ★★ 落盘频率是这块唯一要紧的事：**必须低频**（每曲一次 + 暂停一次
     *    + 服务销毁），绝不能跟着 200ms 心跳写。这条写在调用方那边，
     *   这里留一句是因为**改这块的人多半会先看这里**。
     */
    fun listenedMs(ctx: Context): Long = sp(ctx).getLong("listened_ms", 0L)

    fun setListenedMs(ctx: Context, v: Long) =
        sp(ctx).edit().putLong("listened_ms", v).apply()

    // ---- 无线传输 ----

    /*
     * ★ 这里原来有个 `wireless_on` 持久化标志，已删除。
     *
     *   它的问题是**没人兑现**：App 重启后没有任何地方按它把服务拉起来，
     *   于是设置里显示"已开启"而 8765 根本没在监听。用户想打开，
     *   点一下反而走的是"关闭"分支，得点两次。
     *
     *   现在"开没开"只问 `WirelessServer.isRunning()` —— 服务状态是
     *   唯一事实来源，不存第二份会过期的副本。
     *   端口倒是该存，它跟进程生死无关。
     */

    fun wirelessPort(ctx: Context): Int = sp(ctx).getInt("wireless_port", 8765)
    fun setWirelessPort(ctx: Context, v: Int) = sp(ctx).edit().putInt("wireless_port", v).apply()

    /**
     * 调试端口（只读）。**默认关**。
     *
     * ★ 和「无线传输」分开一个开关，是刻意的：那个是日常功能（传歌用），
     *   这个是开发时用的。绑在一起的话，用户为了传歌打开无线传输，
     *   就顺手把屏幕内容也暴露到局域网上了。
     *
     * 打开后同网段任何人都能读到当前界面截图和播放状态，用完请关掉。
     */
    fun debugEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("debug_on", false)
    fun setDebugEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("debug_on", v).apply()

    fun debugPort(ctx: Context): Int = sp(ctx).getInt("debug_port", 8766)
    fun setDebugPort(ctx: Context, v: Int) = sp(ctx).edit().putInt("debug_port", v).apply()
}
