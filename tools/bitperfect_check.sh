#!/usr/bin/env bash
#
# bitperfect_check.sh —— 一条命令做完端到端 bit-perfect 验证。
#
# 用法：
#   ./bitperfect_check.sh <原始音频文件> <从手机拉回来的 dump.raw>
#
# 它会：
#   1. 用 ffmpeg 把源文件解成**和输出端同格式**的裸 PCM（默认 24bit/3字节/小端）
#   2. 和 dump 逐字节比对，给出结论或诊断
#
# 关键前提（不满足则比对必然失败，但不是缺陷）：
#   · 播放时**没有重采样** —— 源文件采样率必须落在设备的"正常"档
#     （48000 / 96000 / 192000 / 384000），44.1k 家族会被重采样，必然不同
#   · 没开任何音量 / DSP 处理
#   · --bytes 要和输出端实际用的子帧长度一致
#
# 拉 dump 的命令（注意 MSYS_NO_PATHCONV，否则 Git Bash 会把 /sdcard 当 Windows 路径）：
#   MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/com.hifiprobe/files/hifiprobe_dump.raw \
#       /c/某个目录/hifiprobe_dump.raw
#
# 需要 ffmpeg。默认从 PATH 里找；不在 PATH 上就显式给一个：
#   FFMPEG=/c/某个目录/ffmpeg.exe ./bitperfect_check.sh ...

set -euo pipefail

FFMPEG="${FFMPEG:-ffmpeg}"
BYTES=3          # 每样本每声道字节数：24bit -> 3
CHANNELS=2

if [ $# -lt 2 ]; then
    sed -n '2,26p' "$0"
    exit 2
fi

SRC="$1"
DUMP="$2"
[ -f "$SRC" ]  || { echo "✗ 找不到源文件：$SRC"; exit 2; }
[ -f "$DUMP" ] || { echo "✗ 找不到转储文件：$DUMP"; exit 2; }
# FFMPEG 可能只是 PATH 上的一个名字（默认就是 "ffmpeg"），所以 -x 判不了，
# 得先问 PATH。两条都不成立才算找不到。
{ command -v "$FFMPEG" >/dev/null 2>&1 || [ -x "$FFMPEG" ]; } \
    || { echo "✗ 找不到 ffmpeg：$FFMPEG（可用 FFMPEG=/绝对路径/ffmpeg 覆盖）"; exit 2; }

REF="$(dirname "$DUMP")/$(basename "$DUMP").ref.s${BYTES}"

echo "── 源文件信息 ──"
"${FFMPEG%ffmpeg.exe}ffprobe.exe" -v error \
    -show_entries stream=codec_name,sample_rate,channels,bits_per_raw_sample \
    -of default=nw=1 "$SRC"

echo
echo "── 生成参考 PCM（s$((BYTES * 8))le）──"
"$FFMPEG" -v error -y -i "$SRC" -f "s$((BYTES * 8))le" "$REF"
echo "$REF"

echo
echo "── 逐字节比对 ──"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
python "$SCRIPT_DIR/compare_dump.py" "$DUMP" "$REF" --channels "$CHANNELS" --bytes "$BYTES"
