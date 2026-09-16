// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_PRESENTER_TIMING_H
#define CHIAKI_JNI_VIDEO_PRESENTER_TIMING_H

#include <stdint.h>

typedef enum android_chiaki_video_presenter_period_observation_t
{
	ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_SMOOTH,
	ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_RELOCK,
	ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_GAP,
} AndroidChiakiVideoPresenterPeriodObservation;

AndroidChiakiVideoPresenterPeriodObservation android_chiaki_video_presenter_classify_period(
		int64_t current_period_ns, int64_t observed_period_ns);

int64_t android_chiaki_video_presenter_seed_period(double panel_refresh_hz,
		int64_t choreographer_period_ns, unsigned int stream_fps);

int64_t android_chiaki_video_presenter_update_period(int64_t current_period_ns,
		int64_t observed_period_ns);

#endif
