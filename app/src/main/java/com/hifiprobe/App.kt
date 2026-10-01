package com.hifiprobe

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * 应用入口。
 *
 * 存在的唯一理由是**在任何一个界面创建之前**把主题定下来。
 *
 * ★ 为什么不能在各 Activity 的 onCreate 里设：那会导致每进一个界面
 *   都触发一次配置变更 → Activity 重建 → 闪一下。放在 Application 里，
 *   整个进程只决定一次。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Settings.themeMode(this))

        // 调试桥要跟踪"当前是哪个 Activity"，必须在任何界面创建之前注册
        DebugBridge.install(this)

        /*
         * 封面缩略图的落盘目录。
         *
         * ★ 必须在这里（任何界面创建之前）：列表一 bind 就会去读缩略图，
         *   晚一步的话第一批封面会走"从母版解"那条慢路。
         */
        CoverLoader.init(this)

        /*
         * 记下未捕获异常。
         *
         * ★ 必须把控制权交还给系统原本的处理器 —— 自己吞掉的话，
         *   进程不会正常退出，也弹不出"应用已停止"，用户看到的是
         *   界面卡死而不是崩溃。那不是"更友好"，是把问题藏起来。
         */
        val fallback = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            DebugBridge.recordCrash(e)
            fallback?.uncaughtException(t, e)
        }

        /*
         * USB 解码器的接入从这里开始。
         *
         * ★ 必须在 Application 里做：设备接入是**进程级**的事，不是某个页面的功能。
         *   以前只有音乐库页 onResume 会去开设备，于是从首页走「所有歌曲」
         *   点歌永远"点了没反应" —— 那条路根本没人开设备。
         *
         * install 做两件事：注册插拔广播，以及立刻试连一次。
         */
        DeviceGate.install(this)

        // 上次开着的话，重启后接着开 —— 不然每次改完代码重新安装都要去设置里点一遍
        // ★★ 只在内部版自启（见 [diagnosticsEnabled]）—— 正式包里那个端口
        //    连开关都没有，更不该自己起来。
        if (diagnosticsEnabled(this) && Settings.debugEnabled(this)) {
            DebugServer.start(this)
        }

        /*
         * ★★ 「无线传输」**刻意不在这里开机自启**（正式版就该是这样）。
         *
         *   开发期曾经临时开过一段（每次重装都杀进程，而开关状态刻意不持久化，
         *   于是来回装十几次就得去设置里手动开十几次）。**那段已经删掉了。**
         *
         *   现在是默认关闭：要传东西自己到「设置 → 无线传输」开一下。
         *   理由是**安全** —— 那个服务会在局域网上开一个无鉴权的 HTTP 端口，
         *   能读能写整个音乐库。不该让它默认就在那儿开着。
         */
    }
}
