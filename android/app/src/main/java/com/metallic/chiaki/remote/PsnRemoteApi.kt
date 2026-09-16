// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.metallic.chiaki.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

interface PsnRefreshTokenStore
{
	fun read(): String?
	fun write(value: String)
}

data class PsnRemoteEndpoints(
	val tokenUrl: String,
	val webApiBase: String,
	val pushLookupUrl: String
)
{
	companion object
	{
		val Production = PsnRemoteEndpoints(
			tokenUrl = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token",
			webApiBase = "https://web.np.playstation.com/api/",
			pushLookupUrl = "https://mobile-pushcl.np.communication.playstation.net/np/serveraddr?version=2.1&fields=keepAliveStatus&keepAliveStatusType=3"
		)
	}
}

internal class PsnAccessTokenManager(
	private val client: OkHttpClient,
	private val tokenUrl: String,
	private val tokenStore: PsnRefreshTokenStore,
	private val json: Json,
	private val clockMillis: () -> Long
)
{
	companion object
	{
		const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		const val REDIRECT_URL = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
	}

	private val mutex = Mutex()
	private var accessToken: String? = null
	private var expiresAtMillis: Long = 0

	suspend fun get(forceRefresh: Boolean = false): String = mutex.withLock {
		val now = clockMillis()
		if(!forceRefresh && accessToken != null && expiresAtMillis - now >= 60_000)
			return@withLock accessToken!!

		val refreshToken = tokenStore.read()
			?: throw PsnRemoteAuthenticationException("Sign in to PSN before using remote connection")
		val request = Request.Builder()
			.url(tokenUrl)
			.header("Authorization", okhttp3.Credentials.basic(CLIENT_ID, CLIENT_SECRET))
			.header("Accept", "application/json")
			.post(FormBody.Builder()
				.add("grant_type", "refresh_token")
				.add("refresh_token", refreshToken)
				.add("scope", SCOPE)
				.add("redirect_uri", REDIRECT_URL)
				.build())
			.build()
		val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
		response.use {
			val body = it.body?.string().orEmpty()
			if(!it.isSuccessful)
				throw PsnRemoteAuthenticationException("PSN token refresh failed (HTTP ${it.code})")
			val token = runCatching { json.decodeFromString<TokenResponse>(body) }
				.getOrElse { error -> throw PsnRemoteProtocolException("Invalid PSN token response", error) }
			if(token.accessToken.isBlank())
				throw PsnRemoteProtocolException("PSN token response did not contain an access token")
			if(!token.refreshToken.isNullOrBlank() && token.refreshToken != refreshToken)
				tokenStore.write(token.refreshToken)
			accessToken = token.accessToken
			expiresAtMillis = clockMillis() + token.expiresIn.coerceAtLeast(1) * 1000L
			token.accessToken
		}
	}

	@Serializable
	private data class TokenResponse(
		@SerialName("access_token") val accessToken: String,
		@SerialName("refresh_token") val refreshToken: String? = null,
		@SerialName("expires_in") val expiresIn: Long
	)
}

class PsnRemoteApi(
	private val client: OkHttpClient,
	private val endpoints: PsnRemoteEndpoints,
	tokenStore: PsnRefreshTokenStore,
	internal val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
	clockMillis: () -> Long = System::currentTimeMillis
)
{
	private val tokens = PsnAccessTokenManager(client, endpoints.tokenUrl, tokenStore, json, clockMillis)
	private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

	suspend fun accessToken(forceRefresh: Boolean = false): String = tokens.get(forceRefresh)

	suspend fun listDevices(): List<PsnDevice>
	{
		val response = authorized(
			Request.Builder().url(apiUrl("cloudAssistedNavigation/v2/users/me/clients?platform=PS5&includeFields=device&limit=10&offset=0"))
				.header("Accept-Language", "jp"),
			5_000
		)
		val body = decode<DeviceListResponse>(response)
		return body.clients.mapNotNull { client ->
			val device = client.device ?: return@mapNotNull null
			if("remotePlay" !in device.enabledFeatures) return@mapNotNull null
			PsnDevice(client.duid, device.name)
		}
	}

	suspend fun resolvePushWebSocket(): String
	{
		val address = decode<PushAddress>(authorized(Request.Builder().url(endpoints.pushLookupUrl)))
		val fqdn = address.fqdn.trim()
		if(fqdn.isBlank()) throw PsnRemoteProtocolException("PSN push lookup returned an empty address")
		return if(fqdn.startsWith("ws://") || fqdn.startsWith("wss://"))
			fqdn.trimEnd('/') + "/np/pushNotification"
		else "wss://$fqdn/np/pushNotification"
	}

	suspend fun createSession(pushContextId: String): PsnSession
	{
		val member = buildJsonObject {
			put("accountId", "me")
			put("deviceUniqueId", "me")
			put("platform", "me")
			put("pushContexts", buildJsonArray { add(buildJsonObject { put("pushContextId", pushContextId) }) })
		}
		val body = buildJsonObject {
			put("remotePlaySessions", buildJsonArray { add(buildJsonObject { put("members", buildJsonArray { add(member) }) }) })
		}
		val response = authorized(jsonRequest("sessionManager/v1/remotePlaySessions", body))
		val root = parseObject(response)
		val session = root["remotePlaySessions"]?.jsonArray?.firstOrNull()?.jsonObject
			?: throw PsnRemoteProtocolException("PSN session response did not contain a session")
		val sessionId = session["sessionId"]?.jsonPrimitive?.contentOrNull
			?: throw PsnRemoteProtocolException("PSN session response did not contain sessionId")
		val account = session["members"]?.jsonArray?.firstOrNull()?.jsonObject?.get("accountId")?.jsonPrimitive?.contentOrNull
			?: throw PsnRemoteProtocolException("PSN session response did not contain accountId")
		return PsnSession(sessionId, account)
	}

	suspend fun startConsole(session: PsnSession, device: PsnDevice, data1: String, data2: String)
	{
		val initialParams = buildJsonObject {
			put("accountId", session.accountId.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(session.accountId))
			put("roomId", 0)
			put("sessionId", session.sessionId)
			put("clientType", "Windows")
			put("data1", data1)
			put("data2", data2)
		}.toString()
		val body = buildJsonObject {
			put("commandDetail", buildJsonObject {
				put("commandType", "remotePlay")
				put("duid", device.duid)
				put("messageDestination", "SQS")
				put("parameters", buildJsonObject { put("initialParams", initialParams) })
				put("platform", device.platform)
			})
		}
		authorized(jsonRequest("cloudAssistedNavigation/v2/users/me/commands", body, "RpNetHttpUtilImpl"))
	}

	suspend fun sendSignal(session: PsnSession, device: PsnDevice, message: PsnSignalMessage)
	{
		val messageJson = signalJson(message)
		val body = buildJsonObject {
			put("channel", "remote_play:1")
			put("payload", "ver=1.0, type=text, body=$messageJson")
			put("to", buildJsonArray { add(buildJsonObject {
				put("accountId", session.accountId)
				put("deviceUniqueId", device.duid)
				put("platform", device.platform)
			}) })
		}
		authorized(jsonRequest("sessionManager/v1/remotePlaySessions/${session.sessionId}/sessionMessage", body))
	}

	suspend fun deleteSession(sessionId: String)
	{
		authorized(Request.Builder()
			.url(apiUrl("sessionManager/v1/remotePlaySessions/$sessionId/members/me"))
			.delete())
	}

	private fun signalJson(message: PsnSignalMessage): JsonObject = buildJsonObject {
		put("action", message.action)
		put("reqId", message.reqId)
		put("error", message.error)
		put("connRequest", message.connRequest?.let {
			json.encodeToJsonElement(PsnConnectionRequest.serializer(), it)
		} ?: JsonObject(emptyMap()))
	}

	private fun jsonRequest(path: String, body: JsonObject, userAgent: String? = null): Request.Builder =
		Request.Builder().url(apiUrl(path)).apply {
			if(userAgent != null) header("User-Agent", userAgent)
		}.post(body.toString().toRequestBody(jsonMediaType))

	private suspend fun authorized(builder: Request.Builder, timeoutMillis: Long = 10_000): String
	{
		var token = tokens.get()
		repeat(2) { attempt ->
			val request = builder.header("Authorization", "Bearer $token").header("Accept", "application/json").build()
			val callClient = client.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
			val response = withContext(Dispatchers.IO) { callClient.newCall(request).execute() }
			response.use {
				val body = it.body?.string().orEmpty()
				if(it.isSuccessful) return body
				if((it.code == 401 || it.code == 403) && attempt == 0)
				{
					token = tokens.get(forceRefresh = true)
					return@repeat
				}
				if(it.code == 401 || it.code == 403)
					throw PsnRemoteAuthenticationException("PSN authorization was rejected after refresh")
				throw IOException("PSN request failed (HTTP ${it.code})")
			}
		}
		throw PsnRemoteAuthenticationException("PSN authorization was rejected")
	}

	private inline fun <reified T> decode(value: String): T =
		runCatching { json.decodeFromString<T>(value) }
			.getOrElse { throw PsnRemoteProtocolException("Invalid PSN response", it) }

	private fun parseObject(value: String): JsonObject = runCatching { json.parseToJsonElement(value).jsonObject }
		.getOrElse { throw PsnRemoteProtocolException("Invalid PSN response", it) }

	private fun apiUrl(path: String): String = endpoints.webApiBase.trimEnd('/') + "/" + path.trimStart('/')

	@Serializable private data class DeviceListResponse(val clients: List<DeviceClient> = emptyList())
	@Serializable private data class DeviceClient(val duid: String, val device: DeviceInfo? = null)
	@Serializable private data class DeviceInfo(val name: String, val enabledFeatures: List<String> = emptyList())
	@Serializable private data class PushAddress(val fqdn: String)
}
