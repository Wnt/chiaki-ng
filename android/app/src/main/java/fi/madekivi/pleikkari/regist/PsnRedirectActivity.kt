// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.regist

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Forwards Sony's redirect to the login instance below the browser. It arrives either as an app
 * link or from the browser tab's Finish sign-in button, which sends whatever page is showing. A
 * press before Sony has redirected does nothing, so the tab stays open.
 */
class PsnRedirectActivity : Activity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val address = intent.dataString
		if(address != null && parsePsnRedirect(address) != PsnRedirect.NotRedirect)
		{
			startActivity(Intent(this, PsnLoginActivity::class.java).apply {
				data = intent.data
				flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			})
		}
		finish()
	}
}
