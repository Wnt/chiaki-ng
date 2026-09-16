// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include "../android/app/src/main/cpp/video-presenter-age.h"
#include "../android/app/src/main/cpp/video-presenter-cadence.h"
#include "../android/app/src/main/cpp/video-presenter-histogram.h"
#include "../android/app/src/main/cpp/video-presenter-timing.h"

#include <stdlib.h>

static int compare_u64(const void *left, const void *right)
{
	uint64_t a = *(const uint64_t *)left;
	uint64_t b = *(const uint64_t *)right;
	return a < b ? -1 : a > b ? 1 : 0;
}

static uint64_t sorted_percentile(uint64_t *samples, uint32_t count,
		uint32_t numerator, uint32_t denominator)
{
	qsort(samples, count, sizeof(samples[0]), compare_u64);
	uint32_t index = (numerator * count + denominator - 1) / denominator;
	if(index > 0)
		index--;
	return samples[index];
}

static MunitResult test_histogram_matches_sorted_percentile(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	uint64_t samples[300];
	AndroidChiakiVideoHistogram histogram;
	android_chiaki_video_histogram_reset(&histogram);
	uint32_t state = 0x12345678;
	for(uint32_t i = 0; i < 300; i++)
	{
		state = state * 1664525U + 1013904223U;
		samples[i] = state % ANDROID_CHIAKI_VIDEO_HISTOGRAM_MAX_NS;
		android_chiaki_video_histogram_add(&histogram, samples[i]);
	}

	const uint32_t numerators[] = { 500, 950, 997 };
	for(size_t i = 0; i < sizeof(numerators) / sizeof(numerators[0]); i++)
	{
		uint64_t expected = sorted_percentile(samples, 300, numerators[i], 1000);
		uint64_t actual = android_chiaki_video_histogram_percentile(&histogram,
				numerators[i], 1000);
		munit_assert_uint64(actual, <=, expected);
		munit_assert_uint64(expected - actual, <, ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKET_NS);
	}

	android_chiaki_video_histogram_reset(&histogram);
	android_chiaki_video_histogram_add(&histogram, ANDROID_CHIAKI_VIDEO_HISTOGRAM_MAX_NS + 1);
	munit_assert_uint64(android_chiaki_video_histogram_percentile(&histogram, 997, 1000), ==,
			ANDROID_CHIAKI_VIDEO_HISTOGRAM_MAX_NS);
	return MUNIT_OK;
}

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

static MunitResult test_cadence_stable_window(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	AndroidChiakiVideoCadence cadence;
	android_chiaki_video_cadence_reset(&cadence);
	const uint64_t period_us = 1000000 / 60;
	for(uint32_t i = 0; i < ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW; i++)
	{
		android_chiaki_video_cadence_record_decode(&cadence, 8000000);
		bool complete = android_chiaki_video_cadence_record_frame(&cadence,
				(ChiakiSeqNum16)(0xffc0 + i), 1000000 + i * period_us, 60);
		munit_assert_int(complete, ==, i + 1 == ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW);
	}
	munit_assert_uint64(cadence.generation, ==, 1);
	munit_assert_uint64(cadence.err_p50_ns, ==, 0);
	munit_assert_uint64(cadence.err_p99_ns, ==, 0);
	munit_assert_uint64(cadence.decode_ewma_ns, ==, 8000000);
	munit_assert_uint64(cadence.target_ns, ==, 4000000);
	munit_assert_uint64(cadence.depth_ns, ==, 4000000);
	return MUNIT_OK;
}

static MunitResult test_cadence_late_tail_raises_target(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	AndroidChiakiVideoCadence cadence;
	android_chiaki_video_cadence_reset(&cadence);
	const uint64_t period_us = 1000000 / 60;
	for(uint32_t i = 0; i < ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW; i++)
	{
		uint64_t late_us = i >= 100 ? 10000 : 0;
		android_chiaki_video_cadence_record_decode(&cadence, 8000000);
		android_chiaki_video_cadence_record_frame(&cadence, (ChiakiSeqNum16)i,
				2000000 + i * period_us + late_us, 60);
	}
	munit_assert_uint64(cadence.err_p99_ns, >=, 8000000);
	munit_assert_uint64(cadence.target_ns, >=, 9000000);
	munit_assert_uint64(cadence.depth_ns, ==, cadence.target_ns);
	return MUNIT_OK;
}

static MunitResult test_period_observation(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	const int64_t period_120_hz = 8333333;
	const int64_t period_60_hz = 16666667;

	munit_assert_int(android_chiaki_video_presenter_classify_period(period_120_hz, period_60_hz), ==,
			ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_RELOCK);
	munit_assert_int(android_chiaki_video_presenter_classify_period(period_60_hz, period_120_hz), ==,
			ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_RELOCK);
	munit_assert_int(android_chiaki_video_presenter_classify_period(period_120_hz, 16500000), ==,
			ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_RELOCK);
	munit_assert_int(android_chiaki_video_presenter_classify_period(period_60_hz, 8400000), ==,
			ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_RELOCK);
	munit_assert_int(android_chiaki_video_presenter_classify_period(period_60_hz, 17000000), ==,
			ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_SMOOTH);
	munit_assert_int(android_chiaki_video_presenter_classify_period(period_120_hz, 25000000), ==,
			ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_GAP);
	return MUNIT_OK;
}

MunitTest tests_video_presenter[] = {
	{
		"/histogram_matches_sorted_percentile",
		test_histogram_matches_sorted_percentile,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{
		"/frame_exceeds_age",
		test_frame_exceeds_age,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{
		"/cadence_stable_window",
		test_cadence_stable_window,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{
		"/cadence_late_tail_raises_target",
		test_cadence_late_tail_raises_target,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{
		"/period_observation",
		test_period_observation,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
};
