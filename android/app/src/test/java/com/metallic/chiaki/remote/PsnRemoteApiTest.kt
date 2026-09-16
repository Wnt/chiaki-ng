// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.metallic.chiaki.remote

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class PsnRemoteApiTest
{
	private lateinit var server: MockWebServer
	private lateinit var store: MemoryTokenStore
	private lateinit var api: PsnRemoteApi

	@Before fun setUp()
	{
		server = MockWebServer().also { it.start() }
		store = MemoryTokenStore("refresh-token-placeholder")
		api = PsnRemoteApi(
			OkHttpClient(),
			PsnRemoteEndpoints(server.url("/token").toString(), server.url("/api/").toString(), server.url("/push-address").toString()),
			store,
			Json { ignoreUnknownKeys = true; explicitNulls = false },
			clockMillis = { 1_000L }
		)
	}

	@After fun tearDown() = server.shutdown()

	@Test fun refreshRotationAndDeviceList() = runBlocking {
		server.enqueue(MockResponse().setBody(fixture("token_refresh.json")))
		server.enqueue(MockResponse().setBody(fixture("devices.json")))

		val devices = api.listDevices()

		assertEquals("rotated-refresh-placeholder", store.value)
		assertEquals(listOf(PsnDevice(
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
			"Fixture PS5"
		)), devices)
		val tokenRequest = server.takeRequest()
		assertEquals("/token", tokenRequest.path)
		assertTrue(tokenRequest.body.readUtf8().contains("grant_type=refresh_token"))
		val devicesRequest = server.takeRequest()
		assertTrue(devicesRequest.path!!.startsWith("/api/cloudAssistedNavigation/v2/users/me/clients"))
		assertEquals("Bearer access-token-placeholder", devicesRequest.getHeader("Authorization"))
	}

	@Test fun retriesOneAuthorizationFailureWithFreshToken() = runBlocking {
		server.enqueue(MockResponse().setBody(fixture("token_refresh.json")))
		server.enqueue(MockResponse().setResponseCode(401))
		server.enqueue(MockResponse().setBody("""{"access_token":"retry-access","refresh_token":"retry-refresh","expires_in":3600}"""))
		server.enqueue(MockResponse().setBody(fixture("devices.json")))

		assertEquals(1, api.listDevices().size)
		assertEquals("retry-refresh", store.value)
		server.takeRequest() // initial refresh
		server.takeRequest() // rejected device request
		server.takeRequest() // forced refresh
		assertEquals("Bearer retry-access", server.takeRequest().getHeader("Authorization"))
	}

	// PLE-261: reading the body on the caller's dispatcher threw NetworkOnMainThreadException on Android.
	@Test fun responseBodiesAreNotReadOnTheCallerThread()
	{
		val readThreads = Collections.synchronizedSet(mutableSetOf<String>())
		val recordingApi = PsnRemoteApi(
			OkHttpClient.Builder().socketFactory(ReadRecordingSocketFactory(readThreads)).build(),
			PsnRemoteEndpoints(server.url("/token").toString(), server.url("/api/").toString(), server.url("/push-address").toString()),
			store,
			Json { ignoreUnknownKeys = true; explicitNulls = false },
			clockMillis = { 1_000L }
		)
		// Trickle the bodies so most of them are still on the wire when execute() has returned the headers.
		server.enqueue(MockResponse().setBody(fixture("token_refresh.json")).throttleBody(32, 30, TimeUnit.MILLISECONDS))
		server.enqueue(MockResponse().setBody(fixture("devices.json")).throttleBody(32, 30, TimeUnit.MILLISECONDS))

		val executor = Executors.newSingleThreadExecutor { Thread(it, "psn-caller") }
		try
		{
			val listing = runBlocking(executor.asCoroutineDispatcher()) { recordingApi.listDeviceListing() }
			assertEquals(1, listing.devices.size)
			assertTrue("clients before filtering", listing.clientCount >= listing.devices.size)
		}
		finally
		{
			executor.shutdownNow()
		}
		assertTrue("socket reads happened on: $readThreads", readThreads.isNotEmpty())
		assertTrue("socket read on the caller thread: $readThreads", readThreads.none { it.startsWith("psn-caller") })
	}

	@Test fun httpFailureCarriesStatusAndErrorExcerpt() = runBlocking {
		server.enqueue(MockResponse().setBody(fixture("token_refresh.json")))
		server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"code\":2285,\n\"message\":\"server\"}}"))

		val error = runCatching { api.listDevices() }.exceptionOrNull()

		assertTrue("got $error", error is PsnRemoteHttpException)
		error as PsnRemoteHttpException
		assertEquals(500, error.httpCode)
		assertEquals("PSN request failed (HTTP 500)", error.message)
		assertEquals("{\"error\":{\"code\":2285, \"message\":\"server\"}}", error.detail)
	}

	private class ReadRecordingSocketFactory(private val threads: MutableSet<String>) : SocketFactory()
	{
		override fun createSocket(): Socket = object : Socket()
		{
			override fun getInputStream(): InputStream = object : FilterInputStream(super.getInputStream())
			{
				override fun read(): Int { threads += Thread.currentThread().name; return super.read() }
				override fun read(b: ByteArray, off: Int, len: Int): Int
				{
					threads += Thread.currentThread().name
					return super.read(b, off, len)
				}
			}
		}
		override fun createSocket(host: String, port: Int): Socket = unsupported()
		override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
		override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
		override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
		private fun unsupported(): Nothing = throw UnsupportedOperationException()
	}

	private fun fixture(name: String): String = javaClass.getResource("/psn/$name")!!.readText()

	private class MemoryTokenStore(var value: String?) : PsnRefreshTokenStore
	{
		override fun read(): String? = value
		override fun write(value: String) { this.value = value }
	}
}
