// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Lifecycle wrapper for the optional native AImageReader/EGL deband renderer.
 * The native implementation is absent from default builds, and this class is
 * instantiated only when BuildConfig.CHIAKI_ANDROID_EGL_RENDERER is true.
 */
class EglRenderer(
	private val surfaceView: SurfaceView,
	private val videoWidth: Int,
	private val videoHeight: Int,
	private val sharpness: Float,
	private val onDecoderSurfaceReady: (Surface) -> Unit,
	private val onDecoderSurfaceDestroyed: () -> Unit,
	private val onUnavailable: (EglRenderer) -> Unit
) : SurfaceHolder.Callback
{
	companion object
	{
		private const val TAG = "EglRenderer"
	}

	private var nativeHandle = 0L
	private var decoderSurface: Surface? = null
	private var started = false

	fun start()
	{
		if(started)
			return
		started = true
		surfaceView.holder.addCallback(this)
		if(surfaceView.holder.surface?.isValid == true)
			create(surfaceView.holder.surface)
	}

	@Synchronized
	private fun create(outputSurface: Surface)
	{
		if(nativeHandle != 0L)
			return
		val handle = nativeCreate(outputSurface, videoWidth, videoHeight, sharpness)
		if(handle == 0L)
		{
			Log.e(TAG, "Native EGL renderer initialization failed; reverting to GLSurfaceView")
			surfaceView.post { onUnavailable(this) }
			return
		}
		val surface = nativeGetDecoderSurface(handle)
		if(surface == null)
		{
			nativeDestroy(handle)
			Log.e(TAG, "Native EGL renderer did not provide a decoder surface")
			surfaceView.post { onUnavailable(this) }
			return
		}
		nativeHandle = handle
		decoderSurface = surface
		onDecoderSurfaceReady(surface)
	}

	@Synchronized
	private fun destroy()
	{
		if(nativeHandle == 0L)
			return
		onDecoderSurfaceDestroyed()
		decoderSurface?.release()
		decoderSurface = null
		nativeDestroy(nativeHandle)
		nativeHandle = 0L
	}

	fun release()
	{
		if(started)
		{
			surfaceView.holder.removeCallback(this)
			started = false
		}
		destroy()
	}

	override fun surfaceCreated(holder: SurfaceHolder)
	{
		create(holder.surface)
	}

	override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

	override fun surfaceDestroyed(holder: SurfaceHolder)
	{
		destroy()
	}

	private external fun nativeCreate(
		outputSurface: Surface,
		width: Int,
		height: Int,
		sharpness: Float
	): Long
	private external fun nativeGetDecoderSurface(handle: Long): Surface?
	private external fun nativeDestroy(handle: Long)
}
