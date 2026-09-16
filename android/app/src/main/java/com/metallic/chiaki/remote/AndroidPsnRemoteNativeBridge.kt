// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import android.os.ParcelFileDescriptor
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.NativeRemoteConnection
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.RemoteDataSocketNeededEvent
import com.metallic.chiaki.lib.Session
import kotlinx.coroutines.CompletableDeferred

/**
 * The Android/native data-plane boundary. DatagramSocket remains strongly referenced until each
 * JNI call returns; native owns a detached duplicate only after a successful call.
 */
class AndroidPsnRemoteNativeBridge(
	private val connectInfo: ConnectInfo,
	private val logFile: String?,
	private val logVerbose: Boolean,
	private val realVideoTimestamps: Boolean,
	private val decoderInputThread: Boolean
) : PsnRemoteNativeBridge
{
	var session: Session? = null
		private set

	override suspend fun start(
		control: PsnPunchedSocket,
		registration: PsnRegistrationMaterial
	): PsnNativeStartResult
	{
		val controlFd = ParcelFileDescriptor.fromDatagramSocket(control.socket).detachFd()
		val nativeSession = try
		{
			Session(
				connectInfo = connectInfo,
				logFile = logFile,
				logVerbose = logVerbose,
				realVideoTimestamps = realVideoTimestamps,
				decoderInputThread = decoderInputThread,
				remoteConnection = NativeRemoteConnection(
					controlFd = controlFd,
					psnAccountId = accountIdBytes(registration.accountId),
					selectedAddress = control.candidate.addr,
					controlPort = control.candidate.port,
					data1 = registration.data1,
					data2 = registration.data2,
					customData1 = registration.customData1,
					localAddress = control.socket.localAddress.hostAddress ?: "0.0.0.0"
				)
			)
		}
		catch(error: Throwable)
		{
			closeDetachedFd(controlFd)
			throw error
		}
		// The duplicate now belongs to native. The Java socket is no longer needed, but remained
		// alive through the complete JNI creation call.
		control.socket.close()
		session = nativeSession

		val dataNeeded = CompletableDeferred<Unit>()
		nativeSession.eventCallback = { event ->
			when(event)
			{
				RemoteDataSocketNeededEvent -> dataNeeded.complete(Unit)
				is QuitEvent -> if(!dataNeeded.isCompleted)
					dataNeeded.completeExceptionally(PsnRemoteProtocolException(
						"Native remote session quit before requesting data socket: ${event.reason}"
					))
				else -> Unit
			}
		}
		val startResult = nativeSession.start()
		if(!startResult.isSuccess)
		{
			nativeSession.dispose(join = false)
			session = null
			throw PsnRemoteProtocolException("Unable to start native remote session: $startResult")
		}
		dataNeeded.await()
		return PsnNativeStartResult.DataSocketNeeded
	}

	override suspend fun setDataSocket(data: PsnPunchedSocket)
	{
		val nativeSession = session ?: throw PsnRemoteProtocolException("Native remote session is not running")
		val fd = ParcelFileDescriptor.fromDatagramSocket(data.socket).detachFd()
		val result = nativeSession.setRemoteDataSocket(fd)
		if(!result.isSuccess)
		{
			closeDetachedFd(fd)
			throw PsnRemoteProtocolException("Native session rejected remote data socket: $result")
		}
		data.socket.close()
	}

	override fun stop()
	{
		session?.stop()
		session?.dispose()
		session = null
	}

	private fun closeDetachedFd(fd: Int)
	{
		runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
	}

	private fun accountIdBytes(accountId: String): ByteArray
	{
		val value = accountId.toULongOrNull()
			?: throw PsnRemoteProtocolException("PSN account ID is not numeric")
		return ByteArray(8) { index -> (value shr (index * 8)).toByte() }
	}
}
