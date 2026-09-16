// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PsnPushConnection
{
	val notifications: ReceiveChannel<JsonObject>
	fun close()
}

interface PsnPushTransport
{
	suspend fun open(url: String, accessToken: String): PsnPushConnection
}

class OkHttpPsnPushTransport(
	client: OkHttpClient,
	private val json: Json = Json { ignoreUnknownKeys = true }
) : PsnPushTransport
{
	private val client = client.newBuilder().pingInterval(5, TimeUnit.SECONDS).build()

	override suspend fun open(url: String, accessToken: String): PsnPushConnection =
		suspendCancellableCoroutine { continuation ->
			val channel = Channel<JsonObject>(Channel.UNLIMITED)
			var opened = false
			lateinit var socket: WebSocket
			val listener = object : WebSocketListener()
			{
				override fun onOpen(webSocket: WebSocket, response: Response)
				{
					opened = true
					continuation.resume(object : PsnPushConnection
					{
						override val notifications: ReceiveChannel<JsonObject> = channel
						override fun close()
						{
							webSocket.close(1000, "PSN session closed")
							channel.close()
						}
					})
				}

				override fun onMessage(webSocket: WebSocket, text: String)
				{
					val message = runCatching { json.parseToJsonElement(text).jsonObject }
					message.onSuccess { channel.trySend(it) }
						.onFailure { channel.close(PsnRemoteProtocolException("Invalid PSN push notification", it)) }
				}

				override fun onClosing(webSocket: WebSocket, code: Int, reason: String)
				{
					webSocket.close(code, reason)
				}

				override fun onClosed(webSocket: WebSocket, code: Int, reason: String)
				{
					channel.close()
				}

				override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?)
				{
					channel.close(t)
					if(!opened && continuation.isActive) continuation.resumeWithException(t)
				}
			}
			val request = Request.Builder().url(url)
				.header("Authorization", "Bearer $accessToken")
				.header("Sec-WebSocket-Protocol", "np-pushpacket")
				.header("User-Agent", "WebSocket++/0.8.2")
				.header("X-PSN-APP-TYPE", "REMOTE_PLAY")
				.header("X-PSN-APP-VER", "RemotePlay/1.0")
				.header("X-PSN-KEEP-ALIVE-STATUS-TYPE", "3")
				.header("X-PSN-OS-VER", "Windows/10.0")
				.header("X-PSN-PROTOCOL-VERSION", "2.1")
				.header("X-PSN-RECONNECTION", "false")
				.build()
			socket = client.newWebSocket(request, listener)
			continuation.invokeOnCancellation {
				socket.cancel()
				channel.close(it)
			}
		}
}
