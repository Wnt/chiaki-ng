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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

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

	/**
	 * PLE-261: reading the body on the caller's dispatcher threw NetworkOnMainThreadException on Android.
	 * PLE-312: every public suspend function is exercised from the caller thread, and the list is
	 * checked against the class by reflection, so a new call site cannot ship without being covered.
	 */
	@Test fun responseBodiesAreNotReadOnTheCallerThread()
	{
		val readThreads = recordedThreads()
		val recordingApi = PsnRemoteApi(
			OkHttpClient.Builder().socketFactory(ReadRecordingSocketFactory(readThreads)).build(),
			PsnRemoteEndpoints(server.url("/token").toString(), server.url("/api/").toString(), server.url("/push-address").toString()),
			store,
			Json { ignoreUnknownKeys = true; explicitNulls = false },
			clockMillis = { 1_000L }
		)
		// Trickle the bodies so most of them are still on the wire when execute() has returned the headers.
		fun trickle(body: String, code: Int = 200) =
			MockResponse().setResponseCode(code).setBody(body).throttleBody(32, 30, TimeUnit.MILLISECONDS)
		server.enqueue(trickle(fixture("token_refresh.json")))
		server.enqueue(trickle(fixture("devices.json")))
		server.enqueue(trickle(fixture("devices.json")))
		server.enqueue(trickle("""{"fqdn":"push.example.invalid"}"""))
		server.enqueue(trickle(fixture("session_create.json")))
		server.enqueue(trickle("""{"accepted":true,"padding":"${"x".repeat(200)}"}"""))
		server.enqueue(trickle("""{"accepted":true,"padding":"${"x".repeat(200)}"}"""))
		server.enqueue(trickle("""{"accepted":true,"padding":"${"x".repeat(200)}"}"""))
		server.enqueue(trickle("""{"access_token":"forced-access","refresh_token":"forced-refresh","expires_in":3600}"""))

		val device = PsnDevice("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff", "Fixture PS5")
		val covered = mutableSetOf<String>()
		val executor = callerExecutor()
		try
		{
			runBlocking(executor.asCoroutineDispatcher()) {
				assertEquals("access-token-placeholder", recordingApi.accessToken()).also { covered += "accessToken" }
				assertEquals(1, recordingApi.listDevices().size).also { covered += "listDevices" }
				val listing = recordingApi.listDeviceListing().also { covered += "listDeviceListing" }
				assertTrue("clients before filtering", listing.clientCount >= listing.devices.size)
				assertEquals("wss://push.example.invalid/np/pushNotification", recordingApi.resolvePushWebSocket())
					.also { covered += "resolvePushWebSocket" }
				val session = recordingApi.createSession("push-context").also { covered += "createSession" }
				recordingApi.startConsole(session, device, "AAAA", "BBBB").also { covered += "startConsole" }
				recordingApi.sendSignal(session, device, PsnSignalMessage("RESULT", 1)).also { covered += "sendSignal" }
				recordingApi.deleteSession(session.sessionId).also { covered += "deleteSession" }
				assertEquals("forced-access", recordingApi.accessToken(forceRefresh = true))
			}
		}
		finally
		{
			executor.shutdownNow()
		}
		assertEquals("every public suspend function of PsnRemoteApi must be exercised here",
			publicSuspendFunctions(PsnRemoteApi::class.java), covered)
		assertTrue("socket reads happened on: $readThreads", readThreads.isNotEmpty())
		assertEquals("socket read on the caller thread", emptyList<String>(), readThreads.onCallerThread())
	}

	/**
	 * PLE-327: the envelope the controller logs is byte-for-byte what is posted, in upstream's shape
	 * (`holepunch.c:139-145`), and the recipient duid is lowercase hex however PSN listed it (`:144`).
	 */
	@Test fun signalEnvelopeIsWhatSendSignalPosts() = runBlocking {
		server.enqueue(MockResponse().setBody(fixture("token_refresh.json")))
		server.enqueue(MockResponse().setResponseCode(204))
		val session = PsnSession("11111111-2222-4333-8444-555555555555", "12345678901234567")
		val device = PsnDevice("00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF", "Fixture PS5")
		val message = PsnSignalMessage("RESULT", 7)

		api.sendSignal(session, device, message)

		server.takeRequest() // token refresh
		val posted = server.takeRequest()
		assertEquals("/api/sessionManager/v1/remotePlaySessions/11111111-2222-4333-8444-555555555555/sessionMessage", posted.path)
		val envelope = api.signalEnvelope(session, device, message)
		assertEquals(envelope, posted.body.readUtf8())
		assertEquals(
			"""{"channel":"remote_play:1","payload":"ver=1.0, type=text, body={\"action\":\"RESULT\",\"reqId\":7,\"error\":0,\"connRequest\":{}}",""" +
				""""to":[{"accountId":"12345678901234567","deviceUniqueId":"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff","platform":"PS5"}]}""",
			envelope
		)
	}

	/**
	 * PLE-327: upstream's `session_connrequest_fmt` is a printf format, so an OFFER always carries
	 * `natType`, `defaultRouteMacAddr`, `localPeerAddr.platform` and `mappedAddr`/`mappedPort` on every
	 * candidate (`holepunch.c:160-176`). kotlinx.serialization drops a field that equals its default,
	 * which cost us all five and left the console with no natType to read: it re-offered and then
	 * TERMINATEd (proven on the S25, build/captures/ple326/run3-logcat.txt). Every field stays on the
	 * wire, in upstream's order.
	 */
	@Test fun offerCarriesEveryFieldUpstreamAlwaysSends() = runBlocking {
		val session = PsnSession("11111111-2222-4333-8444-555555555555", "12345678901234567")
		val device = PsnDevice("00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF", "Fixture PS5")
		val request = PsnConnectionRequest(
			sid = 33990,
			peerSid = 12976,
			skey = "AAAAAAAAAAAAAAAAAAAAAA==",
			candidate = listOf(
				PsnCandidate("STATIC", "88.192.35.229", port = 37589),
				PsnCandidate("LOCAL", "192.168.1.221", port = 37589)
			),
			localPeerAddr = PsnPeerAddress("12345678901234567", "REMOTE_PLAY"),
			localHashedId = "RMvOiOW1BMAlMn3BJBW5Jzp8zJ8="
		)

		val envelope = api.signalEnvelope(session, device, PsnSignalMessage("OFFER", 2, connRequest = request))

		val body = envelope.substringAfter("body=").substringBefore("\",\"to\"")
		for(field in listOf("natType", "defaultRouteMacAddr", "mappedAddr", "mappedPort", "platform"))
			assertTrue("$field missing from $body", body.contains(field))
		assertEquals(
			"""{\"action\":\"OFFER\",\"reqId\":2,\"error\":0,\"connRequest\":""" +
				"""{\"sid\":33990,\"peerSid\":12976,\"skey\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"natType\":2,""" +
				"""\"candidate\":[""" +
				"""{\"type\":\"STATIC\",\"addr\":\"88.192.35.229\",\"mappedAddr\":\"0.0.0.0\",\"port\":37589,\"mappedPort\":0},""" +
				"""{\"type\":\"LOCAL\",\"addr\":\"192.168.1.221\",\"mappedAddr\":\"0.0.0.0\",\"port\":37589,\"mappedPort\":0}],""" +
				"""\"defaultRouteMacAddr\":\"\",""" +
				"""\"localPeerAddr\":{\"accountId\":\"12345678901234567\",\"platform\":\"REMOTE_PLAY\"},""" +
				"""\"localHashedId\":\"RMvOiOW1BMAlMn3BJBW5Jzp8zJ8=\"}}""",
			body
		)
	}

	@Test fun httpFailureCarriesStatusAndErrorExcerpt() = runBlocking {
		server.enqueue(MockResponse().setBody(fixture("token_refresh.json")))
		server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"code\":2285,\n\"message\":\"server\"}}"))

		val error = runCatching { api.listDevices() }.exceptionOrNull()

		assertTrue("got $error", error is PsnRemoteHttpException)
		error as PsnRemoteHttpException
		assertEquals(500, error.httpCode)
		assertEquals("PlayStation Network isn't responding right now", error.message)
		assertFalse("HTTP status leaked into the user-facing message", error.message!!.contains("500"))
		assertEquals("{\"error\":{\"code\":2285, \"message\":\"server\"}}", error.detail)
	}

	private fun fixture(name: String): String = javaClass.getResource("/psn/$name")!!.readText()

	private class MemoryTokenStore(var value: String?) : PsnRefreshTokenStore
	{
		override fun read(): String? = value
		override fun write(value: String) { this.value = value }
	}
}
