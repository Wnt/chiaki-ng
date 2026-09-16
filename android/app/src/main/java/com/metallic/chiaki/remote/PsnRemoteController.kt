// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.metallic.chiaki.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64

sealed interface PsnNativeStartResult
{
	data object Registered : PsnNativeStartResult
	data object DataSocketNeeded : PsnNativeStartResult
}

interface PsnRemoteNativeBridge
{
	/** The implementation duplicates/detaches the socket FD and only takes ownership after native success. */
	suspend fun start(control: PsnPunchedSocket, registration: PsnRegistrationMaterial): PsnNativeStartResult
	suspend fun setDataSocket(data: PsnPunchedSocket)
	fun stop() = Unit
}

fun interface PsnRandomBytes
{
	fun next(size: Int): ByteArray
}

class PsnRemoteController(
	private val api: PsnRemoteApi,
	private val pushTransport: PsnPushTransport,
	private val holePuncher: PsnHolePuncher,
	private val nativeBridge: PsnRemoteNativeBridge,
	private val randomBytes: PsnRandomBytes = PsnRandomBytes { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
	private val uuid: () -> String = { UUID.randomUUID().toString() },
	private val json: Json = api.json
) : Closeable
{
	private val _state = MutableStateFlow<PsnRemoteState>(PsnRemoteState.Idle)
	val state: StateFlow<PsnRemoteState> = _state.asStateFlow()

	private var push: PsnPushConnection? = null
	private var session: PsnSession? = null
	private var notificationQueue: NotificationQueue? = null
	private val openSockets = mutableListOf<PsnPunchedSocket>()
	private var requestId = 1

	suspend fun listDevices(): List<PsnDevice>
	{
		_state.value = PsnRemoteState.ListingDevices
		return try
		{
			val devices = api.listDevices()
			_state.value = PsnRemoteState.Devices(devices)
			devices
		}
		catch(error: Throwable)
		{
			_state.value = PsnRemoteState.Failed(error.message ?: "Unable to list PSN consoles", error)
			throw error
		}
	}

	suspend fun connect(device: PsnDevice)
	{
		check(session == null) { "A PSN remote session is already active" }
		try
		{
			_state.value = PsnRemoteState.ResolvingPushServer
			val pushUrl = api.resolvePushWebSocket()
			_state.value = PsnRemoteState.OpeningWebSocket
			val connection = withTimeout(30_000) { pushTransport.open(pushUrl, api.accessToken()) }
			push = connection
			notificationQueue = NotificationQueue(connection.notifications)

			_state.value = PsnRemoteState.CreatingSession
			val created = api.createSession(uuid())
			session = created
			awaitClientJoin()
			_state.value = PsnRemoteState.ClientJoined

			val data1 = randomBytes.next(16)
			val data2 = randomBytes.next(16)
			_state.value = PsnRemoteState.StartingConsole
			api.startConsole(created, device, Base64.Default.encode(data1), Base64.Default.encode(data2))
			val customData1 = awaitConsoleJoin(device)
			_state.value = PsnRemoteState.ConsoleJoined
			val registration = PsnRegistrationMaterial(created.accountId, data1, data2, customData1)

			val control = signalAndPunch(created, device, isData = false)
			openSockets += control
			_state.value = PsnRemoteState.ControlPunched(control.candidate)
			_state.value = PsnRemoteState.NativeStarting
			when(nativeBridge.start(control, registration))
			{
				PsnNativeStartResult.Registered -> _state.value = PsnRemoteState.Registered
				PsnNativeStartResult.DataSocketNeeded ->
				{
					_state.value = PsnRemoteState.AwaitingDataSocket
					val data = signalAndPunch(created, device, isData = true)
					openSockets += data
					_state.value = PsnRemoteState.DataPunched(data.candidate)
					nativeBridge.setDataSocket(data)
					_state.value = PsnRemoteState.Streaming
				}
			}
		}
		catch(cancelled: CancellationException)
		{
			_state.value = PsnRemoteState.Cancelling
			cleanup()
			throw cancelled
		}
		catch(error: Throwable)
		{
			_state.value = PsnRemoteState.Failed(error.message ?: "PSN remote connection failed", error)
			cleanup()
			throw error
		}
	}

	suspend fun disconnect()
	{
		_state.value = PsnRemoteState.Cancelling
		cleanup()
	}

	private suspend fun awaitClientJoin() = withTimeout(30_000) {
		var created = false
		var member = false
		while(!created || !member)
		{
			val notification = queue().take { value ->
				value.dataType() == SESSION_CREATED || value.dataType() == MEMBER_CREATED
			}
			when(notification.dataType())
			{
				SESSION_CREATED -> created = true
				MEMBER_CREATED -> member = true
			}
		}
	}

	private suspend fun awaitConsoleJoin(device: PsnDevice): ByteArray = withTimeout(30_000) {
		var joined = false
		var custom: ByteArray? = null
		while(!joined || custom == null)
		{
			val notification = queue().take { value ->
				value.dataType() == MEMBER_CREATED || value.dataType() == CUSTOM_DATA
			}
			when(notification.dataType())
			{
				MEMBER_CREATED ->
				{
					val duid = notification.path("body", "data", "members")?.jsonArray?.firstOrNull()?.jsonObject
						?.get("deviceUniqueId")?.jsonPrimitive?.contentOrNull
					if(duid.equals(device.duid, ignoreCase = true)) joined = true
				}
				CUSTOM_DATA -> custom = decodeCustomData(notification.path("body", "data", "customData1")?.jsonPrimitive?.contentOrNull)
			}
		}
		custom
	}

	private suspend fun signalAndPunch(session: PsnSession, device: PsnDevice, isData: Boolean): PsnPunchedSocket
	{
		_state.value = if(isData) PsnRemoteState.DataSignaling else PsnRemoteState.ControlSignaling
		val consoleOffer = awaitSignal("OFFER")
		val peer = consoleOffer.connRequest ?: throw PsnRemoteProtocolException("Console OFFER did not contain connRequest")
		api.sendSignal(session, device, PsnSignalMessage("RESULT", consoleOffer.reqId))

		val preparation = holePuncher.prepare(peer, session.accountId)
		try
		{
			val offerRequestId = requestId++
			api.sendSignal(session, device, PsnSignalMessage("OFFER", offerRequestId, connRequest = preparation.offer))
			awaitSignal("RESULT", offerRequestId, session, device)
			_state.value = if(isData) PsnRemoteState.DataProbing else PsnRemoteState.ControlProbing
			val punched = preparation.punch()
			val acceptRequestId = requestId++
			val acceptedRequest = preparation.offer.copy(candidate = listOf(punched.candidate))
			api.sendSignal(session, device, PsnSignalMessage("ACCEPT", acceptRequestId, connRequest = acceptedRequest))
			val consoleAccept = awaitSignal("ACCEPT", session = session, device = device)
			api.sendSignal(session, device, PsnSignalMessage("RESULT", consoleAccept.reqId))
			return punched
		}
		finally { preparation.close() }
	}

	private suspend fun awaitSignal(
		action: String,
		reqId: Int? = null,
		session: PsnSession? = null,
		device: PsnDevice? = null
	): PsnSignalMessage = withTimeout(30_000) {
		while(true)
		{
			val notification = queue().take { it.dataType() == SESSION_MESSAGE }
			val payload = notification.path("body", "data", "sessionMessage", "payload")?.jsonPrimitive?.contentOrNull
				?: throw PsnRemoteProtocolException("PSN signaling notification did not contain payload")
			val body = payload.substringAfter("body=", missingDelimiterValue = "")
				.replace("\"localPeerAddr\":,", "\"localPeerAddr\":{}")
			if(body.isEmpty()) throw PsnRemoteProtocolException("PSN signaling payload did not contain a body")
			val message = runCatching { json.decodeFromString<PsnSignalMessage>(body) }
				.getOrElse { throw PsnRemoteProtocolException("Invalid PSN signaling message", it) }
			if(message.action == "TERMINATE") throw PsnRemoteProtocolException("Console terminated PSN candidate exchange")
			if(message.action == "OFFER" && action != "OFFER" && session != null && device != null)
			{
				api.sendSignal(session, device, PsnSignalMessage("RESULT", message.reqId))
				continue
			}
			if(message.action == action && (reqId == null || message.reqId == reqId)) return@withTimeout message
		}
		@Suppress("UNREACHABLE_CODE")
		error("unreachable")
	}

	private fun decodeCustomData(value: String?): ByteArray
	{
		if(value == null) throw PsnRemoteProtocolException("customData1 notification did not contain data")
		val first = runCatching { Base64.Default.decode(value) }
			.getOrElse { throw PsnRemoteProtocolException("Invalid outer customData1 encoding", it) }
		val second = runCatching { Base64.Default.decode(first.decodeToString()) }
			.getOrElse { throw PsnRemoteProtocolException("Invalid inner customData1 encoding", it) }
		if(second.size !in 16..20) throw PsnRemoteProtocolException("customData1 decoded to ${second.size} bytes")
		return second.copyOf(16)
	}

	private suspend fun cleanup() = withContext(NonCancellable) {
		_state.value = PsnRemoteState.DeletingSession
		nativeBridge.stop()
		val current = session
		if(current != null) runCatching { withTimeout(3_000) { api.deleteSession(current.sessionId) } }
		push?.close()
		openSockets.forEach { it.socket.close() }
		openSockets.clear()
		push = null
		session = null
		notificationQueue = null
		requestId = 1
		_state.value = PsnRemoteState.Idle
	}

	private fun queue(): NotificationQueue = notificationQueue ?: error("PSN push channel is not open")

	override fun close()
	{
		nativeBridge.stop()
		push?.close()
		openSockets.forEach { it.socket.close() }
		openSockets.clear()
		push = null
		session = null
		notificationQueue = null
		_state.value = PsnRemoteState.Idle
	}

	private class NotificationQueue(private val channel: ReceiveChannel<JsonObject>)
	{
		private val pending = ArrayDeque<JsonObject>()

		suspend fun take(predicate: (JsonObject) -> Boolean): JsonObject
		{
			val pendingIterator = pending.iterator()
			while(pendingIterator.hasNext())
			{
				val value = pendingIterator.next()
				if(predicate(value))
				{
					pendingIterator.remove()
					return value
				}
			}
			while(true)
			{
				val value = channel.receive()
				if(predicate(value)) return value
				pending += value
			}
		}
	}

	companion object
	{
		private const val SESSION_CREATED = "psn:sessionManager:sys:remotePlaySession:created"
		private const val MEMBER_CREATED = "psn:sessionManager:sys:rps:members:created"
		private const val CUSTOM_DATA = "psn:sessionManager:sys:rps:customData1:updated"
		private const val SESSION_MESSAGE = "psn:sessionManager:sys:rps:sessionMessage:created"

		private fun JsonObject.dataType(): String? = this["dataType"]?.jsonPrimitive?.contentOrNull

		private fun JsonObject.path(vararg parts: String): kotlinx.serialization.json.JsonElement?
		{
			var current: kotlinx.serialization.json.JsonElement = this
			for(part in parts) current = (current as? JsonObject)?.get(part) ?: return null
			return current
		}
	}
}
