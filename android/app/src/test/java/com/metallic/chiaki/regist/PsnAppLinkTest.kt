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

	// PLE-260: this must hold with no preference set, i.e. on a fresh install. The redirect
	// recovery this exercises was previously gated behind a default-off preference, so a
	// fresh install dead-ended on Sony's blank redirect page.
	@Test
	fun freshInstall_pausedExternalLogin_returnIsHandled()
	{
		assertTrue(shouldHandlePsnBrowserReturn(false, false, true, true))
	}

	@Test
	fun initialResume_isNotMistakenForBrowserReturn()
	{
		assertFalse(shouldHandlePsnBrowserReturn(false, false, true, false))
	}

	@Test
	fun redirectDelivery_isNotMistakenForStranding()
	{
		assertFalse(shouldHandlePsnBrowserReturn(false, true, true, true))
	}

	@Test
	fun embeddedFlow_doesNotHandleBrowserReturn()
	{
		assertFalse(shouldHandlePsnBrowserReturn(true, false, true, true))
	}
}
