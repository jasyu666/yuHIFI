#!/usr/bin/env python3
"""
compare_dump.py —— 把 app 导出的「输出字节转储」和 PC 上的参考 PCM 逐字节比对。

这是 bit-perfect 的**唯一**判据。前面所有指标（欠载 0、URB 零失败、缺口 0）
都只说明"没出错"，说明不了"送出去的字节和文件里的一模一样" ——
重采样、位深转换、字节序搞反，全都能在那些指标全绿的情况下发生。

用法
----
    python compare_dump.py <dump.raw> <reference.raw> [--channels 2] [--bytes 3]

参考 PCM 用 ffmpeg 生成，位深和字节序必须和输出端一致：

    # 24bit / 3 字节 / 小端（本项目的 alt 2 直通路径）
    ffmpeg -i in.flac -f s24le ref.s24

    # 16bit / 2 字节 / 小端
    ffmpeg -i in.flac -f s16le ref.s16

判定
----
    完全相同                      -> bit-perfect 得证
    dump 是 ref 的前缀            -> 送出去的都对，只是没播完（正常，非缺陷）
    ref 是 dump 的前缀            -> dump 比文件还长（多出来的部分是静音？见下）
    都不满足                      -> 逐项诊断：整体平移 / 字节序 / 位深错位

诊断的思路是"先排除可解释的差异，剩下的才是真问题"。所以脚本不会只说
"不一样"，而会告诉你最像哪一种。
"""

import argparse
import sys
from pathlib import Path

# Windows 控制台默认是 GBK，直接 print ✓/✗/⚠ 会 UnicodeEncodeError。
# 强制切到 UTF-8，否则报告跑到一半就崩，看不到结论。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

# 一次读这么多做快速定位，避免几十 MB 全量载入后再慢慢找
CHUNK = 1 << 20


def first_diff(a: bytes, b: bytes) -> int:
    """返回首个不同字节的下标；完全相同返回 -1。"""
    n = min(len(a), len(b))
    if a[:n] == b[:n]:
        return -1
    lo, hi = 0, n
    while lo < hi:                       # 二分，几十 MB 也能秒出
        mid = (lo + hi) // 2
        if a[:mid] == b[:mid]:
            lo = mid + 1
        else:
            hi = mid
    return lo


def find_offset(hay: bytes, needle: bytes, limit: int = 1 << 24) -> int:
    """
    在 hay 的前 limit 字节里找 needle 的首次出现，找不到返回 -1。

    用来判断"是不是整体平移了" —— 例如 dump 从文件的第 N 个帧才开始，
    那 dump 的开头会出现在 ref 的 N*frame_bytes 处。

    探针只取 1KB：取太长（比如 64KB）会越过参考文件的末尾，
    于是"平移"这种本来能查出来的情况反而找不到 —— 实测踩过。
    """
    return hay[:limit].find(needle[:1024])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dump", type=Path)
    ap.add_argument("reference", type=Path)
    ap.add_argument("--channels", type=int, default=2)
    ap.add_argument("--bytes", type=int, default=3, dest="frame_bytes",
                    help="每样本每声道字节数：2=16bit, 3=24bit, 4=32bit")
    args = ap.parse_args()

    for p in (args.dump, args.reference):
        if not p.exists():
            print(f"✗ 找不到文件：{p}")
            return 2

    dump = args.dump.read_bytes()
    ref = args.reference.read_bytes()
    fb = args.frame_bytes * args.channels

    print(f"转储文件  : {args.dump}  {len(dump):,} 字节")
    print(f"参考 PCM  : {args.reference}  {len(ref):,} 字节")
    print(f"帧格式    : {args.channels}ch × {args.frame_bytes}B = {fb} B/帧")

    if len(dump) % fb != 0:
        print(f"⚠ 转储长度不是整帧（{len(dump)} % {fb} = {len(dump) % fb}）——"
              f"字节流在某个地方被截断了")

    n = min(len(dump), len(ref))
    print(f"比对长度  : {n:,} 字节（{n // fb:,} 帧）")
    print()

    # ---- 1. 前缀关系：最常见、且完全正常的两种情况 ----

    if dump == ref:
        print("✓✓ 逐字节完全相同 —— bit-perfect 得证")
        print(f"   {len(dump):,} 字节 / {len(dump) // fb:,} 帧，一个字节都没变。")
        return 0

    if dump == ref[:len(dump)]:
        print("✓ 转储是参考的**前缀** —— 送出去的每一个字节都对")
        print(f"   只是没播完：转储 {len(dump) // fb:,} 帧 < 文件 {len(ref) // fb:,} 帧")
        print(f"   （少 {len(ref) - len(dump):,} 字节 / "
              f"{(len(ref) - len(dump)) // fb:,} 帧）")
        print()
        print("   这**不算缺陷** —— 转储上限 32MB，长曲子会被截断；")
        print("   或者你在放完之前按了停止。想验完整首就把上限调大或验短文件。")
        return 0

    if ref == dump[:len(ref)]:
        extra = len(dump) - len(ref)
        print("✓ 参考是转储的**前缀** —— 文件内容全部原样送出")
        print(f"   但转储后面还多了 {extra:,} 字节（{extra // fb:,} 帧）。")
        print()
        print("   常见原因：文件放完之后 USB 线程又按 microframe 节奏喂了静音。")
        print("   看看多出来的是不是全 0：")
        tail = dump[len(ref):]
        print(f"     全 0？ {'是 —— 收尾静音，不是缺陷' if not any(tail) else '否 —— 需要查'}")
        return 0 if not any(tail) else 1

    # ---- 2. 有实质差异，开始诊断 ----

    d = first_diff(dump, ref)
    print(f"✗ 第 {d:,} 字节起不同（第 {d // fb:,} 帧，帧内偏移 {d % fb}）")
    print(f"   转储: {dump[d:d + 16].hex(' ')}")
    print(f"   参考: {ref[d:d + 16].hex(' ')}")
    print()

    frame_no, in_frame = divmod(d, fb)
    print(f"   → 差异落在第 {frame_no:,} 帧的第 {in_frame} 字节"
          f"（该帧是 {'左' if in_frame < args.frame_bytes else '右'}声道）")
    print()

    # ---- 先跑几个"可解释的差异"检查，全部落空才谈得上真问题 ----

    # 整体平移？
    off = find_offset(ref, dump)
    if off > 0:
        print(f"   ⚠ 转储的开头出现在参考的第 {off:,} 字节"
              f"（第 {off // fb:,} 帧）——像是**整体平移**了一个偏移")
        print(f"     若 {off} % {fb} == {off % fb}，说明是从整帧边界开始的平移")
        return 1

    # 字节序？
    probe = min(len(dump), 1 << 20)
    swapped = bytearray()
    for i in range(0, probe, args.frame_bytes):
        swapped += dump[i:i + args.frame_bytes][::-1]
    if bytes(swapped) == ref[:len(swapped)]:
        print("   ⚠ 把每个样本的字节**倒过来**就完全一致 —— **字节序反了**")
        print(f"     参考用的是小端（-f s{args.frame_bytes * 8}le）还是大端？")
        return 1

    # 位深错位？把转储当 16bit 读、参考当 24bit 读之类的组合，靠长度先看
    if len(dump) % fb == 0 and len(ref) % fb == 0:
        ratio = len(dump) / max(1, len(ref))
        if abs(ratio - 1.5) < 0.01 or abs(ratio - 1 / 1.5) < 0.01:
            print(f"   ⚠ 两者长度比约 {ratio:.3f} ≈ 3/2 —— 像是**位深不一致**"
                  f"（16bit vs 24bit）")
            return 1

    # ---- 到这里就只剩"内容真的变了" ----

    # 是"从某个点开始就全不一样"，还是"零星几个字节不一样"？
    # 前者是系统性错误（错位、格式），后者才像偶发损坏。
    tail_dump, tail_ref = dump[d:], ref[d:]
    m = min(len(tail_dump), len(tail_ref))
    step = max(1, m // 100000)
    idx = list(range(0, max(1, m - fb), max(1, step // fb * fb) or fb))
    same_ratio = (sum(1 for i in idx if tail_dump[i:i + fb] == tail_ref[i:i + fb])
                  / max(1, len(idx)))
    print(f"   差异点之后仍有 {same_ratio * 100:.1f}% 的**整帧**是相同的")
    if same_ratio < 0.05:
        print("   → 像是**从这里开始整体错位/变形**（系统性），不是偶发损坏")
    else:
        print("   → 像是**零星损坏**（偶发），后面的数据还能对上")

    print()
    print("   排查顺序建议：")
    print("     1) 确认参考 PCM 的位深/字节序和输出端一致（-f s24le vs s16le）")
    print("     2) 确认播放时**没有经过重采样**（转储是给 DAC 的，重采样后本就不同）")
    print("     3) 确认没开音量处理 / 任何 DSP")
    print("     4) 若差异是零星几个字节，查 URB 填充与环形缓冲的边界")
    return 1


if __name__ == "__main__":
    sys.exit(main())
