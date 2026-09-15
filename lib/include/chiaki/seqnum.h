// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_SEQNUM_H
#define CHIAKI_SEQNUM_H

#include "common.h"

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// RFC 1982

#define CHIAKI_DEFINE_SEQNUM(bits, greater_sint) \
\
typedef uint##bits##_t ChiakiSeqNum##bits; \
\
static inline bool chiaki_seq_num_##bits##_lt(ChiakiSeqNum##bits a, ChiakiSeqNum##bits b) \
{ \
	if(a == b) \
		return false; \
	greater_sint d = (greater_sint)b - (greater_sint)a; \
	return (a < b && d < ((ChiakiSeqNum##bits)1 << (bits - 1))) \
		|| ((a > b) && -d > ((ChiakiSeqNum##bits)1 << (bits - 1))); \
} \
\
static inline bool chiaki_seq_num_##bits##_gt(ChiakiSeqNum##bits a, ChiakiSeqNum##bits b) \
{ \
	if(a == b) \
		return false; \
	greater_sint d = (greater_sint)b - (greater_sint)a; \
	return (a < b && d > ((ChiakiSeqNum##bits)1 << (bits - 1))) \
		   || ((a > b) && -d < ((ChiakiSeqNum##bits)1 << (bits - 1))); \
}

CHIAKI_DEFINE_SEQNUM(16, int32_t)
CHIAKI_DEFINE_SEQNUM(32, int64_t)
#undef CHIAKI_DEFINE_SEQNUM

/**
 * State for extending a monotonically increasing 16-bit serial number to a
 * 64-bit counter. The 16-bit source wraps every 65536 values. Consecutive
 * inputs may be equal or skip values, but forward gaps must be smaller than
 * the RFC 1982 half range (32768).
 */
typedef struct chiaki_seq_num_16_unwrapper_t
{
	ChiakiSeqNum16 previous;
	uint64_t value;
	bool initialized;
} ChiakiSeqNum16Unwrapper;

static inline void chiaki_seq_num_16_unwrapper_init(ChiakiSeqNum16Unwrapper *unwrapper)
{
	unwrapper->previous = 0;
	unwrapper->value = 0;
	unwrapper->initialized = false;
}

static inline uint64_t chiaki_seq_num_16_unwrap(ChiakiSeqNum16Unwrapper *unwrapper, ChiakiSeqNum16 value)
{
	if(!unwrapper->initialized)
	{
		unwrapper->value = value;
		unwrapper->initialized = true;
	}
	else
	{
		unwrapper->value += (ChiakiSeqNum16)(value - unwrapper->previous);
	}
	unwrapper->previous = value;
	return unwrapper->value;
}

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_SEQNUM_H
