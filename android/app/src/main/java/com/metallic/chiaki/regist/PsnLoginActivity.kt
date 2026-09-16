// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.applySystemBarInsets
import com.metallic.chiaki.common.ext.enableAppEdgeToEdge
import com.metallic.chiaki.databinding.ActivityPsnLoginBinding
import kotlinx.coroutines.launch

class PsnLoginActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "psn_account_id"
	}

	private lateinit var binding: ActivityPsnLoginBinding
	private var handlingRedirect = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		enableAppEdgeToEdge()
		binding = ActivityPsnLoginBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.applySystemBarInsets(top = false)
		binding.toolbar.applySystemBarInsets(left = false, right = false, bottom = false)
		binding.toolbar.setNavigationOnClickListener { finish() }
		configureWebView()
		onBackPressedDispatcher.addCallback(this) {
			if(binding.webView.canGoBack() && !handlingRedirect)
				binding.webView.goBack()
			else
				finish()
		}
		if(savedInstanceState == null)
			binding.webView.loadUrl(PsnAuth.loginUrl())
		else
			binding.webView.restoreState(savedInstanceState)
	}

	private fun configureWebView()
	{
		binding.webView.settings.apply {
			javaScriptEnabled = true
			domStorageEnabled = true
			allowFileAccess = false
			allowContentAccess = false
			mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
		}
		CookieManager.getInstance().apply {
			setAcceptCookie(true)
			setAcceptThirdPartyCookies(binding.webView, true)
		}
		binding.webView.webChromeClient = object : WebChromeClient()
		{
			override fun onProgressChanged(view: WebView?, newProgress: Int)
			{
				if(!handlingRedirect)
				{
					binding.progressBar.isIndeterminate = false
					binding.progressBar.progress = newProgress
					binding.progressBar.visibility = if(newProgress == 100) View.GONE else View.VISIBLE
				}
			}
		}
		binding.webView.webViewClient = object : WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
				handleRedirect(request.url)

			@Suppress("DEPRECATION")
			override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean =
				handleRedirect(Uri.parse(url))

			override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?)
			{
				super.onPageStarted(view, url, favicon)
				if(url != null)
					handleRedirect(Uri.parse(url))
			}
		}
	}

	private fun handleRedirect(uri: Uri): Boolean
	{
		if(uri.scheme != "https" || uri.host != "remoteplay.dl.playstation.net" || uri.path != "/remoteplay/redirect")
			return false
		if(handlingRedirect)
			return true

		val code = uri.getQueryParameter("code")
		if(code.isNullOrBlank())
		{
			showError(getString(R.string.psn_login_redirect_invalid))
			return true
		}

		handlingRedirect = true
		binding.webView.stopLoading()
		binding.webView.visibility = View.INVISIBLE
		binding.progressBar.visibility = View.VISIBLE
		binding.progressBar.isIndeterminate = true
		lifecycleScope.launch {
			runCatching {
				PsnAuth.exchangeCode(code).also { result ->
					PsnCredentialStore(this@PsnLoginActivity).putRefreshToken(result.refreshToken)
					Preferences(this@PsnLoginActivity).psnAccountId = result.accountId
				}
			}
				.onSuccess { result ->
					setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, result.accountId))
					finish()
				}
				.onFailure { error -> showError(error.message ?: getString(R.string.psn_login_failed)) }
		}
		return true
	}

	private fun showError(message: String)
	{
		handlingRedirect = true
		binding.webView.stopLoading()
		binding.webView.visibility = View.INVISIBLE
		binding.progressBar.visibility = View.GONE
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.psn_login_failed)
			.setMessage(message)
			.setPositiveButton(R.string.action_retry) { _, _ -> restartLogin() }
			.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
			.setOnCancelListener { finish() }
			.show()
	}

	private fun restartLogin()
	{
		handlingRedirect = false
		binding.webView.visibility = View.VISIBLE
		binding.webView.clearHistory()
		binding.webView.loadUrl(PsnAuth.loginUrl())
	}

	override fun onSaveInstanceState(outState: Bundle)
	{
		binding.webView.saveState(outState)
		super.onSaveInstanceState(outState)
	}

	override fun onDestroy()
	{
		binding.webView.stopLoading()
		binding.webView.webChromeClient = null
		binding.webView.webViewClient = WebViewClient()
		binding.webView.destroy()
		super.onDestroy()
	}
}
