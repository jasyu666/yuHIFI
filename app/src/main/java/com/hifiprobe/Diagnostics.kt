package com.hifiprobe

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * 诊断 / 调试相关的东西要不要露出来。
 *
 * ★★ **正式版里全部藏起来**（用户 2026-09-26 明确要求），内部版保留。
 *    注意是**藏入口，不是删代码** —— 诊断页、那几节设置、调试端口都还在，
 *    只是正式包里没有任何路径能走到它们。
 *
 * ★★ 判据用「**这个 APK 是不是 debuggable**」，不用 `BuildConfig.DEBUG`：
 *
 *    · 本工程没开 `buildFeatures.buildConfig`，为这一个布尔值去开不值当；
 *    · 更要紧的是 —— debuggable 标志就写在 **APK 自己的清单里**，
 *      所以它**不可能和实际打出来的包对不上**。想验证正式包有没有藏干净，
 *      直接 `aapt2 dump badging` 看 `application-debuggable` 就行。
 *
 *    `assembleDebug` → true（内部版）／`assembleRelease` → false（正式版）。
 *
 * ★ 需要 Context 而不是做成全局单例：本工程的 `App` 没有静态 instance，
 *   为这一个布尔去加一个全局引用不划算。调用点本来都拿得到 Context。
 */
fun diagnosticsEnabled(ctx: Context): Boolean =
    (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
