# 第三方组件与许可

本仓库自身代码以 **PolyForm Noncommercial 1.0.0** 授权，见 [LICENSE.md](LICENSE.md)。

下列组件各自保留其许可证，**不受**本仓库许可约束，亦不因被本应用使用而改变。

---

## 一、FFmpeg 7.1

| | |
|---|---|
| 用途 | 除 DSD 外的全部音频解码与重采样 |
| 许可 | **LGPL-2.1-or-later** |
| 链接方式 | **动态链接**（`libavcodec` / `libavformat` / `libavutil` / `libswresample`） |
| 是否修改 | **否。源码为未经修改的上游原版** |
| 源码 | <https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz> |
| 主页 | <https://ffmpeg.org/> |

### 构建配置

本应用使用裁剪版 FFmpeg（`--disable-everything` 加白名单）。因此「FFmpeg 支持 X」并不等于「本应用支持 X」，权威判据是构建产物中的 `config_components.h`。

**未启用 `--enable-gpl`，亦未启用 `--enable-nonfree`。** 二者任一都会使许可变为 GPL 或不可再分发，从而使本应用无法以非商业许可发布。

完整 configure 参数见 [`third_party/_ff/rebuild_with_wma.sh`](third_party/_ff/rebuild_with_wma.sh)，该脚本可重建出与本仓库二进制一致的产物。

### LGPL 合规说明

LGPL-2.1 允许商业与非商业分发，条件是接收者能够取得库的源码并替换库的版本。本项目通过以下方式满足：

1. **采用动态链接**（非静态链接）。接收者可直接将 `jniLibs/arm64-v8a/` 下的 `libav*.so` 替换为自己编译的版本，无需重新编译本应用的任何代码。
2. 源码为**未经修改的上游原版**，可从上述官方地址获取，版本号与本仓库一致。
3. 重建脚本随仓库分发，构建参数完整可复现。
4. **书面要约**：如需 FFmpeg 7.1 的完整对应源码，可联系仓库维护者索取。本要约自本仓库发布之日起**三年内**有效。

FFmpeg 的许可原文随源码树分发，亦可在 <https://ffmpeg.org/legal.html> 查阅。

---

## 二、libusb 1.0.27

| | |
|---|---|
| 用途 | 独占 USB 音频接口、提交 isochronous 传输 |
| 许可 | **LGPL-2.1-or-later** |
| 链接方式 | **静态链接**（源码随仓库分发于 `third_party/libusb-1.0.27/`） |
| 是否修改 | **否。** 仅新增一份 `CMakeLists.txt`，按官方 `android/jni/libusb.mk` 给出的权威源文件清单构建 |
| 源码 | <https://github.com/libusb/libusb/releases/tag/v1.0.27> |
| 主页 | <https://libusb.info/> |

### LGPL 合规说明

libusb 以**静态**方式链接。LGPL-2.1 对静态链接的要求，是接收者必须能够修改该库并重新链接出可用的程序。本项目满足该条件：

- **libusb 的完整源码位于本仓库中**（`third_party/libusb-1.0.27/`），而非外部下载的二进制
- **本应用的全部源码同样公开**，任何人都可以在修改 libusb 后重新编译整个工程
- 构建脚本（`app/src/main/cpp/CMakeLists.txt`）位于仓库中，链接方式可复现

即：完整的「修改库 → 重新编译 → 得到新程序」链路对任何接收者均可行。

---

## 三、Android / Kotlin 生态（Apache-2.0）

| 组件 | 版本 | 许可 |
|---|---|---|
| AndroidX Core KTX | 1.13.1 | Apache-2.0 |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 |
| Material Components for Android | 1.12.0 | Apache-2.0 |
| Kotlinx Coroutines (Android) | 1.8.1 | Apache-2.0 |
| Kotlin Standard Library | 随 Kotlin 插件 | Apache-2.0 |
| libc++ / LLVM runtime（NDK 27.0.12077973） | 随 NDK | Apache-2.0 **with LLVM Exception** |
| Material Design Icons（`ic_lock` / `ic_lock_open` 等矢量图标） | — | Apache-2.0 |

许可证原文：<https://www.apache.org/licenses/LICENSE-2.0>

---

## 四、仅用于测试

| 组件 | 版本 | 许可 |
|---|---|---|
| JUnit | 4.13.2 | Eclipse Public License 1.0 |

仅在 `testImplementation` 作用域使用，**不会**进入发布的 APK。

---

## 五、一处更正

本仓库早期的开发文档曾记录重采样使用 **libsoxr**，该记录有误。当前实现使用的是 FFmpeg 自带的 **libswresample**（见 `app/src/main/cpp/resampler.cpp` 中的 `swr_alloc_set_opts2`）。工程中**不包含** libsoxr，也不依赖它。

---

## 六、本仓库不包含的内容

- **FFmpeg 源码树**不在仓库中（8500 余个上游文件）。重建方法见 `third_party/_ff/rebuild_with_wma.sh` 的注释。
- **预编译的 FFmpeg 头文件与 `.so`** 包含在仓库中（`third_party/_ff/out/arm64-v8a/`），为构建所必需；缺少时 CMake 会直接报错。运行期使用的那份位于 `app/src/main/jniLibs/arm64-v8a/`，进入 APK 的即为它。
- **APK 成品**不纳入版本控制，请从 Releases 页面下载。
