// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.lib.DiscoveryHost

enum class HomeConsoleStatus { ON, STANDBY, REMOTE, REGISTRATION_REQUIRED }

data class HomeConsole(
	val key: String,
	val name: String,
	val detail: String?,
	val status: HomeConsoleStatus,
	val displayHost: DisplayHost? = null,
	val psnConsole: PsnConsole? = null
)

internal fun mergeHomeConsoles(
	hosts: List<DisplayHost>,
	psnConsoles: List<PsnConsole>
): List<HomeConsole>
{
	val remainingPsn = psnConsoles.toMutableList()
	val local = hosts.map { host ->
		val psn = remainingPsn.firstOrNull { remote ->
			val localRegistration = host.registeredHost
			val remoteRegistration = remote.registeredHost
			localRegistration != null && remoteRegistration != null &&
				((localRegistration.id > 0L && localRegistration.id == remoteRegistration.id) ||
					localRegistration.serverMac == remoteRegistration.serverMac)
		}?.also(remainingPsn::remove)
		val discovered = host as? DiscoveredDisplayHost
		val status = when
		{
			host.registeredHost == null -> HomeConsoleStatus.REGISTRATION_REQUIRED
			discovered?.discoveredHost?.state == DiscoveryHost.State.READY -> HomeConsoleStatus.ON
			discovered?.discoveredHost?.state == DiscoveryHost.State.STANDBY -> HomeConsoleStatus.STANDBY
			else -> HomeConsoleStatus.REMOTE
		}
		HomeConsole(
			key = host.registeredHost?.serverMac?.toString() ?: "local:${host.host}",
			name = host.name?.takeIf(String::isNotBlank) ?: psn?.device?.name ?: host.host,
			detail = discovered?.discoveredHost?.runningAppName?.takeIf(String::isNotBlank)
				?: host.host.takeIf(String::isNotBlank),
			status = status,
			displayHost = host,
			psnConsole = psn
		)
	}
	val remoteOnly = remainingPsn.map { console ->
		HomeConsole(
			key = "psn:${console.device.duid}",
			name = console.device.name,
			detail = null,
			status = if(console.registeredHost == null)
				HomeConsoleStatus.REGISTRATION_REQUIRED else HomeConsoleStatus.REMOTE,
			psnConsole = console
		)
	}
	return (local + remoteOnly).sortedWith(
		compareBy<HomeConsole> { it.status.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
	)
}
