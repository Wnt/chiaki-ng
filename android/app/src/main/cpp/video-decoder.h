// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>

#include <chiaki/thread.h>
#include <chiaki/log.h>
#include <chiaki/seqnum.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

typedef struct android_chiaki_video_decoder_t
{
	ChiakiLog *log;
	ChiakiMutex codec_mutex;
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
} AndroidChiakiVideoDecoder;

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height,
		int32_t target_fps, ChiakiCodec codec, bool low_latency_enabled, bool real_pts_enabled);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, ChiakiSeqNum16 frame_index, int32_t frames_lost, bool frame_recovered, void *user);

#endif
