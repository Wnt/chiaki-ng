// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_PRESENTER_HISTOGRAM_H
#define CHIAKI_JNI_VIDEO_PRESENTER_HISTOGRAM_H

#include <stdint.h>

#define ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKET_NS 1000000ULL
#define ANDROID_CHIAKI_VIDEO_HISTOGRAM_MAX_NS 256000000ULL
#define ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKETS \
	(ANDROID_CHIAKI_VIDEO_HISTOGRAM_MAX_NS / ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKET_NS + 1)

typedef struct android_chiaki_video_histogram_t
{
	uint32_t buckets[ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKETS];
	uint32_t count;
} AndroidChiakiVideoHistogram;

void android_chiaki_video_histogram_reset(AndroidChiakiVideoHistogram *histogram);
void android_chiaki_video_histogram_add(AndroidChiakiVideoHistogram *histogram, uint64_t value_ns);
uint64_t android_chiaki_video_histogram_percentile(const AndroidChiakiVideoHistogram *histogram,
		uint32_t numerator, uint32_t denominator);

#endif
