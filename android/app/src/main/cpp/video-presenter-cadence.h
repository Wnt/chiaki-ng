// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_PRESENTER_CADENCE_H
#define CHIAKI_JNI_VIDEO_PRESENTER_CADENCE_H

#include "video-presenter-histogram.h"

#include <chiaki/seqnum.h>

#include <stdbool.h>
#include <stdint.h>

#define ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW 120
#define ANDROID_CHIAKI_VIDEO_DECODE_HIGH_WINDOWS 4

typedef struct android_chiaki_video_cadence_t
{
	ChiakiSeqNum16Unwrapper frame_index_unwrapper;
	bool clock_valid;
	uint64_t base_frame_index;
	uint64_t last_frame_index;
	int64_t anchor_ns;
	int64_t previous_positive_err_ns;
	int64_t previous_ready_ns;
	AndroidChiakiVideoHistogram err_histogram;
	AndroidChiakiVideoHistogram jitter_histogram;
	uint32_t gap_count;
	uint64_t decode_ewma_ns;
	uint64_t decode_window_max_ns;
	uint64_t decode_high_windows[ANDROID_CHIAKI_VIDEO_DECODE_HIGH_WINDOWS];
	uint32_t decode_high_count;
	uint32_t decode_high_next;
	uint64_t depth_floor_ns;
	uint64_t depth_cap_ns;
	uint64_t depth_ns;
	uint64_t target_ns;
	uint64_t err_p50_ns;
	uint64_t err_p99_ns;
	uint64_t jitter_p95_ns;
	uint32_t gaps;
	uint64_t generation;
} AndroidChiakiVideoCadence;

void android_chiaki_video_cadence_reset(AndroidChiakiVideoCadence *cadence,
		uint64_t depth_floor_ns, uint64_t depth_cap_ns);
void android_chiaki_video_cadence_record_decode(AndroidChiakiVideoCadence *cadence,
		uint64_t decode_ns);
bool android_chiaki_video_cadence_record_frame(AndroidChiakiVideoCadence *cadence,
		ChiakiSeqNum16 frame_index, uint64_t frame_ready_time_us, unsigned int stream_fps);

#endif
