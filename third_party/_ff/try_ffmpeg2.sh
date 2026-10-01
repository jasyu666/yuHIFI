#!/bin/bash
# FFmpeg 交叉编译可行性试验 —— 第二版
#
# 第一版失败原因：从 Windows 继承的 TMPDIR 是 "C:\Users\...\Temp" 这种带反斜杠的
# 形式，被 bash 剥掉转义符后变成 "C:Users...Temp"，configure 无法在其中创建
# 并执行测试脚本。改成短的 POSIX 路径即可。
#
# 另外改用 NDK 提供的目标专用 wrapper（aarch64-linux-androidNN-clang），
# 它内部已设好 --target/--sysroot，比手工传 clang.exe --target= 可靠。

set -u
FFDIR="/c/Users/123/Desktop/hifiprobe/third_party/_ff"
NDK="/c/Users/123/AppData/Local/Android/Sdk/ndk/27.0.12077973"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"

# ★ 关键修复：短、纯 ASCII、无空格的 POSIX 临时目录
export TMPDIR="/c/Users/123/fftmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
mkdir -p "$TMPDIR"
echo "TMPDIR = $TMPDIR  (Windows 视角: $(cygpath -w "$TMPDIR" 2>/dev/null))"

echo
echo "########## 可用的目标 clang wrapper ##########"
ls "$BIN" | grep -E "^aarch64-linux-android[0-9]+-clang$" | head -20

# 选一个 >= minSdk(26) 的
API=26
CC="$BIN/aarch64-linux-android${API}-clang"
CXX="$BIN/aarch64-linux-android${API}-clang++"
if [ ! -f "$CC" ]; then
  echo "找不到 $CC，列出所有 aarch64 wrapper 供排查"
  ls "$BIN" | grep aarch64 | head -20
  exit 1
fi
echo "使用: $CC"

echo
echo "########## 运行 configure ##########"
cd "$FFDIR/ffmpeg-7.1" || exit 1

timeout 1200 ./configure \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$BIN/llvm-ar.exe" \
  --ranlib="$BIN/llvm-ranlib.exe" \
  --nm="$BIN/llvm-nm.exe" \
  --strip="$BIN/llvm-strip.exe" \
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
  2>&1 | tail -35

RC=${PIPESTATUS[0]}
echo
echo "configure 退出码 = $RC"
if [ "$RC" -eq 0 ] && [ -f config.h ]; then
  echo "✔✔ configure 成功 —— FFmpeg 路线可行"
  grep -E "^(CONFIG_|ARCH_|HAVE_)" config.h | head -8
else
  echo "✘ 仍未成功，看 ffbuild/config.log 末尾："
  tail -20 ffbuild/config.log 2>/dev/null
fi
