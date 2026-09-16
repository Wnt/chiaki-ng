// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/congestioncontrol.h>

#include <string.h>

#define CONGESTION_CONTROL_INTERVAL_MS 200

CHIAKI_EXPORT ChiakiAdaptiveLossReportResult chiaki_adaptive_loss_report_update(
		ChiakiAdaptiveLossReportState *state, bool sample_valid,
		double measured_loss, uint64_t video_jitter_us)
{
	ChiakiAdaptiveLossReportResult result = { 0 };
	if(state->cooldown_samples > 0)
		state->cooldown_samples--;

	bool poor_loss = sample_valid && measured_loss >= CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_LOSS;
	bool poor_jitter = sample_valid && video_jitter_us >= CHIAKI_ADAPTIVE_LOSS_REPORT_POOR_JITTER_US;
	bool good = sample_valid && measured_loss <= CHIAKI_ADAPTIVE_LOSS_REPORT_GOOD_LOSS
			&& video_jitter_us <= CHIAKI_ADAPTIVE_LOSS_REPORT_GOOD_JITTER_US;
	if(poor_loss || poor_jitter)
	{
		if(state->poor_samples < CHIAKI_ADAPTIVE_LOSS_REPORT_ENTER_SAMPLES)
			state->poor_samples++;
		state->good_samples = 0;
		if(poor_loss)
			result.reason = (ChiakiAdaptiveLossReportReason)(result.reason | CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_LOSS);
		if(poor_jitter)
			result.reason = (ChiakiAdaptiveLossReportReason)(result.reason | CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_JITTER);
	}
	else if(good)
	{
		if(state->good_samples < CHIAKI_ADAPTIVE_LOSS_REPORT_RECOVER_SAMPLES)
			state->good_samples++;
		state->poor_samples = 0;
		result.reason = CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_GOOD;
	}
	else
	{
		state->poor_samples = 0;
		state->good_samples = 0;
		result.reason = CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_NEUTRAL;
	}

	if(!state->uncapped
			&& state->cooldown_samples == 0
			&& state->poor_samples >= CHIAKI_ADAPTIVE_LOSS_REPORT_ENTER_SAMPLES)
	{
		state->uncapped = true;
		state->cooldown_samples = CHIAKI_ADAPTIVE_LOSS_REPORT_COOLDOWN_SAMPLES;
		result.transition = CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_UNCAPPED;
	}
	else if(state->uncapped
			&& state->cooldown_samples == 0
			&& state->good_samples >= CHIAKI_ADAPTIVE_LOSS_REPORT_RECOVER_SAMPLES)
	{
		state->uncapped = false;
		state->cooldown_samples = CHIAKI_ADAPTIVE_LOSS_REPORT_COOLDOWN_SAMPLES;
		result.transition = CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_CAPPED;
	}

	result.uncapped = state->uncapped;
	result.poor_samples = state->poor_samples;
	result.good_samples = state->good_samples;
	result.cooldown_samples = state->cooldown_samples;
	return result;
}

static const char *adaptive_loss_report_reason_name(ChiakiAdaptiveLossReportReason reason)
{
	switch(reason)
	{
		case CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_LOSS:
			return "loss";
		case CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_JITTER:
			return "jitter";
		case CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_LOSS_AND_JITTER:
			return "loss+jitter";
		case CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_GOOD:
			return "good";
		case CHIAKI_ADAPTIVE_LOSS_REPORT_REASON_NEUTRAL:
			return "neutral";
		default:
			return "unknown";
	}
}

static void *congestion_control_thread_func(void *user)
{
	ChiakiCongestionControl *control = user;
	chiaki_thread_set_affinity(CHIAKI_THREAD_NAME_CONGESTION);

	ChiakiErrorCode err = chiaki_bool_pred_cond_lock(&control->stop_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		return NULL;

	while(true)
	{
		err = chiaki_bool_pred_cond_timedwait(&control->stop_cond, CONGESTION_CONTROL_INTERVAL_MS);
		if(err != CHIAKI_ERR_TIMEOUT)
			break;

		uint64_t measured_received;
		uint64_t measured_lost;
		chiaki_packet_stats_get(control->stats, true, &measured_received, &measured_lost);
		ChiakiTakionCongestionPacket packet = { 0 };
		uint64_t total = measured_received + measured_lost;
		control->packet_loss = total > 0 ? (double)measured_lost / total : 0;

		bool adaptive_sample_ready = false;
		bool adaptive_sample_valid = false;
		double adaptive_sample_loss = 0.0;
		uint64_t video_jitter_us = 0;
		ChiakiAdaptiveLossReportResult adaptive_result = { 0 };
		if(control->adaptive_loss_report_enabled)
		{
			control->adaptive_sample_received += measured_received;
			control->adaptive_sample_lost += measured_lost;
			control->adaptive_sample_intervals++;
			if(control->adaptive_sample_intervals >= CHIAKI_ADAPTIVE_LOSS_REPORT_SAMPLE_INTERVALS)
			{
				uint64_t adaptive_total = control->adaptive_sample_received + control->adaptive_sample_lost;
				adaptive_sample_valid = adaptive_total > 0;
				adaptive_sample_loss = adaptive_total > 0
						? (double)control->adaptive_sample_lost / (double)adaptive_total : 0.0;
				video_jitter_us = chiaki_takion_get_video_packet_jitter_us(control->takion);
				adaptive_result = chiaki_adaptive_loss_report_update(&control->adaptive_loss_report,
						adaptive_sample_valid, adaptive_sample_loss, video_jitter_us);
				control->adaptive_sample_intervals = 0;
				control->adaptive_sample_received = 0;
				control->adaptive_sample_lost = 0;
				adaptive_sample_ready = true;
			}
		}

		double effective_packet_loss_max = control->adaptive_loss_report.uncapped
				? 1.0 : control->packet_loss_max;
		if(control->packet_loss > effective_packet_loss_max)
		{
			CHIAKI_LOGD(control->takion->log, "Clamping reported packet loss: measured=%.1f%% reported_max=%.1f%%",
				control->packet_loss * 100.0, effective_packet_loss_max * 100.0);
		}
		uint64_t reported_received;
		uint64_t reported_lost;
		chiaki_network_stats_record_congestion(control->network_stats,
			measured_received, measured_lost, effective_packet_loss_max,
			&reported_received, &reported_lost);
		double reported_loss = total > 0 ? (double)reported_lost / (double)total : 0.0;
		if(adaptive_sample_ready)
		{
			const char *transition = "none";
			if(adaptive_result.transition == CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_UNCAPPED)
				transition = "uncapped";
			else if(adaptive_result.transition == CHIAKI_ADAPTIVE_LOSS_REPORT_TRANSITION_CAPPED)
				transition = "capped";
			CHIAKI_LOGI(control->takion->log,
				"Adaptive loss report sample: state=%s transition=%s cap=%.4f valid=%d sample_loss=%.4f measured_loss=%.4f reported_loss=%.4f jitter_us=%llu reason=%s poor=%u good=%u cooldown=%u",
				adaptive_result.uncapped ? "uncapped" : "capped", transition,
				effective_packet_loss_max, adaptive_sample_valid,
				adaptive_sample_loss, control->packet_loss, reported_loss,
				(unsigned long long)video_jitter_us,
				adaptive_loss_report_reason_name(adaptive_result.reason),
				adaptive_result.poor_samples, adaptive_result.good_samples,
				adaptive_result.cooldown_samples);
		}
		packet.received = (uint16_t)reported_received;
		packet.lost = (uint16_t)reported_lost;
		CHIAKI_LOGV(control->takion->log, "Sending Congestion Control Packet, received: %u, lost: %u",
			(unsigned int)packet.received, (unsigned int)packet.lost);
		chiaki_takion_send_congestion(control->takion, &packet);
	}

	chiaki_bool_pred_cond_unlock(&control->stop_cond);
	return NULL;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_congestion_control_start(ChiakiCongestionControl *control,
		ChiakiTakion *takion, ChiakiPacketStats *stats, double packet_loss_max,
		bool adaptive_loss_report_enabled, ChiakiNetworkStats *network_stats)
{
	control->takion = takion;
	control->stats = stats;
	control->packet_loss_max = packet_loss_max;
	control->adaptive_loss_report_enabled = adaptive_loss_report_enabled;
	memset(&control->adaptive_loss_report, 0, sizeof(control->adaptive_loss_report));
	control->adaptive_sample_intervals = 0;
	control->adaptive_sample_received = 0;
	control->adaptive_sample_lost = 0;
	control->network_stats = network_stats;
	control->packet_loss = 0;

	ChiakiErrorCode err = chiaki_bool_pred_cond_init(&control->stop_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;

	err = chiaki_thread_create(&control->thread, congestion_control_thread_func, control);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_bool_pred_cond_fini(&control->stop_cond);
		return err;
	}

	chiaki_thread_set_name(&control->thread, "Chiaki Congestion Control");

	return CHIAKI_ERR_SUCCESS;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_congestion_control_stop(ChiakiCongestionControl *control)
{
	ChiakiErrorCode err = chiaki_bool_pred_cond_signal(&control->stop_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;

	err = chiaki_thread_join(&control->thread, NULL);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;
	control->thread.thread = 0;

	return chiaki_bool_pred_cond_fini(&control->stop_cond);
}
