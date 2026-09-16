// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsnSignInFlowTest
{
	@Test
	fun sonySignInHosts_mayMoveSignInToBrowser()
	{
		assertTrue(isPsnSignInHost("my.account.sony.com"))
		assertTrue(isPsnSignInHost("ca.account.sony.com"))
		assertTrue(isPsnSignInHost("auth.api.sonyentertainmentnetwork.com"))
		assertTrue(isPsnSignInHost("SONY.COM."))
	}

	@Test
	fun otherHosts_mayNotMoveSignInToBrowser()
	{
		assertFalse(isPsnSignInHost(null))
		assertFalse(isPsnSignInHost("evilsony.com"))
		assertFalse(isPsnSignInHost("sony.com.example.org"))
	}

	@Test
	fun clipCopiedAfterBrowserOpened_isTaken()
	{
		assertTrue(isPsnClipFromThisSignIn(clipTimestampMs = 2_000, browserOpenedAtMs = 1_000))
		assertTrue(isPsnClipFromThisSignIn(clipTimestampMs = null, browserOpenedAtMs = 1_000))
	}

	@Test
	fun clipCopiedBeforeBrowserOpened_isIgnored()
	{
		assertFalse(isPsnClipFromThisSignIn(clipTimestampMs = 999, browserOpenedAtMs = 1_000))
	}

	@Test
	fun passkeyHook_reportsOnlyExplicitPasskeyRequests()
	{
		assertTrue(PSN_PASSKEY_HOOK_JS.contains("window.$PSN_PASSKEY_BRIDGE.passkeyRequested()"))
		assertTrue(PSN_PASSKEY_HOOK_JS.contains("options.mediation !== 'conditional'"))
	}

	@Test
	fun pausedBrowserSignIn_returnIsHandled()
	{
		assertTrue(shouldHandlePsnBrowserReturn(true, false, true, true))
	}

	@Test
	fun initialResume_isNotMistakenForBrowserReturn()
	{
		assertFalse(shouldHandlePsnBrowserReturn(true, false, true, false))
	}

	@Test
	fun redirectDelivery_isNotMistakenForStranding()
	{
		assertFalse(shouldHandlePsnBrowserReturn(true, true, true, true))
	}

	@Test
	fun inAppSignIn_doesNotHandleBrowserReturn()
	{
		assertFalse(shouldHandlePsnBrowserReturn(false, false, true, true))
	}
}
