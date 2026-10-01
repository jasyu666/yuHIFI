package com.hifiprobe

import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

/**
 * 「加到歌单…」的选单。
 *
 * ★ 抽出来是因为曲目列表、专辑、音乐库三处都要它。抄三遍的话，
 *   "没有歌单时该怎么办"这种分支只会在一处被想到 —— 另外两处就成了死路。
 *   这里统一处理：没有歌单就顺势引导建一个，别让用户点了没反应。
 */
object PlaylistPicker {

    fun show(
        activity: AppCompatActivity,
        tracks: List<Track>,
        onDone: ((String) -> Unit)? = null
    ) {
        if (tracks.isEmpty()) return
        val lists = Playlists.all()

        // 一个歌单都没有 → 直接进新建，不摆一个空列表
        if (lists.isEmpty()) {
            askNewThenAdd(activity, tracks, onDone, title = "还没有歌单，先建一个")
            return
        }

        /*
         * ★★ 列表里**必须留一个「新建歌单」入口**。
         *
         *   原来只在"一个歌单都没有"时才引导新建 —— 已经有歌单之后这条路就没了，
         *   用户想把这批歌放进一个新歌单，只能先退出去建好再回来重选。
         *   （用户 2026-09-29 报的就是这个："添加歌曲进歌单时没有新建歌单选项"）
         *
         * ★ 放在**第一项**：用户抱怨的正是"找不到它"，压在长列表末尾等于没解决。
         */
        val names = buildList {
            add("＋ 新建歌单…")
            lists.forEach { add("${it.name}（${it.size} 首）") }
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle(if (tracks.size == 1) "加到哪个歌单" else "把 ${tracks.size} 首加到哪个歌单")
            .setItems(names) { _, which ->
                if (which == 0) {
                    askNewThenAdd(activity, tracks, onDone)
                    return@setItems
                }
                val p = lists[which - 1]      // ★ 减 1：第 0 项是「新建」
                val n = Playlists.add(p.id, tracks)
                // 如实说"一首都没加" —— 全都重复时静默成功，用户会以为加漏了
                val msg = if (n == 0) "这些歌都已经在「${p.name}」里了"
                else "「${p.name}」已加入 $n 首"
                toast(activity, msg)
                onDone?.invoke(msg)
            }
            .show()
    }

    /**
     * 弹输入框 → 建歌单 → 把曲目加进去。
     *
     * ★ 「一个歌单都没有」和「用户主动选了新建」走的是同一条路 ——
     *   建单的逻辑只留这一份，两处的成功/失败口径不会跑偏。
     */
    private fun askNewThenAdd(
        activity: AppCompatActivity,
        tracks: List<Track>,
        onDone: ((String) -> Unit)?,
        title: String = "新建歌单"
    ) {
        val input = EditText(activity).apply { hint = "歌单名" }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("创建并加入") { _, _ ->
                val p = Playlists.create(input.text.toString())
                if (p == null) {
                    toast(activity, "名字为空或已存在")
                } else {
                    val n = Playlists.add(p.id, tracks)
                    val msg = "「${p.name}」已加入 $n 首"
                    toast(activity, msg)
                    onDone?.invoke(msg)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(a: AppCompatActivity, m: String) =
        Toast.makeText(a, m, Toast.LENGTH_SHORT).show()
}
