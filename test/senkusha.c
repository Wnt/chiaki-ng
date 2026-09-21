// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <munit.h>

#include <chiaki/senkusha.h>
#include <chiaki/session.h>
#include <chiaki/thread.h>

#include <string.h>

#include "fake_console.h"
#include "test_log.h"

#if defined(__linux__)
typedef struct senkusha_run_ctx_t
{
	ChiakiSenkusha senkusha;
	chiaki_socket_t sock;
	uint32_t mtu_in;
	uint32_t mtu_out;
	uint64_t rtt_us;
	ChiakiErrorCode result;
} SenkushaRunCtx;

static void *senkusha_run_thread(void *user)
{
	SenkushaRunCtx *ctx = user;
	ctx->result = chiaki_senkusha_run(&ctx->senkusha, &ctx->mtu_in, &ctx->mtu_out, &ctx->rtt_us, &ctx->sock);
	return NULL;
}

// PLE-490: Senkusha only listened for a takion DISCONNECT while connecting. After
// that, a takion thread that had died (ECONNREFUSED in the S25 capture) left every
// later wait to run to its timeout, and the MTU search read those timeouts as lost
// probes and derived an MTU from them. Here the console completes the handshake,
// takes Senkusha's first message (the takion protocol request) and goes away; the
// send buffer's re-send of that unacked message draws the ICMP that ends the takion
// thread. Senkusha must end with CHIAKI_ERR_DISCONNECTED. Before PLE-490 it returned
// CHIAKI_ERR_TIMEOUT after the full EXPECT_TIMEOUT_MS: the outcome, not a duration,
// is what tells the two apart.
static MunitResult test_senkusha_ends_on_takion_disconnect(const MunitParameter params[], void *user)
{
	FakeConsole console;
	fake_console_open(&console);

	ChiakiSession session;
	memset(&session, 0, sizeof(session));
	session.log = get_test_log();

	SenkushaRunCtx ctx;
	memset(&ctx, 0, sizeof(ctx));
	ctx.sock = console.client_sock;
	ctx.result = CHIAKI_ERR_UNKNOWN;
	munit_assert_int(chiaki_senkusha_init(&ctx.senkusha, &session), ==, CHIAKI_ERR_SUCCESS);

	ChiakiThread thread;
	munit_assert_int(chiaki_thread_create(&thread, senkusha_run_thread, &ctx), ==, CHIAKI_ERR_SUCCESS);

	fake_console_handshake(&console);
	munit_assert_int(fake_console_recv(&console), >, 0); // the takion protocol request
	fake_console_close(&console);

	munit_assert_int(chiaki_thread_join(&thread, NULL), ==, CHIAKI_ERR_SUCCESS);
	munit_assert_int(ctx.result, ==, CHIAKI_ERR_DISCONNECTED);

	chiaki_senkusha_fini(&ctx.senkusha);
	fake_console_fini(&console);
	return MUNIT_OK;
}
#endif

MunitTest tests_senkusha[] = {
#if defined(__linux__)
	{
		"/ends_on_takion_disconnect",
		test_senkusha_ends_on_takion_disconnect,
		NULL,
		NULL,
		MUNIT_TEST_OPTION_NONE,
		NULL
	},
#endif
	{ NULL, NULL, NULL, NULL, MUNIT_TEST_OPTION_NONE, NULL }
};
