// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R

/** Default-off switch for A/B testing the PLE-245 interaction changes. */
object RegistrationFormFeature
{
	const val EXTRA_ENABLED = "registration_form_usability_enabled"

	fun isEnabled(activity: Activity): Boolean
	{
		val defaultValue = PreferenceManager.getDefaultSharedPreferences(activity)
			.getBoolean(activity.getString(R.string.preferences_registration_form_usability_enabled_key), false)
		return activity.intent.getBooleanExtra(EXTRA_ENABLED, defaultValue)
	}
}
