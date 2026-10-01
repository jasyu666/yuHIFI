#!/bin/bash
# 重编 FFmpeg：在白名单里加上 ASF 容器 + WMA 系解码器，让 .wma 能播。
#
# 背景见 docs/08-音频格式支持.md。
# 原构建是 --disable-everything + 白名单，所以 asf / wmav1 / wmav2 / wmalossless
# 压根没编进去 —— 这不是名单问题，是二进制里没有。
#
# 用法：bash rebuild_with_wma.sh          （在 Git Bash 里跑）
# 产物：third_party/_ff/out/arm64-v8a/lib/*.so
#
# ★ configure 参数是照抄 ffbuild/config.mak 里那行 FFMPEG_CONFIGURATION 的，
#   只加了三处（见下面 ### 新增 ###）。别自己重写，不然会和原构建不一致。
#
# ════════════════════════════════════════════════════════════════════════
#  前置：下载源码
# ════════════════════════════════════════════════════════════════════════
#
# ★★ FFmpeg 源码树**不在本仓库里**（8500 多个上游文件，放进来只会
#    把仓库的语言统计压成「C 97%」，还让每次 clone 多拉一份跟本项目
#    无关的代码）。所以第一次跑这个脚本之前要自己下：
#
#     curl -LO https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz
#     tar -xf ffmpeg-7.1.tar.xz -C third_party/_ff/
#
#    脚本会自己检查在不在，不在就直接告诉你这两条命令。
#
# ★ 本脚本产出的 .so 要再拷进 app/src/main/jniLibs/arm64-v8a/（进 APK 的那份）。
#   只跑 make 是不够的 —— 见下面 [4/4] 的说明。
#
# ════════════════════════════════════════════════════════════════════════
#  可覆盖的环境变量（都有默认值，换机器时按需设）
# ════════════════════════════════════════════════════════════════════════
#
#   ANDROID_NDK_HOME   NDK 根目录。默认去 $ANDROID_SDK_ROOT/ndk/ 下找，
#                      再退回 ~/AppData/Local/Android/Sdk/ndk/。
#   HOSTCC             host 编译器。FFmpeg 的 configure 要用它编译几个
#                      跑在 PC 上的小工具。默认从 PATH 里找 cc/clang/gcc。
#   FFMPEG_SRC         源码目录名，默认 ffmpeg-7.1

set -u

# ── 路径：全部相对脚本自己，不再写死任何人的用户名 ──
FFDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK_VER="27.0.12077973"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  for base in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
              "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk"; do
    [ -n "$base" ] && [ -d "$base/ndk/$NDK_VER" ] && {
      ANDROID_NDK_HOME="$base/ndk/$NDK_VER"; break; }
  done
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "!! 找不到 NDK $NDK_VER。设一下 ANDROID_NDK_HOME 再跑："
  echo "     ANDROID_NDK_HOME=/c/Users/你/AppData/Local/Android/Sdk/ndk/$NDK_VER bash $0"
  exit 1
fi

NDK="$ANDROID_NDK_HOME"
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -d "$TC" ] || TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe"
[ -f "$MAKE" ] || MAKE="$(command -v make)"
OUT="$FFDIR/out/arm64-v8a"
SRC="$FFDIR/${FFMPEG_SRC:-ffmpeg-7.1}"

if [ ! -d "$SRC" ]; then
  echo "!! 找不到 FFmpeg 源码：$SRC"
  echo
  echo "   它不在本仓库里，先下载（约 11MB）："
  echo "     curl -LO https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz"
  echo "     tar -xf ffmpeg-7.1.tar.xz -C \"$FFDIR\""
  echo
  echo "   ★ 必须是 **未修改的上游原版** —— 本仓库的 FFmpeg 就是这么编出来的。"
  exit 1
fi

# host 编译器：FFmpeg 的 configure 要编几个跑在 PC 上的小工具
if [ -z "${HOSTCC:-}" ]; then
  for c in cc clang gcc; do
    command -v "$c" >/dev/null 2>&1 && { HOSTCC="$(command -v "$c")"; break; }
  done
fi
[ -n "${HOSTCC:-}" ] || { echo "!! 找不到 host 编译器，设一下 HOSTCC=... 再跑"; exit 1; }

# 临时目录：NDK 的长路径 + Windows 8.3 限制，放个短的
export TMPDIR="${TMPDIR:-${TEMP:-/tmp}}/ffbuild"
export TMP="$TMPDIR"; export TEMP="$TMPDIR"
mkdir -p "$TMPDIR"

echo "NDK      = $NDK"
echo "源码     = $SRC"
echo "host cc  = $HOSTCC"
echo "临时目录 = $TMPDIR"
echo

cd "$SRC" || exit 1

echo "############ [1/3] configure ############"
START=$(date +%s)

./configure \
  --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
  --cc="$TC/aarch64-linux-android26-clang" \
  --cxx="$TC/aarch64-linux-android26-clang++" \
  --ar="$TC/llvm-ar.exe" \
  --ranlib="$TC/llvm-ranlib.exe" \
  --nm="$TC/llvm-nm.exe" \
  --strip="$TC/llvm-strip.exe" \
  --host-cc="$HOSTCC" \
  --host-ld="$HOSTCC" \
  --prefix="$OUT" \
  --disable-everything --disable-programs --disable-doc --disable-avdevice \
  --disable-swscale --disable-postproc --disable-avfilter --disable-network \
  --disable-autodetect --enable-small --enable-shared --disable-static \
  --enable-swresample \
  --enable-decoder='flac,alac,ape,mp3,aac,vorbis,opus,wavpack,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_s16be,pcm_s24be,pcm_u8,wmav1,wmav2,wmalossless,wmapro' \
  --enable-demuxer='flac,alac,ape,mp3,aac,ogg,wav,wv,aiff,mov,matroska,asf' \
  --enable-parser='flac,alac,ape,mpegaudio,aac,vorbis,opus' \
  --enable-protocol=file \
  2>&1 | tail -40

RC=${PIPESTATUS[0]}
echo "configure 退出码 = $RC   耗时 $(( $(date +%s) - START )) 秒"
[ "$RC" -ne 0 ] && { echo "!! configure 失败，不要继续"; exit 1; }

echo
echo "############ [2/3] 先验组件真的进来了 ############"
FAIL=0
check() {                      # check <文件> <宏名> <期望>
  v=$(grep -m1 "define CONFIG_$2 " "$1" 2>/dev/null | awk '{print $NF}')
  printf "  %-28s = %s\n" "CONFIG_$2" "${v:-未找到}"
  [ "${v:-}" != "$3" ] && { echo "     !! 期望 $3"; FAIL=1; }
}
check config_components.h ASF_DEMUXER 1
check config_components.h MATROSKA_DEMUXER 1
check config_components.h OGG_DEMUXER 1
check config_components.h WMAV1_DECODER 1
check config_components.h WMAV2_DECODER 1
check config_components.h WMALOSSLESS_DECODER 1
check config_components.h WMAPRO_DECODER 1
check config_components.h FLAC_DECODER 1

if [ "$FAIL" -ne 0 ]; then
  echo
  echo "!! 有组件没进去 —— 别跑 make，先看上面的 configure 输出。"
  exit 1
fi

echo
echo "############ [3/3] make -j16 ############"
echo "（几百个 .o，慢慢等）"
START=$(date +%s)
"$MAKE" -j16 2>&1 | tail -80
RC=${PIPESTATUS[0]}
echo
echo "make 退出码 = $RC   耗时 $(( $(date +%s) - START )) 秒"

if [ "$RC" -ne 0 ]; then
  echo "!! 构建失败，看上面最后几行"
  exit 1
fi

echo
echo "############ 产物 ############"
ls -la "$OUT"/lib/libavcodec.so* "$OUT"/lib/libavformat.so* \
       "$OUT"/lib/libavutil.so* "$OUT"/lib/libswresample.so* 2>/dev/null

echo
echo "############ 和旧版比大小（变大才对，多了 asf + 4 个解码器）############"
for f in libavcodec libavformat; do
  echo "  $f.so  $(stat -c%s "$OUT/lib/$f.so" 2>/dev/null) 字节"
done
echo
echo "✅ 完成。下一步：把 .so 同步进 app/src/main/jniLibs/arm64-v8a/，然后重新出 APK。"
