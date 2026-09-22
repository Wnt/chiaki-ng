// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.regist

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fi.madekivi.pleikkari.R
import fi.madekivi.pleikkari.common.MacAddress
import fi.madekivi.pleikkari.common.ext.applySystemBarInsets
import fi.madekivi.pleikkari.common.ext.enableAppEdgeToEdge
import fi.madekivi.pleikkari.common.ext.viewModelFactory
import fi.madekivi.pleikkari.common.getDatabase
import fi.madekivi.pleikkari.databinding.ActivityRegistExecuteBinding
import fi.madekivi.pleikkari.lib.RegistInfo

class RegistExecuteActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_REGIST_INFO = "regist_info"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"
		const val EXTRA_CONSOLE_NAME = "regist_console_name"
		const val EXTRA_GUIDED = "regist_guided"
		const val EXTRA_PREVIEW_STATE = "regist_preview_state"
		const val PREVIEW_RUNNING = "running"
		const val PREVIEW_SUCCESS = "success"

		const val RESULT_FAILED = Activity.RESULT_FIRST_USER
	}

	private lateinit var viewModel: RegistExecuteViewModel
	private lateinit var binding: ActivityRegistExecuteBinding
	private var guided = false
	private lateinit var consoleName: String

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		enableAppEdgeToEdge()
		binding = ActivityRegistExecuteBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.applySystemBarInsets()

		viewModel = ViewModelProvider(this, viewModelFactory { RegistExecuteViewModel(getDatabase(this)) })
			.get(RegistExecuteViewModel::class.java)
		guided = intent.getBooleanExtra(EXTRA_GUIDED, false)
		consoleName = intent.getStringExtra(EXTRA_CONSOLE_NAME)?.takeIf { it.isNotBlank() }
			?: getString(R.string.regist_option_ps5)

		viewModel.state.observe(this, Observer {
			if(it == RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE && guided)
				viewModel.saveHost()
			else
				renderState(it)
		})

		val previewState = intent.getStringExtra(EXTRA_PREVIEW_STATE)
		if(fi.madekivi.pleikkari.BuildConfig.DEBUG && previewState != null)
		{
			renderState(if(previewState == PREVIEW_SUCCESS)
				RegistExecuteViewModel.State.SUCCESSFUL
			else
				RegistExecuteViewModel.State.RUNNING)
			return
		}

		val registInfo = IntentCompat.getParcelableExtra(intent, EXTRA_REGIST_INFO, RegistInfo::class.java)
		if(registInfo == null)
		{
			finish()
			return
		}
		viewModel.start(registInfo,
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0)
			else
				null)
	}

	private fun renderState(state: RegistExecuteViewModel.State)
	{
		binding.infoTextView.visibility = View.VISIBLE
		binding.progressBar.visibility = if(state == RegistExecuteViewModel.State.RUNNING || state == RegistExecuteViewModel.State.IDLE) View.VISIBLE else View.GONE
		binding.primaryButton.visibility = View.GONE
		when(state)
		{
			RegistExecuteViewModel.State.IDLE, RegistExecuteViewModel.State.RUNNING ->
				binding.infoTextView.text = getString(R.string.linking_console, consoleName)
			RegistExecuteViewModel.State.FAILED, RegistExecuteViewModel.State.STOPPED ->
			{
				binding.infoTextView.text = getString(R.string.link_console_failed, consoleName)
				binding.primaryButton.visibility = View.VISIBLE
				binding.primaryButton.setText(R.string.action_retry)
				binding.primaryButton.setOnClickListener {
					setResult(RESULT_FAILED)
					finish()
				}
			}
			RegistExecuteViewModel.State.SUCCESSFUL ->
			{
				binding.infoTextView.text = getString(R.string.console_ready, consoleName)
				binding.primaryButton.visibility = View.VISIBLE
				binding.primaryButton.setText(if(guided) R.string.action_play else android.R.string.ok)
				binding.primaryButton.setOnClickListener {
					setResult(RESULT_OK)
					finish()
				}
			}
			RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE -> showDuplicateDialog()
		}
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.stop()
	}

	private var dialog: AlertDialog? = null

	private fun showDuplicateDialog()
	{
		if(dialog != null)
			return

		val macStr = viewModel.host?.serverMac?.let { MacAddress(it).toString() } ?: ""

		dialog = MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_regist_duplicate, macStr))
			.setNegativeButton(R.string.action_regist_discard) { _, _ ->  }
			.setPositiveButton(R.string.action_regist_overwrite) { _, _ ->
				viewModel.saveHost()
			}
			.create()
			.also { it.show() }

	}
}
