#pragma once

#include "loudness_meter.h"

#include <atomic>

namespace hifi {

/*
 * 一次分析任务的状态。
 *
 * 一个 Job 可以只测一首（TrackJob），也可以连续测一整张专辑（AlbumJob）。
 * 专辑任务用**一个连续的 albumMeter**，各曲目依次喂进去；
 * 同时每首再用独立的 trackMeter 算单曲值 —— 一次解码同时得到两种结果。
 */
struct LoudnessAnalysisJob {
    std::atomic<bool> cancel{false};

    LoudnessMeter albumMeter;
    bool albumStarted = false;
    bool albumFailed = false;
    double albumPeak = 0.0;
    int albumRate = 0;
    int albumChannels = 0;
};

struct TrackLoudness {
    double lufs = 0.0;
    double truePeakDb = 0.0;
    int sampleRate = 0;
    int channels = 0;
    bool valid = false;
};

LoudnessAnalysisJob* loudnessJobCreate();
void loudnessJobDestroy(LoudnessAnalysisJob* job);
void loudnessJobCancel(LoudnessAnalysisJob* job);

/*
 * 分析一个 fd 指向的音频文件。
 *
 * ★ 调用方的 fd 不会被关闭；内部 dup 一份自己用（和 media_probe 一致）。
 * ★ 结果同时喂进 job 的专辑计 —— 连续调用多次就是一张专辑。
 */
TrackLoudness loudnessAnalyzeTrack(LoudnessAnalysisJob* job, int fd);

/* 收尾专辑计。只有前面所有曲目都成功时才 valid */
LoudnessResult loudnessFinishAlbum(LoudnessAnalysisJob* job);

}  // namespace hifi
