// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTimestampSourceTest
{
	@Test fun unpacedWithoutRealPtsUsesSyntheticCounter()
	{
		assertEquals(VideoTimestampSource.SYNTHETIC_COUNTER,
			videoTimestampSource(realVideoTimestamps = false, pacingEnabled = false))
	}

	@Test fun pacedWithoutRealPtsUsesSyntheticCounter()
	{
		assertEquals(VideoTimestampSource.SYNTHETIC_COUNTER,
			videoTimestampSource(realVideoTimestamps = false, pacingEnabled = true))
	}

	@Test fun unpacedWithRealPtsUsesFrameIndex()
	{
		assertEquals(VideoTimestampSource.FRAME_INDEX,
			videoTimestampSource(realVideoTimestamps = true, pacingEnabled = false))
	}

	@Test fun pacedWithRealPtsUsesFrameIndex()
	{
		assertEquals(VideoTimestampSource.FRAME_INDEX,
			videoTimestampSource(realVideoTimestamps = true, pacingEnabled = true))
	}
}
