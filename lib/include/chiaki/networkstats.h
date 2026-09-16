// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_NETWORKSTATS_H
#define CHIAKI_NETWORKSTATS_H

#include "common.h"
#include "thread.h"

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct chiaki_network_stats_snapshot_t
{
	bool connection_quality_valid;
	uint64_t target_bitrate_bps;
	uint64_t measured_throughput_bps;
	/** ConnectionQualityPayload.rtt exactly as decoded. The console derives it
	 * by means we cannot see and we have never verified its unit or meaning
	 * (PLE-343 captured it decaying 193 -> 68 over 8 s on a 3.8 ms LAN), so it
	 * is diagnostics only and must not drive a user-facing verdict. */
	double console_rtt_raw;
	/** console_rtt_raw read as milliseconds, in microseconds. Same caveat. */
	uint64_t console_rtt_us;
	uint64_t server_loss;
	double congestion_measured_loss;
	double congestion_reported_loss;
	/** Our own in-stream round trip: the 1 Hz Takion heartbeat on the data
	 * channel until the console's cumulative DATA_ACK covers it. Same socket
	 * and 5-tuple as the video. 0 until the first ack arrives. A heartbeat
	 * lost on the wire is resent by the send buffer after 200 ms, so a sample
	 * that includes a resend is a real loss signal, not filtered out. */
	uint64_t probe_rtt_us;
	uint64_t probe_rtt_samples;
	/** Heartbeats superseded by the next one before any ack matched them. */
	uint64_t probe_rtt_unacked;
} ChiakiNetworkStatsSnapshot;

typedef struct chiaki_network_stats_t
{
	ChiakiMutex mutex;
	ChiakiNetworkStatsSnapshot snapshot;
	bool probe_pending;
	uint32_t probe_seq_num;
	uint64_t probe_sent_us;
} ChiakiNetworkStats;

CHIAKI_EXPORT ChiakiErrorCode chiaki_network_stats_init(ChiakiNetworkStats *stats);
CHIAKI_EXPORT void chiaki_network_stats_fini(ChiakiNetworkStats *stats);

/** Record raw values from ConnectionQualityPayload. target_bitrate and measured
 * throughput are bits/s. console_rtt is stored untouched and additionally read as
 * milliseconds; see ChiakiNetworkStatsSnapshot for why it is diagnostics only. */
CHIAKI_EXPORT void chiaki_network_stats_record_connection_quality(ChiakiNetworkStats *stats,
		uint32_t target_bitrate_bps, uint64_t measured_throughput_bps,
		double console_rtt, uint64_t server_loss);

/** A data-channel probe (the heartbeat) with sequence number seq_num left at now_us.
 * Supersedes any earlier pending probe, counting it as unacked. */
CHIAKI_EXPORT void chiaki_network_stats_probe_sent(ChiakiNetworkStats *stats, uint32_t seq_num, uint64_t now_us);

/** The console acked seq_num at now_us. Returns true and records a round trip when it
 * is the pending probe; any other sequence number is ignored. */
CHIAKI_EXPORT bool chiaki_network_stats_probe_acked(ChiakiNetworkStats *stats, uint32_t seq_num, uint64_t now_us);

/** Apply the configured loss cap, return the exact integer counts sent on the
 * wire, and retain measured-versus-reported ratios for the 1 Hz snapshot.
 * stats may be NULL when diagnostics are disabled. */
CHIAKI_EXPORT void chiaki_network_stats_record_congestion(ChiakiNetworkStats *stats,
		uint64_t measured_received, uint64_t measured_lost, double packet_loss_max,
		uint64_t *reported_received, uint64_t *reported_lost);

CHIAKI_EXPORT void chiaki_network_stats_get_snapshot(ChiakiNetworkStats *stats,
		ChiakiNetworkStatsSnapshot *snapshot);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_NETWORKSTATS_H
