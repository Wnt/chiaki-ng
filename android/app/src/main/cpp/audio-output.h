// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_AUDIO_OUTPUT_H
#define CHIAKI_JNI_AUDIO_OUTPUT_H

#include <chiaki/log.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct android_chiaki_audio_diagnostics_t
{
	uint64_t latency_us;
	uint64_t underruns;
	int32_t xruns;
	bool latency_valid;
	bool xruns_valid;
} AndroidChiakiAudioDiagnostics;

void *android_chiaki_audio_output_new(ChiakiLog *log, uint32_t buffer_bursts, uint32_t fifo_ms,
		bool diagnostics_enabled);
void android_chiaki_audio_output_free(void *audio_output);
void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output);
void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output);
void android_chiaki_audio_output_get_diagnostics(void *audio_output,
		AndroidChiakiAudioDiagnostics *diagnostics);

#ifdef __cplusplus
}
#endif

#endif //CHIAKI_JNI_AUDIO_OUTPUT_H
