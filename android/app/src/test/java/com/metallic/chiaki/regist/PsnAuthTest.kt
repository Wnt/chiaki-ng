// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import org.junit.Assert.assertEquals
import org.junit.Test

class PsnAuthTest
{
	@Test
	fun userIdToAccountId_encodesEightLittleEndianBytes()
	{
		assertEquals("FYHpffQQIhE=", psnAccountIdFromUserId("1234567890123456789"))
	}
}
