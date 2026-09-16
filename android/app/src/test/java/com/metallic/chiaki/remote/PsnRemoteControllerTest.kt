// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package com.metallic.chiaki.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramSocket
import java.util.Collections
import kotlin.io.encoding.Base64

class PsnRemoteControllerTest
{
	private lateinit var server: MockWebServer
	private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
	private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
	private val duid = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

	@Before fun setUp()
	{
		server = MockWebServer()
		server.dispatcher = fixtureDispatcher()
		server.start()
	}

	@After fun tearDown() = server.shutdown()

	@Test fun fixtureExchangeReachesDataPunchedAndStreaming() = runBlocking {
		val api = PsnRemoteApi(
			OkHttpClient(),
			PsnRemoteEndpoints(server.url("/token").toString(), server.url("/api/").toString(), server.url("/push-address").toString()),
			object : PsnRefreshTokenStore {
				private var value = "refresh-token-placeholder"
				override fun read() = value
				override fun write(value: String) { this.value = value }
			},
			json
		)
		val native = RecordingNativeBridge()
		val controller = PsnRemoteController(
			api,
			OkHttpPsnPushTransport(OkHttpClient(), json),
			FixtureHolePuncher(),
			native,
			randomBytes = PsnRandomBytes { size -> ByteArray(size) { (it + 1).toByte() } },
			uuid = { "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee" },
			json = json
		)
		native.state = { controller.state.value }

		controller.connect(PsnDevice(duid, "Fixture PS5"))

		assertTrue(native.startedFromNativeStarting)
		assertTrue(native.receivedFromDataPunched)
		assertTrue(controller.state.value is PsnRemoteState.Streaming)
		assertEquals(1, native.starts)
		assertEquals(1, native.dataSockets)
		assertEquals(8, requests.count { it.path?.endsWith("/sessionMessage") == true })
		assertTrue(requests.any { it.path?.endsWith("/commands") == true })

		controller.disconnect()
		assertTrue(requests.any { it.method == "DELETE" && it.path?.endsWith("/members/me") == true })
	}

	@Test fun wakeSendsConsoleCommandWithoutPunchingOrStartingNative() = runBlocking {
		val api = PsnRemoteApi(
			OkHttpClient(),
			PsnRemoteEndpoints(server.url("/token").toString(), server.url("/api/").toString(), server.url("/push-address").toString()),
			object : PsnRefreshTokenStore {
				override fun read() = "refresh-token-placeholder"
				override fun write(value: String) = Unit
			},
			json
		)
		val controller = PsnRemoteController(
			api,
			OkHttpPsnPushTransport(OkHttpClient(), json),
			object : PsnHolePuncher {
				override suspend fun prepare(peer: PsnConnectionRequest, accountId: String): PsnPunchPreparation =
					error("Wake must not begin candidate exchange")
			},
			object : PsnRemoteNativeBridge {
				override suspend fun start(control: PsnPunchedSocket, registration: PsnRegistrationMaterial) =
					error("Wake must not start native")
				override suspend fun setDataSocket(data: PsnPunchedSocket) = Unit
			},
			randomBytes = PsnRandomBytes { size -> ByteArray(size) },
			uuid = { "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee" },
			json = json
		)

		controller.wake(PsnDevice(duid, "Fixture PS5"))

		assertTrue(controller.state.value is PsnRemoteState.Woken)
		assertTrue(requests.any { it.path?.endsWith("/commands") == true })
		assertTrue(requests.none { it.path?.endsWith("/sessionMessage") == true })
		assertTrue(requests.any { it.method == "DELETE" })
	}

	/**
	 * PLE-312: the S25 threw NetworkOnMainThreadException on Play, from a DNS lookup in the hole
	 * puncher's constructor, after PLE-261 had moved only OkHttp body reads off the main thread. Every
	 * public entry point of the controller is now run from a single caller thread with collaborators
	 * that record where they are called, and the entry-point list is checked by reflection.
	 */
	@Test fun nothingRunsOnTheCallerThread()
	{
		val threads = recordedThreads()
		val api = PsnRemoteApi(
			OkHttpClient.Builder().socketFactory(ReadRecordingSocketFactory(threads)).build(),
			PsnRemoteEndpoints(server.url("/token").toString(), server.url("/api/").toString(), server.url("/push-address").toString()),
			object : PsnRefreshTokenStore {
				override fun read() = "refresh-token-placeholder"
				override fun write(value: String) = Unit
			},
			json
		)
		val puncher = object : PsnHolePuncher {
			private val inner = FixtureHolePuncher()
			override suspend fun prepare(peer: PsnConnectionRequest, accountId: String): PsnPunchPreparation
			{
				recordThread(threads)
				val preparation = inner.prepare(peer, accountId)
				return object : PsnPunchPreparation by preparation {
					override suspend fun punch(): PsnPunchedSocket { recordThread(threads); return preparation.punch() }
				}
			}
		}
		val native = object : PsnRemoteNativeBridge {
			override suspend fun start(control: PsnPunchedSocket, registration: PsnRegistrationMaterial): PsnNativeStartResult
			{
				recordThread(threads)
				return PsnNativeStartResult.DataSocketNeeded
			}
			override suspend fun setDataSocket(data: PsnPunchedSocket) = recordThread(threads)
			override fun stop() = recordThread(threads)
		}
		val transport = object : PsnPushTransport {
			private val inner = OkHttpPsnPushTransport(OkHttpClient.Builder().socketFactory(ReadRecordingSocketFactory(threads)).build(), json)
			override suspend fun open(url: String, accessToken: String): PsnPushConnection
			{
				recordThread(threads)
				return inner.open(url, accessToken)
			}
		}
		val controller = PsnRemoteController(
			api, transport, puncher, native,
			randomBytes = PsnRandomBytes { size -> ByteArray(size) { (it + 1).toByte() } },
			uuid = { "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee" },
			json = json
		)
		val device = PsnDevice(duid, "Fixture PS5")
		val covered = mutableSetOf<String>()
		val executor = callerExecutor()
		try
		{
			runBlocking(executor.asCoroutineDispatcher()) {
				controller.connect(device).also { covered += "connect" }
				assertTrue(controller.state.value is PsnRemoteState.Streaming)
				controller.disconnect().also { covered += "disconnect" }
				controller.wake(device).also { covered += "wake" }
				assertTrue(controller.state.value is PsnRemoteState.Woken)
				assertEquals(1, controller.listDevices().size).also { covered += "listDevices" }
			}
		}
		finally
		{
			executor.shutdownNow()
		}
		assertEquals("every public suspend function of PsnRemoteController must be exercised here",
			publicSuspendFunctions(PsnRemoteController::class.java), covered)
		assertTrue("collaborators were called on: $threads", threads.size >= 2)
		assertEquals("PSN work on the caller thread", emptyList<String>(), threads.onCallerThread())
	}

	private fun fixtureDispatcher(): Dispatcher = object : Dispatcher()
	{
		override fun dispatch(request: RecordedRequest): MockResponse
		{
			requests += request
			return when
			{
				request.path == "/token" -> MockResponse().setBody(fixture("token_refresh.json"))
				request.path == "/push-address" -> MockResponse().setBody("""{"fqdn":"${server.url("/").toString().replaceFirst("http", "ws").trimEnd('/')}"}""")
				request.path == "/np/pushNotification" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener()
				{
					override fun onOpen(webSocket: WebSocket, response: okhttp3.Response)
					{
						fixtureNotifications().forEach(webSocket::send)
						webSocket.close(1000, "fixture complete")
					}
				})
				request.path == "/api/sessionManager/v1/remotePlaySessions" -> MockResponse().setBody(fixture("session_create.json"))
				request.path?.startsWith("/api/cloudAssistedNavigation/v2/users/me/clients") == true ->
					MockResponse().setBody(fixture("devices.json")).throttleBody(32, 30, java.util.concurrent.TimeUnit.MILLISECONDS)
				request.path?.endsWith("/commands") == true -> MockResponse().setResponseCode(204)
				request.path?.endsWith("/sessionMessage") == true -> MockResponse().setResponseCode(204)
				request.method == "DELETE" -> MockResponse().setResponseCode(204)
				else -> MockResponse().setResponseCode(404)
			}
		}
	}

	private fun fixtureNotifications(): List<String>
	{
		val peer = PsnConnectionRequest(
			sid = 4567,
			peerSid = 0,
			skey = Base64.Default.encode(ByteArray(16)),
			candidate = listOf(PsnCandidate("STUN", "192.0.2.10", port = 41000)),
			localPeerAddr = PsnPeerAddress("12345678901234567", "PROSPERO"),
			localHashedId = Base64.Default.encode(ByteArray(20) { 9 })
		)
		val custom = Base64.Default.encode(Base64.Default.encode(ByteArray(16) { 7 }).encodeToByteArray())
		return listOf(
			notification("psn:sessionManager:sys:remotePlaySession:created"),
			memberNotification("client-placeholder"),
			memberNotification(duid),
			buildJsonObject {
				put("dataType", "psn:sessionManager:sys:rps:customData1:updated")
				put("body", buildJsonObject { put("data", buildJsonObject { put("customData1", custom) }) })
			}.toString(),
			signalNotification(PsnSignalMessage("OFFER", 71, connRequest = peer)),
			signalNotification(PsnSignalMessage("RESULT", 1)),
			signalNotification(PsnSignalMessage("ACCEPT", 72, connRequest = peer)),
			signalNotification(PsnSignalMessage("OFFER", 81, connRequest = peer.copy(sid = 5678))),
			signalNotification(PsnSignalMessage("RESULT", 3)),
			signalNotification(PsnSignalMessage("ACCEPT", 82, connRequest = peer.copy(sid = 5678)))
		)
	}

	private fun notification(type: String) = buildJsonObject { put("dataType", type) }.toString()

	private fun memberNotification(memberDuid: String) = buildJsonObject {
		put("dataType", "psn:sessionManager:sys:rps:members:created")
		put("body", buildJsonObject { put("data", buildJsonObject {
			put("members", buildJsonArray { add(buildJsonObject { put("deviceUniqueId", memberDuid) }) })
		}) })
	}.toString()

	private fun signalNotification(message: PsnSignalMessage) = buildJsonObject {
		put("dataType", "psn:sessionManager:sys:rps:sessionMessage:created")
		put("body", buildJsonObject { put("data", buildJsonObject { put("sessionMessage", buildJsonObject {
			put("payload", "ver=1.0, type=text, body=${json.encodeToString(message)}")
		}) }) })
	}.toString()

	private fun fixture(name: String): String = javaClass.getResource("/psn/$name")!!.readText()

	private class FixtureHolePuncher : PsnHolePuncher
	{
		private var round = 0
		override suspend fun prepare(peer: PsnConnectionRequest, accountId: String): PsnPunchPreparation
		{
			val selected = peer.candidate.first()
			val local = PsnConnectionRequest(
				sid = 1234 + round++, peerSid = peer.sid,
				skey = Base64.Default.encode(ByteArray(16)), candidate = listOf(PsnCandidate("LOCAL", "192.0.2.20", port = 42000)),
				localPeerAddr = PsnPeerAddress(accountId), localHashedId = Base64.Default.encode(ByteArray(20) { 3 })
			)
			return object : PsnPunchPreparation
			{
				override val offer = local
				override suspend fun punch() = withContext(Dispatchers.IO) { PsnPunchedSocket(DatagramSocket(), selected) }
				override fun close() = Unit
			}
		}
	}

	private class RecordingNativeBridge : PsnRemoteNativeBridge
	{
		lateinit var state: () -> PsnRemoteState
		var starts = 0
		var dataSockets = 0
		var startedFromNativeStarting = false
		var receivedFromDataPunched = false
		override suspend fun start(control: PsnPunchedSocket, registration: PsnRegistrationMaterial): PsnNativeStartResult
		{
			starts++
			startedFromNativeStarting = state() is PsnRemoteState.NativeStarting
			return PsnNativeStartResult.DataSocketNeeded
		}
		override suspend fun setDataSocket(data: PsnPunchedSocket)
		{
			dataSockets++
			receivedFromDataPunched = state() is PsnRemoteState.DataPunched
			delay(20)
		}
	}
}
