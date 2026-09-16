// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import android.os.Parcelable
import com.metallic.chiaki.lib.RegistHost
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class PsnDevice(
	val duid: String,
	val name: String,
	val platform: String = "PS5"
) : Parcelable

@Serializable
data class PsnCandidate(
	val type: String,
	val addr: String,
	@SerialName("mappedAddr") val mappedAddress: String = "0.0.0.0",
	val port: Int,
	@SerialName("mappedPort") val mappedPort: Int = 0
)

@Serializable
data class PsnPeerAddress(
	val accountId: String = "",
	val platform: String = "REMOTE_PLAY"
)

@Serializable
data class PsnConnectionRequest(
	val sid: Int,
	val peerSid: Int,
	val skey: String,
	val natType: Int = 2,
	val candidate: List<PsnCandidate>,
	val defaultRouteMacAddr: String = "",
	val localPeerAddr: PsnPeerAddress = PsnPeerAddress(),
	val localHashedId: String
)

@Serializable
data class PsnSignalMessage(
	val action: String,
	val reqId: Int,
	val error: Int = 0,
	val connRequest: PsnConnectionRequest? = null
)

data class PsnSession(
	val sessionId: String,
	val accountId: String
)

data class PsnRegistrationMaterial(
	val accountId: String,
	val data1: ByteArray,
	val data2: ByteArray,
	val customData1: ByteArray
)

sealed interface PsnRemoteState
{
	data object Idle : PsnRemoteState
	data object RefreshingToken : PsnRemoteState
	data object ListingDevices : PsnRemoteState
	data class Devices(val devices: List<PsnDevice>) : PsnRemoteState
	data object ResolvingPushServer : PsnRemoteState
	data object OpeningWebSocket : PsnRemoteState
	data object CreatingSession : PsnRemoteState
	data object ClientJoined : PsnRemoteState
	data object StartingConsole : PsnRemoteState
	data object ConsoleJoined : PsnRemoteState
	data object ControlSignaling : PsnRemoteState
	data object ControlProbing : PsnRemoteState
	data class ControlPunched(val candidate: PsnCandidate) : PsnRemoteState
	data object NativeStarting : PsnRemoteState
	data class Registered(val host: RegistHost) : PsnRemoteState
	data object Woken : PsnRemoteState
	data object AwaitingDataSocket : PsnRemoteState
	data object DataSignaling : PsnRemoteState
	data object DataProbing : PsnRemoteState
	data class DataPunched(val candidate: PsnCandidate) : PsnRemoteState
	data object Streaming : PsnRemoteState
	data object Cancelling : PsnRemoteState
	data object DeletingSession : PsnRemoteState
	data class Failed(val reason: String, val cause: Throwable? = null) : PsnRemoteState
}

class PsnRemoteProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PsnRemoteAuthenticationException(
	message: String,
	val httpCode: Int? = null,
	val detail: String? = null
) : Exception(message)
class PsnRemoteHttpException(message: String, val httpCode: Int, val detail: String?) : java.io.IOException(message)

data class PsnDeviceListing(
	/** Clients PSN returned for the account, before dropping ones without the remotePlay feature. */
	val clientCount: Int,
	val devices: List<PsnDevice>
)
class PsnUnsupportedNatException(message: String) : Exception(message)
