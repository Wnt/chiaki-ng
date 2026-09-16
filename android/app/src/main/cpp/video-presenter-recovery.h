// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_PRESENTER_RECOVERY_H
#define CHIAKI_JNI_VIDEO_PRESENTER_RECOVERY_H

#include <stdbool.h>
#include <stdint.h>

// How the presenter recovers when the head frame is already late for the next
// vsync. PLE-107 measures the two against each other on the phone; the shift is
// what the fork has always done, the flush is what GFN's AsyncFrameQueue::push
// does (it drops the whole queue on every pacing transition).
typedef enum android_chiaki_video_recovery_strategy_t
{
	ANDROID_CHIAKI_VIDEO_RECOVERY_TIMELINE_SHIFT = 0,
	ANDROID_CHIAKI_VIDEO_RECOVERY_FLUSH = 1,
} AndroidChiakiVideoRecoveryStrategy;

typedef enum android_chiaki_video_recovery_action_t
{
	ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_SHIFT_TIMELINE = 0,
	ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_DROP_HEAD = 1,
	ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_FLUSH_QUEUE = 2,
} AndroidChiakiVideoRecoveryAction;

AndroidChiakiVideoRecoveryStrategy android_chiaki_video_recovery_sanitize_strategy(int strategy);

// pacing_mode carries AndroidChiakiVideoPacingMode's values; taking it as an int
// keeps this file free of the NDK headers video-presenter.h needs, so the host
// unit test can link it.
AndroidChiakiVideoRecoveryAction android_chiaki_video_recovery_action(
		AndroidChiakiVideoRecoveryStrategy strategy, int pacing_mode);

bool android_chiaki_video_recovery_flush_on_transition(AndroidChiakiVideoRecoveryStrategy strategy);

// Whole vsync periods to add to the timeline so a frame that is lateness_ns
// early of the next vsync lands on it or after it.
int64_t android_chiaki_video_recovery_shift_ns(int64_t lateness_ns, int64_t vsync_period_ns);

#endif
