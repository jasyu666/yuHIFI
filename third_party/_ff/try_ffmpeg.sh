#!/bin/bash
# 试验：能否在本机（Windows + git bash + NDK）从源码构建 FFmpeg for Android arm64
#
# 这是个可行性探测，不是最终构建。先跑通 configure，再决定是否值得投入。

set -u
FFDIR="/c/Users/123/Desktop/hifiprobe/third_party/_ff"
NDK="C:/Users/123/AppData/Local/Android/Sdk/ndk/27.0.12077973"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot"

cd "$FFDIR" || exit 1

echo "########## [1/4] 完整下载 FFmpeg 源码 ##########"
rm -f ffmpeg.tar.xz
curl -L --retry 5 --retry-delay 2 -C - -o ffmpeg.tar.xz \
  "https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz"
SIZE=$(stat -c %s ffmpeg.tar.xz 2>/dev/null || echo 0)
echo "下载大小 = $SIZE 字节"
if [ "$SIZE" -lt 9000000 ]; then
  echo "文件仍偏小，可能不完整。继续尝试解压看结果。"
fi

echo
echo "########## [2/4] 解压 ##########"
rm -rf ffmpeg-7.1
if tar -xf ffmpeg.tar.xz 2>&1; then
  echo "解压成功"
else
  echo "解压失败（下载不完整？）"
fi
if [ ! -f ffmpeg-7.1/configure ]; then
  echo "缺少 configure，中止"
  exit 1
fi
echo "源码文件数: $(find ffmpeg-7.1 -type f 2>/dev/null | wc -l)"

echo
echo "########## [3/4] 检查 NDK 交叉编译工具 ##########"
for f in clang.exe llvm-ar.exe llvm-ranlib.exe llvm-nm.exe llvm-strip.exe; do
  printf "  %-18s " "$f"
  if [ -f "$BIN/$f" ]; then echo "有"; else echo "缺失"; fi
done
echo "  目标专用 wrapper:"
ls "$BIN" 2>/dev/null | grep -E "^aarch64-linux-android[0-9]+-clang" | head -4

echo
echo "########## [4/4] 运行 configure（arm64-v8a, android-26）##########"
cd ffmpeg-7.1 || exit 1
CC="$BIN/clang.exe"
CCFLAGS="--target=aarch64-linux-android26 --sysroot=$SYSROOT"

timeout 900 ./configure \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$BIN/clang++.exe" \
  --ar="$BIN/llvm-ar.exe" \
  --ranlib="$BIN/llvm-ranlib.exe" \
  --nm="$BIN/llvm-nm.exe" \
  --strip="$BIN/llvm-strip.exe" \
  --sysroot="$SYSROOT" \
  --extra-cflags="$CCFLAGS" \
  --extra-ldflags="$CCFLAGS" \
  --prefix="$FFDIR/out/arm64-v8a" \
  --disable-everything \
  --disable-programs \
  --disable-doc \
  --disable-avdevice \
  --disable-swscale \
  --disable-postproc \
  --disable-avfilter \
  --disable-network \
  --disable-autodetect \
  --enable-small \
  --enable-shared \
  --disable-static \
  2>&1 | tail -40

RC=${PIPESTATUS[0]}
echo
echo "configure 退出码 = $RC"
if [ "$RC" -eq 0 ] && [ -f config.h ]; then
  echo "✔ configure 成功 —— 值得继续构建"
  echo "--- config.h 头部 ---"
  head -12 config.h
else
  echo "✘ configure 未成功，需要换方案"
fi
