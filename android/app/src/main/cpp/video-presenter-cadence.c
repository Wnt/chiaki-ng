// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-presenter-cadence.h"

#include <limits.h>
#include <string.h>

#define VIDEO_CADENCE_EWMA_SHIFT 5
#define VIDEO_CADENCE_ANCHOR_SLEW_NS 1000000LL
#define VIDEO_CADENCE_ANCHOR_SLEW_DIVISOR 64
#define VIDEO_CADENCE_ANCHOR_RELOCK_NS 4000000LL
#define VIDEO_CADENCE_DEPTH_GUARD_NS 1000000ULL
#define VIDEO_CADENCE_DEPTH_DECAY_MARGIN_NS 4000000ULL
#define VIDEO_CADENCE_DEPTH_DECAY_NS 1000000ULL

void android_chiaki_video_cadence_reset(AndroidChiakiVideoCadence *cadence,
		uint64_t depth_floor_ns, uint64_t depth_cap_ns, bool half_rate_adaptation_enabled)
{
	memset(cadence, 0, sizeof(*cadence));
	chiaki_seq_num_16_unwrapper_init(&cadence->frame_index_unwrapper);
	cadence->half_rate_adaptation_enabled = half_rate_adaptation_enabled;
	android_chiaki_video_histogram_reset(&cadence->err_histogram);
	android_chiaki_video_histogram_reset(&cadence->jitter_histogram);
	if(depth_cap_ns < depth_floor_ns)
		depth_cap_ns = depth_floor_ns;
	cadence->depth_floor_ns = depth_floor_ns;
	cadence->depth_cap_ns = depth_cap_ns;
	cadence->depth_ns = depth_floor_ns;
	cadence->target_ns = depth_floor_ns;
}

static bool update_half_rate_detector(AndroidChiakiVideoCadence *cadence,
		uint64_t frame_delta, int64_t ready_delta_ns, uint64_t nominal_period_ns,
		unsigned int stream_fps)
{
	// The target 30-in-60 signature advances the frame index (and therefore its
	// derived PTS) once per delivered picture while consecutive pictures arrive
	// about two nominal 60 fps periods apart. Only classify adjacent indices:
	// an index gap is packet/frame loss, not evidence of a halved source rate.
	if(stream_fps != 60)
	{
		bool previous = cadence->half_rate_detected;
		cadence->half_rate_detected = false;
		cadence->half_rate_streak = 0;
		cadence->full_rate_streak = 0;
		return previous;
	}
	if(frame_delta != 1 || ready_delta_ns <= 0)
	{
		cadence->half_rate_streak = 0;
		cadence->full_rate_streak = 0;
		return false;
	}

	uint64_t ready_delta = (uint64_t)ready_delta_ns;
	uint64_t full_min = nominal_period_ns / 2;
	uint64_t full_max = nominal_period_ns + nominal_period_ns / 2;
	uint64_t half_min = full_max;
	uint64_t half_max = nominal_period_ns * 2 + nominal_period_ns / 2;
	bool half_like = ready_delta >= half_min && ready_delta <= half_max;
	bool full_like = ready_delta >= full_min && ready_delta < full_max;

	if(half_like)
	{
		cadence->full_rate_streak = 0;
		if(cadence->half_rate_streak < ANDROID_CHIAKI_VIDEO_HALF_RATE_CONFIRM_FRAMES)
			cadence->half_rate_streak++;
	}
	else if(full_like)
	{
		cadence->half_rate_streak = 0;
		if(cadence->full_rate_streak < ANDROID_CHIAKI_VIDEO_HALF_RATE_CONFIRM_FRAMES)
			cadence->full_rate_streak++;
	}
	else
	{
		cadence->half_rate_streak = 0;
		cadence->full_rate_streak = 0;
	}

	bool previous = cadence->half_rate_detected;
	if(!previous && cadence->half_rate_streak >= ANDROID_CHIAKI_VIDEO_HALF_RATE_CONFIRM_FRAMES)
		cadence->half_rate_detected = true;
	else if(previous && cadence->full_rate_streak >= ANDROID_CHIAKI_VIDEO_HALF_RATE_CONFIRM_FRAMES)
		cadence->half_rate_detected = false;
	return previous != cadence->half_rate_detected;
}

static void rebase_clock(AndroidChiakiVideoCadence *cadence, uint64_t frame_index,
		int64_t ready_ns)
{
	cadence->clock_valid = true;
	cadence->base_frame_index = frame_index;
	cadence->last_frame_index = frame_index;
	cadence->anchor_ns = ready_ns;
	cadence->previous_positive_err_ns = 0;
	cadence->previous_ready_ns = ready_ns;
	android_chiaki_video_histogram_reset(&cadence->err_histogram);
	android_chiaki_video_histogram_reset(&cadence->jitter_histogram);
	cadence->gap_count = 0;
	android_chiaki_video_histogram_add(&cadence->err_histogram, 0);
}

void android_chiaki_video_cadence_record_decode(AndroidChiakiVideoCadence *cadence,
		uint64_t decode_ns)
{
	if(cadence->decode_ewma_ns == 0)
		cadence->decode_ewma_ns = decode_ns;
	else if(decode_ns >= cadence->decode_ewma_ns)
		cadence->decode_ewma_ns += (decode_ns - cadence->decode_ewma_ns) >> VIDEO_CADENCE_EWMA_SHIFT;
	else
		cadence->decode_ewma_ns -= (cadence->decode_ewma_ns - decode_ns) >> VIDEO_CADENCE_EWMA_SHIFT;
	if(decode_ns > cadence->decode_window_max_ns)
		cadence->decode_window_max_ns = decode_ns;
}

static void finish_window(AndroidChiakiVideoCadence *cadence)
{
	cadence->err_p50_ns = android_chiaki_video_histogram_percentile(
			&cadence->err_histogram, 50, 100);
	cadence->err_p99_ns = android_chiaki_video_histogram_percentile(
			&cadence->err_histogram, 99, 100);
	cadence->jitter_p95_ns = android_chiaki_video_histogram_percentile(
			&cadence->jitter_histogram, 95, 100);
	cadence->gaps = cadence->gap_count;

	cadence->decode_high_windows[cadence->decode_high_next] = cadence->decode_window_max_ns;
	cadence->decode_high_next = (cadence->decode_high_next + 1)
			% ANDROID_CHIAKI_VIDEO_DECODE_HIGH_WINDOWS;
	if(cadence->decode_high_count < ANDROID_CHIAKI_VIDEO_DECODE_HIGH_WINDOWS)
		cadence->decode_high_count++;
	uint64_t decode_high_ns = 0;
	for(uint32_t i = 0; i < cadence->decode_high_count; i++)
	{
		if(cadence->decode_high_windows[i] > decode_high_ns)
			decode_high_ns = cadence->decode_high_windows[i];
	}
	uint64_t decode_margin_ns = decode_high_ns > cadence->decode_ewma_ns
			? decode_high_ns - cadence->decode_ewma_ns : 0;
	uint64_t target_ns = cadence->err_p99_ns + decode_margin_ns + VIDEO_CADENCE_DEPTH_GUARD_NS;
	if(target_ns < cadence->depth_floor_ns)
		target_ns = cadence->depth_floor_ns;
	if(target_ns > cadence->depth_cap_ns)
		target_ns = cadence->depth_cap_ns;
	cadence->target_ns = target_ns;

	if(target_ns > cadence->depth_ns)
		cadence->depth_ns = target_ns;
	else if(target_ns + VIDEO_CADENCE_DEPTH_DECAY_MARGIN_NS < cadence->depth_ns)
	{
		cadence->depth_ns = cadence->depth_ns > cadence->depth_floor_ns + VIDEO_CADENCE_DEPTH_DECAY_NS
				? cadence->depth_ns - VIDEO_CADENCE_DEPTH_DECAY_NS : cadence->depth_floor_ns;
	}

	cadence->decode_window_max_ns = 0;
	android_chiaki_video_histogram_reset(&cadence->err_histogram);
	android_chiaki_video_histogram_reset(&cadence->jitter_histogram);
	cadence->gap_count = 0;
	cadence->generation++;
}

bool android_chiaki_video_cadence_record_frame(AndroidChiakiVideoCadence *cadence,
		ChiakiSeqNum16 frame_index, uint64_t frame_ready_time_us, unsigned int stream_fps)
{
	if(frame_ready_time_us > (uint64_t)INT64_MAX / 1000 || stream_fps == 0)
		return false;
	uint64_t unwrapped = chiaki_seq_num_16_unwrap(&cadence->frame_index_unwrapper, frame_index);
	int64_t ready_ns = (int64_t)(frame_ready_time_us * 1000);
	uint64_t nominal_period_ns = 1000000000ULL / stream_fps;
	bool rate_changed = false;
	if(cadence->clock_valid && unwrapped > cadence->last_frame_index)
	{
		rate_changed = update_half_rate_detector(cadence,
				unwrapped - cadence->last_frame_index,
				ready_ns - cadence->previous_ready_ns, nominal_period_ns, stream_fps);
	}
	if(rate_changed && cadence->half_rate_adaptation_enabled)
	{
		// Do not let samples from the old source period contaminate the first
		// window on the new period. The detector state and depth survive; only
		// the source clock and its in-progress statistics are rebased.
		rebase_clock(cadence, unwrapped, ready_ns);
		return false;
	}
	uint64_t period_ns = nominal_period_ns;
	if(cadence->half_rate_adaptation_enabled && cadence->half_rate_detected)
		period_ns *= 2;
	if(!cadence->clock_valid || unwrapped <= cadence->last_frame_index)
	{
		rebase_clock(cadence, unwrapped, ready_ns);
	}
	else
	{
		int64_t deviation_ns = ready_ns - cadence->previous_ready_ns - (int64_t)period_ns;
		uint64_t absolute_deviation_ns = deviation_ns < 0
				? (uint64_t)(-deviation_ns) : (uint64_t)deviation_ns;
		android_chiaki_video_histogram_add(&cadence->jitter_histogram, absolute_deviation_ns);
		if(deviation_ns > (int64_t)(period_ns / 2))
			cadence->gap_count++;
		uint64_t frame_delta = unwrapped - cadence->base_frame_index;
		if(frame_delta > (uint64_t)INT64_MAX / period_ns)
			return false;
		int64_t expected_ns = cadence->anchor_ns + (int64_t)(frame_delta * period_ns);
		int64_t err_ns = ready_ns - expected_ns;
		if(err_ns < 0)
		{
			cadence->anchor_ns += err_ns;
			cadence->previous_positive_err_ns = 0;
		}
		else if(err_ns >= VIDEO_CADENCE_ANCHOR_RELOCK_NS
				&& cadence->previous_positive_err_ns >= VIDEO_CADENCE_ANCHOR_RELOCK_NS)
		{
			// One late arrival is jitter. The same positive phase error on the next
			// frame means the source clock moved, so retaining it would make every
			// later sample late as well. Fold only the persistent part into the
			// anchor and leave any residual as the current frame's jitter sample.
			int64_t correction_ns = cadence->previous_positive_err_ns < err_ns
					? cadence->previous_positive_err_ns : err_ns;
			cadence->anchor_ns += correction_ns;
			err_ns -= correction_ns;
			cadence->previous_positive_err_ns = err_ns;
		}
		else
		{
			int64_t correction_ns = err_ns < VIDEO_CADENCE_ANCHOR_SLEW_NS
					? err_ns : VIDEO_CADENCE_ANCHOR_SLEW_NS;
			cadence->anchor_ns += correction_ns / VIDEO_CADENCE_ANCHOR_SLEW_DIVISOR;
			cadence->previous_positive_err_ns = err_ns
					- correction_ns / VIDEO_CADENCE_ANCHOR_SLEW_DIVISOR;
		}
		android_chiaki_video_histogram_add(&cadence->err_histogram,
				err_ns > 0 ? (uint64_t)err_ns : 0);
		cadence->last_frame_index = unwrapped;
	}
	cadence->previous_ready_ns = ready_ns;

	if(cadence->err_histogram.count < ANDROID_CHIAKI_VIDEO_CADENCE_WINDOW)
		return false;
	finish_window(cadence);
	return true;
}
