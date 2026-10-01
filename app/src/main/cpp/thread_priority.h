#pragma once

/*
 * 音频线程的调度优先级。
 *
 * 为什么需要：USB 提交线程与解码线程原先都跑在默认优先级上，系统一忙
 * （切屏、UI 重绘、后台任务）就会被抢占。实测一次切屏能让解码线程停摆
 * **1 秒以上** —— 缓冲耗尽后就是一段可闻的静音。
 *
 * nice 值可以调到负数：Android 给应用的 RLIMIT_NICE 覆盖了音频档位，
 * 这也是 AudioTrack 内部线程的做法。但**提权可能静默失败**，所以这里
 * 不只设一次就完事 —— 还回读实际值，并把结果留给导出的报告。
 * 否则就会陷入「以为优先级已经提高了，实际没有」的排查死胡同。
 */

#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstring>
#include <sstream>
#include <string>
#include <sys/resource.h>

namespace hifi {

/* 与 Android 的 THREAD_PRIORITY_* 取值一致 */
constexpr int kPriorityUrgentAudio = -19;   // 实时性最高：USB 提交
constexpr int kPriorityAudio = -16;         // 次高：解码

/* 每个线程一个固定槽位，重复调用只覆盖自己的槽，不会越界 */
enum PrioritySlot {
    kSlotUsbSubmit = 0,
    kSlotDecode = 1,
    kSlotCount = 2,
};

namespace detail {

/*
 * 字段用原子：记录由工作线程写、报告由 JNI 线程读，而报告发生在
 * stopDecoding() 之前 —— 那时解码线程可能还在跑。虽然写入只发生在线程
 * 启动时、实践中读到的必然是写完的值，但非原子访问严格来说仍是数据竞争。
 */
struct PriorityRecord {
    std::atomic<const char*> tag{nullptr};
    std::atomic<int> requested{0};
    std::atomic<int> actual{0};
    std::atomic<int> error{0};        // 0 = 成功
    std::atomic<bool> attempted{false};
};

inline PriorityRecord* records() {
    static PriorityRecord recs[kSlotCount];
    return recs;
}

}  // namespace detail

inline void setAudioThreadPriority(int nice, const char* tag, int slot) {
    if (slot < 0 || slot >= kSlotCount) return;

    errno = 0;
    const int r = setpriority(PRIO_PROCESS, 0, nice);
    const int err = (r != 0) ? errno : 0;

    // 回读确认。getpriority 出错和合法值都可能返回 -1，只能靠 errno 区分。
    errno = 0;
    const int got = getpriority(PRIO_PROCESS, 0);
    const int actual = (got == -1 && errno != 0) ? INT_MIN : got;

    detail::PriorityRecord& rec = detail::records()[slot];
    rec.tag.store(tag, std::memory_order_relaxed);
    rec.requested.store(nice, std::memory_order_relaxed);
    rec.actual.store(actual, std::memory_order_relaxed);
    rec.error.store(err, std::memory_order_relaxed);
    rec.attempted.store(true, std::memory_order_release);

    if (err != 0) {
        __android_log_print(ANDROID_LOG_WARN, "HiFiPriority",
                            "%s 提升优先级到 %d 失败: %s（继续用默认优先级）",
                            tag, nice, std::strerror(err));
    } else {
        __android_log_print(ANDROID_LOG_INFO, "HiFiPriority",
                            "%s 优先级 %d 已生效（回读 %d）", tag, nice, actual);
    }
}

/*
 * 报告用：列出各音频线程提权是否真的生效。
 *
 * 为什么必须写进报告：提权失败时我们刻意不中断播放（不能因为提权失败就
 * 让用户听不了歌），但那样一来失败就是完全静默的 —— 排查时会出现
 * 「明明加了提优先级却还是卡」的假象，而真相是压根没提上去。
 */
inline std::string priorityReport() {
    std::ostringstream os;
    bool any = false;
    for (int i = 0; i < kSlotCount; ++i) {
        const detail::PriorityRecord& rec = detail::records()[i];
        if (!rec.attempted.load(std::memory_order_acquire)) continue;
        any = true;
        const int requested = rec.requested.load(std::memory_order_relaxed);
        const int actual = rec.actual.load(std::memory_order_relaxed);
        const int err = rec.error.load(std::memory_order_relaxed);
        const char* tag = rec.tag.load(std::memory_order_relaxed);

        os << "  " << (tag != nullptr ? tag : "?") << ": 请求 " << requested;
        if (err != 0) {
            os << " → **失败**（" << std::strerror(err) << "），仍为默认优先级\n";
        } else {
            os << " → 生效";
            if (actual != requested) {
                os << "（回读 " << actual << "，与请求不一致）";
            }
            os << "\n";
        }
    }
    if (!any) os << "  （尚未设置）\n";
    return os.str();
}

}  // namespace hifi
