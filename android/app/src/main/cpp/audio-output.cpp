// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>
#include <chiaki/time.h>

#include <oboe/Oboe.h>
#include <oboe/OboeExtensions.h>

#include <atomic>
#include <cstring>
#include <mutex>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_DEFAULT_CHUNKS_COUNT 32
#define BUFFER_MAX_CHUNKS_COUNT 1024
#define BUFFER_DEFAULT_FIFO_MS 171

using AudioBuffer = CircularBuffer<BUFFER_MAX_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

// Oboe keeps this object alive (it is handed to setDataCallback/setErrorCallback as a
// shared_ptr, and Oboe's own docs guarantee "the errorCallback object cannot be deleted
// before the stream is deleted") for as long as the oboe::AudioStream exists, including
// across its own detached error-handling thread. AudioOutput has no such guarantee: it can
// be freed by the session while that thread is about to run, or is running, one of these
// methods. A weak_ptr + lock() is what makes touching it race-free: lock() either yields a
// strong ref that keeps AudioOutput alive for the rest of the call, or observes it is
// already gone and the callback becomes a no-op.
class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	std::weak_ptr<AudioOutput> audio_output;

public:
	AudioOutputCallback(std::weak_ptr<AudioOutput> audio_output) : audio_output(std::move(audio_output)) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	// Guards stream, channels and rate: the Oboe error thread reopens the stream
	// (device change) while the session threads open, query and free it.
	std::mutex stream_mutex;
	std::shared_ptr<oboe::AudioStream> stream;
	uint32_t channels = 0;
	uint32_t rate = 0;
	std::shared_ptr<AudioOutputCallback> stream_callback;
	AudioBuffer buf;
	uint32_t buffer_bursts;
	uint32_t fifo_ms;
	bool diagnostics_enabled;
	std::atomic<uint64_t> underruns;
	// Set under stream_mutex by free() so an error callback racing with teardown does not
	// reopen a stream that nothing will ever read from again.
	bool closing = false;

	AudioOutput(uint32_t buffer_bursts, uint32_t fifo_ms, bool diagnostics_enabled)
		: buf(BUFFER_DEFAULT_CHUNKS_COUNT), buffer_bursts(buffer_bursts),
		  fifo_ms(fifo_ms), diagnostics_enabled(diagnostics_enabled), underruns(0) {}
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

// The void* handle returned to callers is a heap-allocated copy of the shared_ptr, not the
// AudioOutput itself: AudioOutputCallback holds a weak_ptr to the same object, and Oboe keeps
// the callback (and, separately, the stream) alive past this handle's lifetime when needed.
extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log, uint32_t buffer_bursts,
		uint32_t fifo_ms, bool diagnostics_enabled)
{
	auto ao = std::make_shared<AudioOutput>(buffer_bursts, fifo_ms, diagnostics_enabled);
	ao->log = log;
	ao->stream_callback = std::make_shared<AudioOutputCallback>(ao);
	return new std::shared_ptr<AudioOutput>(std::move(ao));
}

// Call with stream_mutex held. Dropping the last shared_ptr does not close the stream: neither
// ~AudioStreamAAudio nor ~AudioStream calls close() (Oboe 1.10.0), so AAudio would keep calling
// into the destroyed object from its callback thread. ManagedStream's deleter used to do this.
// Without it every real stream end died with SIGTRAP in callOnAudioReady on AAudio_1 (PLE-541).
static void audio_output_release_stream(AudioOutput *ao)
{
	if(ao->stream)
		ao->stream->close();
	ao->stream = nullptr;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto handle = reinterpret_cast<std::shared_ptr<AudioOutput> *>(audio_output);
	{
		std::lock_guard<std::mutex> lock((*handle)->stream_mutex);
		(*handle)->closing = true;
		audio_output_release_stream(handle->get());
	}
	delete handle;
}

// Call with stream_mutex held and ao->stream empty.
static void audio_output_open_stream(AudioOutput *ao)
{
	oboe::AudioStreamBuilder builder;
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Exclusive)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(ao->channels)
		->setSampleRate(ao->rate)
		->setDataCallback(ao->stream_callback)
		->setErrorCallback(ao->stream_callback);

	auto result = builder.openStream(ao->stream);
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

	if(ao->diagnostics_enabled)
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

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<std::shared_ptr<AudioOutput> *>(audio_output)->get();
	std::lock_guard<std::mutex> lock(ao->stream_mutex);
	audio_output_release_stream(ao);
	ao->buf.SetChunksCount(audio_fifo_chunks(channels, rate, ao->fifo_ms));
	ao->underruns.store(0, std::memory_order_relaxed);
	ao->channels = channels;
	ao->rate = rate;
	audio_output_open_stream(ao);
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<std::shared_ptr<AudioOutput> *>(audio_output)->get();

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	if(pushed < buf_size)
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	auto ao = audio_output.lock();
	if(!ao)
		return oboe::DataCallbackResult::Stop;

	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(ao->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t buf_size_delivered = ao->buf.Pop(buf, buf_size_requested);
	//CHIAKI_LOGW(ao->log, "Delivered %llu", (unsigned long long)buf_size_delivered);

	if(buf_size_delivered < buf_size_requested)
	{
		if(ao->diagnostics_enabled)
			ao->underruns.fetch_add(1, std::memory_order_relaxed);
		CHIAKI_LOGV(ao->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

extern "C" void android_chiaki_audio_output_get_diagnostics(void *audio_output,
		AndroidChiakiAudioDiagnostics *diagnostics)
{
	memset(diagnostics, 0, sizeof(*diagnostics));
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<std::shared_ptr<AudioOutput> *>(audio_output)->get();
	diagnostics->underruns = ao->underruns.load(std::memory_order_relaxed);
	std::lock_guard<std::mutex> lock(ao->stream_mutex);
	if(ao->rate > 0 && ao->channels > 0)
	{
		uint64_t bytes_per_ms = (uint64_t)ao->rate * ao->channels * sizeof(int16_t) / 1000;
		if(bytes_per_ms > 0)
		{
			diagnostics->fifo_fill_us = ao->buf.FillBytes() * 1000 / bytes_per_ms;
			diagnostics->fifo_capacity_us = ao->buf.CapacityBytes() * 1000 / bytes_per_ms;
		}
	}
	if(!ao->stream)
		return;

	auto latency = ao->stream->calculateLatencyMillis();
	if(latency)
	{
		diagnostics->latency_valid = true;
		diagnostics->latency_us = static_cast<uint64_t>(latency.value() * 1000.0);
	}
	auto xruns = ao->stream->getXRunCount();
	if(xruns)
	{
		diagnostics->xruns_valid = true;
		diagnostics->xruns = xruns.value();
	}
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	auto ao = audio_output.lock();
	if(!ao)
		return;
	CHIAKI_LOGE(ao->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	auto ao = audio_output.lock();
	if(!ao)
		return;
	CHIAKI_LOGE(ao->log, "Oboe reported error after close: %s", oboe::convertToText(error));
	if(error != oboe::Result::ErrorDisconnected)
		return;

	// The output device changed under the stream (headphones, HDMI/DeX display, Bluetooth).
	// AAudio never moves a stream to the new device by itself, so open a fresh one on
	// whatever the default route is now; without this the session stays silent.
	std::lock_guard<std::mutex> lock(ao->stream_mutex);
	if(ao->closing || ao->stream.get() != stream)
		return; // being torn down, or already replaced

	ao->stream = nullptr; // Oboe closed it before calling us

	// The closed stream's callback was the only consumer, so this thread may drain what piled
	// up while no device was attached. Otherwise the new stream would start behind a full
	// FIFO and carry that delay for the rest of the session.
	uint8_t stale[BUFFER_CHUNK_SIZE];
	while(ao->buf.Pop(stale, sizeof(stale)) == sizeof(stale));

	CHIAKI_LOGI(ao->log, "Audio Output reopening Oboe stream after device disconnect");
	audio_output_open_stream(ao.get());
}
