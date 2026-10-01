package com.hifiprobe

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * 无线传输 —— 在 App 内起一个极简 HTTP 服务，电脑浏览器打开就能管库。
 *
 * ★ 为什么手写 `ServerSocket` 而不引第三方库（NanoHTTPD 之类）：
 *   我们要的只是"上传文件 + 列目录 + 删除"三件事，一个第三方 HTTP 框架
 *   带来的依赖、体积和升级负担远超收益。而且**上传要直接流式落盘** ——
 *   一首 DSD 几百 MB，先读进内存再写文件会直接把 App 撑爆，
 *   这部分无论如何都得自己写。
 *
 * 只监听局域网，不做鉴权 —— 和手机的 FTP/文件传输类 App 一个定位。
 * 用完请关掉（界面上有开关）。
 */
object WirelessServer {

    private const val TAG = "HiFiWireless"

    /** 接收线程和 [stop] 都会读写，必须 volatile */
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun start(ctx: Context): Boolean {
        if (running) return true
        val port = Settings.wirelessPort(ctx)
        return try {
            val ss = ServerSocket(port)
            serverSocket = ss
            running = true
            acceptThread = Thread({
                while (running && serverSocket === ss) {
                    val s = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break          // 套接字被关掉 = 正常收工
                    }
                    pool.execute { handle(s) }
                }
                /*
                 * ★ 循环退出就把标志落回 false。
                 *
                 *   不落的话 `isRunning()` 会一直说 true —— 而设置页那个开关
                 *   现在正是读它来显示「已开启 / 已关闭」的。accept() 一旦因为
                 *   非 stop() 的原因抛异常（网络切换之类），开关就又开始撒谎，
                 *   那这次修改就白做了。
                 *
                 * ★ 但只有"我还是当前那个套接字"时才收回 —— 否则
                 *   stop() 紧接着 start()（改端口那条路）会有竞态：
                 *   旧线程慢一拍退出，把新服务的 running 又改回 false。
                 */
                if (serverSocket === ss) {
                    running = false
                    serverSocket = null
                }
            }, "wireless-accept").apply { isDaemon = true; start() }
            Log.i(TAG, "无线传输已启动，端口 $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动失败: ${e.message}")
            running = false
            false
        }
    }

    fun stop(ctx: Context) {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    /** 界面显示给用户看的地址 */
    fun url(ctx: Context): String? {
        val ip = localIp() ?: return null
        return "http://$ip:${Settings.wirelessPort(ctx)}"
    }

    /**
     * 取本机在局域网里的 IPv4。
     *
     * ★ 必须**按接口名排序**，不能直接取第一个。
     *
     *   枚举顺序由系统决定，**不保证 WiFi 排在移动数据前面**。手机开着移动数据时
     *   `rmnet*` 也有 IPv4（运营商内网地址），它排前面的话，设置页会显示一个
     *   电脑**根本打不开**的地址 —— 而服务其实是好的，用户只会以为坏了。
     *
     *   这类现象以前被当成"网段一会儿一个样"，其实是取错了网卡。
     */
    private fun localIp(): String? = runCatching {
        fun rank(name: String): Int = when {
            name.startsWith("wlan") -> 0                              // 正常走这个
            name.startsWith("eth") -> 1                               // 有线网卡
            name.startsWith("ap") || name.startsWith("swlan") -> 2    // 手机自己开热点
            name.startsWith("rmnet") -> 4                             // 移动数据：电脑连不上
            else -> 3
        }
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { rank(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    // ------------------------------------------------------------------

    private fun handle(socket: Socket) {
        socket.use { s ->
            /*
             * 两次读之间的等待上限。
             *
             * 上传是持续推流，正常间隔是毫秒级；15 秒对慢速链路偏紧，
             * 放宽到 60 秒。断线照样能检测到，而且现在 `.part` 会被正确清掉，
             * 不会留下半个文件顶掉原文件。
             */
            s.soTimeout = 60_000
            runCatching {
                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val requestLine = readLine(input) ?: return@runCatching
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@runCatching
                val method = parts[0]
                val path = parts[1]

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] =
                        line.substring(i + 1).trim()
                }

                /*
                 * ★★ 每个请求记一行 —— 「浏览器报错、但服务端什么都不知道」时
                 *    这是**唯一**的判据。
                 *
                 *   Chrome 的 net::ERR_FILE_NOT_FOUND / ERR_ACCESS_DENIED 都是
                 *   **客户端在把本地文件读出来之前就失败了**，请求根本没到服务端；
                 *   而"服务端拒绝了"完全是另一回事。没有这行日志，这两种情况在
                 *   报告里长得一模一样 —— 只能靠猜。实测就卡在这儿。
                 *
                 *   POST 再带上 Content-Length：有它说明请求已经开推，
                 *   没推到就是发送途中断的（那更可能是文件读不出来）。
                 */
                val declaredLen = headers["content-length"]
                Log.i(TAG, "→ $method $path" +
                        if (declaredLen != null) "  Content-Length=$declaredLen" else "")

                val out = s.getOutputStream()
                when {
                    method == "GET" && path.startsWith("/download") -> serveFile(out, path)
                    // 提示直接跟着这次请求的 URL 走，不再存进程级字段
                    method == "GET" -> servePage(out, path)
                    method == "POST" && path == "/upload" -> handleUpload(input, headers, out)
                    method == "POST" && path == "/delete" -> {
                        val body = readBody(input, headers)
                        val target = formValue(body, "path")
                        val ok = target != null && deleteEntry(target)
                        /*
                         * ★ 删完必须刷索引。页面上那份列表是现扫目录，所以立刻就对；
                         *   但 App 里的「音乐库 / 所有歌曲」读的是**索引** ——
                         *   不刷新的话，网页上删掉的歌在 App 里还留着一条幽灵记录，
                         *   点它会播放失败，要等下一次上传（或重启）才掉。
                         */
                        if (ok) runCatching { Library.refresh() }
                        val shown = target?.trimEnd('/')?.substringAfterLast('/').orEmpty()
                        redirect(out, if (ok) "/?msg=已删除「$shown」" else "/?msg=删除失败")
                    }
                    method == "POST" && path == "/mkdir" -> {
                        val body = readBody(input, headers)
                        val name = formValue(body, "name")?.trim()
                        val msg = if (name.isNullOrBlank()) {
                            "文件夹名不能为空"
                        } else {
                            val dir = File(Library.root, name)
                            when {
                                // ★ 必须用 inside()，字符串前缀会让 ../libraryX 溜到库外
                                !Paths.inside(Library.root, dir) -> "路径不合法"

                                /*
                                 * ★ 和上传的「不允许同名目录」是同一条规则。
                                 *   原来这里只是 mkdirs() 返回 false → 报一句含糊的
                                 *   「新建失败」，用户根本不知道是重名。
                                 */
                                dir.exists() -> "目录「${dir.name}」已存在 —— 换个名字"

                                dir.mkdirs() -> {
                                    runCatching { Library.refresh() }
                                    "已新建文件夹「${dir.name}」"
                                }
                                else -> "新建失败"
                            }
                        }
                        redirect(out, "/?msg=$msg")
                    }
                    else -> respond(out, 404, "text/plain", "not found")
                }
            }.onFailure { Log.w(TAG, "处理请求出错: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    //  上传：流式解析 multipart，边收边落盘
    // ------------------------------------------------------------------

    /**
     * 上传。**保留目录结构**。
     *
     * ★ multipart 里每个文件的 filename 现在可能带相对路径
     *   （`专辑A/CD1/01.flac`）。客户端用
     *   `FormData.append('files', file, path)` 的第三个参数把路径塞进 filename
     *   —— 那是浏览器唯一肯让我们带路径的地方。
     *
     * ★ 两条规则：
     *   一、**不允许新建一个已存在的同名目录**。要往已有目录里放东西，必须
     *       把它选成「上传到」的目标 —— 那样文件是直接落进去的，不涉及
     *       "新建同名目录"。这样就不会出现 `专辑A (2)`、`专辑A (3)` 越套越深。
     *   二、**同名文件直接覆盖**。
     *
     * ★ 覆盖的前提是"先写临时文件、整套收完才改名"。直接往目标文件流式写的
     *   话，一首 500MB 的 DSD 传到 90% 断线，库里的原文件就被半个文件顶掉了。
     */
    private fun handleUpload(
        input: InputStream,
        headers: Map<String, String>,
        out: OutputStream
    ) {
        val ctype = headers["content-type"] ?: return uploadReply(out, "缺少 Content-Type")
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(ctype)?.groupValues?.get(1)
            ?: return uploadReply(out, "缺少 boundary")
        val total = headers["content-length"]?.toLongOrNull() ?: -1L

        val marker = "--$boundary".toByteArray()
        var saved = 0
        var targetDirRel = ""
        var newDirName: String? = null
        var destDir: File? = null
        var error: String? = null

        /** 这一轮**我们自己刚建出来**的目录（canonicalPath）。区分"早就存在"和"刚建的" */
        val createdDirs = HashSet<String>()

        /*
         * 用一个滑动缓冲扫流。
         *
         * 关键在于**不能把整个请求读进内存** —— 一首 DSD256 有几百 MB。
         * 所以每收到一块就和边界比对，命中之前的部分直接写进目标文件。
         */
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var consumed = 0L

        fun readMore(): Boolean {
            val want = if (total > 0) minOf(chunk.size.toLong(), total - consumed).toInt() else chunk.size
            if (want <= 0) return false
            val n = input.read(chunk, 0, want)
            if (n <= 0) return false
            buf.write(chunk, 0, n)
            consumed += n
            return true
        }

        var target: File? = null                    // 最终文件名
        var part: File? = null                      // 正在写的临时文件（名字 + ".part"）
        var currentOut: OutputStream? = null

        /**
         * 收掉当前这个文件。
         *
         * [commit] = false 表示这轮 multipart **没正常收完**（断线、异常）——
         * 那就把 `.part` 删掉，**原文件一个字节都不动**。
         * 这正是"覆盖"敢开的原因。
         */
        fun closeCurrent(commit: Boolean) {
            val os = currentOut
            runCatching { os?.flush() }
            runCatching { os?.close() }
            val p = part
            val t = target
            if (os != null && p != null && t != null) {
                if (commit) {
                    // 同一目录内的移动。ATOMIC_MOVE 在 NTFS 上是原子的
                    // （MoveFileEx + MOVEFILE_REPLACE_EXISTING，不是"先删再移"）；
                    // 个别文件系统不支持就退回普通移动
                    val moved = runCatching {
                        java.nio.file.Files.move(
                            p.toPath(), t.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE
                        )
                    }.recoverCatching {
                        java.nio.file.Files.move(
                            p.toPath(), t.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }.onFailure {
                        Log.w(TAG, "改名失败: ${it.message}")
                        runCatching { p.delete() }
                    }.isSuccess

                    /*
                     * ★ 计数放在**落盘成功**之后，不是 openTarget 成功之后。
                     *   传到一半断线的文件会被删掉，不该算进"已上传 N 个"。
                     */
                    if (moved) saved++ else if (error == null) error = "写入失败：${t.name}"
                } else {
                    runCatching { p.delete() }
                }
            }
            currentOut = null
            part = null
            target = null
        }

        /** 目标目录。`dir` / `newdir` 都在文件之前到达，所以只解析一次 */
        fun destNow(): File? {
            destDir?.let { return it }
            val nd = newDirName?.trim()
            val d = if (!nd.isNullOrEmpty()) {
                val seg = safeSegment(nd)
                if (seg == null) {
                    error = "文件夹名不合法：$nd"
                    return null
                }
                val f = File(Library.root, seg)
                if (f.exists()) {
                    error = "目录「$seg」已存在 —— 请把它选为「上传到」的目标再传，或换个文件夹名"
                    return null
                }
                if (!f.mkdirs()) {
                    error = "建不了目录「$seg」"
                    return null
                }
                createdDirs.add(f.canonicalPath)
                f
            } else {
                /*
                 * ★ 越界就**报错**，不要静默兜底回库根 ——
                 *   悄悄换个地方落盘，用户会以为文件传丢了。
                 */
                val r = resolveDir(targetDirRel)
                if (r == null) {
                    error = "上传目标目录不合法"
                    return null
                }
                r
            }
            destDir = d
            return d
        }

        /**
         * 按 multipart 里的 filename 开一个临时文件。
         *
         * 成功返回 true，`target` / `part` / `currentOut` 三件套就绪；
         * 失败返回 false，并把原因写进 `error`。
         */
        fun openTarget(rawName: String): Boolean {
            val dest = destNow() ?: return false
            val parts = safeParts(rawName)
            if (parts == null) {
                error = "文件名不合法：$rawName"
                return false
            }

            // 逐层建目录。★ 任何一层**早就存在**就拒绝 —— 见函数头的规则一
            var dir = dest
            for (c in parts.dropLast(1)) {
                val next = File(dir, c)
                if (next.isDirectory) {
                    if (next.canonicalPath !in createdDirs) {
                        error = "目录「$c」已存在 —— 请把它选为「上传到」的目标再传，或换个文件夹名"
                        return false
                    }
                } else if (next.exists()) {
                    error = "「$c」已经被一个同名文件占用了"
                    return false
                } else {
                    if (!next.mkdir()) {
                        error = "建不了目录「$c」"
                        return false
                    }
                    createdDirs.add(next.canonicalPath)
                }
                dir = next
            }

            val name = parts.last()
            val t = File(dir, name)
            /*
             * ★ 第二道防线：规范化之后必须仍在目标目录之内。
             *
             *   上面 safeParts 已经挡掉了 `..`，但这里还要再复核一次 ——
             *   两道防的是不同的事：safeParts 挡的是"能看出来的穿越"，
             *   这一道挡的是平台差异（软链接、保留名、大小写折叠）。
             *   只留一道都不够。
             */
            if (!Paths.inside(dest, t)) {
                error = "路径不合法：$rawName"
                return false
            }

            val p = File(dir, "$name.part")
            val stream = try {
                p.outputStream()
            } catch (e: Exception) {
                error = "写不了文件：${e.message}"
                return false
            }
            target = t
            part = p
            currentOut = stream
            return true
        }

        /** 只有看到收尾的 `--boundary--` 才算这套 multipart 完整收到 */
        var sawTerminator = false

        /*
         * 正文里要搜的分隔符 —— **含前导 CRLF**。
         *
         * ★ 为什么不直接搜 `--boundary`？
         *   正文和分隔符之间隔着一个 CRLF，而那个 CRLF **属于分隔符、
         *   不属于正文**。只搜 marker 的话，流式输出时无从判断这两个字节
         *   该不该写进文件 —— 写进去，每个文件末尾就凭空多两个字节，
         *   整个曲库的 bit-perfect 直接毁掉。
         *   把 CRLF 并进分隔符一起搜，这个歧义就不存在了。
         */
        val delim = "\r\n--$boundary".toByteArray()
        val crlfcrlf = "\r\n\r\n".toByteArray()

        /*
         * ★★ 这里原来是"找到当前边界、再找到下一个边界，然后一次性处理整个 part"。
         *
         *   那个写法有个必然结果：**整个文件都攒在内存里**，而且每轮循环
         *   `buf.toByteArray()` 把整个缓冲全量复制一遍 —— 40MB 的文件要
         *   memcpy 几十 GB，一首几百 MB 的 DSD 直接 OOM。
         *   （注释当时写着"不能把整个请求读进内存"，实现却正好是反的。）
         *
         *   改成状态机：正文一确认就往盘上淌，内存占用与文件大小无关。
         */
        var partState = PS_SEEK
        var fieldName: String? = null

        try {
            while (true) {
                val data = buf.toByteArray()

                when (partState) {
                    // ---- 找一个 part 的开始 ----
                    PS_SEEK -> {
                        val mi = indexOf(data, marker, 0)
                        if (mi < 0) {
                            if (!readMore()) break
                            if (buf.size() > MAX_HEAD) { error = "找不到 multipart 边界"; break }
                            continue
                        }
                        var p = mi + marker.size
                        /*
                         * ★ 边界后面的两个字节可能是 `--`（整套结束），
                         *   也可能是 CRLF（下一个 part）。**没收到第二个字节之前
                         *   不能猜** —— 猜成 CRLF 就会去 HEADERS 等一个永远不来的
                         *   `\r\n\r\n`，最后撞上 MAX_HEAD 报"头部异常"。
                         *   虽然要正好切在那两个字节中间才会发生，但概率不是零。
                         */
                        if (p + 1 >= data.size) {
                            if (!readMore()) break
                            continue
                        }
                        // "--" 结尾表示整个 multipart 结束
                        if (data[p] == '-'.code.toByte() && data[p + 1] == '-'.code.toByte()) {
                            sawTerminator = true
                            break
                        }
                        if (p < data.size && data[p] == '\r'.code.toByte()) p++
                        if (p < data.size && data[p] == '\n'.code.toByte()) p++
                        buf.reset()
                        buf.write(data, p, data.size - p)
                        partState = PS_HEADERS
                    }

                    // ---- 收头部，决定这个 part 是文件还是普通字段 ----
                    PS_HEADERS -> {
                        val he = indexOf(data, crlfcrlf, 0)
                        if (he < 0) {
                            if (!readMore()) break
                            if (buf.size() > MAX_HEAD) { error = "multipart 头部异常"; break }
                            continue
                        }
                        /*
                         * ★ 必须按 UTF-8 解码，**不能**用 ISO-8859-1。
                         *
                         *   HTTP 规范里 header 默认是 Latin-1，但浏览器（和 curl）
                         *   往 `filename="..."` 里塞的是**原样 UTF-8 字节**。
                         *   按 Latin-1 读的话，「新专辑」会变成「æ°ä¸è¾」——
                         *   这个 bug 一直存在，只是以前中文文件名上传后就是乱码
                         *   没人注意；改成保留目录结构之后中文专辑名一抓一大把，
                         *   才暴露出来。
                         */
                        val headerText = String(data, 0, he, Charsets.UTF_8)
                        fieldName = NAME_RE.find(headerText)?.groupValues?.get(1)
                        val fileMatch = FILE_RE.find(headerText)?.groupValues?.get(1)

                        if (fileMatch != null && fileMatch.isNotBlank()) {
                            closeCurrent(true)
                            if (error == null && !openTarget(fileMatch) && error == null) {
                                error = "开不了目标文件：$fileMatch"
                            }
                            // ★ 即使开失败也要进 PS_BODY 把这段正文读完 ——
                            //   否则流会停在半个 part 上，后面全部错位
                            partState = PS_BODY
                        } else {
                            partState = PS_FIELD
                        }
                        buf.reset()
                        buf.write(data, he + 4, data.size - (he + 4))
                    }

                    // ---- 普通字段（dir / newdir）：body 只有几十字节，攒着就行 ----
                    PS_FIELD -> {
                        val di = indexOf(data, delim, 0)
                        if (di < 0) {
                            if (!readMore()) break
                            if (buf.size() > MAX_HEAD) { error = "字段内容异常"; break }
                            continue
                        }
                        // 同上：表单值是 UTF-8。按 Latin-1 读中文目录名会乱码
                        val value = String(data, 0, di, Charsets.UTF_8).trim()
                        when (fieldName) {
                            "dir" -> targetDirRel = value
                            "newdir" -> newDirName = value
                        }
                        buf.reset()
                        buf.write(data, di + delim.size, data.size - (di + delim.size))
                        partState = PS_AFTER
                    }

                    // ---- 文件正文：**流式**写盘 ----
                    PS_BODY -> {
                        val di = indexOf(data, delim, 0)
                        if (di < 0) {
                            /*
                             * ★★ 流式的全部秘密就在这几行。
                             *
                             *   把"绝不可能是分隔符开头"的部分立刻写盘，
                             *   只留最后 `delim.size - 1` 字节（分隔符的最长真前缀）。
                             *
                             *   · 不留尾巴 → 正好跨在两次读之间的分隔符会被漏掉，
                             *     那一块会被当成正文写进文件，后面全部错位
                             *   · 留多了   → 文件越大内存越大，又回到老问题
                             *
                             *   `delim.size - 1` 是可证明正确的长度：既然扫遍
                             *   整个 buf 都没找到 delim，那它就不可能起始于
                             *   `buf.size - (delim.size - 1)` 之前的位置。
                             *
                             * ★★ 冲盘必须**在 readMore 之前**。
                             *
                             *   readMore() 是往 buf 末尾追加的，而 buf.reset()
                             *   会把整个 buf 清掉。先读后冲的话，刚读进来的那
                             *   一块就被 reset 一起干掉了 —— data 是旧快照，
                             *   写回 buf 的也只有旧快照的尾巴。
                             *
                             *   实测症状：63MB 的文件只落盘 30MB，**正好丢一半**
                             *   （每两轮丢一个 64KB 块），而服务端还报"已上传 1 个文件"。
                             *   只看"传没传成功"根本发现不了。
                             */
                            val keep = delim.size - 1
                            val n = data.size - keep
                            if (n > 0) {
                                currentOut?.write(data, 0, n)
                                buf.reset()
                                buf.write(data, n, keep)
                            }
                            if (!readMore()) break
                            continue
                        }
                        currentOut?.write(data, 0, di)
                        closeCurrent(true)
                        buf.reset()
                        buf.write(data, di + delim.size, data.size - (di + delim.size))
                        partState = PS_AFTER
                    }

                    // ---- 分隔符之后：整套结束，还是下一个 part ----
                    PS_AFTER -> {
                        if (data.size < 2) {
                            if (!readMore()) break
                            continue
                        }
                        if (data[0] == '-'.code.toByte() && data[1] == '-'.code.toByte()) {
                            sawTerminator = true
                            break
                        }
                        var p = 0
                        if (data[p] == '\r'.code.toByte()) p++
                        if (p < data.size && data[p] == '\n'.code.toByte()) p++
                        buf.reset()
                        buf.write(data, p, data.size - p)
                        partState = PS_HEADERS
                    }
                }
            }
        } finally {
            /*
             * ★ 只有收尾标记出现才提交当前文件。
             *
             *   断线、异常、或者请求被截断 —— 都走不到那个标记，
             *   这时 .part 会被删掉，**目标位置的旧文件原封不动**。
             */
            closeCurrent(sawTerminator)
        }

        /*
         * 传进来的东西要让 App 里的音乐库也能看到。
         *
         * ★ 页面上那份文件列表是现扫目录（walkTopDown），所以一直是对的；
         *   但 App 里的「音乐库 / 所有歌曲」读的是**索引**，不刷新的话
         *   得重启 App 才出现。设置页那句"传完自动刷新"以前是假的。
         */
        /*
         * 有文件落盘就刷 —— **包括"中途出错但前面几个已经写进去了"** 的情况。
         * 那些文件是真实存在的，索引得跟上。
         */
        if (saved > 0) runCatching { Library.refresh() }

        uploadReply(
            out,
            if (error == null) {
                "已上传 $saved 个文件"
            } else if (saved > 0) {
                /*
                 * ★ 出错时要说清**已经写进去几个**。
                 *
                 *   这里**不做回滚**：覆盖掉的旧内容已经没了，回滚不了 ——
                 *   与其假装干净，不如如实报出当前状态，用户才知道该怎么办。
                 *   （页面 JS 有本地预检，正常走不到这条路，这是给 curl 之类的兜底。）
                 */
                "$error（已写入 $saved 个文件，其余未处理）"
            } else {
                error
            }
        )
    }

    /**
     * 上传的回应。
     *
     * ★ 这里**不重定向**。原来走 `redirect()`，而它只是把一个进程级字段
     *   `currentQueryMsg` 设好再 303 到 `/` —— 更糟的是页面 JS 又硬编码
     *   `location.href='/?msg=上传完成'`，把服务端的话整个盖掉了。
     *   结果是**服务端说什么用户都看不见**，"同名目录已存在"这类提示
     *   加了也白加。现在把消息放进响应体，由页面读了再跳。
     */
    private fun uploadReply(out: OutputStream, msg: String) {
        respond(out, 200, "text/plain; charset=utf-8", msg)
    }

    /**
     * 「上传到」那个下拉框选中的目录。
     *
     * 越界返回 null，由调用方去报错 —— **不要静默兜底回库根**，
     * 那样用户会以为文件传丢了。校验必须走 [inside]。
     */
    private fun resolveDir(rel: String): File? {
        if (rel.isBlank()) return Library.root
        val d = File(Library.root, rel)
        return if (Paths.inside(Library.root, d)) d.apply { mkdirs() } else null
    }

    /**
     * 把 multipart 里的 filename 拆成安全的路径段。
     *
     * ★ 这是**第一道**防线：根本不让危险的东西进来。
     *
     *   · `..` 会让路径逃出目标目录 —— 直接丢掉那一段
     *   · `.` 和空段没有意义，丢掉
     *   · `\ : * ? " < > |` 和 0x00-0x1f 在 NTFS 上要么直接落盘失败，
     *     要么**改变路径含义**（`C:` 是盘符、`\` 是分隔符）—— 换成下划线
     *
     * ★ 换掉旧实现：旧的是 `substringAfterLast('/')`，把路径整个砍掉。
     *   那确实安全，但也就没法保留目录结构了。取舍是"保留结构，
     *   但要自己做规范化" —— 所以下面 openTarget 里还有第二道复核。
     */
    private fun safeParts(raw: String): List<String>? {
        val parts = raw.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { it.replace(INVALID_CHARS, "_") }
            .filter { it.isNotEmpty() }
        return parts.ifEmpty { null }
    }

    /** 单个目录名（「新建」那个输入框）。**不允许出现任何路径分隔符** */
    private fun safeSegment(raw: String): String? =
        safeParts(raw)?.takeIf { it.size == 1 }?.first()

    /** NTFS 上的非法字符 + 全部控制字符 */
    private val INVALID_CHARS = Regex("[\\\\:*?\"<>|\\x00-\\x1f]")

    // ---- multipart 状态机的状态 ----
    private const val PS_SEEK = 0        // 找下一个 part 的边界
    private const val PS_HEADERS = 1     // 收头部，判断是文件还是普通字段
    private const val PS_FIELD = 2       // 普通字段的 body（dir / newdir）
    private const val PS_BODY = 3        // 文件正文，流式写盘
    private const val PS_AFTER = 4       // 分隔符之后：结束还是下一个 part

    /** 头部/字段的攒取上限。它们的体量本来就该是几百字节，超过就是异常输入 */
    private const val MAX_HEAD = 64 * 1024

    private val NAME_RE = Regex("name=\"([^\"]*)\"")
    private val FILE_RE = Regex("filename=\"([^\"]*)\"")

    // 路径校验统一走 [Paths.inside] —— 见那个文件的说明。
    // 以前这里有一份私有实现，Library 里还有另一份，两份都是错的。

    // ------------------------------------------------------------------
    //  页面
    // ------------------------------------------------------------------

    private fun serveFile(out: OutputStream, path: String) {
        val rel = path.substringAfter("/download?p=", "")
        val decoded = URLDecoder.decode(rel, "UTF-8")
        val f = File(Library.root, decoded)
        // ★ 必须用 inside()：字符串前缀会让 `p=../libraryX/xxx` 读到库外的文件
        if (!f.isFile || !Paths.inside(Library.root, f)) {
            respond(out, 404, "text/plain", "not found"); return
        }
        /*
         * 文件名带中文时，`filename="…"` 里塞原始 UTF-8 字节严格说是非法的
         * （HTTP 头按规范是 Latin-1）。补一个 RFC 5987 的 `filename*`：
         * 老浏览器读前者，新浏览器读后者，两边都拿得到正确的名字。
         */
        val ascii = f.name.replace(Regex("[^\\x20-\\x7e]"), "_").replace("\"", "'")
        val utf8 = java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: ${f.length()}\r\n" +
                    "Content-Disposition: attachment; filename=\"$ascii\"; " +
                    "filename*=UTF-8''$utf8\r\n\r\n")
                .toByteArray()
        )
        f.inputStream().use { it.copyTo(out, 64 * 1024) }
    }

    private fun servePage(out: OutputStream, path: String) {
        // 提示只来自本次请求的 URL —— 不存字段，两个客户端同时操作也不会串消息
        val msg = runCatching {
            URLDecoder.decode(path.substringAfter("?msg=", ""), "UTF-8")
        }.getOrNull().orEmpty()

        /*
         * 文件列表按**目录树**渲染。
         *
         * ★ 原来是 `walkTopDown()` 拍平了往表里塞 —— 传完一个专辑文件夹之后，
         *   里面几十首歌平铺成一长条，"哪些属于同一张专辑"完全看不出来。
         *   目录层级是用户上传时唯一的组织信息，列表必须把它显示出来。
         *
         * ★ 按"目录在前、文件在后，各自按名字排"呈现，缩进 = 层级。
         *   纯服务端渲染，不依赖 JS —— 列表是现扫目录的，刷新就是对的。
         */
        val root = Library.root
        val all = root.walkTopDown().filter { it != root }.toList()
        val files = all.filter { it.isFile }
        var totalBytes = 0L
        for (f in files) totalBytes += f.length()

        // 父目录（相对路径，根是 ""）→ 它的直接子项
        val tree = HashMap<String, MutableList<File>>()
        for (f in all) {
            val rel = f.relativeTo(root).path
            tree.getOrPut(rel.substringBeforeLast('/', "")) { mutableListOf() }.add(f)
        }

        /** 原始代码用的是整数 MB，小文件一律显示 "0 MB" —— 顺手修掉 */
        fun sizeText(n: Long): String =
            if (n < 1024 * 1024) "${(n + 1023) / 1024} KB"
            else String.format("%.1f MB", n / 1048576.0)

        /*
         * 删除按钮改用 `form` 属性关联，而不是把 <form> 直接套在按钮外面。
         *
         * ★ 因为目录的删除按钮要放进 <summary> 里（折叠那一行），
         *   而 <summary> 的内容模型只允许**短语内容** —— 塞 <form> 是不合规的。
         *   浏览器虽然照渲染，但"能跑但不合规"的东西迟早咬人。
         *   表单本体渲染在页面末尾，只有 hidden 输入，肉眼看不见。
         */
        var formSeq = 0
        val hiddenForms = StringBuilder()
        fun delButton(target: String): String {
            val id = "df${formSeq++}"
            hiddenForms.append(
                "<form id=$id method=post action=/delete>" +
                        "<input type=hidden name=path value=\"${esc(target)}\"></form>"
            )
            return "<button class=d type=submit form=$id>删</button>"
        }

        val rows = StringBuilder()
        fun render(dirRel: String) {
            val kids = tree[dirRel] ?: return
            for (d in kids.filter { it.isDirectory }.sortedBy { it.name.lowercase() }) {
                val drel = d.relativeTo(root).path
                /*
                 * ★ 折叠用 <details>/<summary> —— 浏览器原生，**零 JS**。
                 *   缩进也不再手算：嵌套的 details 天然一层缩进一层。
                 *
                 * ★★ **默认全折叠**（2026-09-25 用户要求）。
                 *   原来是 `<details open>`，每一层都展开 —— 真实库 202 个目录
                 *   全铺开，页面 909 KB，用户原话"多了以后不好选择"。
                 *   折叠之后顶层只有 7 个目录，一屏就放得下。
                 *   （想看内容点标题，一点都不费事；不好选才是真费事。）
                 */
                rows.append(
                    "<details><summary>📁 ${esc(d.name)}/ ${delButton(drel)}</summary>"
                )
                render(drel)
                rows.append("</details>")
            }
            for (f in kids.filter { it.isFile }.sortedBy { it.name.lowercase() }) {
                val rel = f.relativeTo(root).path
                val url = java.net.URLEncoder.encode(rel, "UTF-8")
                rows.append(
                    "<div class=row><span class=fname>${esc(f.name)}</span>" +
                            "<span class=n>${sizeText(f.length())}</span>" +
                            "<span class=act><a href=\"/download?p=$url\">下载</a> " +
                            "${delButton(rel)}</span></div>"
                )
            }
        }
        render("")

        /*
         * 「上传到」的目录选择：**折叠目录树 + 单选钮**。
         *
         * ★ 原来是 `<select>` 平铺 202 个 `<option>`。用户实测"多了以后不好选择"
         *   —— 2026-09-25 要求改成默认折叠的树。
         *   真实库**顶层只有 7 个目录**，折叠后一屏 7 行，而不是 202 行。
         *
         * ★★ 单选钮放在 `<summary>` **里面**，并且 `stopPropagation`：
         *     点**圆圈** = 选这个目录（不展开）
         *     点**名字** = 展开 / 收起（不改选择）
         *   不加 stopPropagation 的话，点一下圆圈会同时触发 summary 的默认行为，
         *   选目录顺带展开/收起，很难用。
         */
        val dirTree = StringBuilder()
        run {
            // 父路径 -> 直接子路径（都是相对库根的完整路径，用 / 分隔）
            val kids = HashMap<String, MutableList<String>>()
            val seenDir = HashSet<String>()
            for (p in Library.folders().sorted()) {
                var parent = ""
                for (seg in p.split("/")) {
                    if (seg.isEmpty()) continue
                    val full = if (parent.isEmpty()) seg else "$parent/$seg"
                    if (seenDir.add(full)) {
                        kids.getOrPut(parent) { mutableListOf() }.add(full)
                    }
                    parent = full
                }
            }

            fun radio(path: String, label: String) =
                "<label class=dr><input type=radio name=dir value=\"${esc(path)}\"" +
                        (if (path.isEmpty()) " checked" else "") +
                        " onclick=\"event.stopPropagation()\">${esc(label)}</label>"

            fun render(path: String) {
                val children = kids[path]?.sortedBy { it.substringAfterLast('/').lowercase() }
                for (child in children ?: emptyList()) {
                    val name = child.substringAfterLast('/')
                    if (kids[child].isNullOrEmpty()) {
                        dirTree.append("<div class=dt>").append(radio(child, name)).append("</div>")
                    } else {
                        dirTree.append("<details class=dt><summary>")
                            .append(radio(child, name)).append("</summary>")
                        render(child)
                        dirTree.append("</details>")
                    }
                }
            }

            dirTree.append("<div class=dt>").append(radio("", "（根目录）")).append("</div>")
            render("")
            if (kids.isEmpty()) dirTree.append("<div class=sub>库里还没有子目录</div>")
        }

        /*
         * 顺手给页面一份已有的目录清单。
         *
         * 上传前 JS 先本地查一遍重名目录，冲突就**一个字节都不发** ——
         * 否则用户要等几百 MB 传完才被告知"目录已存在"。
         * 服务端仍然会再查一次，那才是权威判据（curl 之类的客户端绕过页面）。
         */
        val dirsJson = org.json.JSONArray(Library.folders()).toString()

        val html = """
<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>yuHIFI 音乐库</title>
<style>
 body{font-family:system-ui,-apple-system,sans-serif;background:#0e1319;color:#e8eef5;
      max-width:860px;margin:0 auto;padding:24px}
 h1{font-size:20px;margin:0 0 4px} .sub{color:#8fa3b8;font-size:13px;margin-bottom:20px}
 .card{background:#161c24;border-radius:10px;padding:18px;margin-bottom:18px}
 table{width:100%;border-collapse:collapse;font-size:13px}
 td{padding:7px 8px;border-bottom:1px solid #232c37;vertical-align:middle}
 .n{color:#8fa3b8;white-space:nowrap}
 /* 文件树：目录用原生 <details> 折叠，嵌套一层就缩进一层 */
 .tree>details,.tree>.row{margin-left:0}
 details>details,details>.row{margin-left:18px}
 summary{cursor:pointer;padding:5px 8px;border-radius:6px}
 summary:hover{background:#1b232d}
 .row{display:flex;align-items:center;gap:10px;padding:4px 8px;border-radius:6px}
 .row:hover{background:#1b232d}
 .row .fname{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 .row .act{white-space:nowrap}
 a{color:#6fcf97;text-decoration:none} a:hover{text-decoration:underline}
 input,select,button{font-size:14px;padding:8px 10px;border-radius:6px;border:1px solid #2c3743;
      background:#0e1319;color:#e8eef5;margin:4px 6px 4px 0}
 button{background:#1f6f43;border-color:#1f6f43;color:#fff;cursor:pointer}
 button:hover{background:#268451} button.d{background:#5a2222;border-color:#5a2222;padding:4px 10px}
 .msg{background:#1f6f43;padding:10px 14px;border-radius:8px;margin-bottom:16px}
 .drop{border:2px dashed #2c3743;border-radius:10px;padding:28px;text-align:center;color:#8fa3b8}
 /* 「上传到」的折叠目录树 —— 顶层只有几个，默认全折叠，一屏放得下 */
 .dtree{background:#0e1319;border-radius:8px;padding:8px 10px;margin-top:8px;
        max-height:240px;overflow:auto}
 .dt{margin:1px 0}
 .dt>summary{cursor:pointer;padding:2px 4px;border-radius:5px;list-style-position:inside}
 .dt>summary:hover{background:#1b232d}
 details.dt .dt{margin-left:16px}
 .dr{cursor:pointer;padding:2px 4px;border-radius:5px}
 .dr:hover{background:#1b232d}
</style></head><body>
<h1>yuHIFI 音乐库</h1>
<div class=sub>${Library.size()} 首 · ${totalBytes / 1024 / 1024} MB</div>
${msgBanner(msg)}
<div class=card>
 <form method=post action=/upload enctype=multipart/form-data id=f>
  <div class=drop id=drop>
    把音乐文件<b>或整个文件夹</b>拖到这里
    <div style="margin-top:10px">
      <input type=file id=pickFiles multiple accept="audio/*,.flac,.dsf,.dff,.m4a,.ape,.wv">
      或 <input type=file id=pickDir webkitdirectory multiple>
    </div>
  </div>
  <div id=picked class=sub style="margin-top:8px"></div>
  <div style="margin-top:12px">
    上传到：<b id=dirPicked>（根目录）</b>
    <button type=submit>开始上传</button>
  </div>
  <div class=dtree>$dirTree</div>
  <div style="margin-top:8px">或新建：<input name=newdir placeholder="文件夹名" style=width:140px></div>
  <div id=prog class=sub style="margin-top:8px"></div>
 </form>
</div>
<div class=card>
 <form method=post action=/mkdir style=display:flex;align-items:center;gap:8px>
  <input name=name placeholder="新建文件夹名" style=flex:1>
  <button type=submit>新建</button>
 </form>
</div>
<div class=card>
 <div class=tree>$rows</div>
 ${if (files.none()) "<div class=sub>库还是空的，从上面传点东西进来吧</div>" else ""}
 $hiddenForms
</div>
<script>
/*
 * ★★ 白名单**从 MusicLibrary 取**，这里不再自己写一份。
 *
 *   原来这里是硬编码的一串，而且和 Library.AUDIO_EXT **不一致**：
 *   多了 oga/wma/mka、少了……两边各写各的，迟早有人忘了同步。
 *   不一致的直接后果：网页传上去的 .wma 服务端存了，但音乐库永远不扫它
 *   —— 文件在手机上，App 里看不见，全程零提示。
 *
 *   现在只有一份名单（Library.AUDIO_EXT），加减格式只改那一处。
 *   权威说明见 docs/08-音频格式支持.md。
 */
var AUDIO=/\.(${Library.audioExtRegex()})$/i;
var HAVE=${dirsJson};      // 库里已有的目录（相对 root），上传前本地查重名用
var picks=[];              // [{file, path}]，path 是相对路径，结构就靠它
/*
 * ★★ 读不出来的文件。
 *
 *   拖放目录时 Chrome 是**逐个** entry.file() 读的，读不到就走失败回调。
 *   原来那个失败回调里**只有一句 release()** —— 文件被静默跳过，
 *   页面照样显示"上传完成"，用户以为传全了。
 *   实测：一张 16 首的专辑少了 2 首，另一张 15 首少了 1 首，全程零提示，
 *   原因都是**路径超过 Windows 260 字符上限**。
 *
 *   "传完了"和"传全了"是两件事 —— 错误信息不能在半路被吞掉。
 */
var pickErrors=[];

function el(id){return document.getElementById(id)}
function prog(t){el('prog').textContent=t}

/* 「上传到」现在是一棵折叠目录树 + 单选钮（不是 <select> 了）。
   ★ 单选钮在折叠状态下看不见选中的是哪个，所以标题旁边常驻一个文字提示。 */
function dirValue(){
  var r=document.querySelector('input[name=dir]:checked');
  return r?r.value:'';
}
function dirName(v){ return v ? v : '（根目录）'; }
Array.prototype.forEach.call(document.querySelectorAll('input[name=dir]'),function(r){
  r.addEventListener('change',function(){ el('dirPicked').textContent=dirName(r.value); });
});

/* 读不到的文件在选中提示里明说，别让"已选 N 个"看起来像全齐了 */
function errSuffix(){
  if(!pickErrors.length) return '';
  /* 注意：这里是 textContent，写 **粗体** 只会原样显示星号，不要用 markdown */
  return '　⚠ 另有 '+pickErrors.length+' 个文件「读不出来，已跳过」：'+
    pickErrors.slice(0,3).join('、')+(pickErrors.length>3?' …':'')+
    '。常见原因：路径超过 Windows 260 字符上限、文件被移动或改名、网盘占位文件。'+
    '把音乐放到更短的路径（例如 C:\\Music\\）通常能解决。';
}

function showPicked(){
  if(!picks.length){
    el('picked').textContent='还没选文件'+errSuffix();
    return;
  }
  var dirs={};
  picks.forEach(function(p){
    var d=p.path.split('/').slice(0,-1).join('/');
    dirs[d]=1;
  });
  el('picked').textContent='已选 '+picks.length+' 个音频文件 · '+
    Object.keys(dirs).length+' 个目录（'+Object.keys(dirs).slice(0,3).join('、')+
    (Object.keys(dirs).length>3?' …':'')+'）'+errSuffix();
}
/* bad = 本次读不出来的文件清单（拖放路径才有），省略即视为全读到了 */
function setPicks(a,bad){ picks=a; pickErrors=bad||[]; showPicked(); }

// ① 选择文件：path 就是文件名
el('pickFiles').addEventListener('change',function(){
  setPicks([].map.call(this.files,function(f){return {file:f,path:f.name}})
    .filter(function(p){return AUDIO.test(p.path)}));
});
// ② 选择文件夹：webkitRelativePath 自带顶层文件夹名，目录结构从这儿来
el('pickDir').addEventListener('change',function(){
  setPicks([].map.call(this.files,function(f){
      return {file:f,path:f.webkitRelativePath||f.name};
    }).filter(function(p){return AUDIO.test(p.path)}));
});

/*
 * ③ 拖放。
 *
 * ★★ 必须递归展开目录，**不能**把 dataTransfer.files 直接塞给 input。
 *    那样塞进去的是一个"目录项"，Chrome 接着会把它当文件读，
 *    Windows 返回 ERROR_ACCESS_DENIED，浏览器报的正是
 *    net::ERR_ACCESS_DENIED —— 请求在发出去之前就死了，
 *    服务端一个字都收不到（这就是之前那个"上传被拒绝"的真身）。
 */
function collect(entries,cb){
  var out=[],bad=[],pending=0;
  function release(){ if(--pending<=0) cb(out,bad); }
  /*
   * ★★ 失败回调**必须记账**。
   *
   *   这里原来是直接传 release —— 文件读不出来就被静默跳过，
   *   页面照样报"上传完成"。实测两张专辑各少了 1~2 首，全程零提示。
   *   现在读不到的逐个记进 bad[]，由 showPicked() 明确报给用户。
   */
  function fail(what){ return function(){ bad.push(what); release(); }; }
  function walk(entry,prefix){
    pending++;
    if(entry.isFile){
      entry.file(function(f){
        if(AUDIO.test(f.name)) out.push({file:f,path:prefix+f.name});
        release();
      }, fail(prefix+entry.name));
    }else if(entry.isDirectory){
      var rd=entry.createReader(),kids=[];
      (function readAll(){
        rd.readEntries(function(es){
          if(!es.length){
            var p=prefix+entry.name+'/';
            kids.forEach(function(k){walk(k,p)});
            release();
          }else{ kids=kids.concat([].slice.call(es)); readAll(); }
        }, fail(prefix+entry.name+'/（整个目录读不出来）'));
      })();
    }else{ release(); }
  }
  if(!entries.length) return cb(out,bad);
  entries.forEach(function(e){walk(e,'')});
}

var d=el('drop');
d.addEventListener('dragover',function(e){e.preventDefault();d.style.borderColor='#6fcf97'});
d.addEventListener('dragleave',function(){d.style.borderColor='#2c3743'});
d.addEventListener('drop',function(e){
  e.preventDefault(); d.style.borderColor='#2c3743';
  // webkitGetAsEntry 只在 drop 事件里能同步拿到，先把 entry 抓出来
  var es=[],it=e.dataTransfer.items||[];
  for(var i=0;i<it.length;i++){
    var en=it[i].webkitGetAsEntry&&it[i].webkitGetAsEntry();
    if(en) es.push(en);
  }
  if(!es.length) return;
  prog('正在读取…');
  collect(es,function(list,bad){
    setPicks(list,bad);
    prog(bad.length ? ('有 '+bad.length+' 个文件读不出来 —— 已跳过，详见上方提示') : '');
  });
});

/*
 * ④ 上传前本地预检。
 *
 * 服务端的规则是"不允许新建一个已存在的同名目录"。放在本地查，
 * 用户不用等几百 MB 传完才被告知 —— 冲突时一个字节都不发。
 * （服务端仍会再查一遍，那才是权威判据。）
 */
function conflict(){
  var sel=dirValue();
  var nd=document.querySelector('input[name=newdir]').value.trim();
  var have={}; HAVE.forEach(function(x){have[x]=1});
  if(nd && have[nd])
    return '目录「'+nd+'」已存在 —— 请把它选为「上传到」的目标，或换个名字';
  for(var i=0;i<picks.length;i++){
    var segs=picks[i].path.split('/');
    var pre = nd || sel;                       // 目标目录作为前缀
    for(var j=0;j<segs.length-1;j++){
      var cur = pre ? (pre+'/'+segs[j]) : segs[j];
      if(have[cur])
        return '目录「'+segs[j]+'」已存在 —— 请把它选为「上传到」的目标再传，或换个文件夹名';
      pre = cur;
    }
  }
  return null;
}

el('f').addEventListener('submit',function(ev){
  ev.preventDefault();
  if(!picks.length){ prog('还没选文件'); return; }
  var bad=conflict();
  if(bad){ prog(bad); return; }

  var nd=document.querySelector('input[name=newdir]').value.trim();

  var fd=new FormData();
  // ★ dir / newdir 必须排在文件**前面** —— 服务端要先用它们定下目标目录，
  //   后面每个文件才知道自己该落在哪儿
  // ★ 二选一，newdir 优先（单选钮没法像 <select> 那样清空，所以这里只判 nd）
  if(nd){ fd.append('newdir',nd); } else { fd.append('dir',dirValue()); }
  // 第三个参数就是 multipart 里的 filename，相对路径只能从这儿传过去
  picks.forEach(function(p){ fd.append('files',p.file,p.path); });

  var x=new XMLHttpRequest();
  x.upload.onprogress=function(e){
    if(e.lengthComputable) prog('上传中 '+Math.round(e.loaded/e.total*100)+'%  ('+
      (e.loaded/1048576).toFixed(0)+' MB)');
  };
  // ★ 在这里把服务端的话原样带回页面。
  //   以前这里是硬编码的 '上传完成'，把服务端的提示整个盖掉了 ——
  //   于是"目录已存在""文件名不合法"这类错误永远看不见。
  x.onload=function(){
    var m=(x.responseText||'').trim()||'上传完成';
    /*
     * ★ 被跳过的文件要**跟着这次上传的结果一起回传**。
     *
     *   下面这行会整页重定向，页面一刷新 pickErrors 就没了 ——
     *   而那正是"以为传全了"的那一刻。消息放进 URL 才活得过去。
     */
    if(pickErrors.length){
      m += '　⚠ 另有 '+pickErrors.length+' 个文件读不出来、没有上传：'+
           pickErrors.slice(0,3).join('、')+(pickErrors.length>3?' …':'')+
           '（常见原因：路径超过 Windows 260 字符上限）';
    }
    location.href='/?msg='+encodeURIComponent(m);
  };
  /*
   * ★★ 这句原来是「上传失败 —— 网络断了？」，**是误导**。
   *
   *   实测那个 ERR_FILE_NOT_FOUND 根本不是网络问题：浏览器连请求都没发出去，
   *   服务端日志里一条都没有。真实原因是**本地文件读不出来**
   *   （路径超过 Windows 260 字符上限最常见的）。
   *   一句错话让人往错的方向查一整轮 —— 所以这里把两类原因都列出来。
   */
  x.onerror=function(){
    prog('上传失败：浏览器没能把文件发出去。两种常见原因 —— '+
         '① 本地文件读不出来（路径超过 Windows 260 字符上限、文件被移动或改名、网盘占位文件）；'+
         '② 网络断了。若是 ①，把音乐放到更短的路径（例如 C:\\Music\\）再试；'+
         '如果是拖进来的，注意上方提示里有没有被跳过的文件。');
  };
  x.open('POST','/upload'); x.send(fd);
});
</script></body></html>
""".trimIndent()

        respond(out, 200, "text/html; charset=utf-8", html)
    }

    private fun msgBanner(msg: String): String =
        if (msg.isBlank()) "" else "<div class=msg>${esc(msg)}</div>"

    // ------------------------------------------------------------------
    //  小工具
    // ------------------------------------------------------------------

    /**
     * 303 回列表页（表单提交后重定向，避免刷新时重复提交）。
     *
     * ★ 消息**必须放进 `Location` 的查询串**，不能只存进程级字段。
     *
     *   原来回的是 `Location: /`，浏览器跟过去是一次不带 `?msg=` 的 GET ——
     *   而 `handle()` 里那句"GET 且不带 ?msg= → 清空"会在 `servePage` 之前
     *   先把消息擦掉。所以「已删除」「新建失败」这些提示**从来没显示过**。
     *   放进 URL 之后，消息由这次 GET 自己带回来，不依赖任何共享状态。
     */
    private fun redirect(out: OutputStream, to: String) {
        val loc = if (to.startsWith("/?msg=")) {
            val plain = URLDecoder.decode(to.removePrefix("/?msg="), "UTF-8")
            "/?msg=" + java.net.URLEncoder.encode(plain, "UTF-8")
        } else "/"
        respond(out, 303, "text/plain", "", extra = "Location: $loc\r\n")
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        type: String,
        body: String,
        extra: String = ""
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        /*
         * 响应也记一行，和 handle() 里那行请求日志**成对**读：
         *   有请求、没响应 → 服务端卡住了（或连接被对端掐了）
         *   两行都没有     → 请求压根没发出来，问题在客户端
         */
        Log.i(TAG, "← $code  ${bytes.size} 字节")
        out.write(
            ("HTTP/1.1 $code\r\nContent-Type: $type\r\n" +
                    "Content-Length: ${bytes.size}\r\n$extra\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        val len = headers["content-length"]?.toIntOrNull() ?: return ""
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(b, off, len - off)
            if (n <= 0) break
            off += n
        }
        return String(b, 0, off, Charsets.UTF_8)
    }

    private fun formValue(body: String, key: String): String? =
        Regex("(?:^|&)${Regex.escape(key)}=([^&]*)").find(body)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun deleteEntry(rel: String): Boolean {
        val f = File(Library.root, rel)
        // ★ inside()：字符串前缀会让 `path=../libraryX` 被整个递归删掉
        if (!Paths.inside(Library.root, f)) return false
        /*
         * ★★ 单独挡住"删库根本身"。
         *
         *   `inside(root, root)` 是 true（故意允许"就是它自己"这种情况），
         *   而 `File(root, "")` 恰好等于 root —— 所以一个 `path=` 空的
         *   POST 就能把**整个音乐库 deleteRecursively 掉**。
         *   页面上不会发这种请求，但接口不该靠调用方守规矩。
         */
        if (f.canonicalPath == Library.root.canonicalPath) return false
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    /** 在 byte 数组里找 pattern 的首次出现 */
    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
