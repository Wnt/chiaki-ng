// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Enables Android's edge-to-edge window contract for ordinary app screens.
 * StreamActivity deliberately owns a separate immersive-system-bar policy.
 */
fun AppCompatActivity.enableAppEdgeToEdge()
{
	WindowCompat.setDecorFitsSystemWindows(window, false)
	WindowCompat.getInsetsController(window, window.decorView).apply {
		isAppearanceLightStatusBars = isLightThemeColor(
			com.google.android.material.R.attr.colorPrimarySurface
		)
		isAppearanceLightNavigationBars = isLightThemeColor(
			android.R.attr.windowBackground,
			android.R.attr.colorBackground
		)
	}
}

/** Adds system-bar and display-cutout safe space without discarding layout padding. */
fun View.applySystemBarInsets(
	left: Boolean = true,
	top: Boolean = true,
	right: Boolean = true,
	bottom: Boolean = true
)
{
	val initialLeft = paddingLeft
	val initialTop = paddingTop
	val initialRight = paddingRight
	val initialBottom = paddingBottom
	ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
		val insets = windowInsets.getInsets(
			WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
		)
		view.setPadding(
			initialLeft + if(left) insets.left else 0,
			initialTop + if(top) insets.top else 0,
			initialRight + if(right) insets.right else 0,
			initialBottom + if(bottom) insets.bottom else 0
		)
		windowInsets
	}
	ViewCompat.requestApplyInsets(this)
}

private fun AppCompatActivity.isLightThemeColor(attribute: Int, fallbackAttribute: Int? = null): Boolean
{
	val color = themeColor(attribute) ?: fallbackAttribute?.let(::themeColor)
	if(color != null)
		return ColorUtils.calculateLuminance(color) > 0.5
	return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
		Configuration.UI_MODE_NIGHT_YES
}

private fun AppCompatActivity.themeColor(attribute: Int): Int?
{
	val value = TypedValue()
	if(!theme.resolveAttribute(attribute, value, true))
		return null
	return if(value.resourceId != 0)
		try {
			ContextCompat.getColor(this, value.resourceId)
		} catch(_: Resources.NotFoundException) {
			null
		}
	else if(value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
		value.data
	else
		null
}
