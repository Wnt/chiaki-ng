// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/networkstats.h>

#include <string.h>

CHIAKI_EXPORT ChiakiErrorCode chiaki_network_stats_init(ChiakiNetworkStats *stats)
{
	memset(&stats->snapshot, 0, sizeof(stats->snapshot));
	stats->probe_pending = false;
	stats->probe_seq_num = 0;
	stats->probe_sent_us = 0;
	return chiaki_mutex_init(&stats->mutex, false);
}

CHIAKI_EXPORT void chiaki_network_stats_fini(ChiakiNetworkStats *stats)
{
	chiaki_mutex_fini(&stats->mutex);
}

CHIAKI_EXPORT void chiaki_network_stats_record_connection_quality(ChiakiNetworkStats *stats,
		uint32_t target_bitrate_bps, uint64_t measured_throughput_bps,
		double console_rtt, uint64_t server_loss)
{
	chiaki_mutex_lock(&stats->mutex);
	stats->snapshot.connection_quality_valid = true;
	stats->snapshot.target_bitrate_bps = target_bitrate_bps;
	stats->snapshot.measured_throughput_bps = measured_throughput_bps;
	stats->snapshot.console_rtt_raw = console_rtt;
	stats->snapshot.console_rtt_us = console_rtt > 0.0
		? (uint64_t)(console_rtt * 1000.0 + 0.5) : 0;
	stats->snapshot.server_loss = server_loss;
	chiaki_mutex_unlock(&stats->mutex);
}

CHIAKI_EXPORT void chiaki_network_stats_probe_sent(ChiakiNetworkStats *stats, uint32_t seq_num, uint64_t now_us)
{
	chiaki_mutex_lock(&stats->mutex);
	if(stats->probe_pending)
		stats->snapshot.probe_rtt_unacked++;
	stats->probe_pending = true;
	stats->probe_seq_num = seq_num;
	stats->probe_sent_us = now_us;
	chiaki_mutex_unlock(&stats->mutex);
}

CHIAKI_EXPORT bool chiaki_network_stats_probe_acked(ChiakiNetworkStats *stats, uint32_t seq_num, uint64_t now_us)
{
	chiaki_mutex_lock(&stats->mutex);
	bool matched = stats->probe_pending && stats->probe_seq_num == seq_num;
	if(matched)
	{
		stats->probe_pending = false;
		uint64_t elapsed_us = now_us >= stats->probe_sent_us ? now_us - stats->probe_sent_us : 0;
		// Karn's algorithm: past the re-send timeout the heartbeat has been
		// retransmitted, so this ack cannot be attributed to a single transmission.
		if(elapsed_us <= (uint64_t)CHIAKI_TAKION_DATA_RESEND_TIMEOUT_MS * 1000)
		{
			stats->snapshot.probe_rtt_us = elapsed_us;
			stats->snapshot.probe_rtt_samples++;
		}
		else
			stats->snapshot.probe_rtt_ambiguous++;
	}
	chiaki_mutex_unlock(&stats->mutex);
	return matched;
}

CHIAKI_EXPORT void chiaki_network_stats_record_congestion(ChiakiNetworkStats *stats,
		uint64_t measured_received, uint64_t measured_lost, double packet_loss_max,
		uint64_t *reported_received, uint64_t *reported_lost)
{
	uint64_t total = measured_received + measured_lost;
	double measured_loss = total > 0 ? (double)measured_lost / (double)total : 0.0;
	uint64_t wire_lost = measured_lost;
	uint64_t wire_received = measured_received;
	if(measured_loss > packet_loss_max)
	{
		wire_lost = (uint64_t)((double)total * packet_loss_max);
		wire_received = total - wire_lost;
	}
	double wire_loss = total > 0 ? (double)wire_lost / (double)total : 0.0;

	if(stats)
	{
		chiaki_mutex_lock(&stats->mutex);
		stats->snapshot.congestion_measured_loss = measured_loss;
		stats->snapshot.congestion_reported_loss = wire_loss;
		chiaki_mutex_unlock(&stats->mutex);
	}

	*reported_received = wire_received;
	*reported_lost = wire_lost;
}

CHIAKI_EXPORT void chiaki_network_stats_get_snapshot(ChiakiNetworkStats *stats,
		ChiakiNetworkStatsSnapshot *snapshot)
{
	chiaki_mutex_lock(&stats->mutex);
	*snapshot = stats->snapshot;
	chiaki_mutex_unlock(&stats->mutex);
}
