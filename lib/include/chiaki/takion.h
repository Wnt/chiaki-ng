// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_TAKION_H
#define CHIAKI_TAKION_H

#include "common.h"
#include "thread.h"
#include "log.h"
#include "gkcrypt.h"
#include "seqnum.h"
#include "stoppipe.h"
#include "reorderqueue.h"
#include "feedback.h"
#include "takionsendbuffer.h"

#include <stdbool.h>

#ifdef _WIN32
#include <winsock2.h>
#endif


#ifdef __cplusplus
extern "C" {
#endif

typedef enum chiaki_takion_message_data_type_t {
	CHIAKI_TAKION_MESSAGE_DATA_TYPE_PROTOBUF = 0,
	CHIAKI_TAKION_MESSAGE_DATA_TYPE_RUMBLE = 7,
	CHIAKI_TAKION_MESSAGE_DATA_TYPE_PAD_INFO = 9,
	CHIAKI_TAKION_MESSAGE_DATA_TYPE_TRIGGER_EFFECTS = 11,
} ChiakiTakionMessageDataType;

typedef struct chiaki_takion_av_packet_t
{
	ChiakiSeqNum16 packet_index;
	ChiakiSeqNum16 frame_index;
	bool uses_nalu_info_structs;
	bool is_video;
	bool is_haptics;
	ChiakiSeqNum16 unit_index;
	uint16_t units_in_frame_total; // source + units_in_frame_fec
	uint16_t units_in_frame_fec;
	uint8_t codec;
	uint16_t word_at_0x18;
	uint8_t adaptive_stream_index;
	uint8_t byte_at_0x2c;

	uint64_t key_pos;

	uint8_t *data; // not owned
	size_t data_size;
} ChiakiTakionAVPacket;

static inline uint8_t chiaki_takion_av_packet_audio_unit_size(ChiakiTakionAVPacket *packet)				{ return packet->units_in_frame_fec >> 8; }
static inline uint8_t chiaki_takion_av_packet_audio_source_units_count(ChiakiTakionAVPacket *packet)	{ return packet->units_in_frame_fec & 0xf; }
static inline uint8_t chiaki_takion_av_packet_audio_fec_units_count(ChiakiTakionAVPacket *packet)		{ return (packet->units_in_frame_fec >> 4) & 0xf; }

typedef ChiakiErrorCode (*ChiakiTakionAVPacketParse)(ChiakiTakionAVPacket *packet, ChiakiKeyState *key_state, uint8_t *buf, size_t buf_size);

typedef struct chiaki_takion_congestion_packet_t
{
	uint16_t word_0;
	uint16_t received;
	uint16_t lost;
} ChiakiTakionCongestionPacket;


typedef enum {
	CHIAKI_TAKION_EVENT_TYPE_CONNECTED,
	CHIAKI_TAKION_EVENT_TYPE_DISCONNECT,
	CHIAKI_TAKION_EVENT_TYPE_DATA,
	CHIAKI_TAKION_EVENT_TYPE_DATA_ACK,
	CHIAKI_TAKION_EVENT_TYPE_AV
} ChiakiTakionEventType;

typedef enum {
	CHIAKI_NONE_DISABLED = 0,  //(bits: 00)
	CHIAKI_AUDIO_DISABLED = 1, //(bits: 01)
	CHIAKI_VIDEO_DISABLED = 2, //(bits: 10)
	CHIAKI_AUDIO_VIDEO_DISABLED = 3 //(bits: 11)
} ChiakiDisableAudioVideo;

typedef struct chiaki_takion_event_t
{
	ChiakiTakionEventType type;
	union
	{
		struct
		{
			ChiakiTakionMessageDataType data_type;
			uint8_t *buf;
			size_t buf_size;
		} data;

		struct
		{
			ChiakiSeqNum32 seq_num;
		} data_ack;

		ChiakiTakionAVPacket *av;
	};
} ChiakiTakionEvent;

typedef void (*ChiakiTakionCallback)(ChiakiTakionEvent *event, void *user);

typedef struct chiaki_takion_connect_info_t
{
	ChiakiLog *log;
	struct sockaddr *sa;
	size_t sa_len;
	bool ip_dontfrag;
	ChiakiTakionCallback cb;
	void *cb_user;
	ChiakiDisableAudioVideo disable_audio_video;
	bool enable_crypt;
	bool enable_dualsense;
	uint8_t protocol_version;
	bool disable_video_packet_reordering;
	bool diagnostics_enabled;
	uint32_t video_fps;
	bool close_socket; // close socket when finishing takion
} ChiakiTakionConnectInfo;

/** PLE-356: frame-boundary packet delay variation, plus the superseded per-packet EWMA.
 *
 * The per-packet form (`raw_jitter_us_q4`) derives each packet's send time from
 * `frame_index / fps`, but every packet of one video frame carries the same
 * `frame_index`, so ~9 of every 10 samples are intra-burst gaps of tens of us with a
 * sender delta of zero. The EWMA then converges to roughly the real delay variation
 * divided by the packets per frame, which is a function of bitrate, not a constant.
 * Measured: real delay variation stepped 1.8 -> 13.3 ms while the field moved 2.14 ->
 * 3.11 ms and never reached CONSTRAINED_JITTER_MS. It is kept only so a capture can
 * show both numbers side by side; it drives nothing.
 *
 * `jitter_us_q4` takes one sample per *frame*, from the first packet received for each
 * frame index, against the nominal `frame_delta / fps` cadence. That is a delay
 * variation of the video stream itself and tracks the path.
 *
 * PLE-403: the EWMA above starts at zero, and the gain term `(jitter_us_q4 + 8) >> 4`
 * is itself zero until the accumulator has some magnitude, so the very first frame
 * sample lands in `jitter_us_q4` at full weight, unsmoothed -- it *is* the estimate,
 * not an average of one. A one-off startup delay (the first frame after connect is
 * commonly late) then reads back as if it were the steady-state jitter. `frame_sample_count`
 * counts smoothing updates so callers can tell "one raw sample" from "an average";
 * `CHIAKI_TAKION_VIDEO_JITTER_FILL_SAMPLES` is the filter's own time constant --
 * `1 / gain` with gain `1/16` -- the number of samples after which the initial
 * transient's contribution has decayed to a minority of the accumulator, same
 * reasoning RFC 3550 A.8 uses for its own EWMA.
 */
#define CHIAKI_TAKION_VIDEO_JITTER_MAX_FRAME_DELTA 120
#define CHIAKI_TAKION_VIDEO_JITTER_FILL_SAMPLES 16

typedef struct chiaki_takion_video_packet_jitter_t
{
	bool initialized;
	uint64_t previous_arrival_us;
	ChiakiSeqNum16 previous_frame_index;
	int64_t raw_jitter_us_q4;
	bool frame_initialized;
	uint64_t frame_arrival_us;
	ChiakiSeqNum16 frame_index;
	int64_t jitter_us_q4;
	uint32_t frame_sample_count;
} ChiakiTakionVideoPacketJitter;


typedef struct chiaki_takion_t
{
	ChiakiLog *log;
	uint8_t version;

	// Whether or not audio or video is disabled from further processing beyond basic ack
	ChiakiDisableAudioVideo disable_audio_video;
	/**
	 * Whether encryption should be used.
	 *
	 * If false, encryption and MACs are disabled completely.
	 *
	 * If true, encryption and MACs will be used depending on whether gkcrypt_local and gkcrypt_remote are non-null, respectively.
	 * However, if gkcrypt_remote is null, only control data packets are passed to the callback and all other packets are postponed until
	 * gkcrypt_remote is set, so it has been set, so eventually all MACs will be checked.
	 */
	bool enable_crypt;

	/**
	 * Array to be temporarily allocated when non-data packets come, enable_crypt is true, but gkcrypt_remote is NULL
	 * to not ignore any MACs in this period.
	 */
	struct chiaki_takion_postponed_packet_t *postponed_packets;
	size_t postponed_packets_size;
	size_t postponed_packets_count;

	ChiakiGKCrypt *gkcrypt_local; // if NULL (default), no gmac is calculated and nothing is encrypted
	uint64_t key_pos_local;
	ChiakiMutex gkcrypt_local_mutex;

	ChiakiGKCrypt *gkcrypt_remote; // if NULL (default), remote gmacs are IGNORED (!) and everything is expected to be unencrypted

	ChiakiReorderQueue data_queue;
	ChiakiReorderQueue video_queue;
	bool video_queue_initialized;
	int64_t video_queue_head_wait_start_us;
	uint64_t video_queue_head_wait_seq_num;
	bool disable_video_packet_reordering;
	bool diagnostics_enabled;
	ChiakiMutex diagnostics_mutex;
	uint64_t video_reorder_timeouts;
	uint32_t video_fps;
	ChiakiTakionVideoPacketJitter video_packet_jitter;
	ChiakiTakionSendBuffer send_buffer;

	ChiakiTakionCallback cb;
	void *cb_user;
	chiaki_socket_t sock;
	ChiakiThread thread;
	ChiakiStopPipe stop_pipe;
	uint32_t tag_local;
	uint32_t tag_remote;
	bool close_socket;

	ChiakiSeqNum32 seq_num_local;
	ChiakiMutex seq_num_local_mutex;

	/**
	 * Advertised Receiver Window Credit
	 */
	uint32_t a_rwnd;

	ChiakiTakionAVPacketParse av_packet_parse;

	ChiakiKeyState key_state;

	bool enable_dualsense;

	/**
	 * PLE-423: truncated monotonic ms of the last datagram received on this
	 * socket, 0 until the first one lands, and the longest gap between two of
	 * them so far.
	 *
	 * Written by the Takion receive thread and read by StreamConnection's 1 Hz
	 * loop without a lock. 32 bits on purpose: an aligned 32-bit load is
	 * indivisible on every ABI built here, where a `uint64_t` load is not, and
	 * every reader does unsigned arithmetic so the 49.7-day wrap costs nothing.
	 * A lock per received packet is not worth paying at 60 fps for a counter
	 * whose worst stale read delays a quit by one poll.
	 */
	uint32_t last_receive_ms;
	uint32_t max_receive_gap_ms;
	/**
	 * PLE-464: the same maximum, but over one diagnostics window instead of the
	 * whole session, so the quality classifier can see a gap that has already
	 * closed. Taken and reset by the reader; see
	 * chiaki_takion_take_window_max_receive_gap_ms().
	 */
	uint32_t window_max_receive_gap_ms;
} ChiakiTakion;


CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_connect(ChiakiTakion *takion, ChiakiTakionConnectInfo *info, chiaki_socket_t *sock);
CHIAKI_EXPORT void chiaki_takion_close(ChiakiTakion *takion);
CHIAKI_EXPORT uint64_t chiaki_takion_get_video_reorder_timeouts(ChiakiTakion *takion);
/** PLE-423: truncated monotonic ms of the last inbound datagram, 0 if none has arrived yet. */
CHIAKI_EXPORT uint32_t chiaki_takion_get_last_receive_ms(ChiakiTakion *takion);
/** PLE-423: longest observed gap between two inbound datagrams, in ms. Diagnostics: it is
 * what sets CHIAKI_LINK_WATCHDOG_TIMEOUT_MS, so a rig run can report it. */
CHIAKI_EXPORT uint32_t chiaki_takion_get_max_receive_gap_ms(ChiakiTakion *takion);
/**
 * PLE-464: the longest gap between two inbound datagrams since this function was last
 * called, in ms, resetting the accumulator as it reads.
 *
 * The classifier's only view of a total outage. Its 1 Hz poll of
 * chiaki_link_watchdog_silence_ms() sees only the silence still *running* at the poll
 * instant, so an outage that starts and ends between two polls is invisible to it, and
 * one that ends just after a poll is reported at a fraction of its width (measured:
 * `roam-1200ms` outages read back as 264-1130 ms across ple404/ple404b). This is the
 * gap as it actually happened.
 *
 * Same lockless contract as last_receive_ms above, with one extra: the reset is a
 * plain 32-bit store from the reader thread, so a gap closing in the same instant can
 * be dropped. That costs at most one window's reading of an outage that the next
 * window reports anyway, and is not worth a lock per received packet.
 */
CHIAKI_EXPORT uint32_t chiaki_takion_take_window_max_receive_gap_ms(ChiakiTakion *takion);
/**
 * PLE-464: fold one inter-arrival gap into a running maximum, guarding the clock-read
 * race the same way the watchdog does -- a gap in the top half of the 32-bit range is
 * the two stamps read backwards, not a 25-day silence. Exported so the guard is
 * testable on the host without a live socket.
 */
CHIAKI_EXPORT uint32_t chiaki_takion_receive_gap_fold(uint32_t max_gap_ms, uint32_t previous_ms, uint32_t now_ms);
CHIAKI_EXPORT void chiaki_takion_video_packet_jitter_push(ChiakiTakionVideoPacketJitter *jitter,
	uint64_t arrival_us, ChiakiSeqNum16 frame_index, uint32_t video_fps);
CHIAKI_EXPORT uint64_t chiaki_takion_video_packet_jitter_get(const ChiakiTakionVideoPacketJitter *jitter);
/** The superseded per-packet EWMA. Diagnostics only: never feed it to a threshold. */
CHIAKI_EXPORT uint64_t chiaki_takion_video_packet_jitter_get_raw(const ChiakiTakionVideoPacketJitter *jitter);
CHIAKI_EXPORT uint64_t chiaki_takion_get_video_packet_jitter_us(ChiakiTakion *takion);
CHIAKI_EXPORT uint64_t chiaki_takion_get_video_packet_jitter_raw_us(ChiakiTakion *takion);
/** PLE-403: false until `jitter_us_q4` has absorbed enough frame samples that its value is an
 * average rather than one unsmoothed sample. See the comment on `CHIAKI_TAKION_VIDEO_JITTER_FILL_SAMPLES`. */
CHIAKI_EXPORT bool chiaki_takion_video_packet_jitter_filled(const ChiakiTakionVideoPacketJitter *jitter);
CHIAKI_EXPORT bool chiaki_takion_get_video_packet_jitter_filled(ChiakiTakion *takion);

/**
 * Must be called from within the Takion thread, i.e. inside the callback!
 */
static inline void chiaki_takion_set_crypt(ChiakiTakion *takion, ChiakiGKCrypt *gkcrypt_local, ChiakiGKCrypt *gkcrypt_remote)
{
	takion->gkcrypt_local = gkcrypt_local;
	takion->gkcrypt_remote = gkcrypt_remote;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_packet_mac(ChiakiGKCrypt *crypt, uint8_t *buf, size_t buf_size, uint64_t key_pos, uint8_t *mac_out, uint8_t *mac_old_out);

/**
 * Get a new key pos and advance by data_size.
 *
 * Thread-safe while Takion is running.
 * @param key_pos pointer to write the new key pos to. will be 0 if encryption is disabled. Contents undefined on failure.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_crypt_advance_key_pos(ChiakiTakion *takion, size_t data_size, uint64_t *key_pos);

/**
 * Send a datagram directly on the socket.
 *
 * Thread-safe while Takion is running.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_raw(ChiakiTakion *takion, const uint8_t *buf, size_t buf_size);

/**
 * Calculate the MAC for the packet depending on the type derived from the first byte in buf,
 * assign MAC inside buf at the respective position and send the packet.
 *
 * If encryption is disabled, the MAC will be set to 0.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send(ChiakiTakion *takion, uint8_t *buf, size_t buf_size, uint64_t key_pos);

/**
 * Thread-safe while Takion is running.
 *
 * @param optional pointer to write the sequence number of the sent packet to
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_message_data(ChiakiTakion *takion, uint8_t chunk_flags, uint16_t channel, uint8_t *buf, size_t buf_size, ChiakiSeqNum32 *seq_num);

/**
 * Thread-safe while Takion is running.
 *
 * @param optional pointer to write the sequence number of the sent packet to
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_message_data_cont(ChiakiTakion *takion, uint8_t chunk_flags, uint16_t channel, uint8_t *buf, size_t buf_size, ChiakiSeqNum32 *seq_num);

/**
 * Thread-safe while Takion is running.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_congestion(ChiakiTakion *takion, ChiakiTakionCongestionPacket *packet);

/**
 * Thread-safe while Takion is running.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_feedback_state(ChiakiTakion *takion, ChiakiSeqNum16 seq_num, ChiakiFeedbackState *feedback_state);

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_mic_packet(ChiakiTakion *takion, uint8_t *audio_packet, size_t packet_size, bool ps5);
/**
 * Thread-safe while Takion is running.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_feedback_history(ChiakiTakion *takion, ChiakiSeqNum16 seq_num, uint8_t *payload, size_t payload_size);

#define CHIAKI_TAKION_V9_AV_HEADER_SIZE_VIDEO 0x17
#define CHIAKI_TAKION_V9_AV_HEADER_SIZE_AUDIO 0x12

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_v9_av_packet_parse(ChiakiTakionAVPacket *packet, ChiakiKeyState *key_state, uint8_t *buf, size_t buf_size);

#define CHIAKI_TAKION_V12_AV_HEADER_SIZE_VIDEO 0x17
#define CHIAKI_TAKION_V12_AV_HEADER_SIZE_AUDIO 0x13

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_v12_av_packet_parse(ChiakiTakionAVPacket *packet, ChiakiKeyState *key_state, uint8_t *buf, size_t buf_size);

#define CHIAKI_TAKION_V7_AV_HEADER_SIZE_BASE					0x12
#define CHIAKI_TAKION_V7_AV_HEADER_SIZE_VIDEO_ADD				0x3
#define CHIAKI_TAKION_V7_AV_HEADER_SIZE_NALU_INFO_STRUCTS_ADD	0x3

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_v7_av_packet_format_header(uint8_t *buf, size_t buf_size, size_t *header_size_out, ChiakiTakionAVPacket *packet);

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_v7_av_packet_parse(ChiakiTakionAVPacket *packet, ChiakiKeyState *key_state, uint8_t *buf, size_t buf_size);

#define CHIAKI_TAKION_CONGESTION_PACKET_SIZE 0xf

CHIAKI_EXPORT void chiaki_takion_format_congestion(uint8_t *buf, ChiakiTakionCongestionPacket *packet, uint64_t key_pos);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_TAKION_H
