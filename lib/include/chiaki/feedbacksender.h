// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_FEEDBACKSENDER_H
#define CHIAKI_FEEDBACKSENDER_H

#include "controller.h"
#include "takion.h"
#include "thread.h"
#include "common.h"

#define CHIAKI_FEEDBACK_HISTORY_PACKET_BUF_SIZE 0x300
#define CHIAKI_FEEDBACK_HISTORY_PACKET_QUEUE_SIZE 0x40

#ifdef __cplusplus
extern "C" {
#endif

typedef struct chiaki_feedback_sender_t
{
	ChiakiLog *log;
	ChiakiTakion *takion;
	ChiakiThread thread;

	ChiakiSeqNum16 state_seq_num;

	ChiakiSeqNum16 history_seq_num;
	ChiakiFeedbackHistoryBuffer history_buf;
	uint8_t history_packets[CHIAKI_FEEDBACK_HISTORY_PACKET_QUEUE_SIZE][CHIAKI_FEEDBACK_HISTORY_PACKET_BUF_SIZE];
	size_t history_packet_sizes[CHIAKI_FEEDBACK_HISTORY_PACKET_QUEUE_SIZE];
	size_t history_packet_begin;
	size_t history_packet_len;

	bool should_stop;
	ChiakiControllerState controller_state_prev;
	ChiakiControllerState controller_state_history_prev;
	ChiakiControllerState controller_state;
	bool controller_state_changed;
	bool history_dirty;
	ChiakiMutex state_mutex;
	ChiakiCond state_cond;
	uint32_t state_min_interval_ms;
	uint64_t last_feedback_state_ms;

	// PLE-57: optional periodic packet-rate line in the session log. 0 = disabled (default).
	uint32_t stats_log_interval_ms;
	uint64_t stats_window_start_ms;
	uint64_t stats_state_packets;
	uint64_t stats_history_packets;
	uint64_t stats_packets_total;
} ChiakiFeedbackSender;

/**
 * @param state_min_interval_ms minimum time in ms to wait between sending 2 controller state
 *        packets, or 0 to use the default (8ms)
 * @param stats_log_interval_ms if non-zero, log a feedback packet-rate summary to the session log
 *        roughly every this many ms. 0 (the default) logs nothing.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_feedback_sender_init(ChiakiFeedbackSender *feedback_sender, ChiakiTakion *takion, uint32_t state_min_interval_ms, uint32_t stats_log_interval_ms);
CHIAKI_EXPORT void chiaki_feedback_sender_fini(ChiakiFeedbackSender *feedback_sender);
CHIAKI_EXPORT ChiakiErrorCode chiaki_feedback_sender_set_controller_state(ChiakiFeedbackSender *feedback_sender, ChiakiControllerState *state);
CHIAKI_EXPORT uint64_t chiaki_feedback_sender_get_packets_total(ChiakiFeedbackSender *feedback_sender);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_FEEDBACKSENDER_H
