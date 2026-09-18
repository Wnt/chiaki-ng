// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnScreenControlsPolicyTest
{
	@Test
	fun touchscreenShowsTheControlsAsThePreferenceSays()
	{
		assertTrue(OnScreenControlsPolicy(true).visible)
		assertFalse(OnScreenControlsPolicy(false).visible)
	}

	@Test
	fun windowWithoutTouchscreenHidesTheControls()
	{
		val policy = OnScreenControlsPolicy(true)
		policy.touchscreenChanged(false)
		assertFalse(policy.visible)
	}

	@Test
	fun toggleWithoutTouchscreenOverridesTheWindowButIsNotSaved()
	{
		val policy = OnScreenControlsPolicy(true)
		policy.touchscreenChanged(false)
		assertFalse(policy.toggle(true))
		assertTrue(policy.visible)
		assertFalse(policy.toggle(false))
		assertFalse(policy.visible)
	}

	@Test
	fun touchscreenReturningRestoresThePreferenceAndDropsTheOverride()
	{
		val policy = OnScreenControlsPolicy(true)
		policy.touchscreenChanged(false)
		policy.toggle(false)
		policy.touchscreenChanged(true)
		assertTrue(policy.visible)
		policy.touchscreenChanged(false)
		assertFalse(policy.visible)
	}

	@Test
	fun toggleOnTouchscreenIsSaved()
	{
		val policy = OnScreenControlsPolicy(true)
		assertTrue(policy.toggle(false))
		assertFalse(policy.visible)
		policy.touchscreenChanged(false)
		policy.touchscreenChanged(true)
		assertFalse(policy.visible)
	}
}
