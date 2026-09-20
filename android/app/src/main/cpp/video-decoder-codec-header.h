// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_CODEC_HEADER_H
#define CHIAKI_JNI_VIDEO_DECODER_CODEC_HEADER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/** Whether an Annex-B sample opens with a parameter set NAL: VPS/SPS/PPS for H.265, SPS/PPS for H.264. */
bool android_chiaki_sample_is_codec_header(bool h265, const uint8_t *buf, size_t buf_size);

#endif
