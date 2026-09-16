// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include <chiaki/networkstats.h>
#include <chiaki/thread.h>

#include <pb_decode.h>
#include <takion.pb.h>

static MunitResult test_connection_quality_wire_units(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	// Captured PS5 message: target is bits/s and RTT is milliseconds on the wire.
	static const uint8_t encoded[] = {
		0x08, 0x10, 0x8a, 0x01, 0x25,
		0x08, 0xb8, 0xed, 0xf8, 0x06, 0x10, 0x45, 0x1d, 0x00, 0x00, 0x00, 0x00,
		0x20, 0x00, 0x29, 0x14, 0xae, 0x47, 0xe1, 0x7a, 0xa4, 0x41, 0x40, 0x30,
		0x02, 0x39, 0x20, 0xfd, 0xe8, 0x47, 0x3f, 0xfa, 0xc1, 0x3f, 0x40, 0x90, 0x0b
	};
	tkproto_TakionMessage message = { 0 };
	pb_istream_t stream = pb_istream_from_buffer(encoded, sizeof(encoded));
	munit_assert_true(pb_decode(&stream, tkproto_TakionMessage_fields, &message));
	munit_assert_true(message.has_connection_quality_payload);

	ChiakiNetworkStats stats;
	munit_assert_int(chiaki_network_stats_init(&stats), ==, CHIAKI_ERR_SUCCESS);
	chiaki_network_stats_record_connection_quality(&stats,
		message.connection_quality_payload.target_bitrate, 11137400,
		message.connection_quality_payload.rtt, message.connection_quality_payload.loss);
	ChiakiNetworkStatsSnapshot snapshot;
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_true(snapshot.connection_quality_valid);
	munit_assert_uint64(snapshot.target_bitrate_bps, ==, 14563000);
	munit_assert_uint64(snapshot.measured_throughput_bps, ==, 11137400);
	munit_assert_uint64(snapshot.live_rtt_us, ==, 35285);
	munit_assert_uint64(snapshot.server_loss, ==, 2);
	chiaki_network_stats_fini(&stats);
	return MUNIT_OK;
}

static MunitResult test_congestion_measured_versus_reported(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiNetworkStats stats;
	munit_assert_int(chiaki_network_stats_init(&stats), ==, CHIAKI_ERR_SUCCESS);
	uint64_t received;
	uint64_t lost;
	chiaki_network_stats_record_congestion(&stats, 80, 20, 0.05, &received, &lost);
	munit_assert_uint64(received, ==, 95);
	munit_assert_uint64(lost, ==, 5);
	ChiakiNetworkStatsSnapshot snapshot;
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_double_equal(snapshot.congestion_measured_loss, 0.20, 6);
	munit_assert_double_equal(snapshot.congestion_reported_loss, 0.05, 6);

	chiaki_network_stats_record_congestion(&stats, 0, 0, 0.05, &received, &lost);
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_double_equal(snapshot.congestion_measured_loss, 0.0, 6);
	munit_assert_double_equal(snapshot.congestion_reported_loss, 0.0, 6);
	chiaki_network_stats_fini(&stats);
	return MUNIT_OK;
}

typedef struct network_stats_writer_context_t
{
	ChiakiNetworkStats *stats;
} NetworkStatsWriterContext;

static void *network_stats_writer(void *user)
{
	NetworkStatsWriterContext *context = user;
	for(uint64_t i = 1; i <= 10000; i++)
		chiaki_network_stats_record_connection_quality(context->stats,
			(uint32_t)i, i * 2, (double)(i * 3) / 1000.0, i * 4);
	return NULL;
}

static MunitResult test_snapshot_is_coherent_during_update(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiNetworkStats stats;
	munit_assert_int(chiaki_network_stats_init(&stats), ==, CHIAKI_ERR_SUCCESS);
	NetworkStatsWriterContext context = { .stats = &stats };
	ChiakiThread thread;
	munit_assert_int(chiaki_thread_create(&thread, network_stats_writer, &context), ==,
		CHIAKI_ERR_SUCCESS);
	for(size_t i = 0; i < 10000; i++)
	{
		ChiakiNetworkStatsSnapshot snapshot;
		chiaki_network_stats_get_snapshot(&stats, &snapshot);
		if(!snapshot.connection_quality_valid)
			continue;
		munit_assert_uint64(snapshot.measured_throughput_bps, ==,
			snapshot.target_bitrate_bps * 2);
		munit_assert_uint64(snapshot.live_rtt_us, ==, snapshot.target_bitrate_bps * 3);
		munit_assert_uint64(snapshot.server_loss, ==, snapshot.target_bitrate_bps * 4);
	}
	munit_assert_int(chiaki_thread_join(&thread, NULL), ==, CHIAKI_ERR_SUCCESS);
	chiaki_network_stats_fini(&stats);
	return MUNIT_OK;
}

MunitResult test_network_stats_all(void)
{
	MunitResult result = test_connection_quality_wire_units(NULL, NULL);
	if(result != MUNIT_OK)
		return result;
	result = test_congestion_measured_versus_reported(NULL, NULL);
	if(result != MUNIT_OK)
		return result;
	return test_snapshot_is_coherent_during_update(NULL, NULL);
}
