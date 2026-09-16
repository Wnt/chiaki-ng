// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-presenter-histogram.h"

#include <string.h>

void android_chiaki_video_histogram_reset(AndroidChiakiVideoHistogram *histogram)
{
	memset(histogram, 0, sizeof(*histogram));
}

void android_chiaki_video_histogram_add(AndroidChiakiVideoHistogram *histogram, uint64_t value_ns)
{
	uint64_t bucket = value_ns / ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKET_NS;
	if(bucket >= ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKETS)
		bucket = ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKETS - 1;
	histogram->buckets[bucket]++;
	histogram->count++;
}

uint64_t android_chiaki_video_histogram_percentile(const AndroidChiakiVideoHistogram *histogram,
		uint32_t numerator, uint32_t denominator)
{
	if(histogram->count == 0 || denominator == 0)
		return 0;

	uint64_t rank = ((uint64_t)numerator * histogram->count + denominator - 1) / denominator;
	if(rank == 0)
		rank = 1;
	uint32_t cumulative = 0;
	for(uint32_t bucket = 0; bucket < ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKETS; bucket++)
	{
		cumulative += histogram->buckets[bucket];
		if(cumulative >= rank)
			return (uint64_t)bucket * ANDROID_CHIAKI_VIDEO_HISTOGRAM_BUCKET_NS;
	}
	return ANDROID_CHIAKI_VIDEO_HISTOGRAM_MAX_NS;
}
