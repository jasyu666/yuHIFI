#pragma once

#include <cstdint>

namespace hifi {

/*
 * 内部交错 S32 → USB 线格式。
 *
 * 内部统一用「源样本左对齐到 32 位」表示：
 *     16bit 源 → 值 = sample16 << 16
 *     24bit 源 → 值 = sample24 << 8
 *     32bit 源 → 值 = sample32
 *
 * 输出时右移 (32 - bitResolution) 位即可还原成该位深下的整数：
 *     bitResolution=16 → 右移 16 → sample16      （精确还原，无损失）
 *     bitResolution=24 → 右移 8  → sample24      （精确还原）
 *     bitResolution=32 → 不移位  → sample32      （精确还原）
 *
 * 因此只要输出位深 ≥ 源位深，整个过程就是无损的；相等时即 bit-perfect。
 *
 * 小端逐字节写出。当 bitResolution < subframeSize*8 时（例如 4 字节容器装
 * 24bit），负样本的高位自然补 0xFF 形成符号扩展，符合 ALSA/Linux 的
 * S24_LE 约定，也是 Android/Linux USB Audio HAL 的通行做法。
 */
inline void convertS32ToWire(const int32_t* src, int frames, int channels,
                             int subframeSize, int bitResolution,
                             uint8_t* dst) {
    const int shift = 32 - bitResolution;
    const int total = frames * channels;
    uint8_t* p = dst;

    for (int i = 0; i < total; ++i) {
        const int32_t v = (shift > 0) ? (src[i] >> shift) : src[i];
        for (int b = 0; b < subframeSize; ++b) {
            p[b] = static_cast<uint8_t>((v >> (8 * b)) & 0xFF);
        }
        p += subframeSize;
    }
}

}  // namespace hifi
