// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include <chiaki/session.h>

#include "test_log.h"

// PLE-499: Senkusha's failure fallback used to hardcode MTU 1454 regardless of
// what the local path could carry. chiaki_session_mtu_fallback() derives it from
// the local interface MTU instead; these three cases are the acceptance criteria.

static MunitResult test_lan_interface_mtu_reproduces_1454(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	// No-regression requirement: a standard 1500-byte LAN interface must still
	// produce the pre-PLE-499 constant exactly.
	uint32_t mtu = chiaki_session_mtu_fallback(get_test_log(), 1500);
	munit_assert_uint32(mtu, ==, 1454);
	return MUNIT_OK;
}

static MunitResult test_tunnelled_interface_mtu_yields_smaller_value(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	// A ~1280-byte VPN tunnel must not get the LAN-sized 1454 fallback.
	uint32_t mtu = chiaki_session_mtu_fallback(get_test_log(), 1280);
	munit_assert_uint32(mtu, ==, 1234);
	munit_assert_uint32(mtu, <, 1454);
	return MUNIT_OK;
}

static MunitResult test_undeterminable_interface_mtu_falls_through_to_default(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	// Per acceptance criterion 2: keep today's 1454 rather than inventing a
	// smaller guess when the interface MTU cannot be determined.
	uint32_t mtu = chiaki_session_mtu_fallback(get_test_log(), -1);
	munit_assert_uint32(mtu, ==, 1454);

	mtu = chiaki_session_mtu_fallback(get_test_log(), 0);
	munit_assert_uint32(mtu, ==, 1454);
	return MUNIT_OK;
}

MunitTest tests_session_mtu_fallback[] = {
	{
		"/lan_interface_mtu_reproduces_1454",
		test_lan_interface_mtu_reproduces_1454,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/tunnelled_interface_mtu_yields_smaller_value",
		test_tunnelled_interface_mtu_yields_smaller_value,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{
		"/undeterminable_interface_mtu_falls_through_to_default",
		test_undeterminable_interface_mtu_falls_through_to_default,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL }
};
