// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import com.metallic.chiaki.remote.PsnServiceEndpoints

/** Name of the object the passkey hook calls; see [PSN_PASSKEY_HOOK_JS]. */
internal const val PSN_PASSKEY_BRIDGE = "PleikkariSignIn"

/*
 * Sony's sign-in page asks for a passkey through WebAuthn. The WebView can never satisfy that for
 * this app: Credential Manager checks the relying party's assetlinks.json, and Sony's lists only
 * its own apps. Sony also removes the password once an account has a passkey, so such an account
 * cannot finish in the WebView at all. The hook tells the app when the page asks, and the app
 * moves the sign-in to the browser. Conditional (autofill) requests stay pending instead, because
 * pages make them on load for every account.
 */
internal val PSN_PASSKEY_HOOK_JS = """
(function() {
	if(window.__pleikkariPasskeyHook || !window.$PSN_PASSKEY_BRIDGE)
		return;
	window.__pleikkariPasskeyHook = true;
	var pending = function() { return new Promise(function() {}); };
	var intercept = function(original) {
		return function(options) {
			if(!options || !options.publicKey)
				return original ? original(options) : Promise.reject(new DOMException('', 'NotSupportedError'));
			if(options.mediation !== 'conditional')
				window.$PSN_PASSKEY_BRIDGE.passkeyRequested();
			return pending();
		};
	};
	var container = navigator.credentials;
	if(!container) {
		container = {};
		Object.defineProperty(navigator, 'credentials', { value: container, configurable: true });
	}
	container.get = intercept(container.get && container.get.bind(container));
	container.create = intercept(container.create && container.create.bind(container));
	if(!window.PublicKeyCredential)
		window.PublicKeyCredential = function() {};
	window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable = function() { return Promise.resolve(true); };
	window.PublicKeyCredential.isConditionalMediationAvailable = function() { return Promise.resolve(false); };
})();
""".trimIndent()

private val PSN_SIGN_IN_HOST_SUFFIXES = listOf("sony.com", "sonyentertainmentnetwork.com", "playstation.com")

/**
 * Only Sony's own sign-in pages may move the sign-in to the browser. A PSN mock build (PLE-284)
 * also accepts the mock's host, which is null in every other build.
 */
internal fun isPsnSignInHost(host: String?, mockHost: String? = PsnServiceEndpoints.current.mockHost): Boolean
{
	val normalized = host?.lowercase()?.trimEnd('.') ?: return false
	if(mockHost != null && normalized == mockHost.lowercase())
		return true
	return PSN_SIGN_IN_HOST_SUFFIXES.any { normalized == it || normalized.endsWith(".$it") }
}

/**
 * A redirect link on the clipboard is only taken if it was copied after the browser opened, so an
 * old, already used link is never exchanged. Android 7 records no clip time; accept it there.
 */
internal fun isPsnClipFromThisSignIn(clipTimestampMs: Long?, browserOpenedAtMs: Long): Boolean =
	clipTimestampMs == null || clipTimestampMs >= browserOpenedAtMs

internal fun shouldHandlePsnBrowserReturn(
	browserSignIn: Boolean,
	handlingRedirect: Boolean,
	browserLaunched: Boolean,
	browserPauseObserved: Boolean
): Boolean = browserSignIn && !handlingRedirect && browserLaunched && browserPauseObserved

/**
 * A redirect handed over by the sign-in tab's Finish button. The receiver also opens the app, which
 * Android allows while the tab sits in the app's own task; if a launcher or browser setup places it
 * elsewhere, the redirect waits here until the sign-in screen is in front again.
 */
internal object PsnPendingRedirect
{
	@Volatile private var address: String? = null

	fun offer(value: String)
	{
		if(parsePsnRedirect(value) != PsnRedirect.NotRedirect)
			address = value
	}

	fun take(): String? = synchronized(this) { address.also { address = null } }
}
