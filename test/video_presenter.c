// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include "../android/app/src/main/cpp/video-presenter-age.h"
#include "../android/app/src/main/cpp/video-presenter-recovery.h"

static MunitResult test_frame_exceeds_age(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	munit_assert_false(android_chiaki_video_presenter_frame_exceeds_age(0, 50000, 60, 0));
	munit_assert_false(android_chiaki_video_presenter_frame_exceeds_age(0, 50000, 0, 2));
	munit_assert_false(android_chiaki_video_presenter_frame_exceeds_age(1000, 1000, 60, 2));
	munit_assert_false(android_chiaki_video_presenter_frame_exceeds_age(2000, 1000, 60, 2));

	// Two 60 fps periods are rounded up to 33,334 us; the boundary is retained.
	munit_assert_false(android_chiaki_video_presenter_frame_exceeds_age(0, 33334, 60, 2));
	munit_assert_true(android_chiaki_video_presenter_frame_exceeds_age(0, 33335, 60, 2));

	// The same two-period setting follows the stream rate instead of a fixed millisecond value.
	munit_assert_false(android_chiaki_video_presenter_frame_exceeds_age(100000, 166667, 30, 2));
	munit_assert_true(android_chiaki_video_presenter_frame_exceeds_age(100000, 166668, 30, 2));

	return MUNIT_OK;
}

static MunitResult test_recovery_action(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	// Anything that is not the flush strategy stays on today's behaviour.
	munit_assert_int(android_chiaki_video_recovery_sanitize_strategy(0), ==,
			ANDROID_CHIAKI_VIDEO_RECOVERY_TIMELINE_SHIFT);
	munit_assert_int(android_chiaki_video_recovery_sanitize_strategy(7), ==,
			ANDROID_CHIAKI_VIDEO_RECOVERY_TIMELINE_SHIFT);
	munit_assert_int(android_chiaki_video_recovery_sanitize_strategy(-1), ==,
			ANDROID_CHIAKI_VIDEO_RECOVERY_TIMELINE_SHIFT);
	munit_assert_int(android_chiaki_video_recovery_sanitize_strategy(1), ==,
			ANDROID_CHIAKI_VIDEO_RECOVERY_FLUSH);

	// timeline_shift keeps the split the presenter has today: balanced drops the
	// head frame, every other pacing mode shifts the timeline.
	for(int mode = 0; mode <= 3; mode++)
	{
		AndroidChiakiVideoRecoveryAction expected = mode == 2
				? ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_DROP_HEAD
				: ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_SHIFT_TIMELINE;
		munit_assert_int(android_chiaki_video_recovery_action(
				ANDROID_CHIAKI_VIDEO_RECOVERY_TIMELINE_SHIFT, mode), ==, expected);
		munit_assert_int(android_chiaki_video_recovery_action(
				ANDROID_CHIAKI_VIDEO_RECOVERY_FLUSH, mode), ==,
				ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_FLUSH_QUEUE);
	}

	munit_assert_false(android_chiaki_video_recovery_flush_on_transition(
			ANDROID_CHIAKI_VIDEO_RECOVERY_TIMELINE_SHIFT));
	munit_assert_true(android_chiaki_video_recovery_flush_on_transition(
			ANDROID_CHIAKI_VIDEO_RECOVERY_FLUSH));

	return MUNIT_OK;
}

static MunitResult test_recovery_shift_ns(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	const int64_t period = 16666667;
	munit_assert_int64(android_chiaki_video_recovery_shift_ns(0, period), ==, 0);
	munit_assert_int64(android_chiaki_video_recovery_shift_ns(-5, period), ==, 0);
	munit_assert_int64(android_chiaki_video_recovery_shift_ns(1, period), ==, period);
	munit_assert_int64(android_chiaki_video_recovery_shift_ns(period, period), ==, period);
	munit_assert_int64(android_chiaki_video_recovery_shift_ns(period + 1, period), ==, 2 * period);
	// A zero period cannot be aligned to; recovery must not divide by it.
	munit_assert_int64(android_chiaki_video_recovery_shift_ns(period, 0), ==, 0);

	return MUNIT_OK;
}

MunitTest tests_video_presenter[] = {
	{
		"/frame_exceeds_age",
		test_frame_exceeds_age,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{
		"/recovery_action",
		test_recovery_action,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{
		"/recovery_shift_ns",
		test_recovery_shift_ns,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
};
