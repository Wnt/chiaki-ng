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

enum class PsnConsoleAction { PLAY }

data class PsnConsoleActionState(val duid: String, val action: PsnConsoleAction)

data class PsnPlayRequest(val console: PsnConsole)

enum class PsnErrorRecovery { RETRY, SIGN_IN }

data class PsnActionError(
	val message: String,
	val recovery: PsnErrorRecovery
)

enum class OnboardingHomeState
{
	WELCOME,
	ACCOUNT_CONSOLES,
	HOME
}

/**
 * The welcome screen is only for a first run with nothing to show: a console found on the network
 * is listed straight away, so a new user starts from their console rather than from a choice.
 */
internal fun onboardingHomeState(
	configuredConsoleCount: Int,
	psnSignedIn: Boolean,
	discoveredConsoleCount: Int = 0
): OnboardingHomeState = when
{
	configuredConsoleCount > 0 -> OnboardingHomeState.HOME
	psnSignedIn || discoveredConsoleCount > 0 -> OnboardingHomeState.ACCOUNT_CONSOLES
	else -> OnboardingHomeState.WELCOME
}

/** The unlinked console on the account that a sign-in started for [consoleName] should link. */
internal fun psnConsoleNamed(consoles: List<PsnConsole>, consoleName: String?): PsnConsole?
{
	val name = consoleName?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
	return consoles.firstOrNull { it.registeredHost == null && it.device.name.trim().lowercase() == name }
}

internal fun shouldLoadPsnConsoleList(
	psnRemotePlayEnabled: Boolean,
	psnSignInEnabled: Boolean,
	psnAccountId: String?
): Boolean = psnRemotePlayEnabled && psnSignInEnabled && !psnAccountId.isNullOrBlank()

sealed interface PsnConsoleListState
{
	data object Hidden : PsnConsoleListState
	data object Loading : PsnConsoleListState
	data object Ready : PsnConsoleListState
	data class Error(val message: String) : PsnConsoleListState
}
