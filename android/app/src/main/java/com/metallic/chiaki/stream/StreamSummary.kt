// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.os.Parcelable
import com.metallic.chiaki.lib.StreamStatsEvent
import kotlinx.parcelize.Parcelize
import java.util.Locale

enum class StreamSummaryQuality { GOOD, FAIR, POOR, UNKNOWN }

@Parcelize
data class StreamSummary(
	val durationMillis: Long,
	val averageLatencyMillis: Double?,
	val droppedFrames: Long,
	val quality: StreamSummaryQuality,
	/** PLE-262: set when the console ended the stream, so the home screen can name the cause. */
	val endReason: StreamEndReason? = null
): Parcelable

internal class StreamSummaryAccumulator
{
	private var connectedAtMillis: Long? = null
	private var endedAtMillis: Long? = null
	private var latencyWeightedSum = 0.0
	private var latencyDurationMillis = 0L
	private var droppedFrames = 0L
	private var knownQualityDurationMillis = 0L
	private var fairQualityDurationMillis = 0L
	private var poorQualityDurationMillis = 0L
	private var classifier = NetworkQualityClassifier()

	fun connected(nowMillis: Long)
	{
		connectedAtMillis = nowMillis
		endedAtMillis = null
		latencyWeightedSum = 0.0
		latencyDurationMillis = 0L
		droppedFrames = 0L
		knownQualityDurationMillis = 0L
		fairQualityDurationMillis = 0L
		poorQualityDurationMillis = 0L
		classifier = NetworkQualityClassifier()
	}

	fun ended(nowMillis: Long)
	{
		if(connectedAtMillis != null && endedAtMillis == null)
			endedAtMillis = nowMillis
	}

	fun add(stats: StreamStatsEvent, link: NetworkLinkSample)
	{
		if(connectedAtMillis == null)
			return
		val interval = stats.intervalMillis.coerceAtLeast(0L)
		val latencyMicros = stats.measuredRttMicros
		if(latencyMicros > 0L && interval > 0L)
		{
			latencyWeightedSum += latencyMicros / 1000.0 * interval
			latencyDurationMillis += interval
		}
		droppedFrames += stats.decoderInputFramesDropped +
			stats.presenterFramesDropped +
			stats.videoFramesLost
		when(classifier.update(stats, link).level)
		{
			NetworkQualityLevel.GOOD -> knownQualityDurationMillis += interval
			NetworkQualityLevel.CONSTRAINED ->
			{
				knownQualityDurationMillis += interval
				fairQualityDurationMillis += interval
			}
			NetworkQualityLevel.POOR ->
			{
				knownQualityDurationMillis += interval
				poorQualityDurationMillis += interval
			}
			NetworkQualityLevel.UNKNOWN -> Unit
		}
	}

	fun build(nowMillis: Long): StreamSummary?
	{
		val started = connectedAtMillis ?: return null
		val quality = when
		{
			knownQualityDurationMillis == 0L -> StreamSummaryQuality.UNKNOWN
			poorQualityDurationMillis * 5 >= knownQualityDurationMillis -> StreamSummaryQuality.POOR
			(fairQualityDurationMillis + poorQualityDurationMillis) * 5 >= knownQualityDurationMillis ->
				StreamSummaryQuality.FAIR
			else -> StreamSummaryQuality.GOOD
		}
		return StreamSummary(
			durationMillis = ((endedAtMillis ?: nowMillis) - started).coerceAtLeast(0L),
			averageLatencyMillis = if(latencyDurationMillis > 0L)
				latencyWeightedSum / latencyDurationMillis else null,
			droppedFrames = droppedFrames,
			quality = quality
		)
	}
}

internal object StreamSummaryFormatter
{
	fun duration(durationMillis: Long): String
	{
		val totalSeconds = durationMillis.coerceAtLeast(0L) / 1000L
		val hours = totalSeconds / 3600L
		val minutes = totalSeconds % 3600L / 60L
		val seconds = totalSeconds % 60L
		return if(hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
		else String.format(Locale.US, "%d:%02d", minutes, seconds)
	}

	fun latency(averageLatencyMillis: Double?): String =
		averageLatencyMillis?.let { String.format(Locale.US, "%.0f ms", it) } ?: "—"
}
