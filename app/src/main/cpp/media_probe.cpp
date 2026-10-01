#include "media_probe.h"

#include <android/log.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
}

#define LOG_TAG "HiFiProbe"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace hifi {

namespace {

constexpr int kIoBufferSize = 32 * 1024;

std::string avErrStr(int e) {
    char b[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(e, b, sizeof(b));
    return std::string(b);
}

/*
 * 探测用的 AVIO 回调。
 *
 * 和 ffmpeg_decoder.cpp 里那套是同一个思路（对 fd 直接 read/lseek，
 * 这样 SAF 的 content:// 也能用），但不共用 —— 那边是私有的匿名命名空间，
 * 而且两处的缓冲大小诉求不同（播放要 64KB 抗抖，探测只读个头，32KB 足够）。
 * 重复的代价远小于把播放器的内部实现暴露出来。
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
        return end < 0 ? AVERROR(errno) : static_cast<int64_t>(end);
    }
    const off_t r = ::lseek(fd, static_cast<off_t>(offset), whence);
    return r < 0 ? AVERROR(errno) : static_cast<int64_t>(r);
}

/* 从字典里取一个键，取不到返回空串。FFmpeg 的键大小写不敏感。 */
std::string dictGet(AVDictionary* d, const char* key) {
    if (d == nullptr) return {};
    const AVDictionaryEntry* e = av_dict_get(d, key, nullptr, AV_DICT_IGNORE_SUFFIX);
    return (e != nullptr && e->value != nullptr) ? std::string(e->value) : std::string();
}

/*
 * 内嵌歌词。容器级和流级都看，取第一个非空的。
 *
 * ★ 要按顺序试几个键名：不同容器把歌词塞在不同的标签里 ——
 *   · m4a 的 `©lyr`   → FFmpeg 暴露成 `lyrics`
 *   · ID3v2 的 USLT   → 也是 `lyrics`
 *   · Vorbis comment  → `LYRICS` / `UNSYNCEDLYRICS`
 *
 * ★ `AV_DICT_IGNORE_SUFFIX`（[dictGet] 用的标志）让匹配**大小写不敏感、
 *   且允许后缀** —— `lyrics-eng` 这种带语言后缀的也能命中。
 */
std::string lyricsOf(AVDictionary* st, AVDictionary* fmt) {
    static const char* kKeys[] = {
        "lyrics", "unsyncedlyrics", "syncedlyrics",
    };
    for (const char* k : kKeys) {
        std::string v = dictGet(st, k);
        if (v.empty()) v = dictGet(fmt, k);
        if (!v.empty()) return v;
    }
    return {};
}

/*
 * 位深。
 *
 * 优先 bits_per_raw_sample（FLAC / ALAC 这类无损格式的**原始位深**），
 * 它才反映文件里真正存了多少位。没有的话退回数据布局的宽度 ——
 * 注意那只是容器宽度，24bit 装在 4 字节容器里时会误报成 32，
 * 所以它只能当兜底。
 */
int deriveBits(const AVCodecParameters* par) {
    /*
     * bits_per_raw_sample 是唯一可信的来源 —— FLAC / ALAC 这类无损格式会
     * 如实填它，也正是"文件里真正存了多少位"。
     *
     * 注意 FFmpeg 7.x 已经**移除**了 AVCodecParameters::bits_per_sample
     * （旧的容器位宽字段），所以兜底只能从样本格式的数据布局推：
     * 那是容器宽度而不是有效位深，24bit 装在 4 字节容器里时会误报成 32。
     * 所以兜底值仅用于显示，**不要拿它决定线格式** —— 那个由播放引擎
     * 按真实解码结果决定。
     */
    if (par->bits_per_raw_sample > 0) return par->bits_per_raw_sample;
    const int bytes = av_get_bytes_per_sample(static_cast<AVSampleFormat>(par->format));
    return bytes > 0 ? bytes * 8 : 0;
}

}  // namespace

bool probeFd(int fd, MediaInfo* out, std::string* err) {
    if (out == nullptr) return false;
    *out = MediaInfo{};
    if (fd < 0) {
        if (err) *err = "fd 无效";
        return false;
    }

    /*
     * dup 一份自己用。
     *
     * 探测过程会把 fd 的读写位置挪来挪去，直接用调用方的 fd 会**破坏它的状态** ——
     * 调用方后面还要拿它去播。dup 之后各有各的偏移量（共享文件描述，但不共享
     * 偏移），互不干扰。
     */
    // 非 const：avio_alloc_context 要的是 void*，而 &const 转不过去
    int myFd = ::dup(fd);
    if (myFd < 0) {
        if (err) *err = std::string("dup 失败: ") + std::strerror(errno);
        return false;
    }

    auto cleanup = [&](AVIOContext* io, AVFormatContext* fmt) {
        if (fmt != nullptr) avformat_close_input(&fmt);
        if (io != nullptr) {
            av_freep(&io->buffer);
            avio_context_free(&io);
        }
        ::close(myFd);
    };

    AVFormatContext* fmt = nullptr;
    AVIOContext* io = nullptr;

    unsigned char* iobuf = static_cast<unsigned char*>(av_malloc(kIoBufferSize));
    if (iobuf == nullptr) {
        ::close(myFd);
        if (err) *err = "IO 缓冲分配失败";
        return false;
    }

    io = avio_alloc_context(iobuf, kIoBufferSize, 0, &myFd, ioRead, nullptr, ioSeek);
    if (io == nullptr) {
        av_free(iobuf);
        ::close(myFd);
        if (err) *err = "avio_alloc_context 失败";
        return false;
    }

    fmt = avformat_alloc_context();
    if (fmt == nullptr) {
        cleanup(io, nullptr);
        if (err) *err = "avformat_alloc_context 失败";
        return false;
    }
    fmt->pb = io;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

    int r = avformat_open_input(&fmt, nullptr, nullptr, nullptr);
    if (r < 0) {
        cleanup(io, fmt);
        if (err) *err = "无法识别该文件: " + avErrStr(r);
        return false;
    }

    r = avformat_find_stream_info(fmt, nullptr);
    if (r < 0) {
        cleanup(io, fmt);
        if (err) *err = "读取流信息失败: " + avErrStr(r);
        return false;
    }

    const int idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (idx < 0) {
        cleanup(io, fmt);
        if (err) *err = "没有音频流";
        return false;
    }

    const AVStream* st = fmt->streams[idx];
    const AVCodecParameters* par = st->codecpar;

    out->sampleRate = par->sample_rate;
    out->channels = par->ch_layout.nb_channels;
    out->bitsPerSample = deriveBits(par);
    out->codec = avcodec_get_name(par->codec_id);

    // 时长：优先流自己的（准），退回容器级
    if (st->duration > 0) {
        out->durationMs = static_cast<int64_t>(st->duration * av_q2d(st->time_base) * 1000.0);
    } else if (fmt->duration > 0) {
        out->durationMs = fmt->duration / (AV_TIME_BASE / 1000);
    }

    /*
     * 标签。容器级和流级都要看 —— FLAC/MP3 通常写在流上，M4A 常在容器级，
     * 而同一个文件里也可能两边都有且不一致，**以流级为准**（更贴近这一路音频）。
     */
    out->title = dictGet(st->metadata, "title");
    if (out->title.empty()) out->title = dictGet(fmt->metadata, "title");
    out->artist = dictGet(st->metadata, "artist");
    if (out->artist.empty()) out->artist = dictGet(fmt->metadata, "artist");
    out->album = dictGet(st->metadata, "album");
    if (out->album.empty()) out->album = dictGet(fmt->metadata, "album");

    /*
     * 歌词。
     *
     * ★ **常规扫描不关心它** —— [Library] 只取前 8 个字段，多读一个标签
     *   不影响扫描成本（字典本来就在内存里）。
     *   真正要紧的是"**别把它塞进 JNI 数组**"：一首歌词几 KB，
     *   1674 首全量回传就是好几 MB 的无谓拷贝，而且绝大多数用不上。
     *   所以正在播放页是**按需**单独探一首的 —— 见 nativeProbeLyrics。
     */
    out->lyrics = lyricsOf(st->metadata, fmt->metadata);

    /*
     * ★★ 诊断：「探测成功、但什么字段都没拿到」时报警。
     *
     *   实测就是靠这一条定位 DSD 的：probeFd 返回 **true**，
     *   但 title / artist / album / codec / 时长全是空 ——
     *   界面于是显示成"未知艺术家 + 无规格"，看着像"这文件本来就没标签"，
     *   其实是**我们这条探测路径没读到**。没有这条日志，那就只能靠猜。
     *
     *   判据取"**title 空 且 codec 空**"：codec 来自 avcodec_get_name()，
     *   只要 FFmpeg 认出了音频流就不会是空的。两者同时为空 = 真的没探到东西。
     *   正常文件不会触发，所以可以一直开着，不会刷屏。
     */
    if (out->title.empty() && out->codec.empty()) {
        const AVDictionaryEntry* e = nullptr;
        int nStream = 0, nFmt = 0;
        while ((e = av_dict_get(st->metadata, "", e, AV_DICT_IGNORE_SUFFIX)) != nullptr) nStream++;
        e = nullptr;
        while ((e = av_dict_get(fmt->metadata, "", e, AV_DICT_IGNORE_SUFFIX)) != nullptr) nFmt++;
        LOGW("探测到空结果：容器=%s  流标签=%d  容器标签=%d  codec_id=%d  采样率=%d  时长=%lldms"
             " —— 文件多半没问题，是探测路径没读到",
             fmt->iformat != nullptr ? fmt->iformat->name : "?",
             nStream, nFmt, static_cast<int>(par->codec_id), par->sample_rate,
             static_cast<long long>(out->durationMs));
    }

    /*
     * 内嵌封面。
     *
     * FFmpeg 把封面当作一路**视频流**（AV_DISPOSITION_ATTACHED_PIC），
     * 图像数据直接挂在流的 attached_pic 包里，不必解码。
     * 只取第一张：多图文件（正面/背面/CD）会让缓存膨胀好几倍。
     */
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        const AVStream* s = fmt->streams[i];
        if ((s->disposition & AV_DISPOSITION_ATTACHED_PIC) == 0) continue;
        const AVPacket& pic = s->attached_pic;
        if (pic.data == nullptr || pic.size <= 0) continue;
        out->cover.assign(pic.data, pic.data + pic.size);
        break;
    }

    cleanup(io, fmt);
    return true;
}

}  // namespace hifi
