// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import com.metallic.chiaki.BuildConfig

/** The PSN mock host a debug build was made for with -PchiakiPsnMock, or null for Sony. The release twin is always null. */
internal fun psnMockHost(): String? = BuildConfig.PSN_MOCK_HOST.ifEmpty { null }
