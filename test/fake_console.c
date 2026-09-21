// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "fake_console.h"

#if defined(__linux__)

#include <munit.h>

#include <chiaki/common.h>
#include <chiaki/seqnum.h>
#include <chiaki/takion.h>

#include <pb_decode.h>
#include <pb_encode.h>
#include <takion.pb.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#define FAKE_CONSOLE_TAG_REMOTE 0x2c25ada
#define FAKE_CONSOLE_HEADER_SIZE (1 + 0x10)
#define FAKE_CONSOLE_UDP_IP_ADD 0x1c // the IPv4 and UDP headers, as senkusha.c's MTU_UDP_PACKET_ADD
#define FAKE_CONSOLE_RTT_PING_SIZE 0x224 // senkusha_run_rtt_test()'s ping

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
	console->tag_client = tag_local;

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

static void fake_console_send(FakeConsole *console, const uint8_t *buf, size_t size)
{
	munit_assert_int((int)send(console->sock, buf, size, 0), ==, (int)size);
}

static void fake_console_send_data_ack(FakeConsole *console, uint32_t seq_num)
{
	uint8_t buf[FAKE_CONSOLE_HEADER_SIZE + 0xc] = { 0 };
	fake_console_write_header(buf, console->tag_client, 3, 0xc); // DATA_ACK
	uint8_t *pl = buf + FAKE_CONSOLE_HEADER_SIZE;
	*((chiaki_unaligned_uint32_t *)(pl + 0)) = htonl(seq_num);
	*((chiaki_unaligned_uint32_t *)(pl + 4)) = htonl(0x19000);
	fake_console_send(console, buf, sizeof(buf));
}

static bool fake_console_encode_empty_string(pb_ostream_t *stream, const pb_field_t *field, void *const *arg)
{
	(void)arg;
	return pb_encode_tag_for_field(stream, field) && pb_encode_string(stream, (const pb_byte_t *)"", 0);
}

static void fake_console_send_message(FakeConsole *console, uint32_t *seq_num, tkproto_TakionMessage *msg)
{
	uint8_t buf[FAKE_CONSOLE_HEADER_SIZE + 9 + 0x80] = { 0 };
	uint8_t *pl = buf + FAKE_CONSOLE_HEADER_SIZE;
	pb_ostream_t stream = pb_ostream_from_buffer(pl + 9, sizeof(buf) - FAKE_CONSOLE_HEADER_SIZE - 9);
	munit_assert_true(pb_encode(&stream, tkproto_TakionMessage_fields, msg));
	fake_console_write_header(buf, console->tag_client, 0, 9 + stream.bytes_written); // DATA
	buf[1 + 0xd] = 1; // chunk flags
	*((chiaki_unaligned_uint32_t *)(pl + 0)) = htonl((*seq_num)++);
	*((chiaki_unaligned_uint16_t *)(pl + 4)) = htons(1); // channel
	pl[8] = CHIAKI_TAKION_MESSAGE_DATA_TYPE_PROTOBUF;
	fake_console_send(console, buf, FAKE_CONSOLE_HEADER_SIZE + 9 + stream.bytes_written);
}

/** Answer MTU command id with one video packet of mtu_req bytes (IP and UDP included). */
static void fake_console_send_mtu_answer(FakeConsole *console, uint32_t id, uint32_t mtu_req)
{
	uint8_t buf[1500] = { 0 };
	size_t size = mtu_req - FAKE_CONSOLE_UDP_IP_ADD;
	munit_assert_size(size, <=, sizeof(buf));
	ChiakiTakionAVPacket packet = { 0 };
	packet.is_video = true;
	packet.packet_index = 0;
	packet.frame_index = (uint16_t)id;
	packet.unit_index = 0;
	packet.units_in_frame_total = 1;
	packet.codec = 0xff;
	size_t header_size;
	munit_assert_int(chiaki_takion_v7_av_packet_format_header(buf, sizeof(buf), &header_size, &packet), ==, CHIAKI_ERR_SUCCESS);
	munit_assert_size(header_size, <=, size);
	fake_console_send(console, buf, size);
}

void fake_console_serve_senkusha(FakeConsole *console, uint32_t mtu_ceiling, unsigned int delay_ms, FakeConsoleSenkushaStats *stats)
{
	memset(stats, 0, sizeof(*stats));
	uint32_t seq_num_local = FAKE_CONSOLE_TAG_REMOTE; // takion takes the remote tag as the initial seq num
	bool have_seq_num_remote = false;
	uint32_t seq_num_remote = 0;

	uint8_t buf[1500];
	while(true)
	{
		ssize_t r = recv(console->sock, buf, sizeof(buf), 0);
		if(r <= 0)
			return; // the client went quiet; the caller's assertions say what went wrong

		uint8_t base_type = buf[0] & 0xf;
		if(base_type == 2 || base_type == 3) // AV: a ping, answered with an identical pong
		{
			if(r != FAKE_CONSOLE_RTT_PING_SIZE)
				stats->mtu_out_pings++;
			if((uint32_t)r + FAKE_CONSOLE_UDP_IP_ADD > mtu_ceiling)
				continue;
			usleep(delay_ms * 1000);
			fake_console_send(console, buf, (size_t)r);
			continue;
		}

		if(base_type != 0 || r < FAKE_CONSOLE_HEADER_SIZE + 9 || buf[1 + 0xc] != 0) // only DATA needs an answer
			continue;

		uint8_t *pl = buf + FAKE_CONSOLE_HEADER_SIZE;
		uint32_t seq_num = ntohl(*((chiaki_unaligned_uint32_t *)pl));
		fake_console_send_data_ack(console, seq_num);
		if(have_seq_num_remote && !chiaki_seq_num_32_gt(seq_num, seq_num_remote))
			continue; // a re-send of a message already answered
		have_seq_num_remote = true;
		seq_num_remote = seq_num;

		tkproto_TakionMessage msg;
		memset(&msg, 0, sizeof(msg));
		pb_istream_t istream = pb_istream_from_buffer(pl + 9, (size_t)r - FAKE_CONSOLE_HEADER_SIZE - 9);
		munit_assert_true(pb_decode(&istream, tkproto_TakionMessage_fields, &msg));

		tkproto_TakionMessage answer;
		memset(&answer, 0, sizeof(answer));
		switch(msg.type)
		{
			case tkproto_TakionMessage_PayloadType_TAKIONPROTOCOLREQUEST:
				answer.type = tkproto_TakionMessage_PayloadType_TAKIONPROTOCOLREQUESTACK;
				answer.has_takion_protocol_request_ack = true;
				answer.takion_protocol_request_ack.has_takion_protocol_version = true;
				answer.takion_protocol_request_ack.takion_protocol_version = 9;
				fake_console_send_message(console, &seq_num_local, &answer);
				break;
			case tkproto_TakionMessage_PayloadType_BIG:
				answer.type = tkproto_TakionMessage_PayloadType_BANG;
				answer.has_bang_payload = true;
				answer.bang_payload.server_version = 9;
				answer.bang_payload.version_accepted = true;
				answer.bang_payload.session_key.funcs.encode = fake_console_encode_empty_string;
				fake_console_send_message(console, &seq_num_local, &answer);
				break;
			case tkproto_TakionMessage_PayloadType_SENKUSHA:
				munit_assert_true(msg.has_senkusha_payload);
				if(msg.senkusha_payload.command == tkproto_SenkushaPayload_Command_MTU_COMMAND)
				{
					munit_assert_true(msg.senkusha_payload.has_mtu_command);
					stats->mtu_in_probes++;
					if(msg.senkusha_payload.mtu_command.mtu_req > mtu_ceiling)
						break;
					stats->mtu_in_answered++;
					usleep(delay_ms * 1000);
					fake_console_send_mtu_answer(console, msg.senkusha_payload.mtu_command.id, msg.senkusha_payload.mtu_command.mtu_req);
				}
				else if(msg.senkusha_payload.command == tkproto_SenkushaPayload_Command_CLIENT_MTU_COMMAND
					&& msg.senkusha_payload.has_client_mtu_command
					&& msg.senkusha_payload.client_mtu_command.state)
				{
					answer.type = tkproto_TakionMessage_PayloadType_SENKUSHA;
					answer.has_senkusha_payload = true;
					answer.senkusha_payload.command = tkproto_SenkushaPayload_Command_CLIENT_MTU_COMMAND;
					answer.senkusha_payload.has_client_mtu_command = true;
					answer.senkusha_payload.client_mtu_command = msg.senkusha_payload.client_mtu_command;
					fake_console_send_message(console, &seq_num_local, &answer);
				}
				break; // ECHO_COMMAND and the final CLIENT_MTU_COMMAND need only the ack
			case tkproto_TakionMessage_PayloadType_DISCONNECT:
				stats->disconnected = true;
				return;
			default:
				break;
		}
	}
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
