// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_TEST_FAKE_CONSOLE_H
#define CHIAKI_TEST_FAKE_CONSOLE_H

#if defined(__linux__)

#include <chiaki/sock.h>

/**
 * PLE-490: a console on loopback, just enough of one to take a real ChiakiTakion
 * through its handshake. The takion under test runs its real thread; nothing on the
 * client side is mocked. Both sockets are connected to each other, so once the
 * console socket is closed the next datagram from the client draws an ICMP port
 * unreachable, which the client socket reports as ECONNREFUSED.
 */
typedef struct fake_console_t
{
	int sock;
	chiaki_socket_t client_sock;
} FakeConsole;

void fake_console_open(FakeConsole *console);
/** Answer INIT with INIT_ACK and COOKIE with COOKIE_ACK. */
void fake_console_handshake(FakeConsole *console);
/** Receive one datagram from the client, returning its size. */
int fake_console_recv(FakeConsole *console);
/** The console goes away; the client socket stays open, as a caller-owned socket does. */
void fake_console_close(FakeConsole *console);
void fake_console_fini(FakeConsole *console);

#endif

#endif // CHIAKI_TEST_FAKE_CONSOLE_H
