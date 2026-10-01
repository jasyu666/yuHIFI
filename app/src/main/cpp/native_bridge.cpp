/*
 * native_bridge.cpp —— JNI 桥
 *
 * 职责边界（重要）：
 *   Java 侧只做 UsbManager.openDevice() 拿到已授权的 fd，其余全部在原生层完成。
 *   原因是 libusb_wrap_sys_device() 包裹的就是 UsbDeviceConnection 持有的那个
 *   fd，两边同时 claim 同一个接口会互相打架。因此：
 *     - Java 不得在原生层工作期间调用 claimInterface()
 *     - Java 不得在 nativeClose() 之前调用 UsbDeviceConnection.close()，
 *       否则 fd 被关闭，原生层后续 ioctl 全部 EBADF
 */
#include <jni.h>
#include <android/log.h>

#include <libusb.h>

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "audio_engine.h"
#include "iso_player.h"
#include "system_audio_sink.h"
#include "media_probe.h"
#include "rate_policy.h"
#include "thread_priority.h"
#include "tone_source.h"

#define LOG_TAG "HiFiNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_errMutex;
std::string g_lastError;

void setLastError(const std::string& msg) {
    std::lock_guard<std::mutex> lk(g_errMutex);
    g_lastError = msg;
    LOGE("%s", msg.c_str());
}

struct NativeCtx {
    libusb_context* ctx = nullptr;
    libusb_device_handle* devh = nullptr;
    int fd = -1;
    std::vector<int> claimedInterfaces;
    hifi::IsoPlayer player;

    // 播放期间必须存活，IsoPlayer 只在 start() 时拿到裸指针。
    // 换数据源时（测试音 / 真实文件）在这里替换。
    std::unique_ptr<hifi::PcmSource> source;

    // 真实文件播放。engine 持有解码器与环形缓冲，
    // player 通过 engine.source() 消费数据。
    hifi::AudioEngine engine;

    /*
     * 系统音频输出（AAudio）—— 和 player 是**并列**的另一个输出端，
     * 同一次播放只会起其中一个（由 PlayerSession 按输出模式决定）。
     *
     * ★ 它消费的是**同一个** engine.source()，所以解码线程、环形缓冲、
     *   重采样器、欠载记账全部照用，那条路只是把字节交给 AAudio 而不是 USB。
     */
    hifi::SystemAudioSink sink;

    /*
     * 系统给的采样率（AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE）。
     * **非 0 就表示这次走系统音频**；0 = 走 USB 直连。还没问过时也是 0。
     */
    int systemRate = 0;

    // 音量请求可用的 wIndex 构造与通道号，探测一次后缓存。
    // -1 表示尚未探测（或不可控）。
    // 缓存是必要的：拖音量滑条时会连续下发几十次 SET_CUR，
    // 每次都重试几种组合会白白多出几倍的控制传输。
    int volumeWIndex = -1;
    int volumeChannel = -1;

    // ---- 以下几项都在起播时读取一次，中途改不影响正在播的这段 ----

    // A/B 开关：是否跟随设备的 feedback 速率
    bool followFeedback = true;

    /*
     * 在途 URB 深度。同时决定抗调度卡顿能力与 seek 迟钝程度（两者绑死）。
     *
     * 默认 32：实测这是能消除断音的最小值（16 及以下会卡）。选最小值而不是
     * 更大值，是因为 seek 后旧音频的尾巴长度**等于**队列深度 —— 够用就好。
     * 界面上保留 64 / 128 备选，将来换设备或系统负载变重导致卡顿超过余量时
     * 可以现场往上调，不必重新编译。
     */
    int urbCount = 32;

    // seek 时是否立即丢弃在途 URB（丢掉旧位置的声音，代价是一小段静音）
    bool flushQueueOnSeek = false;

    /*
     * 输出字节转储（bit-perfect 验证用）。
     *
     * 路径由 Java 侧给出（getExternalFilesDir），停止播放时把累积的字节写过去。
     * 默认关 —— 它要占 32MB 内存，正常听歌不需要。
     */
    bool dumpEnabled = false;
    std::string dumpPath;
};

NativeCtx* asCtx(jlong h) {
    return reinterpret_cast<NativeCtx*>(static_cast<intptr_t>(h));
}

jstring toJString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

const char* speedName(int speed) {
    switch (speed) {
        case LIBUSB_SPEED_LOW: return "1.5M (Low)";
        case LIBUSB_SPEED_FULL: return "12M (Full)";
        case LIBUSB_SPEED_HIGH: return "480M (High)";
        case LIBUSB_SPEED_SUPER: return "5G (Super)";
        case LIBUSB_SPEED_SUPER_PLUS: return "10G (SuperPlus)";
        default: return "未知";
    }
}

const char* syncTypeName(int attr) {
    switch ((attr >> 2) & 0x03) {
        case 0: return "None (异步 Asynchronous, 需读 feedback)";
        case 1: return "Asynchronous (异步, 需读 feedback)";
        case 2: return "Adaptive (自适应)";
        case 3: return "Synchronous (同步)";
        default: return "?";
    }
}

const char* usageTypeName(int attr) {
    switch ((attr >> 4) & 0x03) {
        case 0: return "Data";
        case 1: return "Feedback";
        case 2: return "Implicit Feedback Data";
        default: return "?";
    }
}

std::string hexDump(const unsigned char* p, int len) {
    static const char* d = "0123456789ABCDEF";
    std::string out;
    out.reserve(static_cast<size_t>(len) * 3);
    for (int i = 0; i < len; ++i) {
        if (i) out += ' ';
        out += d[(p[i] >> 4) & 0xF];
        out += d[p[i] & 0xF];
    }
    return out;
}

// 用 libusb 自己解析出的描述符树生成报告，与 Java 侧的原始字节解析互为交叉验证
std::string describeHandle(NativeCtx* c) {
    std::ostringstream os;

    /*
     * ★ 系统音频模式的上下文是**无设备**的（devh == nullptr）——
     *   这一层以前从没遇到过空句柄，因为上下文只有在设备打开成功时才存在。
     *   有了第二条不需要设备的路之后，碰 devh 之前必须过这一关。
     */
    if (c->devh == nullptr) {
        return "当前是系统音频模式，没有 USB 设备（切到「USB 直连」再试）\n";
    }

    libusb_device* dev = libusb_get_device(c->devh);
    if (dev == nullptr) {
        return "libusb_get_device 返回空，fd 可能已失效\n";
    }

    struct libusb_device_descriptor dd{};
    int r = libusb_get_device_descriptor(dev, &dd);
    if (r < 0) {
        return std::string("读取设备描述符失败: ") + libusb_error_name(r) + "\n";
    }

    os << "libusb 视角的设备信息\n";
    os << "  VID:PID = " << std::hex << dd.idVendor << ":" << dd.idProduct
       << std::dec << "\n";
    os << "  bcdUSB = 0x" << std::hex << dd.bcdUSB << std::dec
       << "   bDeviceClass = " << static_cast<int>(dd.bDeviceClass) << "\n";
    os << "  速度 = " << speedName(libusb_get_device_speed(dev)) << "\n";
    os << "  bus " << libusb_get_bus_number(dev)
       << " / addr " << libusb_get_device_address(dev) << "\n";
    os << "  配置数 = " << static_cast<int>(dd.bNumConfigurations) << "\n";

    // 字符串描述符读取失败不影响主流程（部分设备会拒绝）
    unsigned char sbuf[256];
    if (dd.iManufacturer) {
        int n = libusb_get_string_descriptor_ascii(c->devh, dd.iManufacturer, sbuf, sizeof(sbuf));
        if (n > 0) os << "  厂商 = " << reinterpret_cast<char*>(sbuf) << "\n";
    }
    if (dd.iProduct) {
        int n = libusb_get_string_descriptor_ascii(c->devh, dd.iProduct, sbuf, sizeof(sbuf));
        if (n > 0) os << "  产品 = " << reinterpret_cast<char*>(sbuf) << "\n";
    }

    for (int ci = 0; ci < dd.bNumConfigurations; ++ci) {
        struct libusb_config_descriptor* cfg = nullptr;
        r = libusb_get_config_descriptor(dev, static_cast<uint8_t>(ci), &cfg);
        if (r < 0) {
            os << "  配置 " << ci << " 读取失败: " << libusb_error_name(r) << "\n";
            continue;
        }

        os << "\n配置 " << ci << ": 接口数=" << static_cast<int>(cfg->bNumInterfaces)
           << "  wTotalLength=" << cfg->wTotalLength
           << "  MaxPower=" << static_cast<int>(cfg->MaxPower) * 2 << "mA\n";

        for (int ii = 0; ii < cfg->bNumInterfaces; ++ii) {
            const struct libusb_interface& itf = cfg->interface[ii];
            for (int ai = 0; ai < itf.num_altsetting; ++ai) {
                const struct libusb_interface_descriptor& id = itf.altsetting[ai];
                os << "  [接口 " << static_cast<int>(id.bInterfaceNumber)
                   << " alt " << static_cast<int>(id.bAlternateSetting) << "]"
                   << " class=" << static_cast<int>(id.bInterfaceClass)
                   << " subclass=" << static_cast<int>(id.bInterfaceSubClass)
                   << " proto=0x" << std::hex << static_cast<int>(id.bInterfaceProtocol)
                   << std::dec
                   << " 端点=" << static_cast<int>(id.bNumEndpoints);

                if (id.bInterfaceClass == 0x01) {
                    os << (id.bInterfaceSubClass == 0x01 ? "  [AudioControl]"
                        : id.bInterfaceSubClass == 0x02 ? "  [AudioStreaming]"
                                                        : "  [Audio 其他]");
                    if (id.bInterfaceProtocol == 0x20) os << " UAC2";
                    else if (id.bInterfaceProtocol == 0x00) os << " UAC1";
                }
                os << "\n";

                for (int ei = 0; ei < id.bNumEndpoints; ++ei) {
                    const struct libusb_endpoint_descriptor& ed = id.endpoint[ei];
                    const int type = ed.bmAttributes & 0x03;
                    os << "      端点 0x" << std::hex
                       << static_cast<int>(ed.bEndpointAddress) << std::dec
                       << "  类型=";
                    switch (type) {
                        case 0: os << "Control"; break;
                        case 1: os << "Isochronous"; break;
                        case 2: os << "Bulk"; break;
                        case 3: os << "Interrupt"; break;
                        default: os << "?"; break;
                    }
                    os << "  同步=" << syncTypeName(ed.bmAttributes)
                       << "  用途=" << usageTypeName(ed.bmAttributes)
                       << "  wMaxPacketSize=" << ed.wMaxPacketSize
                       << "  bInterval=" << static_cast<int>(ed.bInterval);
                    if (type == 1) {
                        os << "  bRefresh=" << static_cast<int>(ed.bRefresh)
                           << "  bSynchAddress=0x" << std::hex
                           << static_cast<int>(ed.bSynchAddress) << std::dec;
                    }
                    os << "\n";

                    // 类专属描述符原始字节，交给上层做 UAC 细节解析
                    if (ed.extra_length > 0) {
                        os << "        [EP 类专属描述符 " << ed.extra_length
                           << "B] " << hexDump(ed.extra, ed.extra_length) << "\n";
                    }
                }

                if (id.extra_length > 0) {
                    os << "      [接口类专属描述符 " << id.extra_length
                       << "B] " << hexDump(id.extra, id.extra_length) << "\n";
                }
            }
        }

        libusb_free_config_descriptor(cfg);
    }

    return os.str();
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_errMutex);
    return toJString(env, g_lastError);
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeVersion(JNIEnv* env, jobject) {
    std::ostringstream os;
    os << "libusb " << libusb_get_version()->major << "."
       << libusb_get_version()->minor << "."
       << libusb_get_version()->micro;
    return toJString(env, os.str());
}

/*
 * 用 Java 传来的 fd 建立 libusb 设备句柄。
 * LIBUSB_OPTION_NO_DEVICE_DISCOVERY 必须在 libusb_init 之前设置：
 * 非 root 的 Android 应用没有 /dev/bus/usb 的读权限，一旦 libusb 尝试枚举
 * 整个 USB 总线就会失败。跳过枚举后，libusb 只操作我们交给它的这个 fd。
 */
JNIEXPORT jlong JNICALL
Java_com_hifiprobe_NativePlayer_nativeOpen(JNIEnv* env, jobject, jint fd) {
    /*
     * ★★ fd < 0 = 建一个**无设备上下文**。
     *
     *   引擎（解码器 + 环形缓冲 + 两个输出端）住在 NativeCtx 里，而 NativeCtx
     *   原来**只能**在 nativeOpen(fd) 里创建 —— 于是"没有小尾巴就什么都放不了"，
     *   因为压根没有引擎。系统音频那条路必须能脱离 USB 设备存在。
     *
     *   无设备上下文里 devh = nullptr / ctx = nullptr，所有需要 USB 的入口
     *   都会在各自的"句柄为空"检查处拒绝；而非 USB 的那些（开文件、解码、
     *   seek、AAudio 输出）照常工作。
     */
    if (fd < 0) {
        auto* c = new NativeCtx();
        LOGI("nativeOpen：无设备上下文（系统音频模式，engine 可用）");
        return static_cast<jlong>(reinterpret_cast<intptr_t>(c));
    }

    int r = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (r != LIBUSB_SUCCESS) {
        setLastError(std::string("libusb_set_option(NO_DEVICE_DISCOVERY) 失败: ") +
                     libusb_error_name(r));
        return 0;
    }

    libusb_context* ctx = nullptr;
    r = libusb_init(&ctx);
    if (r < 0) {
        setLastError(std::string("libusb_init 失败: ") + libusb_error_name(r));
        return 0;
    }

    libusb_device_handle* devh = nullptr;
    r = libusb_wrap_sys_device(ctx, static_cast<intptr_t>(fd), &devh);
    if (r < 0) {
        setLastError(std::string("libusb_wrap_sys_device 失败: ") +
                     libusb_error_name(r) +
                     "（这是 P0 最关键的一步，失败说明该 ROM 无法用此方案）");
        libusb_exit(ctx);
        return 0;
    }
    if (devh == nullptr) {
        setLastError("libusb_wrap_sys_device 返回了空句柄");
        libusb_exit(ctx);
        return 0;
    }

    auto* c = new NativeCtx();
    c->ctx = ctx;
    c->devh = devh;
    c->fd = fd;

    // 允许自动解绑内核驱动。Android 上 snd-usb-audio 通常已绑定该接口，
    // 不解绑的话 claim 会返回 BUSY。
    libusb_set_auto_detach_kernel_driver(devh, 1);

    LOGI("nativeOpen 成功, fd=%d", fd);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(c));
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeDescribe(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");
    return toJString(env, describeHandle(c));
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeClaim(JNIEnv* env, jobject, jlong h,
                                            jint ifaceNum, jint altSetting) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    std::ostringstream os;
    int r = libusb_claim_interface(c->devh, ifaceNum);
    os << "claim_interface(" << ifaceNum << ") -> ";
    if (r < 0) {
        os << "失败: " << libusb_error_name(r) << " (" << r << ")\n";
        if (r == LIBUSB_ERROR_BUSY) {
            os << "  接口被占用。可能原因：Java 侧已 claim 未释放，"
                  "或系统音频 HAL 仍持有该设备。\n";
        } else if (r == LIBUSB_ERROR_ACCESS) {
            os << "  权限不足。SELinux 可能拦截了 USBDEVFS_CLAIMINTERFACE。\n";
        } else if (r == LIBUSB_ERROR_NOT_FOUND) {
            os << "  接口不存在，检查接口号是否正确。\n";
        }
        setLastError(os.str());
        return toJString(env, os.str());
    }
    c->claimedInterfaces.push_back(ifaceNum);
    os << "成功\n";

    if (altSetting >= 0) {
        r = libusb_set_interface_alt_setting(c->devh, ifaceNum, altSetting);
        os << "set_interface_alt_setting(" << ifaceNum << ", " << altSetting
           << ") -> ";
        if (r < 0) {
            os << "失败: " << libusb_error_name(r) << " (" << r << ")\n";
            setLastError(os.str());
            return toJString(env, os.str());
        }
        os << "成功（iso 端点已激活）\n";
    }

    return toJString(env, os.str());
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeRelease(JNIEnv* env, jobject, jlong h,
                                              jint ifaceNum) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");
    const int r = libusb_release_interface(c->devh, ifaceNum);
    for (size_t i = 0; i < c->claimedInterfaces.size(); ++i) {
        if (c->claimedInterfaces[i] == ifaceNum) {
            c->claimedInterfaces.erase(c->claimedInterfaces.begin() +
                                       static_cast<long>(i));
            break;
        }
    }
    std::ostringstream os;
    os << "release_interface(" << ifaceNum << ") -> "
       << (r < 0 ? libusb_error_name(r) : "成功") << "\n";
    return toJString(env, os.str());
}

/*
 * 单独切换 alt setting，与 claim 解耦。
 *
 * 之所以要拆开：UAC2 的时钟切换必须在本流接口处于空闲态(alt 0)时进行。
 * 若先把 alt 切到工作值、再发 SET_CUR，设备会在时钟切换时复位内部缓冲，
 * 而已经装载的 iso 端点不会自动重新装载 —— 现象是 URB 零错误、包长完美，
 * 但完全没有声音。正确顺序是「claim → 定时钟 → 再激活 alt」。
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetAltSetting(JNIEnv* env, jobject, jlong h,
                                                    jint ifaceNum, jint alt) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    const int r = libusb_set_interface_alt_setting(c->devh, ifaceNum, alt);
    std::ostringstream os;
    os << "set_interface_alt_setting(" << ifaceNum << ", " << alt << ") -> ";
    if (r == 0) {
        os << "成功";
        if (alt != 0) os << "（iso 端点已激活）";
    } else {
        os << "失败: " << libusb_error_name(r) << " (" << r << ")";
        setLastError(os.str());
    }
    os << "\n";
    return toJString(env, os.str());
}

/*
 * 逐个尝试采样率，报告设备哪些真正接受。
 *
 * 重要限制：SET_CUR 被接受 ≠ 该速率下能正常工作。实测 MOONDROP Dawn Pro
 * 接受 88200Hz、回读也一致，但实际出声异常 —— 那是设备固件问题，描述符和
 * 控制请求都看不出来。所以这个扫描只能用来排除「明确不支持」的速率，
 * 不能用来保证音质。
 *
 * 副作用：扫描会把设备采样率停在最后一个被接受的值上。
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeScanRates(JNIEnv* env, jobject, jlong h,
                                                jint acIface, jint clockId,
                                                jintArray rates) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    const jsize n = env->GetArrayLength(rates);
    std::vector<jint> list(static_cast<size_t>(n));
    env->GetIntArrayRegion(rates, 0, n, list.data());

    const uint16_t wIndex =
        static_cast<uint16_t>((clockId << 8) | (acIface & 0xFF));

    std::ostringstream os;
    os << "扫描各采样率的接受情况 (wIndex=0x" << std::hex << wIndex << std::dec
       << ", ClockID=" << clockId << ")\n";

    int accepted = 0;
    for (jsize i = 0; i < n; ++i) {
        const int rate = list[static_cast<size_t>(i)];
        unsigned char data[4] = {
            static_cast<unsigned char>(rate & 0xFF),
            static_cast<unsigned char>((rate >> 8) & 0xFF),
            static_cast<unsigned char>((rate >> 16) & 0xFF),
            static_cast<unsigned char>((rate >> 24) & 0xFF),
        };
        const int r = libusb_control_transfer(c->devh, 0x21, 0x01, 0x0100,
                                              wIndex, data, 4, 1000);
        os << "  " << rate << "Hz -> ";
        if (r != 4) {
            os << "拒绝 (" << libusb_error_name(r) << ")\n";
            continue;
        }
        ++accepted;

        unsigned char back[4] = {0, 0, 0, 0};
        const int k = libusb_control_transfer(c->devh, 0xA1, 0x81, 0x0100,
                                              wIndex, back, 4, 1000);
        os << "接受";
        if (k == 4) {
            int got = 0;
            for (int j = 0; j < 4; ++j) got |= back[j] << (8 * j);
            os << "，回读 " << got << "Hz" << (got == rate ? " ✓" : " ✗ 不一致");
        } else {
            os << "（回读失败）";
        }
        os << "\n";
    }

    os << "\n共 " << accepted << "/" << n << " 个被接受。\n";
    os << "※ **被接受不等于设备真的换了速率** —— SET_CUR 对任何速率都返回成功，\n";
    os << "  GET_CUR 还会把设定值原样回读（请求 44000 也照样显示「一致 ✓」）。\n";
    os << "  要查出设备**实际**在跑什么速率，用下面的「速率真伪验证」。\n";
    os << "※ 扫描结束后设备停在最后一个被接受的速率上。\n";
    return toJString(env, os.str());
}

/*
 * 速率真伪验证 —— 这是唯一能识破设备的办法。
 *
 * ★ 为什么要实际发流：SET_CUR + GET_CUR 回读**查不出任何东西**。设备对
 *   44000 也回读成 44000，看起来完美一致，而它实际上根本没换速率、
 *   继续按上一次的 48000 在跑（实测：请求 44000 → feedback 报 48001Hz、
 *   Fm=6.0001，正是 48000 的值）。我们按 5.5 样本/µframe 喂、它按 6.0 消耗，
 *   供不上，于是听感上"故障"。
 *
 *   只有 feedback 端点说实话 —— 它报的是设备自己消耗样本的速率，
 *   与我们的组包方式无关。
 *
 * 流程（每个速率一遍）：
 *   alt=0 停流 → SET_CUR → alt=N 起流 → 发 1kHz 正弦 settleMs 毫秒
 *   → 停 → 读 feedback → 算出设备实际速率 → 与请求值比对
 *
 * ★ settleMs × 速率个数 = 本函数的阻塞时长（默认 400ms × 10 ≈ 4 秒），
 *   **必须从后台线程调用**，否则会 ANR。
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeScanRatesLive(
        JNIEnv* env, jobject, jlong h, jint acIface, jint clockId,
        jint asIface, jint asAlt, jint epAddr, jint feedbackEp,
        jint channels, jint subframeSize, jint bitResolution,
        jint maxPacketSize, jint urbCount, jint packetsPerUrb,
        jint settleMs, jintArray rates) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr || c->devh == nullptr) return toJString(env, "句柄为空");

    const jsize n = env->GetArrayLength(rates);
    std::vector<jint> list(static_cast<size_t>(n));
    env->GetIntArrayRegion(rates, 0, n, list.data());

    // 扫描期间要独占设备：解码线程和测试音都得先停
    c->engine.stopDecoding();
    c->player.stop(); c->sink.stop();

    const uint16_t wIndex =
        static_cast<uint16_t>((clockId << 8) | (acIface & 0xFF));
    if (settleMs < 100) settleMs = 100;
    if (settleMs > 2000) settleMs = 2000;

    std::ostringstream os;
    os << "速率真伪验证 —— 判据是设备 feedback 端点，不是 SET_CUR 回读\n";
    os << "每个速率实际发流 " << settleMs << "ms 后读设备回报。"
          "（期间会听到一串短促的 1kHz 蜂鸣，正常）\n";
    os << "端点=0x" << std::hex << epAddr << std::dec
       << "  alt=" << asAlt << "  " << channels << "ch/"
       << bitResolution << "bit/" << subframeSize << "B\n\n";
    os << "   请求速率     设备实测     每µframe 标称/实测    偏差        判定\n";
    os << "  ──────────────────────────────────────────────────────────────\n";

    int good = 0, bad = 0, unknown = 0, skipped = 0;

    for (jsize i = 0; i < n; ++i) {
        const int rate = list[static_cast<size_t>(i)];
        char line[256];

        /*
         * ★★ 带宽预检 —— 必须放在发流**之前**。
         *
         *   每 microframe 要送的字节 = rate × (channels × subframeSize) / 8000
         *   一个 URB 装 packetsPerUrb 个 microframe 的包。
         *
         *   超过**端点自己的容量**（maxPacketSize × packetsPerUrb）就别试了 ——
         *   试的结果是：
         *     · 刷一屏「包总长 X 超过缓冲容量 Y，本次回退为等长包」
         *     · 几十条「libusb_submit_transfer 失败: -2」
         *     · 然后 feedback 端点读到的是设备**空转**的值（还停在上一个速率）
         *   那不是测量，是撞墙。直接算出来标成结论，信息量大得多，
         *   也不用让用户白听一次噪音。
         *
         *   ★ 这一条同时是「每个 32-bit 字装 1 位 DSD」那种说法的**判据**：
         *     按那种算法 DSD256 要 22.6 MB/s（2822 B/µframe），
         *     在 776 B/µframe 的端点这里**当场就会被挡下来**。
         */
        const double pktBytes = static_cast<double>(rate) * channels * subframeSize
                                * packetsPerUrb / 8000.0;
        const double cap = static_cast<double>(maxPacketSize) * packetsPerUrb;
        if (pktBytes > cap) {
            std::snprintf(line, sizeof(line),
                          "  %8d     ——          ——              ——        ⊘ 带宽装不下"
                          "（需 %.0f B/URB，端点只有 %.0f）\n",
                          rate, pktBytes, cap);
            os << line;
            ++skipped;
            continue;
        }

        // ---- 1. 停流。换速率必须在流停止时做，否则设备可能不认 ----
        libusb_set_interface_alt_setting(c->devh, asIface, 0);

        // ---- 2. SET_CUR ----
        unsigned char data[4] = {
            static_cast<unsigned char>(rate & 0xFF),
            static_cast<unsigned char>((rate >> 8) & 0xFF),
            static_cast<unsigned char>((rate >> 16) & 0xFF),
            static_cast<unsigned char>((rate >> 24) & 0xFF),
        };
        const int w = libusb_control_transfer(c->devh, 0x21, 0x01, 0x0100,
                                              wIndex, data, 4, 1000);
        if (w != 4) {
            std::snprintf(line, sizeof(line), "  %8d     ——          ——              ——        ✗ 拒绝\n", rate);
            os << line;
            ++bad;
            continue;
        }

        // ---- 3. 起流 ----
        if (libusb_set_interface_alt_setting(c->devh, asIface, asAlt) != 0) {
            std::snprintf(line, sizeof(line), "  %8d     ——          ——              ——        ✗ 起流失败\n", rate);
            os << line;
            ++bad;
            continue;
        }

        // ---- 4. 发一段测试音 ----
        hifi::OutputConfig cfg;
        cfg.epAddr = static_cast<uint8_t>(epAddr);
        cfg.feedbackEp = static_cast<uint8_t>(feedbackEp);
        cfg.sampleRate = rate;
        cfg.channels = channels;
        cfg.subframeSize = subframeSize;
        cfg.bitResolution = bitResolution;
        cfg.seconds = 0;                 // 由我们主动停
        cfg.maxPacketSize = maxPacketSize;
        cfg.urbCount = urbCount;
        cfg.packetsPerUrb = packetsPerUrb;
        cfg.followFeedback = false;      // ★ 关键：不跟随，否则我们的下发速率
                                         //   会被设备反馈拉走，测不出"设备在跑什么"

        hifi::ToneSource::Config tcfg;
        tcfg.sampleRate = rate;
        tcfg.channels = channels;
        tcfg.subframeSize = subframeSize;
        tcfg.bitResolution = bitResolution;
        tcfg.toneFreqHz = 1000;

        {
            hifi::ToneSource src(tcfg);
            std::string err;
            if (!c->player.start(c->ctx, c->devh, cfg, &src, &err)) {
                std::snprintf(line, sizeof(line), "  %8d     ——          ——              ——        ✗ %s\n",
                              rate, err.c_str());
                os << line;
                ++bad;
                continue;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(settleMs));
            c->player.stop(); c->sink.stop();            // 结束后 stats_ 仍保留，measuredRate 可用
        }

        // ---- 5. 读设备回报 ----
        double hz = 0.0, ppm = 0.0;
        bool perFrame = false;
        if (!c->player.measuredRate(&hz, &ppm, &perFrame)) {
            std::snprintf(line, sizeof(line), "  %8d     ——          ——              ——        ? 无反馈读数\n", rate);
            os << line;
            ++unknown;
            continue;
        }

        // 判定阈值 200ppm：晶振正常精度是 ±50ppm 量级，20ppm 是实测常态；
        // 一旦设备没换速率，偏差是百分之几（几万 ppm），差着三个数量级。
        const bool ok = std::fabs(hz - rate) / rate * 1e6 < 200.0;

        std::snprintf(line, sizeof(line),
                      "  %8d  %9.1f Hz   %8.4f / %-8.4f  %+9.1f ppm   %s\n",
                      rate, hz,
                      static_cast<double>(rate) / 8000.0,
                      perFrame ? hz / 1000.0 : hz / 8000.0,
                      ppm, ok ? "✓ 真的切过去了" : "✗ 设备没换速率");
        os << line;
        if (ok) ++good; else ++bad;
    }

    libusb_set_interface_alt_setting(c->devh, asIface, 0);

    os << "\n结果：真的切过去 " << good << " 个，没切 " << bad
       << " 个，带宽装不下 " << skipped << " 个，无读数 " << unknown << " 个。\n\n";
    os << "※ 判定看「设备实测」是否等于「请求速率」。不等就说明设备**静默忽略**了\n";
    os << "  这个速率、继续按上一次的速率跑 —— 它不会报错，只会让你听到错的音高，\n";
    os << "  而且缓冲最终会被抽干。这就是非标准速率（44000/50000/56000）的故障机理。\n";
    os << "※ ⊘ 是**算出来的，不是测出来的**：那个速率要的带宽超过端点能力，\n";
    os << "  数据根本发不出去。去测它只会拿到设备空转的读数（还停在上一个速率），\n";
    os << "  白白听一次噪音 —— 所以直接挡在发流之前。\n";
    os << "※ 「设备实测」两次跑会有 ±20ppm 上下的微小差异，那是 **DAC 自己晶振**\n";
    os << "  的实测值（随温度漂）。**这恰恰说明读的是真时钟** —— 如果它像 GET_CUR\n";
    os << "  那样回读我们请求的值，两次会一模一样。\n";

    /*
     * ★ 同时写 logcat。
     *
     *   结果以前只进界面 —— 而界面上一大段文本既不好截也不好抄，
     *   远程排查等于没有。⑨ DSD 速率实测 用的是同一个函数，
     *   它更需要这一行（DSD 到底该请求多少 Hz 就看这里）。
     */
    LOGI("速率扫描(alt=%d) 结果：\n%s", asAlt, os.str().c_str());

    return toJString(env, os.str());
}

/*
 * 下发采样率。
 *
 * UAC2 的采样率请求打在 Clock Source 实体上，wIndex = (接口号<<8)|ClockID；
 * UAC1 则打在 iso 数据端点上，wIndex = 端点地址。规范虽然明确，但实测中
 * 不同固件对 wIndex 高字节该填 AC 接口还是 AS 接口并不一致，而
 * LIBUSB_ERROR_IO 又只说明请求没发出去、不告诉你哪里错了。
 *
 * 所以这里不猜，把几种可能的组合依次试一遍并逐个报告结果 ——
 * 哪一行显示成功，答案就是哪个。
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetSampleRate(JNIEnv* env, jobject, jlong h,
                                                    jint uacVersion, jint acIface,
                                                    jint asIface, jint asAlt,
                                                    jint clockId, jint epAddr, jint rate) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    std::ostringstream os;
    const int len = (uacVersion >= 2) ? 4 : 3;
    unsigned char data[4] = {0, 0, 0, 0};
    for (int i = 0; i < len; ++i) {
        data[i] = static_cast<unsigned char>((rate >> (8 * i)) & 0xFF);
    }

    if (uacVersion < 2) {
        // UAC1 没有时钟实体，直接打在 iso 数据端点上，3 字节
        const int r = libusb_control_transfer(c->devh, 0x22, 0x01, 0x0100,
                                              static_cast<uint16_t>(epAddr),
                                              data, 3, 1000);
        os << "SET_CUR " << rate << "Hz (UAC1, 端点 0x"
           << std::hex << epAddr << std::dec << ") -> "
           << (r == 3 ? "成功 ✓" : libusb_error_name(r)) << "\n";
        return toJString(env, os.str());
    }

    // ------------------------------------------------------------------
    //  连通性诊断
    //
    //  用「合法实体」和「明显非法的实体(0x7F)」各发一次 GET_CUR，
    //  两个错误码能把故障定位到完全不同的层面：
    //    · 非法实体返回 PIPE(STALL) → 请求确实到达了设备，设备主动拒绝，
    //      说明只是 wIndex 构造不对，继续换组合即可
    //    · 非法实体同样返回 IO    → 请求在 USB 层就被丢弃，设备根本没看见，
    //      这种情况下换 wIndex 永远无效，必须换思路
    // ------------------------------------------------------------------
    os << "── 类控制请求连通性诊断 ──\n";
    unsigned char buf[4] = {0, 0, 0, 0};

    const uint16_t goodIdx =
        static_cast<uint16_t>((acIface << 8) | (clockId & 0xFF));
    int r = libusb_control_transfer(c->devh, 0xA1, 0x81, 0x0100, goodIdx, buf,
                                    static_cast<uint16_t>(len), 1000);
    os << "  GET_CUR 合法实体 (wIndex=0x" << std::hex << goodIdx << std::dec << ") -> ";
    if (r == len) {
        int got = 0;
        for (int i = 0; i < len; ++i) got |= buf[i] << (8 * i);
        os << "成功，当前 " << got << "Hz ✓\n";
    } else {
        os << "失败: " << libusb_error_name(r) << " (" << r << ")\n";
    }

    const uint16_t badIdx = static_cast<uint16_t>((acIface << 8) | 0x7F);
    r = libusb_control_transfer(c->devh, 0xA1, 0x81, 0x0100, badIdx, buf,
                                static_cast<uint16_t>(len), 1000);
    os << "  GET_CUR 非法实体 (wIndex=0x" << std::hex << badIdx << std::dec << ") -> ";
    if (r == len) {
        os << "居然成功（该设备不校验实体 ID）\n";
    } else {
        os << "失败: " << libusb_error_name(r) << " (" << r << ")\n";
        if (r == LIBUSB_ERROR_PIPE) {
            os << "    ← STALL：请求**到达了设备**，只是参数被拒。\n"
                  "      继续换 wIndex 组合是对的方向。\n";
        } else if (r == LIBUSB_ERROR_IO) {
            os << "    ← 与合法实体报同样的 IO：类请求根本没送到设备，\n"
                  "      换 wIndex 不会有任何效果。\n";
        }
    }

    // ------------------------------------------------------------------
    //  依次尝试各种 wIndex 构造
    //
    //  UAC2 里 Entity ID 与 Interface Number 各占 wIndex 的一个字节，
    //  但哪个在高位、以及该填 AC 还是 AS 接口，不同来源说法不一
    //  （Linux 内核 sound/usb/clock.c 用的是 ClockID 在高位）。
    //  与其猜，不如全试一遍，报告里直接指出哪种成功。
    // ------------------------------------------------------------------
    struct Variant {
        const char* label;
        uint16_t wIndex;
    };
    std::vector<Variant> variants;
    variants.push_back({"wIndex=(AC接口<<8)|ClockID",
                        static_cast<uint16_t>((acIface << 8) | (clockId & 0xFF))});
    if (asIface >= 0 && asIface != acIface) {
        variants.push_back({"wIndex=(AS接口<<8)|ClockID",
                            static_cast<uint16_t>((asIface << 8) | (clockId & 0xFF))});
    }
    variants.push_back({"wIndex=(ClockID<<8)|AC接口",
                        static_cast<uint16_t>((clockId << 8) | (acIface & 0xFF))});
    if (asIface >= 0 && asIface != acIface) {
        variants.push_back({"wIndex=(ClockID<<8)|AS接口",
                            static_cast<uint16_t>((clockId << 8) | (asIface & 0xFF))});
    }

    os << "\n── SET_CUR " << rate << "Hz ──\n";
    int winner = -1;
    for (size_t i = 0; i < variants.size(); ++i) {
        const int rr = libusb_control_transfer(c->devh, 0x21, 0x01, 0x0100,
                                               variants[i].wIndex, data,
                                               static_cast<uint16_t>(len), 1000);
        os << "  " << variants[i].label << " (0x" << std::hex
           << variants[i].wIndex << std::dec << ") -> ";
        if (rr == len) {
            os << "成功 ✓\n";
            winner = static_cast<int>(i);
            break;
        }
        os << "失败: " << libusb_error_name(rr) << " (" << rr << ")\n";
    }

    // 注：这里不再做「降回 alt 0 重试」的兜底。调用方现在本就在接口空闲态
    // 下发时钟（见 MainActivity.startTone 的顺序），若在此处恢复 asAlt 反而会
    // 提前把 iso 端点装载起来，正好触发要避免的那个问题。

    if (winner < 0) {
        os << "\n所有 wIndex 组合都失败。\n";
        setLastError(os.str());
        return toJString(env, os.str());
    }

    // 回读校验，确认设备真的接受了
    unsigned char back[4] = {0, 0, 0, 0};
    const int n = libusb_control_transfer(
        c->devh, 0xA1, 0x81, 0x0100,
        variants[static_cast<size_t>(winner)].wIndex,
        back, static_cast<uint16_t>(len), 1000);
    if (n == len) {
        int got = 0;
        for (int i = 0; i < len; ++i) got |= back[i] << (8 * i);
        os << "GET_CUR 回读 = " << got << "Hz "
           << (got == rate ? "（一致 ✓）" : "（与设置值不一致 ✗）") << "\n";
    } else {
        os << "GET_CUR 回读失败: " << libusb_error_name(n)
           << "（部分设备不支持回读，不代表设置失败）\n";
    }

    return toJString(env, os.str());
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeStartTone(
        JNIEnv* env, jobject, jlong h, jint epAddr, jint feedbackEp, jint rate,
        jint channels, jint subframeSize, jint bitResolution, jint seconds,
        jint maxPacketSize, jint urbCount, jint packetsPerUrb) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    // 测试音用的是 ToneSource，不消费环形缓冲。若此时文件解码线程还在跑，
    // 它会一直往无人消费的缓冲里写，写满后卡在 writeToRing 里空转烧 CPU。
    c->engine.stopDecoding();

    hifi::OutputConfig cfg;
    cfg.epAddr = static_cast<uint8_t>(epAddr);
    cfg.feedbackEp = static_cast<uint8_t>(feedbackEp);
    cfg.sampleRate = rate;
    cfg.channels = channels;
    cfg.subframeSize = subframeSize;
    cfg.bitResolution = bitResolution;
    cfg.seconds = seconds;
    cfg.maxPacketSize = maxPacketSize;
    cfg.urbCount = urbCount;
    cfg.packetsPerUrb = packetsPerUrb;

    hifi::ToneSource::Config tcfg;
    tcfg.sampleRate = rate;
    tcfg.channels = channels;
    tcfg.subframeSize = subframeSize;
    tcfg.bitResolution = bitResolution;
    tcfg.toneFreqHz = 1000;
    c->source = std::make_unique<hifi::ToneSource>(tcfg);

    std::string err;
    if (!c->player.start(c->ctx, c->devh, cfg, c->source.get(), &err)) {
        c->source.reset();
        setLastError(err);
        return toJString(env, "启动失败: " + err + "\n");
    }

    std::ostringstream os;
    os << "已开始播放 1kHz 测试音\n";
    os << "  端点=0x" << std::hex << epAddr << std::dec
       << "  采样率=" << rate << "Hz"
       << "  声道=" << channels
       << "  子帧=" << subframeSize << "B"
       << "  有效位=" << bitResolution << "\n";
    os << "  在途 URB=" << urbCount << " × " << packetsPerUrb << " 包\n";
    os << "如果小尾巴的采样率指示灯变成 " << rate
       << "Hz 对应的颜色并且耳机里有 1kHz 蜂鸣，P0 即通过。\n";
    return toJString(env, os.str());
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeStats(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");
    return toJString(env, c->player.statsReport());
}

JNIEXPORT jboolean JNICALL
Java_com_hifiprobe_NativePlayer_nativeIsPlaying(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return JNI_FALSE;
    // 两个输出端是并列的，同一次播放只会起其中一个 —— 哪个在跑都算"在播"
    return (c->player.isRunning() || c->sink.isRunning()) ? JNI_TRUE : JNI_FALSE;
}

/*
 * 系统音频输出是不是**被系统掐掉了**（取走即清）。
 *
 * ★ 和 nativeIsPlaying 是两件事，不要合并：
 *   那个回答"此刻在不在放"，这个回答"刚刚被掐了、需要重建"。
 */
JNIEXPORT jboolean JNICALL
Java_com_hifiprobe_NativePlayer_nativeSystemOutputDead(JNIEnv*, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return JNI_FALSE;
    return c->sink.takeDisconnected() ? JNI_TRUE : JNI_FALSE;
}

/*
 * 重建系统音频输出。**引擎、环形缓冲、解码线程全都不动** ——
 * 所以是从当前位置接着放，不是从头重来。
 *
 * ★★ 它会阻塞（内部要 openStream），**调用方必须放在一条专用线程上**，
 *    绝对不能是播放会话那条 worker —— 上一版正是栽在这儿：它把 worker
 *    连同整条播放链路一起卡死。
 *    （sink 内部已经不持锁做阻塞调用了，所以卡住它也只卡它自己，
 *      stop() 照样能返回 —— 但"不拖累别人"不等于"不会卡"，这点别忘。）
 *
 * @return 空串 = 成功，否则是失败原因
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeRestartSystemOutput(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");
    return toJString(env, c->sink.restart());
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeStop(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");
    // 先取出统计再 stop，stop 之后 cfg 仍在，但语义上更清晰
    const std::string report = c->player.statsReport();
    c->player.stop(); c->sink.stop();
    return toJString(env, report);
}

// ===========================================================================
//  真实文件播放
// ===========================================================================

/*
 * 打开音频文件。fd 的所有权自此转交给引擎（Kotlin 侧用 detachFd() 转交），
 * 引擎关闭文件时会一并关闭它。
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeOpenFile(JNIEnv* env, jobject, jlong h, jint fd) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    if (c->player.isRunning()) {
        c->player.stop(); c->sink.stop();
    }
    c->engine.close();

    std::string err;
    if (!c->engine.open(fd, &err)) {
        setLastError(err);
        return toJString(env, "打开失败: " + err + "\n");
    }
    return toJString(env, c->engine.openReport());
}

/*
 * 开始播放已打开的文件。
 *
 * 把 claim / 下发采样率 / 激活 alt setting / 启动数据流 放在原生层一次做完，
 * 而不是让 Kotlin 分多步调用 —— 这几步之间有严格的顺序要求：
 *
 *   1) claim AudioControl（UAC2 的时钟请求打在它上面）
 *   2) claim AudioStreaming，但**停在 alt 0**
 *   3) 空闲态下发采样率
 *      ⚠ 若先激活 alt 再改时钟，设备会因时钟切换复位内部缓冲，而已装载的
 *        iso 端点不会自动重新装载 —— 现象是 URB 零错误、包长完美，却完全没声音
 *   4) 时钟就位后再激活 alt setting
 *   5) 启动解码线程与 iso 传输
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeStartPlayback(
        JNIEnv* env, jobject, jlong h, jint uacVersion, jint acIface, jint asIface,
        jint asAlt, jint clockId, jint epAddr, jint feedbackEp,
        jint subframeSize, jint bitResolution, jint maxPacketSize) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    std::ostringstream os;

    if (!c->engine.ready()) {
        return toJString(env, "尚未打开音频文件，请先选择文件");
    }
    if (c->player.isRunning()) {
        c->player.stop(); c->sink.stop();
    }
    // 解码线程也要停。只停播放器的话，旧线程会在我们改线格式、重新预灌的
    // 同时继续往环形缓冲里写，两边的 frameBytes_ 还不是同一个值。
    // 正常路径（Kotlin 先 stopPlayback 再起播）已经停了，这里是兜底。
    c->engine.stopDecoding();

    // 1) claim AudioControl
    if (acIface >= 0 && acIface != asIface) {
        const int r = libusb_claim_interface(c->devh, acIface);
        os << "claim_interface(" << acIface << ") -> "
           << (r == 0 ? "成功" : libusb_error_name(r)) << "\n";
        if (r == 0) c->claimedInterfaces.push_back(acIface);
    }

    // 2) claim AudioStreaming，停在空闲态
    int r = libusb_claim_interface(c->devh, asIface);
    os << "claim_interface(" << asIface << ") -> "
       << (r == 0 ? "成功" : libusb_error_name(r)) << "\n";
    if (r != 0) {
        setLastError(os.str());
        return toJString(env, os.str() + "claim 失败，无法继续\n");
    }
    c->claimedInterfaces.push_back(asIface);

    libusb_set_interface_alt_setting(c->devh, asIface, 0);

    // 3) 空闲态下发采样率
    const int rate = c->engine.outputRate();
    {
        unsigned char data[4] = {
            static_cast<unsigned char>(rate & 0xFF),
            static_cast<unsigned char>((rate >> 8) & 0xFF),
            static_cast<unsigned char>((rate >> 16) & 0xFF),
            static_cast<unsigned char>((rate >> 24) & 0xFF),
        };
        // 实测该设备要求 Entity ID 在高位：wIndex = (ClockID << 8) | 接口号
        const uint16_t wIndex = (uacVersion >= 2)
                ? static_cast<uint16_t>((clockId << 8) | (acIface & 0xFF))
                : static_cast<uint16_t>(epAddr);
        const uint8_t rt = (uacVersion >= 2) ? 0x21 : 0x22;
        const int len = (uacVersion >= 2) ? 4 : 3;
        const int sr = libusb_control_transfer(c->devh, rt, 0x01, 0x0100,
                                               wIndex, data,
                                               static_cast<uint16_t>(len), 1000);
        os << "SET_CUR " << rate << "Hz (wIndex=0x" << std::hex << wIndex << std::dec
           << ") -> " << (sr == len ? "成功" : libusb_error_name(sr)) << "\n";
    }

    // 4) 时钟就位后再激活 alt setting
    r = libusb_set_interface_alt_setting(c->devh, asIface, asAlt);
    os << "set_interface_alt_setting(" << asIface << ", " << asAlt << ") -> "
       << (r == 0 ? "成功（iso 端点已激活）" : libusb_error_name(r)) << "\n";
    if (r != 0) {
        setLastError(os.str());
        return toJString(env, os.str());
    }

    // 5) 启动解码线程与 iso 传输
    hifi::OutputConfig cfg;
    cfg.epAddr = static_cast<uint8_t>(epAddr);
    cfg.feedbackEp = static_cast<uint8_t>(feedbackEp);
    cfg.maxPacketSize = maxPacketSize;
    /*
     * 在途 URB 深度 —— 这是让播放对调度延迟免疫的关键参数。
     *
     * 原来只排 8 个 URB = 每个 URB 覆盖 8 个 microframe = **8ms 余量**。
     * 而实测（A/B 两组各跑一遍）USB 提交线程会被调度器挂起：
     *
     *     超过 4ms: 105 / 107 次      超过 8ms: 17 / 14 次      最大 16~25ms
     *
     * 队列一被耗尽，设备就收不到数据 —— 那正是用户听到的"短暂静音"。
     * 注意这时我们的所有计数器都是干净的（不欠载、URB 零失败），
     * 因为 `fill()` 根本没被调用，故障发生在"没提交"这个动作的缺席上。
     *
     * 线程**一定会**被卡十几到二十几毫秒（这是实测），所以不跟调度器较劲，
     * 直接把余量做到 64 个 URB = **64ms**：即使卡 25ms，队列里还剩 39ms 的数据。
     *
     * 代价：每个 URB 缓冲约 6.4KB，64 个约 400KB 内存；设备侧在途数据多 64ms
     * （相对 1 秒深的环形缓冲可以忽略）。
     */
    constexpr int kPacketsPerUrb = 8;

    cfg.urbCount = c->urbCount;
    cfg.packetsPerUrb = kPacketsPerUrb;
    cfg.seconds = 0;
    cfg.followFeedback = c->followFeedback;
    cfg.flushQueueOnSeek = c->flushQueueOnSeek;
    c->engine.fillOutputConfig(&cfg, subframeSize, bitResolution);

    // 转储只对"直通"有意义：一旦经过重采样，送出去的字节本来就该和文件不同。
    // 开着也不会有害（转储的就是实际送出的字节），但报告里要说清楚。
    c->player.setDumpEnabled(c->dumpEnabled);

    c->engine.source()->resetUnderrun();
    c->engine.setPaused(false);
    if (!c->engine.startDecoding()) {
        setLastError("启动解码线程失败");
        return toJString(env, os.str() + "启动解码线程失败\n");
    }

    std::string err;
    if (!c->player.start(c->ctx, c->devh, cfg, c->engine.source(), &err)) {
        c->engine.stopDecoding();
        setLastError(err);
        return toJString(env, os.str() + "启动 iso 传输失败: " + err + "\n");
    }

    os << "\n开始播放\n";
    return toJString(env, os.str());
}

/*
 * A/B 开关：是否跟随设备的 feedback 速率。
 *
 * 只在**起播时**生效 —— 中途变速会让下发速率跳变，那本身就会引入一次卡顿，
 * 把对比结果污染掉。所以改这个开关后需要重新开始播放。
 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetFollowFeedback(JNIEnv* env, jobject, jlong h,
                                                        jboolean follow) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    c->followFeedback = (follow != JNI_FALSE);
    LOGI("feedback 跟随: %s（下次起播生效）", c->followFeedback ? "开" : "关");
}

/* 在途 URB 深度。下次起播生效。 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetUrbCount(JNIEnv* env, jobject, jlong h,
                                                  jint count) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    if (count < 2) count = 2;
    if (count > 256) count = 256;       // 再深内核多半会拒，且 seek 迟钝得没法用
    c->urbCount = count;
    LOGI("在途 URB 深度: %d（下次起播生效）", count);
}

/* seek 时是否丢弃在途 URB。下次起播生效。 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetFlushQueueOnSeek(JNIEnv* env, jobject, jlong h,
                                                          jboolean enable) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    c->flushQueueOnSeek = (enable != JNI_FALSE);
    LOGI("seek 清队: %s（下次起播生效）", c->flushQueueOnSeek ? "开" : "关");
}

/*
 * 输出字节转储开关（bit-perfect 验证用）。
 *
 * path 由 Java 侧给出（getExternalFilesDir，免存储权限）。停止播放时落盘。
 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetOutputDump(JNIEnv* env, jobject, jlong h,
                                                    jboolean enable, jstring path) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    c->dumpEnabled = (enable != JNI_FALSE);
    if (path != nullptr) {
        const char* s = env->GetStringUTFChars(path, nullptr);
        if (s != nullptr) {
            c->dumpPath = s;
            env->ReleaseStringUTFChars(path, s);
        }
    }
    LOGI("输出字节转储: %s -> %s", c->dumpEnabled ? "开" : "关",
         c->dumpPath.empty() ? "(未指定路径)" : c->dumpPath.c_str());
}

/*
 * 探测一首曲目的元信息（文件库扫描用）。
 *
 * 返回 String[9]：
 *   [0] 标题  [1] 艺术家  [2] 专辑  [3] 编码
 *   [4] 采样率 [5] 声道数 [6] 位深 [7] 时长(ms)
 *   [8] 封面写出的字节数（0 = 没有封面）
 *
 * ★ 封面用**写文件**而不是返回 byte[] 来传。
 *   一次扫描有几百首，每首的内嵌封面动辄几百 KB，全走 JNI 数组
 *   就是几百 MB 的来回拷贝。让原生层直接落盘、只回一个字节数，
 *   既省掉那份拷贝，又顺带把封面缓存到了磁盘上，下次启动直接读。
 *
 * coverOutPath 为空表示不要封面（只读标签，更快）。
 */
/*
 * 按需读取**一首**的内嵌歌词。没有就返回空数组。
 *
 * ★★ 为什么不复用 nativeProbeFile 的返回值：那是个 String[9]，
 *   往里加一项等于让**每次扫描**（一千多首）都多拷一份歌词过 JNI。
 *   而歌词只在正在播放页用得上、一次只看一首 —— 单独一个接口干净得多。
 *
 * ★★ 为什么返回 byte[] 而不是 String：
 *   `NewStringUTF` 要求**合法的 modified UTF-8**，遇到编码不规范的标签
 *   （ID3v2.3 不带编码标志时按 Latin-1 存的很常见）会直接 abort。
 *   传字节、让 Kotlin 侧用 `String(bytes, UTF_8)` 解码，
 *   非法字节会被替换成 U+FFFD，**最多显示成乱码，不会崩**。
 */
JNIEXPORT jbyteArray JNICALL
Java_com_hifiprobe_NativePlayer_nativeProbeLyrics(JNIEnv* env, jobject, jint fd) {
    hifi::MediaInfo info;
    std::string err;
    if (!hifi::probeFd(fd, &info, &err)) {
        // 探测失败不是错误路径 —— 大量文件本来就没有歌词标签
        LOGW("歌词探测失败: %s", err.c_str());
    }
    const jsize n = static_cast<jsize>(info.lyrics.size());
    jbyteArray arr = env->NewByteArray(n);
    if (arr != nullptr && n > 0) {
        env->SetByteArrayRegion(arr, 0, n,
                                reinterpret_cast<const jbyte*>(info.lyrics.data()));
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_hifiprobe_NativePlayer_nativeProbeFile(JNIEnv* env, jobject, jint fd,
                                                jstring coverOutPath) {
    hifi::MediaInfo info;
    std::string err;
    const bool ok = hifi::probeFd(fd, &info, &err);
    if (!ok) {
        LOGW("probeFd 失败: %s", err.c_str());
    }

    size_t coverWritten = 0;
    if (ok && info.hasCover() && coverOutPath != nullptr) {
        const char* p = env->GetStringUTFChars(coverOutPath, nullptr);
        if (p != nullptr && p[0] != '\0') {
            FILE* f = std::fopen(p, "wb");
            if (f != nullptr) {
                coverWritten = std::fwrite(info.cover.data(), 1, info.cover.size(), f);
                std::fclose(f);
            }
        }
        if (p != nullptr) env->ReleaseStringUTFChars(coverOutPath, p);
    }

    auto makeArr = [&](const std::vector<std::string>& vals) {
        jclass strCls = env->FindClass("java/lang/String");
        jobjectArray arr = env->NewObjectArray(
            static_cast<jsize>(vals.size()), strCls, nullptr);
        for (jsize i = 0; i < static_cast<jsize>(vals.size()); ++i) {
            env->SetObjectArrayElement(arr, i, toJString(env, vals[i]));
        }
        return arr;
    };

    if (!ok) {
        // 失败也要返回定长数组，调用方按长度取字段，不必额外判空
        return makeArr({"", "", "", "", "0", "0", "0", "0", "0"});
    }

    return makeArr({
        info.title,
        info.artist,
        info.album,
        info.codec,
        std::to_string(info.sampleRate),
        std::to_string(info.channels),
        std::to_string(info.bitsPerSample),
        std::to_string(info.durationMs),
        std::to_string(coverWritten),
    });
}

/*
 * 是否信任 44.1k 家族（8 个标准速率全部直通）。
 *
 * 默认开。关掉则退回旧的「只走 48k 家族、44.1k 家族一律重采样」策略，
 * 行为与 p17 及以前**逐字节一致** —— 留作现场回退用。
 *
 * ★ 不加 handle 参数：这个标志是全局的，且必须在**打开文件之前**设置好
 *   （速率决策发生在 openFile 里）。这样界面初始化时就能填，不必等设备打开。
 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetAllowAllStandardRates(JNIEnv*, jobject,
                                                               jboolean allow) {
    const bool v = (allow != JNI_FALSE);
    hifi::g_allowAllStandardRates.store(v, std::memory_order_relaxed);
    LOGI("44.1k 家族直通: %s（下次打开文件生效）",
         v ? "开——8 个标准速率全直通，44.1k 曲库比特完美"
           : "关——退回旧策略，44.1k 家族重采样到 48k 家族");
}

/*
 * 把**设备自己声明的速率能力**交给引擎。
 *
 * ★ 必须在 nativeOpenFile 之前调 —— 速率决策发生在 openFile 里面。
 *
 * ★ 设备没声明速率（比如 MOONDROP Dawn Pro：FORMAT_TYPE_I 是 6 字节截断的，
 *   bSamFreqType 字段压根不存在）时传空数组，引擎保持 hasInfo=false，
 *   走原来那套标准速率白名单 —— **行为与以前逐字节一致**。
 *
 * ★ 设备声明了就听它的。这样换任何设备都自动适配：
 *      · 只支持到 96k 的设备 → 自动重采样到 96000，而不是挑不到 alt 直接报错
 *      · 支持非标准速率的设备 → 直接直通，不再被白名单白白重采样一层
 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetDeviceRates(JNIEnv* env, jobject, jlong h,
                                                     jintArray discrete,
                                                     jint contMin, jint contMax) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;

    hifi::DeviceRateCaps caps;
    if (discrete != nullptr) {
        const jsize n = env->GetArrayLength(discrete);
        if (n > 0) {
            caps.discrete.resize(static_cast<size_t>(n));
            env->GetIntArrayRegion(discrete, 0, n, caps.discrete.data());
            caps.hasInfo = true;
        }
    }
    if (contMin > 0 && contMax > contMin) {
        caps.contMin = contMin;
        caps.contMax = contMax;
        caps.hasInfo = true;
    }
    c->engine.setDeviceRates(caps);

    if (!caps.hasInfo) {
        LOGI("设备未声明速率表 —— 回落到标准速率白名单（本次会话不变）");
        return;
    }
    std::ostringstream os;
    os << "设备声明了速率：";
    if (!caps.discrete.empty()) {
        os << caps.discrete.size() << " 个离散值";
        if (caps.discrete.size() <= 8) {
            os << " [";
            for (size_t i = 0; i < caps.discrete.size(); ++i) {
                if (i) os << " ";
                os << caps.discrete[i];
            }
            os << "]";
        }
    }
    if (caps.contMax > 0) {
        os << (caps.discrete.empty() ? "" : "  +  ")
           << "连续 " << caps.contMin << "~" << caps.contMax << "Hz";
    }
    os << " —— 速率决策改以设备为准（下次打开文件生效）";
    LOGI("%s", os.str().c_str());
}

/*
 * 暂停 / 恢复。
 *
 * 除了停解码线程，还要通知播放器 —— 只停解码的话，环形缓冲里那一秒
 * 还会照常放完，按了暂停要等一秒才真的静音。
 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetPaused(JNIEnv* env, jobject, jlong h,
                                                jboolean paused) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    c->engine.setPaused(paused == JNI_TRUE);
    c->player.setPaused(paused == JNI_TRUE);
}

/*
 * 交叉馈送：开关 + 档位（0=弱 1=中 2=强）。
 *
 * ★ 引擎内部用原子变量，解码线程每块读一次 —— **改设置立刻生效**，
 *   不用重建引擎、也不用重新开文件。
 *
 * ★★ 它是**引擎级**的状态，所以 handle 一换（切输出方式、拔插后重建）
 *    就得重新设一遍 —— Kotlin 那边统一走 PlayerSession.applyCrossfeed()。
 *
 * ★ 对 DSD 和单声道无效，用 nativeCrossfeedState 的 usable 位问。
 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetCrossfeed(JNIEnv*, jobject, jlong h,
                                                   jboolean on, jint level) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    c->engine.setCrossfeed(on == JNI_TRUE, level);
}

/*
 * 软件音量：开关 + 增益（分贝，<= 0）。
 *
 * ★★ **这是软件音量，不是设备的 UAC 硬件音量** —— 它在样本上做乘法，
 *    **必然不是 bit-perfect**。所以有开关，默认关。
 * ★ 原子变量，解码线程每块读一次 → 拖动滑条**实时生效**，不用重建引擎。
 * ★ 增益 0dB 时原生侧直接跳过，一个样本都不碰。
 * ★ 对 DSD 无效（DSD 走原生位流，不经过 PCM 域）。
 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetSoftwareVolume(JNIEnv*, jobject, jlong h,
                                                        jboolean on, jfloat gainDb) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    c->engine.setVolumeControl(on == JNI_TRUE);
    // dB → 线性。0dB 就是 1.0，applyGain 会因此整段跳过
    c->engine.setGain(std::pow(10.0f, gainDb / 20.0f));
}

/*
 * 返回 {开着吗, 当前 dB × 100, 实际输出的位深}。
 *
 * ★ 位深是界面要显示的：开了音量控制、源又是 16bit 的话，
 *   输出会被提到 24bit —— 用户有权知道送出去的是什么。
 */
JNIEXPORT jlongArray JNICALL
Java_com_hifiprobe_NativePlayer_nativeSoftwareVolumeState(JNIEnv* env, jobject, jlong h) {
    jlong vals[3] = {0, 0, 0};
    NativeCtx* c = asCtx(h);
    if (c != nullptr) {
        vals[0] = c->engine.volumeControl() ? 1 : 0;
        vals[1] = static_cast<jlong>(c->engine.gainDb() * 100.0f);
        vals[2] = c->engine.outputBitResolution();
    }
    jlongArray a = env->NewLongArray(3);
    if (a != nullptr) env->SetLongArrayRegion(a, 0, 3, vals);
    return a;
}

/*
 * 返回 {当前开着吗, 这个文件能不能用, 档位}。
 *
 * ★ 界面拿它决定状态条要不要显示「交叉馈送」—— 开了但当前是 DSD
 *   （或单声道）时，**显示上要能区分**，否则用户会以为是坏了。
 */
JNIEXPORT jintArray JNICALL
Java_com_hifiprobe_NativePlayer_nativeCrossfeedState(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    jint vals[3] = {0, 0, 1};
    if (c != nullptr) {
        vals[0] = c->engine.crossfeedEnabled() ? 1 : 0;
        vals[1] = c->engine.crossfeedUsable() ? 1 : 0;
        vals[2] = c->engine.crossfeedLevel();
    }
    jintArray a = env->NewIntArray(3);
    if (a != nullptr) env->SetIntArrayRegion(a, 0, 3, vals);
    return a;
}

// ===========================================================================
//  系统音频输出（AAudio）—— **非 bit-perfect** 的那条路
//
//  和 USB 那条是并列的两个输出端，同一次播放只起一个。
//  它们消费的是**同一个** engine.source()（环形缓冲），所以解码线程、
//  重采样器、欠载记账全部照用，这里只是把字节交给 AAudio 而不是 USB URB。
// ===========================================================================

/*
 * 探测系统实际会给的采样率。
 *
 * ★ 必须在 nativeOpenFile **之前**调：引擎的速率决策发生在 open() 里面，
 *   它得先知道目标速率。拿到后由 Kotlin 通过 nativeSetOutputMode 传下来。
 *
 * 失败返回 0，调用方回落到 48000。
 */
JNIEXPORT jint JNICALL
Java_com_hifiprobe_NativePlayer_nativeProbeSystemAudioRate(JNIEnv*, jobject) {
    return static_cast<jint>(hifi::SystemAudioSink::probeSampleRate());
}

/*
 * 设定输出模式。**必须在 nativeOpenFile 之前调。**
 *
 * @param systemRate 非 0 = 走系统音频，值是系统采样率；0 = 走 USB 直连
 */
JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetOutputMode(JNIEnv*, jobject, jlong h,
                                                    jint systemRate) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    c->systemRate = systemRate > 0 ? static_cast<int>(systemRate) : 0;
    c->engine.setSystemAudio(c->systemRate);
}

/*
 * 起播：系统音频。
 *
 * 不需要任何 USB 参数 —— 速率/声道/每样本字节数引擎自己知道，
 * 线格式直接映射到 AAudio 的 PCM_I16 / I24_PACKED / I32（布局完全一致）。
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeStartSystemPlayback(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    if (!c->engine.ready()) {
        return toJString(env, "尚未打开音频文件，请先选择文件");
    }

    /*
     * ★★ DSD 走不了这条路。
     *
     *   DSD 是**裸位流**，AAudio 只吃 PCM —— 要塞给它就必须先转 PCM，
     *   而那是**有损**的（FIR 低通 + 抽取，和 ffmpeg 内部那个 dsd2pcm 同理）。
     *   用户明确要求 DSD 不准走系统音频，所以这里直接拒绝，
     *   由界面提示"DSD 需要小尾巴直连"。
     */
    if (c->engine.isDsd()) {
        return toJString(env,
                "DSD 不能走系统音频（要先转成有损 PCM）—— 请插上小尾巴走直连");
    }

    if (c->sink.isRunning()) c->sink.stop();
    c->player.stop(); c->sink.stop();
    c->engine.stopDecoding();

    if (c->systemRate <= 0) {
        return toJString(env, "还没问过系统采样率（nativeProbeSystemAudioRate 没调或失败）");
    }

    /*
     * 解码线程要先跑起来 —— AAudio 第一次回调就等着取数据，
     * 顺序反了会先欠载一大片。
     */
    if (!c->engine.startDecoding()) {
        return toJString(env, "解码线程起不来");
    }

    std::string err;
    if (!c->sink.start(c->engine.source(), c->systemRate, c->engine.channels(),
                      c->engine.subframeSize(), &err)) {
        c->engine.stopDecoding();
        return toJString(env, err);
    }

    std::ostringstream os;
    os << "系统音频已起播（非 bit-perfect）：" << c->systemRate << "Hz "
       << c->engine.channels() << "ch\n";
    return toJString(env, os.str());
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeSeekTo(JNIEnv* env, jobject, jlong h, jint ms) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");
    const bool ok = c->engine.seekToMs(ms);

    /*
     * 清队在 seek **之后**做，顺序不能反。
     *
     * seekToMs 返回时解码线程已经清空并重建了环形缓冲；此时再去取消在途 URB，
     * 回调就会用新数据重新填充。若反过来先清队，回调会在缓冲还是旧内容的
     * 时候把它又填回去，等于白清。
     */
    if (ok && c->flushQueueOnSeek) {
        c->player.flushQueue();
    }
    std::ostringstream os;
    os << "seek 到 " << (ms / 1000) << "." << ((ms % 1000) / 100) << " 秒 -> "
       << (ok ? "成功" : "失败") << "\n";
    return toJString(env, os.str());
}

/*
 * 停止播放并释放接口。引擎本身保留（文件仍打开），便于再次播放或 seek。
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeStopPlayback(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    std::ostringstream os;

    /*
     * 统计一定要打出来，不能只在「还在播」时才打。
     *
     * 播到自然结束的情况下，播放器已经自己收手了（数据源放完即停），
     * 到这里 isRunning() 已经是 false —— 若照旧只在运行时才输出，
     * 恰恰是最需要数据的那一次（完整放完一首）什么都看不到。
     */
    os << "── 播放统计 ──\n";
    os << c->player.statsReport();
    c->player.stop(); c->sink.stop();

    /*
     * 转储落盘。
     *
     * ★ 必须在 player.stop() **之后** —— stop() 会 join libusb 事件线程，
     *   那之后 dumpBuf_ 才不再有人写。播放中写就是数据竞争。
     */
    if (c->dumpEnabled && !c->dumpPath.empty()) {
        /*
         * 文件名带上**输出速率 + 时刻**。
         *
         * 最初用固定文件名，每次停止都覆盖上一次；改成只加速率标签后
         * 又踩了一次：连播两个 44.1k 文件时它们**撞名**，后一个把前一个
         * 盖掉了 —— 实测就这样丢掉了一份 12MB 的转储，报告里明明写着
         * 「已写出 12162120 字节」，盘上却只剩 476640 字节。
         *
         * 加上时刻后才真正唯一，一个文件一份，不会互相覆盖。
         * 报告里会印出完整路径，照着取值即可。
         */
        std::string path = c->dumpPath;
        const int outRate = c->engine.outputRate();
        if (outRate > 0) {
            char stamp[32] = {0};
            const std::time_t now = std::time(nullptr);
            std::tm tmv{};
            localtime_r(&now, &tmv);
            std::strftime(stamp, sizeof(stamp), "%H%M%S", &tmv);

            const size_t dot = path.find_last_of('.');
            const std::string tag =
                "_" + std::to_string(outRate) + "Hz_" + stamp;
            if (dot == std::string::npos) {
                path += tag;
            } else {
                path.insert(dot, tag);
            }
        }

        bool truncated = false;
        std::string derr;
        const size_t n = c->player.writeDump(path, &truncated, &derr);
        os << "\n── 输出字节转储 ──\n";
        if (n == 0) {
            os << "未写出：" << derr << "\n";
        } else {
            os << "已写出 " << n << " 字节 -> " << path << "\n";
            if (truncated) {
                os << "⚠ 已达 32MB 上限，本次只转储了前 " << n
                   << " 字节（后面的没记）\n";
            }
            os << "这是**送进 USB 的有效字节**，不含欠载补的静音；\n";
            os << "拿它和 PC 上 `ffmpeg -f s24le`（按实际位深/字节序）的输出逐字节比对即可。\n";
        }
    }

    // 引擎计数：定位「音频少了一截」丢在哪一段
    const hifi::AudioEngine& e = c->engine;
    if (e.ready()) {
        os << "\n── 引擎计数 ──\n";
        if (e.seekCount() > 0) {
            os << "（本次播放 seek 过 " << e.seekCount()
               << " 次，以下计数为**最后一次 seek 之后**的分段值）\n";
        }
        os << "解码器交出: " << e.decodedFrames() << " 源帧";
        if (e.sourceRate() > 0) {
            os << "（" << (e.decodedFrames() / static_cast<uint64_t>(e.sourceRate()))
               << "." << ((e.decodedFrames() % static_cast<uint64_t>(e.sourceRate())) * 10 /
                          static_cast<uint64_t>(e.sourceRate())) << " 秒）";
        }
        os << "\n";
        os << "写入缓冲:   " << e.writtenFrames() << " 输出帧";
        if (e.outputRate() > 0) {
            os << "（" << (e.writtenFrames() / static_cast<uint64_t>(e.outputRate()))
               << "." << ((e.writtenFrames() % static_cast<uint64_t>(e.outputRate())) * 10 /
                          static_cast<uint64_t>(e.outputRate())) << " 秒）";
        }
        os << "\n";

        /*
         * 出环 + 收尾残留 —— 「音频少了一截」时用来切开责任段。
         *
         *   ★ writtenFrames 是**进环**，consumedFrames 是**出环**（都不含补的静音）。
         *     两个数加上残留一摆，三种情形的指向完全不同：
         *       · 进环满、出环少              → 丢在环形缓冲/取数这一侧
         *       · 出环满、残留 0、USB 统计少   → 丢在 USB 提交那一侧
         *       · 残留 ≠ 0 而 finished() 却为真 → finished() 的判据本身有问题
         *
         *   实测背景（p53，DSD256 20 秒那首）：冷启动后的**第一次**播放
         *   比文件少 5~8 个 URB，之后每次分毫不差；进环那行是满的，
         *   所以问题在"环 → USB"，加这两行就是为了定死是哪一侧。
         */
        const int fb = e.frameBytes() > 0 ? e.frameBytes() : 1;
        os << "取出缓冲:   " << (e.consumedBytes() / static_cast<uint64_t>(fb))
           << " 输出帧（" << e.consumedBytes() << " 字节）\n";
        os << "收尾残留:   " << e.ringAvailableBytes() << " 字节\n";
        if (e.resampling() && e.sourceRate() > 0 && e.outputRate() > 0) {
            const double expect = static_cast<double>(e.decodedFrames()) *
                                  static_cast<double>(e.outputRate()) /
                                  static_cast<double>(e.sourceRate());
            os << "期望输出:   " << static_cast<uint64_t>(expect) << " 帧"
               << "（解码帧数 × " << e.outputRate() << "/" << e.sourceRate() << "）\n";
            const int64_t diff = static_cast<int64_t>(e.writtenFrames()) -
                                 static_cast<int64_t>(expect);
            os << "差额:       " << diff << " 帧";
            if (e.outputRate() > 0) {
                os << "（" << (diff * 1000.0 / e.outputRate()) << " ms）";
            }
            os << "\n";
            os << "            ← 几十帧以内的正值正常，那是 flush 回收的滤波器延迟尾巴；\n";
            os << "              要警惕的是负值，或绝对值到千帧量级（那才是真丢数据）\n";
        } else if (e.sourceRate() > 0 && e.outputRate() > 0) {
            /*
             * 直通 —— 这一行是「能不能做 bit-perfect」的第一判据。
             * 只要走的是直通，配上「输出字节转储」就能和 PC 上的参考 PCM
             * 逐字节比对（tools/bitperfect_check.sh），不必靠耳朵。
             */
            os << "线格式:     **直通，未重采样** —— 送进 DAC 的就是文件里的样本\n";
            if (e.sourceRate() == e.outputRate()) {
                os << "            " << e.sourceRate() << "Hz → " << e.outputRate()
                   << "Hz，一个采样点都没碰\n";
            }
        }
    }

    // 音频线程提权结果 —— 失败是静默的（刻意不中断播放），只能靠报告暴露
    os << "\n── 音频线程优先级 ──\n" << hifi::priorityReport();

    // 欠载事件清单：用户能听出"卡了几次"，报告就必须能对上"第几秒、多长"
    os << "\n" << c->engine.underrunEventsReport();

    c->engine.stopDecoding();

    /*
     * 降回空闲态再释放，避免下次运行时残留上一次的 alt 设置。
     *
     * ★ devh 可能为空（系统音频模式）—— 那时 claimedInterfaces 也一定是空的，
     *   循环本来就不会进；但直接把 devh 交给 libusb 是**欠一次崩溃**，
     *   加这一道判断，别让"恰好没进循环"成为唯一的保护。
     */
    if (c->devh != nullptr) {
        for (int iface : c->claimedInterfaces) {
            libusb_set_interface_alt_setting(c->devh, iface, 0);
            libusb_release_interface(c->devh, iface);
        }
    }
    c->claimedInterfaces.clear();
    return toJString(env, os.str());
}

JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeEngineStatus(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");
    return toJString(env, c->engine.statusReport());
}

JNIEXPORT jboolean JNICALL
Java_com_hifiprobe_NativePlayer_nativeEngineReady(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return JNI_FALSE;
    return c->engine.ready() ? JNI_TRUE : JNI_FALSE;
}

/*
 * 引擎状态快照，供 UI 每秒轮询。
 *
 * 返回 long[] 而不是拼好的字符串：这是每秒都要走一次的路径，
 * 让 Kotlin 去解析文本既浪费又容易随文案改动而失效。
 *
 * 布局（改动时同步 NativePlayer.kt 里的 IDX_* 常量）：
 *   [0] sourceRate     源采样率 Hz
 *   [1] outputRate     输出采样率 Hz
 *   [2] channels       声道数
 *   [3] sourceBits     源位深
 *   [4] subframeSize   实际下发的子帧字节数
 *   [5] bitResolution  实际下发的有效位
 *   [6] resampling     1=重采样，0=直通
 *   [7] durationMs     总时长，未知为 0
 *   [8] positionMs     当前播放位置
 *   [9] bufferedBytes  环形缓冲里积压的字节数
 *   [10] underrunBytes 累计欠载字节数
 *   [11] finished      1=解码已到文件末尾
 *   [12] paused        1=已暂停
 *   [13] targetBytes   目标缓冲水位，配合 [9] 才能算出真实水位百分比
 */
JNIEXPORT jlongArray JNICALL
Java_com_hifiprobe_NativePlayer_nativeEngineInfo(JNIEnv* env, jobject, jlong h) {
    constexpr int kCount = 16;   // ★ 加字段时这里和 NativePlayer.EngineInfo.COUNT 要同步
    jlong v[kCount] = {0};

    NativeCtx* c = asCtx(h);
    if (c != nullptr) {
        hifi::AudioEngine& e = c->engine;
        v[0] = e.sourceRate();
        v[1] = e.outputRate();
        v[2] = e.channels();
        v[3] = e.sourceBits();
        v[4] = e.subframeSize();
        v[5] = e.bitResolution();
        v[6] = e.resampling() ? 1 : 0;
        v[7] = e.durationMs();
        v[8] = e.positionMs();
        v[9] = static_cast<jlong>(e.bufferedBytes());
        v[10] = static_cast<jlong>(e.underrunBytes());
        v[11] = e.decodeFinished() ? 1 : 0;
        v[12] = e.paused() ? 1 : 0;
        v[13] = static_cast<jlong>(e.targetBufferBytes());
        v[14] = e.isDsd() ? 1 : 0;
        // 系统音频（非 bit-perfect）—— 界面据此**不能**再显示"直通 · bit-perfect"
        v[15] = e.systemAudio() ? 1 : 0;
    }

    jlongArray arr = env->NewLongArray(kCount);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, kCount, v);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeCloseFile(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;
    if (c->player.isRunning()) c->player.stop(); c->sink.stop();
    c->engine.close();
}

// ===========================================================================
//  UAC 硬件音量（Feature Unit）
// ===========================================================================

/*
 * 确保 AudioControl 接口已被 claim。
 *
 * Feature Unit 的音量请求打在 AudioControl 接口上，而停止播放时
 * nativeStopPlayback 会把所有接口一并释放。音量调节不该依赖「是否正在播放」，
 * 所以这里按需补 claim，并记进 claimedInterfaces 交给统一释放。
 */
static bool ensureAcClaimed(NativeCtx* c, int acIface) {
    if (acIface < 0) return false;
    for (int iface : c->claimedInterfaces) {
        if (iface == acIface) return true;
    }
    const int r = libusb_claim_interface(c->devh, acIface);
    if (r != 0) {
        LOGW("按需 claim AudioControl(%d) 失败: %s", acIface, libusb_error_name(r));
        return false;
    }
    c->claimedInterfaces.push_back(acIface);
    return true;
}

/*
 * Feature Unit 音量控制。请求格式（UAC1 与 UAC2 一致）：
 *
 *   bmRequestType = 0x21 / 0xA1        (class, interface)
 *   bRequest      = 0x01 SET_CUR / 0x81 GET_CUR
 *                   0x82 MIN / 0x83 MAX / 0x84 RES
 *   wValue        = (CS_VOLUME_CONTROL << 8) | 通道号
 *                   CS_VOLUME_CONTROL = 0x02，通道号 0 表示 master
 *   wIndex        = 实体 ID 与接口号（构造方式见下）
 *   数据          = 2 字节有符号，单位 1/256 dB
 *
 * wIndex 是唯一的未知数 —— 与 Clock Source 那处是同一类坑：规范说 Entity ID
 * 在高位，但不同固件实现不一致，失败时只得到一个语焉不详的 LIBUSB_ERROR_IO。
 * 所以沿用探针里验证过的做法：不猜，几种组合都试一遍，哪个成功用哪个。
 *
 * 顺序按可能性排：第一项与 Clock Source 在这台设备上实测成功的写法一致。
 */
struct VolumeIndexVariant {
    const char* label;
    uint16_t wIndex;
};

static std::vector<VolumeIndexVariant> volumeIndexVariants(int acIface, int unitId) {
    std::vector<VolumeIndexVariant> v;
    // 去重：AC 接口号为 0 时，后两种构造算出来是同一个 wIndex。
    // 不去重的话探测会做两遍无用功，报告里也会出现两段一模一样的输出，
    // 让人误以为设备给了两次相同结果。
    auto add = [&v](const char* label, int idx) {
        const uint16_t w = static_cast<uint16_t>(idx & 0xFFFF);
        for (const auto& e : v) {
            if (e.wIndex == w) return;
        }
        v.push_back({label, w});
    };
    add("wIndex=(UnitID<<8)|AC接口", (unitId << 8) | (acIface & 0xFF));
    add("wIndex=(AC接口<<8)|UnitID", (acIface << 8) | (unitId & 0xFF));
    // 部分 UAC1 固件只认低字节的实体 ID
    add("wIndex=UnitID（不带接口号）", unitId & 0xFF);
    return v;
}

/*
 * 发一次 Feature Unit 音量请求。
 *
 * request: 0x81 GET_CUR / 0x82 GET_MIN / 0x83 GET_MAX / 0x84 GET_RES
 * cn:      通道号，0 = master，1/2 = 逐声道
 *
 * 这几个请求只有 bRequest 不同，wValue 都是 (CS_VOLUME_CONTROL << 8) | CN，
 * 所以合并成一个函数 —— 原来拆成两个，改一处漏一处的风险没必要承担。
 */
static bool volumeRequest(NativeCtx* c, uint16_t wIndex, int cn,
                          int request, int* out) {
    unsigned char buf[2] = {0, 0};
    const int r = libusb_control_transfer(
        c->devh, 0xA1, static_cast<uint8_t>(request),
        static_cast<uint16_t>((0x02 << 8) | (cn & 0xFF)),
        wIndex, buf, 2, 1000);
    if (r != 2) return false;
    const int raw = buf[0] | (buf[1] << 8);
    *out = (raw & 0x8000) ? (raw - 0x10000) : raw;   // 2 字节有符号
    return true;
}

/*
 * 用给定 wIndex 读一组**自洽**的音量参数。
 *
 * 为什么要自洽性检查，而不是「GET_CUR 有响应就算数」：
 * 这台设备对不存在的实体也照答不误（P0 阶段用非法实体 0x7F 发 GET_CUR
 * 都能成功），所以「有响应」根本不能证明 wIndex 指对了地方。
 *
 * 实测就撞上过：探测"成功"拿到一组 min=1 / max=0 的垃圾值，
 * 于是音量键一路把非法区间喂给 coerceIn，直接抛异常闪退。
 *
 * 合格条件：
 *   · max > min          —— 区间非空，这是 coerceIn 的硬性前提
 *   · cur ∈ [min, max]   —— 当前值必须落在自己声明的范围里
 * 步进只在 > 0 时采信，否则由上层按 1/256 dB 兜底。
 */
static bool readValidVolumeRange(NativeCtx* c, uint16_t wIndex, int cn,
                                 int* lo, int* hi, int* res, int* cur) {
    if (!volumeRequest(c, wIndex, cn, 0x82, lo)) return false;   // GET_MIN
    if (!volumeRequest(c, wIndex, cn, 0x83, hi)) return false;   // GET_MAX
    if (!volumeRequest(c, wIndex, cn, 0x84, res)) return false;  // GET_RES
    if (!volumeRequest(c, wIndex, cn, 0x81, cur)) return false;  // GET_CUR

    if (*hi <= *lo) return false;
    if (*cur < *lo || *cur > *hi) return false;
    return true;
}

/*
 * 探测可用的 wIndex + 通道号组合，缓存起来。
 *
 * 之所以连通道号一起试（0=master / 1 / 2=逐声道）：这台设备描述符里 master
 * 明确声明了音量能力（bmaControls[0]=0x0F），但固件实测不响应；有些设备
 * 恰恰相反，只在逐声道上真正实现。多试两组成本很低，漏掉才是损失。
 *
 * 返回 false 表示所有组合都给不出一组自洽的范围 —— 此时判定为不可控，
 * 而不是拿垃圾值硬上（早期版本就是这么崩的）。
 */
static bool resolveVolumeParams(NativeCtx* c, int acIface, int unitId) {
    if (c->volumeWIndex >= 0 && c->volumeChannel >= 0) return true;
    if (!ensureAcClaimed(c, acIface)) return false;

    const int channelCandidates[] = {0, 1, 2};
    for (const auto& v : volumeIndexVariants(acIface, unitId)) {
        for (int cn : channelCandidates) {
            int lo = 0, hi = 0, res = 0, cur = 0;
            if (readValidVolumeRange(c, v.wIndex, cn, &lo, &hi, &res, &cur)) {
                LOGI("音量控制探测成功: %s (0x%04X) CN=%d  min=%d max=%d res=%d cur=%d",
                     v.label, v.wIndex, cn, lo, hi, res, cur);
                c->volumeWIndex = v.wIndex;
                c->volumeChannel = cn;
                return true;
            }
        }
    }
    LOGW("音量控制：所有 wIndex × 通道组合都读不出自洽范围，判定为不可控");
    c->volumeWIndex = -1;
    c->volumeChannel = -1;
    return false;
}

/*
 * 查询音量范围与当前值。
 *
 * 返回 long[6]：
 *   [0] min  最小音量（1/256 dB，通常是很负的值或静音）
 *   [1] max  最大音量（1/256 dB，通常为 0）
 *   [2] res  步进（1/256 dB，常见 0x0040 = 0.25dB；≤0 表示设备未报，由上层兜底）
 *   [3] cur  当前音量
 *   [4] ok      1 = 成功，0 = 该设备不支持（其余项无意义）
 *   [5] wIndex  实际生效的 wIndex，仅用于诊断输出
 *   [6] channel 实际生效的通道号（0=master，1/2=逐声道），仅用于诊断输出
 *
 * 失败时**整个数组都是 0**，调用方必须先看 ok 再用其余项 ——
 * 拿 ok=0 的零值去构造区间会得到空的 [0,0]，再进入区间运算就是崩溃。
 */
JNIEXPORT jlongArray JNICALL
Java_com_hifiprobe_NativePlayer_nativeVolumeRange(JNIEnv* env, jobject, jlong h,
                                                  jint acIface, jint unitId) {
    constexpr int kCount = 7;
    jlong out[kCount] = {0, 0, 0, 0, 0, 0, 0};
    NativeCtx* c = asCtx(h);
    if (c != nullptr && resolveVolumeParams(c, acIface, unitId)) {
        int lo = 0, hi = 0, res = 0, cur = 0;
        if (readValidVolumeRange(c, static_cast<uint16_t>(c->volumeWIndex),
                                 c->volumeChannel, &lo, &hi, &res, &cur)) {
            out[0] = lo;
            out[1] = hi;
            out[2] = res;
            out[3] = cur;
            out[4] = 1;
            out[5] = c->volumeWIndex;
            out[6] = c->volumeChannel;
        } else {
            // 缓存组合这次读不出自洽范围（设备重插、固件状态变了），
            // 清掉缓存让下次重新探测，本次如实报告失败
            LOGW("缓存的音量参数 wIndex=0x%04X CN=%d 失效，清除缓存",
                 c->volumeWIndex, c->volumeChannel);
            c->volumeWIndex = -1;
            c->volumeChannel = -1;
        }
    }
    jlongArray arr = env->NewLongArray(kCount);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, kCount, out);
    return arr;
}

/* 0x0000 形式的四位十六进制，避免动 ostringstream 的格式化状态 */
static std::string hex4(int v) {
    static const char* d = "0123456789ABCDEF";
    std::string s = "0x";
    s += d[(v >> 12) & 0xF];
    s += d[(v >> 8) & 0xF];
    s += d[(v >> 4) & 0xF];
    s += d[v & 0xF];
    return s;
}

/*
 * 音量探测诊断：把三个 wIndex 候选各自读到的原始字节全打出来。
 *
 * 只为回答一个问题：**这台设备到底能不能从手机侧调音量。**
 *
 * 已知它会对不存在的实体也照答不误（P0 阶段用非法实体 0x7F 发 GET_CUR 都能
 * 成功），所以「有响应」完全不能证明找对了地方 —— 早期版本就因此拿到一组
 * min=1/max=0 的垃圾值，一路喂到 coerceIn 把 App 崩掉。
 *
 * 光看「通过/没通过自洽性检查」不够：得看到每个候选实际返回的字节，才能判断
 * 是固件压根没实现 Feature Unit 音量（音量做在模拟域），还是我们没试对 wIndex。
 */
JNIEXPORT jstring JNICALL
Java_com_hifiprobe_NativePlayer_nativeVolumeProbe(JNIEnv* env, jobject, jlong h,
                                                  jint acIface, jint unitId) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return toJString(env, "句柄为空");

    std::ostringstream os;
    os << "── 硬件音量探测（Feature Unit ID=" << unitId
       << "，AudioControl 接口=" << acIface << "）──\n";

    if (!ensureAcClaimed(c, acIface)) {
        os << "  AudioControl 接口 claim 失败，无法探测\n";
        return toJString(env, os.str());
    }

    struct Req { const char* name; int code; };
    const Req reqs[] = {
        {"GET_CUR", 0x81},
        {"GET_MIN", 0x82},
        {"GET_MAX", 0x83},
        {"GET_RES", 0x84},
    };

    // 通道号候选：0 = master，1/2 = 逐声道。
    // 描述符里 master 声明了音量能力，但固件实测不响应；有些设备恰恰相反，
    // 只在逐声道上真正实现 —— 两边都试过才敢下结论。
    const int channels[] = {0, 1, 2};

    for (const auto& v : volumeIndexVariants(acIface, unitId)) {
        for (int cn : channels) {
            os << "  " << v.label << "  wIndex=" << hex4(v.wIndex)
               << "  CN=" << cn
               << (cn == 0 ? "（master）" : "（逐声道）") << "\n";
            for (const auto& rq : reqs) {
                unsigned char buf[2] = {0, 0};
                const int r = libusb_control_transfer(
                    c->devh, 0xA1, static_cast<uint8_t>(rq.code),
                    static_cast<uint16_t>((0x02 << 8) | cn),
                    v.wIndex, buf, 2, 1000);
                os << "      " << rq.name << " -> ";
                if (r != 2) {
                    os << "失败 " << libusb_error_name(r) << " (" << r << ")\n";
                    continue;
                }
                const int raw = buf[0] | (buf[1] << 8);
                const int sv = (raw & 0x8000) ? (raw - 0x10000) : raw;
                os << hex4(raw) << " = " << sv << "（" << (sv / 256.0) << " dB）\n";
            }
        }
    }

    os << "\n判读方法（自洽 = max > min 且 GET_CUR 落在 [min, max] 内）：\n";
    os << "  · 所有 wIndex × 通道组合都不自洽\n";
    os << "    → 固件没实现 Feature Unit 音量，音量做在模拟域，只能用它自己的按键\n";
    os << "  · 某一组自洽 → 那就是它对，把 wIndex 和 CN 一起发回来\n";
    return toJString(env, os.str());
}

/*
 * 设置音量并回读。
 *
 * value 单位 1/256 dB，调用方应先用 nativeVolumeRange 拿到范围。
 * 返回 long[2]：[0] = 回读到的当前值，[1] = 1 成功 / 0 失败。
 */
JNIEXPORT jlongArray JNICALL
Java_com_hifiprobe_NativePlayer_nativeSetVolume(JNIEnv* env, jobject, jlong h,
                                                jint acIface, jint unitId,
                                                jint value) {
    jlong out[2] = {0, 0};
    NativeCtx* c = asCtx(h);
    if (c != nullptr && resolveVolumeParams(c, acIface, unitId)) {
        unsigned char data[2] = {
            static_cast<unsigned char>(value & 0xFF),
            static_cast<unsigned char>((value >> 8) & 0xFF),
        };
        const int r = libusb_control_transfer(
            c->devh, 0x21, 0x01,
            static_cast<uint16_t>((0x02 << 8) | (c->volumeChannel & 0xFF)),
            static_cast<uint16_t>(c->volumeWIndex), data, 2, 1000);
        if (r == 2) {
            out[1] = 1;
            int cur = 0;
            if (volumeRequest(c, static_cast<uint16_t>(c->volumeWIndex),
                              c->volumeChannel, 0x81, &cur)) {
                out[0] = cur;
            } else {
                out[0] = value;
            }
        } else {
            setLastError(std::string("SET_CUR 音量失败: ") + libusb_error_name(r));
        }
    }
    jlongArray arr = env->NewLongArray(2);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, 2, out);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_hifiprobe_NativePlayer_nativeClose(JNIEnv* env, jobject, jlong h) {
    NativeCtx* c = asCtx(h);
    if (c == nullptr) return;

    c->player.stop(); c->sink.stop();
    c->sink.stop();

    if (c->devh != nullptr) {
        for (int iface : c->claimedInterfaces) {
            libusb_release_interface(c->devh, iface);
        }
    }
    c->claimedInterfaces.clear();

    /*
     * ★★ 交还设备之前，**复位它一次** —— 这是软件层面最接近"拔插一次"的动作。
     *
     *   实测：用过 USB 直连之后切回系统音频，**所有软件层都是好的** ——
     *
     *     · 内核 snd-usb-audio 已重新绑定（/sys/bus/usb/drivers/snd-usb-audio/ 下
     *       AudioControl 与 AudioStreaming 两个接口都在）
     *     · ALSA 卡还在，而且**卡号没变**（不是"Android 攥着失效引用"）
     *     · 路由 = USB_HEADSET，音量 150/150 未静音
     *     · HAL 正在写（Blocked in write、Last write 38ms 前）
     *     · 我们的 track Active、FrmRdy 满、Underruns 0
     *
     *   可小尾巴就是不出声，**只有物理拔插一次能恢复**。
     *   → 残留状态在那颗 DAC 内部，不在 Linux/Android 这一侧。
     *
     *   USB 端口复位会重置设备自己的 USB 状态机、并让内核重新探测接口，
     *   这是应用能做的唯一一件事。
     *
     *   ★ 只放在 nativeClose（"彻底交还"那一刻），**不放在每次 stop 上** ——
     *     每首歌之间复位一次既慢又没必要。
     *
     *   ★ 诚实的不确定性：**复位不切 VBUS、不断电**。如果这颗 DAC 需要真正
     *     断电才复位，这一下也不管用，那就只剩"提示用户拔插一次"。
     *     所以结果和耗时都打出来，下次一看日志就知道有没有用。
     */
    if (c->devh != nullptr) {
        const auto t0 = std::chrono::steady_clock::now();
        const int rr = libusb_reset_device(c->devh);
        const auto cost = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t0).count();
        if (rr == 0) LOGI("设备已复位（交还前清状态，耗时 %lld ms）", (long long) cost);
        else LOGW("设备复位失败: %s（不影响后面的 close）", libusb_error_name(rr));
    }

    if (c->devh != nullptr) libusb_close(c->devh);
    if (c->ctx != nullptr) libusb_exit(c->ctx);

    delete c;
    LOGI("nativeClose 完成");
}

}  // extern "C"
