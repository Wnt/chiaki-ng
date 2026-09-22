// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.stream

import android.net.wifi.WifiManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiLockModePolicyTest
{
	@Suppress("DEPRECATION")
	@Test
	fun usesHighPerformanceModeBeforeAndroid10()
	{
		assertEquals(
			WifiManager.WIFI_MODE_FULL_HIGH_PERF,
			wifiLockModeForSdk(Build.VERSION_CODES.P)
		)
	}

	@Test
	fun usesLowLatencyModeFromAndroid10()
	{
		assertEquals(
			WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
			wifiLockModeForSdk(Build.VERSION_CODES.Q)
		)
		assertEquals(
			WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
			wifiLockModeForSdk(Build.VERSION_CODES.VANILLA_ICE_CREAM)
		)
	}
}
