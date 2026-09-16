// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PsnUdpCodecTest
{
	@Test fun parsesXorMappedStunAddress()
	{
		val transactionId = ByteArray(12) { it.toByte() }
		val address = InetAddress.getByName("203.0.113.9").address
		val cookie = byteArrayOf(0x21, 0x12, 0xa4.toByte(), 0x42)
		val response = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
			putShort(0x0101)
			putShort(12)
			putInt(0x2112a442)
			put(transactionId)
			putShort(0x0020)
			putShort(8)
			put(0)
			put(1)
			putShort((45678 xor 0x2112).toShort())
			for(index in address.indices) put((address[index].toInt() xor cookie[index].toInt()).toByte())
		}.array()

		val mapped = PsnStunCodec.mappedAddress(response, transactionId)

		assertEquals("203.0.113.9", mapped.address.hostAddress)
		assertEquals(45678, mapped.port)
	}

	@Test fun validatesCandidateHandshakeRequestAndResponse()
	{
		val local = ByteArray(20) { 1 }
		val peer = ByteArray(20) { 2 }
		val requestId = byteArrayOf(3, 4, 5, 6, 7)
		val request = PsnCandidateHandshake.request(local, peer, 0x1234, 0x5678, requestId)
		val response = PsnCandidateHandshake.response(request, local, peer, 0x1234, 0x5678)

		assertEquals(88, request.size)
		assertTrue(PsnCandidateHandshake.isRequest(request))
		assertTrue(PsnCandidateHandshake.isResponse(response, requestId))
		assertFalse(PsnCandidateHandshake.isResponse(response, byteArrayOf(0, 0, 0, 0, 0)))
		assertArrayEquals(requestId, response.copyOfRange(0x4b, 0x50))
	}
}
