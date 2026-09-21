// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_SENKUSHA_H
#define CHIAKI_SENKUSHA_H

#include "takion.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct chiaki_session_t ChiakiSession;

typedef struct senkusha_t
{
	ChiakiSession *session;
	ChiakiLog *log;
	ChiakiTakion takion;

	int state;
	bool state_finished;
	bool state_failed;
	bool should_stop;
	/**
	 * PLE-490: the takion thread has ended (it reported DISCONNECT), so nothing more
	 * can arrive and every probe from here on carries no information.
	 */
	bool takion_disconnected;
	ChiakiSeqNum32 data_ack_seq_num_expected;
	uint64_t pong_time_us;
	uint16_t ping_test_index;
	uint16_t ping_index;
	uint32_t ping_tag;
	uint32_t mtu_id;

	/**
	 * signaled on change of state_finished, should_stop or takion_disconnected
	 */
	ChiakiCond state_cond;

	/**
	 * protects state, state_finished, state_failed, should_stop and takion_disconnected
	 */
	ChiakiMutex state_mutex;
} ChiakiSenkusha;

CHIAKI_EXPORT ChiakiErrorCode chiaki_senkusha_init(ChiakiSenkusha *senkusha, ChiakiSession *session);
CHIAKI_EXPORT void chiaki_senkusha_fini(ChiakiSenkusha *senkusha);
CHIAKI_EXPORT ChiakiErrorCode chiaki_senkusha_run(ChiakiSenkusha *senkusha, uint32_t *mtu_in, uint32_t *mtu_out, uint64_t *rtt_us, chiaki_socket_t *sock);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_SENKUSHA_H
