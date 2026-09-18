// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.lib.ConnectedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionModeClassifierTest
{
	@Test
	fun lanAddressOnWifiIsDirect()
	{
		val event = ConnectedEvent(
			relay = false, peerHost = "192.168.1.164", peerPort = 9295,
			mtuIn = 1454, rttUs = 3_000, measured = true
		)
		val snapshot = ConnectionModeClassifier.classify(event, vpnActive = false)
		assertEquals(ConnectionMode.DIRECT, snapshot.mode)
	}

	@Test
	fun rfc1918AddressWithVpnTransportIsVpn()
	{
		val event = ConnectedEvent(
			relay = false, peerHost = "10.8.0.5", peerPort = 9295,
			mtuIn = 1380, rttUs = 42_000, measured = true
		)
		val snapshot = ConnectionModeClassifier.classify(event, vpnActive = true)
		assertEquals(ConnectionMode.VPN, snapshot.mode)
	}

	@Test
	fun rfc1918AddressWithoutVpnTransportIsDirect()
	{
		// Same private address, but no VPN transport on the phone: still Direct, not VPN.
		val event = ConnectedEvent(
			relay = false, peerHost = "10.8.0.5", peerPort = 9295,
			mtuIn = 1454, rttUs = 3_000, measured = true
		)
		val snapshot = ConnectionModeClassifier.classify(event, vpnActive = true.not())
		assertEquals(ConnectionMode.DIRECT, snapshot.mode)
	}

	@Test
	fun publicAddressWithVpnTransportIsStillDirectNotVpn()
	{
		// VPN active on the phone is not sufficient on its own -- the peer address must also be
		// RFC1918, or this isn't the "private address reached through a tunnel" case the ticket means.
		val event = ConnectedEvent(
			relay = false, peerHost = "203.0.113.9", peerPort = 9295,
			mtuIn = 1454, rttUs = 3_000, measured = true
		)
		val snapshot = ConnectionModeClassifier.classify(event, vpnActive = true)
		assertEquals(ConnectionMode.DIRECT, snapshot.mode)
	}

	@Test
	fun holepunchOrRemoteConnectionSessionIsRelay()
	{
		val event = ConnectedEvent(
			relay = true, peerHost = "", peerPort = 0,
			mtuIn = 1454, rttUs = 1_000, measured = false
		)
		// Relay is decided from the library's own state even with a VPN transport present --
		// it must win over the address-based inference, not just apply when inference is silent.
		val snapshot = ConnectionModeClassifier.classify(event, vpnActive = true)
		assertEquals(ConnectionMode.RELAY, snapshot.mode)
	}

	@Test
	fun sentinelBeforeConnectedEventIsUnknownNotDirectOrVpn()
	{
		val snapshot = ConnectionModeSnapshot.UNKNOWN
		assertEquals(ConnectionMode.UNKNOWN, snapshot.mode)
		assertFalse(snapshot.mode == ConnectionMode.DIRECT)
		assertFalse(snapshot.mode == ConnectionMode.VPN)
	}

	@Test
	fun blankPeerHostWithoutRelayIsUnknownNotAsserted()
	{
		// e.g. a ConnectedEvent that somehow carries no address and isn't flagged relay:
		// must not guess Direct or VPN from nothing.
		val event = ConnectedEvent(
			relay = false, peerHost = "", peerPort = 0,
			mtuIn = 0, rttUs = 0, measured = false
		)
		val snapshot = ConnectionModeClassifier.classify(event, vpnActive = false)
		assertEquals(ConnectionMode.UNKNOWN, snapshot.mode)
	}

	@Test
	fun mtuAndRttProvenanceCarriesThroughFromTheFallbackFlag()
	{
		val fallback = ConnectedEvent(
			relay = false, peerHost = "192.168.1.164", peerPort = 9295,
			mtuIn = 1454, rttUs = 1_000, measured = false
		)
		val snapshot = ConnectionModeClassifier.classify(fallback, vpnActive = false)
		assertFalse(snapshot.measured)
		assertEquals(1454L, snapshot.mtuIn)
	}

	@Test
	fun isPrivateIPv4RejectsPublicAndMalformedAddresses()
	{
		assertFalse(ConnectionModeClassifier.isPrivateIPv4("8.8.8.8"))
		assertFalse(ConnectionModeClassifier.isPrivateIPv4("not-an-address"))
		assertFalse(ConnectionModeClassifier.isPrivateIPv4("172.32.0.1")) // just outside 172.16/12
	}
}
