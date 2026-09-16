// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>

#include <chiaki/thread.h>
#include <chiaki/log.h>
#include <chiaki/seqnum.h>

#include "video-presenter.h"

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

typedef struct android_chiaki_video_stats_t
{
	uint64_t input_frames_dropped;
	uint64_t missed_vsyncs;
	uint64_t presenter_frames_dropped;
	uint64_t dejitter_buffer_ns;
} AndroidChiakiVideoStats;

typedef struct android_chiaki_video_diagnostics_t
{
	uint64_t output_frames;
	uint64_t decode_mean_us;
	uint64_t decode_p95_us;
	uint64_t input_frames_dropped;
	uint64_t missed_vsyncs;
	uint64_t presenter_frames_dropped;
	uint64_t presenter_bounded_age_frames_dropped;
	uint64_t dejitter_buffer_ns;
	uint32_t presenter_queue_depth;
} AndroidChiakiVideoDiagnostics;

typedef ChiakiErrorCode (*AndroidChiakiVideoDecoderRequestIDRCallback)(void *user);

typedef struct android_chiaki_video_decoder_t
{
	ChiakiLog *log;
	ChiakiMutex codec_mutex;
	ChiakiMutex input_mutex;
	ChiakiCond input_cond;
	ChiakiMutex stats_mutex;
	AMediaCodec *codec;
	ANativeWindow *window;
	uint64_t timestamp_cur;
	unsigned int fps;
	unsigned int pts_rate_hz;
	bool real_pts_enabled;
	ChiakiSeqNum16Unwrapper frame_index_unwrapper;
	AndroidChiakiVideoPresenter presenter;
	ChiakiThread input_thread;
	bool input_thread_enabled;
	bool shutdown_input;
	bool input_pending;
	uint8_t *input_buf;
	size_t input_buf_size;
	size_t input_buf_capacity;
	ChiakiSeqNum16 input_frame_index;
	uint64_t input_frame_ready_time_us;
	uint64_t input_frames_dropped;
	int32_t target_width;
	int32_t target_height;
	int32_t target_fps;
	ChiakiCodec target_codec;
	bool low_latency_enabled;
	bool performance_mode_enabled;
	int32_t operating_rate;
	bool operating_rate_auto;
	bool realtime_priority;
	bool diagnostics_enabled;
	bool late_frame_recovery_enabled;
	bool last_queued_frame_index_valid;
	ChiakiSeqNum16 last_queued_frame_index;
	uint64_t output_backlog;
	uint64_t output_frames_released;
	uint64_t output_frames_dropped;
	bool backlog_idr_requested;
	AndroidChiakiVideoDecoderRequestIDRCallback request_idr_cb;
	void *request_idr_cb_user;
} AndroidChiakiVideoDecoder;

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height,
		int32_t target_fps, ChiakiCodec codec, bool low_latency_enabled, bool real_pts_enabled,
		bool input_thread_enabled, bool late_frame_recovery_enabled, bool performance_mode_enabled,
		int32_t operating_rate, bool operating_rate_auto, bool realtime_priority, unsigned int pts_rate_hz,
		bool diagnostics_enabled);
void android_chiaki_video_decoder_set_request_idr_cb(AndroidChiakiVideoDecoder *decoder,
		AndroidChiakiVideoDecoderRequestIDRCallback cb, void *user);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface,
		unsigned int stream_fps, double refresh_hz, int64_t app_vsync_offset_ns,
		AndroidChiakiVideoPacingMode pacing_mode, AndroidChiakiVideoPresenterLead presenter_lead,
		uint32_t max_queue_age_periods, bool nonblocking_producer);
void android_chiaki_video_decoder_set_timing(AndroidChiakiVideoDecoder *decoder,
		unsigned int stream_fps, double refresh_hz, int64_t app_vsync_offset_ns,
		AndroidChiakiVideoPacingMode pacing_mode, AndroidChiakiVideoPresenterLead presenter_lead,
		uint32_t max_queue_age_periods, bool nonblocking_producer);
void android_chiaki_video_decoder_set_pacing_mode(AndroidChiakiVideoDecoder *decoder,
		AndroidChiakiVideoPacingMode pacing_mode);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size,
		ChiakiSeqNum16 frame_index, uint64_t frame_ready_time_us, int32_t frames_lost,
		bool frame_recovered, void *user);
void android_chiaki_video_decoder_get_stats(AndroidChiakiVideoDecoder *decoder, AndroidChiakiVideoStats *stats);
void android_chiaki_video_decoder_get_diagnostics(AndroidChiakiVideoDecoder *decoder,
		AndroidChiakiVideoDiagnostics *diagnostics);

#endif
