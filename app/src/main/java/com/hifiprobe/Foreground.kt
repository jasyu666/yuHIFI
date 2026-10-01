package com.hifiprobe

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 当前在前台的 Activity。
 *
 * ★★ 为什么需要它
 *
 *   `PlayerSession` 是**进程级**的：起播可能在任何一个页面被触发，
 *   也可能由 `PlaybackService` 自动连播触发。而"把失败原因弹给用户看"
 *   需要一个 Activity —— `applicationContext` 弹不出对话框。
 *
 *   所以在 `PlayerSession` 这种进程级的代码里，必须有一个办法拿到
 *   "现在用户在看哪个页面"，否则就只能退回 Toast（一闪而过，
 *   而"为什么没播"往往需要几行字才说得清）。
 *
 * ★ 只在 RESUMED 期间持有，PAUSED / DESTROYED 立刻清掉 ——
 *   进程级单例持有界面对象，不清就是把 Activity 泄漏出去了。
 *
 * ★ 这份跟踪原来长在 `DebugBridge` 里。抽出来是因为它**不属于调试功能** ——
 *   普通界面也要用；留着两份就会各自漂移，而"当前是哪个页面"这种状态
 *   一旦有两个来源，谁对谁错根本说不清。
 */
object Foreground {

    @Volatile
    var activity: Activity? = null
        private set

    /** 在 `Application.onCreate` 里注册一次 */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) {
                    activity = a
                }

                override fun onActivityPaused(a: Activity) {
                    if (activity === a) activity = null
                }

                override fun onActivityDestroyed(a: Activity) {
                    if (activity === a) activity = null
                }

                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            })
    }
}
