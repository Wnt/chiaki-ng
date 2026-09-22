// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActionLayoutTest
{
	@Test
	fun portraitReservesSpaceAboveAction()
	{
		val insets = HomeActionLayout.contentInsets(1080, 2280, 612, 2080, 48, false)

		assertEquals(HomeActionInsets(end = 0, bottom = 248), insets)
		assertTrue(2280 - insets.bottom <= 2080 - 48)
	}

	@Test
	fun landscapeReservesSpaceBeforeAction()
	{
		val insets = HomeActionLayout.contentInsets(2280, 1080, 1780, 880, 48, true)

		assertEquals(HomeActionInsets(end = 548, bottom = 0), insets)
		assertTrue(2280 - insets.end <= 1780 - 48)
	}

	@Test
	fun actionAlreadyOutsideContentNeedsNoInset()
	{
		assertEquals(
			HomeActionInsets(0, 0),
			HomeActionLayout.contentInsets(100, 100, 120, 120, 10, true)
		)
	}

	// PLE-522: PLE-241's LinearLayout weight split (2 of 3) was meant to give consoleListContainer
	// a guaranteed larger minimum height share in landscape versus a squeezable top section, but
	// PLE-549 found the weight split itself did not hold under real content overflow and replaced
	// it with a ConstraintLayout Guideline + explicit minimum height. Either way, the landscape
	// `end` inset only depends on contentRight/actionLeft/clearance, so a taller or shorter
	// container must not change it: contentBottom (and therefore the container's height share)
	// never enters the landscape formula.
	@Test
	fun landscapeEndInsetIsIndependentOfContainerHeight()
	{
		val actionLeft = 1780
		val actionTop = 880
		val squeezedShare = HomeActionLayout.contentInsets(2280, 320, actionLeft, actionTop, 48, true)
		val guaranteedShare = HomeActionLayout.contentInsets(2280, 960, actionLeft, actionTop, 48, true)

		assertEquals(squeezedShare.end, guaranteedShare.end)
		assertEquals(HomeActionInsets(end = 548, bottom = 0), squeezedShare)
		assertEquals(HomeActionInsets(end = 548, bottom = 0), guaranteedShare)
	}
}
