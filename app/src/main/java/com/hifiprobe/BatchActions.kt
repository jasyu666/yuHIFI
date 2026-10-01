package com.hifiprobe

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 多选之后能做的几件事 —— 各列表页共用。
 *
 * 抽出来的理由和 [PlaylistPicker] 一样：这些操作散在六个页面里各写一遍的话，
 * "删除前要二次确认""移动到同一个文件夹要拦住"这类判断只会在一两处被想到，
 * 另外几处就成了能误删文件的入口。
 *
 * ★ 每个操作都**如实报告结果**：删了 3 首里有 1 首失败，就要说"删了 2 首"，
 *   不能因为大部分成功就报"已完成"。用户是靠这个判断库里还剩什么的。
 */
object BatchActions {

    /** 加到歌单。已经有歌单就直接选，没有就先引导建一个 */
    fun addToPlaylist(act: AppCompatActivity, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        PlaylistPicker.show(act, tracks)
    }

    /**
     * 移动。
     *
     * ★ 勾中的**文件夹**和**歌**走的是两条不同的路：
     *     歌   → 平铺着挪过去
     *     文件夹 → 整棵子树搬过去，内部结构保持不动
     *   都按"平铺"处理的话，用户精心按专辑分好的目录会被拍平成
     *   一堆歌堆在一个文件夹里 —— 而且这个损失是不可逆的。
     *
     * 列的是**库里所有目录**，不只是当前这一层：从「所有歌曲」进来的话
     * 根本没有"当前目录"这个概念，只列同级目录会让人无处可去。
     */
    fun moveTo(
        act: AppCompatActivity,
        tracks: List<Track>,
        folders: List<String>,
        onDone: () -> Unit
    ) {
        if (tracks.isEmpty() && folders.isEmpty()) return
        val dirs = buildList {
            add("" to "（音乐库根目录）")
            Library.folders().forEach { add(it to it) }
        }
        val what = buildString {
            if (tracks.isNotEmpty()) append("${tracks.size} 首")
            if (folders.isNotEmpty()) {
                if (isNotEmpty()) append("、")
                append("${folders.size} 个文件夹")
            }
        }
        AlertDialog.Builder(act)
            .setTitle("将 $what 移动至")
            .setItems(dirs.map { it.second }.toTypedArray()) { _, which ->
                val to = dirs[which].first
                // 先搬文件夹再搬散歌：文件夹搬走之后，原本在它里面的歌
                // 已经跟着走了，不会被第二次挪动碰到
                val nf = Library.moveFolders(folders, to)
                val ns = Library.moveTracks(tracks, to)
                val msg = buildString {
                    if (ns > 0) append("已移动 $ns 首")
                    if (nf > 0) {
                        if (isNotEmpty()) append("、")
                        append("$nf 个文件夹")
                    }
                    if (isEmpty()) append("没有可移动的项（可能已在目标位置）")
                }
                toast(act, msg)
                onDone()
            }
            .show()
    }

    /**
     * 删除音乐文件。**不可逆**，所以确认框里写清楚几首。
     *
     * 按钮不叫"确定"叫"删除"，标题里带首数 —— 批量操作的确认框如果只说
     * "确定要删除吗"，用户根本不知道自己勾了几首。
     */
    fun deleteFiles(act: AppCompatActivity, tracks: List<Track>, onDone: () -> Unit) {
        if (tracks.isEmpty()) return
        AlertDialog.Builder(act)
            .setTitle("删除 ${tracks.size} 首？")
            .setMessage(
                "文件将从音乐库中彻底删除，无法恢复。\n" +
                        "引用这些曲目的歌单将一并移除相应条目。"
            )
            .setPositiveButton("删除") { _, _ ->
                val n = Library.deleteAll(tracks)
                toast(act, if (n == tracks.size) "已删除 $n 首" else "已删除 $n 首（${
                    tracks.size - n
                } 首失败）")
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 重命名对话框。移动/删除都是不可逆或半不可逆的，唯独改名要能随便改回来 */
    fun rename(act: AppCompatActivity, title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(act).apply { setText(initial) }
        AlertDialog.Builder(act)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(a: AppCompatActivity, m: String) =
        Toast.makeText(a, m, Toast.LENGTH_SHORT).show()
}
