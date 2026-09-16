// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.net.Uri
import android.util.Base64
import com.metallic.chiaki.remote.PsnServiceEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlin.io.encoding.ExperimentalEncodingApi

internal object PsnAuth
{
	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
	// Sony's, unless this is a debug build made for the PSN mock (PLE-284).
	private val AUTHORIZE_URL get() = PsnServiceEndpoints.current.authorizeUrl
	private val TOKEN_URL get() = PsnServiceEndpoints.current.tokenUrl
	val REDIRECT_URL get() = PsnServiceEndpoints.current.redirectUrl
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

internal sealed interface PsnRedirect
{
	data object NotRedirect : PsnRedirect
	data object Invalid : PsnRedirect
	data class Code(val value: String) : PsnRedirect
}

internal fun parsePsnRedirect(url: String, endpoints: PsnServiceEndpoints = PsnServiceEndpoints.current): PsnRedirect
{
	val uri = try { URI(url.trim()) } catch(_: Exception) { return PsnRedirect.NotRedirect }
	if(!uri.scheme.equals("https", ignoreCase = true) ||
		!uri.host.equals(endpoints.redirectHost, ignoreCase = true) ||
		uri.path != endpoints.redirectPath)
		return PsnRedirect.NotRedirect

	val code = try {
		uri.rawQuery.orEmpty().split('&').asSequence()
			.map { field -> field.split('=', limit = 2) }
			.firstOrNull { parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) == "code" }
			?.getOrNull(1)
			?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
	} catch(_: IllegalArgumentException) {
		return PsnRedirect.Invalid
	}
	return if(code.isNullOrBlank()) PsnRedirect.Invalid else PsnRedirect.Code(code)
}

@OptIn(ExperimentalUnsignedTypes::class, ExperimentalEncodingApi::class)
internal fun psnAccountIdFromUserId(userId: String): String
{
	val value = userId.toULong()
	val littleEndian = ByteArray(8) { index -> (value shr (index * 8)).toByte() }
	return kotlin.io.encoding.Base64.Default.encode(littleEndian)
}
