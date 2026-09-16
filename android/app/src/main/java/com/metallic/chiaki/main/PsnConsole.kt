// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.remote.PsnDevice

data class PsnConsole(
	val device: PsnDevice,
	val registeredHost: RegisteredHost?
)

internal fun matchPsnConsoles(
	devices: List<PsnDevice>,
	registeredHosts: List<RegisteredHost>
): List<PsnConsole>
{
	val registeredByName = registeredHosts
		.filter { !it.serverNickname.isNullOrBlank() }
		.associateBy { it.serverNickname!!.lowercase() }
	return devices.map { device ->
		PsnConsole(device, registeredByName[device.name.lowercase()])
	}
}

enum class PsnConsoleAction { REGISTER, WAKE }

data class PsnConsoleActionState(val duid: String, val action: PsnConsoleAction)

sealed interface PsnConsoleListState
{
	data object Hidden : PsnConsoleListState
	data object Loading : PsnConsoleListState
	data object Ready : PsnConsoleListState
	data class Error(val message: String) : PsnConsoleListState
}
