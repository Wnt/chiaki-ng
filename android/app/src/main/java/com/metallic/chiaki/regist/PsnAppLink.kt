// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

internal const val PSN_REDIRECT_HOST = "remoteplay.dl.playstation.net"

/** Android 11 and earlier do not expose per-app domain-selection state. */
internal fun isPsnRedirectAppLinkAllowed(context: Context): Boolean
{
	if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
		return true
	return PsnAppLinkApi31.isAllowed(context)
}

internal fun psnAppLinkSettingsIntent(context: Context): Intent?
{
	if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
		return null
	return PsnAppLinkApi31.settingsIntent(context)
}

internal fun isPsnRedirectDomainAllowed(linkHandlingAllowed: Boolean, hostState: Int?): Boolean =
	linkHandlingAllowed && (hostState == 1 || hostState == 2)

internal fun shouldHandlePsnBrowserReturn(
	reliableRedirectEnabled: Boolean,
	embeddedBrowser: Boolean,
	handlingRedirect: Boolean,
	externalLoginLaunched: Boolean,
	browserPauseObserved: Boolean
): Boolean = reliableRedirectEnabled && !embeddedBrowser && !handlingRedirect &&
	externalLoginLaunched && browserPauseObserved

@RequiresApi(Build.VERSION_CODES.S)
private object PsnAppLinkApi31
{
	fun settingsIntent(context: Context) =
		Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
			data = Uri.parse("package:${context.packageName}")
		}

	fun isAllowed(context: Context): Boolean
	{
		val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return false
		val state = try {
			manager.getDomainVerificationUserState(context.packageName)
		} catch(_: PackageManager.NameNotFoundException) {
			return false
		} ?: return false
		return isPsnRedirectDomainAllowed(
			state.isLinkHandlingAllowed,
			state.hostToStateMap[PSN_REDIRECT_HOST]
		)
	}
}
