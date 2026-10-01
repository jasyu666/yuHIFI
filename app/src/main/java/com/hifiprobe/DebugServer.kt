package com.hifiprobe

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 调试端口 —— 一个只读的局域网 HTTP 服务，用来从电脑看 App 此刻的状态。
 *
 * ```
 *   GET /                 端点列表（浏览器直接打开就行）
 *   GET /debug/state      当前页面、播放状态、曲库统计（JSON）
 *   GET /debug/shot       当前界面的截图（PNG），可加 ?w=1440 要原尺寸
 *   GET /debug/crash      最后一次未捕获异常的堆栈
 * ```
 *
 * ★ **只读，而且必须一直保持只读。**
 *   这个端口没有任何鉴权（和「无线传输」一致的定位：局域网、自己用、用完关）。
 *   能读最多泄漏屏幕内容；一旦开了"能写"，它就变成远程控制了 ——
 *   那是完全不同的一个东西，不该混在这里。
 *
 * ★ 独立开关、独立端口（默认 8766），**不跟着「无线传输」走**。
 *   两个服务的生命周期完全不同：管库那个是日常功能，这个只在开发时开。
 *   绑在一起的话，用户为了传歌打开无线传输，就顺手把屏幕也暴露了。
 *
 * ★ 为什么另起一个 ServerSocket 而不是往 WirelessServer 里塞路由：
 *   那个文件是给用户看的网页面（上传/删除/建文件夹），往里插调试路由会让
 *   "哪些是给用户的、哪些是给开发者的"这条线糊掉。共用的 HTTP 零件
 *   已经抽到 [Http] 里了，重复的只是几十行 accept 循环。
 */
object DebugServer {

    private const val TAG = "HiFiDebug"
    private const val DEFAULT_PORT = 8766

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(2)

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun start(ctx: Context): Boolean {
        if (running) return true
        val port = Settings.debugPort(ctx)
        return try {
            val ss = ServerSocket(port)
            serverSocket = ss
            running = true
            acceptThread = Thread({
                while (running) {
                    val s = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break          // 套接字被关掉 = 正常收工
                    }
                    pool.execute { handle(s) }
                }
            }, "debug-accept").apply { isDaemon = true; start() }
            Log.i(TAG, "调试端口已启动，端口 $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "调试端口启动失败: ${e.message}")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    /** 界面显示给用户看的地址 */
    fun url(ctx: Context): String? {
        val ip = localIp() ?: return null
        return "http://$ip:${Settings.debugPort(ctx)}"
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    // ------------------------------------------------------------------

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 20000
            runCatching {
                val input = BufferedInputStream(s.getInputStream(), 32 * 1024)
                val requestLine = Http.readLine(input) ?: return@runCatching
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@runCatching
                val method = parts[0]
                val path = parts[1]
                Http.readHeaders(input)          // 读完头，不然 socket 里留着垃圾

                val out = s.getOutputStream()
                val route = path.substringBefore('?')

                if (method != "GET") {
                    Http.respondText(out, 405, "text/plain; charset=utf-8", "只读服务，只接受 GET")
                    return@runCatching
                }

                when (route) {
                    "/" -> Http.respondText(
                        out, 200, "text/html; charset=utf-8", INDEX_HTML
                    )

                    "/debug/state" -> Http.respondText(
                        out, 200, "application/json; charset=utf-8",
                        DebugBridge.state().toString(2),
                        extra = "Cache-Control: no-store\r\n"
                    )

                    "/debug/shot" -> {
                        /*
                         * 夹紧。这是只读接口里唯一能让人远程指定尺寸的地方 ——
                         * 不夹的话 `?w=99999999` 会让主线程去分配一张巨图，
                         * 而截图本来就已经是 4 秒超时的边缘操作。
                         */
                        val w = (Http.query(path)["w"]?.toIntOrNull() ?: 720)
                            .coerceIn(120, 1440)
                        val png = DebugBridge.shot(w)
                        if (png == null) {
                            // 明确区分"没截到"和"截到一张黑的" ——
                            // 前者是没前台页面，后者才是渲染问题
                            Http.respondText(
                                out, 503, "text/plain; charset=utf-8",
                                "没有前台页面可截（正在切页，或主线程被占用）"
                            )
                        } else {
                            Http.respondBytes(
                                out, 200, "image/png", png,
                                extra = "Cache-Control: no-store\r\n"
                            )
                        }
                    }

                    "/debug/crash" -> {
                        val c = DebugBridge.lastCrash()
                        if (c == null) Http.respondText(
                            out, 404, "text/plain; charset=utf-8", "没有崩溃记录"
                        ) else Http.respondText(
                            out, 200, "text/plain; charset=utf-8", c,
                            extra = "Cache-Control: no-store\r\n"
                        )
                    }

                    else -> Http.respondText(out, 404, "text/plain; charset=utf-8", "not found")
                }
            }.onFailure { Log.w(TAG, "处理请求出错: ${it.message}") }
        }
    }

    private val INDEX_HTML = """
        <!doctype html><meta charset="utf-8">
        <title>hifiprobe 调试端口</title>
        <style>
          body{background:#111;color:#ddd;font:14px/1.7 -apple-system,system-ui,sans-serif;
               max-width:640px;margin:40px auto;padding:0 20px}
          h1{font-size:18px;color:#4db6ac;margin-bottom:4px}
          p.sub{color:#888;font-size:12px;margin-top:0}
          a{color:#4db6ac;text-decoration:none} a:hover{text-decoration:underline}
          code{background:#1e1e1e;padding:2px 6px;border-radius:4px;font-size:12px}
          li{margin:10px 0} .warn{color:#e0a020;font-size:12px;margin-top:28px}
        </style>
        <h1>hifiprobe 调试端口</h1>
        <p class="sub">只读。没有鉴权 —— 用完请在设置里关掉。</p>
        <ul>
          <li><a href="/debug/state">/debug/state</a> —— 当前页面、播放状态、曲库统计</li>
          <li><a href="/debug/shot">/debug/shot</a> —— 当前界面截图（PNG，默认缩到 720 宽）</li>
          <li><a href="/debug/shot?w=1440">/debug/shot?w=1440</a> —— 原尺寸截图</li>
          <li><a href="/debug/crash">/debug/crash</a> —— 最后一次未捕获异常的堆栈</li>
        </ul>
        <p class="warn">同网络下的任何人都能访问这些地址，包括你的屏幕内容。</p>
    """.trimIndent()
}
