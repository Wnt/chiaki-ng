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
	uint64_t live_rtt_us;
	uint64_t server_loss;
	double congestion_measured_loss;
	double congestion_reported_loss;
} ChiakiNetworkStatsSnapshot;

typedef struct chiaki_network_stats_t
{
	ChiakiMutex mutex;
	ChiakiNetworkStatsSnapshot snapshot;
} ChiakiNetworkStats;

CHIAKI_EXPORT ChiakiErrorCode chiaki_network_stats_init(ChiakiNetworkStats *stats);
CHIAKI_EXPORT void chiaki_network_stats_fini(ChiakiNetworkStats *stats);

/** Record raw values from ConnectionQualityPayload. target_bitrate and measured
 * throughput are bits/s; the protobuf RTT is milliseconds. */
CHIAKI_EXPORT void chiaki_network_stats_record_connection_quality(ChiakiNetworkStats *stats,
		uint32_t target_bitrate_bps, uint64_t measured_throughput_bps,
		double live_rtt_ms, uint64_t server_loss);

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
