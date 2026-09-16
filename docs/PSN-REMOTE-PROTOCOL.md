# PSN remote registration and connection protocol

Status: proposed Step 1 design for PLE-26; Kotlin implementation must wait for
review and agreement on the JNI boundary below. The map is based on
`lib/src/remote/holepunch.c`, `lib/src/remote/stun.h`,
`gui/src/qmlbackend.cpp`, and `gui/src/streamsession.cpp` as of 2026-09-16.

This is an implementation map, not a description of a public Sony API. The
endpoints and schemas are private and can change without notice. Recorded test
fixtures must replace access tokens, refresh tokens, account IDs, online IDs,
console DUIDs, session IDs, IP addresses, and push context IDs with stable test
values before they are committed.

## Decision

Android owns the complete PSN control plane in Kotlin:

- OAuth token refresh and encrypted refresh-token storage;
- device discovery;
- PSN session creation, console command/wake-up, session messages, and deletion;
- the push-notification WebSocket;
- STUN, candidate construction, candidate selection, and UDP hole punching; and
- the lifecycle/state machine exposed to the UI as a `StateFlow`.

The C library keeps only the existing Remote Play data plane: RUDP, remote
registration and its cryptography, the control connection, Senkusha, Takion,
and the streaming session. Android must not call any `chiaki_holepunch_*`
entry point. `holepunch.c` remains available to desktop clients, but a later
implementation change should exclude it, json-c, and miniupnpc from the
Android target. libcurl is also used by the library's independent AIA
certificate-chain recovery code, so removing the Android hole-puncher alone
does not prove that libcurl can be removed from the whole target.

The boundary is a **connected UDP socket FD**, not an address and port. A
freshly connected socket carries NAT state that cannot be recreated by C from
an address. On Android 24+, Kotlin can obtain a duplicate of a `DatagramSocket`
FD with `ParcelFileDescriptor.fromDatagramSocket()` (API 14), transfer that
duplicate with `detachFd()` (API 12), and keep or close the Java socket as
required. JNI takes ownership
of a successfully submitted detached FD and closes it with the native session.
It leaves ownership with Kotlin on a failed call. Every native method must
document this success/failure rule.

There are two staged transfers:

1. Kotlin punches and connects the control socket, then creates the native
   session with the control FD, PSN account ID, selected peer address/port, and
   remote-registration material (`data1`, `data2`, decoded `customData1`, and
   the client's local address).
2. After RUDP registration, the session request, and control startup, C emits
   `REMOTE_DATA_SOCKET_NEEDED`. Kotlin performs the second PSN OFFER/ACCEPT and
   candidate exchange, then calls `sessionSetRemoteDataSocket(fd)`. C wakes its
   waiting session thread and continues with Senkusha and the stream.

The second stage is required. The current C flow does not ask for the data
socket until `lib/src/session.c` has started control and received (or fallen
back to) the Remote Play session ID. Trying to punch both sockets before native
session startup changes the wire ordering and has not been shown to work.

### Existing C handoff points

The desktop path currently crosses from control-plane work into data-plane work
at these exact points:

1. `StreamSession::ConnectPsnConnection()` creates the PSN session, creates the
   first offer, starts/wakes the console, and punches the control socket.
2. `QmlBackend::checkPsnConnection()` starts `StreamSession` only after that
   control punch succeeds.
3. `session_thread_func()` gets the control socket with
   `chiaki_get_holepunch_sock(...CTRL)` and gives it to `chiaki_rudp_init()`.
4. The same thread runs `chiaki_regist_*` over RUDP. It derives registration
   cryptography from `data1`, `data2`, and `customData1`; the successful callback
   copies the returned `rp_regist_key` and `rp_key` into the session. A
   registration-only request stops here after publishing the registered host.
5. A streaming request continues with the ordinary session request and control
   startup. Only then does it create/punch the second offer, obtain the data
   socket, and pass that socket to `chiaki_senkusha_run()` and finally
   `chiaki_stream_connection_run()`.

These are the seams the neutral remote-connection structure and staged JNI
setter replace. Kotlin does not need to reproduce RUDP, registration crypto,
control, Senkusha, or Takion.

## Authentication

The authorization-code sign-in and refresh token are established by PLE-25.
The control plane needs an access token with these scopes:

```text
psn:clientapp
referenceDataService:countryConfig.read
pushNotification:webSocket.desktop.connect
sessionManager:remotePlaySession.system.update
```

The initial authorization URL also supplies a client DUID of the form
`0000000700410080` plus 16 random bytes encoded as lowercase hex. Generate it
once per authorization attempt. Token refresh does not send a DUID.

Refresh before an operation when the access-token expiry is less than 60
seconds away:

```http
POST https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token
Authorization: Basic base64(remote-play-client-id:remote-play-client-secret)
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&
refresh_token=<percent-encoded refresh token>&
scope=<percent-encoded scopes above>&
redirect_uri=https%3A%2F%2Fremoteplay.dl.playstation.net%2Fremoteplay%2Fredirect
```

The JSON response supplies `access_token`, `refresh_token`, and `expires_in`.
Atomically replace the stored refresh token when Sony rotates it. Keep the
refresh token in the existing Keystore-backed `PsnCredentialStore`; the access
token and calculated expiry may remain in memory. A 401/403 invalidates the
access token and permits one serialized refresh-and-retry. A second failure is
reported as reauthentication required; requests must not form an infinite
refresh loop.

All PSN HTTP requests below use `Authorization: Bearer <access token>` and a
10-second call timeout unless noted otherwise.

## HTTP and WebSocket map

### Device list

```http
GET https://web.np.playstation.com/api/cloudAssistedNavigation/v2/users/me/clients?platform=PS5&includeFields=device&limit=10&offset=0
Accept-Language: jp
```

Timeout: 5 seconds. The Qt UI retries once (`PSN_DEVICES_TRIES = 2`). Relevant
response shape:

```json
{
  "clients": [
    {
      "duid": "<64 lowercase hex characters>",
      "device": {
        "name": "<console name>",
        "enabledFeatures": ["remotePlay"]
      }
    }
  ]
}
```

Only clients containing `remotePlay` are displayed. The current source only
lists PS5 devices. Desktop synthesizes a `Main PS4 Console` row when a PS4 is
already registered; that is not PSN discovery and is out of the first Android
implementation.

### Push server lookup and WebSocket

```http
GET https://mobile-pushcl.np.communication.playstation.net/np/serveraddr?version=2.1&fields=keepAliveStatus&keepAliveStatusType=3
```

Timeout: 10 seconds. Read the string field `fqdn`, then connect to:

```text
wss://<fqdn>/np/pushNotification
```

WebSocket request headers:

```text
Authorization: Bearer <access token>
Sec-WebSocket-Protocol: np-pushpacket
User-Agent: WebSocket++/0.8.2
X-PSN-APP-TYPE: REMOTE_PLAY
X-PSN-APP-VER: RemotePlay/1.0
X-PSN-KEEP-ALIVE-STATUS-TYPE: 3
X-PSN-OS-VER: Windows/10.0
X-PSN-PROTOCOL-VERSION: 2.1
X-PSN-RECONNECTION: false
```

The existing implementation opens the WebSocket before creating a session so
that it cannot miss creation/member notifications. It sends a ping every five
seconds and treats five seconds without a pong as failure. OkHttp handles ping
frames when `pingInterval(5.seconds)` is configured; still surface `onFailure`
and `onClosed` into the state machine. The connection deadline is 30 seconds.

Notifications are JSON objects keyed by `dataType`. The control plane consumes:

| `dataType` | Relevant fields / meaning |
| --- | --- |
| `psn:sessionManager:sys:remotePlaySession:created` | `/to/onlineId`; confirms the created session |
| `psn:sessionManager:sys:rps:members:created` | `/body/data/members/0/deviceUniqueId`; first confirms this client member, later the requested console member |
| `psn:sessionManager:sys:rps:customData1:updated` | `/body/data/customData1`; console registration material |
| `psn:sessionManager:sys:rps:sessionMessage:created` | `/body/data/sessionMessage/payload`; OFFER/RESULT/ACCEPT/TERMINATE signaling |
| `psn:sessionManager:sys:rps:members:deleted` | teardown acknowledgement |
| `psn:sessionManager:sys:remotePlaySession:deleted` | teardown acknowledgement; ignore if its `sessionId` is not ours |

Queue notifications in arrival order. Waiters must filter without discarding
unrelated notifications because member and custom-data notifications can arrive
in either order.

### Create and inspect a remote-play session

Generate a UUIDv4 `pushContextId`, open the WebSocket, then:

```http
POST https://web.np.playstation.com/api/sessionManager/v1/remotePlaySessions
Content-Type: application/json; charset=utf-8

{
  "remotePlaySessions": [
    {
      "members": [
        {
          "accountId": "me",
          "deviceUniqueId": "me",
          "platform": "me",
          "pushContexts": [{"pushContextId": "<uuid>"}]
        }
      ]
    }
  ]
}
```

Read `/remotePlaySessions/0/sessionId` (a 36-character UUID) and
`/remotePlaySessions/0/members/0/accountId` (Sony has returned either a JSON
string or number). The create request itself is also the client's join: there
is no separate client-join endpoint in `holepunch.c`. Wait up to 30 seconds
for both the session-created and client-member-created notifications.

The source sometimes performs a diagnostic/session synchronization GET after
notifications:

```http
GET https://web.np.playstation.com/api/sessionManager/v1/remotePlaySessions?view=v1.0
X-PSN-SESSION-MANAGER-SESSION-IDS: <session UUID>
```

It also uses the same URL without `?view=v1.0`. Responses are only logged and
do not drive state, so Kotlin need not make these GETs in production. They may
be useful behind debug logging while fixtures are being captured.

### Start or wake the PS5

Generate random 16-byte `data1` and `data2`, encoded as standard padded Base64.
For the chosen PS5 DUID:

```http
POST https://web.np.playstation.com/api/cloudAssistedNavigation/v2/users/me/commands
Content-Type: application/json; charset=utf-8
User-Agent: RpNetHttpUtilImpl

{
  "commandDetail": {
    "commandType": "remotePlay",
    "duid": "<64-character console DUID>",
    "messageDestination": "SQS",
    "parameters": {
      "initialParams": "{\"accountId\":<numeric account id>,\"roomId\":0,\"sessionId\":\"<uuid>\",\"clientType\":\"Windows\",\"data1\":\"<base64>\",\"data2\":\"<base64>\"}"
    },
    "platform": "PS5"
  }
}
```

`initialParams` is a JSON string containing JSON, not a nested object. This
command both wakes/starts remote play and causes the console to join the PSN
session. Wait up to 30 seconds for both:

- `members:created` whose 32-byte DUID equals the selected console; and
- `customData1:updated`.

`customData1` is unusually encoded: Base64-decode its 32-character outer value,
then Base64-decode the resulting bytes again. The second result is 16 to 20
bytes; retain the first 16 bytes for remote registration and reject shorter or
more than four trailing bytes.

For completeness, PS4 first reads
`https://asm.np.community.playstation.net/asm/v1/apps/me/baseUrls/userProfile`,
then posts `data1`, `data2`, `roomId`, `protocolVer`, and `sessionId` to the
returned base URL at
`/v1/users/<onlineId>/remoteConsole/wakeUp?platform=PS4`. Android should not
claim PS4 support until this path has fixtures and hardware validation.

### Send signaling messages

```http
POST https://web.np.playstation.com/api/sessionManager/v1/remotePlaySessions/<session UUID>/sessionMessage
Content-Type: application/json; charset=utf-8

{
  "channel": "remote_play:1",
  "payload": "ver=1.0, type=text, body=<JSON encoded as a string>",
  "to": [
    {
      "accountId": "<numeric account id as a string>",
      "deviceUniqueId": "<console DUID>",
      "platform": "PS5"
    }
  ]
}
```

The body carried after `body=` has this logical schema:

```json
{
  "action": "OFFER | RESULT | ACCEPT | TERMINATE",
  "reqId": 1,
  "error": 0,
  "connRequest": {
    "sid": 1234,
    "peerSid": 5678,
    "skey": "<16 bytes, base64>",
    "natType": 2,
    "candidate": [
      {
        "type": "LOCAL | STATIC | STUN | DERIVED",
        "addr": "192.0.2.1",
        "mappedAddr": "0.0.0.0",
        "port": 50000,
        "mappedPort": 0
      }
    ],
    "defaultRouteMacAddr": "",
    "localPeerAddr": {
      "accountId": "<numeric account id>",
      "platform": "REMOTE_PLAY"
    },
    "localHashedId": "<20 bytes, base64>"
  }
}
```

`RESULT` acknowledgements carry an empty `connRequest`. The official client
can emit invalid JSON as `"localPeerAddr":,`; normalize that exact token to
`"localPeerAddr":{}` before decoding. Do not apply a general-purpose JSON
repair. `reqId` begins at 1 and increments for each local OFFER or ACCEPT.
`RESULT` must echo the request ID being acknowledged. A `TERMINATE` aborts the
operation.

For each socket (control first, data later), the signaling order is:

1. receive the console's OFFER and send RESULT with its `reqId`;
2. send our OFFER and wait for a matching RESULT;
3. probe the console candidates and select/connect a working UDP socket;
4. send ACCEPT containing the selected candidate;
5. receive console ACCEPT and send its matching RESULT; and
6. answer any final UDP probe packets for one second.

Each OFFER/RESULT/ACCEPT wait is bounded by 30 seconds. Extra console OFFERs
that arrive after the first control OFFER or after the data OFFER are
acknowledged to prevent console retries but do not restart negotiation.

### Delete/leave

```http
DELETE https://web.np.playstation.com/api/sessionManager/v1/remotePlaySessions/<session UUID>/members/me
Content-Type: application/json; charset=utf-8
```

This removes the client member; the service may subsequently delete the whole
session. Wait at most three seconds for either the member-deleted or matching
session-deleted notification, then close the WebSocket and local sockets even
if the acknowledgement was lost. Teardown is idempotent and runs from
`NonCancellable` cleanup so activity destruction does not leak a PSN session.

## STUN and candidate exchange

Use a single IPv4 `DatagramChannel` bound to wildcard address and an ephemeral
port for each control/data round. The current C reference creates an IPv6
socket but closes it before candidate exchange because `ENABLE_IPV6` is false;
IPv6 is therefore not part of the first Android implementation.

An RFC 5389 binding request is exactly 20 bytes here:

- type `0x0001`, length `0`, magic cookie `0x2112a442` in network order;
- 12 cryptographically random transaction-ID bytes.

Validate response type `0x0101`, declared length, magic cookie, and transaction
ID. Accept `XOR-MAPPED-ADDRESS` (`0x0020`) and fall back to
`MAPPED-ADDRESS` (`0x0001`). The reference uses a five-second receive timeout.
Start with the static vetted list in `lib/src/remote/stun.h` (Moonlight first,
then Google hosts in randomized order). Do not download a mutable third-party
STUN server list during a connection.

Build the client OFFER from:

- `LOCAL`: the socket's LAN address and bound port;
- `STUN`: the mapped public address/port; and
- `STATIC`: the public address with the local port, matching the desktop wire
  format.

The local connection request uses a random 16-bit `sid`, random 20-byte
`localHashedId`, zeroed 16-byte `skey`, `peerSid` from the console OFFER,
`natType = 2`, an empty route MAC, and `REMOTE_PLAY` as the local platform.
Although `skey` is parsed and serialized by the control plane, the existing
client always sends zeroes and the native data plane does not consume it. It
therefore does not cross JNI.

Probe every console candidate with the same connected-socket handshake used by
`check_candidates()`:

- 88-byte request type `0x06000000` in network order;
- local hashed ID at offset `0x04`, console hashed ID at `0x24`;
- local and console SIDs at `0x44` and `0x46`; and
- a random five-byte request ID at `0x4b`.

Responses are 88 bytes with type `0x07000000` and the same five-byte request
ID. Respond symmetrically when the console sends a request. A packet received
from an unadvertised address can create one of at most three `DERIVED`
candidates. Send to all candidates, wait 500 ms, and retry up to 20 times
(about ten seconds); after the first response allow a final five-second
selection window. Connect the Java UDP socket to the selected source address.
The selected FD—not a newly opened socket—is handed to JNI.

The desktop code contains symmetric-NAT port prediction (up to 75 advertised
port guesses and 250 probing sockets) and optional UPnP. Implement the ordinary
LOCAL/STUN/STATIC path first, but model the result as an explicit
`UnsupportedNat` failure when mappings are random. Do not silently report a
generic timeout. Port prediction can then be added in Kotlin and tested as a
separate policy without changing JNI.

## State machine

The Kotlin `StateFlow` should expose immutable states and accept serialized
events in one coroutine scope:

```text
Idle
  -> RefreshingToken (only when expiry is near)
  -> ListingDevices -> Devices(list) -> Idle

Idle -> ResolvingPushServer -> OpeningWebSocket -> CreatingSession
  -> ClientJoined -> StartingConsole -> ConsoleJoined
  -> ControlSignaling -> ControlProbing -> ControlPunched
  -> NativeStarting
       -> Registered                         (registration-only terminal state)
       -> AwaitingDataSocket
  -> DataSignaling -> DataProbing -> DataPunched
  -> Streaming
  -> DeletingSession -> Idle

Any non-terminal state -> Failed(reason) -> DeletingSession -> Idle
Any non-terminal state -> Cancelling -> DeletingSession -> Idle
```

`CreatingSession` completes only after both creation notifications.
`StartingConsole` completes only after both console-join and custom-data
notifications. Both hole-punch rounds complete only after PSN ACCEPT/RESULT and
the 88-byte UDP handshake. Keep the PSN session and WebSocket alive for the
entire native registration/stream; deleting them immediately after punching is
not equivalent to the desktop behavior.

The Android feature must be behind a setting that defaults off. With it off,
device lists, startup, and the existing LAN registration/streaming paths remain
unchanged.

## Timeout policy

| Operation | Limit |
| --- | ---: |
| Device list | 5 s, one retry |
| Other HTTP request | 10 s |
| Push-server lookup | 10 s |
| WebSocket open | 30 s |
| WebSocket ping / expected pong | 5 s / 5 s |
| Session-created + client-joined notifications | 30 s total |
| Console-joined + custom-data notifications | 30 s total |
| Each signaling OFFER/RESULT/ACCEPT wait | 30 s |
| STUN server response | 5 s |
| Candidate retry interval / retries | 500 ms / 20 |
| Candidate final selection | 5 s |
| Final UDP request drain | 1 s after the last packet |
| Native remote registration | 10 s (current `session.c`) |
| Session deletion notification | 3 s, best effort |

Kotlin should apply one deadline to each multi-notification phase. The C code
restarts a 30-second wait for each notification, which can accidentally double
the intended duration; the Kotlin implementation should not copy that bug.

## Native/JNI contract

Add a control-plane-neutral structure to `session.h` (names illustrative until
reviewed):

```c
typedef struct chiaki_remote_connection_info_t {
    chiaki_socket_t ctrl_sock;       /* owned by session after successful init */
    char peer_addr[INET6_ADDRSTRLEN];
    uint16_t peer_ctrl_port;
    uint8_t data1[16];
    uint8_t data2[16];
    uint8_t custom_data1[16];
    char client_local_addr[INET6_ADDRSTRLEN];
} ChiakiRemoteConnectionInfo;

CHIAKI_EXPORT ChiakiErrorCode chiaki_session_set_remote_data_socket(
    ChiakiSession *session, chiaki_socket_t data_sock);
```

`ChiakiConnectInfo` should carry an optional `ChiakiRemoteConnectionInfo`
instead of making `ChiakiHolepunchSession` the marker for a remote connection.
`session.c` initializes `ChiakiRudp` from `ctrl_sock`, builds the existing
`ChiakiHolepunchRegistInfo`-equivalent values locally, and uses `peer_addr` and
`peer_ctrl_port` for the remote session HTTP Host header. After control startup
it emits `CHIAKI_EVENT_REMOTE_DATA_SOCKET_NEEDED` and waits on the existing
session condition for either `chiaki_session_set_remote_data_socket()` or stop.
The setter rejects a second socket, takes ownership on success, and signals the
condition. The existing session cleanup closes both owned sockets exactly once.

JNI mirrors this with two calls:

```text
sessionCreateRemote(..., ctrlFd, peerAddress, peerControlPort,
                    data1, data2, customData1, clientLocalAddress,
                    psnAccountId, registrationOnly)
sessionSetRemoteDataSocket(sessionPtr, dataFd)
```

All byte-array lengths and port/address validity are checked before native
allocation. JNI duplicates no PSN state and receives no OAuth token, WebSocket,
candidate list, `skey`, or session UUID. The native event callback maps
`CHIAKI_EVENT_REMOTE_DATA_SOCKET_NEEDED` to the Kotlin session object; the
callback itself does no network I/O.

### C entry points that stay in the Android data plane

- `chiaki_rudp_init`, `chiaki_rudp_fini`, and the existing `chiaki_rudp_*`
  send/receive helpers;
- `chiaki_regist_start`, `chiaki_regist_stop`, `chiaki_regist_fini`, and
  `chiaki_regist_request_payload_format`;
- `chiaki_session_init`, `chiaki_session_start`, `chiaki_session_stop`,
  `chiaki_session_join`, `chiaki_session_fini`, plus the new staged data-socket
  setter;
- the existing control, Senkusha, Takion, stream, RP-crypt, and registration
  functions reached by those entry points; and
- JNI session lifecycle and event methods, extended only with the remote
  creation arguments and data-socket setter.

No `chiaki_holepunch_*` entry point stays in Android. The desktop ABI can retain
the current functions and adapt its completed sockets into the same neutral
remote-connection structure later, avoiding a forced Qt migration in this
ticket.

## Test seams for the implementation PR

- Inject base URLs, an `OkHttpClient`, UUID/random sources, clock, STUN server
  list, and UDP transport; production constructors use the constants above.
- MockWebServer tests cover rotated-token refresh, PS5 device parsing, session
  create, command start, session-message POSTs, and delete.
- A WebSocket fixture replays session-created, member-created,
  custom-data-updated, console OFFER, matching RESULT, and ACCEPT messages.
- A fake UDP transport validates STUN parsing and the 88-byte request/response
  handshake without binding public interfaces.
- The end-to-end state-machine test asserts the exact progression through
  `ControlPunched`, native `AwaitingDataSocket`, and `DataPunched` before the
  terminal `Streaming`/`Punched` state.
- Cancellation tests assert one best-effort DELETE, WebSocket closure, and
  closure of every FD that was not successfully transferred to native code.

Real PSN behavior, NAT traversal, PIN-less registration, wake-up, and streaming
still require the linked PS5 and a second network. Captured fixtures prove
parsing and ordering only.
