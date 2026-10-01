package com.hifiprobe

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 调试桥 —— 把"App 此刻长什么样、在干什么"变成能远程读到的数据。
 *
 * ★ 存在的理由：验证界面改动时，唯一可靠的证据是**真机上的那一帧**。
 *   走 adb 截图要先开无线调试，而它每次重启都关、端口每次都变，
 *   整个流程脆得没法用（实测反复掉线，一半时间花在重连上）。
 *   让 App 自己把当前界面吐出来，这条路就稳了。
 *
 * ★ **只读**。这里没有任何"改状态"的入口 —— 能读和能写是两个量级的
 *   安全问题：能读最多泄漏屏幕内容，能写就是远程控制。
 *   加功能时请守住这条线。
 *
 * ★ 所以它默认关闭，开关在设置里，和「无线传输」分开管。
 *   局域网内不做鉴权（和无线传输一致的定位），设置页里明写了这一点。
 */
object DebugBridge {

    private const val TAG = "HiFiDebug"
    private const val CRASH_FILE = "last_crash.txt"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var app: Context? = null

    /** 在 Application.onCreate 里注册一次 */
    fun install(application: Application) {
        app = application.applicationContext
        /*
         * "当前是哪个 Activity" 的跟踪搬去了 [Foreground]。
         * 它**不属于调试功能** —— 播放失败要弹对话框时普通界面也要用，
         * 留着两份会各自漂移，而"当前页面"这种状态一旦有两个来源就说不清了。
         */
        Foreground.install(application)
    }

    fun currentActivityName(): String = Foreground.activity?.javaClass?.name ?: ""

    // ------------------------------------------------------------------
    //  截图
    // ------------------------------------------------------------------

    /**
     * 截当前界面，返回 PNG 字节。没有前台 Activity 时返回 null。
     *
     * ★ `View.draw()` **必须在主线程**调 —— 它会遍历整棵 View 树读状态，
     *   别的线程正在布局/绘制时读就是数据竞争，轻则画出一半，重则崩。
     *   所以这里 post 到主线程再等结果。
     *
     * ★ 等待有超时：主线程可能正卡在解码一首 DSD 上，不能把 HTTP 连接吊死。
     *   超时就返回 null，让调用方自己决定说什么 —— 总比浏览器一直转圈强。
     */
    fun shot(targetWidth: Int): ByteArray? {
        val act = Foreground.activity ?: return null
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        main.post {
            out = runCatching { render(act, targetWidth) }
                .onFailure { Log.w(TAG, "截图失败: ${it.message}") }
                .getOrNull()
            latch.countDown()
        }
        if (!latch.await(4, TimeUnit.SECONDS)) {
            Log.w(TAG, "截图超时：主线程没在 4 秒内响应")
            return null
        }
        return out
    }

    private fun render(act: Activity, targetWidth: Int): ByteArray? {
        val view = act.window?.decorView ?: return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null

        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))

        /*
         * 默认缩到 720 宽再发。
         *
         * 1440×3200 的原图 PNG 有好几 MB，但界面文字在这个尺寸下已经足够清楚，
         * 缩一半传输快得多。要看细节可以传 ?w=1440 要原尺寸。
         */
        val outBmp = if (targetWidth in 1 until w) {
            val th = (h.toLong() * targetWidth / w).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(full, targetWidth, th, true).also { full.recycle() }
        } else full

        return ByteArrayOutputStream(1 shl 20).use { bos ->
            outBmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            outBmp.recycle()
            bos.toByteArray()
        }
    }

    // ------------------------------------------------------------------
    //  状态
    // ------------------------------------------------------------------

    /**
     * 当前状态。
     *
     * ★ 取不到的字段宁可**留空字符串**，也不要填一个看起来像真的的默认值 ——
     *   调试数据一旦开始撒谎，比没有还糟。所以每个跨模块的读取都包了 runCatching。
     */
    fun state(): JSONObject = JSONObject().apply {
        put("version", versionName())
        put("activity", currentActivityName())
        put("screen", JSONObject().apply {
            val m = app?.resources?.displayMetrics
            put("widthPx", m?.widthPixels ?: 0)
            put("heightPx", m?.heightPixels ?: 0)
            put("density", (m?.density ?: 0f).toDouble())
        })

        put("player", JSONObject().apply {
            val t = runCatching { PlayerSession.current }.getOrNull()
            put("state", runCatching { PlayerSession.state.name }.getOrDefault(""))
            put("active", runCatching { PlayerSession.isActive }.getOrDefault(false))
            put("title", t?.displayTitle ?: "")
            put("uri", t?.uri ?: "")
            put("spec", t?.specLabelOrDefault ?: "")
            // 这是这里唯一一处跨线程调进原生层的读。原生侧本来就在被解码线程
            // 和 USB 线程同时读，再多个只读的访问者是同一类访问；
            // 真出问题也不该在这里吞掉 —— 所以保留，但记住它
            put("positionMs", runCatching { PlayerSession.currentPositionMs() }.getOrDefault(0L))
            val q = runCatching { PlayerSession.queue }.getOrNull()
            put("queueSize", q?.size ?: 0)
            put("queueIndex", q?.currentIndex ?: -1)
            put("shuffle", q?.shuffle ?: false)
            put("repeat", q?.repeatMode?.name ?: "")
        })

        put("library", JSONObject().apply {
            put("tracks", runCatching { Library.size() }.getOrDefault(-1))
            put("folders", runCatching { Library.folders().size }.getOrDefault(-1))
            put("albums", runCatching { Library.albums().size }.getOrDefault(-1))
            put("playlists", runCatching { Playlists.size() }.getOrDefault(-1))
        })

        put("server", JSONObject().apply {
            put("wireless", runCatching { WirelessServer.isRunning() }.getOrDefault(false))
            put("debug", DebugServer.isRunning())
        })
    }

    private fun versionName(): String = runCatching {
        val c = app ?: return@runCatching ""
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    // ------------------------------------------------------------------
    //  崩溃记录
    // ------------------------------------------------------------------

    /**
     * 记下最后一次未捕获异常。
     *
     * ★ 为什么值得单独做：用户反馈"闪退了"的时候，如果没有 adb，
     *   拿到的只有一句"闪退了" —— 定位不了任何东西。
     *   落到文件里，重启后还能通过 /debug/crash 取出来。
     *
     * 只留最后一次：崩溃往往是同一个原因连着发生，堆一长串反而看不到重点。
     */
    fun recordCrash(t: Throwable) {
        val c = app ?: return
        runCatching {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            File(c.filesDir, CRASH_FILE).writeText(
                "时间: $stamp\n版本: ${versionName()}\n" +
                        "页面: ${currentActivityName()}\n\n${sw}"
            )
        }.onFailure { Log.w(TAG, "崩溃记录写入失败: ${it.message}") }
    }

    fun lastCrash(): String? {
        val c = app ?: return null
        val f = File(c.filesDir, CRASH_FILE)
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    fun clearCrash() {
        val c = app ?: return
        runCatching { File(c.filesDir, CRASH_FILE).delete() }
    }
}
