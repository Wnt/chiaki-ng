// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include "../android/app/src/main/cpp/video-presenter-age.h"

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

MunitTest tests_video_presenter[] = {
	{
		"/frame_exceeds_age",
		test_frame_exceeds_age,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
};
