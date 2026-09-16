// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import java.net.URI

/**
 * Every PSN address the app signs in with and lists consoles from. Production is Sony's.
 * A debug build made with -PchiakiPsnMock points them all at the PSN mock (android/psn-mock);
 * the release source set has no mock at all, see [psnMockHost].
 */
internal data class PsnServiceEndpoints(
	val authorizeUrl: String,
	val tokenUrl: String,
	val redirectUrl: String,
	val remote: PsnRemoteEndpoints,
	val mockHost: String? = null
)
{
	val redirectHost: String get() = URI(redirectUrl).host
	val redirectPath: String get() = URI(redirectUrl).path

	companion object
	{
		val Production = PsnServiceEndpoints(
			authorizeUrl = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize",
			tokenUrl = PsnRemoteEndpoints.Production.tokenUrl,
			redirectUrl = "https://remoteplay.dl.playstation.net/remoteplay/redirect",
			remote = PsnRemoteEndpoints.Production
		)

		/** The mock mirrors Sony's paths on one host, so only the host changes. */
		fun mock(host: String) = PsnServiceEndpoints(
			authorizeUrl = "https://$host/2.0/oauth/authorize",
			tokenUrl = "https://$host/2.0/oauth/token",
			redirectUrl = "https://$host/remoteplay/redirect",
			remote = PsnRemoteEndpoints(
				tokenUrl = "https://$host/2.0/oauth/token",
				webApiBase = "https://$host/api/",
				pushLookupUrl = "https://$host/np/serveraddr?version=2.1&fields=keepAliveStatus&keepAliveStatusType=3"
			),
			mockHost = host
		)

		val current: PsnServiceEndpoints by lazy { psnMockHost()?.let(::mock) ?: Production }
	}
}
