// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.applySystemBarInsets
import com.metallic.chiaki.common.ext.enableAppEdgeToEdge
import com.metallic.chiaki.databinding.ActivityPsnLoginBinding
import kotlinx.coroutines.launch

/**
 * Signs in inside the default browser's Custom Tab, the one surface that can do everything Sony's
 * page asks for: passkeys included, which the app's own WebView can never provide for this package
 * (Sony's assetlinks.json names only Sony's apps). One surface means the address is typed once
 * (PLE-312: starting in the WebView and moving to the tab on the passkey prompt asked for it twice,
 * because the two have separate cookie jars). The tab carries a Finish sign-in button handing the
 * page address back to the app. The WebView remains only for a device with no Custom Tabs browser.
 */
class PsnLoginActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "psn_account_id"
		private const val STATE_BROWSER_SIGN_IN = "browser_sign_in"
		private const val STATE_BROWSER_LAUNCHED = "browser_launched"
		private const val STATE_BROWSER_PAUSE_OBSERVED = "browser_pause_observed"
		private const val STATE_BROWSER_OPENED_AT = "browser_opened_at"
	}

	private lateinit var binding: ActivityPsnLoginBinding
	private var handlingRedirect = false
	private var browserSignIn = false
	private var browserLaunched = false
	private var browserPauseObserved = false
	private var browserOpenedAtMs = 0L

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		enableAppEdgeToEdge()
		binding = ActivityPsnLoginBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.applySystemBarInsets(top = false)
		binding.toolbar.applySystemBarInsets(left = false, right = false, bottom = false)
		binding.toolbar.setNavigationOnClickListener { finish() }
		binding.continueButton.setOnClickListener { launchBrowserSignIn() }
		configureWebView()
		onBackPressedDispatcher.addCallback(this) {
			if(!browserSignIn && binding.webView.canGoBack() && !handlingRedirect)
				binding.webView.goBack()
			else
				finish()
		}

		if(intent.dataString?.let(::handleRedirect) == true)
			return

		browserSignIn = savedInstanceState?.getBoolean(STATE_BROWSER_SIGN_IN) ?: false
		browserLaunched = savedInstanceState?.getBoolean(STATE_BROWSER_LAUNCHED) ?: false
		browserPauseObserved = savedInstanceState?.getBoolean(STATE_BROWSER_PAUSE_OBSERVED) ?: false
		browserOpenedAtMs = savedInstanceState?.getLong(STATE_BROWSER_OPENED_AT) ?: 0L
		when
		{
			browserSignIn -> showBrowserWaiting()
			savedInstanceState != null ->
			{
				showWebView()
				binding.webView.restoreState(savedInstanceState)
			}
			launchBrowserSignIn(tabOnly = true) -> Unit
			else ->
			{
				showWebView()
				binding.webView.loadUrl(PsnAuth.loginUrl())
			}
		}
	}

	override fun onPause()
	{
		if(browserLaunched)
			browserPauseObserved = true
		super.onPause()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		// The clipboard is only readable with window focus, which arrives after onResume.
		if(!hasFocus || !shouldHandlePsnBrowserReturn(
			browserSignIn,
			handlingRedirect,
			browserLaunched,
			browserPauseObserved
		))
			return
		browserLaunched = false
		browserPauseObserved = false
		if(PsnPendingRedirect.take()?.let(::handleRedirect) != true && !consumeClipboardRedirect())
			showBrowserReturnedWithoutCode()
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
		binding.webView.addJavascriptInterface(PasskeyBridge(), PSN_PASSKEY_BRIDGE)
		binding.webView.webChromeClient = object : WebChromeClient()
		{
			override fun onProgressChanged(view: WebView?, newProgress: Int)
			{
				if(!browserSignIn && !handlingRedirect)
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
				if(url?.let(::handleRedirect) != true)
					view?.evaluateJavascript(PSN_PASSKEY_HOOK_JS, null)
			}

			override fun onPageCommitVisible(view: WebView?, url: String?)
			{
				super.onPageCommitVisible(view, url)
				view?.evaluateJavascript(PSN_PASSKEY_HOOK_JS, null)
			}

			override fun onPageFinished(view: WebView?, url: String?)
			{
				super.onPageFinished(view, url)
				view?.evaluateJavascript(PSN_PASSKEY_HOOK_JS, null)
			}
		}
	}

	private inner class PasskeyBridge
	{
		@JavascriptInterface
		fun passkeyRequested()
		{
			runOnUiThread {
				if(browserSignIn || handlingRedirect || isFinishing)
					return@runOnUiThread
				if(!isPsnSignInHost(binding.webView.url?.let { Uri.parse(it).host }))
					return@runOnUiThread
				launchBrowserSignIn()
			}
		}
	}

	/**
	 * Opens the sign-in in a browser: a Custom Tab with the Finish sign-in button, or with [tabOnly]
	 * false any browser at all. Returns false when nothing opened, leaving the WebView as the way in.
	 */
	private fun launchBrowserSignIn(tabOnly: Boolean = false): Boolean
	{
		val uri = Uri.parse(PsnAuth.loginUrl())
		val tabIntent = customTabIntent(uri)
		val intents = listOfNotNull(
			tabIntent,
			Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).takeUnless { tabOnly }
		)
		for(browserIntent in intents)
		{
			try
			{
				markBrowserOpened()
				startActivity(browserIntent)
				binding.webView.stopLoading()
				binding.webView.loadUrl("about:blank")
				browserSignIn = true
				showBrowserWaiting()
				return true
			}
			catch(_: ActivityNotFoundException)
			{
				// The provider disappeared between discovery and launch; try the next one.
			}
		}
		// No browser at all: the WebView is the only way left, even without passkeys.
		browserLaunched = false
		return false
	}

	private fun markBrowserOpened()
	{
		browserLaunched = true
		browserPauseObserved = false
		browserOpenedAtMs = System.currentTimeMillis()
		PsnPendingRedirect.take()
	}

	private fun customTabIntent(uri: Uri): Intent?
	{
		val packageName = findCustomTabsPackage(uri) ?: return null
		// A broadcast, not an activity: Android 14 and later drop an activity PendingIntent sent by a
		// browser that does not opt in to background starts (Firefox 152 does not), while a receiver
		// may open the app because the tab runs in the app's task.
		val finishIntent = PendingIntent.getBroadcast(
			this,
			0,
			Intent(this, PsnRedirectReceiver::class.java),
			// The browser fills in the page address, so the intent has to stay mutable.
			PendingIntent.FLAG_UPDATE_CURRENT or
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
		)
		val finishLabel = getString(R.string.action_psn_finish_sign_in)
		val finishIcon = ContextCompat.getDrawable(this, R.drawable.ic_psn_finish_sign_in)!!.toBitmap()
		return CustomTabsIntent.Builder()
			.setShowTitle(true)
			.setActionButton(finishIcon, finishLabel, finishIntent, true)
			.addMenuItem(finishLabel, finishIntent)
			.build()
			.intent
			.setPackage(packageName)
			.setData(uri)
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

	private fun consumeClipboardRedirect(): Boolean
	{
		val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return false
		val clipTimestamp = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			clip.description.timestamp
		else
			null
		if(!isPsnClipFromThisSignIn(clipTimestamp, browserOpenedAtMs))
			return false
		val address = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
		if(parsePsnRedirect(address) !is PsnRedirect.Code)
			return false
		return handleRedirect(address)
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
		PsnPendingRedirect.take()
		binding.webView.stopLoading()
		binding.webView.visibility = View.GONE
		binding.continueButton.visibility = View.GONE
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
				.onFailure {
					showError(getString(R.string.psn_login_link_expired))
				}
		}
	}

	private fun showError(message: String)
	{
		handlingRedirect = true
		binding.webView.stopLoading()
		binding.webView.visibility = View.GONE
		binding.continueButton.visibility = View.GONE
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
		if(browserSignIn)
			launchBrowserSignIn()
		else
		{
			showWebView()
			binding.webView.clearHistory()
			binding.webView.loadUrl(PsnAuth.loginUrl())
		}
	}

	private fun showWebView()
	{
		binding.continueButton.visibility = View.GONE
		binding.webView.visibility = View.VISIBLE
		binding.progressBar.visibility = View.VISIBLE
		binding.progressBar.isIndeterminate = true
	}

	/** Behind the browser tab: nothing to read, nothing to decide. */
	private fun showBrowserWaiting()
	{
		binding.webView.visibility = View.GONE
		binding.progressBar.visibility = View.GONE
		binding.continueButton.visibility = View.GONE
	}

	private fun showBrowserReturnedWithoutCode()
	{
		showBrowserWaiting()
		binding.continueButton.visibility = View.VISIBLE
	}

	override fun onSaveInstanceState(outState: Bundle)
	{
		outState.putBoolean(STATE_BROWSER_SIGN_IN, browserSignIn)
		outState.putBoolean(STATE_BROWSER_LAUNCHED, browserLaunched)
		outState.putBoolean(STATE_BROWSER_PAUSE_OBSERVED, browserPauseObserved)
		outState.putLong(STATE_BROWSER_OPENED_AT, browserOpenedAtMs)
		if(!browserSignIn)
			binding.webView.saveState(outState)
		super.onSaveInstanceState(outState)
	}

	override fun onDestroy()
	{
		binding.webView.stopLoading()
		binding.webView.removeJavascriptInterface(PSN_PASSKEY_BRIDGE)
		binding.webView.webChromeClient = null
		binding.webView.webViewClient = WebViewClient()
		binding.webView.destroy()
		super.onDestroy()
	}
}
