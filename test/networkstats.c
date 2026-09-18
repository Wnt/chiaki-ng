// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include <chiaki/congestioncontrol.h>
#include <chiaki/networkstats.h>
#include <chiaki/thread.h>
#include <chiaki/packetstats.h>
#include <chiaki/feedbacksender.h>

#include <pb_decode.h>
#include <takion.pb.h>

static MunitResult test_connection_quality_wire_units(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	// Captured PS5 message. target_bitrate is bits/s. The rtt field decodes to 35.285,
	// but PLE-343's impairment-rig capture showed the field is a sawtooth independent of
	// the real round trip, so this asserts the decode only: it is carried through as
	// console_rtt_raw, and console_rtt_us is that figure read as ms for diagnostics alone.
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
	munit_assert_double_equal(snapshot.console_rtt_raw, 35.285, 3);
	munit_assert_uint64(snapshot.console_rtt_us, ==, 35285);
	// The console's figure never touches the probe fields.
	munit_assert_uint64(snapshot.probe_rtt_us, ==, 0);
	munit_assert_uint64(snapshot.probe_rtt_samples, ==, 0);
	munit_assert_uint64(snapshot.server_loss, ==, 2);
	chiaki_network_stats_fini(&stats);
	return MUNIT_OK;
}

static MunitResult test_probe_rtt_matches_only_the_pending_heartbeat(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiNetworkStats stats;
	munit_assert_int(chiaki_network_stats_init(&stats), ==, CHIAKI_ERR_SUCCESS);
	ChiakiNetworkStatsSnapshot snapshot;

	// No probe outstanding: any ack is ignored.
	munit_assert_false(chiaki_network_stats_probe_acked(&stats, 7, 1000));
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_uint64(snapshot.probe_rtt_us, ==, 0);
	munit_assert_uint64(snapshot.probe_rtt_samples, ==, 0);

	// A foreign sequence number (another data message's ack) does not close the probe.
	chiaki_network_stats_probe_sent(&stats, 42, 10000);
	munit_assert_false(chiaki_network_stats_probe_acked(&stats, 41, 10500));
	munit_assert_true(chiaki_network_stats_probe_acked(&stats, 42, 13400));
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_uint64(snapshot.probe_rtt_us, ==, 3400);
	munit_assert_uint64(snapshot.probe_rtt_samples, ==, 1);
	munit_assert_uint64(snapshot.probe_rtt_unacked, ==, 0);

	// A late ack for a closed probe is ignored, the last sample stands.
	munit_assert_false(chiaki_network_stats_probe_acked(&stats, 42, 20000));
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_uint64(snapshot.probe_rtt_us, ==, 3400);

	// A heartbeat superseded before its ack counts as unacked, and its stale ack is dropped.
	chiaki_network_stats_probe_sent(&stats, 43, 30000);
	chiaki_network_stats_probe_sent(&stats, 44, 31000);
	munit_assert_false(chiaki_network_stats_probe_acked(&stats, 43, 31200));
	munit_assert_true(chiaki_network_stats_probe_acked(&stats, 44, 31250));
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_uint64(snapshot.probe_rtt_us, ==, 250);
	munit_assert_uint64(snapshot.probe_rtt_samples, ==, 2);
	munit_assert_uint64(snapshot.probe_rtt_unacked, ==, 1);

	// Karn's algorithm: an ack that arrives past the send buffer's re-send timeout
	// crossed a retransmission, so it is not a round trip. PLE-343's measured case:
	// a heartbeat sent on a 25 ms link, resent at 200 ms, acked at 228.8 ms.
	chiaki_network_stats_probe_sent(&stats, 50, 100000);
	munit_assert_true(chiaki_network_stats_probe_acked(&stats, 50, 100000 + 228800));
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_uint64(snapshot.probe_rtt_us, ==, 250);      // the previous sample stands
	munit_assert_uint64(snapshot.probe_rtt_samples, ==, 2);   // and no new one was recorded
	munit_assert_uint64(snapshot.probe_rtt_ambiguous, ==, 1);

	// The boundary itself is still a usable sample: exactly the re-send timeout means
	// the re-send thread had not yet fired when the ack was timed.
	chiaki_network_stats_probe_sent(&stats, 51, 200000);
	munit_assert_true(chiaki_network_stats_probe_acked(&stats, 51,
		200000 + (uint64_t)CHIAKI_TAKION_DATA_RESEND_TIMEOUT_MS * 1000));
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_uint64(snapshot.probe_rtt_us, ==, (uint64_t)CHIAKI_TAKION_DATA_RESEND_TIMEOUT_MS * 1000);
	munit_assert_uint64(snapshot.probe_rtt_samples, ==, 3);
	munit_assert_uint64(snapshot.probe_rtt_ambiguous, ==, 1);

	// A console quality message in between leaves the probe untouched.
	chiaki_network_stats_probe_sent(&stats, 45, 40000);
	chiaki_network_stats_record_connection_quality(&stats, 1, 1, 98.0, 0);
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_uint64(snapshot.probe_rtt_us, ==, (uint64_t)CHIAKI_TAKION_DATA_RESEND_TIMEOUT_MS * 1000);
	munit_assert_double_equal(snapshot.console_rtt_raw, 98.0, 6);
	munit_assert_uint64(snapshot.console_rtt_us, ==, 98000);
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
	chiaki_network_stats_record_congestion(&stats, 80, 20, 1.0, &received, &lost);
	munit_assert_uint64(received, ==, 80);
	munit_assert_uint64(lost, ==, 20);

	chiaki_network_stats_record_congestion(&stats, 0, 0, 0.05, &received, &lost);
	chiaki_network_stats_get_snapshot(&stats, &snapshot);
	munit_assert_double_equal(snapshot.congestion_measured_loss, 0.0, 6);
	munit_assert_double_equal(snapshot.congestion_reported_loss, 0.0, 6);
	chiaki_network_stats_fini(&stats);
	return MUNIT_OK;
}

static MunitResult test_adaptive_loss_report_hysteresis(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiAdaptiveLossReportState state = { 0 };
	ChiakiAdaptiveLossReportResult result;

	// A short spike is insufficient; the third consecutive 1 Hz loss sample enters.
	for(uint32_t i = 0; i < CHIAKI_ADAPTIVE_LOSS_REPORT_ENTER_SAMPLES - 1; i++)
	{
		result = chiaki_adaptive_loss_report_update(&state, true,
			CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_LOSS, 0);
		munit_assert_false(result.uncapped);
		munit_assert_int(result.transition, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_NONE);
	}
	result = chiaki_adaptive_loss_report_update(&state, true,
		CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_LOSS, 0);
	munit_assert_true(result.uncapped);
	munit_assert_int(result.transition, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_UNCAPPED);
	munit_assert_int(result.reason, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_LOSS);
	munit_assert_uint(result.poor_samples, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_ENTER_SAMPLES);

	// Thirty good samples satisfy recovery hysteresis but the 60 s dwell still wins.
	for(uint32_t i = 0; i < CHIAKI_ADAPTIVE_LOSS_REPORT_COOLDOWN_SAMPLES - 1; i++)
	{
		result = chiaki_adaptive_loss_report_update(&state, true, 0.0, 0);
		munit_assert_true(result.uncapped);
	}
	result = chiaki_adaptive_loss_report_update(&state, true, 0.0, 0);
	munit_assert_false(result.uncapped);
	munit_assert_int(result.transition, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_CAPPED);
	munit_assert_int(result.reason, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_GOOD);
	munit_assert_uint(result.good_samples, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_RECOVER_SAMPLES);
	munit_assert_uint(result.cooldown_samples, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_COOLDOWN_SAMPLES);
	return MUNIT_OK;
}

static MunitResult test_adaptive_loss_report_jitter_and_cooldown(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiAdaptiveLossReportState state = { 0 };
	ChiakiAdaptiveLossReportResult result;

	// A no-packet sample is neutral even if the last jitter estimate was high,
	// and breaks the consecutive poor run.
	for(uint32_t i = 0; i < CHIAKI_ADAPTIVE_LOSS_REPORT_ENTER_SAMPLES - 1; i++)
		chiaki_adaptive_loss_report_update(&state, true, 0.0,
			CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_JITTER_US);
	result = chiaki_adaptive_loss_report_update(&state, false, 1.0,
		CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_JITTER_US);
	munit_assert_false(result.uncapped);
	munit_assert_uint(result.poor_samples, ==, 0);
	munit_assert_int(result.reason, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_NEUTRAL);

	for(uint32_t i = 0; i < CHIAKI_ADAPTIVE_LOSS_REPORT_ENTER_SAMPLES; i++)
		result = chiaki_adaptive_loss_report_update(&state, true, 0.0,
			CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_JITTER_US);
	munit_assert_true(result.uncapped);
	munit_assert_int(result.reason, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_JITTER);

	// Even sustained good input cannot restore the baseline before the dwell expires.
	for(uint32_t i = 0; i < CHIAKI_ADAPTIVE_LOSS_REPORT_RECOVER_SAMPLES; i++)
		result = chiaki_adaptive_loss_report_update(&state, true, 0.0, 0);
	munit_assert_true(result.uncapped);
	munit_assert_int(result.transition, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_NONE);
	return MUNIT_OK;
}

// PLE-365: the two jitter bounds re-derived against real per-second samples of
// chiaki_takion_get_video_packet_jitter_us, recorded in docs/verification/PLE-365.md from
// build/captures/ple356 and build/captures/ple357. Values here are representative samples
// from each phase, not synthetic round numbers, so a regression that drifts a bound back
// toward the old (broken) 2000/5000 values shows up as a wrong classification of real data.
static MunitResult test_adaptive_loss_report_jitter_boundaries_from_capture(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;
	ChiakiAdaptiveLossReportState state = { 0 };
	ChiakiAdaptiveLossReportResult result;

	// Clean-LAN 1 Hz samples (build/captures/ple356/analysis.txt 01_clean/06_clean and
	// ple357 01_clean/05_clean), up to the combined observed max of 3.70 ms: all "good".
	uint64_t clean_samples_us[] = { 1530, 1990, 2400, 2900, 3700 };
	for(size_t i = 0; i < sizeof(clean_samples_us) / sizeof(clean_samples_us[0]); i++)
	{
		result = chiaki_adaptive_loss_report_update(&state, true, 0.0, clean_samples_us[i]);
		munit_assert_int(result.reason, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_GOOD);
	}

	// The 4g phase's own observed maximum, 4.90 ms (ple357 02_4g). It is not clean, but it
	// is the ceiling of a phase the badge itself calls GOOD (PLE-356's after-capture verdict
	// table), so the recovery gate must not call it poor: it lands in the dead zone.
	result = chiaki_adaptive_loss_report_update(&state, true, 0.0, 4900);
	munit_assert_int(result.reason, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_NEUTRAL);
	munit_assert_uint(result.good_samples, ==, 0);

	// wifi-slow's phase median, 7.43 ms (ple356 03_wifi-slow) -- the phase PLE-356 measured
	// as CONSTRAINED against 10.8 ms of the phone's own ping mean |delta|. Three consecutive
	// samples at this level must enter poor.
	for(uint32_t i = 0; i < CHIAKI_ADAPTIVE_LOSS_REPORT_ENTER_SAMPLES - 1; i++)
	{
		result = chiaki_adaptive_loss_report_update(&state, true, 0.0, 7430);
		munit_assert_int(result.transition, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_NONE);
	}
	result = chiaki_adaptive_loss_report_update(&state, true, 0.0, 7430);
	munit_assert_true(result.uncapped);
	munit_assert_int(result.transition, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_UNCAPPED);
	munit_assert_int(result.reason, ==, CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_JITTER);

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
		munit_assert_uint64(snapshot.console_rtt_us, ==, snapshot.target_bitrate_bps * 3);
		munit_assert_uint64(snapshot.server_loss, ==, snapshot.target_bitrate_bps * 4);
	}
	munit_assert_int(chiaki_thread_join(&thread, NULL), ==, CHIAKI_ERR_SUCCESS);
	chiaki_network_stats_fini(&stats);
	return MUNIT_OK;
}

MunitResult test_network_stats_all(void)
{
	ChiakiPacketStats packet_stats;
	munit_assert_int(chiaki_packet_stats_init(&packet_stats), ==, CHIAKI_ERR_SUCCESS);
	chiaki_packet_stats_push_generation(&packet_stats, 90, 10);
	chiaki_packet_stats_push_generation(&packet_stats, 45, 5);
	chiaki_packet_stats_push_recovery(&packet_stats, 8, 2);
	uint64_t received, lost, recovered, unrecoverable;
	chiaki_packet_stats_get_generation_totals(&packet_stats, &received, &lost);
	chiaki_packet_stats_get_recovery_totals(&packet_stats, &recovered, &unrecoverable);
	munit_assert_uint64(received, ==, 135);
	munit_assert_uint64(received + lost, ==, 150);
	munit_assert_uint64(recovered, ==, 8);
	munit_assert_uint64(unrecoverable, ==, 2);
	chiaki_packet_stats_fini(&packet_stats);

	ChiakiFeedbackSender sender = { 0 };
	chiaki_feedback_sender_record_send(&sender, 1000);
	chiaki_feedback_sender_record_send(&sender, 1020);
	chiaki_feedback_sender_record_send(&sender, 1070);
	chiaki_feedback_sender_record_send(&sender, 1121);
	munit_assert_uint64(sender.stats_packets_total, ==, 4);
	munit_assert_uint64(sender.stats_gap_count, ==, 3);
	munit_assert_uint64(sender.stats_gap_sum_ms, ==, 121);
	munit_assert_uint64(sender.stats_gap_max_ms, ==, 51);
	munit_assert_uint64(sender.stats_gaps_over_50_ms, ==, 1);

	MunitResult result = test_connection_quality_wire_units(NULL, NULL);
	if(result != MUNIT_OK)
		return result;
	result = test_probe_rtt_matches_only_the_pending_heartbeat(NULL, NULL);
	if(result != MUNIT_OK)
		return result;
	result = test_congestion_measured_versus_reported(NULL, NULL);
	if(result != MUNIT_OK)
		return result;
	result = test_adaptive_loss_report_hysteresis(NULL, NULL);
	if(result != MUNIT_OK)
		return result;
	result = test_adaptive_loss_report_jitter_and_cooldown(NULL, NULL);
	if(result != MUNIT_OK)
		return result;
	result = test_adaptive_loss_report_jitter_boundaries_from_capture(NULL, NULL);
	if(result != MUNIT_OK)
		return result;
	return test_snapshot_is_coherent_during_update(NULL, NULL);
}
