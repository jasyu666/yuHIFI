#pragma once

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <vector>

namespace hifi {

/*
 * 输出速率策略。
 *
 * ★ p18 重写了这张表。旧版说「只有 48k 家族可用，44.1k 家族一律失真」，
 *   **那是错的** —— 错因是两个 bug 叠加后被误判成了设备的能力边界：
 *
 *     1. 探针速率 40000/50000/56000 失败，被归因成"44.1k 家族坏"。
 *        其实它们失败只是因为**不是标准速率**（见下）。
 *     2. 44100 在 P0 早期确实失真，但那是**逐包帧对齐 bug**
 *        （33.075 字节被向下对齐到 30 → 实际只有 40000Hz，1kHz 变 907Hz）。
 *        那个 bug 修好之后没人重测，结论却一直挂着。
 *
 *   实测证据（2026-09-22，p17，判据是**设备自己的 feedback 端点**，
 *   与我们的组包方式无关）：
 *
 *     请求     设备实测      每µframe样本(标称/实测)   判定
 *     48000    48001.0 Hz    6.0000 / 6.0001          ✓
 *     44100    44100.6 Hz    5.5125 / 5.5126          ✓
 *     88200    88201.2 Hz   11.0250 / 11.0251         ✓
 *     176400  176403   Hz   22.0500 / 22.0504         ✓
 *     352800  352808   Hz   44.1000 / 44.1010         ✓
 *     44000    48001.0 Hz    5.5000 / **6.0001**      ✗ ← 见下
 *
 * ★ 真正的规则：**设备只认那 8 个标准速率；非标准的它默默忽略，
 *   继续按上一次设的速率跑。**
 *
 *   44000 那行是铁证：请求 44000，feedback 却报 48001Hz、Fm=6.0001
 *   （正是 48000 的值）—— 说明设备压根没换，还停在上一次的 48000 上，
 *   而我们按 5.5 样本/µframe 喂、它按 6.0 消耗，供不上，于是"故障"。
 *
 *   这也意味着 **SET_CUR + GET_CUR 回读根本查不出能力**（设备把设定值
 *   原样回读，44000 也照样"一致 ✓"）。要查只能用 feedback 端点 ——
 *   见 nativeScanRatesLive()。
 */

struct RateDecision {
    int outputRate;    // 最终送给 DAC 的速率
    bool resample;     // 是否需要重采样（false = 直通，一个采样点都不碰）
};

/*
 * 设备真正支持的 8 个标准速率。全部实测可用，全部直通。
 * 少一个都不行 —— 非标准速率不会报错，只会静默跑错，那比报错更危险。
 */
constexpr int kStandardRates[] = {44100, 48000, 88200, 96000,
                                  176400, 192000, 352800, 384000};

/* 48k 家族 —— 只在对 44.1k 家族存疑时才用（旧策略） */
constexpr int kRates48kFamily[] = {48000, 96000, 192000, 384000};

/*
 * 是否信任 44.1k 家族。
 *
 * 默认**开**（8 个标准速率全直通）。留这个开关是为了现场回退：
 * 万一将来换到某台设备上 44.1k 真有问题，关掉它就退回旧的
 * "只走 48k 家族、其余重采样"策略，不必重新编译。
 * 关掉后的行为与 p17 及以前**逐字节一致**。
 */
inline std::atomic<bool> g_allowAllStandardRates{true};

/* 距 r 最近的 48k 家族速率。非标准速率与（开关关闭时的）44.1k 家族都走这里。 */
inline int nearest48kFamily(int r) {
    int best = 48000;
    int bestDist = std::abs(r - 48000);
    for (int c : kRates48kFamily) {
        const int d = std::abs(r - c);
        if (d < bestDist) { bestDist = d; best = c; }
    }
    return best;
}

/*
 * 设备**自己声明的**速率能力。
 *
 * ★★ 为什么要有这个：白名单是**为 MOONDROP Dawn Pro 打的补丁** ——
 *    那台设备的描述符里没有速率表（FORMAT_TYPE_I 是 6 字节截断的），
 *    `SET_CUR` 又任何值都回"成功"，不靠白名单就没法判断。
 *
 *    但白名单是**写死的**，换一台设备就会出问题：
 *      · 只支持到 96k 的设备 → 我们仍然按 176400 直通请求 → 挑不到 alt →
 *        播放直接失败（不会静默出错，但也不会自动重采样过去）
 *      · 支持非标准速率的设备 → 白名单把它重采样掉，明明能直通却多转一层
 *
 *    所以：**设备报了速率表就听它的**，没报才回落到白名单。
 */
struct DeviceRateCaps {
    bool hasInfo = false;          // 设备有没有在描述符里声明速率
    std::vector<int> discrete;     // 离散速率表
    int contMin = 0;               // 连续范围（contMax > 0 才有效）
    int contMax = 0;

    bool supports(int r) const {
        if (!hasInfo) return false;
        if (contMax > 0 && r >= contMin && r <= contMax) return true;
        for (int d : discrete) {
            if (d == r) return true;
        }
        return false;
    }

    /** 设备支持的所有候选里，离 r 最近的那个。没有可用项时返回 0 */
    int nearest(int r) const {
        if (!hasInfo) return 0;
        int best = 0;
        for (int d : discrete) {
            if (best == 0 || std::abs(d - r) < std::abs(best - r)) best = d;
        }
        if (contMax > 0) {
            const int clamped = std::min(std::max(r, contMin), contMax);
            if (best == 0 || std::abs(clamped - r) < std::abs(best - r)) best = clamped;
        }
        return best;
    }
};

/**
 * 决定输出速率。
 *
 * 优先级：
 *   1. **设备自己说支持** → 直通（一个采样点都不碰）
 *   2. 设备报了速率表但不支持源速率 → 重采样到**它支持的、离得最近**的那个
 *   3. 设备什么也没说（像 Dawn Pro）→ 回落到白名单，行为与以前逐字节一致
 */
inline RateDecision decideOutputRate(int sourceRate,
                                     const DeviceRateCaps& caps = DeviceRateCaps{}) {
    // ---- 1. 设备自己说了算 ----
    if (caps.supports(sourceRate)) return {sourceRate, false};

    // ---- 2. 设备报了速率表，但不支持这个源速率 ----
    if (caps.hasInfo) {
        const int alt = caps.nearest(sourceRate);
        if (alt > 0) return {alt, true};
        // 表里一个能用的都没有 —— 不硬来，落到下面按白名单处理
    }

    // ---- 3. 设备没声明速率，按白名单（这台设备的老路）----
    const bool trust44k = g_allowAllStandardRates.load(std::memory_order_relaxed);

    if (trust44k) {
        for (int r : kStandardRates) {
            if (r == sourceRate) return {r, false};   // 标准速率一律直通
        }
    } else {
        for (int r : kRates48kFamily) {
            if (r == sourceRate) return {r, false};
        }
    }

    /*
     * 走到这里说明：要么是非标准速率（设备根本不支持，必须换），
     * 要么是开关关闭时的 44.1k 家族。两者都重采样到最近的 48k 家族速率。
     *
     * 用"最近"而不是固定 48000：56000 归到 48000（差 8000），
     * 而 88200 归到 96000（差 7800）—— 重采样比越小伪影越少。
     */
    return {nearest48kFamily(sourceRate), true};
}

}  // namespace hifi
