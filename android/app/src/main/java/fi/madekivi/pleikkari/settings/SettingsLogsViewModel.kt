// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import fi.madekivi.pleikkari.common.LogFile
import fi.madekivi.pleikkari.common.LogManager

class SettingsLogsViewModel(val logManager: LogManager): ViewModel()
{
	private val _sessionLogs = MutableLiveData<List<LogFile>>(logManager.files)
	val sessionLogs: LiveData<List<LogFile>> get() = _sessionLogs

	private fun updateLogs()
	{
		_sessionLogs.value = logManager.files
	}

	fun deleteLog(file: LogFile)
	{
		file.file.delete()
		updateLogs()
	}
}
