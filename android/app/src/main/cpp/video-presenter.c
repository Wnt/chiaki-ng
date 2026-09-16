// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-presenter.h"
#include "video-presenter-age.h"
#include "video-presenter-histogram.h"
#include "video-presenter-timing.h"

#include <inttypes.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define VIDEO_PRESENTER_LEAD_NS 2000000LL
#define VIDEO_PRESENTER_DJB_START_NS 8000000ULL
#define VIDEO_PRESENTER_DJB_MAX_NS 32000000ULL
#define VIDEO_PRESENTER_DJB_INCREASE_MARGIN_NS 1000000ULL
#define VIDEO_PRESENTER_DJB_DECREASE_MARGIN_NS 4000000ULL
#define VIDEO_PRESENTER_DJB_ADJUST_INTERVAL 60
#define VIDEO_PRESENTER_DJB_DECREASE_WINDOWS 3

extern void AChoreographer_postFrameCallback64_weak(AChoreographer *choreographer,
		AChoreographer_frameCallback64 callback, void *data)
		__asm__("AChoreographer_postFrameCallback64") __attribute__((weak));

static void *output_thread_func(void *user);
static void *vsync_thread_func(void *user);
static void frame_callback_64(int64_t frame_time_ns, void *user);
static void frame_callback_legacy(long frame_time_ns, void *user);
static void drain_immediate_locked(AndroidChiakiVideoPresenter *presenter);

static void record_output_available(AndroidChiakiVideoPresenter *presenter,
		int64_t presentation_time_us, int64_t available_ns)
{
	if(!presenter->diagnostics_enabled)
		return;
	chiaki_mutex_lock(&presenter->mutex);
	presenter->diagnostics_output_frames++;
	for(uint32_t offset = 0; offset < ANDROID_CHIAKI_VIDEO_DIAGNOSTICS_CAPACITY; offset++)
	{
		uint32_t index = (presenter->diagnostics_input_next
				+ ANDROID_CHIAKI_VIDEO_DIAGNOSTICS_CAPACITY - 1 - offset)
				% ANDROID_CHIAKI_VIDEO_DIAGNOSTICS_CAPACITY;
		AndroidChiakiVideoInputTimestamp *input = &presenter->diagnostics_inputs[index];
		if(!input->valid || input->presentation_time_us != presentation_time_us)
			continue;
		input->valid = false;
		if(available_ns >= input->queued_ns && available_ns - input->queued_ns <= 5000000000LL)
		{
			presenter->diagnostics_decode_ns[presenter->diagnostics_decode_next] =
					(uint64_t)(available_ns - input->queued_ns);
			presenter->diagnostics_decode_next = (presenter->diagnostics_decode_next + 1)
					% ANDROID_CHIAKI_VIDEO_DIAGNOSTICS_CAPACITY;
			if(presenter->diagnostics_decode_count < ANDROID_CHIAKI_VIDEO_DIAGNOSTICS_CAPACITY)
				presenter->diagnostics_decode_count++;
		}
		break;
	}
	chiaki_mutex_unlock(&presenter->mutex);
}

static bool start_vsync_thread_if_needed(AndroidChiakiVideoPresenter *presenter)
{
	chiaki_mutex_lock(&presenter->mutex);
	if(presenter->vsync_thread_started || presenter->shutdown || !presenter->timestamped_release_enabled)
	{
		chiaki_mutex_unlock(&presenter->mutex);
		return true;
	}
	ChiakiErrorCode err = chiaki_thread_create(&presenter->vsync_thread, vsync_thread_func, presenter);
	if(err == CHIAKI_ERR_SUCCESS)
	{
		presenter->vsync_thread_started = true;
		chiaki_thread_set_name(&presenter->vsync_thread, "ChiakiVsync");
		chiaki_mutex_unlock(&presenter->mutex);
		return true;
	}
	presenter->timestamped_release_enabled = false;
	drain_immediate_locked(presenter);
	chiaki_mutex_unlock(&presenter->mutex);
	CHIAKI_LOGE(presenter->log, "Failed to create video presenter vsync thread: %s; using immediate release",
			chiaki_error_string(err));
	return false;
}

static int64_t monotonic_time_ns(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static const char *mode_name(AndroidChiakiVideoPacingMode mode)
{
	switch(mode)
	{
		case ANDROID_CHIAKI_VIDEO_PACING_LOWEST_LATENCY:
			return "lowest latency";
		case ANDROID_CHIAKI_VIDEO_PACING_BALANCED:
			return "balanced";
		case ANDROID_CHIAKI_VIDEO_PACING_SMOOTHEST:
			return "smoothest";
		default:
			return "disabled";
	}
}

static AndroidChiakiVideoPacingMode sanitize_mode(AndroidChiakiVideoPacingMode mode)
{
	if(mode < ANDROID_CHIAKI_VIDEO_PACING_DISABLED || mode > ANDROID_CHIAKI_VIDEO_PACING_SMOOTHEST)
		return ANDROID_CHIAKI_VIDEO_PACING_DISABLED;
	return mode;
}

static AndroidChiakiVideoPresenterLead sanitize_lead_mode(AndroidChiakiVideoPresenterLead lead_mode)
{
	if(lead_mode != ANDROID_CHIAKI_VIDEO_PRESENTER_LEAD_HALF_VSYNC)
		return ANDROID_CHIAKI_VIDEO_PRESENTER_LEAD_2MS;
	return lead_mode;
}

static int64_t presenter_lead_ns(const AndroidChiakiVideoPresenter *presenter)
{
	return presenter->lead_mode == ANDROID_CHIAKI_VIDEO_PRESENTER_LEAD_HALF_VSYNC
			? presenter->vsync_period_ns / 2 : VIDEO_PRESENTER_LEAD_NS;
}

static bool refresh_matches(double refresh_hz, unsigned int stream_fps)
{
	double difference = refresh_hz - (double)stream_fps;
	return difference >= -0.5 && difference <= 0.5;
}

static bool timestamped_release_eligible(AndroidChiakiVideoPacingMode mode, double refresh_hz,
		unsigned int stream_fps)
{
	if(mode != ANDROID_CHIAKI_VIDEO_PACING_BALANCED && mode != ANDROID_CHIAKI_VIDEO_PACING_SMOOTHEST)
		return false;
	if(refresh_hz >= 119.0)
		return false;
	return (refresh_hz >= 59.0 && refresh_hz <= 61.0) || refresh_matches(refresh_hz, stream_fps);
}

static bool queue_pop(AndroidChiakiVideoPresenter *presenter, AndroidChiakiVideoPresenterFrame *frame)
{
	if(presenter->queue_size == 0)
		return false;
	*frame = presenter->queue[presenter->queue_head];
	presenter->queue_head = (presenter->queue_head + 1) % ANDROID_CHIAKI_VIDEO_PRESENTER_QUEUE_CAPACITY;
	presenter->queue_size--;
	chiaki_cond_signal(&presenter->queue_cond);
	return true;
}

static void record_release_locked(AndroidChiakiVideoPresenter *presenter, bool dropped)
{
	if(dropped)
		presenter->dropped_frames++;
	if(presenter->release_cb)
		presenter->release_cb(presenter->release_cb_user, dropped);
}

static void release_frame_locked(AndroidChiakiVideoPresenter *presenter,
		const AndroidChiakiVideoPresenterFrame *frame, bool render, int64_t release_time_ns)
{
	media_status_t result;
	if(render && release_time_ns > 0)
		result = AMediaCodec_releaseOutputBufferAtTime(presenter->codec, frame->index, release_time_ns);
	else
		result = AMediaCodec_releaseOutputBuffer(presenter->codec, frame->index, render);
	if(result != AMEDIA_OK)
		CHIAKI_LOGW(presenter->log, "AMediaCodec output release failed: %d", (int)result);
	if(frame->info.size != 0)
		record_release_locked(presenter, !render);
}

static int compare_u64(const void *left, const void *right)
{
	uint64_t a = *(const uint64_t *)left;
	uint64_t b = *(const uint64_t *)right;
	return a < b ? -1 : a > b ? 1 : 0;
}

static void adjust_dejitter_buffer_locked(AndroidChiakiVideoPresenter *presenter)
{
	uint32_t count = presenter->arrival_offset_count;
	if(count < ANDROID_CHIAKI_VIDEO_PRESENTER_JITTER_WINDOW)
		return;

	int64_t baseline = presenter->arrival_offsets[0];
	for(uint32_t i = 1; i < count; i++)
	{
		if(presenter->arrival_offsets[i] < baseline)
			baseline = presenter->arrival_offsets[i];
	}
	AndroidChiakiVideoHistogram histogram;
	android_chiaki_video_histogram_reset(&histogram);
	for(uint32_t i = 0; i < count; i++)
		android_chiaki_video_histogram_add(&histogram,
				(uint64_t)(presenter->arrival_offsets[i] - baseline));
	uint64_t percentile_ns = android_chiaki_video_histogram_percentile(&histogram, 997, 1000);
	uint64_t old_depth_ns = presenter->dejitter_buffer_ns;
	uint64_t period_ns = presenter->vsync_period_ns > 0 ? (uint64_t)presenter->vsync_period_ns : 16666667ULL;

	if(percentile_ns + VIDEO_PRESENTER_DJB_INCREASE_MARGIN_NS > presenter->dejitter_buffer_ns)
	{
		uint64_t increased = presenter->dejitter_buffer_ns + period_ns;
		presenter->dejitter_buffer_ns = increased < VIDEO_PRESENTER_DJB_MAX_NS ? increased : VIDEO_PRESENTER_DJB_MAX_NS;
		presenter->decrease_hysteresis = 0;
	}
	else if(percentile_ns + VIDEO_PRESENTER_DJB_DECREASE_MARGIN_NS < presenter->dejitter_buffer_ns)
	{
		presenter->decrease_hysteresis++;
		if(presenter->decrease_hysteresis >= VIDEO_PRESENTER_DJB_DECREASE_WINDOWS)
		{
			presenter->dejitter_buffer_ns = presenter->dejitter_buffer_ns > period_ns
					? presenter->dejitter_buffer_ns - period_ns : VIDEO_PRESENTER_DJB_START_NS;
			if(presenter->dejitter_buffer_ns < VIDEO_PRESENTER_DJB_START_NS)
				presenter->dejitter_buffer_ns = VIDEO_PRESENTER_DJB_START_NS;
			presenter->decrease_hysteresis = 0;
		}
	}
	else
	{
		presenter->decrease_hysteresis = 0;
	}

	if(old_depth_ns != presenter->dejitter_buffer_ns)
	{
		if(presenter->timeline_valid)
			presenter->timeline_offset_ns += (int64_t)presenter->dejitter_buffer_ns - (int64_t)old_depth_ns;
		CHIAKI_LOGI(presenter->log, "Video presenter DJB adjusted from %.1f ms to %.1f ms (arrival jitter p99.7 %.1f ms)",
				(double)old_depth_ns / 1000000.0, (double)presenter->dejitter_buffer_ns / 1000000.0,
				(double)percentile_ns / 1000000.0);
	}
}

static void record_arrival_locked(AndroidChiakiVideoPresenter *presenter,
		const AndroidChiakiVideoPresenterFrame *frame)
{
	int64_t sample_ns;
	if(presenter->real_pts_enabled)
	{
		int64_t pts_ns = frame->info.presentationTimeUs * 1000LL;
		sample_ns = frame->arrival_ns - pts_ns;
	}
	else
	{
		if(presenter->last_arrival_ns == 0)
		{
			presenter->last_arrival_ns = frame->arrival_ns;
			return;
		}
		sample_ns = frame->arrival_ns - presenter->last_arrival_ns;
		presenter->last_arrival_ns = frame->arrival_ns;
	}
	presenter->arrival_offsets[presenter->arrival_offset_next] = sample_ns;
	presenter->arrival_offset_next = (presenter->arrival_offset_next + 1) % ANDROID_CHIAKI_VIDEO_PRESENTER_JITTER_WINDOW;
	if(presenter->arrival_offset_count < ANDROID_CHIAKI_VIDEO_PRESENTER_JITTER_WINDOW)
		presenter->arrival_offset_count++;
	if(!presenter->timestamped_release_enabled)
		return;
	presenter->samples_since_adjustment++;
	if(presenter->samples_since_adjustment >= VIDEO_PRESENTER_DJB_ADJUST_INTERVAL)
	{
		presenter->samples_since_adjustment = 0;
		adjust_dejitter_buffer_locked(presenter);
	}
}

static int64_t align_at_or_after(int64_t value, int64_t phase, int64_t period)
{
	if(period <= 0 || value <= phase)
		return phase;
	int64_t periods = (value - phase + period - 1) / period;
	return phase + periods * period;
}

static int64_t align_nearest(int64_t value, int64_t phase, int64_t period)
{
	if(period <= 0)
		return value;
	int64_t delta = value - phase;
	if(delta >= 0)
		return phase + ((delta + period / 2) / period) * period;
	return phase - ((-delta + period / 2) / period) * period;
}

static void drain_immediate_locked(AndroidChiakiVideoPresenter *presenter)
{
	bool drop_stale = presenter->mode == ANDROID_CHIAKI_VIDEO_PACING_LOWEST_LATENCY
			|| presenter->mode == ANDROID_CHIAKI_VIDEO_PACING_BALANCED
			|| (presenter->mode == ANDROID_CHIAKI_VIDEO_PACING_DISABLED && presenter->late_frame_recovery_enabled);
	while(presenter->queue_size > 0)
	{
		AndroidChiakiVideoPresenterFrame frame;
		queue_pop(presenter, &frame);
		bool render = !drop_stale || presenter->queue_size == 0;
		release_frame_locked(presenter, &frame, render, 0);
	}
	presenter->timeline_valid = false;
}

static void on_vsync(AndroidChiakiVideoPresenter *presenter, int64_t app_vsync_ns)
{
	chiaki_mutex_lock(&presenter->mutex);
	if(presenter->shutdown)
	{
		chiaki_mutex_unlock(&presenter->mutex);
		return;
	}

	int64_t physical_vsync_ns = app_vsync_ns - presenter->app_vsync_offset_ns;
	if(presenter->last_vsync_ns > 0)
	{
		int64_t observed_period_ns = physical_vsync_ns - presenter->last_vsync_ns;
		AndroidChiakiVideoPresenterPeriodObservation observation =
				android_chiaki_video_presenter_classify_period(presenter->vsync_period_ns, observed_period_ns);
		if(observation == ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_RELOCK)
			presenter->vsync_period_ns = observed_period_ns;
		else if(observation == ANDROID_CHIAKI_VIDEO_PRESENTER_PERIOD_SMOOTH)
			presenter->vsync_period_ns = (presenter->vsync_period_ns * 7 + observed_period_ns) / 8;
		else if(observed_period_ns >= presenter->vsync_period_ns * 3 / 2)
		{
			uint64_t elapsed_vsyncs = (uint64_t)((observed_period_ns + presenter->vsync_period_ns / 2) / presenter->vsync_period_ns);
			if(elapsed_vsyncs > 1)
				presenter->missed_vsyncs += elapsed_vsyncs - 1;
		}
	}
	presenter->last_vsync_ns = physical_vsync_ns;

	if(!presenter->timestamped_release_enabled)
	{
		drain_immediate_locked(presenter);
		goto repost;
	}

	int64_t next_vsync_ns = physical_vsync_ns + presenter->vsync_period_ns;
	while(presenter->queue_size > 0)
	{
		AndroidChiakiVideoPresenterFrame *head = &presenter->queue[presenter->queue_head];
		int64_t pts_ns = head->info.presentationTimeUs * 1000LL;
		if(!presenter->timeline_valid)
		{
			int64_t desired_ns = head->arrival_ns + (int64_t)presenter->dejitter_buffer_ns;
			int64_t first_target_ns = align_at_or_after(desired_ns, next_vsync_ns, presenter->vsync_period_ns);
			presenter->timeline_offset_ns = first_target_ns - pts_ns;
			presenter->timeline_valid = true;
		}

		int64_t target_vsync_ns = align_nearest(pts_ns + presenter->timeline_offset_ns,
				next_vsync_ns, presenter->vsync_period_ns);
		if(target_vsync_ns > next_vsync_ns + presenter->vsync_period_ns / 3)
			break;

		if(target_vsync_ns < next_vsync_ns - presenter->vsync_period_ns / 3)
		{
			presenter->missed_vsyncs++;
			if(presenter->mode == ANDROID_CHIAKI_VIDEO_PACING_BALANCED)
			{
				AndroidChiakiVideoPresenterFrame dropped;
				queue_pop(presenter, &dropped);
				release_frame_locked(presenter, &dropped, false, 0);
				continue;
			}
			int64_t shift_ns = align_at_or_after(next_vsync_ns - target_vsync_ns, 0, presenter->vsync_period_ns);
			presenter->timeline_offset_ns += shift_ns;
			target_vsync_ns += shift_ns;
		}

		AndroidChiakiVideoPresenterFrame frame;
		queue_pop(presenter, &frame);
		release_frame_locked(presenter, &frame, true, target_vsync_ns - presenter_lead_ns(presenter));
		break;
	}

repost:
	;
	bool repost = !presenter->shutdown;
	AChoreographer *choreographer = presenter->choreographer;
	chiaki_mutex_unlock(&presenter->mutex);
	if(repost)
	{
		if(AChoreographer_postFrameCallback64_weak)
			AChoreographer_postFrameCallback64_weak(choreographer, frame_callback_64, presenter);
		else
			AChoreographer_postFrameCallback(choreographer, frame_callback_legacy, presenter);
	}
}

static void frame_callback_64(int64_t frame_time_ns, void *user)
{
	on_vsync(user, frame_time_ns);
}

static void frame_callback_legacy(long frame_time_ns, void *user)
{
	int64_t value_ns;
	if(sizeof(long) >= sizeof(int64_t))
		value_ns = (int64_t)frame_time_ns;
	else
	{
		int64_t now_ns = monotonic_time_ns();
		int64_t wrap_ns = 1LL << 32;
		value_ns = (now_ns & ~(wrap_ns - 1)) | (uint32_t)frame_time_ns;
		if(value_ns > now_ns + wrap_ns / 2)
			value_ns -= wrap_ns;
		else if(value_ns < now_ns - wrap_ns / 2)
			value_ns += wrap_ns;
	}
	on_vsync(user, value_ns);
}

static void *vsync_thread_func(void *user)
{
	AndroidChiakiVideoPresenter *presenter = user;
	chiaki_thread_set_affinity(CHIAKI_THREAD_NAME_VIDEO_PRESENTER);
	if(presenter->performance_hint_thread_start_cb)
		presenter->performance_hint_thread_start_cb(presenter->performance_hint_cb_user,
				CHIAKI_THREAD_NAME_VIDEO_PRESENTER);
	ALooper *looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
	AChoreographer *choreographer = AChoreographer_getInstance();
	chiaki_mutex_lock(&presenter->mutex);
	presenter->looper = looper;
	presenter->choreographer = choreographer;
	bool shutdown = presenter->shutdown;
	chiaki_mutex_unlock(&presenter->mutex);
	if(!looper || !choreographer)
	{
		CHIAKI_LOGE(presenter->log, "Failed to initialize native video presenter Choreographer");
		chiaki_mutex_lock(&presenter->mutex);
		presenter->timestamped_release_enabled = false;
		chiaki_mutex_unlock(&presenter->mutex);
		if(presenter->performance_hint_thread_stop_cb)
			presenter->performance_hint_thread_stop_cb(presenter->performance_hint_cb_user,
					CHIAKI_THREAD_NAME_VIDEO_PRESENTER);
		return NULL;
	}
	if(shutdown)
	{
		CHIAKI_LOGI(presenter->log, "Video Presenter Vsync Thread exiting before first callback");
		if(presenter->performance_hint_thread_stop_cb)
			presenter->performance_hint_thread_stop_cb(presenter->performance_hint_cb_user,
					CHIAKI_THREAD_NAME_VIDEO_PRESENTER);
		return NULL;
	}

	if(AChoreographer_postFrameCallback64_weak)
		AChoreographer_postFrameCallback64_weak(choreographer, frame_callback_64, presenter);
	else
		AChoreographer_postFrameCallback(choreographer, frame_callback_legacy, presenter);

	while(!shutdown)
	{
		ALooper_pollOnce(-1, NULL, NULL, NULL);
		chiaki_mutex_lock(&presenter->mutex);
		shutdown = presenter->shutdown;
		chiaki_mutex_unlock(&presenter->mutex);
	}

	CHIAKI_LOGI(presenter->log, "Video Presenter Vsync Thread exiting");
	if(presenter->performance_hint_thread_stop_cb)
		presenter->performance_hint_thread_stop_cb(presenter->performance_hint_cb_user,
				CHIAKI_THREAD_NAME_VIDEO_PRESENTER);
	return NULL;
}

static bool direct_drop_stale(const AndroidChiakiVideoPresenter *presenter)
{
	return presenter->mode == ANDROID_CHIAKI_VIDEO_PACING_LOWEST_LATENCY
			|| presenter->mode == ANDROID_CHIAKI_VIDEO_PACING_BALANCED
			|| (presenter->mode == ANDROID_CHIAKI_VIDEO_PACING_DISABLED && presenter->late_frame_recovery_enabled);
}

static bool handle_direct_frame(AndroidChiakiVideoPresenter *presenter, size_t index,
		AMediaCodecBufferInfo info)
{
	AndroidChiakiVideoPresenterFrame current = {
		.index = index,
		.info = info,
		.arrival_ns = monotonic_time_ns(),
	};
	bool drop_stale;
	bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
	chiaki_mutex_lock(&presenter->mutex);
	drop_stale = direct_drop_stale(presenter);
	if(current.info.size != 0)
		record_arrival_locked(presenter, &current);
	chiaki_mutex_unlock(&presenter->mutex);

	while(drop_stale && current.info.size != 0)
	{
		AMediaCodecBufferInfo newer_info;
		ssize_t newer_index = AMediaCodec_dequeueOutputBuffer(presenter->codec, &newer_info, 0);
		if(newer_index < 0)
			break;
		AndroidChiakiVideoPresenterFrame newer = {
			.index = (size_t)newer_index,
			.info = newer_info,
			.arrival_ns = monotonic_time_ns(),
		};
		if(newer_info.size != 0)
			record_output_available(presenter, newer_info.presentationTimeUs, newer.arrival_ns);
		if((newer_info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0)
			eos = true;
		bool newer_is_frame = newer_info.size != 0;
		chiaki_mutex_lock(&presenter->mutex);
		if(presenter->shutdown)
		{
			release_frame_locked(presenter, &current, false, 0);
			release_frame_locked(presenter, &newer, false, 0);
			chiaki_mutex_unlock(&presenter->mutex);
			return eos;
		}
		release_frame_locked(presenter, &current, !newer_is_frame, 0);
		if(newer_is_frame)
			record_arrival_locked(presenter, &newer);
		chiaki_mutex_unlock(&presenter->mutex);
		if(!newer_is_frame)
		{
			current = newer;
			break;
		}
		current = newer;
	}

	chiaki_mutex_lock(&presenter->mutex);
	bool render = !presenter->shutdown && current.info.size != 0;
	release_frame_locked(presenter, &current, render, 0);
	chiaki_mutex_unlock(&presenter->mutex);
	return eos;
}

static void enqueue_paced_frame(AndroidChiakiVideoPresenter *presenter, size_t index,
		AMediaCodecBufferInfo info)
{
	AndroidChiakiVideoPresenterFrame frame = {
		.index = index,
		.info = info,
		.arrival_ns = monotonic_time_ns(),
	};
	chiaki_mutex_lock(&presenter->mutex);
	while(presenter->queue_size == ANDROID_CHIAKI_VIDEO_PRESENTER_QUEUE_CAPACITY
			&& presenter->mode == ANDROID_CHIAKI_VIDEO_PACING_SMOOTHEST
			&& presenter->max_queue_age_periods == 0 && !presenter->shutdown)
		chiaki_cond_wait(&presenter->queue_cond, &presenter->mutex);
	if(presenter->shutdown)
	{
		release_frame_locked(presenter, &frame, false, 0);
		chiaki_mutex_unlock(&presenter->mutex);
		return;
	}
	if(!presenter->timestamped_release_enabled)
	{
		release_frame_locked(presenter, &frame, true, 0);
		chiaki_mutex_unlock(&presenter->mutex);
		return;
	}
	while(presenter->queue_size > 0
			&& android_chiaki_video_presenter_frame_exceeds_age(
				presenter->queue[presenter->queue_head].info.presentationTimeUs,
				frame.info.presentationTimeUs, presenter->stream_fps,
				presenter->max_queue_age_periods))
	{
		AndroidChiakiVideoPresenterFrame dropped;
		queue_pop(presenter, &dropped);
		release_frame_locked(presenter, &dropped, false, 0);
		presenter->bounded_age_dropped_frames++;
	}
	if(presenter->queue_size == ANDROID_CHIAKI_VIDEO_PRESENTER_QUEUE_CAPACITY)
	{
		AndroidChiakiVideoPresenterFrame dropped;
		queue_pop(presenter, &dropped);
		release_frame_locked(presenter, &dropped, false, 0);
	}
	uint32_t tail = (presenter->queue_head + presenter->queue_size) % ANDROID_CHIAKI_VIDEO_PRESENTER_QUEUE_CAPACITY;
	presenter->queue[tail] = frame;
	presenter->queue_size++;
	record_arrival_locked(presenter, &frame);
	chiaki_mutex_unlock(&presenter->mutex);
}

static void *output_thread_func(void *user)
{
	AndroidChiakiVideoPresenter *presenter = user;
	chiaki_thread_set_affinity(CHIAKI_THREAD_NAME_VIDEO_DECODER);
	if(presenter->performance_hint_thread_start_cb)
		presenter->performance_hint_thread_start_cb(presenter->performance_hint_cb_user,
				CHIAKI_THREAD_NAME_VIDEO_DECODER);
	int64_t last_report_ns = monotonic_time_ns();
	while(true)
	{
		AMediaCodecBufferInfo info;
		ssize_t status = AMediaCodec_dequeueOutputBuffer(presenter->codec, &info, -1);
		if(status >= 0)
		{
			bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
			if(info.size != 0)
				record_output_available(presenter, info.presentationTimeUs, monotonic_time_ns());
			if(presenter->real_pts_enabled && info.size != 0)
				CHIAKI_LOGV(presenter->log, "Video Decoder output PTS: %" PRId64 " us", info.presentationTimeUs);
			if(info.size == 0)
			{
				AndroidChiakiVideoPresenterFrame frame = { .index = (size_t)status, .info = info, .arrival_ns = monotonic_time_ns() };
				chiaki_mutex_lock(&presenter->mutex);
				AMediaCodec_releaseOutputBuffer(presenter->codec, frame.index, false);
				chiaki_mutex_unlock(&presenter->mutex);
			}
			else
			{
				chiaki_mutex_lock(&presenter->mutex);
				bool paced = presenter->timestamped_release_enabled;
				chiaki_mutex_unlock(&presenter->mutex);
				if(paced)
					enqueue_paced_frame(presenter, (size_t)status, info);
				else
					eos = handle_direct_frame(presenter, (size_t)status, info) || eos;
			}
			if(eos)
			{
				CHIAKI_LOGI(presenter->log, "AMediaCodec reported EOS");
				break;
			}
			int64_t now_ns = monotonic_time_ns();
			if(presenter->performance_hint_report_cb)
				presenter->performance_hint_report_cb(presenter->performance_hint_cb_user,
						CHIAKI_THREAD_NAME_VIDEO_DECODER, (uint64_t)(now_ns - last_report_ns));
			last_report_ns = now_ns;
		}
		else
		{
			chiaki_mutex_lock(&presenter->mutex);
			bool shutdown = presenter->shutdown;
			chiaki_mutex_unlock(&presenter->mutex);
			if(shutdown)
				break;
		}
	}
	CHIAKI_LOGI(presenter->log, "Video Decoder Output Thread exiting");
	if(presenter->performance_hint_thread_stop_cb)
		presenter->performance_hint_thread_stop_cb(presenter->performance_hint_cb_user,
				CHIAKI_THREAD_NAME_VIDEO_DECODER);
	return NULL;
}

ChiakiErrorCode android_chiaki_video_presenter_init(AndroidChiakiVideoPresenter *presenter, ChiakiLog *log,
		bool late_frame_recovery_enabled, bool real_pts_enabled, bool diagnostics_enabled,
		AndroidChiakiVideoPresenterReleaseCallback release_cb, void *release_cb_user)
{
	memset(presenter, 0, sizeof(*presenter));
	presenter->log = log;
	presenter->late_frame_recovery_enabled = late_frame_recovery_enabled;
	presenter->real_pts_enabled = real_pts_enabled;
	presenter->diagnostics_enabled = diagnostics_enabled;
	presenter->release_cb = release_cb;
	presenter->release_cb_user = release_cb_user;
	presenter->dejitter_buffer_ns = VIDEO_PRESENTER_DJB_START_NS;
	ChiakiErrorCode err = chiaki_mutex_init(&presenter->mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;
	err = chiaki_cond_init(&presenter->queue_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		chiaki_mutex_fini(&presenter->mutex);
	return err;
}

void android_chiaki_video_presenter_fini(AndroidChiakiVideoPresenter *presenter)
{
	android_chiaki_video_presenter_request_stop(presenter);
	android_chiaki_video_presenter_join(presenter);
	chiaki_cond_fini(&presenter->queue_cond);
	chiaki_mutex_fini(&presenter->mutex);
}

void android_chiaki_video_presenter_set_performance_hint_callbacks(AndroidChiakiVideoPresenter *presenter,
		AndroidChiakiPerformanceHintThreadCallback start_cb,
		AndroidChiakiPerformanceHintReportCallback report_cb,
		AndroidChiakiPerformanceHintThreadCallback stop_cb, void *user)
{
	presenter->performance_hint_thread_start_cb = start_cb;
	presenter->performance_hint_report_cb = report_cb;
	presenter->performance_hint_thread_stop_cb = stop_cb;
	presenter->performance_hint_cb_user = user;
}

ChiakiErrorCode android_chiaki_video_presenter_start(AndroidChiakiVideoPresenter *presenter, AMediaCodec *codec,
		unsigned int stream_fps, double refresh_hz, int64_t app_vsync_offset_ns,
		AndroidChiakiVideoPacingMode mode, AndroidChiakiVideoPresenterLead lead_mode,
		uint32_t max_queue_age_periods)
{
	mode = sanitize_mode(mode);
	lead_mode = sanitize_lead_mode(lead_mode);
	chiaki_mutex_lock(&presenter->mutex);
	presenter->codec = codec;
	presenter->stream_fps = stream_fps > 0 ? stream_fps : 60;
	presenter->refresh_hz = refresh_hz > 1.0 ? refresh_hz : 60.0;
	presenter->app_vsync_offset_ns = app_vsync_offset_ns;
	presenter->vsync_period_ns = (int64_t)(1000000000.0 / presenter->refresh_hz + 0.5);
	presenter->mode = mode;
	presenter->lead_mode = lead_mode;
	presenter->max_queue_age_periods = max_queue_age_periods;
	presenter->timestamped_release_enabled = timestamped_release_eligible(mode, presenter->refresh_hz, presenter->stream_fps);
	presenter->shutdown = false;
	presenter->queue_head = 0;
	presenter->queue_size = 0;
	presenter->looper = NULL;
	presenter->choreographer = NULL;
	presenter->timeline_valid = false;
	presenter->last_vsync_ns = 0;
	presenter->dejitter_buffer_ns = VIDEO_PRESENTER_DJB_START_NS;
	presenter->last_arrival_ns = 0;
	presenter->arrival_offset_count = 0;
	presenter->arrival_offset_next = 0;
	presenter->samples_since_adjustment = 0;
	presenter->decrease_hysteresis = 0;
	presenter->missed_vsyncs = 0;
	presenter->dropped_frames = 0;
	presenter->bounded_age_dropped_frames = 0;
	presenter->diagnostics_input_next = 0;
	presenter->diagnostics_decode_count = 0;
	presenter->diagnostics_decode_next = 0;
	presenter->diagnostics_output_frames = 0;
	memset(presenter->diagnostics_inputs, 0, sizeof(presenter->diagnostics_inputs));
	chiaki_mutex_unlock(&presenter->mutex);

	if(mode != ANDROID_CHIAKI_VIDEO_PACING_DISABLED && presenter->refresh_hz >= 119.0)
		CHIAKI_LOGI(presenter->log, "Video presenter %s mode using immediate release: %.2f Hz display (timestamped release disabled at 120 Hz)",
				mode_name(mode), presenter->refresh_hz);
	else
		CHIAKI_LOGI(presenter->log, "Video presenter %s mode: stream=%u fps display=%.2f Hz timestamped_release=%s offset=%.3f ms lead=%.3f ms bounded_age=%u periods",
				mode_name(mode), presenter->stream_fps, presenter->refresh_hz,
				presenter->timestamped_release_enabled ? "enabled" : "disabled",
				(double)presenter->app_vsync_offset_ns / 1000000.0,
				(double)presenter_lead_ns(presenter) / 1000000.0,
				presenter->max_queue_age_periods);

	start_vsync_thread_if_needed(presenter);
	ChiakiErrorCode err = chiaki_thread_create(&presenter->output_thread, output_thread_func, presenter);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		android_chiaki_video_presenter_request_stop(presenter);
		android_chiaki_video_presenter_join(presenter);
		return err;
	}
	presenter->output_thread_started = true;
	chiaki_thread_set_name(&presenter->output_thread, "ChiakiVideoOut");
	return CHIAKI_ERR_SUCCESS;
}

void android_chiaki_video_presenter_request_stop(AndroidChiakiVideoPresenter *presenter)
{
	chiaki_mutex_lock(&presenter->mutex);
	presenter->shutdown = true;
	while(presenter->queue_size > 0)
	{
		AndroidChiakiVideoPresenterFrame frame;
		queue_pop(presenter, &frame);
		if(presenter->codec)
			release_frame_locked(presenter, &frame, false, 0);
	}
	chiaki_cond_broadcast(&presenter->queue_cond);
	ALooper *looper = presenter->looper;
	chiaki_mutex_unlock(&presenter->mutex);
	if(looper)
		ALooper_wake(looper);
}

void android_chiaki_video_presenter_join(AndroidChiakiVideoPresenter *presenter)
{
	if(presenter->output_thread_started)
	{
		chiaki_thread_join(&presenter->output_thread, NULL);
		presenter->output_thread_started = false;
	}
	if(presenter->vsync_thread_started)
	{
		chiaki_thread_join(&presenter->vsync_thread, NULL);
		presenter->vsync_thread_started = false;
	}
	chiaki_mutex_lock(&presenter->mutex);
	presenter->codec = NULL;
	presenter->looper = NULL;
	presenter->choreographer = NULL;
	chiaki_mutex_unlock(&presenter->mutex);
}

void android_chiaki_video_presenter_set_mode(AndroidChiakiVideoPresenter *presenter,
		AndroidChiakiVideoPacingMode mode)
{
	mode = sanitize_mode(mode);
	chiaki_mutex_lock(&presenter->mutex);
	presenter->mode = mode;
	presenter->timestamped_release_enabled = timestamped_release_eligible(mode, presenter->refresh_hz, presenter->stream_fps);
	presenter->timeline_valid = false;
	if(!presenter->real_pts_enabled)
		presenter->last_arrival_ns = 0;
	if(!presenter->timestamped_release_enabled)
		drain_immediate_locked(presenter);
	chiaki_cond_broadcast(&presenter->queue_cond);
	ALooper *looper = presenter->looper;
	bool timestamped = presenter->timestamped_release_enabled;
	chiaki_mutex_unlock(&presenter->mutex);
	if(looper)
		ALooper_wake(looper);
	CHIAKI_LOGI(presenter->log, "Video presenter switched to %s mode; timestamped_release=%s",
			mode_name(mode), timestamped ? "enabled" : "disabled");
	start_vsync_thread_if_needed(presenter);
}

void android_chiaki_video_presenter_set_timing(AndroidChiakiVideoPresenter *presenter,
		unsigned int stream_fps, double refresh_hz, int64_t app_vsync_offset_ns,
		AndroidChiakiVideoPacingMode mode, AndroidChiakiVideoPresenterLead lead_mode,
		uint32_t max_queue_age_periods)
{
	mode = sanitize_mode(mode);
	lead_mode = sanitize_lead_mode(lead_mode);
	chiaki_mutex_lock(&presenter->mutex);
	presenter->stream_fps = stream_fps > 0 ? stream_fps : 60;
	presenter->refresh_hz = refresh_hz > 1.0 ? refresh_hz : 60.0;
	presenter->app_vsync_offset_ns = app_vsync_offset_ns;
	presenter->vsync_period_ns = (int64_t)(1000000000.0 / presenter->refresh_hz + 0.5);
	presenter->mode = mode;
	presenter->lead_mode = lead_mode;
	presenter->max_queue_age_periods = max_queue_age_periods;
	presenter->timestamped_release_enabled = timestamped_release_eligible(mode, presenter->refresh_hz, presenter->stream_fps);
	presenter->timeline_valid = false;
	presenter->last_vsync_ns = 0;
	if(!presenter->real_pts_enabled)
		presenter->last_arrival_ns = 0;
	if(!presenter->timestamped_release_enabled)
		drain_immediate_locked(presenter);
	chiaki_cond_broadcast(&presenter->queue_cond);
	ALooper *looper = presenter->looper;
	bool timestamped = presenter->timestamped_release_enabled;
	chiaki_mutex_unlock(&presenter->mutex);
	if(looper)
		ALooper_wake(looper);
	if(mode != ANDROID_CHIAKI_VIDEO_PACING_DISABLED && refresh_hz >= 119.0)
		CHIAKI_LOGI(presenter->log, "Video presenter using immediate release at %.2f Hz; timestamped release is disabled at 120 Hz", refresh_hz);
	else
		CHIAKI_LOGI(presenter->log, "Video presenter timing updated: mode=%s stream=%u fps display=%.2f Hz timestamped_release=%s lead=%.3f ms bounded_age=%u periods",
				mode_name(mode), presenter->stream_fps, presenter->refresh_hz, timestamped ? "enabled" : "disabled",
				(double)presenter_lead_ns(presenter) / 1000000.0,
				presenter->max_queue_age_periods);
	start_vsync_thread_if_needed(presenter);
}

void android_chiaki_video_presenter_get_stats(AndroidChiakiVideoPresenter *presenter,
		AndroidChiakiVideoPresenterStats *stats)
{
	chiaki_mutex_lock(&presenter->mutex);
	stats->missed_vsyncs = presenter->missed_vsyncs;
	stats->dropped_frames = presenter->dropped_frames;
	stats->dejitter_buffer_ns = presenter->dejitter_buffer_ns;
	stats->queue_depth = presenter->queue_size;
	chiaki_mutex_unlock(&presenter->mutex);
}

void android_chiaki_video_presenter_record_input_queued(AndroidChiakiVideoPresenter *presenter,
		int64_t presentation_time_us, int64_t queued_ns)
{
	if(!presenter->diagnostics_enabled)
		return;
	chiaki_mutex_lock(&presenter->mutex);
	AndroidChiakiVideoInputTimestamp *input =
			&presenter->diagnostics_inputs[presenter->diagnostics_input_next];
	input->presentation_time_us = presentation_time_us;
	input->queued_ns = queued_ns;
	input->valid = true;
	presenter->diagnostics_input_next = (presenter->diagnostics_input_next + 1)
			% ANDROID_CHIAKI_VIDEO_DIAGNOSTICS_CAPACITY;
	chiaki_mutex_unlock(&presenter->mutex);
}

void android_chiaki_video_presenter_get_diagnostics(AndroidChiakiVideoPresenter *presenter,
		AndroidChiakiVideoPresenterDiagnostics *diagnostics)
{
	memset(diagnostics, 0, sizeof(*diagnostics));
	chiaki_mutex_lock(&presenter->mutex);
	diagnostics->output_frames = presenter->diagnostics_output_frames;
	diagnostics->missed_vsyncs = presenter->missed_vsyncs;
	diagnostics->dropped_frames = presenter->dropped_frames;
	diagnostics->bounded_age_dropped_frames = presenter->bounded_age_dropped_frames;
	diagnostics->dejitter_buffer_ns = presenter->dejitter_buffer_ns;
	diagnostics->queue_depth = presenter->queue_size;
	uint32_t count = presenter->diagnostics_decode_count;
	uint64_t samples[ANDROID_CHIAKI_VIDEO_DIAGNOSTICS_CAPACITY];
	uint64_t sum = 0;
	for(uint32_t i = 0; i < count; i++)
	{
		samples[i] = presenter->diagnostics_decode_ns[i];
		sum += samples[i];
	}
	if(count > 0)
	{
		qsort(samples, count, sizeof(samples[0]), compare_u64);
		uint32_t p95_index = (95 * count + 99) / 100;
		if(p95_index > 0)
			p95_index--;
		diagnostics->decode_mean_us = sum / count / 1000;
		diagnostics->decode_p95_us = samples[p95_index] / 1000;
	}
	presenter->diagnostics_output_frames = 0;
	presenter->diagnostics_decode_count = 0;
	presenter->diagnostics_decode_next = 0;
	chiaki_mutex_unlock(&presenter->mutex);
}
