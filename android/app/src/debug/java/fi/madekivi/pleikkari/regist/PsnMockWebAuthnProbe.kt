// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.regist

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import fi.madekivi.pleikkari.common.ext.applySystemBarInsets
import fi.madekivi.pleikkari.common.ext.enableAppEdgeToEdge
import fi.madekivi.pleikkari.remote.PsnServiceEndpoints

/**
 * PLE-324: can the app's own WebView complete a passkey sign-in if it asks to be treated as a
 * browser (`WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER`)? If it could, the redirect
 * would be read straight from the navigation and sign-in would need no tap on the tab at all.
 *
 * Only a -PchiakiPsnMock build has this activity (src/psnMock/AndroidManifest.xml). It opens the
 * sign-in page in a WebView with the requested WebAuthn mode and logs, under the tag [TAG], what the
 * device offers (feature support, WebView provider, the set-origin permission), every WebAuthn call
 * the page makes with its exact outcome, and whether the redirect was captured. It never exchanges
 * the code and never logs it.
 *
 * ```
 * adb shell am start -n fi.madekivi.pleikkari.psnmock/fi.madekivi.pleikkari.regist.PsnMockWebAuthnProbe \
 *     --es mode browser|app|none --es target mock|sony
 * adb logcat -s PsnWebAuthnProbe
 * ```
 */
class PsnMockWebAuthnProbe : AppCompatActivity()
{
	companion object
	{
		private const val TAG = "PsnWebAuthnProbe"
		const val EXTRA_MODE = "mode"
		const val EXTRA_TARGET = "target"
		private const val CONSOLE_PREFIX = "PLE324:"

		/** Reports every WebAuthn call and its outcome through the console, without changing it. */
		private val OBSERVER_JS = """
			(function() {
				if(window.__ple324Observer || !navigator.credentials)
					return;
				window.__ple324Observer = true;
				var report = function(what) { console.log('$CONSOLE_PREFIX ' + location.origin + ' ' + what); };
				['get', 'create'].forEach(function(name) {
					var original = navigator.credentials[name] && navigator.credentials[name].bind(navigator.credentials);
					if(!original)
						return;
					navigator.credentials[name] = function(options) {
						var kind = options && options.publicKey ? 'publicKey' : 'other';
						var mediation = options && options.mediation || 'default';
						report(name + ' called kind=' + kind + ' mediation=' + mediation);
						return original(options).then(function(credential) {
							report(name + ' resolved type=' + (credential && credential.type) +
								' attachment=' + (credential && credential.authenticatorAttachment));
							return credential;
						}, function(error) {
							report(name + ' rejected ' + (error && error.name) + ': ' + (error && error.message));
							throw error;
						});
					};
				});
				if(window.PublicKeyCredential) {
					PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable().then(
						function(v) { report('uvpaa=' + v); }, function(e) { report('uvpaa error ' + e.name); });
					if(PublicKeyCredential.isConditionalMediationAvailable)
						PublicKeyCredential.isConditionalMediationAvailable().then(
							function(v) { report('conditional=' + v); }, function(e) { report('conditional error ' + e.name); });
				} else {
					report('no PublicKeyCredential');
				}
			})();
		""".trimIndent()
	}

	private lateinit var webView: WebView
	private lateinit var status: TextView
	private lateinit var endpoints: PsnServiceEndpoints

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		enableAppEdgeToEdge()
		status = TextView(this).apply { setPadding(24, 8, 24, 8) }
		webView = WebView(this)
		setContentView(LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
			addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
			applySystemBarInsets()
		})

		val mode = intent.getStringExtra(EXTRA_MODE) ?: "browser"
		val sony = intent.getStringExtra(EXTRA_TARGET) == "sony"
		endpoints = if(sony) PsnServiceEndpoints.Production else PsnServiceEndpoints.current
		reportEnvironment()

		webView.settings.apply {
			javaScriptEnabled = true
			domStorageEnabled = true
			allowFileAccess = false
			allowContentAccess = false
			mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
		}
		applyMode(mode)
		installObserver()
		webView.webChromeClient = object : WebChromeClient()
		{
			override fun onConsoleMessage(message: ConsoleMessage): Boolean
			{
				val text = message.message()
				if(text.startsWith(CONSOLE_PREFIX))
					report(text.removePrefix(CONSOLE_PREFIX).trim())
				return super.onConsoleMessage(message)
			}
		}
		webView.webViewClient = object : WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
				captured(request.url.toString())

			override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?)
			{
				super.onPageStarted(view, url, favicon)
				if(url != null && captured(url))
					view?.stopLoading()
				else if(!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
					view?.evaluateJavascript(OBSERVER_JS, null)
			}
		}
		webView.loadUrl(signInUrl(sony))
	}

	private fun reportEnvironment()
	{
		val provider = WebViewCompat.getCurrentWebViewPackage(this)
		report("sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
		report("webview=${provider?.packageName} ${provider?.versionName}")
		report("feature WEB_AUTHENTICATION=${WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)}")
		if(Build.VERSION.SDK_INT >= 34)
		{
			val granted = checkSelfPermission(android.Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN) ==
				PackageManager.PERMISSION_GRANTED
			report("permission CREDENTIAL_MANAGER_SET_ORIGIN granted=$granted")
		}
	}

	private fun applyMode(mode: String)
	{
		val support = when(mode)
		{
			"none" -> WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
			"app" -> WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
			else -> WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
		}
		if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
		{
			report("mode $mode not applied: WEB_AUTHENTICATION unsupported by this WebView")
			return
		}
		WebSettingsCompat.setWebAuthenticationSupport(webView.settings, support)
		report("mode $mode applied, reads back ${WebSettingsCompat.getWebAuthenticationSupport(webView.settings)}")
	}

	private fun installObserver()
	{
		if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
			WebViewCompat.addDocumentStartJavaScript(webView, OBSERVER_JS, setOf("*"))
	}

	/** The app's sign-in address, pointed at Sony's page and redirect instead of the mock's when asked. */
	private fun signInUrl(sony: Boolean): String
	{
		val mockUrl = Uri.parse(PsnAuth.loginUrl())
		if(!sony)
			return mockUrl.toString()
		val builder = Uri.parse(PsnServiceEndpoints.Production.authorizeUrl).buildUpon()
		for(name in mockUrl.queryParameterNames)
		{
			val value = if(name == "redirect_uri") PsnServiceEndpoints.Production.redirectUrl
				else mockUrl.getQueryParameter(name)
			builder.appendQueryParameter(name, value)
		}
		return builder.build().toString()
	}

	private fun captured(url: String): Boolean = when(val redirect = parsePsnRedirect(url, endpoints))
	{
		PsnRedirect.NotRedirect -> false
		PsnRedirect.Invalid -> {
			report("redirect captured without a code: ${Uri.parse(url).query}")
			true
		}
		PsnRedirect.Cancelled -> {
			report("redirect captured as a cancel (error=access_denied)")
			true
		}
		is PsnRedirect.Code -> {
			report("redirect captured with a code (${redirect.value.length} chars); not exchanged")
			true
		}
	}

	private fun report(line: String)
	{
		Log.i(TAG, line)
		runOnUiThread { status.text = line }
	}

	override fun onDestroy()
	{
		webView.destroy()
		super.onDestroy()
	}
}
