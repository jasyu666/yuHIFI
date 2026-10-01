#!/bin/bash
# 构建 FFmpeg for Android arm64-v8a
#
# 潜在坑：Windows 命令行长度上限 32KB，而 libavcodec 链接时要传几百个 .o。
# 若报 "command line too long"，需要改用响应文件或静态库。

set -u
FFDIR="/c/Users/123/Desktop/hifiprobe/third_party/_ff"
NDK="/c/Users/123/AppData/Local/Android/Sdk/ndk/27.0.12077973"
MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe"

export TMPDIR="/c/Users/123/fftmp"
export TMP="$TMPDIR"; export TEMP="$TMPDIR"
mkdir -p "$TMPDIR"

cd "$FFDIR/ffmpeg-7.1" || exit 1
echo "make = $MAKE"
"$MAKE" --version 2>&1 | head -1

echo
echo "########## make -j16 ##########"
START=$(date +%s)
"$MAKE" -j16 2>&1 | tail -60
RC=${PIPESTATUS[0]}
END=$(date +%s)
echo
echo "make 退出码 = $RC   耗时 $((END-START)) 秒"

if [ "$RC" -eq 0 ]; then
  echo
  echo "########## 构建产物 ##########"
  find . -name "*.so*" -o -name "*.a" 2>/dev/null | head -30
  echo
  echo "--- 各库大小 ---"
  ls -la libavcodec/libavcodec.so* libavformat/libavformat.so* \
         libavutil/libavutil.so* libswresample/libswresample.so* 2>/dev/null
else
  echo "构建失败，看上面最后几行"
fi
