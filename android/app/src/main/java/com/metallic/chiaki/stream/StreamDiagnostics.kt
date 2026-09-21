// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.metallic.chiaki.lib.StreamStatsEvent
import java.util.Locale

internal data class StreamDiagnosticsDisplay(
	val width: Int,
	val height: Int,
	val refreshRate: Float,
	val modeId: Int
)

internal data class StreamDiagnosticsUiState(
	val display: StreamDiagnosticsDisplay,
	val viewMode: String,
	val flags: List<String>,
	val presenterMode: String?,
	val networkLink: NetworkLinkSample = NetworkLinkSample(NetworkLinkType.UNKNOWN)
)

internal object StreamDiagnosticsFormatter
{
	private fun rate(count: Long, intervalMillis: Long): Double =
		if(intervalMillis > 0) count * 1000.0 / intervalMillis else 0.0

	fun format(stats: StreamStatsEvent?, ui: StreamDiagnosticsUiState,
		quality: NetworkQualitySnapshot = NetworkQualitySnapshot.UNKNOWN): String
	{
		val interval = stats?.intervalMillis ?: 1000L
		val received = stats?.takionPacketsReceived ?: 0L
		val lost = stats?.takionPartialFrameUnitsMissing ?: 0L
		val packetTotal = received + lost
		val lossPercent = if(packetTotal > 0) lost * 100.0 / packetTotal else 0.0
		val flags = ui.flags.ifEmpty { listOf("none") }.joinToString(" ")
		val presenter = ui.presenterMode?.let { " | presenter=$it" }.orEmpty()
		val measuredLoss = (stats?.congestionMeasuredLoss ?: 0.0) * 100.0
		val reportedLoss = (stats?.congestionReportedLoss ?: 0.0) * 100.0
		val rttMicros = stats?.measuredRttMicros ?: 0L
		val rttSource = stats?.measuredRttSource ?: "none"
		val cause = when(quality.cause)
		{
			NetworkQualityCause.WIFI_LINK -> "Wi-Fi link"
			NetworkQualityCause.LAN -> "LAN"
			NetworkQualityCause.CONSOLE -> "console"
			NetworkQualityCause.NONE -> "none"
		}

		return String.format(
			Locale.US,
			"stream %.1f fps | decoder %.1f fps\n" +
				"decode %.2f ms mean | %.2f ms p95 | q %d\n" +
				"drop-in %d | late %d | lost %d | discarded-for-idr %d | reorder %d\n" +
				"network %s (%s) | %.2f/%.2f Mbps actual/target\n" +
				"loss %.2f/%.2f%% measured/reported | RTT %.2f ms %s (ambiguous %d) | jitter %.2f ms | console-rtt %.1f (unverified)\n" +
				"Takion %.1f pkt/s | loss %.2f%% | feedback %.1f pkt/s | server-loss %d\n" +
				"audio %.2f ms | xruns %d | underruns %d\n" +
				"vsync %.3f ms | miss %d | DJB %.1f ms\n" +
				"stage0 D %.1f target %.1f | err p50 %.1f p99 %.1f ms | decode-ewma %.1f | drops %d | source %s\n" +
				"display %dx%d@%.2f Hz mode %d | view=%s\n" +
				"flags $flags$presenter",
			rate(stats?.streamFrames ?: 0L, interval),
			rate(stats?.decoderFrames ?: 0L, interval),
			(stats?.decodeMeanMicros ?: 0L) / 1000.0,
			(stats?.decodeP95Micros ?: 0L) / 1000.0,
			stats?.presenterQueueDepth ?: 0L,
			stats?.decoderInputFramesDropped ?: 0L,
			stats?.presenterFramesDropped ?: 0L,
			stats?.videoFramesLost ?: 0L,
			stats?.videoFramesDiscardedForIdr ?: 0L,
			stats?.reorderQueueTimeouts ?: 0L,
			quality.level.name,
			cause,
			(stats?.measuredThroughputBps ?: 0L) / 1_000_000.0,
			(stats?.targetBitrateBps ?: 0L) / 1_000_000.0,
			measuredLoss,
			reportedLoss,
			rttMicros / 1000.0,
			rttSource,
			stats?.probeRttAmbiguous ?: 0L,
			(stats?.videoPacketJitterMicros ?: 0L) / 1000.0,
			stats?.consoleRttRaw ?: 0.0,
			rate(received, interval),
			lossPercent,
			rate(stats?.feedbackPackets ?: 0L, interval),
			stats?.serverLoss ?: 0L,
			(stats?.audioLatencyMicros ?: 0L) / 1000.0,
			stats?.audioXruns ?: 0L,
			stats?.audioUnderruns ?: 0L,
			(stats?.vsyncPeriodNanos ?: 0L) / 1_000_000.0,
			stats?.missedVsyncs ?: 0L,
			(stats?.dejitterBufferNanos ?: 0L) / 1_000_000.0,
			(stats?.cadenceDepthNanos ?: 0L) / 1_000_000.0,
			(stats?.cadenceTargetNanos ?: 0L) / 1_000_000.0,
			(stats?.cadenceErrP50Nanos ?: 0L) / 1_000_000.0,
			(stats?.cadenceErrP99Nanos ?: 0L) / 1_000_000.0,
			(stats?.decodeEwmaNanos ?: 0L) / 1_000_000.0,
			stats?.cadenceWindowDrops ?: 0L,
			if(stats?.cadenceHalfRateDetected == true) "30-in-60" else "nominal",
			ui.display.width,
			ui.display.height,
			ui.display.refreshRate,
			ui.display.modeId,
			ui.viewMode
		)
	}
}

/** A separate application window guarantees the diagnostic text cannot consume control touches. */
internal class StreamDiagnosticsOverlay(
	private val activity: Activity,
	private val uiState: () -> StreamDiagnosticsUiState
)
{
	companion object
	{
		const val REDRAW_INTERVAL_MS = 1000L
		const val WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
			WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
			WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
	}

	private val textView = TextView(activity).apply {
		setTextColor(Color.WHITE)
		setBackgroundColor(Color.argb(184, 0, 0, 0))
		textSize = 11f
		typeface = Typeface.MONOSPACE
		val padding = (8 * resources.displayMetrics.density).toInt()
		setPadding(padding, padding, padding, padding)
		importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
	}
	private var latestStats: StreamStatsEvent? = null
	private var latestQuality = NetworkQualitySnapshot.UNKNOWN
	private val qualityClassifier = NetworkQualityClassifier()
	private var attached = false
	private var destroyed = false
	private val redraw = object: Runnable
	{
		override fun run()
		{
			if(destroyed || !attached)
				return
			textView.text = StreamDiagnosticsFormatter.format(latestStats, uiState(), latestQuality)
			textView.postDelayed(this, REDRAW_INTERVAL_MS)
		}
	}

	fun update(stats: StreamStatsEvent)
	{
		latestStats = stats
		latestQuality = qualityClassifier.update(stats, uiState().networkLink)
	}

	fun show(anchor: View)
	{
		if(attached || destroyed)
			return
		val token = anchor.windowToken
		if(token == null)
		{
			anchor.post { show(anchor) }
			return
		}
		val margin = (8 * activity.resources.displayMetrics.density).toInt()
		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
			WINDOW_FLAGS,
			PixelFormat.TRANSLUCENT
		).apply {
			this.token = token
			gravity = Gravity.TOP or Gravity.START
			x = margin
			y = margin
		}
		textView.text = StreamDiagnosticsFormatter.format(latestStats, uiState(), latestQuality)
		activity.windowManager.addView(textView, params)
		attached = true
		textView.postDelayed(redraw, REDRAW_INTERVAL_MS)
	}

	fun destroy()
	{
		destroyed = true
		textView.removeCallbacks(redraw)
		if(attached)
		{
			runCatching { activity.windowManager.removeViewImmediate(textView) }
			attached = false
		}
	}
}
