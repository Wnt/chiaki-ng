// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "fake_console.h"

#if defined(__linux__)

#include <munit.h>

#include <chiaki/common.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#define FAKE_CONSOLE_TAG_REMOTE 0x2c25ada

void fake_console_open(FakeConsole *console)
{
	console->sock = socket(AF_INET, SOCK_DGRAM, 0);
	munit_assert_int(console->sock, >=, 0);
	struct timeval tv = { .tv_sec = 5, .tv_usec = 0 }; // guards against a hang; no assertion depends on it
	munit_assert_int(setsockopt(console->sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)), ==, 0);
	struct sockaddr_in console_addr = { 0 };
	console_addr.sin_family = AF_INET;
	console_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	munit_assert_int(bind(console->sock, (struct sockaddr *)&console_addr, sizeof(console_addr)), ==, 0);
	socklen_t console_addr_len = sizeof(console_addr);
	munit_assert_int(getsockname(console->sock, (struct sockaddr *)&console_addr, &console_addr_len), ==, 0);

	console->client_sock = socket(AF_INET, SOCK_DGRAM, 0);
	munit_assert_int(console->client_sock, >=, 0);
	munit_assert_int(connect(console->client_sock, (struct sockaddr *)&console_addr, sizeof(console_addr)), ==, 0);
	struct sockaddr_in client_addr;
	socklen_t client_addr_len = sizeof(client_addr);
	munit_assert_int(getsockname(console->client_sock, (struct sockaddr *)&client_addr, &client_addr_len), ==, 0);
	munit_assert_int(connect(console->sock, (struct sockaddr *)&client_addr, sizeof(client_addr)), ==, 0);
}

static void fake_console_write_header(uint8_t *buf, uint32_t tag, uint8_t chunk_type, size_t payload_size)
{
	buf[0] = 0; // TAKION_PACKET_TYPE_CONTROL
	memset(buf + 1, 0, 0x10);
	*((chiaki_unaligned_uint32_t *)(buf + 1)) = htonl(tag);
	buf[1 + 0xc] = chunk_type;
	*((chiaki_unaligned_uint16_t *)(buf + 1 + 0xe)) = htons((uint16_t)(payload_size + 4));
}

void fake_console_handshake(FakeConsole *console)
{
	uint8_t buf[1500];
	ssize_t r = recv(console->sock, buf, sizeof(buf), 0);
	munit_assert_int((int)r, ==, 1 + 0x10 + 0x10);
	munit_assert_uint8(buf[1 + 0xc], ==, 1); // INIT
	uint32_t tag_local = ntohl(*((chiaki_unaligned_uint32_t *)(buf + 1 + 0x10)));

	uint8_t init_ack[1 + 0x10 + 0x10 + 0x20] = { 0 };
	fake_console_write_header(init_ack, tag_local, 2, 0x10 + 0x20);
	uint8_t *pl = init_ack + 1 + 0x10;
	*((chiaki_unaligned_uint32_t *)(pl + 0)) = htonl(FAKE_CONSOLE_TAG_REMOTE);
	*((chiaki_unaligned_uint32_t *)(pl + 4)) = htonl(0x19000);
	*((chiaki_unaligned_uint16_t *)(pl + 8)) = htons(0x64);
	*((chiaki_unaligned_uint16_t *)(pl + 0xa)) = htons(0x64);
	*((chiaki_unaligned_uint32_t *)(pl + 0xc)) = htonl(FAKE_CONSOLE_TAG_REMOTE);
	munit_assert_int((int)send(console->sock, init_ack, sizeof(init_ack), 0), ==, (int)sizeof(init_ack));

	r = recv(console->sock, buf, sizeof(buf), 0);
	munit_assert_int((int)r, ==, 1 + 0x10 + 0x20);
	munit_assert_uint8(buf[1 + 0xc], ==, 0xa); // COOKIE

	uint8_t cookie_ack[1 + 0x10];
	fake_console_write_header(cookie_ack, tag_local, 0xb, 0);
	munit_assert_int((int)send(console->sock, cookie_ack, sizeof(cookie_ack), 0), ==, (int)sizeof(cookie_ack));
}

int fake_console_recv(FakeConsole *console)
{
	uint8_t buf[1500];
	return (int)recv(console->sock, buf, sizeof(buf), 0);
}

void fake_console_close(FakeConsole *console)
{
	if(console->sock >= 0)
		close(console->sock);
	console->sock = -1;
}

void fake_console_fini(FakeConsole *console)
{
	fake_console_close(console);
	close(console->client_sock);
}

#endif
