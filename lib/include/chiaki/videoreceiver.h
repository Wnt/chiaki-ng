// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_VIDEORECEIVER_H
#define CHIAKI_VIDEORECEIVER_H

#include "common.h"
#include "log.h"
#include "video.h"
#include "takion.h"
#include "frameprocessor.h"
#include "bitstream.h"
#include "thread.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CHIAKI_VIDEO_PROFILES_MAX 8

/**
 * PLE-474: which frames `frames_lost_total` has already counted, so that a frame is
 * counted once however many paths notice it is gone.
 *
 * The console numbers video frames with a 16-bit `frame_index` that advances by one
 * per frame (the CORRUPTFRAME report videoreceiver.c sends for a skipped range relies
 * on the same fact). A frame no unit of which arrived never reaches the frame
 * processor, but the next frame that does arrive shows the gap, so it can be counted
 * then -- late, never live, and never at all if the stream does not resume.
 */
typedef struct chiaki_frame_loss_tracker_t
{
	int32_t last_arrived; // newest frame index a unit arrived for, -1 before the first
	int32_t counted_through; // every frame index up to this one is either counted or not lost, -1 before the first
} ChiakiFrameLossTracker;

CHIAKI_EXPORT void chiaki_frame_loss_tracker_init(ChiakiFrameLossTracker *tracker);

/**
 * The first unit of frame_index arrived. Returns how many frames immediately before it
 * never delivered a single unit and were not counted yet. The first frame of a stream
 * returns 0: nothing before it was due.
 */
CHIAKI_EXPORT uint32_t chiaki_frame_loss_tracker_frame_arrived(ChiakiFrameLossTracker *tracker, ChiakiSeqNum16 frame_index);

/**
 * Frames first..last (inclusive) could not be used. Returns how many of them were not
 * counted yet, so a range that overlaps an earlier report only counts the new part.
 */
CHIAKI_EXPORT uint32_t chiaki_frame_loss_tracker_frames_failed(ChiakiFrameLossTracker *tracker, ChiakiSeqNum16 first, ChiakiSeqNum16 last);

typedef struct chiaki_video_receiver_t
{
	struct chiaki_session_t *session;
	ChiakiLog *log;
	ChiakiVideoProfile profiles[CHIAKI_VIDEO_PROFILES_MAX];
	size_t profiles_count;
	int profile_cur; // < 1 if no profile selected yet, else index in profiles

	int32_t frame_index_cur; // frame that is currently being filled
	int32_t frame_index_prev; // last frame that has been at least partially decoded
	int32_t frame_index_prev_complete; // last frame that has been completely decoded
	ChiakiFrameProcessor frame_processor;
	ChiakiPacketStats *packet_stats;

	int32_t frames_lost; // handed to video_sample_cb with the next good frame, then reset
	int32_t frames_lost_total; // each lost frame index once, including frames no unit of which arrived (PLE-474)
	ChiakiFrameLossTracker frame_loss_tracker;
	uint64_t frames_received_total;
	/**
	 * PLE-485: frames that arrived and were fully assembled, but were thrown away by
	 * chiaki_video_receiver_flush_frame() because they were a P-frame decoded while still
	 * waiting for an IDR ("Skipping P-frame"). These are not transport loss -- every unit
	 * arrived -- so they are deliberately kept out of frames_lost_total, which PLE-474 and
	 * PLE-475 pinned to mean exactly that.
	 */
	uint64_t frames_discarded_for_idr_total;
	int32_t reference_frames[16];
	ChiakiBitstream bitstream;
	ChiakiMutex waiting_for_idr_mutex;
	bool waiting_for_idr;
	ChiakiMutex frames_lost_mutex;
} ChiakiVideoReceiver;

CHIAKI_EXPORT void chiaki_video_receiver_init(ChiakiVideoReceiver *video_receiver, struct chiaki_session_t *session, ChiakiPacketStats *packet_stats);
CHIAKI_EXPORT void chiaki_video_receiver_fini(ChiakiVideoReceiver *video_receiver);

/**
 * Called after receiving the Stream Info Packet.
 *
 * @param video_receiver
 * @param profiles Array of profiles. Ownership of the contained header buffers will be transferred to the ChiakiVideoReceiver!
 * @param profiles_count must be <= CHIAKI_VIDEO_PROFILES_MAX
 */
CHIAKI_EXPORT void chiaki_video_receiver_stream_info(ChiakiVideoReceiver *video_receiver, ChiakiVideoProfile *profiles, size_t profiles_count);

CHIAKI_EXPORT void chiaki_video_receiver_av_packet(ChiakiVideoReceiver *video_receiver, ChiakiTakionAVPacket *packet);
CHIAKI_EXPORT void chiaki_video_receiver_set_waiting_for_idr(ChiakiVideoReceiver *video_receiver, bool waiting_for_idr);
CHIAKI_EXPORT bool chiaki_video_receiver_get_waiting_for_idr(ChiakiVideoReceiver *video_receiver);
CHIAKI_EXPORT int32_t chiaki_video_receiver_get_frames_lost_total(ChiakiVideoReceiver *video_receiver);
CHIAKI_EXPORT uint64_t chiaki_video_receiver_get_frames_received_total(ChiakiVideoReceiver *video_receiver);
CHIAKI_EXPORT uint64_t chiaki_video_receiver_get_frames_discarded_for_idr_total(ChiakiVideoReceiver *video_receiver);

static inline ChiakiVideoReceiver *chiaki_video_receiver_new(struct chiaki_session_t *session, ChiakiPacketStats *packet_stats)
{
	ChiakiVideoReceiver *video_receiver = CHIAKI_NEW(ChiakiVideoReceiver);
	if(!video_receiver)
		return NULL;
	chiaki_video_receiver_init(video_receiver, session, packet_stats);
	return video_receiver;
}

static inline void chiaki_video_receiver_free(ChiakiVideoReceiver *video_receiver)
{
	if(!video_receiver)
		return;
	chiaki_video_receiver_fini(video_receiver);
	free(video_receiver);
}

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_VIDEORECEIVER_H
