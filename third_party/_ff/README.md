# FFmpeg for Android —— 构建说明

> ## ★ 只想重编的话，看这一个脚本就够
>
> **`rebuild_with_wma.sh` 是唯一权威的重编脚本。** 它自带前置检查、组件校验
> 和产物对比，路径全部相对脚本自己，换台机器也能跑。
>
> **本目录里另外那几个 `try_ffmpeg*.sh` / `build_ffmpeg.sh` / `install_zig.sh`
> 是当年的探索稿，保留只为了记录过程，不要拿去跑** —— 它们写死了原作者机器上的
> 绝对路径，而且配方是错的（正是不工作才被淘汰掉的）。真正的配方是在
> `rebuild_with_wma.sh` 里。
>
> ★ **FFmpeg 源码树不在本仓库里**，跑脚本前先按它的注释下载
> `ffmpeg-7.1.tar.xz` 解到本目录。

本目录下的产物供 `app/src/main/cpp/CMakeLists.txt` 链接使用。

```
out/arm64-v8a/
├── include/   libavcodec/ libavformat/ libavutil/ libswresample/
└── lib/       libavcodec.so libavformat.so libavutil.so libswresample.so
```

产物同时被复制到 `app/src/main/jniLibs/arm64-v8a/` 以便打进 APK。

---

## 为什么这件事在 Windows 上不好做

FFmpeg 的 `configure` 是一个 POSIX shell 脚本，而且**需要一个"宿主编译器"**——
不是给 Android 用的交叉编译器，而是能生成 Windows 本机可执行文件的编译器。

原因不是探测性的，而是硬需求：`libavcodec` 里的表生成器要在构建时
**编译成宿主程序并运行**，把结果写进 `.h`：

```
libavcodec/mpegaudio_tablegen.c    ← MP3 解码器需要
libavcodec/pcm_tablegen.c          ← PCM 需要
libavcodec/cbrt_tablegen.c
```

这台机器上没有任何宿主编译器（无 MinGW、无 MSVC C++ 工作负载），
因此走了一条非常规路线：**用 zig 自带的 C 编译器充当宿主 cc**。

zig 内置 clang 后端以及 MinGW-w64 的头文件与运行库，`zig cc` 可以直接
产出并运行 Windows 可执行文件，正好补上这一环。

## 三个踩过的坑

| 现象 | 原因 | 解法 |
|---|---|---|
| `Unable to create and execute files in C:\Users\...\Temp` | 从 Windows 继承的 `TMPDIR` 带反斜杠，被 bash 剥掉转义符后变成 `C:Users...Temp` | 把 `TMPDIR` 设为短的 POSIX 路径（如 `/c/Users/123/fftmp`） |
| `Host compiler lacks C11 support` / `gcc: command not found` | 缺宿主编译器 | 装 zig，用包装脚本 `hostcc` 包一层 `zig cc`（因为 `host_cc` 会被当成单个命令调用，不能带空格） |
| `java.net.BindException: Address already in use` | Gradle 守护进程残留占端口，与 FFmpeg 无关 | `gradle --stop` 后重试 |

## 复现步骤

```bash
# 1) 装 zig（约 75MB）
bash install_zig.sh          # 下载解压到 C:\Users\123\zig，并生成 hostcc 包装脚本

# 2) 配置
bash try_ffmpeg3.sh          # 若 ffmpeg-7.1 源码不在，先用 try_ffmpeg.sh 下载解压

# 3) 构建 + 安装
bash build_ffmpeg.sh
cd ffmpeg-7.1 && make install
```

## 当前的 configure 参数要点

```
--target-os=android --arch=aarch64 --enable-cross-compile
--cc=<NDK>/aarch64-linux-android26-clang
--host-cc=<zig>/hostcc          ← 关键
--disable-everything            ← 只要解码，不要编码/视频/网络
--enable-shared --disable-static
--enable-decoder=flac,alac,ape,mp3,aac,vorbis,opus,wavpack,pcm_*
--enable-demuxer=flac,alac,ape,mp3,aac,ogg,wav,wv,aiff,mov,matroska
--enable-protocol=file
```

产物 1.7MB，`License: LGPL version 2.1 or later`（未开 `--enable-gpl`，
不会传染成 GPL）。

**用动态库而非静态库是刻意的**：LGPL 下动态链接才不必提供可重链接的
目标文件，合规上最省事。

## 一个容易误判的点

FFmpeg 在 Linux 上会生成 `libavcodec.so.61.19.100` + 两个符号链接，
打包进 Android 时很麻烦。但对 `target_os=android`，configure 会自动
`disable symver`，**soname 就是不带版本号的 `libavcodec.so`**，
`DT_NEEDED` 引用的也是无版本名，直接丢进 `jniLibs` 即可。

## 尚未构建的 ABI

只构建了 `arm64-v8a`。若需 `armeabi-v7a`，改 `--arch=arm --cpu=armv7-a`
并用 `armv7a-linux-androideabi26-clang`，再跑一遍即可。
