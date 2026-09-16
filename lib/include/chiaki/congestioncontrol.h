// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_CONGESTIONCONTROL_H
#define CHIAKI_CONGESTIONCONTROL_H

#include "takion.h"
#include "thread.h"
#include "packetstats.h"
#include "networkstats.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Initial experiment thresholds: the loss-2 impairment is poor; recovery must
 * be clearly below it. Jitter uses separate enter/exit gates to avoid flapping. */
#define CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_LOSS 0.02
#define CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_JITTER_US 5000
#define CHIAKI_ADAPTIVE_LOSS_REPORT_GOOD_LOSS 0.005
#define CHIAKI_ADAPTIVE_LOSS_REPORT_GOOD_JITTER_US 2000
#define CHIAKI_ADAPTIVE_LOSS_REPORT_ENTER_SAMPLES 3
#define CHIAKI_ADAPTIVE_LOSS_REPORT_RECOVER_SAMPLES 30
#define CHIAKI_ADAPTIVE_LOSS_REPORT_COOLDOWN_SAMPLES 60
#define CHIAKI_ADAPTIVE_LOSS_REPORT_SAMPLE_INTERVALS 5

typedef enum chiaki_adaptive_loss_report_transition_t
{
	CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_NONE,
	CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_UNCAPPED,
	CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_CAPPED,
} ChiakiAdaptiveLossReportTransition;

typedef enum chiaki_adaptive_loss_report_reason_t
{
	CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_NONE = 0,
	CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_LOSS = 1 << 0,
	CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_JITTER = 1 << 1,
	CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_LOSS_AND_JITTER =
		CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_LOSS | CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_JITTER,
	CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_GOOD = 1 << 2,
	CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_NEUTRAL = 1 << 3,
} ChiakiAdaptiveLossReportReason;

typedef struct chiaki_adaptive_loss_report_state_t
{
	bool uncapped;
	uint32_t poor_samples;
	uint32_t good_samples;
	uint32_t cooldown_samples;
} ChiakiAdaptiveLossReportState;

typedef struct chiaki_adaptive_loss_report_result_t
{
	ChiakiAdaptiveLossReportTransition transition;
	ChiakiAdaptiveLossReportReason reason;
	bool uncapped;
	uint32_t poor_samples;
	uint32_t good_samples;
	uint32_t cooldown_samples;
} ChiakiAdaptiveLossReportResult;

/** Update the adaptive report policy from one 1 Hz measured sample. */
CHIAKI_EXPORT ChiakiAdaptiveLossReportResult chiaki_adaptive_loss_report_update(
		ChiakiAdaptiveLossReportState *state, bool sample_valid,
		double measured_loss, uint64_t video_jitter_us);

typedef struct chiaki_congestion_control_t
{
	ChiakiTakion *takion;
	ChiakiPacketStats *stats;
	ChiakiThread thread;
	ChiakiBoolPredCond stop_cond;
	double packet_loss;
	double packet_loss_max;
	bool adaptive_loss_report_enabled;
	ChiakiAdaptiveLossReportState adaptive_loss_report;
	uint32_t adaptive_sample_intervals;
	uint64_t adaptive_sample_received;
	uint64_t adaptive_sample_lost;
	ChiakiNetworkStats *network_stats;
} ChiakiCongestionControl;

CHIAKI_EXPORT ChiakiErrorCode chiaki_congestion_control_start(ChiakiCongestionControl *control,
		ChiakiTakion *takion, ChiakiPacketStats *stats, double packet_loss_max,
		bool adaptive_loss_report_enabled, ChiakiNetworkStats *network_stats);

/**
 * Stop control and join the thread
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_congestion_control_stop(ChiakiCongestionControl *control);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_CONGESTIONCONTROL_H
