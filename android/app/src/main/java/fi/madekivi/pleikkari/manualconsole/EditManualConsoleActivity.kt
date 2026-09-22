// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.manualconsole

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import fi.madekivi.pleikkari.R
import fi.madekivi.pleikkari.common.RegisteredHost
import fi.madekivi.pleikkari.common.ext.RevealActivity
import fi.madekivi.pleikkari.common.ext.applySystemBarInsets
import fi.madekivi.pleikkari.common.ext.enableAppEdgeToEdge
import fi.madekivi.pleikkari.common.ext.viewModelFactory
import fi.madekivi.pleikkari.common.getDatabase
import fi.madekivi.pleikkari.databinding.ActivityEditManualBinding

class EditManualConsoleActivity: AppCompatActivity(), RevealActivity
{
	companion object
	{
		const val EXTRA_MANUAL_HOST_ID = "manual_host_id"
	}

	private lateinit var viewModel: EditManualConsoleViewModel
	private lateinit var binding: ActivityEditManualBinding

	override val revealIntent: Intent get() = intent
	override val revealRootLayout: View get() = binding.rootLayout
	override val revealWindow: Window get() = window

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		enableAppEdgeToEdge()
		binding = ActivityEditManualBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.applySystemBarInsets()
		handleReveal()
		window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
		binding.rootLayout.isFillViewport = true
		keepFocusedInputVisible()

		viewModel = ViewModelProvider(this, viewModelFactory {
				EditManualConsoleViewModel(getDatabase(this),
					if(intent.hasExtra(EXTRA_MANUAL_HOST_ID))
						intent.getLongExtra(EXTRA_MANUAL_HOST_ID, 0)
					else
						null)
			})
			.get(EditManualConsoleViewModel::class.java)

		viewModel.existingHost?.observe(this, Observer {
			binding.hostEditText.setText(it.host)
		})

		viewModel.selectedRegisteredHost.observe(this, Observer {
			binding.registeredHostTextView.setText(titleForRegisteredHost(it))
		})

		viewModel.registeredHosts.observe(this, Observer { hosts ->
			val hasRegistrationChoice = hosts.any { it != null }
			binding.registeredHostTextInputLayout.visibility =
				if(hasRegistrationChoice) View.VISIBLE else View.GONE
			binding.registeredHostTextView.setAdapter(ArrayAdapter<String>(this, R.layout.dropdown_menu_popup_item,
				hosts.map { titleForRegisteredHost(it) }))
			binding.registeredHostTextView.onItemClickListener = object: AdapterView.OnItemClickListener {
				override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
				{
					if(position >= hosts.size)
						return
					val host = hosts[position]
					viewModel.selectedRegisteredHost.value = host
				}
			}
		})

		binding.saveButton.setOnClickListener { saveHost() }
	}

	private fun titleForRegisteredHost(registeredHost: RegisteredHost?) =
		if(registeredHost == null)
			getString(R.string.add_manual_regist_on_connect)
		else
			"${registeredHost.serverNickname ?: ""} (${registeredHost.serverMac})"

	private fun keepFocusedInputVisible()
	{
		val initialBottomPadding = binding.formContentLayout.paddingBottom
		ViewCompat.setOnApplyWindowInsetsListener(binding.formContentLayout) { view, insets ->
			val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
			val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
			view.updatePadding(bottom = initialBottomPadding + (imeBottom - systemBottom).coerceAtLeast(0))
			insets
		}
		ViewCompat.requestApplyInsets(binding.formContentLayout)

		binding.rootLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
			if(binding.hostEditText.hasFocus())
				revealInput(binding.hostEditText)
		}
		binding.hostEditText.setOnFocusChangeListener { view, hasFocus ->
			if(hasFocus)
				revealInput(view)
		}
	}

	private fun revealInput(input: View)
	{
		input.postDelayed({
			val extraBottom = (24 * resources.displayMetrics.density).toInt()
			input.requestRectangleOnScreen(
				Rect(0, 0, input.width, input.height + extraBottom),
				true
			)
		}, 200)
	}

	private fun saveHost()
	{
		val host = binding.hostEditText.text.toString().trim()
		if(host.isEmpty())
		{
			binding.hostEditText.error = getString(R.string.entered_host_invalid)
			return
		}

		binding.saveButton.isEnabled = false
		viewModel.saveHost(host)
		finish()
	}
}
