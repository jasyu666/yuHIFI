#include "loudness_analyzer.h"

#include <android/log.h>
#include <unistd.h>

#include <cerrno>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "HiFiLoudness"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace hifi {

namespace {

constexpr int kIoBufferSize = 64 * 1024;

std::string avErrStr(int e) {
    char b[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(e, b, sizeof(b));
    return std::string(b);
}

int ioRead(void* opaque, uint8_t* buf, int bufSize) {
    const int fd = *static_cast<int*>(opaque);
    const ssize_t n = ::read(fd, buf, static_cast<size_t>(bufSize));
    if (n < 0) return AVERROR(errno);
    if (n == 0) return AVERROR_EOF;
    return static_cast<int>(n);
}

int64_t ioSeek(void* opaque, int64_t offset, int whence) {
    const int fd = *static_cast<int*>(opaque);
    if (whence == AVSEEK_SIZE) {
        const off_t cur = ::lseek(fd, 0, SEEK_CUR);
        if (cur < 0) return AVERROR(errno);
        const off_t end = ::lseek(fd, 0, SEEK_END);
        ::lseek(fd, cur, SEEK_SET);
        return end < 0 ? AVERROR(errno) : static_cast<int64_t>(end);
    }
    const off_t r = ::lseek(fd, static_cast<off_t>(offset), whence);
    return r < 0 ? AVERROR(errno) : static_cast<int64_t>(r);
}

}  // namespace

LoudnessAnalysisJob* loudnessJobCreate() {
    return new LoudnessAnalysisJob();
}

void loudnessJobDestroy(LoudnessAnalysisJob* job) {
    delete job;
}

void loudnessJobCancel(LoudnessAnalysisJob* job) {
    if (job != nullptr) job->cancel.store(true, std::memory_order_relaxed);
}

TrackLoudness loudnessAnalyzeTrack(LoudnessAnalysisJob* job, int fd) {
    TrackLoudness out;
    if (job == nullptr || fd < 0) return out;

    /*
     * dup 一份自己用。
     *
     * Kotlin 侧传进来的是**单独为分析打开**的 ParcelFileDescriptor，
     * 不是播放引擎正在用的那个 fd；即便如此也 dup 一份，保证文件偏移
     * 不会和调用方互相干扰。
     */
    int myFd = ::dup(fd);
    if (myFd < 0) {
        LOGE("dup 失败: %s", std::strerror(errno));
        return out;
    }

    AVFormatContext* fmt = nullptr;
    AVIOContext* io = nullptr;
    AVCodecContext* dec = nullptr;
    SwrContext* swr = nullptr;
    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();

    auto cleanup = [&]() {
        if (swr != nullptr) swr_free(&swr);
        if (dec != nullptr) avcodec_free_context(&dec);
        if (fmt != nullptr) avformat_close_input(&fmt);
        if (io != nullptr) {
            av_freep(&io->buffer);
            avio_context_free(&io);
        }
        if (pkt != nullptr) av_packet_free(&pkt);
        if (frame != nullptr) av_frame_free(&frame);
        if (myFd >= 0) {
            ::close(myFd);
            myFd = -1;
        }
    };

    if (pkt == nullptr || frame == nullptr) {
        LOGE("分配 packet/frame 失败");
        cleanup();
        return out;
    }

    unsigned char* iobuf =
            static_cast<unsigned char*>(av_malloc(kIoBufferSize));
    if (iobuf == nullptr) {
        LOGE("IO 缓冲分配失败");
        cleanup();
        return out;
    }

    io = avio_alloc_context(iobuf, kIoBufferSize, 0, &myFd, ioRead, nullptr,
                            ioSeek);
    if (io == nullptr) {
        av_free(iobuf);
        LOGE("avio_alloc_context 失败");
        cleanup();
        return out;
    }

    fmt = avformat_alloc_context();
    if (fmt == nullptr) {
        LOGE("avformat_alloc_context 失败");
        cleanup();
        return out;
    }
    fmt->pb = io;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

    int r = avformat_open_input(&fmt, nullptr, nullptr, nullptr);
    if (r < 0) {
        LOGE("打开失败: %s", avErrStr(r).c_str());
        cleanup();
        return out;
    }
    r = avformat_find_stream_info(fmt, nullptr);
    if (r < 0) {
        LOGE("读流信息失败: %s", avErrStr(r).c_str());
        cleanup();
        return out;
    }

    const int idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1,
                                        nullptr, 0);
    if (idx < 0) {
        LOGE("没有音频流");
        cleanup();
        return out;
    }

    const AVCodec* codec =
            avcodec_find_decoder(fmt->streams[idx]->codecpar->codec_id);
    if (codec == nullptr) {
        LOGE("没有解码器");
        cleanup();
        return out;
    }

    dec = avcodec_alloc_context3(codec);
    if (dec == nullptr) {
        LOGE("avcodec_alloc_context3 失败");
        cleanup();
        return out;
    }
    r = avcodec_parameters_to_context(dec, fmt->streams[idx]->codecpar);
    if (r < 0) {
        LOGE("参数复制失败: %s", avErrStr(r).c_str());
        cleanup();
        return out;
    }
    /*
     * 限制解码线程数：分析是后台任务，不能和播放的 USB/解码线程抢满所有核。
     * 2 线程足够跑到实时数百倍，既快又不至于把大核占死。
     */
    dec->thread_count = 2;
    r = avcodec_open2(dec, codec, nullptr);
    if (r < 0) {
        LOGE("开解码器失败: %s", avErrStr(r).c_str());
        cleanup();
        return out;
    }

    const int fs = dec->sample_rate;
    const int ch = dec->ch_layout.nb_channels;
    if (fs <= 0 || ch <= 0) {
        LOGE("非法音频参数: %dHz %dch", fs, ch);
        cleanup();
        return out;
    }

    // 输入/输出都沿用解码器声明的声道布局 —— 用 default 布局在 5.1 等
    // 多声道文件上会和真实声道顺序对不上。
    AVChannelLayout inLayout;
    AVChannelLayout outLayout;
    av_channel_layout_copy(&inLayout, &dec->ch_layout);
    av_channel_layout_copy(&outLayout, &dec->ch_layout);
    r = swr_alloc_set_opts2(&swr, &outLayout, AV_SAMPLE_FMT_FLT, fs,
                            &inLayout, dec->sample_fmt, fs, 0, nullptr);
    av_channel_layout_uninit(&inLayout);
    av_channel_layout_uninit(&outLayout);
    if (swr == nullptr || r < 0) {
        LOGE("swr 创建失败: %s", avErrStr(r).c_str());
        cleanup();
        return out;
    }
    r = swr_init(swr);
    if (r < 0) {
        LOGE("swr 初始化失败: %s", avErrStr(r).c_str());
        cleanup();
        return out;
    }

    LoudnessMeter trackMeter;
    trackMeter.init(fs, ch, true);

    if (!job->albumStarted) {
        job->albumMeter.init(fs, ch, false);
        job->albumStarted = true;
        job->albumRate = fs;
        job->albumChannels = ch;
    } else if (fs != job->albumRate || ch != job->albumChannels) {
        // 同一张专辑里采样率/声道数变了：保留已积累的块，只换滤波器
        job->albumMeter.setFormat(fs, ch);
        job->albumRate = fs;
        job->albumChannels = ch;
    }

    std::vector<float> buf;
    bool eofSent = false;
    bool readFailed = false;

    while (!job->cancel.load(std::memory_order_relaxed)) {
        r = avcodec_receive_frame(dec, frame);
        if (r == AVERROR(EAGAIN)) {
            const int pr = av_read_frame(fmt, pkt);
            if (pr < 0) {
                // 只有真正的 EOF 才算正常结束；读错误不能当成"文件播完了"
                if (pr != AVERROR_EOF) readFailed = true;
                if (!eofSent) {
                    avcodec_send_packet(dec, nullptr);
                    eofSent = true;
                    continue;
                }
                // 已经送过 EOF 还是没帧可出 —— 防止解码器异常时空转
                break;
            }
            if (pkt->stream_index == idx) {
                avcodec_send_packet(dec, pkt);
            }
            av_packet_unref(pkt);
            continue;
        }
        if (r == AVERROR_EOF) break;
        if (r < 0) {
            readFailed = true;
            break;
        }

        const int maxOut = swr_get_out_samples(swr, frame->nb_samples) + 64;
        if (maxOut <= 0) {
            av_frame_unref(frame);
            continue;
        }
        buf.resize(static_cast<size_t>(maxOut) * static_cast<size_t>(ch));

        uint8_t* outPtr = reinterpret_cast<uint8_t*>(buf.data());
        // C 风格转换：swr_convert 要的是 const 指针数组，这里只加 const
        const uint8_t** inPtr = (const uint8_t**)frame->extended_data;
        const int got = swr_convert(swr, &outPtr, maxOut, inPtr,
                                    frame->nb_samples);
        if (got > 0) {
            trackMeter.feedInterleavedFloat(buf.data(), got);
            job->albumMeter.feedInterleavedFloat(buf.data(), got);
        }
        av_frame_unref(frame);
    }

    if (job->cancel.load(std::memory_order_relaxed)) {
        job->albumFailed = true;
        cleanup();
        return out;
    }

    trackMeter.finish();
    const LoudnessResult lr = trackMeter.result();

    out.lufs = lr.lufs;
    out.truePeakDb = lr.truePeakDb;
    out.sampleRate = fs;
    out.channels = ch;
    out.valid = lr.valid && !readFailed;

    if (!out.valid) {
        /*
         * 整首一句完整的 400ms 块都没有 —— 极短文件或解码异常。
         * 专辑任务里有任何一个曲目失败，专辑值就不作数。
         */
        job->albumFailed = true;
    } else if (lr.truePeakDb > job->albumPeak) {
        job->albumPeak = lr.truePeakDb;
    }

    cleanup();
    return out;
}

LoudnessResult loudnessFinishAlbum(LoudnessAnalysisJob* job) {
    LoudnessResult out;
    if (job == nullptr || !job->albumStarted || job->albumFailed) return out;
    if (job->cancel.load(std::memory_order_relaxed)) return out;

    job->albumMeter.finish();
    out = job->albumMeter.result();
    if (out.valid) {
        out.truePeakDb = (job->albumPeak > 0.0)
                ? 20.0 * std::log10(job->albumPeak)
                : -HUGE_VAL;
    }
    return out;
}

}  // namespace hifi
