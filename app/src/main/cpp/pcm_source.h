#pragma once

#include <cstdint>

namespace hifi {

/*
 * IsoPlayer 只认这个接口，它决定「音频数据从哪来」。
 *
 * 之所以要这层抽象：P0 已经把 URB 调度、包长分配、feedback 读写验证正确了
 * （数千个 URB 零失败零丢包），那部分代码不该因为「换成播真实文件」而被改动。
 * 换数据来源只需要换一个实现类。
 */
class PcmSource {
public:
    virtual ~PcmSource() = default;

    /*
     * 填充 dst 指向的 bytes 字节。
     *
     * 实现必须**永不阻塞**：这是从 USB 提交线程调用的，一旦阻塞就会让
     * isochronous 传输断流（iso 端点不重传，断一次就是一声爆音）。
     * 数据不足时必须补静音并立刻返回，由上层去解决供给问题。
     *
     * 返回值：实际写入的**有效**字节数（不含补进去的静音）。
     */
    virtual int fill(uint8_t* dst, int bytes) = 0;

    /* 累计因数据不足而补的静音字节数，用于诊断缓冲是否够深 */
    virtual uint64_t underrunBytes() const { return 0; }

    /*
     * 暂停期间补的静音字节数，单独统计。
     *
     * 暂停只停解码线程、USB 流照跑，缓冲抽干后必然开始补静音 —— 那是**故意的**。
     * 混进 underrunBytes 的话，暂停一分钟就会显示成"欠载一分钟"，
     * 把唯一的供给健康指标彻底污染。实测已经踩过：暂停 54 秒 → 欠载 54604ms。
     */
    virtual uint64_t pauseSilenceBytes() const { return 0; }

    /* 音源是否已经播完（用于自动停止） */
    virtual bool finished() const { return false; }
};

}  // namespace hifi
