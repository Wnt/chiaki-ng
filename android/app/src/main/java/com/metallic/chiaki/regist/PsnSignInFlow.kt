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

/** Silent reopens of the sign-in tab in a row that may end without a code before the app stops (PLE-323). */
internal const val PSN_SILENT_BROWSER_RECOVERIES = 2

internal enum class PsnBrowserReturnStep { REOPEN_TAB, OFFER_CHOICES }

/**
 * The user left the sign-in tab without the Finish button: its X, back, the menu's "Open in
 * browser", or the app switcher. The browser keeps Sony's session cookie, so reopening the sign-in
 * comes straight back to a fresh redirect; after [PSN_SILENT_BROWSER_RECOVERIES] reopens in a row
 * that still brought no code, the user is choosing to leave, and gets the choices instead of a loop.
 */
internal fun psnBrowserReturnStep(recoveriesInARow: Int): PsnBrowserReturnStep =
	if(recoveriesInARow < PSN_SILENT_BROWSER_RECOVERIES) PsnBrowserReturnStep.REOPEN_TAB else PsnBrowserReturnStep.OFFER_CHOICES

/**
 * Off only in a PSN mock build running the PLE-302 fault `exit-loses-code`, which puts back the dead
 * end this recovery removed so the onboarding driver can show it catches it.
 */
internal object PsnBrowserRecovery
{
	@Volatile var enabled = true
}

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
