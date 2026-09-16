// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidChiakiVideoPresenterConfigTest
{
	@Test
	fun defaultsPreserveExistingPresenterBehaviour()
	{
		val config = AndroidChiakiVideoPresenterConfig()

		assertFalse(config.pacingEnabled)
		assertEquals(2, config.pacingMode)
		assertEquals(0, config.presenterLead)
		assertFalse(config.boundedAgeEnabled)
		assertEquals(2, config.maxFrameAgePeriods)
		assertFalse(config.nonblockingProducer)
		assertEquals(0, config.recoveryStrategy)
	}
}
