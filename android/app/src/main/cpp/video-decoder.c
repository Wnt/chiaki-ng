// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>

#include <inttypes.h>
#include <string.h>

#define INPUT_BUFFER_TIMEOUT_MS 10
#define OUTPUT_BACKLOG_IDR_THRESHOLD 5

#define DECODER_CONFIGURE_TIER_COUNT 4

extern media_status_t AMediaCodec_getName_weak(AMediaCodec *codec, char **out_name)
		__asm__("AMediaCodec_getName") __attribute__((weak));
extern void AMediaCodec_releaseName_weak(AMediaCodec *codec, char *name)
		__asm__("AMediaCodec_releaseName") __attribute__((weak));

static void *android_chiaki_video_decoder_output_thread_func(void *user);

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height,
		int32_t target_fps, ChiakiCodec codec, bool low_latency_enabled, bool real_pts_enabled, bool late_frame_recovery_enabled)
{
	decoder->log = log;
	decoder->codec = NULL;
	decoder->timestamp_cur = 0;
	decoder->fps = target_fps > 0 ? (unsigned int)target_fps : 60;
	decoder->real_pts_enabled = real_pts_enabled;
	chiaki_seq_num_16_unwrapper_init(&decoder->frame_index_unwrapper);
	if(real_pts_enabled)
		CHIAKI_LOGI(log, "Frame-index video timestamps enabled at %u fps", decoder->fps);
	decoder->target_width = target_width;
	decoder->target_height = target_height;
	decoder->target_fps = target_fps;
	decoder->target_codec = codec;
	decoder->low_latency_enabled = low_latency_enabled;
	decoder->late_frame_recovery_enabled = late_frame_recovery_enabled;
	decoder->last_queued_frame_index_valid = false;
	decoder->output_backlog = 0;
	decoder->output_frames_released = 0;
	decoder->output_frames_dropped = 0;
	decoder->backlog_idr_requested = false;
	decoder->request_idr_cb = NULL;
	decoder->request_idr_cb_user = NULL;
	decoder->shutdown_output = false;
	ChiakiErrorCode err = chiaki_mutex_init(&decoder->codec_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;
	err = chiaki_mutex_init(&decoder->stats_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
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
	if(tier <= 1)
	{
		AMediaFormat_setInt32(format, "operating-rate", decoder->target_fps * 4);
		AMediaFormat_setInt32(format, "priority", 1);
	}
	if(tier == 0)
		AMediaFormat_setInt32(format, "low-latency", 1);

	return format;
}

static void kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->codec_mutex);
	decoder->shutdown_output = true;
	ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);
	if(codec_buf_index >= 0)
	{
		CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
		AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, decoder->timestamp_cur++, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
		chiaki_thread_join(&decoder->output_thread, NULL);
	}
	else
	{
		CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
	}
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;
	decoder->shutdown_output = false;
}

void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder)
{
	if(decoder->codec)
		kill_decoder(decoder);
	chiaki_mutex_fini(&decoder->stats_mutex);
	chiaki_mutex_fini(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface)
{
	chiaki_mutex_lock(&decoder->codec_mutex);

	if(!surface)
	{
		if(decoder->codec)
		{
			kill_decoder(decoder);
			CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
		}
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
	int first_tier = decoder->low_latency_enabled ? 0 : 3;
	media_status_t r = AMEDIA_ERROR_UNKNOWN;
	AMediaFormat *format = NULL;
	int configured_tier = -1;
	for(int tier = first_tier; tier < DECODER_CONFIGURE_TIER_COUNT; tier++)
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
	CHIAKI_LOGI(decoder->log, "AMediaCodec_configure() succeeded for %s at tier %d%s", decoder_name, configured_tier,
			decoder->low_latency_enabled ? "" : " (low-latency setting disabled)");
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

	ChiakiErrorCode err = chiaki_thread_create(&decoder->output_thread, android_chiaki_video_decoder_output_thread_func, decoder);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create output thread for AMediaCodec");
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

bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, ChiakiSeqNum16 frame_index, int32_t frames_lost, bool frame_recovered, void *user)
{
	bool r = true;
	bool request_idr = false;
	uint64_t backlog = 0;
	AndroidChiakiVideoDecoderRequestIDRCallback request_idr_cb = NULL;
	void *request_idr_cb_user = NULL;
	bool backlog_recorded = false;
	AndroidChiakiVideoDecoder *decoder = user;
	chiaki_mutex_lock(&decoder->codec_mutex);

	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Received video data, but decoder is not initialized!");
		goto beach;
	}

	uint64_t presentation_time_us = decoder->timestamp_cur;
	if(decoder->real_pts_enabled)
	{
		uint64_t unwrapped_frame_index = chiaki_seq_num_16_unwrap(&decoder->frame_index_unwrapper, frame_index);
		presentation_time_us = unwrapped_frame_index * 1000000ULL / decoder->fps;
	}

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

		media_status_t queue_result = AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, codec_sample_size, presentation_time_us, 0);
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

static void *android_chiaki_video_decoder_output_thread_func(void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;

	while(1)
	{
		AMediaCodecBufferInfo info;
		ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);
		if(status >= 0)
		{
			unsigned int dropped_now = 0;
			bool eos = false;
			while(status >= 0)
			{
				AMediaCodecBufferInfo newer_info;
				ssize_t newer_status = -1;
				if(decoder->late_frame_recovery_enabled && info.size != 0)
					newer_status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &newer_info, 0);

				bool drop = newer_status >= 0 && newer_info.size != 0;
				if(decoder->real_pts_enabled && info.size != 0)
					CHIAKI_LOGV(decoder->log, "Video Decoder output PTS: %" PRId64 " us%s", info.presentationTimeUs,
							drop ? " (dropped)" : "");
				AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, info.size != 0 && !drop);
				eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
				if(decoder->late_frame_recovery_enabled && info.size != 0)
				{
					uint64_t backlog;
					uint64_t released;
					uint64_t dropped_total;
					record_output_frame(decoder, drop, &backlog, &released, &dropped_total);
					if(drop)
						dropped_now++;
					else if(released % decoder->fps == 0)
						CHIAKI_LOGI(decoder->log, "Video decoder output stats: backlog=%" PRIu64 " dropped=%" PRIu64,
								backlog, dropped_total);
				}

				if(eos || newer_status < 0)
					break;
				status = newer_status;
				info = newer_info;
			}
			if(dropped_now > 0)
			{
				chiaki_mutex_lock(&decoder->stats_mutex);
				uint64_t backlog = decoder->output_backlog;
				uint64_t dropped_total = decoder->output_frames_dropped;
				chiaki_mutex_unlock(&decoder->stats_mutex);
				CHIAKI_LOGW(decoder->log, "Dropped %u stale decoder output frame(s): backlog=%" PRIu64 " dropped=%" PRIu64,
						dropped_now, backlog, dropped_total);
			}
			if(eos)
			{
				CHIAKI_LOGI(decoder->log, "AMediaCodec reported EOS");
				break;
			}
		}
		else
		{
			chiaki_mutex_lock(&decoder->codec_mutex);
			bool shutdown = decoder->shutdown_output;
			chiaki_mutex_unlock(&decoder->codec_mutex);
			if(shutdown)
			{
				CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread detected shutdown after reported error");
				break;
			}
		}
	}

	if(decoder->late_frame_recovery_enabled)
	{
		chiaki_mutex_lock(&decoder->stats_mutex);
		CHIAKI_LOGI(decoder->log, "Video decoder final output stats: backlog=%" PRIu64 " dropped=%" PRIu64,
				decoder->output_backlog, decoder->output_frames_dropped);
		chiaki_mutex_unlock(&decoder->stats_mutex);
	}
	CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread exiting");

	return NULL;
}
