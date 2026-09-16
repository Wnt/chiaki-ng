// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-presenter-age.h"

bool android_chiaki_video_presenter_frame_exceeds_age(int64_t head_pts_us,
		int64_t newest_pts_us, unsigned int stream_fps, uint32_t max_age_periods)
{
	if(max_age_periods == 0 || stream_fps == 0 || newest_pts_us <= head_pts_us)
		return false;

	uint64_t max_age_us = ((uint64_t)max_age_periods * 1000000ULL + stream_fps - 1)
			/ stream_fps;
	uint64_t age_us = (uint64_t)newest_pts_us - (uint64_t)head_pts_us;
	return age_us > max_age_us;
}
