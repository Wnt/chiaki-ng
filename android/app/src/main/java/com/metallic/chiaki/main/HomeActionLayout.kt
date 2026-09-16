// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

data class HomeActionInsets(val end: Int, val bottom: Int)

/** Keeps console-card content out of the floating Add console action's bounds. */
object HomeActionLayout
{
	fun contentInsets(
		contentRight: Int,
		contentBottom: Int,
		actionLeft: Int,
		actionTop: Int,
		clearance: Int,
		landscape: Boolean
	): HomeActionInsets
	{
		val end = (contentRight - actionLeft + clearance).coerceAtLeast(0)
		val bottom = (contentBottom - actionTop + clearance).coerceAtLeast(0)
		return if(landscape) HomeActionInsets(end = end, bottom = 0)
		else HomeActionInsets(end = 0, bottom = bottom)
	}
}
