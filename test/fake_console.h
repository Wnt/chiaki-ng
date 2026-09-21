// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_TEST_FAKE_CONSOLE_H
#define CHIAKI_TEST_FAKE_CONSOLE_H

#if defined(__linux__)

#include <chiaki/sock.h>

#include <stdint.h>

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
	uint32_t tag_client; // the client's takion tag, learnt from its INIT
} FakeConsole;

void fake_console_open(FakeConsole *console);
/** Answer INIT with INIT_ACK and COOKIE with COOKIE_ACK. */
void fake_console_handshake(FakeConsole *console);
/** Receive one datagram from the client, returning its size. */
int fake_console_recv(FakeConsole *console);
/** The console goes away; the client socket stays open, as a caller-owned socket does. */
void fake_console_close(FakeConsole *console);

/**
 * PLE-501: what fake_console_serve_senkusha() saw.
 */
typedef struct fake_console_senkusha_stats_t
{
	unsigned int mtu_in_probes; // MTU commands received
	unsigned int mtu_in_answered; // of those, answered (mtu_req <= mtu_ceiling)
	unsigned int mtu_out_pings; // Senkusha pings larger than the RTT test's
	bool disconnected; // Senkusha's DISCONNECT arrived
} FakeConsoleSenkushaStats;

/**
 * PLE-501: after fake_console_handshake(), play the console's side of a whole
 * Senkusha run until the client sends DISCONNECT: ack every data message, answer
 * the protocol request and BIG, echo pings back as pongs, answer the MTU commands
 * and the client MTU command. The path carries datagrams up to mtu_ceiling bytes
 * (IP + UDP included) in both directions: an MTU command asking for more, or a ping
 * larger than that, goes unanswered, just as the real console's answer or the
 * client's ping would be lost with DF set. Every answer is sent after delay_ms, so
 * the path has an RTT Senkusha can measure and derive its MTU timeout from.
 *
 * Like the real console, it answers MTU command id N with one video packet whose
 * frame_index is N and whose packet_index is 0, every time.
 */
void fake_console_serve_senkusha(FakeConsole *console, uint32_t mtu_ceiling, unsigned int delay_ms, FakeConsoleSenkushaStats *stats);
void fake_console_fini(FakeConsole *console);

#endif

#endif // CHIAKI_TEST_FAKE_CONSOLE_H
