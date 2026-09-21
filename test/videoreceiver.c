// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include <chiaki/videoreceiver.h>
#include <chiaki/session.h>

#include <string.h>
#include <stdlib.h>

#include "test_log.h"

// PLE-485: a P-frame that arrives fully intact while the receiver is waiting for an IDR
// is thrown away by chiaki_video_receiver_flush_frame() ("Skipping P-frame ... while
// waiting for IDR"). It is not transport loss -- every unit of it arrived -- so it must
// count separately from frames_lost_total, which PLE-474/PLE-475 pinned to mean exactly
// "did not arrive or could not be decoded". These tests drive the real receiver through
// its public API (chiaki_video_receiver_av_packet), using real H.264 NAL fixtures, the
// same slice bytes test/bitstream.c parses directly.

static uint8_t h264_header[] = {
	0x00, 0x00, 0x00, 0x01, 0x67, 0x4d, 0x40, 0x32, 0x91, 0x8a, 0x01, 0xe0, 0x08, 0x9f, 0x97, 0x01,
	0x6a, 0x02, 0x02, 0x02, 0x80, 0x00, 0x03, 0xe9, 0x00, 0x01, 0xd4, 0xc0, 0x44, 0xd0, 0xf1, 0xf1,
	0x50, 0x00, 0x00, 0x00, 0x01, 0x68, 0xee, 0x3c, 0x80,
};

static uint8_t h264_slice_i[] = {
	0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x80, 0x82, 0x1f, 0x00, 0x49, 0xee, 0x03, 0x29, 0xff, 0xf8,
	0x7f, 0x88, 0x46, 0x44, 0x77, 0x17, 0xe7, 0x6d, 0xb3, 0xad, 0x38, 0x19, 0x74, 0x5a, 0xf1, 0x51,
};

static uint8_t h264_slice_p[] = {
	0x00, 0x00, 0x00, 0x01, 0x41, 0x9a, 0x04, 0x44, 0x3f, 0x41, 0x5b, 0xf4, 0x65, 0xb4, 0x3e, 0x1a,
	0xd3, 0xa0, 0x28, 0x1f, 0x83, 0x63, 0x0e, 0xc2, 0xfc, 0x9d, 0x7a, 0xc7, 0xc4, 0x7d, 0xf9, 0x18,
};

// Every unit in these tests fits in a single non-video source packet: is_video = false
// skips frameprocessor.c's per-unit size-extension header (the extra length read out of
// the first 2 bytes when allocating), like frameloss.c's put_unit() helper. But
// chiaki_frame_processor_flush() unconditionally treats a unit's first 2 bytes as that
// header and strips them, video or not -- so every unit still needs 2 leading filler
// bytes for its real payload to survive the strip intact.
static void send_whole_frame(ChiakiVideoReceiver *video_receiver, ChiakiSeqNum16 frame_index, uint8_t *slice, size_t slice_size)
{
	uint8_t buf[64];
	munit_assert_size(slice_size + 2, <=, sizeof(buf));
	buf[0] = 0;
	buf[1] = 0;
	memcpy(buf + 2, slice, slice_size);

	ChiakiTakionAVPacket packet = { 0 };
	packet.frame_index = frame_index;
	packet.adaptive_stream_index = 0;
	packet.is_video = false;
	packet.unit_index = 0;
	packet.units_in_frame_total = 1;
	packet.units_in_frame_fec = 0;
	packet.data = buf;
	packet.data_size = slice_size + 2;
	chiaki_video_receiver_av_packet(video_receiver, &packet);
}

static void init_receiver(ChiakiSession *session, ChiakiVideoReceiver *video_receiver)
{
	memset(session, 0, sizeof(ChiakiSession));
	session->log = get_test_log();
	session->connect_info.video_profile.codec = CHIAKI_CODEC_H264;
	// video_sample_cb stays NULL: none of these frames need to reach a decoder, only be
	// classified as accepted, discarded, or lost.

	chiaki_video_receiver_init(video_receiver, session, NULL);
	ChiakiVideoProfile profile = { 0 };
	profile.width = 1920;
	profile.height = 1080;
	// chiaki_video_receiver_stream_info() takes ownership of the header buffer and frees
	// it in chiaki_video_receiver_fini(), so it must be a heap copy, not the fixture array.
	profile.header = malloc(sizeof(h264_header));
	munit_assert_not_null(profile.header);
	memcpy(profile.header, h264_header, sizeof(h264_header));
	profile.header_sz = sizeof(h264_header);
	chiaki_video_receiver_stream_info(video_receiver, &profile, 1);
}

static MunitResult test_p_frame_while_waiting_for_idr_is_discarded_not_lost(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiSession session;
	ChiakiVideoReceiver video_receiver;
	init_receiver(&session, &video_receiver);

	// Frame 1: an I-frame, accepted normally before any loss has happened.
	send_whole_frame(&video_receiver, 1, h264_slice_i, sizeof(h264_slice_i));
	munit_assert_uint64(chiaki_video_receiver_get_frames_discarded_for_idr_total(&video_receiver), ==, 0);
	munit_assert_int32(chiaki_video_receiver_get_frames_lost_total(&video_receiver), ==, 0);

	// Simulate what an FEC failure with enable_idr_on_fec_failure would have set: an IDR
	// was requested and none has arrived yet.
	chiaki_video_receiver_set_waiting_for_idr(&video_receiver, true);

	// Frame 2: a P-frame that arrives whole -- every unit present, FEC not needed -- but
	// must be thrown away because no IDR has been seen since the request.
	send_whole_frame(&video_receiver, 2, h264_slice_p, sizeof(h264_slice_p));
	munit_assert_true(chiaki_video_receiver_get_waiting_for_idr(&video_receiver));
	munit_assert_uint64(chiaki_video_receiver_get_frames_discarded_for_idr_total(&video_receiver), ==, 1);
	// The frame arrived intact: it must not also inflate frames_lost_total.
	munit_assert_int32(chiaki_video_receiver_get_frames_lost_total(&video_receiver), ==, 0);

	chiaki_video_receiver_fini(&video_receiver);
	return MUNIT_OK;
}

static MunitResult test_idr_arrival_resumes_decode_and_is_not_counted_as_discarded(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiSession session;
	ChiakiVideoReceiver video_receiver;
	init_receiver(&session, &video_receiver);

	// An IDR was already requested (e.g. by an earlier FEC failure, out of scope here)
	// before the very first frame of this run is seen.
	chiaki_video_receiver_set_waiting_for_idr(&video_receiver, true);

	// The first frame is the IDR itself: decode resumes immediately, and the frame is
	// accepted, not discarded.
	send_whole_frame(&video_receiver, 1, h264_slice_i, sizeof(h264_slice_i));
	munit_assert_false(chiaki_video_receiver_get_waiting_for_idr(&video_receiver));
	munit_assert_uint64(chiaki_video_receiver_get_frames_discarded_for_idr_total(&video_receiver), ==, 0);
	munit_assert_int32(chiaki_video_receiver_get_frames_lost_total(&video_receiver), ==, 0);

	chiaki_video_receiver_fini(&video_receiver);
	return MUNIT_OK;
}

static MunitResult test_p_frames_not_waiting_for_idr_are_not_discarded(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiSession session;
	ChiakiVideoReceiver video_receiver;
	init_receiver(&session, &video_receiver);

	send_whole_frame(&video_receiver, 1, h264_slice_i, sizeof(h264_slice_i));
	// No IDR wait armed: a P-frame is handled normally, not discarded.
	send_whole_frame(&video_receiver, 2, h264_slice_p, sizeof(h264_slice_p));
	munit_assert_uint64(chiaki_video_receiver_get_frames_discarded_for_idr_total(&video_receiver), ==, 0);

	chiaki_video_receiver_fini(&video_receiver);
	return MUNIT_OK;
}

MunitTest tests_video_receiver[] = {
	{
		"/p_frame_while_waiting_for_idr_is_discarded_not_lost",
		test_p_frame_while_waiting_for_idr_is_discarded_not_lost,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/p_frames_not_waiting_for_idr_are_not_discarded",
		test_p_frames_not_waiting_for_idr_are_not_discarded,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/idr_arrival_resumes_decode_and_is_not_counted_as_discarded",
		test_idr_arrival_resumes_decode_and_is_not_counted_as_discarded,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL }
};
