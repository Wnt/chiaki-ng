// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

// PLE-524: adb-shell stress test for the Oboe error-thread vs free() race of PLE-514.
//
// Built only with -PchiakiNativeStress=thread|address|hwaddress, never into the APK. It includes
// audio-output.cpp itself (not the chiaki-jni library) so it can reach AudioOutput's internals
// and does the one thing a real device change does: an Oboe error thread running
// onErrorBeforeClose, AudioStream::close and onErrorAfterClose(ErrorDisconnected) on a live
// stream, in that order as Oboe does, here fired
// concurrently with android_chiaki_audio_output_free() on another thread. The race window
// is shifted every iteration with a random delay on each side, drawn from a window that also
// covers twice the longest free() and the longest close() on the error thread seen so far
// (closing an Oboe stream takes milliseconds), so either side lands before, inside and after
// the other. The PASS line counts how often the error callback reopened a stream (the error
// thread won), found AudioOutput already gone (free() won), or raced it in between.
//
// The fake error thread holds exactly what Oboe itself holds on its detached error thread:
// a shared_ptr to the stream when the stream was opened through a shared_ptr, and a
// shared_ptr to the callback when one was handed to setErrorCallback(); otherwise raw
// pointers. That makes the harness faithful to both the fixed code and the pre-PLE-514 one
// (git show 9b767536^:android/app/src/main/cpp/audio-output.cpp), which it must flag as a
// heap-use-after-free under ASan/HWASan: run.sh --self-test does that.
//
// Exit status: 0 clean, 1 usage/setup error, 3 an iteration hung (deadlock: the watchdog
// prints where each thread was); a sanitizer report aborts with its own non-zero status
// (halt_on_error).

#ifndef CHIAKI_STRESS_AUDIO_OUTPUT_SOURCE
#define CHIAKI_STRESS_AUDIO_OUTPUT_SOURCE "audio-output.cpp"
#endif
#include CHIAKI_STRESS_AUDIO_OUTPUT_SOURCE

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <thread>
#include <type_traits>

namespace
{

// The handle android_chiaki_audio_output_new() returns: a heap shared_ptr<AudioOutput> since
// PLE-514, the AudioOutput itself before. Told apart by the stream_callback member's type.
template<typename T> struct is_shared_ptr : std::false_type {};
template<typename T> struct is_shared_ptr<std::shared_ptr<T>> : std::true_type {};

constexpr bool kHandleIsSharedPtr = is_shared_ptr<decltype(AudioOutput::stream_callback)>::value;

template<bool shared = kHandleIsSharedPtr>
typename std::enable_if<shared, AudioOutput *>::type ao_from_handle(void *handle)
{
	return reinterpret_cast<std::shared_ptr<AudioOutput> *>(handle)->get();
}

template<bool shared = kHandleIsSharedPtr>
typename std::enable_if<!shared, AudioOutput *>::type ao_from_handle(void *handle)
{
	return reinterpret_cast<AudioOutput *>(handle);
}

// What an Oboe error thread holds while it runs a callback.
struct OboeErrorThreadRefs
{
	std::shared_ptr<void> callback_ref;
	std::shared_ptr<void> stream_ref;
	oboe::AudioStreamCallback *callback = nullptr;
	oboe::AudioStream *stream = nullptr;
};

template<typename C> void hold_callback(OboeErrorThreadRefs &refs, std::shared_ptr<C> &callback)
{
	refs.callback_ref = callback;
	refs.callback = callback.get();
}

template<typename C> void hold_callback(OboeErrorThreadRefs &refs, C &callback)
{
	refs.callback = &callback;
}

void hold_stream(OboeErrorThreadRefs &refs, std::shared_ptr<oboe::AudioStream> &stream)
{
	refs.stream_ref = stream;
	refs.stream = stream.get();
}

template<typename S> void hold_stream(OboeErrorThreadRefs &refs, S &stream)
{
	refs.stream = stream.get();
}

struct Options
{
	unsigned iterations = 200;
	unsigned max_delay_us = 2000;
	unsigned seed = 0;
	unsigned hang_s = 10;
	bool error_thread = true;
	bool verbose = false;
};

// Where each side of the race is, for the watchdog's report.
std::atomic<const char *> main_phase{"start"};
std::atomic<const char *> error_phase{"idle"};
std::atomic<unsigned> iteration{0};
std::atomic<uint64_t> iteration_start_ms{0};

uint64_t now_ms()
{
	return (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::steady_clock::now().time_since_epoch()).count();
}

// debuggerd needs root on a stock image, so a deadlock is reported from inside.
void watchdog(unsigned hang_s)
{
	for(;;)
	{
		std::this_thread::sleep_for(std::chrono::milliseconds(200));
		uint64_t start = iteration_start_ms.load();
		if(start && now_ms() - start > hang_s * 1000ull)
		{
			printf("audio-output-stress: HANG iteration %u stuck for more than %u s: main thread in %s, error thread in %s\n",
				iteration.load(), hang_s, main_phase.load(), error_phase.load());
			fflush(stdout);
			_exit(3);
		}
	}
}

void usage(const char *argv0)
{
	fprintf(stderr,
		"usage: %s [-n iterations] [-d max_delay_us] [-s seed] [-t hang_s] [-E] [-v]\n"
		"  Fires Oboe's error callbacks concurrently with android_chiaki_audio_output_free().\n"
		"  -E: no error thread, only the new/settings/free lifecycle a session without a device change runs.\n", argv0);
}

bool parse_options(int argc, char **argv, Options *opts)
{
	for(int i = 1; i < argc; i++)
	{
		std::string arg = argv[i];
		auto value = [&](unsigned *out) {
			if(i + 1 >= argc)
				return false;
			*out = (unsigned)strtoul(argv[++i], nullptr, 0);
			return true;
		};
		if(arg == "-n" ? !value(&opts->iterations)
				: arg == "-d" ? !value(&opts->max_delay_us)
				: arg == "-s" ? !value(&opts->seed)
				: arg == "-t" ? !value(&opts->hang_s)
				: arg == "-v" ? (opts->verbose = true, false)
				: arg == "-E" ? (opts->error_thread = false, false)
				: true)
			return false;
	}
	return true;
}

struct LogState
{
	bool verbose;
	std::atomic<unsigned> reopened{0};
	std::atomic<unsigned> after_close_seen{0};
};

void log_cb(ChiakiLogLevel level, const char *msg, void *user)
{
	auto state = static_cast<LogState *>(user);
	// audio-output.cpp's own messages are the only record of which way a race went.
	if(strstr(msg, "reopening Oboe stream"))
		state->reopened++;
	if(strstr(msg, "error after close"))
		state->after_close_seen++;
	if(state->verbose || (level == CHIAKI_LOG_ERROR && !strstr(msg, "Oboe reported error")))
		fprintf(stderr, "[chiaki %d] %s\n", (int)level, msg);
}

// Both threads wait here so the two sides start as close together as the scheduler allows.
class StartGate
{
	std::mutex mutex;
	std::condition_variable cv;
	int waiting = 0;
	int parties;

public:
	explicit StartGate(int parties) : parties(parties) {}
	void arrive_and_wait()
	{
		std::unique_lock<std::mutex> lock(mutex);
		if(++waiting == parties)
			cv.notify_all();
		else
			cv.wait(lock, [this] { return waiting >= parties; });
	}
};

void spin_us(unsigned us)
{
	auto end = std::chrono::steady_clock::now() + std::chrono::microseconds(us);
	while(std::chrono::steady_clock::now() < end);
}

} // namespace

int main(int argc, char **argv)
{
	Options opts;
	if(!parse_options(argc, argv, &opts))
	{
		usage(argv[0]);
		return 1;
	}
	if(!opts.seed)
		opts.seed = std::random_device()();
	// adb shell hands us a pipe: without this, a hang shows no progress at all.
	setvbuf(stdout, nullptr, _IOLBF, 0);

	bool verbose = opts.verbose;
	LogState log_state;
	log_state.verbose = verbose;
	ChiakiLog log;
	chiaki_log_init(&log, CHIAKI_LOG_ALL, log_cb, &log_state);

	printf("audio-output-stress: source %s, handle %s, iterations %u, max_delay_us %u, seed %u\n",
		CHIAKI_STRESS_AUDIO_OUTPUT_SOURCE, kHandleIsSharedPtr ? "shared_ptr (PLE-514+)" : "raw (pre-PLE-514)",
		opts.iterations, opts.max_delay_us, opts.seed);
	fflush(stdout);

	std::thread(watchdog, opts.hang_s).detach();
	std::mt19937 rng(opts.seed);
	unsigned streams_opened = 0;
	unsigned free_us_max = 0;
	unsigned close_us_max = 0;
	unsigned callbacks_skipped = 0;
	int16_t silence[480 * 2] = {};

	// Calibrate the race window with one plain lifecycle, so the first iterations already cover
	// the error thread starting after free() returns, not only while it is still closing.
	{
		main_phase = "calibration lifecycle";
		iteration_start_ms = now_ms();
		void *handle = android_chiaki_audio_output_new(&log, 0, BUFFER_DEFAULT_FIFO_MS, true);
		android_chiaki_audio_output_settings(2, 48000, handle);
		auto free_start = std::chrono::steady_clock::now();
		android_chiaki_audio_output_free(handle);
		free_us_max = close_us_max = std::min((unsigned)std::chrono::duration_cast<std::chrono::microseconds>(
			std::chrono::steady_clock::now() - free_start).count(), 250000u);
		printf("audio-output-stress: calibration free() %u us\n", free_us_max);
	}

	for(unsigned i = 0; i < opts.iterations; i++)
	{
		iteration = i;
		iteration_start_ms = now_ms();
		main_phase = "android_chiaki_audio_output_new/settings (stream open)";
		error_phase = "idle";
		void *handle = android_chiaki_audio_output_new(&log, 0, BUFFER_DEFAULT_FIFO_MS, true);
		android_chiaki_audio_output_settings(2, 48000, handle);
		android_chiaki_audio_output_frame(silence, sizeof(silence) / sizeof(silence[0]), handle);

		AudioOutput *ao = ao_from_handle(handle);
		OboeErrorThreadRefs refs;
		if(!opts.error_thread)
		{
			bool open;
			{
				std::lock_guard<std::mutex> lock(ao->stream_mutex);
				open = (bool)ao->stream;
			}
			streams_opened += open;
			spin_us(std::uniform_int_distribution<unsigned>(0, opts.max_delay_us)(rng));
			main_phase = "android_chiaki_audio_output_free";
			android_chiaki_audio_output_free(handle);
			if(verbose)
				printf("iteration %u: lifecycle only, stream %s\n", i, open ? "open" : "none");
			continue;
		}
		{
			std::lock_guard<std::mutex> lock(ao->stream_mutex);
			hold_callback(refs, ao->stream_callback);
			hold_stream(refs, ao->stream);
		}
		if(refs.stream)
			streams_opened++;

		unsigned window_us = opts.max_delay_us + 2 * std::max(free_us_max, close_us_max);
		unsigned error_delay = std::uniform_int_distribution<unsigned>(0, window_us)(rng);
		unsigned free_delay = std::uniform_int_distribution<unsigned>(0, window_us)(rng);
		unsigned close_us = 0;
		unsigned after_close_before = log_state.after_close_seen.load();
		StartGate gate(2);
		std::thread error_thread([&] {
			gate.arrive_and_wait();
			spin_us(error_delay);
			// Oboe's own error thread: onErrorBeforeClose, close the stream, onErrorAfterClose.
			error_phase = "onErrorBeforeClose";
			refs.callback->onErrorBeforeClose(refs.stream, oboe::Result::ErrorDisconnected);
			error_phase = "AudioStream::close";
			if(refs.stream)
			{
				auto close_start = std::chrono::steady_clock::now();
				refs.stream->close();
				close_us = (unsigned)std::chrono::duration_cast<std::chrono::microseconds>(
					std::chrono::steady_clock::now() - close_start).count();
			}
			error_phase = "onErrorAfterClose";
			refs.callback->onErrorAfterClose(refs.stream, oboe::Result::ErrorDisconnected);
			error_phase = "done";
		});

		gate.arrive_and_wait();
		spin_us(free_delay);
		auto free_start = std::chrono::steady_clock::now();
		main_phase = "android_chiaki_audio_output_free";
		android_chiaki_audio_output_free(handle);
		main_phase = "join error thread";
		auto free_us = (unsigned)std::chrono::duration_cast<std::chrono::microseconds>(
			std::chrono::steady_clock::now() - free_start).count();
		error_thread.join();
		// Capped, so one call stuck behind a slow reopen cannot make every later iteration crawl.
		free_us_max = std::max(free_us_max, std::min(free_us, 250000u));
		close_us_max = std::max(close_us_max, std::min(close_us, 250000u));
		if(log_state.after_close_seen.load() == after_close_before)
			callbacks_skipped++; // onErrorAfterClose found AudioOutput already gone

		if(verbose)
			printf("iteration %u: error_delay_us %u free_delay_us %u free_us %u close_us %u stream %s\n",
				i, error_delay, free_delay, free_us, close_us, refs.stream ? "open" : "none");
		// Oboe drops these when its error thread returns; the last one destroys the stream object.
		main_phase = "drop the error thread's stream/callback references";
		refs = OboeErrorThreadRefs();
	}
	iteration_start_ms = 0;
	// A stream released without close() keeps AAudio calling into it after free(): give the last
	// iteration's callback thread time to trip the sanitizer before exit() tears it down.
	std::this_thread::sleep_for(std::chrono::milliseconds(300));

	if(!opts.error_thread)
	{
		printf("audio-output-stress: PASS %u lifecycle iterations, %u with a live Oboe stream\n",
			opts.iterations, streams_opened);
		if(!streams_opened)
			printf("audio-output-stress: WARNING no Oboe stream ever opened\n");
		return 0;
	}
	unsigned reopened = log_state.reopened.load();
	printf("audio-output-stress: PASS %u iterations, %u with a live Oboe stream; error callback reopened %u,"
		" found AudioOutput gone %u, raced in between %u; longest free() %u us, close() %u us\n",
		opts.iterations, streams_opened, reopened, callbacks_skipped,
		opts.iterations - reopened - callbacks_skipped, free_us_max, close_us_max);
	if(opts.error_thread && streams_opened && (!reopened || !callbacks_skipped))
		printf("audio-output-stress: WARNING one side never won the race; raise -n or -d\n");
	if(!streams_opened)
		printf("audio-output-stress: WARNING no Oboe stream ever opened; only the no-stream paths were raced\n");
	return 0;
}
