package com.hifiprobe

import android.content.Context
import kotlin.math.min

/**
 * 响度平衡的取数与决策。
 *
 * 测量结果在 [LoudnessStore] 里只有绝对值；这里把"目标值"带进来，
 * 算出这一首实际该用多少分贝。**改目标不需要重新测量** —— 只是再算一次。
 */
object LoudnessPolicy {

    /** 真峰值上限：EBU R128 建议的 −1 dBTP */
    const val TRUE_PEAK_CEILING_DB = -1.0

    enum class Source { TRACK, ALBUM }

    data class Decision(
        val gainDb: Float,
        val targetLufs: Float,
        val measuredLufs: Double,
        val truePeakDb: Double,
        val source: Source,
        /** 想要提得更多，但被真峰值上限拦住了 */
        val clamped: Boolean
    )

    /** DSD 走原生位流，不经过 PCM 域，明确排除 */
    fun isDsd(track: Track): Boolean {
        val ext = track.localPath?.substringAfterLast('.', "")?.lowercase()
            ?: track.uri.substringAfterLast('.', "").lowercase()
        return ext == "dsf" || ext == "dff"
    }

    /**
     * 响度平衡的启用条件。
     *
     * ★★ **不再依赖音量控制**：音量控制是给"没有硬件音量的直连设备"
     *    准备的软件音量；响度平衡是独立的处理层，自己就能工作。
     */
    fun enabled(ctx: Context): Boolean = Settings.loudnessEnabled(ctx)

    /**
     * 自动模式下，这一首是否按专辑处理。
     *
     * 规则：**曲目数 < 用户设的上限**才算专辑。
     * 沿用 [Library.albumOf] 的既有分组 —— 和专辑页看到的完全一致。
     */
    fun albumFor(ctx: Context, track: Track): Library.Album? {
        if (Settings.loudnessMode(ctx) != Settings.LOUDNESS_MODE_AUTO) return null
        val album = Library.albumOf(track) ?: return null
        val limit = Settings.loudnessAlbumMaxTracks(ctx)
        return if (album.size < limit) album else null
    }

    /**
     * 当前这一首应该用多少增益。返回 null 表示"还没有可用的测量"或"这个功能不适用"。
     *
     * ★ 专辑测量优先；专辑还没测好时退回单曲值当**临时桥**，
     *   等专辑结果到了再由 PlayerSession 斜坡切过去。
     */
    fun decide(ctx: Context, track: Track): Decision? {
        if (!enabled(ctx)) return null
        if (isDsd(track)) return null

        val target = Settings.loudnessTargetLufs(ctx)

        val album = albumFor(ctx, track)
        if (album != null) {
            val sig = LoudnessStore.albumSig(album)
            val am = LoudnessStore.getAlbum(album.name, sig)
            if (am != null) {
                return make(am.lufs, am.truePeakDb, target, Source.ALBUM)
            }
        }

        val tm = LoudnessStore.getTrack(track) ?: return null
        return make(tm.lufs, tm.truePeakDb, target, Source.TRACK)
    }

    private fun make(
        lufs: Double,
        truePeakDb: Double,
        targetLufs: Float,
        source: Source
    ): Decision {
        val desired = targetLufs - lufs
        val cap = TRUE_PEAK_CEILING_DB - truePeakDb
        val applied = min(desired, cap)
            // 防御性夹取：真峰值缺失/异常时也别把增益推到离谱的位置
            .coerceIn(-80.0, 24.0)

        return Decision(
            gainDb = applied.toFloat(),
            targetLufs = targetLufs,
            measuredLufs = lufs,
            truePeakDb = truePeakDb,
            source = source,
            clamped = desired > cap + 0.001
        )
    }
}
