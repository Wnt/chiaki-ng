// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.view.MotionEvent

internal data class TriggerAxes(val l2: Int, val r2: Int)

internal class TriggerAxisResolver(
	private val rangesForDevice: (Int) -> Map<Int, Float?>
)
{
	private val axesByDevice = mutableMapOf<Int, TriggerAxes>()

	fun axesFor(deviceId: Int): TriggerAxes = axesByDevice.getOrPut(deviceId) {
		val ranges = rangesForDevice(deviceId)
		TriggerAxes(
			l2 = selectAxis(ranges, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
			r2 = selectAxis(ranges, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
		)
	}

	private fun selectAxis(ranges: Map<Int, Float?>, primary: Int, fallback: Int): Int
	{
		if((ranges[primary] ?: 0.0f) > 0.0f)
			return primary
		if((ranges[fallback] ?: 0.0f) > 0.0f)
			return fallback
		return primary
	}
}
