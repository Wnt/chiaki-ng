// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.res.Configuration

/**
 * Whether the on-screen touch controls are shown (PLE-384).
 *
 * A window whose configuration reports no touchscreen — a Samsung DeX window on a TV, driven by
 * mouse, keyboard and a controller — does not show them, whatever the saved preference says: they
 * would only cover the video. The overlay's controller toggle still works there, as an override
 * for this window that is not saved, so the phone keeps the preference the user chose on the phone.
 * The override lapses when the touchscreen comes or goes (the cable is pulled or plugged).
 */
internal class OnScreenControlsPolicy(private var preferenceEnabled: Boolean)
{
	var touchscreenAvailable = true
		private set
	private var windowOverride: Boolean? = null

	val visible: Boolean get() = windowOverride ?: (preferenceEnabled && touchscreenAvailable)

	/** The user flipped the toggle. Returns true when [enabled] is to be saved as the preference. */
	fun toggle(enabled: Boolean): Boolean
	{
		if(!touchscreenAvailable)
		{
			windowOverride = enabled
			return false
		}
		preferenceEnabled = enabled
		windowOverride = null
		return true
	}

	fun touchscreenChanged(available: Boolean)
	{
		if(available == touchscreenAvailable)
			return
		touchscreenAvailable = available
		windowOverride = null
	}
}

internal fun hasTouchscreen(configuration: Configuration): Boolean =
	configuration.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
