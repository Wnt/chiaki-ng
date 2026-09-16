// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.discovery.serverMac
import com.metallic.chiaki.lib.DiscoveryHost

enum class HomeConsoleStatus { ON, STANDBY, REMOTE, REGISTRATION_REQUIRED }

data class HomeConsole(
	val key: String,
	val name: String,
	val detail: String?,
	val status: HomeConsoleStatus,
	val displayHost: DisplayHost? = null,
	val psnConsole: PsnConsole? = null,
	val manualDisplayHost: ManualDisplayHost? = displayHost as? ManualDisplayHost
)

internal fun mergeHomeConsolesLegacy(
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

private data class LocalConsoleGroup(
	val hosts: MutableList<DisplayHost>,
	val identities: MutableSet<String>
)

private fun normalizedAddress(address: String): String = address
	.trim()
	.removePrefix("[")
	.removeSuffix("]")
	.trimEnd('.')
	.lowercase()

private fun localIdentities(host: DisplayHost): Set<String> = buildSet {
	host.registeredHost?.also { registration ->
		if(registration.id > 0L)
			add("registration:${registration.id}")
		add("mac:${registration.serverMac.toString().lowercase()}")
	}
	(host as? DiscoveredDisplayHost)?.discoveredHost?.serverMac?.also {
		add("mac:${it.toString().lowercase()}")
	}
	normalizedAddress(host.host).takeIf(String::isNotBlank)?.also { add("address:$it") }
}

private fun registrationsMatch(first: RegisteredHost?, second: RegisteredHost?): Boolean =
	first != null && second != null &&
		((first.id > 0L && second.id > 0L && first.id == second.id) || first.serverMac == second.serverMac)

private fun groupLocalConsoles(hosts: List<DisplayHost>): List<LocalConsoleGroup>
{
	val groups = mutableListOf<LocalConsoleGroup>()
	hosts.forEach { host ->
		val identities = localIdentities(host)
		val matches = groups.filter { group -> group.identities.any(identities::contains) }
		if(matches.isEmpty())
		{
			groups += LocalConsoleGroup(mutableListOf(host), identities.toMutableSet())
			return@forEach
		}

		val destination = matches.first()
		destination.hosts += host
		destination.identities += identities
		matches.drop(1).forEach { duplicate ->
			destination.hosts += duplicate.hosts
			destination.identities += duplicate.identities
			groups.remove(duplicate)
		}
	}
	return groups
}

private fun discoveryStatePriority(host: DiscoveredDisplayHost): Int = when(host.discoveredHost.state)
{
	DiscoveryHost.State.READY -> 0
	DiscoveryHost.State.STANDBY -> 1
	else -> 2
}

private fun homeConsole(
	group: LocalConsoleGroup,
	psnConsole: PsnConsole?
): HomeConsole
{
	val discovered = group.hosts.filterIsInstance<DiscoveredDisplayHost>()
		.minByOrNull(::discoveryStatePriority)
	val manual = group.hosts.filterIsInstance<ManualDisplayHost>().firstOrNull()
	val registration = discovered?.registeredHost
		?: manual?.registeredHost
		?: group.hosts.firstNotNullOfOrNull(DisplayHost::registeredHost)
		?: psnConsole?.registeredHost
	val displayHost = discovered?.takeIf { it.registeredHost != null }
		?: manual?.takeIf { it.registeredHost != null }
		?: discovered
		?: manual
		?: group.hosts.first()
	val address = discovered?.host?.takeIf(String::isNotBlank)
		?: manual?.host?.takeIf(String::isNotBlank)
		?: displayHost.host.takeIf(String::isNotBlank)
	val status = when
	{
		registration == null -> HomeConsoleStatus.REGISTRATION_REQUIRED
		discovered?.discoveredHost?.state == DiscoveryHost.State.READY -> HomeConsoleStatus.ON
		discovered?.discoveredHost?.state == DiscoveryHost.State.STANDBY -> HomeConsoleStatus.STANDBY
		else -> HomeConsoleStatus.REMOTE
	}
	val key = registration?.serverMac?.toString()?.lowercase()
		?: group.identities.firstOrNull { it.startsWith("mac:") }
		?: group.identities.firstOrNull { it.startsWith("address:") }
		?: "local:${displayHost.id.orEmpty()}:${displayHost.name.orEmpty().trim().lowercase()}"
	return HomeConsole(
		key = key,
		name = discovered?.name?.takeIf(String::isNotBlank)
			?: registration?.serverNickname?.takeIf(String::isNotBlank)
			?: psnConsole?.device?.name?.takeIf(String::isNotBlank)
			?: displayHost.name?.takeIf(String::isNotBlank)
			?: address.orEmpty(),
		detail = discovered?.discoveredHost?.runningAppName?.takeIf(String::isNotBlank) ?: address,
		status = status,
		displayHost = displayHost,
		psnConsole = psnConsole,
		manualDisplayHost = manual
	)
}

internal fun mergeHomeConsoles(
	hosts: List<DisplayHost>,
	psnConsoles: List<PsnConsole>
): List<HomeConsole>
{
	val remainingPsn = psnConsoles.toMutableList()
	val local = groupLocalConsoles(hosts).map { group ->
		val registrations = group.hosts.mapNotNull(DisplayHost::registeredHost)
		val psn = remainingPsn.firstOrNull { remote ->
			registrations.any { localRegistration ->
				registrationsMatch(localRegistration, remote.registeredHost)
			}
		}?.also(remainingPsn::remove)
		homeConsole(group, psn)
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
