// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.metallic.chiaki.remote

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

	private fun fixture(name: String): String = javaClass.getResource("/psn/$name")!!.readText()

	private class MemoryTokenStore(var value: String?) : PsnRefreshTokenStore
	{
		override fun read(): String? = value
		override fun write(value: String) { this.value = value }
	}
}
