// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari

import android.app.Application
import com.google.android.material.color.DynamicColors

class ChiakiApplication : Application()
{
	override fun onCreate()
	{
		super.onCreate()
		DynamicColors.applyToActivitiesIfAvailable(this)
	}
}
