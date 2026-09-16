// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.os.Build
import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadUnbufferedDispatchTest
{
	@Test
	fun disabledByDefaultEvenOnCapableSdk()
	{
		assertFalse(
			StreamActivity.shouldRequestUnbufferedGamepadDispatch(
				source = InputDevice.SOURCE_JOYSTICK,
				sdkInt = Build.VERSION_CODES.R,
				enabled = false
			)
		)
	}

	@Test
	fun requestedForJoystickAndGamepadSourcesWhenEnabledOnApi30Plus()
	{
		assertTrue(
			StreamActivity.shouldRequestUnbufferedGamepadDispatch(
				source = InputDevice.SOURCE_JOYSTICK,
				sdkInt = Build.VERSION_CODES.R,
				enabled = true
			)
		)
		assertTrue(
			// A physical gamepad's analog-stick events carry both bits; SOURCE_GAMEPAD alone
			// (buttons only, SOURCE_CLASS_BUTTON) never reaches onGenericMotionEvent by itself.
			StreamActivity.shouldRequestUnbufferedGamepadDispatch(
				source = InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD,
				sdkInt = Build.VERSION_CODES.TIRAMISU,
				enabled = true
			)
		)
	}

	@Test
	fun notRequestedBelowApi30EvenWhenEnabled()
	{
		// requestUnbufferedDispatch(int source) was added in API 30; the older
		// requestUnbufferedDispatch(MotionEvent) overload is documented for touch events only,
		// so there is no way to request unbuffered joystick dispatch on API 24-29.
		assertFalse(
			StreamActivity.shouldRequestUnbufferedGamepadDispatch(
				source = InputDevice.SOURCE_JOYSTICK,
				sdkInt = Build.VERSION_CODES.Q,
				enabled = true
			)
		)
	}

	@Test
	fun notRequestedForNonJoystickSources()
	{
		assertFalse(
			StreamActivity.shouldRequestUnbufferedGamepadDispatch(
				source = InputDevice.SOURCE_TOUCHSCREEN,
				sdkInt = Build.VERSION_CODES.R,
				enabled = true
			)
		)
		assertFalse(
			StreamActivity.shouldRequestUnbufferedGamepadDispatch(
				source = InputDevice.SOURCE_MOUSE,
				sdkInt = Build.VERSION_CODES.R,
				enabled = true
			)
		)
	}
}
