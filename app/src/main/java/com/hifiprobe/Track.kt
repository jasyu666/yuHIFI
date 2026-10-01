package com.hifiprobe

import android.net.Uri

/**
 * 队列里的一首曲目。
 *
 * 只存**足够用来播放和显示**的东西，不存解码器状态 —— 那是原生层的事。
 *
 * 关于 uri：P1 阶段是 SAF 给的 `content://`；P2 建了私有文件库之后，
 * 库内文件会走 `file://`。两种都能用 ContentResolver / 直接路径打开，
 * 所以这里统一存字符串，由播放侧判断怎么开。
 */
data class Track(
    val uri: String,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,

    /*
     * 规格。**允许为 0（未知）** —— 扫描时逐首开 FFmpeg 探测太慢，
     * 列表先用文件名/标签把界面填上，等真正播放时引擎会给出精确值
     * （nativeEngineInfo），那时再回填到这一首上。
     *
     * 「未知」和「已知但为 0」必须能区分，否则界面会显示 "0Hz"。
     */
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,

    /** 编码名（flac / alac / mp3…），来自原生探测，列表上显示 */
    val codec: String = "",

    /**
     * 封面图在磁盘上的路径（app 私有缓存）。
     *
     * ★ 存**路径**而不是 Bitmap：列表可能同时显示几十张封面，
     *   全留在内存里很快就 OOM。图片按需从磁盘读、由 RecyclerView
     *   的复用机制自然回收。
     *   为空表示没有内嵌封面 —— 界面回落到默认的黑胶图标。
     */
    val coverPath: String = "",

    /** 库内文件的大小与修改时间，用来判断缓存是否过期 */
    val fileSize: Long = 0L,
    val fileModified: Long = 0L
) {
    /** 标题为空时回落到文件名，保证界面上永远有东西可显示 */
    val displayTitle: String
        get() = title.ifBlank { uri.substringAfterLast('/').substringBeforeLast('.') }

    /** 是不是 app 私有库里的文件（用于区分"库内"与"外部 SAF 引用"） */
    val isLocalFile: Boolean get() = uri.startsWith("file://")

    /** 本地文件路径；非 file:// 的返回 null */
    val localPath: String?
        get() = if (isLocalFile) Uri.decode(uri.removePrefix("file://")) else null

    val displayArtist: String
        get() = artist?.takeIf { it.isNotBlank() } ?: "未知艺术家"

    /** "44.1k / 24bit"；规格未知时返回 null，由界面决定显示什么 */
    val specLabel: String?
        get() {
            if (sampleRate <= 0 || bitDepth <= 0) return null
            val khz = if (sampleRate % 1000 == 0) "${sampleRate / 1000}k"
            else String.format("%.1fk", sampleRate / 1000.0)
            return "$khz / ${bitDepth}bit"
        }

    /** 规格未知时也要能显示，用占位符避免界面跳动 */
    val specLabelOrDefault: String
        get() = specLabel ?: "—"

    /** 拿播放后探测到的精确规格回填。只补空缺，不覆盖已知值。 */
    fun withSpec(rate: Int, bits: Int, ch: Int): Track =
        if (sampleRate == rate && bitDepth == bits && channels == ch) this
        else copy(sampleRate = rate, bitDepth = bits, channels = ch)

    fun withDuration(ms: Long): Track =
        if (ms <= 0 || durationMs == ms) this else copy(durationMs = ms)
}
