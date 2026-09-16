// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.metallic.chiaki.remote

import android.content.Context
import com.metallic.chiaki.regist.PsnCredentialStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Shared production wiring for the feature-gated PSN console UI and stream flow. */
class AndroidPsnRemoteClient(context: Context)
{
	private val http = OkHttpClient()
	private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
	private val api = PsnRemoteApi(
		http,
		PsnRemoteEndpoints.Production,
		PsnCredentialStore(context.applicationContext),
		json
	)

	suspend fun listDevices(): List<PsnDevice> = api.listDevices()

	suspend fun listDeviceListing(): PsnDeviceListing = api.listDeviceListing()

	fun controller(nativeBridge: PsnRemoteNativeBridge): PsnRemoteController = PsnRemoteController(
		api,
		OkHttpPsnPushTransport(http, json),
		DatagramPsnHolePuncher(),
		nativeBridge,
		json = json
	)
}
