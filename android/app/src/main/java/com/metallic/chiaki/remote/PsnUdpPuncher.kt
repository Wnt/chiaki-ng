// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.metallic.chiaki.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.io.encoding.Base64

data class PsnPunchedSocket(val socket: DatagramSocket, val candidate: PsnCandidate)

interface PsnPunchPreparation : Closeable
{
	val offer: PsnConnectionRequest
	suspend fun punch(): PsnPunchedSocket
}

interface PsnHolePuncher
{
	suspend fun prepare(peer: PsnConnectionRequest, accountId: String): PsnPunchPreparation
}

object PsnStunCodec
{
	private const val COOKIE = 0x2112A442

	fun request(transactionId: ByteArray): ByteArray
	{
		require(transactionId.size == 12)
		return ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
			.putShort(0x0001).putShort(0).putInt(COOKIE).put(transactionId).array()
	}

	fun mappedAddress(response: ByteArray, transactionId: ByteArray): InetSocketAddress
	{
		if(response.size < 20) throw PsnRemoteProtocolException("Short STUN response")
		val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
		if(buffer.short.toInt() and 0xffff != 0x0101) throw PsnRemoteProtocolException("Unexpected STUN response type")
		val length = buffer.short.toInt() and 0xffff
		if(length + 20 > response.size) throw PsnRemoteProtocolException("Truncated STUN response")
		if(buffer.int != COOKIE) throw PsnRemoteProtocolException("Invalid STUN magic cookie")
		val responseId = ByteArray(12).also(buffer::get)
		if(!responseId.contentEquals(transactionId)) throw PsnRemoteProtocolException("STUN transaction ID mismatch")
		var position = 20
		while(position + 4 <= 20 + length)
		{
			val type = ((response[position].toInt() and 0xff) shl 8) or (response[position + 1].toInt() and 0xff)
			val attributeLength = ((response[position + 2].toInt() and 0xff) shl 8) or (response[position + 3].toInt() and 0xff)
			if(position + 4 + attributeLength > response.size) throw PsnRemoteProtocolException("Truncated STUN attribute")
			if((type == 0x0020 || type == 0x0001) && attributeLength >= 8)
			{
				val family = response[position + 5].toInt() and 0xff
				if(family != 0x01) throw PsnRemoteProtocolException("STUN returned a non-IPv4 address")
				var port = ((response[position + 6].toInt() and 0xff) shl 8) or (response[position + 7].toInt() and 0xff)
				val address = response.copyOfRange(position + 8, position + 12)
				if(type == 0x0020)
				{
					port = port xor (COOKIE ushr 16)
					val cookie = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(COOKIE).array()
					for(index in address.indices) address[index] = (address[index].toInt() xor cookie[index].toInt()).toByte()
				}
				return InetSocketAddress(InetAddress.getByAddress(address), port)
			}
			position += 4 + ((attributeLength + 3) and 3.inv())
		}
		throw PsnRemoteProtocolException("STUN response did not contain a mapped address")
	}
}

object PsnCandidateHandshake
{
	const val SIZE = 88
	private const val REQUEST = 0x06000000
	private const val RESPONSE = 0x07000000

	fun request(localHashedId: ByteArray, peerHashedId: ByteArray, localSid: Int, peerSid: Int, requestId: ByteArray): ByteArray =
		packet(REQUEST, localHashedId, peerHashedId, localSid, peerSid, requestId)

	fun response(
		request: ByteArray,
		localHashedId: ByteArray,
		peerHashedId: ByteArray,
		localSid: Int,
		peerSid: Int,
		peerAddress: InetSocketAddress? = null
	): ByteArray
	{
		if(!isRequest(request)) throw PsnRemoteProtocolException("Candidate packet is not a request")
		return packet(RESPONSE, localHashedId, peerHashedId, localSid, peerSid, request.copyOfRange(0x4b, 0x50)).also { response ->
			val address = peerAddress?.address?.address
			if(address != null && address.size == 4)
			{
				val encoded = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
				encoded.putShort(0x50, localSid.toShort())
				encoded.putShort(0x52, peerSid.toShort())
				encoded.putShort(0x54, localSid.toShort())
				for(index in address.indices) response[0x50 + index] = (response[0x50 + index].toInt() xor address[index].toInt()).toByte()
				val port = byteArrayOf((peerAddress.port ushr 8).toByte(), peerAddress.port.toByte())
				for(index in port.indices) response[0x54 + index] = (response[0x54 + index].toInt() xor port[index].toInt()).toByte()
			}
		}
	}

	fun isRequest(packet: ByteArray): Boolean = packet.size == SIZE && type(packet) == REQUEST
	fun isResponse(packet: ByteArray, requestId: ByteArray): Boolean =
		packet.size == SIZE && type(packet) == RESPONSE && packet.copyOfRange(0x4b, 0x50).contentEquals(requestId)

	private fun packet(type: Int, local: ByteArray, peer: ByteArray, localSid: Int, peerSid: Int, requestId: ByteArray): ByteArray
	{
		require(local.size == 20 && peer.size == 20 && requestId.size == 5)
		return ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN).apply {
			putInt(0, type)
			position(0x04); put(local)
			position(0x24); put(peer)
			putShort(0x44, localSid.toShort())
			putShort(0x46, peerSid.toShort())
			position(0x4b); put(requestId)
		}.array()
	}

	private fun type(packet: ByteArray): Int = ByteBuffer.wrap(packet, 0, 4).order(ByteOrder.BIG_ENDIAN).int
}

/**
 * The STUN servers are kept unresolved until [prepare] runs on [Dispatchers.IO]. `InetSocketAddress(host, port)`
 * resolves the name in the constructor, and this puncher is constructed on the main thread
 * (`AndroidPsnRemoteClient.controller()`), where Android throws NetworkOnMainThreadException for a DNS
 * lookup (PLE-312). Any address given here must be unresolved or literal.
 */
class DatagramPsnHolePuncher(
	private val random: SecureRandom = SecureRandom(),
	internal val stunServers: List<InetSocketAddress> = listOf(
		InetSocketAddress.createUnresolved("stun.moonlight-stream.org", 3478),
		InetSocketAddress.createUnresolved("stun.l.google.com", 19302),
		InetSocketAddress.createUnresolved("stun1.l.google.com", 19302),
		InetSocketAddress.createUnresolved("stun2.l.google.com", 19302)
	)
) : PsnHolePuncher
{
	override suspend fun prepare(peer: PsnConnectionRequest, accountId: String): PsnPunchPreparation = withContext(Dispatchers.IO) {
		val socket = DatagramSocket(null).apply {
			reuseAddress = true
			bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
		}
		try
		{
			val mapping = discoverMapping(socket)
			val localAddress = socket.localAddress.takeUnless { it.isAnyLocalAddress } as? Inet4Address
				?: routeAddress(resolve(stunServers.first()))
			val sid = random.nextInt(0x10000)
			val localHash = ByteArray(20).also(random::nextBytes)
			val candidates = listOf(
				PsnCandidate("LOCAL", localAddress.hostAddress ?: "0.0.0.0", port = socket.localPort),
				PsnCandidate("STUN", mapping.address.hostAddress ?: "0.0.0.0", port = mapping.port),
				PsnCandidate("STATIC", mapping.address.hostAddress ?: "0.0.0.0", port = socket.localPort)
			)
			val offer = PsnConnectionRequest(
				sid = sid,
				peerSid = peer.sid,
				skey = Base64.Default.encode(ByteArray(16)),
				candidate = candidates,
				localPeerAddr = PsnPeerAddress(accountId, "REMOTE_PLAY"),
				localHashedId = Base64.Default.encode(localHash)
			)
			Preparation(socket, offer, peer, localHash, random)
		}
		catch(error: Throwable)
		{
			socket.close()
			throw error
		}
	}

	private fun discoverMapping(socket: DatagramSocket): InetSocketAddress
	{
		var lastError: Throwable? = null
		for(server in stunServers)
		{
			try
			{
				val transactionId = ByteArray(12).also(random::nextBytes)
				val request = PsnStunCodec.request(transactionId)
				socket.send(DatagramPacket(request, request.size, resolve(server)))
				socket.soTimeout = 5_000
				val packet = DatagramPacket(ByteArray(1024), 1024)
				socket.receive(packet)
				return PsnStunCodec.mappedAddress(packet.data.copyOf(packet.length), transactionId)
			}
			catch(error: Throwable) { lastError = error }
		}
		throw PsnUnsupportedNatException("No configured STUN server returned an IPv4 mapping: ${lastError?.message}")
	}

	/** DNS happens here, inside [prepare] on IO, never where the puncher is constructed. */
	private fun resolve(server: InetSocketAddress): InetSocketAddress =
		if(server.isUnresolved) InetSocketAddress(InetAddress.getByName(server.hostString), server.port) else server

	private fun routeAddress(server: InetSocketAddress): Inet4Address
	{
		DatagramSocket().use { probe ->
			probe.connect(server)
			return probe.localAddress as? Inet4Address
				?: throw PsnUnsupportedNatException("No IPv4 route to the STUN server")
		}
	}

	private class Preparation(
		private val socket: DatagramSocket,
		override val offer: PsnConnectionRequest,
		private val peer: PsnConnectionRequest,
		private val localHash: ByteArray,
		private val random: SecureRandom
	) : PsnPunchPreparation
	{
		private var transferred = false

		override suspend fun punch(): PsnPunchedSocket = withContext(Dispatchers.IO) {
			val peerHash = runCatching { Base64.Default.decode(peer.localHashedId) }
				.getOrElse { throw PsnRemoteProtocolException("Invalid console hashed ID", it) }
			if(peerHash.size != 20) throw PsnRemoteProtocolException("Console hashed ID is not 20 bytes")
			val destinations = peer.candidate.mapNotNull { candidate ->
				runCatching { candidate to InetSocketAddress(InetAddress.getByName(candidate.addr), candidate.port) }.getOrNull()
			}
			if(destinations.isEmpty()) throw PsnUnsupportedNatException("Console did not advertise a usable IPv4 candidate")
			val requestIds = List(3) { ByteArray(5).also(random::nextBytes) }
			val requests = requestIds.map { PsnCandidateHandshake.request(localHash, peerHash, offer.sid, peer.sid, it) }
			val receive = DatagramPacket(ByteArray(PsnCandidateHandshake.SIZE), PsnCandidateHandshake.SIZE)
			var responseIndex = 0
			for(attempt in 0 until 20)
			{
				val request = requests[responseIndex]
				for((_, address) in destinations) socket.send(DatagramPacket(request, request.size, address))
				socket.soTimeout = 500
				try
				{
					socket.receive(receive)
					val bytes = receive.data.copyOf(receive.length)
					if(PsnCandidateHandshake.isRequest(bytes))
					{
						val response = PsnCandidateHandshake.response(
							bytes, localHash, peerHash, offer.sid, peer.sid, receive.socketAddress as InetSocketAddress
						)
						socket.send(DatagramPacket(response, response.size, receive.socketAddress))
						continue
					}
					if(PsnCandidateHandshake.isResponse(bytes, requestIds[responseIndex]))
					{
						if(responseIndex < requests.lastIndex)
						{
							responseIndex++
							val next = requests[responseIndex]
							socket.send(DatagramPacket(next, next.size, receive.socketAddress))
							continue
						}
						val selected = peer.candidate.firstOrNull {
							it.addr == receive.address.hostAddress && it.port == receive.port
						} ?: PsnCandidate("DERIVED", receive.address.hostAddress ?: throw PsnRemoteProtocolException("Candidate source had no address"), port = receive.port)
						socket.connect(receive.socketAddress)
						transferred = true
						return@withContext PsnPunchedSocket(socket, selected)
					}
				}
				catch(_: SocketTimeoutException) { }
			}
			throw PsnUnsupportedNatException("No console candidate completed the UDP handshake")
		}

		override fun close()
		{
			if(!transferred) socket.close()
		}
	}
}
