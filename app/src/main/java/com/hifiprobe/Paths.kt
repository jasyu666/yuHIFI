package com.hifiprobe

import java.io.File

/**
 * 路径校验。
 *
 * ★ 全工程**只有这一处**判断"某个路径是不是在某个根里面"。
 *
 *   这个判断极易写错，而且错了不一定看得出来 —— `WirelessServer` 和
 *   `Library` 曾经各写了一份，两份都是错的（少了分隔符）。与其"记得别写错"，
 *   不如让它只有一个地方可以写。
 */
object Paths {

    /**
     * [f] 是不是真的在 [root] **里面**。
     *
     * ★★ 不能用 `f.canonicalPath.startsWith(root.canonicalPath)` ——
     *    那是**字符串前缀**，不是**路径前缀**：
     *
     * ```
     * root = /…/files/library
     * 目标 = /…/files/libraryX/secret      ← 不在库里
     * 目标 = /…/files/library.json        ← 是索引文件，也不在库里
     * ```
     *
     * 这两个都会让 `startsWith` 返回 **true**。
     *
     * 在 `WirelessServer` 里它能串成一条完整的逃逸链（已修）：
     * ```
     * /mkdir    name=../libraryX     → 在库外建目录
     * /upload   dir=../libraryX      → 把文件写进库外
     * /download p=../libraryX/xxx    → 读库外文件
     * /delete   path=../libraryX     → 递归删库外目录
     * ```
     *
     * @return 完全相等（root 自己）或确实在 root 之下
     */
    fun inside(root: File, f: File): Boolean {
        val r = runCatching { root.canonicalPath }.getOrNull()
            ?.trimEnd(File.separatorChar) ?: return false
        val p = runCatching { f.canonicalPath }.getOrNull() ?: return false
        return p == r || p.startsWith(r + File.separator)
    }
}
