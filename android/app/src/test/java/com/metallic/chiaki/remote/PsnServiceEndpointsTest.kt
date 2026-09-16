// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import com.metallic.chiaki.regist.PsnRedirect
import com.metallic.chiaki.regist.parsePsnRedirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.URI

class PsnServiceEndpointsTest
{
	@Test
	fun buildWithoutMockProperty_talksToSony()
	{
		assertSame(PsnServiceEndpoints.Production, PsnServiceEndpoints.current)
		assertNull(PsnServiceEndpoints.current.mockHost)
		assertEquals("remoteplay.dl.playstation.net", PsnServiceEndpoints.current.redirectHost)
	}

	@Test
	fun mock_keepsSonysPathsAndOnlySwapsTheHost()
	{
		val production = PsnServiceEndpoints.Production
		val mock = PsnServiceEndpoints.mock("psn.example")
		fun pathAndQuery(url: String) = URI(url).let { it.path to it.query }
		listOf(
			production.authorizeUrl to mock.authorizeUrl,
			production.tokenUrl to mock.tokenUrl,
			production.redirectUrl to mock.redirectUrl,
			production.remote.tokenUrl to mock.remote.tokenUrl,
			production.remote.pushLookupUrl to mock.remote.pushLookupUrl,
			production.remote.webApiBase to mock.remote.webApiBase
		).forEach { (sony, mocked) ->
			assertEquals("psn.example", URI(mocked).host)
			assertEquals(pathAndQuery(sony), pathAndQuery(mocked))
		}
	}

	@Test
	fun redirectParser_followsTheEndpointsHost()
	{
		val mock = PsnServiceEndpoints.mock("psn.example")
		assertEquals(PsnRedirect.Code("abc"), parsePsnRedirect("https://psn.example/remoteplay/redirect?code=abc", mock))
		assertSame(PsnRedirect.NotRedirect, parsePsnRedirect("https://remoteplay.dl.playstation.net/remoteplay/redirect?code=abc", mock))
		assertSame(PsnRedirect.NotRedirect, parsePsnRedirect("https://psn.example/remoteplay/redirect?code=abc"))
	}
}
