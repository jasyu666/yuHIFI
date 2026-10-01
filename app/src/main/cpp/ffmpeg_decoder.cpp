#include "ffmpeg_decoder.h"

#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "HiFiDec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hifi {

namespace {

constexpr int kIoBufferSize = 64 * 1024;

std::string avErrStr(int e) {
    char b[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(e, b, sizeof(b));
    return std::string(b);
}

/*
 * 直接对文件描述符做 read/lseek。
 *
 * 用 fd 而不是路径，是为了让 SAF 的 content:// URI 也能播 —— FFmpeg 不认识
 * 那类 URI，但 ContentResolver.openFileDescriptor() 能给出 fd。
 * 本地文件的 fd 是可 seek 的，所以拖进度条也能正常工作。
 */
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
        if (end < 0) return AVERROR(errno);
        return static_cast<int64_t>(end);
    }

    const off_t r = ::lseek(fd, static_cast<off_t>(offset), whence);
    if (r < 0) return AVERROR(errno);
    return static_cast<int64_t>(r);
}

}  // namespace

FFmpegDecoder::FFmpegDecoder() {
    pkt_ = av_packet_alloc();
    frame_ = av_frame_alloc();
}

FFmpegDecoder::~FFmpegDecoder() {
    close();
}

bool FFmpegDecoder::open(int fd, std::string* err) {
    close();
    fd_ = fd;

    if (!pkt_) pkt_ = av_packet_alloc();
    if (!frame_) frame_ = av_frame_alloc();
    if (!pkt_ || !frame_) {
        if (err) *err = "FFmpeg 结构分配失败";
        return false;
    }

    auto fail = [&](const std::string& m) {
        if (err) *err = m;
        LOGE("open 失败: %s", m.c_str());
        return false;
    };

    // ---- 自定义 IO ----
    auto* iobuf = static_cast<unsigned char*>(av_malloc(kIoBufferSize));
    if (!iobuf) return fail("av_malloc 失败");

    io_ = avio_alloc_context(iobuf, kIoBufferSize, 0, &fd_,
                             ioRead, nullptr, ioSeek);
    if (!io_) {
        av_free(iobuf);
        return fail("avio_alloc_context 失败");
    }

    fmt_ = avformat_alloc_context();
    if (!fmt_) return fail("avformat_alloc_context 失败");
    fmt_->pb = io_;
    // 必须置此标志：告诉 FFmpeg 这个 AVIOContext 由我们管理，
    // 它不要在 close 时释放（否则双重释放）
    fmt_->flags |= AVFMT_FLAG_CUSTOM_IO;

    int r = avformat_open_input(&fmt_, nullptr, nullptr, nullptr);
    if (r < 0) return fail("avformat_open_input: " + avErrStr(r));

    r = avformat_find_stream_info(fmt_, nullptr);
    if (r < 0) return fail("avformat_find_stream_info: " + avErrStr(r));

    const AVCodec* codec = nullptr;
    streamIndex_ = av_find_best_stream(fmt_, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (streamIndex_ < 0 || codec == nullptr)
        return fail("找不到音频流（这个文件可能只有视频或封面图）");

    AVStream* st = fmt_->streams[streamIndex_];
    dec_ = avcodec_alloc_context3(codec);
    if (!dec_) return fail("avcodec_alloc_context3 失败");

    r = avcodec_parameters_to_context(dec_, st->codecpar);
    if (r < 0) return fail("avcodec_parameters_to_context: " + avErrStr(r));

    dec_->pkt_timebase = st->time_base;

    r = avcodec_open2(dec_, codec, nullptr);
    if (r < 0) return fail(std::string("avcodec_open2(") + codec->name + "): " + avErrStr(r));

    sampleRate_ = dec_->sample_rate;
    channels_ = dec_->ch_layout.nb_channels;
    if (sampleRate_ <= 0 || channels_ <= 0)
        return fail("解码器给出的采样率/声道数非法");

    // 有效位深：优先用 bits_per_raw_sample（FLAC/ALAC 会正确填这个字段），
    // 拿不到时退回样本格式的容器宽度
    bitsPerSample_ = dec_->bits_per_raw_sample;
    if (bitsPerSample_ <= 0) {
        bitsPerSample_ = av_get_bytes_per_sample(dec_->sample_fmt) * 8;
    }

    // ---- 重采样器：只做格式归一化到 S32 交错，不改采样率 ----
    AVChannelLayout outLayout{};
    av_channel_layout_default(&outLayout, channels_);
    r = swr_alloc_set_opts2(&swr_,
                            &outLayout, AV_SAMPLE_FMT_S32, sampleRate_,
                            &dec_->ch_layout, dec_->sample_fmt, sampleRate_,
                            0, nullptr);
    av_channel_layout_uninit(&outLayout);
    if (r < 0 || !swr_) return fail("swr_alloc_set_opts2 失败");

    // 明确关掉抖动：这里是增加位深（→S32），本来就无损，
    // 开抖动反而会引入本不该有的噪声
    av_opt_set_int(swr_, "dither_method", SWR_DITHER_NONE, 0);

    r = swr_init(swr_);
    if (r < 0) return fail("swr_init: " + avErrStr(r));

    // ---- 时长 ----
    if (fmt_->duration != AV_NOPTS_VALUE && fmt_->duration > 0) {
        durationMs_ = fmt_->duration / (AV_TIME_BASE / 1000);
    } else if (st->duration != AV_NOPTS_VALUE && st->duration > 0) {
        durationMs_ = static_cast<int64_t>(st->duration * av_q2d(st->time_base) * 1000.0);
    } else {
        durationMs_ = 0;
    }

    codecName_ = codec->name;
    formatName_ = fmt_->iformat ? fmt_->iformat->name : "?";

    LOGI("打开成功: %s / %s  %dHz %dch %dbit  时长 %lldms",
         formatName_, codecName_, sampleRate_, channels_, bitsPerSample_,
         static_cast<long long>(durationMs_));
    return true;
}

void FFmpegDecoder::close() {
    if (swr_) swr_free(&swr_);
    if (frame_) av_frame_free(&frame_);
    if (pkt_) av_packet_free(&pkt_);
    if (dec_) avcodec_free_context(&dec_);
    if (fmt_) avformat_close_input(&fmt_);

    // 带 AVFMT_FLAG_CUSTOM_IO 时 FFmpeg 不会释放 AVIOContext，得自己收
    if (io_) {
        av_freep(&io_->buffer);
        avio_context_free(&io_);
    }

    streamIndex_ = -1;
    sampleRate_ = channels_ = bitsPerSample_ = 0;
    durationMs_ = 0;
    demuxEof_ = drainSent_ = decoderDone_ = false;
    pending_.clear();
    pendingFrames_ = pendingOffset_ = 0;
    codecName_ = formatName_ = "?";
    fd_ = -1;
}

bool FFmpegDecoder::decodeNextAudioFrame() {
    pendingFrames_ = 0;
    pendingOffset_ = 0;
    if (decoderDone_) return false;

    for (;;) {
        const int r = avcodec_receive_frame(dec_, frame_);

        if (r == 0) {
            const int maxOut = frame_->nb_samples;
            pending_.resize(static_cast<size_t>(maxOut) * static_cast<size_t>(channels_));

            uint8_t* out[1] = {reinterpret_cast<uint8_t*>(pending_.data()) };
            const int got = swr_convert(
                    swr_, out, maxOut,
                    const_cast<const uint8_t**>(frame_->extended_data),
                    frame_->nb_samples);

            av_frame_unref(frame_);

            if (got > 0) {
                pendingFrames_ = got;
                pendingOffset_ = 0;
                return true;
            }
            continue;   // 这次没产出（例如封面图那种无音频的帧），继续
        }

        if (r == AVERROR_EOF) {
            decoderDone_ = true;
            return false;
        }
        if (r != AVERROR(EAGAIN)) {
            LOGE("avcodec_receive_frame 出错: %s", avErrStr(r).c_str());
            decoderDone_ = true;
            return false;
        }

        // EAGAIN：解码器需要更多输入
        if (drainSent_) {
            // 已经送过冲刷包却仍要输入，正常不该发生，防御性退出
            decoderDone_ = true;
            return false;
        }

        const int rr = av_read_frame(fmt_, pkt_);
        if (rr < 0) {
            // 读完了，送 NULL 包让解码器把内部缓冲吐干净
            avcodec_send_packet(dec_, nullptr);
            drainSent_ = true;
            demuxEof_ = true;
            continue;
        }

        if (pkt_->stream_index == streamIndex_) {
            const int sr = avcodec_send_packet(dec_, pkt_);
            if (sr < 0 && sr != AVERROR(EAGAIN)) {
                LOGE("avcodec_send_packet 出错: %s", avErrStr(sr).c_str());
            }
        }
        av_packet_unref(pkt_);
    }
}

int FFmpegDecoder::readFrames(int32_t* dst, int maxFrames) {
    if (!dec_ || maxFrames <= 0) return 0;

    int written = 0;
    while (written < maxFrames) {
        if (pendingOffset_ < pendingFrames_) {
            const int take = std::min(pendingFrames_ - pendingOffset_,
                                      maxFrames - written);
            std::memcpy(dst + static_cast<size_t>(written) * channels_,
                        pending_.data() + static_cast<size_t>(pendingOffset_) * channels_,
                        static_cast<size_t>(take) * channels_ * sizeof(int32_t));
            pendingOffset_ += take;
            written += take;
            continue;
        }
        if (!decodeNextAudioFrame()) break;
    }
    return written;
}

bool FFmpegDecoder::seekToMs(int64_t ms) {
    if (!fmt_ || !dec_ || streamIndex_ < 0) return false;

    AVStream* st = fmt_->streams[streamIndex_];
    const int64_t ts = av_rescale_q(ms, AVRational{1, 1000}, st->time_base);

    // BACKWARD：定位到目标之前的关键帧，保证解码器能立刻开始出数据
    const int r = av_seek_frame(fmt_, streamIndex_, ts, AVSEEK_FLAG_BACKWARD);
    if (r < 0) {
        LOGE("av_seek_frame 失败: %s", avErrStr(r).c_str());
        return false;
    }

    avcodec_flush_buffers(dec_);
    if (swr_) swr_convert(swr_, nullptr, 0, nullptr, 0);   // 冲掉重采样器里的残留

    demuxEof_ = drainSent_ = decoderDone_ = false;
    pendingFrames_ = pendingOffset_ = 0;
    return true;
}

}  // namespace hifi
