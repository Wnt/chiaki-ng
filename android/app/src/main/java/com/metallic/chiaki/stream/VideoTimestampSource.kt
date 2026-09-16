// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

internal enum class VideoTimestampSource
{
	SYNTHETIC_COUNTER,
	FRAME_INDEX
}

/**
 * Selects the decoder timestamp source independently from presenter pacing.
 *
 * Keep [pacingEnabled] in this policy boundary: it documents and tests that enabling the
 * presenter must not silently activate frame-index timestamps again.
 */
internal fun videoTimestampSource(
	realVideoTimestamps: Boolean,
	pacingEnabled: Boolean
): VideoTimestampSource = when
{
	realVideoTimestamps -> VideoTimestampSource.FRAME_INDEX
	pacingEnabled -> VideoTimestampSource.SYNTHETIC_COUNTER
	else -> VideoTimestampSource.SYNTHETIC_COUNTER
}

internal fun useFrameIndexVideoTimestamps(
	realVideoTimestamps: Boolean,
	pacingEnabled: Boolean
) = videoTimestampSource(realVideoTimestamps, pacingEnabled) == VideoTimestampSource.FRAME_INDEX
