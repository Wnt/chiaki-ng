// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlin.io.encoding.ExperimentalEncodingApi

internal object PsnAuth
{
	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
	private const val AUTHORIZE_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize"
	private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
	const val REDIRECT_URL = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
	private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
	private const val DUID_PREFIX = "0000000700410080"

	data class Result(val accountId: String, val refreshToken: String)

	// These are Sony's Remote Play app credentials. Sony may change this private flow at any time.
	fun loginUrl(): String = Uri.parse(AUTHORIZE_URL).buildUpon()
		.appendQueryParameter("service_entity", "urn:service-entity:psn")
		.appendQueryParameter("response_type", "code")
		.appendQueryParameter("client_id", CLIENT_ID)
		.appendQueryParameter("redirect_uri", REDIRECT_URL)
		.appendQueryParameter("scope", SCOPE)
		.appendQueryParameter("request_locale", "en_US")
		.appendQueryParameter("ui", "pr")
		.appendQueryParameter("service_logo", "ps")
		.appendQueryParameter("layout_type", "popup")
		.appendQueryParameter("smcid", "remoteplay")
		.appendQueryParameter("prompt", "always")
		.appendQueryParameter("PlatformPrivacyWs1", "minimal")
		.appendQueryParameter("duid", generateDuid())
		.build()
		.toString()

	suspend fun exchangeCode(code: String): Result = withContext(Dispatchers.IO) {
		val tokenJson = request(
			url = TOKEN_URL,
			method = "POST",
			body = formBody(
				"grant_type" to "authorization_code",
				"code" to code,
				"scope" to SCOPE,
				"redirect_uri" to REDIRECT_URL
			)
		)
		val accessToken = tokenJson.requiredString("access_token")
		val refreshToken = tokenJson.requiredString("refresh_token")
		val userId = (tokenJson.opt("user_id") as? String)?.takeIf { it.isNotBlank() }
			?: request("$TOKEN_URL/${encode(accessToken)}", "GET").requiredString("user_id")
		Result(psnAccountIdFromUserId(userId), refreshToken)
	}

	private fun request(url: String, method: String, body: String? = null): JSONObject
	{
		val connection = URL(url).openConnection() as HttpURLConnection
		try
		{
			connection.requestMethod = method
			connection.connectTimeout = 15_000
			connection.readTimeout = 15_000
			connection.setRequestProperty("Authorization", basicAuthorization())
			connection.setRequestProperty("Accept", "application/json")
			if(body != null)
			{
				connection.doOutput = true
				connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
				connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body) }
			}

			val status = connection.responseCode
			if(status !in 200..299)
				throw IOException("Sony authentication failed (HTTP $status)")
			val response = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
			return JSONObject(response)
		}
		finally
		{
			connection.disconnect()
		}
	}

	private fun JSONObject.requiredString(name: String): String =
		(opt(name) as? String)?.takeIf { it.isNotBlank() }
			?: throw IOException("Sony authentication response did not contain $name")

	private fun basicAuthorization(): String
	{
		val credentials = "$CLIENT_ID:$CLIENT_SECRET".toByteArray(StandardCharsets.UTF_8)
		return "Basic ${Base64.encodeToString(credentials, Base64.NO_WRAP)}"
	}

	private fun formBody(vararg fields: Pair<String, String>) =
		fields.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

	private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

	private fun generateDuid(): String
	{
		val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
		return DUID_PREFIX + random.joinToString("") { "%02x".format(it.toInt() and 0xff) }
	}
}

@OptIn(ExperimentalUnsignedTypes::class, ExperimentalEncodingApi::class)
internal fun psnAccountIdFromUserId(userId: String): String
{
	val value = userId.toULong()
	val littleEndian = ByteArray(8) { index -> (value shr (index * 8)).toByte() }
	return kotlin.io.encoding.Base64.Default.encode(littleEndian)
}
