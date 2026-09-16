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

int64_t android_chiaki_video_presenter_seed_period(double panel_refresh_hz,
		int64_t choreographer_period_ns, unsigned int stream_fps)
{
	if(panel_refresh_hz > 1.0)
		return (int64_t)(1000000000.0 / panel_refresh_hz + 0.5);
	if(choreographer_period_ns > 0)
		return choreographer_period_ns;
	if(stream_fps == 0)
		stream_fps = 60;
	return (int64_t)((1000000000ULL + stream_fps / 2) / stream_fps);
}

int64_t android_chiaki_video_presenter_update_period(int64_t current_period_ns,
		int64_t observed_period_ns)
{
	switch(android_chiaki_video_presenter_classify_period(current_period_ns, observed_period_ns))
	{
		case ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_RELOCK:
			return observed_period_ns;
		case ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_SMOOTH:
			return (current_period_ns * 7 + observed_period_ns) / 8;
		case ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_GAP:
		default:
			return current_period_ns;
	}
}
