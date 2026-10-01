#!/bin/bash
# FFmpeg 交叉编译 —— 第三次尝试：用 zig cc 充当宿主编译器
#
# 前两次的障碍已排除：
#   1) configure 无法在 TMPDIR 创建测试文件  → 改用短 POSIX 路径
#   2) 找不到宿主编译器 gcc                  → 用 zig cc（自带 MinGW-w64 运行时）
#
# FFmpeg 确实需要宿主编译器：libavcodec 里的 *_tablegen.c 要编译成宿主程序
# 并在构建时运行，把生成的表写进 .h 文件（mpegaudio / pcm / cbrt 等）。

set -u
FFDIR="/c/Users/123/Desktop/hifiprobe/third_party/_ff"
NDK="/c/Users/123/AppData/Local/Android/Sdk/ndk/27.0.12077973"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
ZIGDIR="/c/Users/123/zig"
HOSTCC="$ZIGDIR/hostcc"

export TMPDIR="/c/Users/123/fftmp"
export TMP="$TMPDIR"; export TEMP="$TMPDIR"
mkdir -p "$TMPDIR"

echo "########## 前置检查 ##########"
for f in "$ZIGDIR/zig/zig.exe" "$HOSTCC" "$BIN/aarch64-linux-android26-clang"; do
  printf "  %-60s " "$f"
  [ -e "$f" ] && echo "OK" || { echo "缺失"; exit 1; }
done
echo "  hostcc 自测:"
"$HOSTCC" -std=c11 -o "$TMPDIR/hc.exe" -x c - <<'EOF' 2>&1 | tail -3
#include <stdio.h>
int main(void){ printf("host cc works\n"); return 0; }
EOF
[ -f "$TMPDIR/hc.exe" ] && "$TMPDIR/hc.exe" || { echo "hostcc 不可用"; exit 1; }

echo
echo "########## 运行 configure ##########"
cd "$FFDIR/ffmpeg-7.1" || exit 1

timeout 1500 ./configure \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$BIN/aarch64-linux-android26-clang" \
  --cxx="$BIN/aarch64-linux-android26-clang++" \
  --ar="$BIN/llvm-ar.exe" \
  --ranlib="$BIN/llvm-ranlib.exe" \
  --nm="$BIN/llvm-nm.exe" \
  --strip="$BIN/llvm-strip.exe" \
  --host-cc="$HOSTCC" \
  --host-ld="$HOSTCC" \
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
  --enable-swresample \
  --enable-decoder=flac,alac,ape,mp3,aac,vorbis,opus,wavpack,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_s16be,pcm_s24be,pcm_u8 \
  --enable-demuxer=flac,alac,ape,mp3,aac,ogg,wav,wv,aiff,mov,matroska \
  --enable-parser=flac,alac,ape,mpegaudio,aac,vorbis,opus \
  --enable-protocol=file \
  2>&1 | tail -30

RC=${PIPESTATUS[0]}
echo
echo "configure 退出码 = $RC"
if [ "$RC" -eq 0 ] && [ -f config.h ]; then
  echo "✔✔✔ configure 成功 —— zig 方案奏效"
  echo
  echo "接下来检查关键组件是否启用："
  for c in CONFIG_FLAC_DECODER CONFIG_ALAC_DECODER CONFIG_APE_DECODER \
           CONFIG_MP3_DECODER CONFIG_WAV_DEMUXER CONFIG_SWRESAMPLE; do
    printf "  %-28s " "$c"
    grep -c "^#define $c 1" config.h 2>/dev/null | tr -d '\n'; echo
  done
else
  echo "✘ configure 仍失败，config.log 末尾："
  tail -25 ffbuild/config.log 2>/dev/null
fi
