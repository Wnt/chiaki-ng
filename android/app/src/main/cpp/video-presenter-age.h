// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_PRESENTER_AGE_H
#define CHIAKI_JNI_VIDEO_PRESENTER_AGE_H

#include <stdbool.h>
#include <stdint.h>

bool android_chiaki_video_presenter_frame_exceeds_age(int64_t head_pts_us,
		int64_t newest_pts_us, unsigned int stream_fps, uint32_t max_age_periods);

#endif
