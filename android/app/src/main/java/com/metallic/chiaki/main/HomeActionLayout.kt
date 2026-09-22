// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.res.Configuration
import androidx.core.view.updatePadding
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivityMainBinding

data class HomeActionInsets(val end: Int, val bottom: Int)

/** Keeps console-card content out of the floating Add console action's bounds. */
object HomeActionLayout
{
	fun contentInsets(
		contentRight: Int,
		contentBottom: Int,
		actionLeft: Int,
		actionTop: Int,
		clearance: Int,
		landscape: Boolean
	): HomeActionInsets
	{
		val end = (contentRight - actionLeft + clearance).coerceAtLeast(0)
		val bottom = (contentBottom - actionTop + clearance).coerceAtLeast(0)
		return if(landscape) HomeActionInsets(end = end, bottom = 0)
		else HomeActionInsets(end = 0, bottom = bottom)
	}

	/**
	 * Re-applies [contentInsets] on every layout of the home screen. Shared by MainActivity and
	 * PLE-523's HomeScreenBoundsTest, so the gated bounds assertion exercises this exact code.
	 */
	fun install(binding: ActivityMainBinding)
	{
		binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply(binding) }
	}

	private fun apply(binding: ActivityMainBinding)
	{
		val container = binding.consoleListContainer
		val action = binding.addConsoleButton
		if(container.width == 0 || container.height == 0 || action.width == 0 || action.height == 0)
			return
		val resources = binding.root.resources
		val location = IntArray(2)
		val actionLocation = IntArray(2)
		container.getLocationInWindow(location)
		action.getLocationInWindow(actionLocation)
		val insets = contentInsets(
			contentRight = location[0] + container.width,
			contentBottom = location[1] + container.height,
			actionLeft = actionLocation[0],
			actionTop = actionLocation[1],
			clearance = resources.getDimensionPixelSize(R.dimen.home_action_clearance),
			landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
		)
		if(container.paddingRight != insets.end || container.paddingBottom != insets.bottom)
			container.updatePadding(right = insets.end, bottom = insets.bottom)
		val summaryEnd = maxOf(resources.getDimensionPixelSize(R.dimen.home_summary_end_padding), insets.end)
		if(binding.streamSummaryContent.paddingRight != summaryEnd)
			binding.streamSummaryContent.updatePadding(right = summaryEnd)
	}
}
