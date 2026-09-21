// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include <chiaki/videoreceiver.h>
#include <chiaki/frameprocessor.h>
#include <chiaki/packetstats.h>

#include "test_log.h"

// PLE-474: what the video receiver adds to frames_lost_total, driven through the same
// calls it makes: frame_arrived when a frame's first unit arrives, frames_failed when a
// frame that did arrive cannot be used.

static MunitResult test_steady_stream_loses_nothing(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiFrameLossTracker tracker;
	chiaki_frame_loss_tracker_init(&tracker);
	// Two minutes at 60 fps, across the 16-bit wrap.
	uint32_t lost = 0;
	for(uint32_t i = 0; i < 7200; i++)
		lost += chiaki_frame_loss_tracker_frame_arrived(&tracker, (ChiakiSeqNum16)(62000 + i));
	munit_assert_uint32(lost, ==, 0);
	return MUNIT_OK;
}

static MunitResult test_first_frame_owes_nothing(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiFrameLossTracker tracker;
	chiaki_frame_loss_tracker_init(&tracker);
	// Nothing before the first frame of a stream was due to this client.
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 1), ==, 0);
	chiaki_frame_loss_tracker_init(&tracker);
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 5000), ==, 0);
	return MUNIT_OK;
}

static MunitResult test_fully_lost_frames_are_counted(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiFrameLossTracker tracker;
	chiaki_frame_loss_tracker_init(&tracker);
	for(ChiakiSeqNum16 i = 10000; i <= 10252; i++)
		munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, i), ==, 0);
	// ple404b's first roam-1200ms blackout: nothing of 10253..10326 arrived.
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 10327), ==, 74);
	// A single fully-lost frame.
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 10329), ==, 1);
	// Across the wrap.
	chiaki_frame_loss_tracker_init(&tracker);
	chiaki_frame_loss_tracker_frame_arrived(&tracker, 65530);
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 4), ==, 9);
	return MUNIT_OK;
}

static MunitResult test_undecodable_frames_after_a_gap_are_counted_once(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiFrameLossTracker tracker;
	chiaki_frame_loss_tracker_init(&tracker);
	chiaki_frame_loss_tracker_frame_arrived(&tracker, 10252);
	// The ple404b sequence: after the gap every arriving P-frame misses its reference.
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 10327), ==, 74);
	munit_assert_uint32(chiaki_frame_loss_tracker_frames_failed(&tracker, 10327, 10327), ==, 1);
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 10328), ==, 0);
	munit_assert_uint32(chiaki_frame_loss_tracker_frames_failed(&tracker, 10328, 10328), ==, 1);
	// Then FEC fails on 10329. The receiver reports the span from its last complete
	// frame, 10252, which covers every frame already counted above.
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 10329), ==, 0);
	munit_assert_uint32(chiaki_frame_loss_tracker_frames_failed(&tracker, 10253, 10329), ==, 1);
	// Reporting the same frame twice adds nothing.
	munit_assert_uint32(chiaki_frame_loss_tracker_frames_failed(&tracker, 10329, 10329), ==, 0);
	return MUNIT_OK;
}

static MunitResult test_back_to_back_fec_failures_count_each_frame_once(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiFrameLossTracker tracker;
	chiaki_frame_loss_tracker_init(&tracker);
	chiaki_frame_loss_tracker_frame_arrived(&tracker, 9);
	uint32_t total = 0;
	// Frames 10, 11 and 12 all partly arrive and all fail FEC; the last complete frame
	// stays 9, so the receiver's span grows 10..10, 10..11, 10..12. Before PLE-474 the
	// total took the whole span every time (1 + 2 + 3).
	for(ChiakiSeqNum16 f = 10; f <= 12; f++)
	{
		total += chiaki_frame_loss_tracker_frame_arrived(&tracker, f);
		total += chiaki_frame_loss_tracker_frames_failed(&tracker, 10, f);
	}
	munit_assert_uint32(total, ==, 3);
	return MUNIT_OK;
}

static MunitResult test_a_long_clean_run_does_not_age_out(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiFrameLossTracker tracker;
	chiaki_frame_loss_tracker_init(&tracker);
	chiaki_frame_loss_tracker_frame_arrived(&tracker, 100);
	munit_assert_uint32(chiaki_frame_loss_tracker_frames_failed(&tracker, 100, 100), ==, 1);
	// More than half the 16-bit space without a loss: a stale "counted through" would
	// read as ahead of the failing frame, or claim tens of thousands of frames.
	for(uint32_t i = 101; i <= 100 + 40000; i++)
		munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, (ChiakiSeqNum16)i), ==, 0);
	ChiakiSeqNum16 cur = (ChiakiSeqNum16)(100 + 40000);
	munit_assert_uint32(chiaki_frame_loss_tracker_frames_failed(&tracker, cur, cur), ==, 1);
	return MUNIT_OK;
}

static void put_unit(ChiakiFrameProcessor *fp, uint16_t unit_index)
{
	uint8_t data[64] = { 0 };
	ChiakiTakionAVPacket packet = { 0 };
	packet.frame_index = 20;
	packet.is_video = false;
	packet.unit_index = unit_index;
	packet.units_in_frame_total = 4;
	packet.units_in_frame_fec = 1;
	packet.data = data;
	packet.data_size = sizeof(data);
	munit_assert_int(chiaki_frame_processor_put_unit(fp, &packet), ==, CHIAKI_ERR_SUCCESS);
}

static MunitResult test_partly_lost_frame_counts_units_and_one_frame(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiFrameProcessor fp;
	chiaki_frame_processor_init(&fp, get_test_log());
	ChiakiPacketStats stats;
	munit_assert_int(chiaki_packet_stats_init(&stats), ==, CHIAKI_ERR_SUCCESS);
	ChiakiFrameLossTracker tracker;
	chiaki_frame_loss_tracker_init(&tracker);
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 19), ==, 0);

	// Frame 20: 3 source + 1 FEC units, of which only source units 0 and 2 arrive.
	uint8_t data[64] = { 0 };
	ChiakiTakionAVPacket first = { 0 };
	first.frame_index = 20;
	first.unit_index = 0;
	first.units_in_frame_total = 4;
	first.units_in_frame_fec = 1;
	first.data = data;
	first.data_size = sizeof(data);
	munit_assert_int(chiaki_frame_processor_alloc_frame(&fp, &first), ==, CHIAKI_ERR_SUCCESS);
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 20), ==, 0);
	put_unit(&fp, 0);
	put_unit(&fp, 2);

	uint8_t *frame;
	size_t frame_size;
	munit_assert_int(chiaki_frame_processor_flush(&fp, &frame, &frame_size), ==, CHIAKI_FRAME_PROCESSOR_FLUSH_RESULT_FEC_FAILED);
	munit_assert_uint32(chiaki_frame_loss_tracker_frames_failed(&tracker, 20, 20), ==, 1);

	// The unit counter sees the two missing units of the frame that partly arrived.
	chiaki_frame_processor_report_packet_stats(&fp, &stats);
	uint64_t received, lost;
	chiaki_packet_stats_get_generation_totals(&stats, &received, &lost);
	munit_assert_uint64(received, ==, 2);
	munit_assert_uint64(lost, ==, 2);

	// Frames 21..29 deliver nothing and frame 30 arrives: the frame count takes all
	// nine, the unit counter nothing -- it never learns how many units they had.
	munit_assert_uint32(chiaki_frame_loss_tracker_frame_arrived(&tracker, 30), ==, 9);
	chiaki_packet_stats_get_generation_totals(&stats, &received, &lost);
	munit_assert_uint64(lost, ==, 2);

	chiaki_packet_stats_fini(&stats);
	chiaki_frame_processor_fini(&fp);
	return MUNIT_OK;
}

MunitTest tests_frame_loss[] = {
	{
		"/steady_stream_loses_nothing",
		test_steady_stream_loses_nothing,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/first_frame_owes_nothing",
		test_first_frame_owes_nothing,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/fully_lost_frames_are_counted",
		test_fully_lost_frames_are_counted,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/undecodable_frames_after_a_gap_are_counted_once",
		test_undecodable_frames_after_a_gap_are_counted_once,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/back_to_back_fec_failures_count_each_frame_once",
		test_back_to_back_fec_failures_count_each_frame_once,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/a_long_clean_run_does_not_age_out",
		test_a_long_clean_run_does_not_age_out,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/partly_lost_frame_counts_units_and_one_frame",
		test_partly_lost_frame_counts_units_and_one_frame,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL }
};
