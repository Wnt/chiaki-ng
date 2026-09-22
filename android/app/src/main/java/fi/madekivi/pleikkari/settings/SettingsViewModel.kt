// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import fi.madekivi.pleikkari.common.AppDatabase
import fi.madekivi.pleikkari.common.Preferences

class SettingsViewModel(val database: AppDatabase, val preferences: Preferences): ViewModel()
{
	val registeredHostsCount by lazy {
		database.registeredHostDao().count().asLiveData()
	}

	val bitrateAuto by lazy {
		preferences.bitrateAutoFlow.asLiveData()
	}
}
