// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistTargetTest
{
	@Test fun newRegistrationAlwaysTargetsPS5()
	{
		val info = RegistInfo.forPS5("192.0.2.1", false, ByteArray(RegistInfo.ACCOUNT_ID_SIZE), 12345678)
		assertEquals(Target.PS5_1, info.target)
		assertTrue(info.target.isPS5)
		assertNull(info.psnOnlineId)
	}

	@Test fun registrationFactoryHasNoTargetParameter()
	{
		val factory = RegistInfo.Companion::class.java.methods.single { it.name == "forPS5" }
		assertFalse(factory.parameterTypes.any { it == Target::class.java })
	}

	@Test fun unknownTargetValuesResolveToPS5()
	{
		for(value in listOf(-1, 1, 1001, 999999, 1000200, Int.MAX_VALUE))
			assertEquals(Target.PS5_1, Target.fromValue(value))
	}

	@Test fun storedPS4ValuesStayDetectableAsUnsupported()
	{
		for(value in listOf(0, 800, 900, 1000))
			assertFalse(Target.fromValue(value).isPS5)
		assertTrue(Target.fromValue(1000000).isPS5)
		assertTrue(Target.fromValue(1000100).isPS5)
	}
}
