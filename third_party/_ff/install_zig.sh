#!/bin/bash
# 安装 zig 并验证它能充当宿主编译器。
#
# zig 自带完整的 C 编译器（clang 后端）以及 MinGW-w64 的头文件与运行库，
# 因此 `zig cc` 可以在没有装 MSVC / MinGW 的 Windows 上直接产出可执行的
# 宿主程序 —— 这正是 FFmpeg 的 configure 所缺的那一环。

set -u
ZIGDIR="/c/Users/123/zig"
mkdir -p "$ZIGDIR"
cd "$ZIGDIR" || exit 1

if [ -x "$ZIGDIR/zig/zig.exe" ]; then
  echo "zig 已存在，跳过下载"
else
  echo "########## 下载 zig ##########"
  OK=0
  for URL in \
    "https://ziglang.org/download/0.13.0/zig-windows-x86_64-0.13.0.zip" \
    "https://ziglang.org/download/0.14.0/zig-x86_64-windows-0.14.0.zip" \
    "https://ziglang.org/download/0.14.0/zig-windows-x86_64-0.14.0.zip"
  do
    echo "尝试: $URL"
    rm -f zig.zip
    if curl -L --retry 2 -f -o zig.zip "$URL"; then
      SZ=$(stat -c %s zig.zip 2>/dev/null || echo 0)
      echo "  下载成功，$SZ 字节"
      if [ "$SZ" -gt 30000000 ]; then OK=1; break; fi
      echo "  文件偏小，换下一个"
    else
      echo "  失败"
    fi
  done

  if [ "$OK" -ne 1 ]; then
    echo "✘ 所有下载地址都失败"
    exit 1
  fi

  echo
  echo "########## 解压 ##########"
  rm -rf zig tmpz
  mkdir -p tmpz
  unzip -q -o zig.zip -d tmpz || { echo "解压失败"; exit 1; }
  INNER=$(ls tmpz | head -1)
  echo "解压出: $INNER"
  mv "tmpz/$INNER" zig
  rm -rf tmpz zig.zip
fi

echo
echo "########## 验证 zig ##########"
ZIG="$ZIGDIR/zig/zig.exe"
ls -la "$ZIG" 2>/dev/null || { echo "找不到 zig.exe"; exit 1; }
"$ZIG" version 2>&1 | head -2

echo
echo "########## 关键验证：zig cc 能否生成并运行宿主程序 ##########"
T="/c/Users/123/fftmp"
mkdir -p "$T"
cat > "$T/hosttest.c" <<'EOF'
#include <stdio.h>
#include <ctype.h>
int main(void){ printf("host cc ok, C11=%d\n", __STDC_VERSION__ >= 201112L); return 0; }
EOF

"$ZIG" cc -std=c11 -o "$T/hosttest.exe" "$T/hosttest.c" 2>&1 | tail -5
if [ -f "$T/hosttest.exe" ]; then
  echo "编译成功，运行结果："
  "$T/hosttest.exe"
  echo "✔ zig cc 可以作为宿主编译器"
else
  echo "✘ zig cc 未能产出可执行文件"
fi

echo
echo "########## 建立 hostcc 包装脚本 ##########"
# FFmpeg 的 configure 会把 host_cc 当成单个命令来调用，
# 因此用脚本包一层，避免 "zig cc" 这种带空格的命令解析出问题
cat > "$ZIGDIR/hostcc" <<EOF
#!/bin/sh
exec "$(cygpath -w "$ZIG" 2>/dev/null || echo "$ZIG")" cc "\$@"
EOF
chmod +x "$ZIGDIR/hostcc"
echo "已生成 $ZIGDIR/hostcc"
cat "$ZIGDIR/hostcc"
