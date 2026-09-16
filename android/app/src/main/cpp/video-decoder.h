// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>

#include <chiaki/thread.h>
#include <chiaki/log.h>
#include <chiaki/seqnum.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

typedef ChiakiErrorCode (*AndroidChiakiVideoDecoderRequestIDRCallback)(void *user);

typedef struct android_chiaki_video_decoder_t
{
	ChiakiLog *log;
	ChiakiMutex codec_mutex;
	ChiakiMutex stats_mutex;
	AMediaCodec *codec;
	ANativeWindow *window;
	uint64_t timestamp_cur;
	unsigned int fps;
	bool real_pts_enabled;
	ChiakiSeqNum16Unwrapper frame_index_unwrapper;
	ChiakiThread output_thread;
	bool shutdown_output;
	int32_t target_width;
	int32_t target_height;
	int32_t target_fps;
	ChiakiCodec target_codec;
	bool low_latency_enabled;
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
		int32_t target_fps, ChiakiCodec codec, bool low_latency_enabled, bool real_pts_enabled, bool late_frame_recovery_enabled);
void android_chiaki_video_decoder_set_request_idr_cb(AndroidChiakiVideoDecoder *decoder,
		AndroidChiakiVideoDecoderRequestIDRCallback cb, void *user);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, ChiakiSeqNum16 frame_index, int32_t frames_lost, bool frame_recovered, void *user);

#endif
