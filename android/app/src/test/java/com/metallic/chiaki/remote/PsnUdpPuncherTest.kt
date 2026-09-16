// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.metallic.chiaki.remote

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64

class PsnUdpPuncherTest
{
	/**
	 * PLE-312: `InetSocketAddress(host, port)` resolves in the constructor. The puncher is built on
	 * the main thread, where that lookup threw NetworkOnMainThreadException and killed every Play.
	 */
	@Test fun defaultStunServersAreNotResolvedAtConstruction()
	{
		val puncher = DatagramPsnHolePuncher()
		assertEquals(4, puncher.stunServers.size)
		assertTrue("resolved at construction: ${puncher.stunServers}", puncher.stunServers.all { it.isUnresolved })
	}

	@Test fun prepareResolvesTheStunServerOffTheCallerThread() = runBlocking {
		val stun = DatagramSocket(0, InetAddress.getLoopbackAddress())
		val threads = recordedThreads()
		val responder = thread(name = "fake-stun", isDaemon = true) {
			val packet = DatagramPacket(ByteArray(64), 64)
			runCatching {
				stun.receive(packet)
				val request = packet.data.copyOf(packet.length)
				val transactionId = request.copyOfRange(8, 20)
				val source = packet.socketAddress as InetSocketAddress
				stun.send(DatagramPacket(bindingResponse(transactionId, source), 32, source))
			}
		}
		val puncher = DatagramPsnHolePuncher(
			stunServers = listOf(InetSocketAddress.createUnresolved("localhost", stun.localPort))
		)
		val peer = PsnConnectionRequest(
			sid = 7, peerSid = 0, skey = Base64.Default.encode(ByteArray(16)),
			candidate = listOf(PsnCandidate("STUN", "192.0.2.10", port = 41000)),
			localHashedId = Base64.Default.encode(ByteArray(20))
		)
		val executor = callerExecutor()
		try
		{
			val preparation = runBlocking(executor.asCoroutineDispatcher()) {
				val prepared = puncher.prepare(peer, "12345678901234567")
				recordThread(threads)
				prepared
			}
			preparation.use {
				val mapped = it.offer.candidate.first { candidate -> candidate.type == "STUN" }
				assertEquals("127.0.0.1", mapped.addr)
				assertEquals(45678, mapped.port)
			}
		}
		finally
		{
			executor.shutdownNow()
			stun.close()
			responder.join(2_000)
		}
		// The caller thread only ran the coroutine's own continuation, never the STUN exchange.
		assertEquals(listOf(threads.first()), threads.onCallerThread())
	}

	/** A STUN binding success with one XOR-MAPPED-ADDRESS of 127.0.0.1:45678, whatever the request came from. */
	private fun bindingResponse(transactionId: ByteArray, @Suppress("UNUSED_PARAMETER") source: InetSocketAddress): ByteArray =
		ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
			putShort(0x0101)
			putShort(12)
			putInt(0x2112a442)
			put(transactionId)
			putShort(0x0020)
			putShort(8)
			put(0)
			put(1)
			putShort((45678 xor 0x2112).toShort())
			put(byteArrayOf((127 xor 0x21).toByte(), 0x12, 0xa4.toByte(), (1 xor 0x42).toByte()))
		}.array()
}
