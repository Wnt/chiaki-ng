// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PsnAuthTest
{
	@Test
	fun userIdToAccountId_encodesEightLittleEndianBytes()
	{
		assertEquals("FYHpffQQIhE=", psnAccountIdFromUserId("1234567890123456789"))
	}

	@Test
	fun redirectParser_extractsEncodedCode()
	{
		assertEquals(
			PsnRedirect.Code("ticket/value+1"),
			parsePsnRedirect("https://remoteplay.dl.playstation.net/remoteplay/redirect?state=x&code=ticket%2Fvalue%2B1")
		)
	}

	@Test
	fun redirectParser_rejectsMissingCode()
	{
		assertSame(
			PsnRedirect.Invalid,
			parsePsnRedirect("https://remoteplay.dl.playstation.net/remoteplay/redirect?state=x")
		)
	}

	@Test
	fun redirectParser_ignoresLookalikeRedirect()
	{
		assertSame(
			PsnRedirect.NotRedirect,
			parsePsnRedirect("https://example.com/remoteplay/redirect?code=secret")
		)
	}
}
