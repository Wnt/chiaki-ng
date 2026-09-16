// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_PRESENTER_DEJITTER_H
#define CHIAKI_JNI_VIDEO_PRESENTER_DEJITTER_H

#include <stdint.h>

// Computes the timestamp passed to AMediaCodec_releaseOutputBufferAtTime.
// The arrival-derived depth is applied to the receiver-side frame-ready time;
// only the resulting target is rounded to the display's vsync grid.
int64_t android_chiaki_video_dejitter_release_time_ns(uint64_t frame_ready_time_us,
		uint64_t target_depth_ns, int64_t next_vsync_ns, int64_t vsync_period_ns,
		int64_t presenter_lead_ns);

#endif
