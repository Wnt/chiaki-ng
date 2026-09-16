// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.metallic.chiaki.remote

import android.content.Context
import com.metallic.chiaki.regist.PsnCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Shared production wiring for the feature-gated PSN console UI and stream flow. */
class AndroidPsnRemoteClient(context: Context)
{
	private val http = OkHttpClient()
	private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
	private val api = PsnRemoteApi(
		http,
		PsnServiceEndpoints.current.remote,
		PsnCredentialStore(context.applicationContext),
		json
	)

	/** Whole calls on IO: the caller is a ViewModel on the main thread (PLE-261, PLE-312). */
	suspend fun listDevices(): List<PsnDevice> = withContext(Dispatchers.IO) { api.listDevices() }

	suspend fun listDeviceListing(): PsnDeviceListing = withContext(Dispatchers.IO) { api.listDeviceListing() }

	/**
	 * Called on the main thread, so nothing constructed here may touch the network; the puncher's STUN
	 * addresses stay unresolved until it runs (PLE-312). The controller moves every call to IO itself.
	 */
	fun controller(nativeBridge: PsnRemoteNativeBridge): PsnRemoteController = PsnRemoteController(
		api,
		OkHttpPsnPushTransport(http, json),
		DatagramPsnHolePuncher(),
		nativeBridge,
		json = json
	)
}
