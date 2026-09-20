// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include "../android/app/src/main/cpp/video-decoder-codec-header.h"

static MunitResult test_start_codes(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	const uint8_t sps_4byte[] = { 0x00, 0x00, 0x00, 0x01, 0x67 };
	munit_assert_true(android_chiaki_sample_is_codec_header(false, sps_4byte, sizeof(sps_4byte)));

	const uint8_t sps_3byte[] = { 0x00, 0x00, 0x01, 0x67 };
	munit_assert_true(android_chiaki_sample_is_codec_header(false, sps_3byte, sizeof(sps_3byte)));

	return MUNIT_OK;
}

static MunitResult test_h264_types(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	const uint8_t sps[] = { 0x00, 0x00, 0x00, 0x01, 0x67 };
	munit_assert_true(android_chiaki_sample_is_codec_header(false, sps, sizeof(sps)));

	const uint8_t pps[] = { 0x00, 0x00, 0x00, 0x01, 0x68 };
	munit_assert_true(android_chiaki_sample_is_codec_header(false, pps, sizeof(pps)));

	const uint8_t slice[] = { 0x00, 0x00, 0x00, 0x01, 0x65 };
	munit_assert_false(android_chiaki_sample_is_codec_header(false, slice, sizeof(slice)));

	const uint8_t non_idr_slice[] = { 0x00, 0x00, 0x00, 0x01, 0x61 };
	munit_assert_false(android_chiaki_sample_is_codec_header(false, non_idr_slice, sizeof(non_idr_slice)));

	return MUNIT_OK;
}

static MunitResult test_h265_types(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	const uint8_t vps[] = { 0x00, 0x00, 0x00, 0x01, 32 << 1 };
	munit_assert_true(android_chiaki_sample_is_codec_header(true, vps, sizeof(vps)));

	const uint8_t sps[] = { 0x00, 0x00, 0x00, 0x01, 33 << 1 };
	munit_assert_true(android_chiaki_sample_is_codec_header(true, sps, sizeof(sps)));

	const uint8_t pps[] = { 0x00, 0x00, 0x00, 0x01, 34 << 1 };
	munit_assert_true(android_chiaki_sample_is_codec_header(true, pps, sizeof(pps)));

	const uint8_t trail_r[] = { 0x00, 0x00, 0x00, 0x01, 1 << 1 };
	munit_assert_false(android_chiaki_sample_is_codec_header(true, trail_r, sizeof(trail_r)));

	return MUNIT_OK;
}

static MunitResult test_h265_flag_changes_classification(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	/* NAL byte 0x67 is H.264 type 7 (SPS) but H.265 type 51 (not a parameter set). */
	const uint8_t buf[] = { 0x00, 0x00, 0x00, 0x01, 0x67 };
	munit_assert_true(android_chiaki_sample_is_codec_header(false, buf, sizeof(buf)));
	munit_assert_false(android_chiaki_sample_is_codec_header(true, buf, sizeof(buf)));

	return MUNIT_OK;
}

static MunitResult test_boundary_cases(const MunitParameter params[], void *user)
{
	(void)params;
	(void)user;

	/* buf_size below 3: no start code, of either length, can fit at all. */
	const uint8_t too_short_for_3[] = { 0x00, 0x00 };
	munit_assert_false(android_chiaki_sample_is_codec_header(false, too_short_for_3, sizeof(too_short_for_3)));

	/* buf_size below 4: the 4-byte start-code check must not run past the buffer. */
	const uint8_t too_short_for_4[] = { 0x00, 0x00, 0x00 };
	munit_assert_false(android_chiaki_sample_is_codec_header(false, too_short_for_4, sizeof(too_short_for_4)));

	/* No start code present at all. */
	const uint8_t no_start_code[] = { 0x67, 0x42, 0x00, 0x1e };
	munit_assert_false(android_chiaki_sample_is_codec_header(false, no_start_code, sizeof(no_start_code)));

	/* Buffer is exactly a 4-byte start code and nothing else: nal == buf_size. */
	const uint8_t only_4byte_start_code[] = { 0x00, 0x00, 0x00, 0x01 };
	munit_assert_false(android_chiaki_sample_is_codec_header(false, only_4byte_start_code, sizeof(only_4byte_start_code)));

	/* Buffer is exactly a 3-byte start code and nothing else: nal == buf_size. */
	const uint8_t only_3byte_start_code[] = { 0x00, 0x00, 0x01 };
	munit_assert_false(android_chiaki_sample_is_codec_header(false, only_3byte_start_code, sizeof(only_3byte_start_code)));

	return MUNIT_OK;
}

MunitTest tests_video_decoder_codec_header[] = {
	{ "/start_codes", test_start_codes, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
	{ "/h264_types", test_h264_types, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
	{ "/h265_types", test_h265_types, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
	{ "/h265_flag_changes_classification", test_h265_flag_changes_classification, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
	{ "/boundary_cases", test_boundary_cases, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL },
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL }
};
