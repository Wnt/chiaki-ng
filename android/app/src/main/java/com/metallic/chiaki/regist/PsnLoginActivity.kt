// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
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
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
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
		private const val STATE_EMBEDDED_BROWSER = "embedded_browser"
	}

	private lateinit var binding: ActivityPsnLoginBinding
	private var handlingRedirect = false
	private var embeddedBrowser = false

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
		binding.pasteAddressButton.setOnClickListener { pasteRedirectAddress() }
		binding.openSignInButton.setOnClickListener { launchExternalLogin() }
		onBackPressedDispatcher.addCallback(this) {
			if(embeddedBrowser && binding.webView.canGoBack() && !handlingRedirect)
				binding.webView.goBack()
			else
				finish()
		}

		if(intent.dataString?.let(::handleRedirect) == true)
			return

		embeddedBrowser = savedInstanceState?.getBoolean(STATE_EMBEDDED_BROWSER)
			?: Preferences(this).psnLoginInAppBrowser
		if(embeddedBrowser)
		{
			showEmbeddedBrowser()
			if(savedInstanceState == null)
				binding.webView.loadUrl(PsnAuth.loginUrl())
			else
				binding.webView.restoreState(savedInstanceState)
		}
		else
		{
			showExternalInstructions()
			if(savedInstanceState == null)
				launchExternalLogin()
		}
	}

	override fun onNewIntent(intent: Intent)
	{
		super.onNewIntent(intent)
		setIntent(intent)
		intent.dataString?.let(::handleRedirect)
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
				if(embeddedBrowser && !handlingRedirect)
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
				handleRedirect(request.url.toString())

			@Suppress("DEPRECATION")
			override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean =
				handleRedirect(url)

			override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?)
			{
				super.onPageStarted(view, url, favicon)
				url?.let(::handleRedirect)
			}
		}
	}

	private fun launchExternalLogin()
	{
		val uri = Uri.parse(PsnAuth.loginUrl())
		val customTabsPackage = findCustomTabsPackage(uri)
		if(customTabsPackage != null)
		{
			try
			{
				CustomTabsIntent.Builder()
					.setShowTitle(true)
					.build()
					.apply { intent.setPackage(customTabsPackage) }
					.launchUrl(this, uri)
				return
			}
			catch(_: ActivityNotFoundException)
			{
				// The provider disappeared between discovery and launch; try a browser next.
			}
		}

		try
		{
			startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
		}
		catch(_: ActivityNotFoundException)
		{
			embeddedBrowser = true
			showEmbeddedBrowser()
			binding.webView.loadUrl(PsnAuth.loginUrl())
		}
	}

	private fun findCustomTabsPackage(uri: Uri): String?
	{
		val viewIntent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
		val defaultPackage = packageManager.resolveActivity(viewIntent, 0)?.activityInfo?.packageName
		val candidates = packageManager.queryIntentActivities(viewIntent, 0)
			.map { it.activityInfo.packageName }
			.distinct()
			.sortedByDescending { it == defaultPackage }
		return candidates.firstOrNull { packageName ->
			packageManager.resolveService(
				Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(packageName),
				0
			) != null
		}
	}

	private fun pasteRedirectAddress()
	{
		val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		val address = clipboard.primaryClip
			?.takeIf { it.itemCount > 0 }
			?.getItemAt(0)
			?.coerceToText(this)
			?.toString()
			.orEmpty()
		if(address.isBlank())
		{
			binding.redirectUrl.error = getString(R.string.psn_login_no_clipboard_address)
			return
		}
		binding.redirectUrl.setText(address)
		if(!handleRedirect(address))
			binding.redirectUrl.error = getString(R.string.psn_login_redirect_invalid)
	}

	private fun handleRedirect(url: String): Boolean
	{
		val redirect = parsePsnRedirect(url)
		if(handlingRedirect && redirect != PsnRedirect.NotRedirect)
			return true
		return when(redirect)
		{
			PsnRedirect.NotRedirect -> false
			PsnRedirect.Invalid -> {
				showError(getString(R.string.psn_login_redirect_invalid))
				true
			}
			is PsnRedirect.Code -> {
				exchangeCode(redirect.value)
				true
			}
		}
	}

	private fun exchangeCode(code: String)
	{
		if(handlingRedirect)
			return
		handlingRedirect = true
		binding.webView.stopLoading()
		binding.webView.visibility = View.GONE
		binding.externalLoginContainer.visibility = View.GONE
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
	}

	private fun showError(message: String)
	{
		handlingRedirect = true
		binding.webView.stopLoading()
		binding.webView.visibility = View.GONE
		binding.externalLoginContainer.visibility = View.GONE
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
		if(Preferences(this).psnLoginInAppBrowser)
		{
			embeddedBrowser = true
			showEmbeddedBrowser()
			binding.webView.clearHistory()
			binding.webView.loadUrl(PsnAuth.loginUrl())
		}
		else
		{
			embeddedBrowser = false
			showExternalInstructions()
			launchExternalLogin()
		}
	}

	private fun showExternalInstructions()
	{
		binding.progressBar.visibility = View.GONE
		binding.webView.visibility = View.GONE
		binding.externalLoginContainer.visibility = View.VISIBLE
	}

	private fun showEmbeddedBrowser()
	{
		binding.externalLoginContainer.visibility = View.GONE
		binding.webView.visibility = View.VISIBLE
		binding.progressBar.visibility = View.VISIBLE
		binding.progressBar.isIndeterminate = true
	}

	override fun onSaveInstanceState(outState: Bundle)
	{
		outState.putBoolean(STATE_EMBEDDED_BROWSER, embeddedBrowser)
		if(embeddedBrowser)
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
