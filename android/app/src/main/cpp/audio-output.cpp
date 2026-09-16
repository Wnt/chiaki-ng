// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>
#include <chiaki/time.h>

#include <oboe/Oboe.h>
#include <oboe/OboeExtensions.h>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_DEFAULT_CHUNKS_COUNT 32
#define BUFFER_MAX_CHUNKS_COUNT 1024
#define BUFFER_DEFAULT_FIFO_MS 171

using AudioBuffer = CircularBuffer<BUFFER_MAX_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	AudioOutput *audio_output;

public:
	AudioOutputCallback(AudioOutput *audio_output) : audio_output(audio_output) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioOutputCallback stream_callback;
	AudioBuffer buf;
	uint32_t buffer_bursts;
	uint32_t fifo_ms;
	uint32_t stats_log_interval_ms;
	uint64_t stats_window_start_ms;

	AudioOutput(uint32_t buffer_bursts, uint32_t fifo_ms, uint32_t stats_log_interval_ms)
		: stream_callback(this), buf(BUFFER_DEFAULT_CHUNKS_COUNT), buffer_bursts(buffer_bursts),
		  fifo_ms(fifo_ms), stats_log_interval_ms(stats_log_interval_ms), stats_window_start_ms(0) {}
};

static size_t audio_fifo_chunks(uint32_t channels, uint32_t rate, uint32_t fifo_ms)
{
	// 171 ms at the PS Remote Play 48 kHz stereo format represents today's exact 32 KiB FIFO.
	if(fifo_ms == BUFFER_DEFAULT_FIFO_MS)
		return BUFFER_DEFAULT_CHUNKS_COUNT;

	uint64_t bytes = (uint64_t)fifo_ms * rate * channels * sizeof(int16_t) / 1000;
	size_t chunks = (size_t)((bytes + BUFFER_CHUNK_SIZE / 2) / BUFFER_CHUNK_SIZE);
	if(chunks < 1)
		chunks = 1;
	if(chunks > BUFFER_MAX_CHUNKS_COUNT)
		chunks = BUFFER_MAX_CHUNKS_COUNT;
	return chunks;
}

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log, uint32_t buffer_bursts,
		uint32_t fifo_ms, uint32_t stats_log_interval_ms)
{
	auto r = new AudioOutput(buffer_bursts, fifo_ms, stats_log_interval_ms);
	r->log = log;
	return r;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;
	delete ao;
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;
	ao->buf.SetChunksCount(audio_fifo_chunks(channels, rate, ao->fifo_ms));
	ao->stats_window_start_ms = 0;

	oboe::AudioStreamBuilder builder;
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Exclusive)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(channels)
		->setSampleRate(rate)
		->setCallback(&ao->stream_callback);

	auto result = builder.openManagedStream(ao->stream);
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGE(ao->log, "Audio Output failed to open Oboe stream: %s", oboe::convertToText(result));
		return;
	}

	if(ao->buffer_bursts > 0)
	{
		int32_t requested_frames = ao->stream->getFramesPerBurst() * (int32_t)ao->buffer_bursts;
		auto buffer_result = ao->stream->setBufferSizeInFrames(requested_frames);
		if(!buffer_result)
			CHIAKI_LOGW(ao->log, "Audio Output failed to set Oboe buffer to %d frames: %s",
				requested_frames, oboe::convertToText(buffer_result.error()));
	}

	if(ao->stats_log_interval_ms)
	{
		CHIAKI_LOGI(ao->log,
			"Audio output opened: api %s frames_per_burst %d buffer_frames %d capacity_frames %d"
			" sample_rate %d mmap %s fifo_ms %u",
			oboe::convertToText(ao->stream->getAudioApi()), ao->stream->getFramesPerBurst(),
			ao->stream->getBufferSizeInFrames(), ao->stream->getBufferCapacityInFrames(),
			ao->stream->getSampleRate(), oboe::OboeExtensions::isMMapUsed(ao->stream.get()) ? "yes" : "no",
			ao->fifo_ms);
	}
	else
		CHIAKI_LOGI(ao->log, "Audio Output opened Oboe stream");

	result = ao->stream->start();
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output started Oboe stream");
	else
		CHIAKI_LOGE(ao->log, "Audio Output failed to start Oboe stream: %s", oboe::convertToText(result));
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	if(ao->stats_log_interval_ms && ao->stream)
	{
		uint64_t now_ms = chiaki_time_now_monotonic_ms();
		if(!ao->stats_window_start_ms)
			ao->stats_window_start_ms = now_ms;
		else if(now_ms - ao->stats_window_start_ms >= ao->stats_log_interval_ms)
		{
			auto latency = ao->stream->calculateLatencyMillis();
			auto xruns = ao->stream->getXRunCount();
			CHIAKI_LOGI(ao->log,
				"Audio output stats: window %llu ms latency_ms %.3f latency_result %s"
				" xruns %d xrun_result %s",
				(unsigned long long)(now_ms - ao->stats_window_start_ms), latency.value(),
				latency ? "OK" : oboe::convertToText(latency.error()), xruns.value(),
				xruns ? "OK" : oboe::convertToText(xruns.error()));
			ao->stats_window_start_ms = now_ms;
		}
	}

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	if(pushed < buf_size)
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_output->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t buf_size_delivered = audio_output->buf.Pop(buf, buf_size_requested);
	//CHIAKI_LOGW(audio_output->log, "Delivered %llu", (unsigned long long)buf_size_delivered);

	if(buf_size_delivered < buf_size_requested)
	{
		CHIAKI_LOGV(audio_output->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error after close: %s", oboe::convertToText(error));
}
