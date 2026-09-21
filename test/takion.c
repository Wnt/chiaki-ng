// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include <chiaki/takion.h>
#include <chiaki/seqnum.h>
#include <chiaki/base64.h>

#define CHIAKI_UNIT_TEST
#include "../lib/src/takionsendbuffer.c"

#include "test_log.h"

#include "fake_console.h"

#if defined(__linux__)
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

MunitResult test_network_stats_all(void);


static MunitResult test_av_packet_parse(const MunitParameter params[], void *user)
{
	uint8_t packet[] = {
			0x2, 0x0, 0x2d, 0x0, 0x5, 0x0, 0xc0, 0x1c, 0x1, 0x3, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0,
			0xe4, 0x10, 0x3, 0x67, 0x0, 0x29, 0xf3, 0x2f, 0x98, 0xf6, 0x99, 0x82, 0x83, 0x78, 0xdb, 0x29,
			0x43, 0xa9, 0xe5, 0x88, 0xf2, 0x11, 0x4, 0x20, 0xe6, 0x20, 0x96, 0xe9, 0x6, 0xee, 0xd, 0x27,
			0xa1, 0x83, 0x82, 0x88, 0xe6, 0x21, 0x49, 0x2, 0x75, 0x74, 0x32, 0x5b, 0xf6, 0xe9, 0xdc, 0x93,
			0xea, 0x31, 0x88, 0xd, 0x2b, 0x4b, 0x34, 0xf9, 0xec, 0x1b, 0x26, 0xcc, 0xbb, 0xbb, 0x81, 0xf2,
			0xd9, 0x2d, 0x8e, 0xa1, 0xb9, 0xe2, 0xb3, 0xca, 0xb2, 0x7d, 0xa3, 0x31, 0xf0, 0x42, 0xb7, 0xb6,
			0x1e, 0x8f, 0x6d, 0xa2, 0x70, 0x46, 0xfd, 0x7e, 0x9b, 0x60, 0x85, 0xb0, 0xed, 0x4f, 0x20, 0xb5,
			0x1, 0x71, 0xa9, 0xaa, 0x18, 0x6b, 0x2a, 0x90, 0xf3, 0xa7, 0x84, 0x36, 0xfd, 0x6d, 0x14, 0x83,
			0x68, 0xa3, 0x9b, 0x3a, 0xc8, 0xd4, 0x3a, 0x31, 0xa0, 0x9b, 0x61, 0xde, 0xa7, 0xed, 0x46, 0xb4,
			0xa3, 0xdf, 0x3f, 0x44, 0x8f, 0xad, 0x64, 0x9, 0xfc, 0x7a, 0xe7, 0x24, 0xf0, 0xd2, 0x42, 0xd3,
			0x57, 0x5a, 0x76, 0x0, 0xc5, 0xe0, 0x93, 0xa9, 0xf5, 0x32, 0x5d, 0xee, 0xf7, 0x9d
	};

	ChiakiKeyState key_state;
	chiaki_key_state_init(&key_state);

	ChiakiTakionAVPacket av_packet;

	ChiakiErrorCode err = chiaki_takion_v9_av_packet_parse(&av_packet, &key_state, packet, sizeof(packet));
	munit_assert_int(err, ==, CHIAKI_ERR_SUCCESS);

	munit_assert(av_packet.is_video);
	munit_assert_uint16(av_packet.packet_index, ==, 45);
	munit_assert_uint16(av_packet.frame_index, ==, 5);
	// TODO: uses_nalu_info_structs
	munit_assert_uint16(av_packet.unit_index, ==, 6);
	munit_assert_uint16(av_packet.units_in_frame_total, ==, 8);
	munit_assert_uint16(av_packet.units_in_frame_fec, ==, 1);
	munit_assert_uint32(av_packet.codec, ==, 3);
//	munit_assert_uint16(av_packet.word_at_0x18, ==, 871);
	munit_assert_uint8(av_packet.adaptive_stream_index, ==, 0);
//	munit_assert_uint8(av_packet.byte_at_0x2c, ==, 0);

	munit_assert_ptr_equal(av_packet.data, packet + 0x15);
	munit_assert_size(av_packet.data_size, ==, 0x99);

	return MUNIT_OK;
}


static MunitResult test_av_packet_parse_real_video(const MunitParameter params[], void *user)
{
#include "takion_av_packet_parse_real_video.inl"
	return MUNIT_OK;
}

static void random_seqnums(ChiakiSeqNum32 *nums, size_t count)
{
	for(size_t i=0; i<count; i++)
	{
		ChiakiSeqNum32 seqnum;
		retry:
		seqnum = munit_rand_uint32();
		for(size_t j=0; j<i; j++)
		{
			if(nums[j] == seqnum)
				goto retry;
		}
		nums[i] = seqnum;
	}
}

static bool check_send_buffer_contents(ChiakiTakionSendBuffer *send_buffer, const ChiakiSeqNum32 *nums_expected, size_t nums_expected_count)
{
	// nums_expected must be unique

	if(chiaki_mutex_lock(&send_buffer->mutex) != CHIAKI_ERR_SUCCESS)
		return false;

	if(send_buffer->packets_count != nums_expected_count)
		goto fail;

	for(size_t i=0; i<nums_expected_count; i++)
	{
		bool found = false;
		for(size_t j=0; j<send_buffer->packets_count; j++)
		{
			if(send_buffer->packets[j].seq_num == nums_expected[i])
			{
				found = true;
				break;
			}
		}
		if(!found)
			goto fail;
	}

	chiaki_mutex_unlock(&send_buffer->mutex);
	return true;
fail:
	chiaki_mutex_unlock(&send_buffer->mutex);
	return false;
}

static void seqnums_ack(ChiakiSeqNum32 *nums, size_t *nums_count, ChiakiSeqNum32 ack_num)
{
	// simulate ack of ack_num
	for(size_t i=0; i<*nums_count; i++)
	{
		if(nums[i] == ack_num || chiaki_seq_num_32_lt(nums[i], ack_num))
		{
			for(size_t j=i+1; j<*nums_count; j++)
				nums[j-1] = nums[j];
			(*nums_count)--;
			i--;
		}
	}
}

static MunitResult test_takion_send_buffer(const MunitParameter params[], void *user)
{
#define nums_count 0x30
	ChiakiTakionSendBuffer send_buffer;
	ChiakiErrorCode err = chiaki_takion_send_buffer_init(&send_buffer, NULL, nums_count);
	munit_assert_int(err, ==, CHIAKI_ERR_SUCCESS);
	send_buffer.log = get_test_log();

	ChiakiSeqNum32 nums_expected[nums_count + 1];
	random_seqnums(nums_expected, nums_count + 1);

	for(size_t i=0; i<nums_count; i++)
	{
		err = chiaki_takion_send_buffer_push(&send_buffer, nums_expected[i], malloc(8), 8);
		munit_assert_int(err, ==, CHIAKI_ERR_SUCCESS);
	}

	err = chiaki_takion_send_buffer_push(&send_buffer, nums_expected[nums_count], malloc(8), 8);
	munit_assert_int(err, ==, CHIAKI_ERR_OVERFLOW);

	size_t nums_count_cur = nums_count;
	while(nums_count_cur > 0)
	{
		ChiakiSeqNum32 ack_num = nums_expected[nums_count_cur - 1]
				+ munit_rand_int_range(-1, 1) * munit_rand_int_range(1, 32);
		chiaki_takion_send_buffer_ack(&send_buffer, ack_num, NULL, NULL); // TODO: test acked seqnums params
		seqnums_ack(nums_expected, &nums_count_cur, ack_num);
		bool correct = check_send_buffer_contents(&send_buffer, nums_expected, nums_count_cur);
		munit_assert(correct);
	}

	chiaki_takion_send_buffer_fini(&send_buffer);
	return MUNIT_OK;
#undef nums_count
}

static MunitResult test_takion_video_packet_jitter(const MunitParameter params[], void *user);

static MunitResult test_takion_format_congestion(const MunitParameter params[], void *user)
{
	MunitResult jitter_result = test_takion_video_packet_jitter(params, user);
	if(jitter_result != MUNIT_OK)
		return jitter_result;
	static const uint8_t handshake_key[] = { 0x54, 0x65, 0x4c, 0x34, 0x5c, 0xac, 0x56, 0xb8, 0xea, 0xe6, 0x15, 0x2a, 0xde, 0x1c, 0xe2, 0xe8 };
	static const uint8_t ecdh_secret[] = { 0x00, 0x34, 0xf8, 0x21, 0xc7, 0xd9, 0xde, 0xa9, 0xe9, 0x11, 0xca, 0x5a, 0xd6, 0x7d, 0x11, 0xce, 0x4f, 0x02, 0xb1, 0xce, 0x1e, 0xe7, 0xc3, 0x8d, 0x54, 0x39, 0xfa, 0x64, 0xe3, 0xdb, 0xd8, 0x0d };

	ChiakiGKCrypt gkcrypt;
	ChiakiErrorCode err = chiaki_gkcrypt_init(&gkcrypt, NULL, 0, 2, handshake_key, ecdh_secret);
	if(err != CHIAKI_ERR_SUCCESS)
		return MUNIT_ERROR;

	ChiakiTakionCongestionPacket packet;
	packet.word_0 = 0x42;
	packet.received = 26;
	packet.lost = 10;

	const uint64_t key_pos = 0x1e5;

	uint8_t buf[CHIAKI_TAKION_CONGESTION_PACKET_SIZE];
	chiaki_takion_format_congestion(buf, &packet, key_pos);

	static const uint8_t buf_expected[] = { 0x05, 0x00, 0x42, 0x00, 0x1a, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0xe5 };
	munit_assert_memory_equal(sizeof(buf), buf, buf_expected);

	err = chiaki_takion_packet_mac(&gkcrypt, buf, sizeof(buf), key_pos, NULL, NULL);
	if(err != CHIAKI_ERR_SUCCESS)
		return MUNIT_ERROR;

	static const uint8_t buf_expected_mac[] = { 0x05, 0x00, 0x42, 0x00, 0x1a, 0x00, 0x0a, 0x64, 0x8a, 0x7c, 0x74, 0x00, 0x00, 0x01, 0xe5 };
	munit_assert_memory_equal(sizeof(buf), buf, buf_expected_mac);

	chiaki_gkcrypt_fini(&gkcrypt);
	munit_assert_int(test_network_stats_all(), ==, MUNIT_OK);

	return MUNIT_OK;
}

static MunitResult test_takion_video_packet_jitter(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	const uint64_t frame_us = 1000000 / 60;

	{
		// Stable 60 fps, one packet per frame, including the frame-index wrap.
		ChiakiTakionVideoPacketJitter jitter = { 0 };
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000, 65534, 60);
		chiaki_takion_video_packet_jitter_push(&jitter, 1016666, 65535, 60);
		chiaki_takion_video_packet_jitter_push(&jitter, 1033332, 0, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 0);

		// A 16 ms delay spike and its recovery each contribute |D| = 16000 us at gain 1/16.
		chiaki_takion_video_packet_jitter_push(&jitter, 1065998, 1, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 1000);
		chiaki_takion_video_packet_jitter_push(&jitter, 1066664, 2, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 1938);
	}

	{
		// PLE-356: further packets of a frame carry no new sender-side time, so they must
		// not move the estimate. Before the fix each of these dropped it -- 1938 -> 1879 --
		// and ~9 of every 10 real packets are of this kind, which is why the field read
		// 2.9 ms on a path whose delay variation was 32 ms.
		ChiakiTakionVideoPacketJitter jitter = { 0 };
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000, 0, 60);
		uint64_t late = 1000000 + frame_us + 16000;  // 16 ms late
		chiaki_takion_video_packet_jitter_push(&jitter, late, 1, 60);
		uint64_t after_spike = chiaki_takion_video_packet_jitter_get(&jitter);
		munit_assert_uint64(after_spike, ==, 1000);
		for(int i = 1; i <= 10; i++)
			chiaki_takion_video_packet_jitter_push(&jitter, late + i * 100, 1, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, after_spike);
		// The superseded per-packet EWMA is exactly what those ten packets dilute.
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get_raw(&jitter), <, after_spike);
	}

	{
		// A burst arriving intact but late: ten packets of the frame land together, and the
		// frame's leading edge is what is priced, once, at its full size.
		ChiakiTakionVideoPacketJitter a = { 0 };
		ChiakiTakionVideoPacketJitter b = { 0 };
		chiaki_takion_video_packet_jitter_push(&a, 1000000, 0, 60);
		chiaki_takion_video_packet_jitter_push(&b, 1000000, 0, 60);
		for(int i = 0; i < 10; i++)
			chiaki_takion_video_packet_jitter_push(&b, 1000000 + i * 50, 0, 60);
		chiaki_takion_video_packet_jitter_push(&a, 1000000 + frame_us + 20000, 1, 60);
		chiaki_takion_video_packet_jitter_push(&b, 1000000 + frame_us + 20000, 1, 60);
		// Same answer whether the frame came as one packet or as eleven: 20000/16 = 1250.
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&a), ==, 1250);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&b), ==,
			chiaki_takion_video_packet_jitter_get(&a));
	}

	{
		// Lost frames are priced against the nominal cadence, so three missing frames
		// arriving on time are not jitter; a reordered older frame is ignored outright.
		ChiakiTakionVideoPacketJitter jitter = { 0 };
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000, 10, 60);
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000 + 4 * frame_us, 14, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 0);
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000 + 5 * frame_us, 12, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 0);
		// ... and the state still tracks frame 14, so the next frame is priced from it.
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000 + 5 * frame_us + 16000, 15, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 1000);
	}

	{
		// A blip-200ms stall is twelve frames at 60 fps: it is real delay variation and must
		// register. Only a gap too large to attribute resyncs silently.
		ChiakiTakionVideoPacketJitter jitter = { 0 };
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000, 0, 60);
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000 + frame_us + 200000, 1, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 12500);

		ChiakiTakionVideoPacketJitter discontinuity = { 0 };
		chiaki_takion_video_packet_jitter_push(&discontinuity, 1000000, 0, 60);
		chiaki_takion_video_packet_jitter_push(&discontinuity, 1000000 + frame_us,
			CHIAKI_TAKION_VIDEO_JITTER_MAX_FRAME_DELTA + 1, 60);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&discontinuity), ==, 0);
	}

	{
		// The whole defect, in the shape the ticket measured it in: every frame alternately
		// 10 ms early and 10 ms late, so |D| is 10 ms at every frame boundary, delivered as
		// ten packets per frame. The fix converges on the real 10 ms; the per-packet form
		// lands near a tenth of it, because the divisor is the packets per frame -- a
		// function of bitrate, so no constant scale factor could correct it.
		ChiakiTakionVideoPacketJitter jitter = { 0 };
		for(int frame = 0; frame < 400; frame++)
		{
			uint64_t arrival = 1000000 + frame * frame_us + ((frame % 2) ? 10000 : 0);
			for(int pkt = 0; pkt < 10; pkt++)
				chiaki_takion_video_packet_jitter_push(&jitter, arrival + pkt * 60,
					(ChiakiSeqNum16)frame, 60);
		}
		uint64_t frame_jitter = chiaki_takion_video_packet_jitter_get(&jitter);
		uint64_t raw_jitter = chiaki_takion_video_packet_jitter_get_raw(&jitter);
		munit_assert_uint64(frame_jitter, >, 9500);
		munit_assert_uint64(frame_jitter, <, 10500);
		munit_assert_uint64(raw_jitter, >, 0);
		munit_assert_uint64(raw_jitter, <, frame_jitter / 4);
	}

	{
		// A zero fps -- no video profile yet -- must not divide by zero or record anything.
		ChiakiTakionVideoPacketJitter jitter = { 0 };
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000, 0, 0);
		chiaki_takion_video_packet_jitter_push(&jitter, 1100000, 1, 0);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 0);
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get_raw(&jitter), ==, 0);
	}

	{
		// PLE-403: the very first frame-boundary sample lands in jitter_us_q4 unsmoothed --
		// gain*(0+8)>>4 is zero the first time -- so a one-off startup delay reads back as if
		// it were the steady-state estimate. "Filled" must stay false through that sample and
		// only become true once CHIAKI_TAKION_VIDEO_JITTER_FILL_SAMPLES have smoothed it.
		ChiakiTakionVideoPacketJitter jitter = { 0 };
		chiaki_takion_video_packet_jitter_push(&jitter, 1000000, 0, 60);
		munit_assert_false(chiaki_takion_video_packet_jitter_filled(&jitter));
		// The frame-index baseline packet contributes no smoothing sample.
		uint64_t late = 1000000 + frame_us + 100000; // 100 ms startup delay
		chiaki_takion_video_packet_jitter_push(&jitter, late, 1, 60);
		// One EWMA update from a zero accumulator: 100000 us divided by the gain's shift,
		// (100000+8)>>4 == 6250 -- an estimate built from a single sample, not yet a filter.
		munit_assert_uint64(chiaki_takion_video_packet_jitter_get(&jitter), ==, 6250);
		munit_assert_false(chiaki_takion_video_packet_jitter_filled(&jitter));
		// Nominal cadence from here: each further push contributes one smoothing sample and no
		// new delay variation, so only frame_sample_count, not the jitter value, is under test.
		for(uint32_t i = 0; i < CHIAKI_TAKION_VIDEO_JITTER_FILL_SAMPLES - 1; i++)
		{
			munit_assert_false(chiaki_takion_video_packet_jitter_filled(&jitter));
			chiaki_takion_video_packet_jitter_push(&jitter, late + (i + 1) * frame_us,
				(ChiakiSeqNum16)(i + 2), 60);
		}
		munit_assert_true(chiaki_takion_video_packet_jitter_filled(&jitter));
		// Filled never regresses back to unfilled as further samples keep arriving.
		chiaki_takion_video_packet_jitter_push(&jitter,
			late + CHIAKI_TAKION_VIDEO_JITTER_FILL_SAMPLES * frame_us,
			(ChiakiSeqNum16)(CHIAKI_TAKION_VIDEO_JITTER_FILL_SAMPLES + 1), 60);
		munit_assert_true(chiaki_takion_video_packet_jitter_filled(&jitter));
	}

	return MUNIT_OK;
}

// PLE-464: the quality classifier's only view of a total outage is the gap between two
// inbound datagrams, so the fold that maintains it -- including its clock-race guard --
// is worth pinning on the host, where no socket is needed.
static MunitResult test_takion_receive_gap_fold(const MunitParameter params[], void *user)
{
	// Nothing received yet: there is no interval to measure, whatever "now" says.
	munit_assert_uint32(chiaki_takion_receive_gap_fold(0, 0, 5000), ==, 0);
	munit_assert_uint32(chiaki_takion_receive_gap_fold(1200, 0, 5000), ==, 1200);

	// A plain gap, and one that does not beat the running maximum.
	munit_assert_uint32(chiaki_takion_receive_gap_fold(0, 1000, 1016), ==, 16);
	munit_assert_uint32(chiaki_takion_receive_gap_fold(3000, 1000, 1016), ==, 3000);
	munit_assert_uint32(chiaki_takion_receive_gap_fold(16, 1000, 4000), ==, 3000);

	// The two stamps are read on different threads, so the newer one can read older.
	// That is the clock, not a 25-day silence: the running maximum must not move.
	munit_assert_uint32(chiaki_takion_receive_gap_fold(16, 1000, 990), ==, 16);
	munit_assert_uint32(chiaki_takion_receive_gap_fold(0, 1000, 990), ==, 0);

	// The 32-bit wrap is unsigned arithmetic and stays a small gap across it.
	munit_assert_uint32(chiaki_takion_receive_gap_fold(0, UINT32_MAX - 100, 100), ==, 201);

	return MUNIT_OK;
}

// The window accumulator the stats poll drains: each window reports its own worst gap
// exactly once, so a closed outage is still seen and is not re-reported forever after.
static MunitResult test_takion_window_max_receive_gap(const MunitParameter params[], void *user)
{
	ChiakiTakion takion;
	memset(&takion, 0, sizeof(takion));

	munit_assert_uint32(chiaki_takion_take_window_max_receive_gap_ms(&takion), ==, 0);

	takion.window_max_receive_gap_ms = chiaki_takion_receive_gap_fold(
		takion.window_max_receive_gap_ms, 1000, 1016);
	takion.window_max_receive_gap_ms = chiaki_takion_receive_gap_fold(
		takion.window_max_receive_gap_ms, 1016, 4016);
	takion.window_max_receive_gap_ms = chiaki_takion_receive_gap_fold(
		takion.window_max_receive_gap_ms, 4016, 4032);

	munit_assert_uint32(chiaki_takion_take_window_max_receive_gap_ms(&takion), ==, 3000);
	// Drained: the next window starts clean rather than inheriting the outage.
	munit_assert_uint32(chiaki_takion_take_window_max_receive_gap_ms(&takion), ==, 0);

	takion.window_max_receive_gap_ms = chiaki_takion_receive_gap_fold(
		takion.window_max_receive_gap_ms, 4032, 4048);
	munit_assert_uint32(chiaki_takion_take_window_max_receive_gap_ms(&takion), ==, 16);

	return MUNIT_OK;
}

// PLE-476: PLE-464 argued this race rather than measuring it -- take() (the stats
// thread) reads then plain-stores 0 over window_max_receive_gap_ms with no lock, and
// takion_note_receive() (the recv thread) does its own unlocked read-fold-store of the
// same field, so a gap that folds in between take()'s read and its reset is clobbered.
// Real threads would make this timing-dependent and flaky to assert on, but both sides
// are just field operations on a struct the test already has full access to (see the
// two tests above), so the exact interleaving the argument describes is reproducible
// by calling them in that order on one thread -- deterministic, and it is the specific
// claim being checked: that the race can only ever drop a sample it should have
// reported, never corrupt the accumulator into something wrong or leak across windows.
static MunitResult test_takion_window_max_receive_gap_reset_race(const MunitParameter params[], void *user)
{
	ChiakiTakion takion;
	memset(&takion, 0, sizeof(takion));

	// A receive earlier in the window already folded a 50ms gap in.
	takion.window_max_receive_gap_ms = chiaki_takion_receive_gap_fold(
		takion.window_max_receive_gap_ms, 1000, 1050);
	munit_assert_uint32(takion.window_max_receive_gap_ms, ==, 50);

	// take() reads the value for this window's stats event...
	uint32_t reported_this_window = takion.window_max_receive_gap_ms;
	munit_assert_uint32(reported_this_window, ==, 50);

	// ...and before it stores the reset, a packet arrives closing a much bigger gap,
	// folding it into the pre-reset value take() already read past.
	takion.window_max_receive_gap_ms = chiaki_takion_receive_gap_fold(
		takion.window_max_receive_gap_ms, 1050, 5050);
	munit_assert_uint32(takion.window_max_receive_gap_ms, ==, 4000);

	// take()'s reset lands last and clobbers that fresh 4000ms value: the drop.
	takion.window_max_receive_gap_ms = 0;
	munit_assert_uint32(chiaki_takion_take_window_max_receive_gap_ms(&takion), ==, 0);

	// Benign, not corrupting: the race only ever touches window_max_receive_gap_ms,
	// never last_receive_ms, so a still-open outage is not lost with it -- the next
	// receive folds against the true previous timestamp and reports the full gap.
	munit_assert_uint32(chiaki_takion_receive_gap_fold(0, 5050, 9050), ==, 4000);

	return MUNIT_OK;
}

#if defined(__linux__)
typedef struct takion_teardown_probe_t
{
	ChiakiMutex mutex;
	ChiakiCond cond;
	bool connected;
	bool disconnected;
} TakionTeardownProbe;

static void takion_teardown_probe_cb(ChiakiTakionEvent *event, void *user)
{
	TakionTeardownProbe *probe = user;
	chiaki_mutex_lock(&probe->mutex);
	if(event->type == CHIAKI_TAKION_EVENT_TYPE_CONNECTED)
		probe->connected = true;
	else if(event->type == CHIAKI_TAKION_EVENT_TYPE_DISCONNECT)
		probe->disconnected = true;
	chiaki_cond_signal(&probe->cond);
	chiaki_mutex_unlock(&probe->mutex);
}

static bool takion_teardown_probe_connected(void *user)
{
	return ((TakionTeardownProbe *)user)->connected;
}

static bool takion_teardown_probe_disconnected(void *user)
{
	return ((TakionTeardownProbe *)user)->disconnected;
}

// PLE-490: the send buffer is owned by the takion thread, which finalises it the
// moment its receive loop breaks -- on any recv error, not only on close. The data
// send API stays callable by the owner until chiaki_takion_close(), and used to push
// into that destroyed buffer (bionic: FORTIFY abort, "pthread_mutex_lock called on a
// destroyed mutex"). This drives a real takion into exactly that state: the console
// goes away, the ICMP port-unreachable surfaces as ECONNREFUSED on the takion thread's
// recv, the thread exits. DISCONNECT is emitted after the send buffer is finalised, so
// waiting for it orders the sends below after the teardown without any sleep.
// The socket is caller-owned (close_socket = false), as for the PSN data socket in the
// S25 capture; with a takion-owned socket the dying thread closes it and the send
// fails with EBADF before it ever reaches the buffer.
static MunitResult test_takion_send_after_receive_thread_exit(const MunitParameter params[], void *user)
{
	FakeConsole console;
	fake_console_open(&console);

	TakionTeardownProbe probe = { 0 };
	munit_assert_int(chiaki_mutex_init(&probe.mutex, false), ==, CHIAKI_ERR_SUCCESS);
	munit_assert_int(chiaki_cond_init(&probe.cond), ==, CHIAKI_ERR_SUCCESS);

	ChiakiTakionConnectInfo info = { 0 };
	info.log = get_test_log();
	info.close_socket = false;
	info.ip_dontfrag = false;
	info.enable_crypt = false;
	info.protocol_version = 7;
	info.cb = takion_teardown_probe_cb;
	info.cb_user = &probe;

	ChiakiTakion takion;
	memset(&takion, 0, sizeof(takion));
	chiaki_key_state_init(&takion.key_state);
	munit_assert_int(chiaki_takion_connect(&takion, &info, &console.client_sock), ==, CHIAKI_ERR_SUCCESS);

	fake_console_handshake(&console);

	chiaki_mutex_lock(&probe.mutex);
	chiaki_cond_timedwait_pred(&probe.cond, &probe.mutex, 5000, takion_teardown_probe_connected, &probe);
	munit_assert_true(probe.connected);
	chiaki_mutex_unlock(&probe.mutex);

	// A live takion accepts data.
	uint8_t payload[] = { 0xde, 0xad, 0xbe, 0xef };
	munit_assert_int(chiaki_takion_send_message_data(&takion, 1, 1, payload, sizeof(payload), NULL), ==, CHIAKI_ERR_SUCCESS);
	munit_assert_int(fake_console_recv(&console), >, 0);

	// The console goes away. The next datagram draws an ICMP port unreachable, which
	// the takion thread's recv reports as ECONNREFUSED; its loop breaks and it tears
	// down the send buffer.
	fake_console_close(&console);
	munit_assert_int(chiaki_takion_send_raw(&takion, payload, sizeof(payload)), ==, CHIAKI_ERR_SUCCESS);

	chiaki_mutex_lock(&probe.mutex);
	chiaki_cond_timedwait_pred(&probe.cond, &probe.mutex, 5000, takion_teardown_probe_disconnected, &probe);
	munit_assert_true(probe.disconnected);
	chiaki_mutex_unlock(&probe.mutex);

	// The takion thread has exited and the send buffer is gone; the takion handle is
	// still open. A data send must now fail cleanly instead of touching the buffer.
	munit_assert_int(chiaki_takion_send_message_data(&takion, 1, 1, payload, sizeof(payload), NULL), ==, CHIAKI_ERR_DISCONNECTED);
	munit_assert_int(chiaki_takion_send_message_data_cont(&takion, 1, 1, payload, sizeof(payload), NULL), ==, CHIAKI_ERR_DISCONNECTED);

	// Raw sends tell Senkusha why they failed. Nothing reads the socket any more, so
	// this datagram's ICMP stays pending; poll() waits for it and the next send
	// reports it.
	munit_assert_int(chiaki_takion_send_raw(&takion, payload, sizeof(payload)), ==, CHIAKI_ERR_SUCCESS);
	struct pollfd pfd = { .fd = console.client_sock, .events = 0 };
	munit_assert_int(poll(&pfd, 1, 5000), ==, 1);
	munit_assert_true(pfd.revents & POLLERR);
	munit_assert_int(chiaki_takion_send_raw(&takion, payload, sizeof(payload)), ==, CHIAKI_ERR_CONNECTION_REFUSED);
	// Larger than any UDP datagram can be: the local "does not fit" answer.
	static uint8_t too_big[70000];
	munit_assert_int(chiaki_takion_send_raw(&takion, too_big, sizeof(too_big)), ==, CHIAKI_ERR_OVERFLOW);

	chiaki_takion_close(&takion);
	fake_console_fini(&console);
	chiaki_cond_fini(&probe.cond);
	chiaki_mutex_fini(&probe.mutex);
	return MUNIT_OK;
}

// PLE-502: holds one send() on a chosen fd inside the call, after the sender has
// already read takion->sock -- the window no scheduler hands out on demand. The
// executable's send() wins over libc's for the statically linked chiaki-lib; every
// other send() passes straight through to sendto(), which is the same call.
typedef struct takion_send_hold_t
{
	ChiakiMutex mutex;
	ChiakiCond cond;
	int fd; // armed while >= 0; disarms itself on the first send it holds
	bool held;
	int witness_fd; // what "reuses" the fd number once the takion thread has closed it
	bool saw_close;
} TakionSendHold;

static TakionSendHold takion_send_hold = { .fd = -1, .witness_fd = -1 };

static void takion_send_hold_in_flight(int fd)
{
	chiaki_mutex_lock(&takion_send_hold.mutex);
	__atomic_store_n(&takion_send_hold.fd, -1, __ATOMIC_RELEASE);
	takion_send_hold.held = true;
	chiaki_cond_signal(&takion_send_hold.cond);
	chiaki_mutex_unlock(&takion_send_hold.mutex);

	// Give the takion thread every chance to close the fd while this send is in
	// flight. If the fd closes, reuse its number the way any other open() in the
	// process would; the send below then lands on the reused socket.
	for(int i = 0; i < 1000; i++)
	{
		if(fcntl(fd, F_GETFD) < 0 && errno == EBADF)
		{
			chiaki_mutex_lock(&takion_send_hold.mutex);
			takion_send_hold.saw_close = true;
			dup2(takion_send_hold.witness_fd, fd);
			chiaki_mutex_unlock(&takion_send_hold.mutex);
			return;
		}
		usleep(1000);
	}
}

ssize_t send(int fd, const void *buf, size_t len, int flags)
{
	if(fd >= 0 && __atomic_load_n(&takion_send_hold.fd, __ATOMIC_ACQUIRE) == fd)
		takion_send_hold_in_flight(fd);
	return sendto(fd, buf, len, flags, NULL, 0);
}

typedef struct takion_congestion_sender_t
{
	ChiakiTakion *takion;
	ChiakiErrorCode err;
} TakionCongestionSender;

static void *takion_congestion_sender_run(void *user)
{
	TakionCongestionSender *sender = user;
	ChiakiTakionCongestionPacket packet = { 0 };
	sender->err = chiaki_takion_send_congestion(sender->takion, &packet);
	return NULL;
}

static bool takion_send_hold_is_held(void *user)
{
	return ((TakionSendHold *)user)->held;
}

// PLE-502: with a takion-owned socket (close_socket = true, every LAN stream) the
// takion thread closes the fd as soon as its receive loop breaks, while the
// congestion, feedback and mic senders and Senkusha's raw probes keep sending until
// their owners stop them much later. A sender that has read takion->sock and is
// about to send() when the close happens sends on whatever reuses that number next.
// This holds a real congestion send inside send(), drives the takion thread through
// its ECONNREFUSED teardown (as PLE-490's test does), and lets a witness socket take
// the fd number the moment it is closed. The close must wait for the send, so the
// witness must see nothing; and every send after the teardown must be refused with
// CHIAKI_ERR_DISCONNECTED rather than fail on a stale descriptor.
static MunitResult test_takion_socket_close_waits_for_senders(const MunitParameter params[], void *user)
{
	FakeConsole console;
	fake_console_open(&console);
	int takion_fd = console.client_sock;
	console.client_sock = -1; // the takion owns it now
	int trigger_fd = dup(takion_fd);
	munit_assert_int(trigger_fd, >=, 0);

	// The unrelated socket that would inherit the fd number: connected to a
	// receiver, so anything sent on it is observable.
	int witness_rx = socket(AF_INET, SOCK_DGRAM, 0);
	munit_assert_int(witness_rx, >=, 0);
	struct sockaddr_in witness_addr = { 0 };
	witness_addr.sin_family = AF_INET;
	witness_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	munit_assert_int(bind(witness_rx, (struct sockaddr *)&witness_addr, sizeof(witness_addr)), ==, 0);
	socklen_t witness_addr_len = sizeof(witness_addr);
	munit_assert_int(getsockname(witness_rx, (struct sockaddr *)&witness_addr, &witness_addr_len), ==, 0);
	int witness_tx = socket(AF_INET, SOCK_DGRAM, 0);
	munit_assert_int(witness_tx, >=, 0);
	munit_assert_int(connect(witness_tx, (struct sockaddr *)&witness_addr, sizeof(witness_addr)), ==, 0);

	munit_assert_int(chiaki_mutex_init(&takion_send_hold.mutex, false), ==, CHIAKI_ERR_SUCCESS);
	munit_assert_int(chiaki_cond_init(&takion_send_hold.cond), ==, CHIAKI_ERR_SUCCESS);
	takion_send_hold.held = false;
	takion_send_hold.saw_close = false;
	takion_send_hold.witness_fd = witness_tx;

	TakionTeardownProbe probe = { 0 };
	munit_assert_int(chiaki_mutex_init(&probe.mutex, false), ==, CHIAKI_ERR_SUCCESS);
	munit_assert_int(chiaki_cond_init(&probe.cond), ==, CHIAKI_ERR_SUCCESS);

	ChiakiTakionConnectInfo info = { 0 };
	info.log = get_test_log();
	info.close_socket = true;
	info.ip_dontfrag = false;
	info.enable_crypt = false;
	info.protocol_version = 7;
	info.cb = takion_teardown_probe_cb;
	info.cb_user = &probe;

	ChiakiTakion takion;
	memset(&takion, 0, sizeof(takion));
	chiaki_key_state_init(&takion.key_state);
	chiaki_socket_t sock = takion_fd;
	munit_assert_int(chiaki_takion_connect(&takion, &info, &sock), ==, CHIAKI_ERR_SUCCESS);

	fake_console_handshake(&console);

	chiaki_mutex_lock(&probe.mutex);
	chiaki_cond_timedwait_pred(&probe.cond, &probe.mutex, 5000, takion_teardown_probe_connected, &probe);
	munit_assert_true(probe.connected);
	chiaki_mutex_unlock(&probe.mutex);

	// A congestion sender, as the congestion control thread runs one, enters send().
	__atomic_store_n(&takion_send_hold.fd, takion_fd, __ATOMIC_RELEASE);
	TakionCongestionSender sender = { .takion = &takion, .err = CHIAKI_ERR_UNKNOWN };
	ChiakiThread sender_thread;
	munit_assert_int(chiaki_thread_create(&sender_thread, takion_congestion_sender_run, &sender), ==, CHIAKI_ERR_SUCCESS);
	chiaki_mutex_lock(&takion_send_hold.mutex);
	chiaki_cond_timedwait_pred(&takion_send_hold.cond, &takion_send_hold.mutex, 5000, takion_send_hold_is_held, &takion_send_hold);
	munit_assert_true(takion_send_hold.held);
	chiaki_mutex_unlock(&takion_send_hold.mutex);

	// While it is in flight the console goes away. A datagram sent on the socket
	// directly (not through takion, so not ordered against the held send) draws the
	// ICMP port unreachable that ends the takion thread's receive loop. It goes out
	// on a dup of the fd, taken before the takion existed: same socket, but the test
	// itself never touches the number the takion thread closes.
	fake_console_close(&console);
	uint8_t trigger[] = { 0xde, 0xad, 0xbe, 0xef };
	munit_assert_int((int)sendto(trigger_fd, trigger, sizeof(trigger), 0, NULL, 0), ==, (int)sizeof(trigger));

	chiaki_thread_join(&sender_thread, NULL);

	chiaki_mutex_lock(&probe.mutex);
	chiaki_cond_timedwait_pred(&probe.cond, &probe.mutex, 5000, takion_teardown_probe_disconnected, &probe);
	munit_assert_true(probe.disconnected);
	chiaki_mutex_unlock(&probe.mutex);

	// The in-flight send went out on the takion's own socket, not on a reused fd.
	uint8_t witness_buf[1500];
	ssize_t witnessed = recv(witness_rx, witness_buf, sizeof(witness_buf), MSG_DONTWAIT);
	int witnessed_errno = errno;
	if(takion_send_hold.saw_close)
		close(takion_fd); // our dup of the witness, not the takion's socket
	munit_assert_int((int)witnessed, ==, -1);
	munit_assert_int(witnessed_errno, ==, EAGAIN);
	munit_assert_false(takion_send_hold.saw_close);
	munit_assert_int(sender.err, ==, CHIAKI_ERR_SUCCESS);

	// The socket is gone: every sender gets a clean DISCONNECTED. (Feedback and mic
	// packets reach the socket through the same chiaki_takion_send_raw(), but encrypt
	// unconditionally first, and this takion has no crypt.)
	ChiakiTakionCongestionPacket congestion = { 0 };
	munit_assert_int(chiaki_takion_send_congestion(&takion, &congestion), ==, CHIAKI_ERR_DISCONNECTED);
	munit_assert_int(chiaki_takion_send_raw(&takion, trigger, sizeof(trigger)), ==, CHIAKI_ERR_DISCONNECTED);
	uint8_t payload[] = { 0xde, 0xad, 0xbe, 0xef };
	munit_assert_int(chiaki_takion_send_message_data(&takion, 1, 1, payload, sizeof(payload), NULL), ==, CHIAKI_ERR_DISCONNECTED);

	chiaki_takion_close(&takion);
	fake_console_fini(&console);
	close(trigger_fd);
	close(witness_tx);
	close(witness_rx);
	chiaki_cond_fini(&probe.cond);
	chiaki_mutex_fini(&probe.mutex);
	chiaki_cond_fini(&takion_send_hold.cond);
	chiaki_mutex_fini(&takion_send_hold.mutex);
	return MUNIT_OK;
}
#endif

MunitTest tests_takion[] = {
	{
		"/av_packet_parse",
		test_av_packet_parse,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/av_packet_parse_real_video",
		test_av_packet_parse_real_video,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/send_buffer",
		test_takion_send_buffer,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/format_congestion",
		test_takion_format_congestion,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/receive_gap_fold",
		test_takion_receive_gap_fold,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/window_max_receive_gap",
		test_takion_window_max_receive_gap,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/window_max_receive_gap_reset_race",
		test_takion_window_max_receive_gap_reset_race,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
#if defined(__linux__)
	{
		"/send_after_receive_thread_exit",
		test_takion_send_after_receive_thread_exit,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/socket_close_waits_for_senders",
		test_takion_socket_close_waits_for_senders,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
#endif
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL }
};
