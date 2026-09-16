// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsnAppLinkTest
{
	@Test
	fun selectedRedirectHost_isAllowed()
	{
		assertTrue(isPsnRedirectDomainAllowed(linkHandlingAllowed = true, hostState = 1))
	}

	@Test
	fun verifiedRedirectHost_isAllowed()
	{
		assertTrue(isPsnRedirectDomainAllowed(linkHandlingAllowed = true, hostState = 2))
	}

	@Test
	fun unselectedRedirectHost_isRejected()
	{
		assertFalse(isPsnRedirectDomainAllowed(linkHandlingAllowed = true, hostState = 0))
		assertFalse(isPsnRedirectDomainAllowed(linkHandlingAllowed = true, hostState = null))
	}

	@Test
	fun globallyDisabledLinkHandling_isRejected()
	{
		assertFalse(isPsnRedirectDomainAllowed(linkHandlingAllowed = false, hostState = 1))
		assertFalse(isPsnRedirectDomainAllowed(linkHandlingAllowed = false, hostState = 2))
	}

	@Test
	fun pausedExternalLogin_returnIsHandled()
	{
		assertTrue(shouldHandlePsnBrowserReturn(true, false, false, true, true))
	}

	@Test
	fun initialResume_isNotMistakenForBrowserReturn()
	{
		assertFalse(shouldHandlePsnBrowserReturn(true, false, false, true, false))
	}

	@Test
	fun redirectDelivery_isNotMistakenForStranding()
	{
		assertFalse(shouldHandlePsnBrowserReturn(true, false, true, true, true))
	}

	@Test
	fun legacyAndEmbeddedFlows_doNotHandleBrowserReturn()
	{
		assertFalse(shouldHandlePsnBrowserReturn(false, false, false, true, true))
		assertFalse(shouldHandlePsnBrowserReturn(true, true, false, true, true))
	}
}
