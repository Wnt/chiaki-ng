// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.metallic.chiaki.remote.PsnRefreshTokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class PsnCredentialStore(context: Context) : PsnRefreshTokenStore
{
	companion object
	{
		private const val KEY_ALIAS = "chiaki_psn_refresh_token"
		private const val PREFERENCES_NAME = "psn_credentials"
		private const val CIPHERTEXT_KEY = "refresh_token_ciphertext"
		private const val IV_KEY = "refresh_token_iv"
	}

	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun putRefreshToken(refreshToken: String)
	{
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
		val ciphertext = cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
		preferences.edit()
			.putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
			.putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
			.apply()
	}

	fun getRefreshToken(): String?
	{
		val ciphertext = preferences.getString(CIPHERTEXT_KEY, null) ?: return null
		val iv = preferences.getString(IV_KEY, null) ?: return null
		return runCatching {
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(
				Cipher.DECRYPT_MODE,
				getOrCreateKey(),
				GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
			)
			String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
		}.getOrNull()
	}

	override fun read(): String? = getRefreshToken()

	override fun write(value: String) = putRefreshToken(value)

	private fun getOrCreateKey(): SecretKey
	{
		val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		generator.init(
			KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build()
		)
		return generator.generateKey()
	}
}
