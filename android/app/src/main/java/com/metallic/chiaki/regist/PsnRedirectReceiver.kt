// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Target of the sign-in tab's Finish sign-in button; the browser fills in the page address. */
class PsnRedirectReceiver : BroadcastReceiver()
{
	override fun onReceive(context: Context, intent: Intent)
	{
		// Pressed before Sony redirected: leave the tab alone.
		val address = intent.dataString?.takeIf { parsePsnRedirect(it) != PsnRedirect.NotRedirect } ?: return
		PsnPendingRedirect.offer(address)
		context.startActivity(Intent(context, PsnRedirectActivity::class.java)
			.setData(intent.data)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	}
}
