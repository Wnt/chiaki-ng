// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_PRESENTER_H
#define CHIAKI_JNI_VIDEO_PRESENTER_H

#include <android/choreographer.h>
#include <android/looper.h>
#include <media/NdkMediaCodec.h>

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <stdbool.h>
#include <stdint.h>

#define ANDROID_CHIAKI_VIDEO_PRESENTER_QUEUE_CAPACITY 5
#define ANDROID_CHIAKI_VIDEO_PRESENTER_JITTER_WINDOW 300

typedef enum android_chiaki_video_pacing_mode_t
{
	ANDROID_CHIAKI_VIDEO_PACING_DISABLED = 0,
	ANDROID_CHIAKI_VIDEO_PACING_LOWEST_LATENCY = 1,
	ANDROID_CHIAKI_VIDEO_PACING_BALANCED = 2,
	ANDROID_CHIAKI_VIDEO_PACING_SMOOTHEST = 3,
} AndroidChiakiVideoPacingMode;

typedef struct android_chiaki_video_presenter_stats_t
{
	uint64_t missed_vsyncs;
	uint64_t dropped_frames;
	uint64_t dejitter_buffer_ns;
	uint32_t queue_depth;
} AndroidChiakiVideoPresenterStats;

typedef void (*AndroidChiakiVideoPresenterReleaseCallback)(void *user, bool dropped);

typedef struct android_chiaki_video_presenter_frame_t
{
	size_t index;
	AMediaCodecBufferInfo info;
	int64_t arrival_ns;
} AndroidChiakiVideoPresenterFrame;

typedef struct android_chiaki_video_presenter_t
{
	ChiakiLog *log;
	ChiakiMutex mutex;
	ChiakiCond queue_cond;
	AMediaCodec *codec;
	ChiakiThread output_thread;
	ChiakiThread vsync_thread;
	bool output_thread_started;
	bool vsync_thread_started;
	bool shutdown;
	ALooper *looper;
	AChoreographer *choreographer;

	AndroidChiakiVideoPresenterFrame queue[ANDROID_CHIAKI_VIDEO_PRESENTER_QUEUE_CAPACITY];
	uint32_t queue_head;
	uint32_t queue_size;

	AndroidChiakiVideoPacingMode mode;
	bool timestamped_release_enabled;
	bool late_frame_recovery_enabled;
	bool real_pts_enabled;
	unsigned int stream_fps;
	double refresh_hz;
	int64_t app_vsync_offset_ns;
	int64_t vsync_period_ns;
	int64_t last_vsync_ns;

	bool timeline_valid;
	int64_t timeline_offset_ns;
	uint64_t dejitter_buffer_ns;
	int64_t arrival_offsets[ANDROID_CHIAKI_VIDEO_PRESENTER_JITTER_WINDOW];
	uint32_t arrival_offset_count;
	uint32_t arrival_offset_next;
	uint32_t samples_since_adjustment;
	uint32_t decrease_hysteresis;

	uint64_t missed_vsyncs;
	uint64_t dropped_frames;
	AndroidChiakiVideoPresenterReleaseCallback release_cb;
	void *release_cb_user;
} AndroidChiakiVideoPresenter;

ChiakiErrorCode android_chiaki_video_presenter_init(AndroidChiakiVideoPresenter *presenter, ChiakiLog *log,
		bool late_frame_recovery_enabled, bool real_pts_enabled,
		AndroidChiakiVideoPresenterReleaseCallback release_cb, void *release_cb_user);
void android_chiaki_video_presenter_fini(AndroidChiakiVideoPresenter *presenter);
ChiakiErrorCode android_chiaki_video_presenter_start(AndroidChiakiVideoPresenter *presenter, AMediaCodec *codec,
		unsigned int stream_fps, double refresh_hz, int64_t app_vsync_offset_ns,
		AndroidChiakiVideoPacingMode mode);
void android_chiaki_video_presenter_request_stop(AndroidChiakiVideoPresenter *presenter);
void android_chiaki_video_presenter_join(AndroidChiakiVideoPresenter *presenter);
void android_chiaki_video_presenter_set_mode(AndroidChiakiVideoPresenter *presenter,
		AndroidChiakiVideoPacingMode mode);
void android_chiaki_video_presenter_set_timing(AndroidChiakiVideoPresenter *presenter,
		unsigned int stream_fps, double refresh_hz, int64_t app_vsync_offset_ns,
		AndroidChiakiVideoPacingMode mode);
void android_chiaki_video_presenter_get_stats(AndroidChiakiVideoPresenter *presenter,
		AndroidChiakiVideoPresenterStats *stats);

#endif
