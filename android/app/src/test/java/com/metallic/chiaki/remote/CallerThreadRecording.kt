// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import java.io.FilterInputStream
import java.io.InputStream
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import javax.net.SocketFactory
import kotlin.coroutines.Continuation

/**
 * Shared by the tests that prove PSN work never runs on the caller's thread (PLE-261, PLE-312).
 * On Android that thread is the main thread, where a socket read, a DNS lookup or a UDP exchange
 * throws NetworkOnMainThreadException; the JVM has no such guard, so the tests record thread names.
 */
internal const val PSN_CALLER_THREAD = "psn-caller"

internal fun callerThreadFactory() = ThreadFactory { Thread(it, PSN_CALLER_THREAD) }

internal fun callerExecutor() = Executors.newSingleThreadExecutor(callerThreadFactory())

internal fun recordedThreads(): MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

/** Coroutine debug mode renames threads to `name @coroutine#N`, so only the prefix is compared. */
internal fun Set<String>.onCallerThread(): List<String> = filter { it.startsWith(PSN_CALLER_THREAD) }

internal fun recordThread(into: MutableSet<String>) { into += Thread.currentThread().name }

/**
 * The public suspend functions of [type], by name: the entry points a caller on the main thread can
 * reach. A test compares this with the names it exercised, so a new entry point fails the test
 * until it is covered too.
 */
internal fun publicSuspendFunctions(type: Class<*>): Set<String> = type.declaredMethods
	.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.parameterTypes.lastOrNull() == Continuation::class.java }
	.map { it.name }
	.toSet()

/** Records the thread of every socket read, which is where OkHttp reads a response body. */
internal class ReadRecordingSocketFactory(private val threads: MutableSet<String>) : SocketFactory()
{
	override fun createSocket(): Socket = object : Socket()
	{
		override fun getInputStream(): InputStream = object : FilterInputStream(super.getInputStream())
		{
			override fun read(): Int { recordThread(threads); return super.read() }
			override fun read(b: ByteArray, off: Int, len: Int): Int
			{
				recordThread(threads)
				return super.read(b, off, len)
			}
		}
	}
	override fun createSocket(host: String, port: Int): Socket = unsupported()
	override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
	override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
	override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
	private fun unsupported(): Nothing = throw UnsupportedOperationException()
}
