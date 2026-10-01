#!/usr/bin/env python3
"""
校验 audio_engine.cpp 里环形缓冲的目标水位计算。

精确复刻 targetBufferBytes()，可随时重跑。

背景 —— 这个文件是为一个会让界面直接卡死的坑写的：

坑（已修）：目标水位写死成「1 秒的数据」，没有对缓冲容量封顶。
    384kHz / 24bit / 2ch 下单秒就是 2.3MB，比整个环形缓冲（2MiB）还大。
    起播预灌循环的退出条件是 ring.available() < targetBytes，此时**永远成立**；
    而那一刻 USB 线程还没启动、没人在消费，writeToRing 一直写不进去并空转 ——
    点下播放后整个界面卡死，且没有任何日志。

    这个 bug 只在「单秒字节数 > 缓冲容量」时出现，也就是高采样率 + 高位深。
    44.1k/16bit 的曲库怎么测都测不出来。

修法：目标水位封顶到容量的一半。

    python tools/verify_buffer_math.py
"""

RING_BYTES = 1 << 21          # AudioEngine 里 RingBuffer ring_{1 << 21}，2 MiB
TARGET_SECONDS = 1            # kTargetBufferSeconds
DECODE_CHUNK_FRAMES = 4096    # kDecodeChunkFrames


def ring_capacity(requested=RING_BYTES):
    """RingBuffer 会把容量向上取整到 2 的幂"""
    cap = 1
    while cap < requested:
        cap <<= 1
    return cap


def one_second_bytes(rate, frame_bytes):
    """坑：不封顶的目标水位"""
    return rate * frame_bytes * TARGET_SECONDS


def target_buffer_bytes(rate, frame_bytes, capacity):
    """修复后：与 C++ 的 targetBufferBytes() 一致"""
    return min(one_second_bytes(rate, frame_bytes), capacity // 2)


def check(rate, channels, subframe):
    capacity = ring_capacity()
    frame_bytes = channels * subframe
    old = one_second_bytes(rate, frame_bytes)
    new = target_buffer_bytes(rate, frame_bytes, capacity)
    chunk = DECODE_CHUNK_FRAMES * frame_bytes

    # 预灌循环按整块写入，因此水位可能冲到 target 之上不到一块
    peak = new + chunk - 1
    deadlock = old > capacity
    ok = (new <= capacity // 2) and (peak < capacity)

    marker = "FAIL" if not ok else ("曾死锁" if deadlock else "OK  ")
    print(f"[{marker}] {rate:>6}Hz {channels}ch {subframe}B/样本  "
          f"单秒={old:>9,}B  容量={capacity:,}B")
    print(f"         旧目标(未封顶)={old:>9,}B  "
          f"{'** 超过容量，预灌永不结束 -> 卡死 **' if deadlock else '未超容量'}")
    print(f"         新目标(封顶/2)={new:>9,}B = {new / (rate * frame_bytes):.3f} 秒，"
          f"峰值 {peak:,}B 余量 {capacity - peak:,}B")
    print(f"         单块 {chunk:,}B，预灌需 {new // chunk + 1} 块")

    assert new <= capacity // 2, f"{rate}Hz/{subframe}B 目标水位未被正确封顶"
    assert peak < capacity, f"{rate}Hz/{subframe}B 预灌峰值会撑爆缓冲"
    return deadlock


def check_all():
    print("=== 环形缓冲目标水位校验 ===\n")
    print(f"容量 = {ring_capacity():,} 字节（{ring_capacity() / 1024 / 1024:.0f} MiB）\n")

    deadlocks = []
    for rate in (44100, 48000, 88200, 96000, 176400,
                 192000, 352800, 384000, 40000):
        for subframe in (2, 3, 4):
            if check(rate, 2, subframe):
                deadlocks.append(f"{rate}Hz/{subframe}B")
        print()

    print("--- 结论 ---")
    if deadlocks:
        print(f"若不封顶，以下组合会卡死（共 {len(deadlocks)} 组）:")
        for d in deadlocks:
            print(f"  · {d}")
    else:
        print("没有任何组合会卡死 —— 但它依赖的是封顶这一行，别去掉。")

    # 本次要验证的两个测试文件走的是这条路径
    print("\n--- 示例文件实际走的路径 ---")
    for rate, bits, sub in ((44100, 16, 2),):
        engine_out_rate, resample = 48000, True   # rate_policy: 44.1k 家族 -> 48k 家族
        cap = ring_capacity()
        t = target_buffer_bytes(engine_out_rate, sub * 2, cap)
        print(f"  源 {rate}Hz/{bits}bit -> 输出 {engine_out_rate}Hz"
              f"（{'重采样' if resample else '直通'}）")
        print(f"  目标水位 {t:,}B = {t / (engine_out_rate * sub * 2):.3f} 秒"
              f"，容量占比 {t / cap * 100:.1f}%")


if __name__ == "__main__":
    check_all()
    print("\n全部通过。")
