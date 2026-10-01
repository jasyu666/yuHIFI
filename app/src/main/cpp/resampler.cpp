#include "resampler.h"

#include <android/log.h>

#include <algorithm>

extern "C" {
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "HiFiResample"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hifi {

namespace {
std::string avErrStr(int e) {
    char b[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(e, b, sizeof(b));
    return std::string(b);
}
}  // namespace

Resampler::~Resampler() {
    close();
}

bool Resampler::init(int inRate, int outRate, int channels, std::string* err) {
    close();

    if (inRate <= 0 || outRate <= 0 || channels <= 0) {
        if (err) *err = "重采样参数非法";
        return false;
    }
    if (inRate == outRate) {
        if (err) *err = "输入输出速率相同，不该走重采样路径";
        return false;
    }

    inRate_ = inRate;
    outRate_ = outRate;
    channels_ = channels;

    AVChannelLayout layout{};
    av_channel_layout_default(&layout, channels);

    int r = swr_alloc_set_opts2(&swr_,
                                &layout, AV_SAMPLE_FMT_S32, outRate,
                                &layout, AV_SAMPLE_FMT_S32, inRate,
                                0, nullptr);
    av_channel_layout_uninit(&layout);

    if (r < 0 || swr_ == nullptr) {
        if (err) *err = "swr_alloc_set_opts2 失败: " + avErrStr(r);
        swr_ = nullptr;
        return false;
    }

    // 质量参数。swresample 默认 filter_size=16 偏低，对 44.1k→48k 这种
    // 非整数比转换，提高抽头数能明显改善阻带抑制。
    av_opt_set_int(swr_, "filter_size", 32, 0);
    av_opt_set_double(swr_, "cutoff", 0.97, 0);

    // 输入输出都是 S32，位深不变，抖动只会引入额外噪声
    av_opt_set_int(swr_, "dither_method", SWR_DITHER_NONE, 0);

    r = swr_init(swr_);
    if (r < 0) {
        if (err) *err = "swr_init 失败: " + avErrStr(r);
        swr_free(&swr_);
        return false;
    }

    LOGI("重采样器就绪: %dHz -> %dHz, %dch, 比率 %.9f",
         inRate_, outRate_, channels_, ratio());
    return true;
}

void Resampler::close() {
    if (swr_) swr_free(&swr_);
    inRate_ = outRate_ = channels_ = 0;
}

int Resampler::process(const int32_t* in, int inFrames, int32_t* out, int maxOutFrames) {
    if (swr_ == nullptr || in == nullptr || inFrames <= 0 || maxOutFrames <= 0) return 0;

    uint8_t* outPtr[1] = {reinterpret_cast<uint8_t*>(out)};
    const uint8_t* inPtr[1] = {reinterpret_cast<const uint8_t*>(in)};

    const int got = swr_convert(swr_, outPtr, maxOutFrames, inPtr, inFrames);
    if (got < 0) {
        LOGE("swr_convert 失败: %s", avErrStr(got).c_str());
        return 0;
    }
    return got;
}

int Resampler::flush(int32_t* out, int maxOutFrames) {
    if (swr_ == nullptr || maxOutFrames <= 0) return 0;

    uint8_t* outPtr[1] = {reinterpret_cast<uint8_t*>(out)};
    const int got = swr_convert(swr_, outPtr, maxOutFrames, nullptr, 0);
    return got < 0 ? 0 : got;
}

}  // namespace hifi
