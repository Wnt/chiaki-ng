// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.remote

/** Release builds have no PSN mock: they always talk to Sony. The debug twin reads -PchiakiPsnMock. */
internal fun psnMockHost(): String? = null
