// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.lib.Target
import com.metallic.chiaki.remote.PsnDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PsnConsoleTest
{
	@Test fun matchesPsnDeviceToRegisteredHostByNicknameIgnoringCase()
	{
		val registered = RegisteredHost(
			target = Target.PS5_1,
			apSsid = null,
			apBssid = null,
			apKey = null,
			apName = null,
			serverMac = MacAddress(1),
			serverNickname = "Living Room PS5",
			rpRegistKey = ByteArray(16),
			rpKeyType = 0,
			rpKey = ByteArray(16)
		)
		val devices = listOf(
			PsnDevice("one", "living room ps5"),
			PsnDevice("two", "Office PS5")
		)

		val result = matchPsnConsoles(devices, listOf(registered))

		assertEquals(registered, result[0].registeredHost)
		assertNull(result[1].registeredHost)
	}

	@Test fun firstLaunchShowsWelcomeUntilPsnIsChosen()
	{
		assertEquals(OnboardingHomeState.WELCOME, onboardingHomeState(0, false))
		assertEquals(OnboardingHomeState.ACCOUNT_CONSOLES, onboardingHomeState(0, true))
	}

	@Test fun consoleFoundOnTheNetworkSkipsWelcome()
	{
		assertEquals(OnboardingHomeState.ACCOUNT_CONSOLES, onboardingHomeState(0, false, 1))
		assertEquals(OnboardingHomeState.WELCOME, onboardingHomeState(0, false, 0))
	}

	@Test fun signInLinksOnlyAnUnlinkedAccountConsoleWithTheTappedName()
	{
		val unlinked = PsnConsole(PsnDevice("one", "PS5-466"), null)
		val other = PsnConsole(PsnDevice("two", "Office PS5"), null)

		assertEquals(unlinked, psnConsoleNamed(listOf(other, unlinked), " ps5-466 "))
		assertNull(psnConsoleNamed(listOf(other), "PS5-466"))
		assertNull(psnConsoleNamed(listOf(unlinked), null))
		assertNull(psnConsoleNamed(listOf(unlinked), ""))
	}

	@Test fun configuredConsoleAlwaysReturnsHome()
	{
		assertEquals(OnboardingHomeState.HOME, onboardingHomeState(1, false))
		assertEquals(OnboardingHomeState.HOME, onboardingHomeState(1, true))
	}
}
