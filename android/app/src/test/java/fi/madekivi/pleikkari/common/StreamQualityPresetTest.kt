// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityPresetTest
{
	@Test
	fun defaultMatchesCurrentEffectiveStreamDefaults()
	{
		val preset = Preferences.streamQualityPresetDefault

		assertEquals(Preferences.StreamQualityPreset.LOW_LATENCY, preset)
		assertEquals(Preferences.resolutionDefault, preset.resolution)
		assertEquals(Preferences.fpsDefault, preset.fps)
		assertEquals(Preferences.codecDefault, preset.codec)
	}

	@Test
	fun namedPresetsMatchTheUxContract()
	{
		assertPreset(
			Preferences.StreamQualityPreset.BALANCED,
			Preferences.Resolution.RES_1080P,
			Preferences.FPS.FPS_60,
			Preferences.Codec.CODEC_H265
		)
		assertPreset(
			Preferences.StreamQualityPreset.LOW_LATENCY,
			Preferences.Resolution.RES_1080P,
			Preferences.FPS.FPS_60,
			Preferences.Codec.CODEC_H265
		)
		assertPreset(
			Preferences.StreamQualityPreset.DATA_SAVER,
			Preferences.Resolution.RES_540P,
			Preferences.FPS.FPS_30,
			Preferences.Codec.CODEC_H264
		)
	}

	@Test
	fun lowLatencyPinsAnExplicitBitrateSoItStaysDistinctFromBalancedAt1080p()
	{
		assertEquals(10000, Preferences.StreamQualityPreset.LOW_LATENCY.bitrate)
		assertNull(Preferences.StreamQualityPreset.BALANCED.bitrate)
	}

	@Test
	fun everyPresetUsesTheProvenDecoderDefaults()
	{
		Preferences.StreamQualityPreset.values().forEach { preset ->
			assertTrue(preset.decoderOperatingRateDefault)
			assertTrue(preset.decoderOperatingRateAuto)
			assertEquals(0, preset.decoderOperatingRate)
			assertTrue(preset.decoderInputThreadEnabled)
			assertFalse(preset.debandingEnabled)
		}
	}

	private fun assertPreset(
		preset: Preferences.StreamQualityPreset,
		resolution: Preferences.Resolution,
		fps: Preferences.FPS,
		codec: Preferences.Codec
	)
	{
		assertEquals(resolution, preset.resolution)
		assertEquals(fps, preset.fps)
		assertEquals(codec, preset.codec)
	}
}
