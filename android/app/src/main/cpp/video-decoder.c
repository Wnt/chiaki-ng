// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>

#include <chiaki/time.h>

#include <inttypes.h>
#include <stdlib.h>
#include <string.h>

#define INPUT_BUFFER_TIMEOUT_MS 10
#define OUTPUT_BACKLOG_IDR_THRESHOLD 5

#define DECODER_CONFIGURE_BASELINE_TIER 3
#define DECODER_CONFIGURE_PERFORMANCE_FALLBACK_TIER 4
// PLE-75: with real 60 fps timestamps the Exynos MFC decoder clocks itself for 60 fps and takes
// 14 ms per frame (p95 30 ms) instead of the 8 ms it takes when the timestamps are 1 us apart.
// An explicit operating-rate of 480 restores 8 ms (120 and 240 only get part of the way back);
// see docs/verification/PTS-decode-latency.md.
#define DECODER_REAL_PTS_OPERATING_RATE 480
#define DECODER_LOW_LATENCY_OPERATING_RATE 480

extern media_status_t AMediaCodec_getName_weak(AMediaCodec *codec, char **out_name)
		__asm__("AMediaCodec_getName") __attribute__((weak));
extern void AMediaCodec_releaseName_weak(AMediaCodec *codec, char *name)
		__asm__("AMediaCodec_releaseName") __attribute__((weak));

static void *android_chiaki_video_decoder_input_thread_func(void *user);
static bool android_chiaki_video_decoder_queue_sample(AndroidChiakiVideoDecoder *decoder,
		uint8_t *buf, size_t buf_size, ChiakiSeqNum16 frame_index,
		uint64_t frame_ready_time_us);
static void android_chiaki_video_decoder_presenter_release(void *user, bool dropped);

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height,
		int32_t target_fps, ChiakiCodec codec, bool low_latency_enabled, bool real_pts_enabled,
		bool input_thread_enabled, bool late_frame_recovery_enabled, bool performance_mode_enabled,
		int32_t operating_rate, bool operating_rate_auto, bool realtime_priority, unsigned int pts_rate_hz,
		bool diagnostics_enabled)
{
	decoder->log = log;
	decoder->codec = NULL;
	decoder->timestamp_cur = 0;
	decoder->fps = target_fps > 0 ? (unsigned int)target_fps : 60;
	decoder->real_pts_enabled = real_pts_enabled;
	// PLE-75 experiment: the PTS timeline may run at a rate other than the stream fps
	// (0 = stream fps, today's behaviour) to test whether the codec keys off timestamp spacing.
	decoder->pts_rate_hz = pts_rate_hz > 0 ? pts_rate_hz : decoder->fps;
	chiaki_seq_num_16_unwrapper_init(&decoder->frame_index_unwrapper);
	if(real_pts_enabled)
		CHIAKI_LOGI(log, "Frame-index video timestamps enabled at %u fps (PTS timeline %u Hz)", decoder->fps, decoder->pts_rate_hz);
	decoder->target_width = target_width;
	decoder->target_height = target_height;
	decoder->target_fps = target_fps;
	decoder->target_codec = codec;
	decoder->low_latency_enabled = low_latency_enabled;
	decoder->performance_mode_enabled = performance_mode_enabled;
	// PLE-75: explicit operating-rate (0 = unset) and realtime priority (false = unset). With real PTS
	// and no explicit value, the auto switch (default on) requests DECODER_REAL_PTS_OPERATING_RATE.
	decoder->operating_rate = operating_rate > 0 ? operating_rate : 0;
	decoder->operating_rate_auto = false;
	if(decoder->operating_rate == 0 && real_pts_enabled && operating_rate_auto)
	{
		decoder->operating_rate = DECODER_REAL_PTS_OPERATING_RATE;
		decoder->operating_rate_auto = true;
	}
	decoder->realtime_priority = realtime_priority;
	decoder->diagnostics_enabled = diagnostics_enabled;
	decoder->late_frame_recovery_enabled = late_frame_recovery_enabled;
	decoder->last_queued_frame_index_valid = false;
	decoder->output_backlog = 0;
	decoder->output_frames_released = 0;
	decoder->output_frames_dropped = 0;
	decoder->backlog_idr_requested = false;
	decoder->request_idr_cb = NULL;
	decoder->request_idr_cb_user = NULL;
	decoder->input_thread_enabled = input_thread_enabled;
	decoder->shutdown_input = false;
	decoder->input_pending = false;
	decoder->input_buf = NULL;
	decoder->input_buf_size = 0;
	decoder->input_buf_capacity = 0;
	decoder->input_frame_ready_time_us = 0;
	decoder->input_frames_dropped = 0;

	ChiakiErrorCode err = chiaki_mutex_init(&decoder->codec_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;
	err = chiaki_mutex_init(&decoder->stats_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_mutex_fini(&decoder->codec_mutex);
		return err;
	}
	err = chiaki_mutex_init(&decoder->input_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_mutex_fini(&decoder->stats_mutex);
		chiaki_mutex_fini(&decoder->codec_mutex);
		return err;
	}
	err = android_chiaki_video_presenter_init(&decoder->presenter, log, late_frame_recovery_enabled,
			real_pts_enabled, diagnostics_enabled, android_chiaki_video_decoder_presenter_release, decoder);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_mutex_fini(&decoder->input_mutex);
		chiaki_mutex_fini(&decoder->stats_mutex);
		chiaki_mutex_fini(&decoder->codec_mutex);
		return err;
	}
	if(!input_thread_enabled)
		return CHIAKI_ERR_SUCCESS;

	err = chiaki_cond_init(&decoder->input_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		goto error_input_mutex;
	err = chiaki_thread_create(&decoder->input_thread, android_chiaki_video_decoder_input_thread_func, decoder);
	if(err != CHIAKI_ERR_SUCCESS)
		goto error_input_cond;
	chiaki_thread_set_name(&decoder->input_thread, "ChiakiVideoIn");
	CHIAKI_LOGI(log, "Decoder input thread enabled with latest-frame handoff");
	return CHIAKI_ERR_SUCCESS;

error_input_cond:
	chiaki_cond_fini(&decoder->input_cond);
error_input_mutex:
	android_chiaki_video_presenter_fini(&decoder->presenter);
	chiaki_mutex_fini(&decoder->input_mutex);
	chiaki_mutex_fini(&decoder->stats_mutex);
	chiaki_mutex_fini(&decoder->codec_mutex);
	return err;
}

void android_chiaki_video_decoder_set_request_idr_cb(AndroidChiakiVideoDecoder *decoder,
		AndroidChiakiVideoDecoderRequestIDRCallback cb, void *user)
{
	chiaki_mutex_lock(&decoder->stats_mutex);
	decoder->request_idr_cb = cb;
	decoder->request_idr_cb_user = user;
	chiaki_mutex_unlock(&decoder->stats_mutex);
}

static void reset_output_stats(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->stats_mutex);
	decoder->last_queued_frame_index_valid = false;
	decoder->output_backlog = 0;
	decoder->output_frames_released = 0;
	decoder->output_frames_dropped = 0;
	decoder->backlog_idr_requested = false;
	chiaki_mutex_unlock(&decoder->stats_mutex);
}

static void record_output_frame(AndroidChiakiVideoDecoder *decoder, bool dropped,
		uint64_t *backlog, uint64_t *released, uint64_t *dropped_total)
{
	chiaki_mutex_lock(&decoder->stats_mutex);
	if(decoder->output_backlog > 0)
		decoder->output_backlog--;
	decoder->output_frames_released++;
	if(dropped)
		decoder->output_frames_dropped++;
	if(decoder->output_backlog < OUTPUT_BACKLOG_IDR_THRESHOLD)
		decoder->backlog_idr_requested = false;
	*backlog = decoder->output_backlog;
	*released = decoder->output_frames_released;
	*dropped_total = decoder->output_frames_dropped;
	chiaki_mutex_unlock(&decoder->stats_mutex);
}

static void android_chiaki_video_decoder_presenter_release(void *user, bool dropped)
{
	AndroidChiakiVideoDecoder *decoder = user;
	if(!decoder->late_frame_recovery_enabled)
		return;
	uint64_t backlog;
	uint64_t released;
	uint64_t dropped_total;
	record_output_frame(decoder, dropped, &backlog, &released, &dropped_total);
	if(dropped)
		CHIAKI_LOGW(decoder->log, "Dropped stale decoder output frame: backlog=%" PRIu64 " dropped=%" PRIu64,
				backlog, dropped_total);
	else if(released % decoder->fps == 0)
		CHIAKI_LOGI(decoder->log, "Video decoder output stats: backlog=%" PRIu64 " dropped=%" PRIu64,
				backlog, dropped_total);
}

static AMediaFormat *create_decoder_format(const AndroidChiakiVideoDecoder *decoder, const char *mime, int tier, bool qti_decoder)
{
	AMediaFormat *format = AMediaFormat_new();
	if(!format)
		return NULL;

	AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, decoder->target_height);

	if(tier <= 2)
	{
		AMediaFormat_setInt32(format, "frame-rate", decoder->target_fps);
		if(qti_decoder)
			AMediaFormat_setInt32(format, "vendor.qti-ext-dec-picture-order.enable", 1);
	}
	else if(decoder->performance_mode_enabled && tier <= DECODER_CONFIGURE_BASELINE_TIER)
	{
		AMediaFormat_setInt32(format, "frame-rate", decoder->target_fps);
	}
	if(tier <= 1)
		AMediaFormat_setInt32(format, "operating-rate", DECODER_LOW_LATENCY_OPERATING_RATE);
	else if(decoder->performance_mode_enabled && tier <= DECODER_CONFIGURE_BASELINE_TIER)
		AMediaFormat_setInt32(format, "operating-rate", decoder->target_fps * 4);
	if(tier == 0)
		AMediaFormat_setInt32(format, "low-latency", 1);

	// PLE-75: explicit overrides win over the tier defaults at every tier.
	if(decoder->operating_rate > 0)
	{
		AMediaFormat_setInt32(format, "frame-rate", decoder->target_fps);
		AMediaFormat_setInt32(format, "operating-rate", decoder->operating_rate);
	}
	if(decoder->realtime_priority)
		AMediaFormat_setInt32(format, "priority", 0);

	return format;
}

static bool kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->codec_mutex);
	if(!decoder->codec)
	{
		chiaki_mutex_unlock(&decoder->codec_mutex);
		return false;
	}
	android_chiaki_video_presenter_request_stop(&decoder->presenter);
	ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);
	if(codec_buf_index >= 0)
	{
		CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
		AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, decoder->timestamp_cur++, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
	}
	else
		CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
	AMediaCodec_stop(decoder->codec);
	chiaki_mutex_unlock(&decoder->codec_mutex);
	android_chiaki_video_presenter_join(&decoder->presenter);
	if(decoder->late_frame_recovery_enabled)
	{
		chiaki_mutex_lock(&decoder->stats_mutex);
		CHIAKI_LOGI(decoder->log, "Video decoder final output stats: backlog=%" PRIu64 " dropped=%" PRIu64,
				decoder->output_backlog, decoder->output_frames_dropped);
		chiaki_mutex_unlock(&decoder->stats_mutex);
	}
	chiaki_mutex_lock(&decoder->codec_mutex);
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;
	ANativeWindow_release(decoder->window);
	decoder->window = NULL;
	chiaki_mutex_unlock(&decoder->codec_mutex);
	return true;
}

void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder)
{
	if(decoder->input_thread_enabled)
	{
		chiaki_mutex_lock(&decoder->input_mutex);
		decoder->shutdown_input = true;
		decoder->input_pending = false;
		chiaki_cond_signal(&decoder->input_cond);
		chiaki_mutex_unlock(&decoder->input_mutex);
		chiaki_thread_join(&decoder->input_thread, NULL);
		chiaki_cond_fini(&decoder->input_cond);
	}
	if(decoder->codec)
		kill_decoder(decoder);
	android_chiaki_video_presenter_fini(&decoder->presenter);
	free(decoder->input_buf);
	chiaki_mutex_fini(&decoder->input_mutex);
	chiaki_mutex_fini(&decoder->stats_mutex);
	chiaki_mutex_fini(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface,
		unsigned int stream_fps, double refresh_hz, int64_t app_vsync_offset_ns,
		AndroidChiakiVideoPacingMode pacing_mode, AndroidChiakiVideoPresenterLead presenter_lead,
		uint32_t max_queue_age_periods, bool nonblocking_producer)
{
	chiaki_mutex_lock(&decoder->codec_mutex);

	if(!surface)
	{
		chiaki_mutex_unlock(&decoder->codec_mutex);
		if(kill_decoder(decoder))
			CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
		return;
	}

	if(decoder->codec)
	{
#if __ANDROID_API__ >= 23
		CHIAKI_LOGI(decoder->log, "Video decoder already initialized, swapping surface");
		ANativeWindow *new_window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
		AMediaCodec_setOutputSurface(decoder->codec, new_window);
		ANativeWindow_release(decoder->window);
		decoder->window = new_window;
		android_chiaki_video_presenter_set_timing(&decoder->presenter, stream_fps, refresh_hz,
				app_vsync_offset_ns, pacing_mode, presenter_lead, max_queue_age_periods,
				nonblocking_producer);
#else
		CHIAKI_LOGE(decoder->log, "Video Decoder already initialized");
#endif
		goto beach;
	}

	decoder->window = ANativeWindow_fromSurface(env, surface);

	const char *mime = chiaki_codec_is_h265(decoder->target_codec) ? "video/hevc" : "video/avc";
	CHIAKI_LOGI(decoder->log, "Initializing decoder with mime %s", mime);

	decoder->codec = AMediaCodec_createDecoderByType(mime);
	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create AMediaCodec for mime type %s", mime);
		goto error_surface;
	}

	char *decoder_name_allocated = NULL;
	const char *decoder_name = "unknown (API < 28)";
	if(AMediaCodec_getName_weak && AMediaCodec_releaseName_weak
			&& AMediaCodec_getName_weak(decoder->codec, &decoder_name_allocated) == AMEDIA_OK
			&& decoder_name_allocated)
		decoder_name = decoder_name_allocated;
	CHIAKI_LOGI(decoder->log, "Video decoder component: %s", decoder_name);

	bool qti_decoder = strncmp(decoder_name, "c2.qti.", strlen("c2.qti.")) == 0;
	if(decoder->performance_mode_enabled)
		CHIAKI_LOGI(decoder->log, "Stream performance mode requesting MediaCodec operating-rate=%d",
				decoder->target_fps * 4);
	if(decoder->operating_rate > 0)
		CHIAKI_LOGI(decoder->log, "Decoder operating-rate override: operating-rate=%d frame-rate=%d%s",
				decoder->operating_rate, decoder->target_fps,
				decoder->operating_rate_auto ? " (auto for frame-index timestamps)" : "");
	if(decoder->realtime_priority)
		CHIAKI_LOGI(decoder->log, "Decoder realtime priority override: priority=0");
	int first_tier = decoder->low_latency_enabled ? 0 : DECODER_CONFIGURE_BASELINE_TIER;
	int last_tier = decoder->performance_mode_enabled
			? DECODER_CONFIGURE_PERFORMANCE_FALLBACK_TIER : DECODER_CONFIGURE_BASELINE_TIER;
	media_status_t r = AMEDIA_ERROR_UNKNOWN;
	AMediaFormat *format = NULL;
	int configured_tier = -1;
	for(int tier = first_tier; tier <= last_tier; tier++)
	{
		format = create_decoder_format(decoder, mime, tier, qti_decoder);
		if(!format)
			break;
		r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
		AMediaFormat_delete(format);
		format = NULL;
		if(r == AMEDIA_OK)
		{
			configured_tier = tier;
			break;
		}
		CHIAKI_LOGW(decoder->log, "AMediaCodec_configure() tier %d failed for %s: %d", tier, decoder_name, (int)r);
	}
	if(configured_tier < 0)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_configure() failed for %s after fallback: %d", decoder_name, (int)r);
		if(decoder_name_allocated)
			AMediaCodec_releaseName_weak(decoder->codec, decoder_name_allocated);
		goto error_codec;
	}
	CHIAKI_LOGI(decoder->log, "AMediaCodec_configure() succeeded for %s at tier %d%s%s", decoder_name, configured_tier,
			decoder->low_latency_enabled ? "" : " (low-latency setting disabled)",
			configured_tier == DECODER_CONFIGURE_PERFORMANCE_FALLBACK_TIER
					? " (performance format unsupported; baseline fallback)" : "");
	if(decoder_name_allocated)
		AMediaCodec_releaseName_weak(decoder->codec, decoder_name_allocated);

	r = AMediaCodec_start(decoder->codec);
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_start() failed: %d", (int)r);
		goto error_codec;
	}
	reset_output_stats(decoder);
	CHIAKI_LOGI(decoder->log, "Decoder late-frame recovery %s (IDR backlog threshold %d)",
			decoder->late_frame_recovery_enabled ? "enabled" : "disabled", OUTPUT_BACKLOG_IDR_THRESHOLD);

	ChiakiErrorCode err = android_chiaki_video_presenter_start(&decoder->presenter, decoder->codec, stream_fps,
			refresh_hz, app_vsync_offset_ns, pacing_mode, presenter_lead, max_queue_age_periods,
			nonblocking_producer);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(decoder->log, "Failed to start video presenter: %s", chiaki_error_string(err));
		AMediaCodec_stop(decoder->codec);
		goto error_codec;
	}

	goto beach;

error_codec:
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;

error_surface:
	ANativeWindow_release(decoder->window);
	decoder->window = NULL;

beach:
	chiaki_mutex_unlock(&decoder->codec_mutex);
}

static bool android_chiaki_video_decoder_queue_sample(AndroidChiakiVideoDecoder *decoder,
		uint8_t *buf, size_t buf_size, ChiakiSeqNum16 frame_index,
		uint64_t frame_ready_time_us)
{
	bool r = true;
	bool request_idr = false;
	uint64_t backlog = 0;
	AndroidChiakiVideoDecoderRequestIDRCallback request_idr_cb = NULL;
	void *request_idr_cb_user = NULL;
	bool backlog_recorded = false;
	chiaki_mutex_lock(&decoder->codec_mutex);

	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Received video data, but decoder is not available!");
		goto beach;
	}

	uint64_t presentation_time_us = decoder->timestamp_cur;
	if(decoder->real_pts_enabled)
	{
		uint64_t unwrapped_frame_index = chiaki_seq_num_16_unwrap(&decoder->frame_index_unwrapper, frame_index);
		presentation_time_us = unwrapped_frame_index * 1000000ULL / decoder->pts_rate_hz;
	}

	bool first_chunk = true;
	while(buf_size > 0)
	{
		ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, INPUT_BUFFER_TIMEOUT_MS * 1000);
		if(codec_buf_index < 0)
		{
			CHIAKI_LOGE(decoder->log, "Failed to get input buffer");
			r = false;
			goto beach;
		}

		size_t codec_buf_size;
		uint8_t *codec_buf = AMediaCodec_getInputBuffer(decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
		size_t codec_sample_size = buf_size;
		if(codec_sample_size > codec_buf_size)
		{
			//CHIAKI_LOGD(decoder->log, "Sample is bigger than buffer, splitting");
			codec_sample_size = codec_buf_size;
		}
		memcpy(codec_buf, buf, codec_sample_size);

		bool backlog_incremented = false;
		bool stats_locked = false;
		bool previous_frame_index_valid = false;
		ChiakiSeqNum16 previous_frame_index = 0;
		if(decoder->late_frame_recovery_enabled && !backlog_recorded)
		{
			chiaki_mutex_lock(&decoder->stats_mutex);
			stats_locked = true;
			previous_frame_index_valid = decoder->last_queued_frame_index_valid;
			previous_frame_index = decoder->last_queued_frame_index;
			if(!previous_frame_index_valid || previous_frame_index != frame_index)
			{
				decoder->last_queued_frame_index = frame_index;
				decoder->last_queued_frame_index_valid = true;
				decoder->output_backlog++;
				backlog_incremented = true;
			}
			backlog = decoder->output_backlog;
			if(backlog >= OUTPUT_BACKLOG_IDR_THRESHOLD && !decoder->backlog_idr_requested)
			{
				decoder->backlog_idr_requested = true;
				request_idr = true;
				request_idr_cb = decoder->request_idr_cb;
				request_idr_cb_user = decoder->request_idr_cb_user;
			}
			backlog_recorded = true;
		}

		int64_t queued_ns = first_chunk
				? (int64_t)chiaki_time_now_monotonic_us() * 1000LL : 0;
		media_status_t queue_result = AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, codec_sample_size, presentation_time_us, 0);
		if(queue_result == AMEDIA_OK && first_chunk)
			android_chiaki_video_presenter_record_input_queued(&decoder->presenter,
					(int64_t)presentation_time_us, queued_ns, frame_index,
					frame_ready_time_us);
		first_chunk = false;
		if(stats_locked)
		{
			if(queue_result != AMEDIA_OK && backlog_incremented)
			{
				decoder->output_backlog--;
				decoder->last_queued_frame_index_valid = previous_frame_index_valid;
				decoder->last_queued_frame_index = previous_frame_index;
				if(request_idr)
				{
					decoder->backlog_idr_requested = false;
					request_idr = false;
				}
			}
			chiaki_mutex_unlock(&decoder->stats_mutex);
		}
		if(queue_result != AMEDIA_OK)
		{
			CHIAKI_LOGE(decoder->log, "AMediaCodec_queueInputBuffer() failed: %d", (int)queue_result);
		}
		buf += codec_sample_size;
		buf_size -= codec_sample_size;
		if(!decoder->real_pts_enabled)
			presentation_time_us++;

	}
	decoder->timestamp_cur = presentation_time_us;

beach:
	chiaki_mutex_unlock(&decoder->codec_mutex);
	if(request_idr)
	{
		ChiakiErrorCode err = request_idr_cb ? request_idr_cb(request_idr_cb_user) : CHIAKI_ERR_UNINITIALIZED;
		if(err == CHIAKI_ERR_SUCCESS)
		{
			CHIAKI_LOGW(decoder->log, "Video decoder backlog reached %" PRIu64 "; requested IDR frame", backlog);
		}
		else
		{
			CHIAKI_LOGW(decoder->log, "Video decoder backlog reached %" PRIu64 "; IDR request failed: %s",
					backlog, chiaki_error_string(err));
			chiaki_mutex_lock(&decoder->stats_mutex);
			decoder->backlog_idr_requested = false;
			chiaki_mutex_unlock(&decoder->stats_mutex);
		}
	}
	return r;
}

static void *android_chiaki_video_decoder_input_thread_func(void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;
	uint8_t *local_buf = NULL;
	size_t local_buf_capacity = 0;

	while(true)
	{
		chiaki_mutex_lock(&decoder->input_mutex);
		while(!decoder->input_pending && !decoder->shutdown_input)
			chiaki_cond_wait(&decoder->input_cond, &decoder->input_mutex);
		if(decoder->shutdown_input)
		{
			chiaki_mutex_unlock(&decoder->input_mutex);
			break;
		}

		uint8_t *swap_buf = local_buf;
		local_buf = decoder->input_buf;
		decoder->input_buf = swap_buf;
		size_t swap_capacity = local_buf_capacity;
		local_buf_capacity = decoder->input_buf_capacity;
		decoder->input_buf_capacity = swap_capacity;
		size_t local_buf_size = decoder->input_buf_size;
		ChiakiSeqNum16 frame_index = decoder->input_frame_index;
		uint64_t frame_ready_time_us = decoder->input_frame_ready_time_us;
		decoder->input_pending = false;
		chiaki_mutex_unlock(&decoder->input_mutex);

		android_chiaki_video_decoder_queue_sample(decoder, local_buf, local_buf_size,
				frame_index, frame_ready_time_us);
	}

	free(local_buf);
	CHIAKI_LOGI(decoder->log, "Video Decoder Input Thread exiting");
	return NULL;
}

bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size,
		ChiakiSeqNum16 frame_index, uint64_t frame_ready_time_us, int32_t frames_lost,
		bool frame_recovered, void *user)
{
	(void)frames_lost;
	(void)frame_recovered;
	AndroidChiakiVideoDecoder *decoder = user;
	if(!decoder->input_thread_enabled)
		return android_chiaki_video_decoder_queue_sample(decoder, buf, buf_size,
				frame_index, frame_ready_time_us);

	chiaki_mutex_lock(&decoder->input_mutex);
	if(decoder->shutdown_input)
	{
		chiaki_mutex_unlock(&decoder->input_mutex);
		return false;
	}
	if(decoder->input_buf_capacity < buf_size)
	{
		uint8_t *new_buf = realloc(decoder->input_buf, buf_size);
		if(!new_buf)
		{
			chiaki_mutex_unlock(&decoder->input_mutex);
			CHIAKI_LOGE(decoder->log, "Failed to grow decoder input handoff slot to %zu bytes", buf_size);
			return false;
		}
		decoder->input_buf = new_buf;
		decoder->input_buf_capacity = buf_size;
	}
	if(decoder->input_pending)
		decoder->input_frames_dropped++;
	memcpy(decoder->input_buf, buf, buf_size);
	decoder->input_buf_size = buf_size;
	decoder->input_frame_index = frame_index;
	decoder->input_frame_ready_time_us = frame_ready_time_us;
	decoder->input_pending = true;
	chiaki_cond_signal(&decoder->input_cond);
	chiaki_mutex_unlock(&decoder->input_mutex);
	return true;
}

void android_chiaki_video_decoder_get_stats(AndroidChiakiVideoDecoder *decoder, AndroidChiakiVideoStats *stats)
{
	chiaki_mutex_lock(&decoder->input_mutex);
	stats->input_frames_dropped = decoder->input_frames_dropped;
	chiaki_mutex_unlock(&decoder->input_mutex);
	AndroidChiakiVideoPresenterStats presenter_stats;
	android_chiaki_video_presenter_get_stats(&decoder->presenter, &presenter_stats);
	stats->missed_vsyncs = presenter_stats.missed_vsyncs;
	stats->presenter_frames_dropped = presenter_stats.dropped_frames;
	stats->dejitter_buffer_ns = presenter_stats.dejitter_buffer_ns;
}

void android_chiaki_video_decoder_get_diagnostics(AndroidChiakiVideoDecoder *decoder,
		AndroidChiakiVideoDiagnostics *diagnostics)
{
	memset(diagnostics, 0, sizeof(*diagnostics));
	chiaki_mutex_lock(&decoder->input_mutex);
	diagnostics->input_frames_dropped = decoder->input_frames_dropped;
	chiaki_mutex_unlock(&decoder->input_mutex);
	AndroidChiakiVideoPresenterDiagnostics presenter;
	android_chiaki_video_presenter_get_diagnostics(&decoder->presenter, &presenter);
	diagnostics->output_frames = presenter.output_frames;
	diagnostics->decode_mean_us = presenter.decode_mean_us;
	diagnostics->decode_p95_us = presenter.decode_p95_us;
	diagnostics->missed_vsyncs = presenter.missed_vsyncs;
	diagnostics->presenter_frames_dropped = presenter.dropped_frames;
	diagnostics->presenter_bounded_age_frames_dropped = presenter.bounded_age_dropped_frames;
	diagnostics->dejitter_buffer_ns = presenter.dejitter_buffer_ns;
	diagnostics->presenter_queue_depth = presenter.queue_depth;
}

void android_chiaki_video_decoder_set_pacing_mode(AndroidChiakiVideoDecoder *decoder,
		AndroidChiakiVideoPacingMode pacing_mode)
{
	android_chiaki_video_presenter_set_mode(&decoder->presenter, pacing_mode);
}
