// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.lib

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable

internal class PerformanceHints private constructor(private val implementation: Implementation?) : Closeable
{
	companion object
	{
		private const val TAG = "PerformanceHints"

		fun create(context: Context?, enabled: Boolean, framesPerSecond: Int): PerformanceHints
		{
			if(!enabled)
				return PerformanceHints(null)
			if(context == null)
			{
				Log.i(TAG, "ADPF performance hints refused: application context is unavailable")
				return PerformanceHints(null)
			}
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			{
				Log.i(TAG, "ADPF performance hints refused: requires Android 12 (API 31)")
				return PerformanceHints(null)
			}
			val targetNanos = 1_000_000_000L / framesPerSecond.coerceAtLeast(1)
			return PerformanceHints(Api31Implementation.create(context, targetNanos))
		}
	}

	private interface Implementation : Closeable
	{
		val isActive: Boolean
		fun threadStarted(role: Int, tid: Int)
		fun reportActualWorkDuration(role: Int, durationNanos: Long)
		fun threadStopped(role: Int)
	}

	fun threadStarted(role: Int, tid: Int) = implementation?.threadStarted(role, tid) ?: Unit

	fun reportActualWorkDuration(role: Int, durationNanos: Long) =
		implementation?.reportActualWorkDuration(role, durationNanos) ?: Unit

	fun threadStopped(role: Int) = implementation?.threadStopped(role) ?: Unit

	val isActive get() = implementation?.isActive ?: false

	override fun close()
	{
		implementation?.close()
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private class Api31Implementation private constructor(
		private val manager: PerformanceHintManager,
		private val targetNanos: Long
	) : Implementation
	{
		companion object
		{
			fun create(context: Context, targetNanos: Long): Api31Implementation?
			{
				return try
				{
					val manager = context.getSystemService(PerformanceHintManager::class.java)
					if(manager == null)
					{
						Log.i(TAG, "ADPF performance hints refused: PerformanceHintManager is unavailable")
						null
					}
					else
						Api31Implementation(manager, targetNanos)
				}
				catch(error: RuntimeException)
				{
					Log.i(TAG, "ADPF performance hints refused: unable to obtain PerformanceHintManager: ${error.message ?: error.javaClass.simpleName}")
					null
				}
			}
		}

		private val threadIds = mutableMapOf<Int, Int>()
		private var session: PerformanceHintManager.Session? = null
		private var refusalLogged = false

		@get:Synchronized
		override val isActive get() = session != null

		@Synchronized
		override fun threadStarted(role: Int, tid: Int)
		{
			threadIds[role] = tid
			closeSession()
			try
			{
				val created = manager.createHintSession(threadIds.values.toIntArray(), targetNanos)
				if(created == null)
				{
					logRefusalOnce("createHintSession returned null for roles=${threadIds.keys}")
					return
				}
				session = created
				Log.i(TAG, "ADPF hint session started for roles=${threadIds.keys} tids=${threadIds.values} target=$targetNanos ns")
			}
			catch(error: RuntimeException)
			{
				logRefusalOnce("createHintSession failed for roles=${threadIds.keys}: ${error.message ?: error.javaClass.simpleName}")
			}
		}

		@Synchronized
		override fun reportActualWorkDuration(role: Int, durationNanos: Long)
		{
			if(!threadIds.containsKey(role))
				return
			val activeSession = session ?: return
			try
			{
				activeSession.reportActualWorkDuration(durationNanos.coerceAtLeast(1L))
			}
			catch(error: RuntimeException)
			{
				Log.w(TAG, "Unable to report ADPF work duration for role=$role", error)
				closeSession()
			}
		}

		@Synchronized
		override fun threadStopped(role: Int)
		{
			threadIds.remove(role)
			closeSession()
		}

		@Synchronized
		override fun close()
		{
			threadIds.clear()
			closeSession()
		}

		private fun closeSession()
		{
			try
			{
				session?.close()
			}
			catch(error: RuntimeException)
			{
				Log.w(TAG, "Unable to close ADPF hint session", error)
			}
			finally
			{
				session = null
			}
		}

		private fun logRefusalOnce(reason: String)
		{
			if(!refusalLogged)
			{
				refusalLogged = true
				Log.i(TAG, "ADPF performance hints refused: $reason")
			}
		}
	}
}
