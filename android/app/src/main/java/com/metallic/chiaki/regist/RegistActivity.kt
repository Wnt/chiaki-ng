// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.RevealActivity
import com.metallic.chiaki.common.ext.applySystemBarInsets
import com.metallic.chiaki.common.ext.enableAppEdgeToEdge
import com.metallic.chiaki.databinding.ActivityRegistBinding
import com.metallic.chiaki.lib.RegistInfo
import java.lang.IllegalArgumentException

class RegistActivity: AppCompatActivity(), RevealActivity
{
	companion object
	{
		const val EXTRA_HOST = "regist_host"
		const val EXTRA_BROADCAST = "regist_broadcast"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"
		const val EXTRA_CONSOLE_NAME = "regist_console_name"
		const val EXTRA_GUIDED = "regist_guided"
		const val EXTRA_PREVIEW = "regist_preview"
		const val EXTRA_REGISTERED_HOST = "registered_host"

		private const val PIN_LENGTH = 8

		private const val REQUEST_REGIST = 1
		private const val REQUEST_PSN_LOGIN = 2
	}

	private lateinit var binding: ActivityRegistBinding
	private lateinit var preferences: Preferences
	private var manualAccountIdVisible = false
	private var currentAccountId: String? = null
	private var guided = false

	override val revealWindow: Window get() = window
	override val revealIntent: Intent get() = intent
	override val revealRootLayout: View get() = binding.rootLayout

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		enableAppEdgeToEdge()
		binding = ActivityRegistBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.applySystemBarInsets()
		handleReveal()
		window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
		binding.rootLayout.isFillViewport = true
		keepFocusedInputVisible()

		preferences = Preferences(this)
		guided = intent.getBooleanExtra(EXTRA_GUIDED, false)

		binding.hostEditText.setText(intent.getStringExtra(EXTRA_HOST) ?: "255.255.255.255")
		binding.broadcastCheckBox.isChecked = intent.getBooleanExtra(EXTRA_BROADCAST, true)

		binding.registButton.setOnClickListener { doRegist() }
		binding.psnSignInButton.setOnClickListener {
			startActivityForResult(Intent(this, PsnLoginActivity::class.java), REQUEST_PSN_LOGIN)
		}
		binding.psnManualEntryButton.setOnClickListener {
			manualAccountIdVisible = !manualAccountIdVisible
			updatePsnControls()
			if(manualAccountIdVisible)
				binding.psnIdEditText.requestFocus()
		}
		if(preferences.psnSignInEnabled || guided)
		{
			currentAccountId = preferences.psnAccountId
			if(currentAccountId.isNullOrBlank() && intent.getBooleanExtra(EXTRA_PREVIEW, false))
				currentAccountId = Base64.encodeToString(ByteArray(RegistInfo.ACCOUNT_ID_SIZE), Base64.NO_WRAP)
			binding.psnIdEditText.setText(currentAccountId)
		}

		updatePsnControls()
		if(guided)
			showGuidedPinEntry()
	}

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

		val inputs = setOf(binding.hostEditText, binding.psnIdEditText, binding.pinEditText)
		binding.rootLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
			currentFocus?.takeIf(inputs::contains)?.let(::revealInput)
		}
		inputs.forEach { input ->
			input.setOnFocusChangeListener { view, hasFocus ->
				if(hasFocus)
					revealInput(view)
			}
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

	private fun showGuidedPinEntry()
	{
		val consoleName = intent.getStringExtra(EXTRA_CONSOLE_NAME)
			?.takeIf { it.isNotBlank() }
			?: getString(R.string.regist_option_ps5)
		binding.titleTextView.text = getString(R.string.link_console_title, consoleName)
		binding.hostTextInputLayout.visibility = View.GONE
		binding.broadcastCheckBox.visibility = View.GONE
		binding.psnSignInButton.visibility = View.GONE
		binding.psnAccountIdStatusTextView.visibility = View.GONE
		binding.psnManualEntryButton.visibility = View.GONE
		binding.psnAccountIdHelpGroup.visibility = View.GONE
		binding.psnIdTextInputLayout.visibility = View.GONE
		binding.pinHelpBeforeTextView.visibility = View.GONE
		binding.pinHelpAfterTextView.visibility = View.GONE
		binding.pinHelpNavigationTextView.alpha = 1f
		binding.registButton.setText(R.string.action_link_console)
		binding.pinEditText.requestFocus()
	}

	private fun updatePsnControls()
	{
		val signInEnabled = preferences.psnSignInEnabled
		val hasAccountId = !binding.psnIdEditText.text.isNullOrBlank()
		binding.psnSignInButton.visibility = if(signInEnabled) View.VISIBLE else View.GONE
		binding.psnManualEntryButton.visibility = if(signInEnabled) View.VISIBLE else View.GONE
		binding.psnManualEntryButton.setText(if(manualAccountIdVisible) R.string.action_psn_hide_manual else R.string.action_psn_enter_manually)
		binding.psnAccountIdStatusTextView.visibility = if(signInEnabled && hasAccountId) View.VISIBLE else View.GONE
		binding.psnAccountIdHelpGroup.visibility = if(!signInEnabled || manualAccountIdVisible) View.VISIBLE else View.GONE
		binding.psnIdTextInputLayout.visibility = if(!signInEnabled || manualAccountIdVisible || hasAccountId) View.VISIBLE else View.GONE
		binding.psnIdEditText.isEnabled = !signInEnabled || manualAccountIdVisible
	}

	private fun doRegist()
	{
		val host = binding.hostEditText.text.toString().trim()
		val hostValid = host.isNotEmpty()
		val broadcast = binding.broadcastCheckBox.isChecked

		val psnId = binding.psnIdEditText.text.toString().trim()
		val psnAccountId: ByteArray? =
			try { Base64.decode(psnId, Base64.DEFAULT) } catch(e: IllegalArgumentException) { null }
		val psnIdValid = psnAccountId != null && psnAccountId.size == RegistInfo.ACCOUNT_ID_SIZE

		val pin = binding.pinEditText.text.toString()
		val pinValid = pin.length == PIN_LENGTH

		binding.hostEditText.error = if(!hostValid) getString(R.string.entered_host_invalid) else null
		binding.psnIdEditText.error =
			if(!psnIdValid)
				getString(R.string.regist_psn_account_id_invalid)
			else
				null
		binding.pinEditText.error = if(!pinValid) getString(R.string.regist_pin_invalid, PIN_LENGTH) else null

		if(!hostValid || psnAccountId == null || !psnIdValid || !pinValid)
			return
		if(preferences.psnSignInEnabled)
		{
			currentAccountId = Base64.encodeToString(psnAccountId, Base64.NO_WRAP)
			preferences.psnAccountId = currentAccountId
		}

		val registInfo = RegistInfo.forPS5(host, broadcast, psnAccountId, pin.toInt())

		Intent(this, RegistExecuteActivity::class.java).also {
			it.putExtra(RegistExecuteActivity.EXTRA_REGIST_INFO, registInfo)
			it.putExtra(RegistExecuteActivity.EXTRA_CONSOLE_NAME, intent.getStringExtra(EXTRA_CONSOLE_NAME))
			it.putExtra(RegistExecuteActivity.EXTRA_GUIDED, guided)
			if(intent.getBooleanExtra(EXTRA_PREVIEW, false))
				it.putExtra(RegistExecuteActivity.EXTRA_PREVIEW_STATE, RegistExecuteActivity.PREVIEW_RUNNING)
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				it.putExtra(RegistExecuteActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0L))
			startActivityForResult(it, REQUEST_REGIST)
		}
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		super.onActivityResult(requestCode, resultCode, data)
		if(requestCode == REQUEST_REGIST && resultCode == RESULT_OK)
		{
			setResult(RESULT_OK, Intent().putExtra(EXTRA_REGISTERED_HOST, binding.hostEditText.text.toString().trim()))
			finish()
		}
		else if(requestCode == REQUEST_PSN_LOGIN && resultCode == RESULT_OK)
		{
			val accountId = data?.getStringExtra(PsnLoginActivity.EXTRA_ACCOUNT_ID) ?: preferences.psnAccountId
			currentAccountId = accountId
			binding.psnIdEditText.setText(accountId)
			manualAccountIdVisible = false
			updatePsnControls()
		}
	}
}
