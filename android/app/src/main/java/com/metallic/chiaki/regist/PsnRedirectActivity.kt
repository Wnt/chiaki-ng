// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Forwards Sony's unverified HTTPS redirect to the login instance below the browser. */
class PsnRedirectActivity : Activity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		startActivity(Intent(this, PsnLoginActivity::class.java).apply {
			data = intent.data
			flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
		})
		finish()
	}
}
