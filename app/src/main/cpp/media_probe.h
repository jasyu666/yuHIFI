#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace hifi {

/*
 * 一首曲目的元信息 —— 文件库列表和"正在播放"页显示的东西。
 *
 * 单独做一个**轻量探测**，而不是复用 FFmpegDecoder：建解码器要开解码线程、
 * 分配环形缓冲，只为读个标签太浪费。而文件库里通常有几百首，扫描时逐个建
 * 解码器的代价是实打实的。
 */
struct MediaInfo {
    std::string title;
    std::string artist;
    std::string album;
    std::string codec;         // "flac" / "alac" / ...

    int sampleRate = 0;
    int channels = 0;
    int bitsPerSample = 0;
    int64_t durationMs = 0;

    /*
     * 内嵌封面（原始 JPEG/PNG 字节，不解码）。
     *
     * 只取**第一张** attached picture。有的文件挂多张（正面/背面/CD 图），
     * 全带上会让 JSON 缓存膨胀好几倍，界面也只显示一张。
     */
    std::vector<uint8_t> cover;

    bool hasCover() const { return !cover.empty(); }

    /*
     * 内嵌歌词原文。空 = 没有。
     *
     * ★ 拿到的是**原始文本**，可能是 LRC（带 `[mm:ss.xx]` 时间戳）也可能是
     *   纯文本 —— 同一个字段名装两种东西，**只能靠嗅探内容分辨**，别按名字猜。
     *   实测 m4a 那个 `©lyr`（规范上定义为"无时间戳纯文本"）里塞的就是 LRC。
     *
     * ★ 它**不跟着扫描走**：[probeFd] 会填它，但 [Library] 的常规扫描不关心，
     *   只有正在播放页按需读一首时才用得上。
     */
    std::string lyrics;
};

/*
 * 从文件描述符探测元信息。
 *
 * 用 fd 而不是路径，和播放走同一条路 —— 库内文件是普通路径，
 * 但 SAF 导入的也是 content:// URI，两种都能给出 fd。
 *
 * ★ **不改动 fd 的当前位置语义**：内部只在打开时 dup 一份自己用，
 *   调用方传进来的 fd 该谁关还谁关（这里不会 close 它）。
 */
bool probeFd(int fd, MediaInfo* out, std::string* err);

}  // namespace hifi
