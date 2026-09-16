// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-presenter-recovery.h"

#define PACING_MODE_BALANCED 2

AndroidChiakiVideoRecoveryStrategy android_chiaki_video_recovery_sanitize_strategy(int strategy)
{
	if(strategy != (int)ANDROID_CHIAKI_VIDEO_RECOVERY_FLUSH)
		return ANDROID_CHIAKI_VIDEO_RECOVERY_TIMELINE_SHIFT;
	return ANDROID_CHIAKI_VIDEO_RECOVERY_FLUSH;
}

AndroidChiakiVideoRecoveryAction android_chiaki_video_recovery_action(
		AndroidChiakiVideoRecoveryStrategy strategy, int pacing_mode)
{
	if(strategy == ANDROID_CHIAKI_VIDEO_RECOVERY_FLUSH)
		return ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_FLUSH_QUEUE;
	if(pacing_mode == PACING_MODE_BALANCED)
		return ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_DROP_HEAD;
	return ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_SHIFT_TIMELINE;
}

bool android_chiaki_video_recovery_flush_on_transition(AndroidChiakiVideoRecoveryStrategy strategy)
{
	return strategy == ANDROID_CHIAKI_VIDEO_RECOVERY_FLUSH;
}

int64_t android_chiaki_video_recovery_shift_ns(int64_t lateness_ns, int64_t vsync_period_ns)
{
	if(vsync_period_ns <= 0 || lateness_ns <= 0)
		return 0;
	return ((lateness_ns + vsync_period_ns - 1) / vsync_period_ns) * vsync_period_ns;
}
