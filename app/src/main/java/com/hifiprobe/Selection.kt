package com.hifiprobe

/**
 * 多选状态 —— 六个列表页共用的一套。
 *
 * ★ 只认**键**，不认对象也不用下标。
 *
 *   下标是绝对不行的：这些列表会因为心跳重画、排序、导入、外部删除而重排，
 *   存下标的话"我勾中的那首"会悄悄变成另一首 —— 而且用户点删除时完全
 *   看不出自己删错了。键是稳定标识，重排多少次都对得上。
 *
 *   键怎么取由各页面决定：曲目用 uri，文件夹用库内相对路径，
 *   专辑用专辑名，歌单用 id。
 *
 * ★ 它只管"选了哪些"，不管界面长什么样。界面（顶栏换装、底部操作栏、
 *   行首勾选圈）由各页面自己画 —— 那部分各页差异太大，硬抽一个通用
 *   容器只会变成一堆 if。
 */
class Selection {

    private val keys = LinkedHashSet<String>()

    /** 是否处在多选模式。注意它和"选了几项"是两回事：可以进了多选还没选任何东西 */
    var active: Boolean = false
        private set

    val size: Int get() = keys.size
    val isEmpty: Boolean get() = keys.isEmpty()

    fun start() {
        active = true
        keys.clear()
    }

    fun stop() {
        active = false
        keys.clear()
    }

    fun contains(key: String): Boolean = key in keys

    /** 切换一项，返回切换后是否选中 */
    fun toggle(key: String): Boolean =
        if (keys.remove(key)) false else {
            keys.add(key)
            true
        }

    fun add(key: String) {
        keys.add(key)
    }

    /**
     * 整组选中 / 取消。文件夹、专辑这类"容器"用这个。
     *
     * ★ 容器在界面上显示的是**全选 / 半选 / 未选**三态，但状态里只记
     *   "叶子选了哪些"。不记容器的选中态，是因为那会制造第二份真相 ——
     *   文件夹里的歌被别处删掉一首，容器的选中态就和实际对不上了。
     */
    fun setGroup(group: Collection<String>, on: Boolean) {
        if (on) keys.addAll(group) else keys.removeAll(group)
    }

    /** 这一组是不是**全部**被选中了。勾选圈画实心还是空心看它 */
    fun containsAll(group: Collection<String>): Boolean =
        group.isNotEmpty() && keys.containsAll(group)

    /** 这一组里选中了几项。用来画半选态和计数 */
    fun countIn(group: Collection<String>): Int = group.count { it in keys }

    /** 当前的选中集合。返回拷贝，交给后台线程用也不会被改到 */
    fun snapshot(): Set<String> = LinkedHashSet(keys)
}
