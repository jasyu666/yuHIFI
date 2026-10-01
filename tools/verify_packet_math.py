#!/usr/bin/env python3
"""
校验 iso_player.cpp 里 iso 包长分配的数学正确性。

这里精确复刻 C++ 的实现，可随时重跑。

背景 —— 这个文件是为两个真实踩过的坑写的：

坑 1（已修）：按字节分配 + 逐包向下对齐到帧边界。
    44100Hz 时每 microframe 33.075 字节，Bresenham 给出 33/34，
    但把两者都截断到 6 的倍数后全变成 30，实际速率掉到 40000Hz。

坑 2（已修，更隐蔽）：按字节分配本身。
    44100Hz 时包长 33/34 字节 = 5.5 / 5.667 个样本 —— 每个包都装着
    半截样本，设备侧样本流错位，听感是持续失真。
    而 48000Hz 每包恰好 6 个整样本（36 字节），完全正常，
    所以这个 bug 只在非整数速率下暴露，极难发现。

正确做法：按「样本数」做小数分配，再换算成字节。

    python tools/verify_packet_math.py
"""

MICROFRAMES_PER_SEC = 8000   # USB 2.0 高速：每毫秒 8 个 microframe
SCALE = 1_000_000            # 定点缩放


def scaled_samples_per_unit(rate):
    """对应 C++ 的 scaledSamplesPerUnit_"""
    return round(rate * SCALE / MICROFRAMES_PER_SEC)


def scaled_bytes_per_unit(rate, frame_bytes):
    """已废弃的按字节分配（坑 2）"""
    return round(rate * frame_bytes * SCALE / MICROFRAMES_PER_SEC)


def bresenham(i, s):
    """floor((i+1)*s) - floor(i*s) 的定点版本"""
    return (i + 1) * s // SCALE - i * s // SCALE


def samples_of_packet_new(i, s_samples):
    """当前实现：先定样本数"""
    return bresenham(i, s_samples)


def bytes_of_packet_new(i, s_samples, frame_bytes):
    return samples_of_packet_new(i, s_samples) * frame_bytes


def bytes_of_packet_old(i, s_bytes):
    """旧实现：直接对字节做分配"""
    return bresenham(i, s_bytes)


def check(rate, channels, subframe, seconds=1):
    frame_bytes = channels * subframe
    n = MICROFRAMES_PER_SEC * seconds
    s_samples = scaled_samples_per_unit(rate)
    s_bytes = scaled_bytes_per_unit(rate, frame_bytes)

    new_packets = [bytes_of_packet_new(i, s_samples, frame_bytes) for i in range(n)]
    old_packets = [bytes_of_packet_old(i, s_bytes) for i in range(n)]

    new_total = sum(new_packets)
    old_total = sum(old_packets)
    expect = rate * frame_bytes * seconds

    # 每个包必须装整数个样本
    new_whole = all(b % frame_bytes == 0 for b in new_packets)
    old_whole = all(b % frame_bytes == 0 for b in old_packets)

    ok = (new_total == expect) and new_whole and (old_total == expect)
    marker = "OK  " if ok else "FAIL"
    per_unit_samples = rate / MICROFRAMES_PER_SEC

    print(f"[{marker}] {rate:>6}Hz {channels}ch {subframe}B  "
          f"{per_unit_samples:>8.4f} 样本/µframe")

    if new_whole:
        lo, hi = min(new_packets), max(new_packets)
        shape = f"恒定 {lo}B" if lo == hi else f"{lo}/{hi}B 交替"
        print(f"         新实现(按样本): {shape}，每包整样本 [OK]，"
              f"1s 共 {new_total}B (期望 {expect})")
    else:
        print(f"         新实现(按样本): 出现非整样本包 [FAIL]")

    frac = sorted({b % frame_bytes for b in old_packets})
    print(f"         旧实现(按字节): 每包 {min(old_packets)}/{max(old_packets)}B，"
          f"字节余数 {frac} -> {'整样本' if old_whole else '**含半截样本，失真**'}")

    assert new_total == expect, f"{rate}Hz 新实现字节总数错误"
    assert new_whole, f"{rate}Hz 新实现出现非整样本包"
    return new_packets


def check_all():
    print("=== iso 包长分配校验 ===\n")
    for rate in (44100, 48000, 50000, 88200, 96000, 176400,
                 192000, 352800, 384000, 40000):
        for subframe in (2, 3, 4):
            check(rate, 2, subframe)
        print()


def demonstrate():
    """量化坑 2：按字节分配时每包装了多少个样本"""
    print("--- 坑 2 的具体表现（44100Hz / 2ch / 24bit，6 字节一帧）---")
    s_bytes = scaled_bytes_per_unit(44100, 6)
    for i in range(8):
        b = bytes_of_packet_old(i, s_bytes)
        print(f"  包{i}: {b} 字节 = {b / 6:.4f} 个样本  ← {'整' if b % 6 == 0 else '半截'}")
    print("\n  没有一个包装着整数个样本 —— 设备侧样本流持续错位。")


if __name__ == "__main__":
    check_all()
    demonstrate()
    print("\n全部通过。")
