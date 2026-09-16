// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include "../android/app/src/main/cpp/video-presenter-age.h"
#include "../android/app/src/main/cpp/video-presenter-recovery.h"
#include "../android/app/src/main/cpp/video-presenter-cadence.h"
#include "../android/app/src/main/cpp/video-presenter-dejitter.h"
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

static void assert_cadence_persistent_phase_step_relocks(void);
static void assert_cadence_clock_drift_stays_in_range(void);
static void assert_half_rate_detector_30_in_60(void);
static void assert_half_rate_detector_switch(void);
static void assert_dejitter_release_time(void);

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
	android_chiaki_video_histogram_add(&histogram, 200123456ULL);
	munit_assert_uint64(android_chiaki_video_histogram_percentile(&histogram, 997, 1000), ==,
			200000000ULL);

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
	android_chiaki_video_cadence_reset(&cadence, 12000000ULL, 32000000ULL, false);
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
	munit_assert_uint64(cadence.jitter_p95_ns, ==, 0);
	munit_assert_uint32(cadence.gaps, ==, 0);
	munit_assert_uint64(cadence.decode_ewma_ns, ==, 8000000);
	munit_assert_uint64(cadence.target_ns, ==, 12000000);
	munit_assert_uint64(cadence.depth_ns, ==, 12000000);
	munit_assert_false(cadence.half_rate_detected);
	assert_cadence_persistent_phase_step_relocks();
	assert_cadence_clock_drift_stays_in_range();
	assert_half_rate_detector_30_in_60();
	assert_half_rate_detector_switch();
	assert_dejitter_release_time();
	return MUNIT_OK;
}

static MunitResult test_cadence_late_tail_raises_target(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	AndroidChiakiVideoCadence cadence;
	android_chiaki_video_cadence_reset(&cadence, 4000000ULL, 32000000ULL, false);
	const uint64_t period_us = 1000000 / 60;
	for(uint32_t i = 0; i < ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW; i++)
	{
		// Repeated isolated late arrivals are jitter, rather than a persistent
		// source-clock phase change, and must remain visible to the percentile.
		uint64_t late_us = i >= 100 && (i & 1) == 0 ? 10000 : 0;
		android_chiaki_video_cadence_record_decode(&cadence, 8000000);
		android_chiaki_video_cadence_record_frame(&cadence, (ChiakiSeqNum16)i,
				2000000 + i * period_us + late_us, 60);
	}
	munit_assert_uint64(cadence.err_p99_ns, >=, 8000000);
	munit_assert_uint64(cadence.target_ns, >=, 9000000);
	munit_assert_uint64(cadence.depth_ns, ==, cadence.target_ns);
	munit_assert_uint64(cadence.jitter_p95_ns, >=, 9000000);
	munit_assert_uint32(cadence.gaps, >=, 5);
	return MUNIT_OK;
}

static void assert_cadence_persistent_phase_step_relocks(void)
{
	AndroidChiakiVideoCadence cadence;
	android_chiaki_video_cadence_reset(&cadence, 4000000ULL, 32000000ULL, false);
	const uint64_t period_us = 1000000 / 60;
	for(uint32_t i = 0; i < ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW; i++)
	{
		// Model a clean encoder cadence pause: after frame 59 every subsequent
		// frame is one period later. Only the transition is an outlier; the new
		// source-clock phase must not make the remaining window late.
		uint64_t phase_us = i >= 60 ? period_us : 0;
		android_chiaki_video_cadence_record_decode(&cadence, 8000000);
		android_chiaki_video_cadence_record_frame(&cadence, (ChiakiSeqNum16)i,
				3000000 + i * period_us + phase_us, 60);
	}
	munit_assert_uint64(cadence.err_p99_ns, <, 4000000);
	munit_assert_uint64(cadence.target_ns, ==, 4000000);
}

static void assert_cadence_clock_drift_stays_in_range(void)
{
	AndroidChiakiVideoCadence cadence;
	android_chiaki_video_cadence_reset(&cadence, 4000000ULL, 32000000ULL, false);
	// Deliberately exaggerate source/nominal clock drift to 8,000 ppm. The
	// recovered error must remain a measured low tail rather than run into the
	// histogram ceiling or the depth cap over successive windows.
	const uint64_t source_period_us = 16800;
	for(uint32_t i = 0; i < ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW * 4; i++)
	{
		android_chiaki_video_cadence_record_decode(&cadence, 8000000);
		android_chiaki_video_cadence_record_frame(&cadence, (ChiakiSeqNum16)i,
				4000000 + i * source_period_us, 60);
	}
	munit_assert_uint64(cadence.generation, ==, 4);
	munit_assert_uint64(cadence.err_p99_ns, <=, 4000000);
	munit_assert_uint64(cadence.target_ns, <, 8000000);
	munit_assert_uint64(cadence.depth_ns, <, 8000000);
}

static void assert_half_rate_detector_30_in_60(void)
{
	AndroidChiakiVideoCadence detector_only;
	android_chiaki_video_cadence_reset(&detector_only, 4000000ULL, 32000000ULL, false);
	const uint64_t half_rate_period_us = 2 * (1000000 / 60);
	uint64_t ready_us = 5000000;
	for(uint32_t i = 0; i <= ANDROID_CHIAKI_VIDEO_HALF_RATE_CONFIRM_FRAMES; i++)
	{
		android_chiaki_video_cadence_record_frame(&detector_only, (ChiakiSeqNum16)i,
				ready_us, 60);
		ready_us += half_rate_period_us;
	}
	// Detection is diagnostic and unconditional; only adapting the cadence clock
	// is controlled by the default-off setting.
	munit_assert_true(detector_only.half_rate_detected);

	AndroidChiakiVideoCadence cadence;
	android_chiaki_video_cadence_reset(&cadence, 4000000ULL, 32000000ULL, true);
	ready_us = 5000000;
	for(uint32_t i = 0; i < ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW
			+ ANDROID_CHIAKI_VIDEO_HALF_RATE_CONFIRM_FRAMES; i++)
	{
		android_chiaki_video_cadence_record_frame(&cadence, (ChiakiSeqNum16)i,
				ready_us, 60);
		ready_us += half_rate_period_us;
	}
	munit_assert_true(cadence.half_rate_detected);
	munit_assert_uint64(cadence.generation, ==, 1);
	munit_assert_uint64(cadence.err_p99_ns, <, 1000000);
	munit_assert_uint64(cadence.target_ns, ==, 4000000);
}

static void assert_half_rate_detector_switch(void)
{
	AndroidChiakiVideoCadence cadence;
	android_chiaki_video_cadence_reset(&cadence, 4000000ULL, 32000000ULL, true);
	const uint64_t period_us = 1000000 / 60;
	uint64_t ready_us = 6000000;
	ChiakiSeqNum16 frame_index = 0;

	// A stable 60 fps prefix must remain nominal.
	for(uint32_t i = 0; i < 16; i++, frame_index++)
	{
		android_chiaki_video_cadence_record_frame(&cadence, frame_index, ready_us, 60);
		ready_us += period_us;
	}
	munit_assert_false(cadence.half_rate_detected);

	// Eight adjacent frame-index intervals near 2T confirm 30-in-60.
	for(uint32_t i = 0; i < ANDROID_CHIAKI_VIDEO_HALF_RATE_CONFIRM_FRAMES;
			i++, frame_index++)
	{
		ready_us += period_us;
		android_chiaki_video_cadence_record_frame(&cadence, frame_index, ready_us, 60);
		ready_us += period_us;
	}
	munit_assert_true(cadence.half_rate_detected);

	// The same hysteresis returns to nominal after a sustained 60 fps cadence.
	for(uint32_t i = 0; i < ANDROID_CHIAKI_VIDEO_HALF_RATE_CONFIRM_FRAMES;
			i++, frame_index++)
	{
		android_chiaki_video_cadence_record_frame(&cadence, frame_index, ready_us, 60);
		ready_us += period_us;
	}
	munit_assert_false(cadence.half_rate_detected);
}

static void assert_dejitter_release_time(void)
{
	const int64_t next_vsync_ns = 1000000000LL;
	const int64_t period_ns = 16666667LL;
	const int64_t lead_ns = 2000000LL;

	// A target at or before the next vsync is released lead_ns ahead of it.
	munit_assert_int64(android_chiaki_video_dejitter_release_time_ns(
			980000, 12000000ULL, next_vsync_ns, period_ns, lead_ns), ==,
			998000000LL);
	munit_assert_int64(android_chiaki_video_dejitter_release_time_ns(
			988000, 12000000ULL, next_vsync_ns, period_ns, lead_ns), ==,
			998000000LL);

	// A target after the next vsync rounds forward, never backward.
	munit_assert_int64(android_chiaki_video_dejitter_release_time_ns(
			990000, 12000000ULL, next_vsync_ns, period_ns, lead_ns), ==,
			1014666667LL);
	munit_assert_int64(android_chiaki_video_dejitter_release_time_ns(
			990000, 12000000ULL, next_vsync_ns, 8333333LL, lead_ns), ==,
			1006333333LL);

	munit_assert_int64(android_chiaki_video_dejitter_release_time_ns(
			990000, 12000000ULL, next_vsync_ns, 0, lead_ns), ==, 0);
	munit_assert_int64(android_chiaki_video_dejitter_release_time_ns(
			UINT64_MAX, 12000000ULL, next_vsync_ns, period_ns, lead_ns), ==, 0);
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

static MunitResult test_recovery_release_decision(const MunitParameter params[], void *user)
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

	// The strategy name controls the late-frame release decision in every mode.
	// In particular, balanced is the high-refresh paced mode used by PLE-128: a
	// timeline shift must not turn into a releaseOutputBuffer(render=false) drop.
	for(int mode = 0; mode <= 3; mode++)
	{
		munit_assert_int(android_chiaki_video_recovery_action(
				ANDROID_CHIAKI_VIDEO_RECOVERY_TIMELINE_SHIFT, mode), ==,
				ANDROID_CHIAKI_VIDEO_RECOVERY_ACTION_SHIFT_TIMELINE);
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

static MunitResult test_period_seed_and_update(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	const int64_t period_120_hz = 8333333;
	const int64_t period_60_hz = 16666667;

	// The legacy high-refresh gate remains the default. Its explicit override
	// affects 120 Hz, while both setting values remain eligible at 60 Hz.
	munit_assert_true(android_chiaki_video_presenter_timestamped_release_eligible(
			2, 60.0, 60, false));
	munit_assert_true(android_chiaki_video_presenter_timestamped_release_eligible(
			2, 60.0, 60, true));
	munit_assert_false(android_chiaki_video_presenter_timestamped_release_eligible(
			2, 120.0, 60, false));
	munit_assert_true(android_chiaki_video_presenter_timestamped_release_eligible(
			2, 120.0, 60, true));

	// The active panel mode wins over both an observed callback and the stream rate.
	munit_assert_int64(android_chiaki_video_presenter_seed_period(
			120.0, period_60_hz, 60), ==, period_120_hz);
	// Without panel timing, Choreographer wins; without either, use the stream.
	munit_assert_int64(android_chiaki_video_presenter_seed_period(
			0.0, period_120_hz, 60), ==, period_120_hz);
	munit_assert_int64(android_chiaki_video_presenter_seed_period(
			0.0, 0, 60), ==, period_60_hz);

	// Exact 2x and 1/2x observations re-lock immediately in both directions.
	munit_assert_int64(android_chiaki_video_presenter_update_period(
			period_60_hz, period_120_hz), ==, period_120_hz);
	munit_assert_int64(android_chiaki_video_presenter_update_period(
			period_120_hz, period_60_hz), ==, period_60_hz);
	munit_assert_int64(android_chiaki_video_presenter_update_period(
			period_60_hz, 17000000), ==, 16708333);
	munit_assert_int64(android_chiaki_video_presenter_update_period(
			period_120_hz, 25000000), ==, period_120_hz);

	// A match-stream panel transition after presenter startup reseeds at 60 Hz;
	// the next Choreographer observation remains locked to that 16.67 ms grid.
	int64_t running_period_ns = android_chiaki_video_presenter_seed_period(
			120.0, 0, 60);
	munit_assert_int64(running_period_ns, ==, period_120_hz);
	running_period_ns = android_chiaki_video_presenter_seed_period(60.0, 0, 60);
	munit_assert_int64(running_period_ns, ==, period_60_hz);
	running_period_ns = android_chiaki_video_presenter_update_period(
			running_period_ns, period_60_hz);
	munit_assert_int64(running_period_ns, ==, period_60_hz);
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
	{
		"/recovery_action",
		test_recovery_release_decision,
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
	{
		"/period_seed_and_update",
		test_period_seed_and_update,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL,
	},
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
};
