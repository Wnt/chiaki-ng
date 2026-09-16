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
		assertFalse(config.pacingHighRefreshEnabled)
		assertEquals(2, config.pacingMode)
		assertEquals(0, config.presenterLead)
		assertFalse(config.boundedAgeEnabled)
		assertEquals(2, config.maxFrameAgePeriods)
		assertFalse(config.nonblockingProducer)
		assertEquals(0, config.recoveryStrategy)
		assertFalse(config.dejitterEnabled)
		assertEquals(12, config.dejitterFloorMs)
		assertEquals(32, config.dejitterCapMs)
		assertEquals(2, config.dejitterQueueAgeFrames)
	}
}
