// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.stream

import fi.madekivi.pleikkari.lib.ConnectedEvent

/**
 * Direct: the console's LAN address, no tunnel.
 * Vpn: a private address reached through a tunnel the user chose.
 * Relay: the PSN data plane. Fires when the opt-in "PSN Remote Play" feature
 *   (Preferences.psnRemotePlayEnabled, default false) streams to a console
 *   that is not reachable on the LAN -- confirmed 2026-09-21 by an S25-over-VPN
 *   capture (PLE-500). Should never happen for a LAN-reachable console.
 * Unknown: no [ConnectedEvent] has arrived yet; must not be presented as any of the above.
 */
enum class ConnectionMode
{
	DIRECT, VPN, RELAY, UNKNOWN
}

data class ConnectionModeSnapshot(
	val mode: ConnectionMode,
	val peerHost: String,
	val peerPort: Int,
	val mtuIn: Long,
	val rttUs: Long,
	/** False when mtuIn/rttUs are the senkusha-failed fallback, not a measurement. */
	val measured: Boolean
)
{
	companion object
	{
		val UNKNOWN = ConnectionModeSnapshot(ConnectionMode.UNKNOWN, "", 0, 0, 0, false)
	}
}

object ConnectionModeClassifier
{
	/**
	 * Prefers the library's own state (relay) over inference. Direct vs. VPN is inference
	 * (RFC1918 peer address plus an active VPN network transport on the phone), since the
	 * library has no notion of "the local transport is a tunnel".
	 */
	fun classify(event: ConnectedEvent, vpnActive: Boolean): ConnectionModeSnapshot
	{
		val mode = when
		{
			event.relay -> ConnectionMode.RELAY
			event.peerHost.isBlank() -> ConnectionMode.UNKNOWN
			isPrivateIPv4(event.peerHost) && vpnActive -> ConnectionMode.VPN
			else -> ConnectionMode.DIRECT
		}
		return ConnectionModeSnapshot(
			mode = mode,
			peerHost = event.peerHost,
			peerPort = event.peerPort,
			mtuIn = event.mtuIn,
			rttUs = event.rttUs,
			measured = event.measured
		)
	}

	internal fun isPrivateIPv4(host: String): Boolean
	{
		val octets = host.split(".").takeIf { it.size == 4 }?.map { it.toIntOrNull() } ?: return false
		if(octets.any { it == null || it !in 0..255 })
			return false
		val a = octets[0]!!
		val b = octets[1]!!
		return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
	}
}
