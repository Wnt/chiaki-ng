// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.ManualHost
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.Target
import com.metallic.chiaki.remote.PsnDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeConsoleTest
{
	private fun registered(id: Long, name: String) = RegisteredHost(
		id = id,
		target = Target.PS5_1,
		apSsid = null,
		apBssid = null,
		apKey = null,
		apName = null,
		serverMac = MacAddress(id),
		serverNickname = name,
		rpRegistKey = ByteArray(16),
		rpKeyType = 0,
		rpKey = ByteArray(16)
	)

	@Test fun mergesMatchingLocalAndPsnConsoleAndUsesLocalPowerState()
	{
		val registration = registered(1, "Living Room")
		val discovered = DiscoveredDisplayHost(
			registration,
			DiscoveryHost(
				DiscoveryHost.State.READY, 0U, "192.168.1.2", null,
				"00030010", "Living Room", null, null, null, "Astro's Playroom"
			)
		)
		val psn = PsnConsole(PsnDevice("duid", "Living Room"), registration)

		val result = mergeHomeConsoles(listOf(discovered), listOf(psn))

		assertEquals(1, result.size)
		assertEquals(HomeConsoleStatus.ON, result.single().status)
		assertEquals("Astro's Playroom", result.single().detail)
		assertEquals(psn, result.single().psnConsole)
	}

	@Test fun exposesRemoteAndRegistrationRequiredStates()
	{
		val remoteRegistration = registered(2, "Office")
		val result = mergeHomeConsoles(
			listOf(ManualDisplayHost(null, ManualHost(host = "10.0.0.8", registeredHost = null))),
			listOf(PsnConsole(PsnDevice("duid", "Office"), remoteRegistration))
		)

		assertEquals(
			listOf(HomeConsoleStatus.REMOTE, HomeConsoleStatus.REGISTRATION_REQUIRED),
			result.map(HomeConsole::status)
		)
	}

	private fun discovered(name: String, hostId: String, registration: RegisteredHost? = null) = DiscoveredDisplayHost(
		registration,
		DiscoveryHost(
			DiscoveryHost.State.READY, 0U, "192.168.1.164", null,
			"00030010", name, null, hostId, null, null
		)
	)

	@Test fun unlinkedNetworkConsoleAndUnlinkedAccountConsoleAreOneRow()
	{
		val psn = PsnConsole(PsnDevice("duid", "PS5-466"), null)

		val result = mergeHomeConsoles(listOf(discovered("PS5-466", "C0151B3DD8BC")), listOf(psn))

		assertEquals(1, result.size)
		assertEquals(HomeConsoleStatus.REGISTRATION_REQUIRED, result.single().status)
		assertEquals(psn, result.single().psnConsole)
		assertEquals("192.168.1.164", result.single().displayHost?.host)
	}

	@Test fun linkedNetworkConsoleIsNotClaimedByAnUnlinkedAccountConsoleOfTheSameName()
	{
		val registration = registered(3, "PS5-466")
		val psn = PsnConsole(PsnDevice("duid", "PS5-466"), null)

		val result = mergeHomeConsoles(listOf(discovered("PS5-466", "000000000003", registration)), listOf(psn))

		assertEquals(2, result.size)
		assertEquals(null, result.first { it.displayHost != null }.psnConsole)
	}

	@Test fun differentlyNamedAccountConsoleStaysItsOwnRow()
	{
		val psn = PsnConsole(PsnDevice("duid", "Office"), null)

		val result = mergeHomeConsoles(listOf(discovered("PS5-466", "C0151B3DD8BC")), listOf(psn))

		assertEquals(2, result.size)
	}
}
