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
}
