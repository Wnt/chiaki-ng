// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-presenter-timing.h"

#include <stdbool.h>

static bool within_five_percent(int64_t value, int64_t target)
{
	int64_t difference = value > target ? value - target : target - value;
	return difference <= target / 20;
}

AndroidChiakiVideoPresenterPeriodObservation android_chiaki_video_presenter_classify_period(
		int64_t current_period_ns, int64_t observed_period_ns)
{
	if(current_period_ns <= 0 || observed_period_ns <= 0)
		return ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_GAP;

	if(within_five_percent(observed_period_ns, current_period_ns * 2)
			|| within_five_percent(observed_period_ns, current_period_ns / 2))
		return ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_RELOCK;

	if(observed_period_ns > current_period_ns / 2 && observed_period_ns < current_period_ns * 3 / 2)
		return ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_SMOOTH;

	return ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_GAP;
}
