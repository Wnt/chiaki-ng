// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include <chiaki/linkwatchdog.h>

#define TIMEOUT_MS 10000

static MunitResult test_healthy_stream_never_expires(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiLinkWatchdog watchdog;
	chiaki_link_watchdog_init(&watchdog, 1000, TIMEOUT_MS);
	// Ten minutes of a stream that receives every 16 ms and is polled every second.
	for(uint32_t second = 1; second <= 600; second++)
	{
		uint32_t now_ms = 1000 + second * 1000;
		uint32_t last_receive_ms = now_ms - 16;
		munit_assert_false(chiaki_link_watchdog_check(&watchdog, last_receive_ms, now_ms));
	}
	munit_assert_uint32(watchdog.max_silence_ms, ==, 16);
	munit_assert_false(watchdog.expired);
	return MUNIT_OK;
}

static MunitResult test_expires_once_the_deadline_passes(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiLinkWatchdog watchdog;
	chiaki_link_watchdog_init(&watchdog, 1000, TIMEOUT_MS);
	// Last packet at t=5000, then silence. The 1 Hz poll cannot see the deadline
	// the instant it passes, only on the first tick at or after it.
	const uint32_t last_receive_ms = 5000;
	for(uint32_t now_ms = 6000; now_ms < 15000; now_ms += 1000)
		munit_assert_false(chiaki_link_watchdog_check(&watchdog, last_receive_ms, now_ms));
	munit_assert_true(chiaki_link_watchdog_check(&watchdog, last_receive_ms, 15000));
	munit_assert_true(watchdog.expired);
	return MUNIT_OK;
}

static MunitResult test_expires_exactly_once(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiLinkWatchdog watchdog;
	chiaki_link_watchdog_init(&watchdog, 0, TIMEOUT_MS);
	munit_assert_true(chiaki_link_watchdog_check(&watchdog, 1000, 11000));
	// The stream is torn down asynchronously, so further polls can still arrive.
	// Each must stay quiet; a second true would raise a second quit event.
	for(uint32_t now_ms = 12000; now_ms < 20000; now_ms += 1000)
		munit_assert_false(chiaki_link_watchdog_check(&watchdog, 1000, now_ms));
	return MUNIT_OK;
}

static MunitResult test_one_late_packet_resets_the_deadline(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiLinkWatchdog watchdog;
	chiaki_link_watchdog_init(&watchdog, 0, TIMEOUT_MS);
	// Nine seconds of silence, then a single packet, then nine more. A stall just
	// short of the limit must not accumulate across the packet that ended it.
	munit_assert_false(chiaki_link_watchdog_check(&watchdog, 1000, 9500));
	munit_assert_false(chiaki_link_watchdog_check(&watchdog, 9800, 10500));
	for(uint32_t now_ms = 11000; now_ms <= 19000; now_ms += 1000)
		munit_assert_false(chiaki_link_watchdog_check(&watchdog, 9800, now_ms));
	munit_assert_uint32(watchdog.max_silence_ms, ==, 9200);
	munit_assert_true(chiaki_link_watchdog_check(&watchdog, 9800, 19800));
	return MUNIT_OK;
}

static MunitResult test_no_packet_ever_still_expires(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	// A stream that receives streaminfo and then nothing at all: last_receive_ms
	// stays 0, so the silence is measured from the moment the watchdog was armed.
	ChiakiLinkWatchdog watchdog;
	chiaki_link_watchdog_init(&watchdog, 50000, TIMEOUT_MS);
	munit_assert_uint32(chiaki_link_watchdog_silence_ms(&watchdog, 0, 53000), ==, 3000);
	munit_assert_false(chiaki_link_watchdog_check(&watchdog, 0, 59000));
	munit_assert_true(chiaki_link_watchdog_check(&watchdog, 0, 60000));
	return MUNIT_OK;
}

static MunitResult test_silence_never_predates_arming(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	// The Takion socket is stamped by the connect handshake before the stream
	// loop arms the watchdog, so `last_receive_ms` can legitimately be older than
	// `armed_ms`. Silence is still measured from arming, not from that stamp --
	// otherwise a slow streaminfo would quit the stream on its first poll.
	ChiakiLinkWatchdog watchdog;
	chiaki_link_watchdog_init(&watchdog, 100000, TIMEOUT_MS);
	munit_assert_uint32(chiaki_link_watchdog_silence_ms(&watchdog, 1000, 101000), ==, 1000);
	munit_assert_false(chiaki_link_watchdog_check(&watchdog, 1000, 101000));
	return MUNIT_OK;
}

static MunitResult test_receive_stamp_ahead_of_the_poll(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	// The receive thread stamps and the poll thread reads the clock at different
	// instants, so `last_receive_ms` can be a few ms into the poll's future.
	// Unsigned subtraction would make that ~4.3e9 ms and quit a healthy stream.
	ChiakiLinkWatchdog watchdog;
	chiaki_link_watchdog_init(&watchdog, 1000, TIMEOUT_MS);
	munit_assert_uint32(chiaki_link_watchdog_silence_ms(&watchdog, 5005, 5000), ==, 0);
	munit_assert_false(chiaki_link_watchdog_check(&watchdog, 5005, 5000));
	munit_assert_uint32(watchdog.max_silence_ms, ==, 0);
	return MUNIT_OK;
}

static MunitResult test_survives_the_clock_wrap(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	// The stamp is a truncated monotonic ms, so it wraps every 49.7 days of
	// uptime. Unsigned arithmetic must carry a stream straight through it.
	ChiakiLinkWatchdog watchdog;
	chiaki_link_watchdog_init(&watchdog, 0xfffff000u, TIMEOUT_MS);
	uint32_t last_receive_ms = 0xffffff00u;
	munit_assert_uint32(chiaki_link_watchdog_silence_ms(&watchdog, last_receive_ms, 0x00000100u), ==, 512);
	munit_assert_false(chiaki_link_watchdog_check(&watchdog, last_receive_ms, 0x00000100u));
	// ...and still expires on the far side of the wrap when the link really dies.
	munit_assert_true(chiaki_link_watchdog_check(&watchdog, last_receive_ms, 0x00002800u));
	return MUNIT_OK;
}

static MunitResult test_impairment_gaps_leave_a_wide_margin(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	// The worst inbound gaps the rig's deliberate profiles produced, measured on
	// device (docs/verification/PLE-423/README.md). Every one of them, replayed
	// against the shipped constant, must leave the watchdog silent. If a profile
	// is ever made harsher than the constant tolerates, this fails here rather
	// than on a user's stream.
	static const uint32_t worst_gap_ms[] = { 1000, 1000, 1000, 1000 }; // clean, blip-200ms, 4g, wifi-slow
	for(size_t i = 0; i < sizeof(worst_gap_ms) / sizeof(*worst_gap_ms); i++)
	{
		munit_assert_uint32(worst_gap_ms[i], <, CHIAKI_LINK_WATCHDOG_TIMEOUT_MS / 4);
		ChiakiLinkWatchdog watchdog;
		chiaki_link_watchdog_init(&watchdog, 0, CHIAKI_LINK_WATCHDOG_TIMEOUT_MS);
		// A ten-minute stream in which every single poll lands at the worst gap.
		for(uint32_t second = 1; second <= 600; second++)
		{
			uint32_t now_ms = second * 1000;
			uint32_t last_receive_ms = now_ms - worst_gap_ms[i];
			if(!last_receive_ms)
				last_receive_ms = 1;
			munit_assert_false(chiaki_link_watchdog_check(&watchdog, last_receive_ms, now_ms));
		}
	}
	return MUNIT_OK;
}

MunitTest tests_link_watchdog[] = {
	{
		"/healthy_stream_never_expires",
		test_healthy_stream_never_expires,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/expires_once_the_deadline_passes",
		test_expires_once_the_deadline_passes,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/expires_exactly_once",
		test_expires_exactly_once,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/one_late_packet_resets_the_deadline",
		test_one_late_packet_resets_the_deadline,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/no_packet_ever_still_expires",
		test_no_packet_ever_still_expires,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/silence_never_predates_arming",
		test_silence_never_predates_arming,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/receive_stamp_ahead_of_the_poll",
		test_receive_stamp_ahead_of_the_poll,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/survives_the_clock_wrap",
		test_survives_the_clock_wrap,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{
		"/impairment_gaps_leave_a_wide_margin",
		test_impairment_gaps_leave_a_wide_margin,
		NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL
	},
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL }
};
