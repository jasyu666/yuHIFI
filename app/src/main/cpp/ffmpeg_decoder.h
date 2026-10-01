#pragma once

#include <vector>

#include "decoder.h"

struct AVFormatContext;
struct AVCodecContext;
struct SwrContext;
struct AVPacket;
struct AVFrame;
struct AVIOContext;

namespace hifi {

/*
 * 基于 FFmpeg 的解码器。
 *
 * 用自定义 AVIOContext 从**文件描述符**读取，而不是让 FFmpeg 按路径打开文件。
 * 原因：Android 的存储访问框架（SAF）给的是 content:// URI，FFmpeg 不认识，
 * 而 ContentResolver.openFileDescriptor() 能拿到 fd。基于 fd 读写既能走 SAF，
 * 也天然支持 scoped storage，无需任何存储权限。
 */
class FFmpegDecoder : public AudioDecoder {
public:
    FFmpegDecoder();
    ~FFmpegDecoder() override;

    FFmpegDecoder(const FFmpegDecoder&) = delete;
    FFmpegDecoder& operator=(const FFmpegDecoder&) = delete;

    bool open(int fd, std::string* err) override;
    void close() override;

    int sampleRate() const override { return sampleRate_; }
    int channels() const override { return channels_; }
    int bitsPerSample() const override { return bitsPerSample_; }
    int64_t durationMs() const override { return durationMs_; }

    int readFrames(int32_t* dst, int maxFrames) override;
    bool seekToMs(int64_t ms) override;

    const char* codecName() const { return codecName_; }
    const char* formatName() const { return formatName_; }

private:
    /* 解出下一帧音频并转成 S32 存入 pending_。返回 false 表示结束或出错。 */
    bool decodeNextAudioFrame();
    /* 当前帧中尚未被取走的帧数 */
    int pendingRemaining() const { return pendingFrames_ - pendingOffset_; }

    AVFormatContext* fmt_ = nullptr;
    AVCodecContext* dec_ = nullptr;
    SwrContext* swr_ = nullptr;
    AVPacket* pkt_ = nullptr;
    AVFrame* frame_ = nullptr;
    AVIOContext* io_ = nullptr;

    int streamIndex_ = -1;
    int sampleRate_ = 0;
    int channels_ = 0;
    int bitsPerSample_ = 0;
    int64_t durationMs_ = 0;

    bool demuxEof_ = false;     // 已读到文件末尾
    bool drainSent_ = false;    // 已送入冲刷包
    bool decoderDone_ = false;  // 解码器已完全输出

    std::vector<int32_t> pending_;   // 当前帧，交错 S32
    int pendingFrames_ = 0;
    int pendingOffset_ = 0;

    const char* codecName_ = "?";
    const char* formatName_ = "?";

    int fd_ = -1;
};

}  // namespace hifi
