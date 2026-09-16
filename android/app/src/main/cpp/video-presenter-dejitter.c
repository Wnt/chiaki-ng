// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-presenter-dejitter.h"

#include <limits.h>

int64_t android_chiaki_video_dejitter_release_time_ns(uint64_t frame_ready_time_us,
		uint64_t target_depth_ns, int64_t next_vsync_ns, int64_t vsync_period_ns,
		int64_t presenter_lead_ns)
{
	if(vsync_period_ns <= 0 || next_vsync_ns <= 0
			|| frame_ready_time_us > (uint64_t)INT64_MAX / 1000)
		return 0;

	int64_t ready_ns = (int64_t)(frame_ready_time_us * 1000);
	if(target_depth_ns > (uint64_t)(INT64_MAX - ready_ns))
		return 0;
	int64_t target_ns = ready_ns + (int64_t)target_depth_ns;
	int64_t target_vsync_ns = next_vsync_ns;
	if(target_ns > next_vsync_ns)
	{
		int64_t delta_ns = target_ns - next_vsync_ns;
		if(delta_ns > INT64_MAX - (vsync_period_ns - 1))
			return 0;
		int64_t periods = (delta_ns + vsync_period_ns - 1) / vsync_period_ns;
		if(periods > (INT64_MAX - next_vsync_ns) / vsync_period_ns)
			return 0;
		target_vsync_ns += periods * vsync_period_ns;
	}

	if(presenter_lead_ns < 0)
		presenter_lead_ns = 0;
	return target_vsync_ns > presenter_lead_ns
			? target_vsync_ns - presenter_lead_ns : 0;
}
