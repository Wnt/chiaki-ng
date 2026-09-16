# Network impairment testbed design

**Ticket:** PLE-190. **Scope:** topology, addressing, traffic selection,
cross-VLAN console selection, profiles, safety, and the Phase 4 verification
contract. PLE-191 owns the scripts, PLE-192 owns phone/ADB resilience, and
PLE-194 owns deployment. This ticket changes no runtime code and used neither the
phone nor the PS5.

Values written as `IMPAIR_*` are read from
`~/.config/pleikkari/impair.env`. The addresses below are the selected defaults;
Phase 0 must collision-check and claim them before deployment and record any
replacement values in that file and the runbook.

## Decision summary

- Use a **routed**, not NATed, impair subnet. The PS5 must see the S22's real
  address, and the UniFi route makes the return path traverse the same guest.
- Make the guest the L3 gateway and DHCP server for the L2-only `ps-impair`
  VLAN. Do not put a second gateway on that VLAN.
- Redirect ingress from each guest NIC to a different IFB. Each IFB has a
  two-band `prio`: PS5/S22 traffic enters the impaired band; all unmatched
  traffic, including ADB and guest SSH, stays in the clean default band.
- Do not relay UDP discovery. Bind a manual entry for `192.168.1.164` to the
  existing PS5-466 registration and use that entry for local impaired runs.
- Every stateful operation is fail-safe: arm rollback/watchdog first, make the
  change second, and disarm only after its success condition is observed.

## Topology and addressing

```text
                              main LAN 192.168.1.0/24

 CT950                      UniFi gateway                PS5-466
 192.168.1.114 ------------- 192.168.1.1 --------------- 192.168.1.164
                                  |                            ^
                                  | static route               |
                                  | 192.168.40.0/24 via        | routed, selected
                                  | 192.168.1.5                | traffic only
                                  v                            |
                         netem guest on pve-nvme --------------+
                         eth0: 192.168.1.5/24
                         eth1: 192.168.40.1/24
                                  |
                          VLAN 40 `ps-impair`
                          DHCP: .100-.199
                                  |
                             Galaxy S22
                         192.168.40.x/24 (DHCP)
                         gateway 192.168.40.1
```

| setting | selected default | owner / constraint |
|---|---:|---|
| `IMPAIR_VLAN_ID` | `40` | Phase 0 must confirm it is unused |
| `IMPAIR_SUBNET` | `192.168.40.0/24` | Must not overlap any routed lab network |
| `IMPAIR_GW` | `192.168.40.1` | Guest `eth1`; DHCP router and DNS forwarder |
| `IMPAIR_GUEST_LAN_IP` | `192.168.1.5` | Guest `eth0`; outside the Default DHCP pool and claimed by the impairment deployment |
| `PS5_IP` / peer | `192.168.1.164` | Existing PS5 reservation; never changed here |
| CT950 | `192.168.1.114` | Existing address; ADB control endpoint |
| phone | DHCP reservation preferred | Discover by paired ADB GUID; never key safety to an old IP |

The UniFi network is VLAN-only/third-party-gateway: the guest, not
`192.168.1.1`, owns `192.168.40.1` and serves DHCP. Enable IPv4 forwarding on
the guest. Add one UniFi static route for `IMPAIR_SUBNET` via
`IMPAIR_GUEST_LAN_IP`; do not masquerade on the guest. Preserve source
addresses and allow forwarding in both directions. Phase 4 must also confirm
that the UniFi gateway provides any required WAN NAT for the routed subnet;
that is independent of the PS5's local route.

Phase 1 is IPv4-only: do not advertise an IPv6 prefix or default route on
`ps-impair`. An unclassified IPv6 path would bypass both the IPv4 static route
and the PS5-address filters while making the apparent topology nondeterministic.

The resulting paths are symmetric through the impairment point:

```text
S22 -> guest eth1 -> guest eth0 -> PS5
PS5 -> UniFi static route -> guest eth0 -> guest eth1 -> S22

CT950 -> UniFi static route -> guest eth0 -> guest eth1 -> S22 (ADB, clean)
S22 -> guest eth1 -> guest eth0 -> CT950                         (ADB, clean)
```

NAT on the guest is rejected. It would make the PS5 see
`IMPAIR_GUEST_LAN_IP`, hide failures in the return route, make all impair-VLAN
clients indistinguishable to packet filters, and test a topology unlike a
normal routed mobile client. Routed mode makes source identity and asymmetric
routing errors observable.

## Traffic selection and `tc` tree

Shaping an interface's egress alone is insufficient: traffic enters the guest
on a different NIC in each direction. Redirect ingress from each NIC to its own
IFB, then shape the IFB's egress. Redirect all IPv4, not just PS5 traffic, so
the same explicit classifier and clean default apply to every packet.

```text
eth1 (`ps-impair`) ingress
  flower: IPv4 -> mirred redirect to ifb-impair-rx
  ifb-impair-rx root 1: prio, 2 bands, every priomap entry -> band 1
    1:1 clean (default; no netem)
    1:2 impaired
      flower: src_ip $PHONE_IP/32 dst_ip 192.168.1.164/32 -> 1:2
      [optional 20: tbf rate PROFILE_RATE]
        30: netem PROFILE_NETEM

eth0 (main LAN) ingress
  flower: IPv4 -> mirred redirect to ifb-lan-rx
  ifb-lan-rx root 1: prio, 2 bands, every priomap entry -> band 1
    1:1 clean (default; no netem)
    1:2 impaired
      flower: src_ip 192.168.1.164/32 dst_ip $PHONE_IP/32 -> 1:2
      [optional 20: tbf rate PROFILE_RATE]
        30: netem PROFILE_NETEM
```

`prio` must set all 16 `priomap` values to band 1; relying on its stock mapping
would let an unrelated packet's priority select band 2. A flower filter is
preferred because the two IPv4 keys are legible in `tc -s`; an equivalent
`u32` match is acceptable. Both source and destination are required. Matching
only the PS5 address would also impair PS5 traffic to a future second client,
while matching the whole impair subnet would make the test client's unrelated
traffic non-clean.

For Phase 4's phone-free client, pass that client's address as `$PHONE_IP`.
For a rate-limited profile, put `tbf` above `netem` in the impaired band; for
an unlimited profile, attach `netem` directly to `1:2`. The `clean` profile
removes both physical ingress qdiscs and both IFB roots rather than leaving an
empty approximation behind. Apply and clear are idempotent.

This classification leaves ADB clean. Packets between `192.168.1.114` and the
phone match neither PS5 tuple, although both directions traverse the guest.
Guest SSH, DHCP, DNS, mDNS, and watchdog control also use band 1. Firewall rules
must not accidentally turn this QoS distinction into an allow-list: forwarding
policy and impairment classification are separate concerns.

## Discovery, registration, and wake

PS5 discovery uses broadcast UDP port 9302
(`CHIAKI_DISCOVERY_PORT_PS5` in `lib/include/chiaki/discovery.h`). A router does
not forward that broadcast, and this design deliberately adds no UDP helper or
broadcast relay. Relaying discovery would add another stateful lab component
without helping the stream path.

The fork already has the required direct-address path:

1. `RegisteredHost` persists the PS5's MAC, target, registration key, and RP
   key in Room.
2. `EditManualConsoleActivity` accepts an address and lets the user select an
   existing registered console; `ManualHost.registeredHost` stores that foreign
   key.
3. `MainViewModel.displayHosts` joins manual and registered rows into a
   `ManualDisplayHost` even when discovery returns nothing.
4. `MainActivity.hostTriggered` passes `ManualDisplayHost.host` and the existing
   registration keys directly to `StreamActivity`. It invokes registration
   only when the manual entry has no registered host.

`RegistActivity` is therefore not part of the normal VLAN-move flow. It can
register an unregistered manual entry without broadcast, but doing so requires
a console PIN and violates the no-human-re-registration constraint.

The PLE-26/PLE-50 PSN console list also works without LAN broadcast, but it is
feature-gated and its connect action uses the PSN remote control plane with an
empty local host, not the fixed local address. It is useful recovery evidence,
not the selected path for a controlled S22-to-PS5 LAN impairment run.

**Phase 5 conclusion:** no fork setting or app change is needed. Phase 5 must
ensure there is a manual `192.168.1.164` entry associated with the already
registered PS5-466 row, verify that tapping it reaches the direct-connect path
with discovery off, and preserve both rows across all tooling. It must not
create a duplicate registration or replace app data. If that source-read
conclusion fails in an actual smoke check, Phase 5 owns the smallest gated
fallback; current behavior remains the default.

`ps5-wake.sh` runs on CT950, which remains on the PS5's main LAN. Its packets do
not traverse the guest and are unaffected by every profile.

## Profiles and parameter semantics

The latency labels are **added round-trip budgets**, because the public
measurements and the Phase 4 ping check are RTT measurements. PLE-191 must split
delay and jitter evenly between the two IFBs. Loss and reorder are
**per-direction packet probabilities**, because the video and controller paths
are independent one-way streams. Consequently, an ICMP echo under per-leg loss
`p` has expected round-trip loss `1 - (1 - p)^2`; use one-way UDP and `tc -s`
counters when validating the configured percentage.

| profile | target behavior | each IFB / direction |
|---|---|---|
| `clean` | no added impairment | remove impairment and rate qdiscs |
| `5g` | about 20 ms RTT, ±5 ms jitter, 0.3% loss | `delay 10ms 2.5ms distribution normal`, `loss 0.3%`; unlimited rate |
| `4g` | about 45 ms RTT, ±15 ms jitter, 1% loss, 0.5% reorder, 30 Mbit/s | `delay 22.5ms 7.5ms distribution normal`, `loss 1%`, `reorder 0.5%`, `tbf 30mbit` |
| `wifi-slow` | about 10 ms RTT, ±25 ms jitter, 2% loss, 2% reorder, 20 Mbit/s, bursty | `delay 5ms 12.5ms 25% distribution normal`, `loss 2% 25%`, `reorder 2% 25%`, `tbf 20mbit`; the correlations create runs, not IID events |
| `blip-200ms` | each one-way stream stalls 200 ms every 20 s | normally clean; atomically replace both legs with `delay 200ms` for a 200 ms window every 20 s, then restore clean |
| `loss-2` | 2% loss only | `loss 2%`; no delay, reorder, or rate cap |

The exact `tc` syntax is owned and unit-tested by PLE-191. Jitter uses netem's
explicit `normal` distribution; apply must fail rather than silently substitute
another distribution if the installed `iproute2` lacks it. Record the live
choice in `status`. For `wifi-slow`, the third delay/loss/reorder number is the
`netem` correlation percentage. Correlation is intentionally modest and fixed
in the profile table; Phase 7 may use `custom` for harsher burst experiments
without silently changing the named baseline.

The 5G center is grounded in the FCC's published Ookla aggregation: Finland's
mean 5G latency was 19.0, 20.5, and 20.7 ms in 2021–2023
([FCC 24-136, fig. III.C.7](https://docs.fcc.gov/public/attachments/FCC-24-136A10_Rcd.pdf)).
For 4G, public user measurements put UK operators at 37.9–48.7 ms
([OpenSignal UK, April 2019](https://insights.opensignal.com/reports/2019/04/uk/mobile-network-experience)),
and the FCC reports 2019 North American 4G means of 34.1–50.0 ms
([FCC 20-188, fig. G-20](https://docs.fcc.gov/public/attachments/FCC-20-188A8_Rcd.pdf)).
Thus 20 ms and 45 ms are representative scenario centers, not claims about the
lab's carrier. The jitter, loss, reorder, cap, and burst values are deliberate
stress-test parameters, not values inferred from those latency studies.

## Safety contract

No step may require a human to pair ADB again or register the PS5 again.

1. Before a VLAN or route mutation, confirm the current ADB connection and
   snapshot the exact UniFi objects that will change. Do not uninstall/clear
   `com.metallic.chiaki`, delete its Room rows, run `adb pair`, or change the
   phone's wireless-debugging identity.
2. Arm **independent** two-minute rollback jobs for the phone VLAN assignment
   and static route before changing either. Rollback restores the captured
   values; it must not merely assume the defaults in this note.
3. Make the route reachable first and move the phone second. From CT950 run
   `scripts/dev/phone.sh connect`, which resolves the paired GUID rather than
   trusting the old address.
4. Cancel both rollback jobs only if that command succeeds within 120 seconds
   and the connected serial/GUID is the expected S22. Timeout, worker death, or
   an ambiguous device leaves the rollback armed.
5. Every non-clean `impair.sh apply` requires a TTL. The guest arms its clear
   watchdog before installing qdiscs and cancels/replaces it only after a
   successful clear or later apply. On expiry it removes the ingress redirects,
   IFB qdiscs, and the blip loop. `clean` and `clear` do the same immediately.
6. Control traffic must remain in the clean band. If the guest cannot be
   reached, do not extend an impairment TTL and do not attempt another VLAN
   move; let both safety mechanisms expire.

Success of `phone.sh connect` proves only that ADB survived the move. It does
not authorize deleting the rollback until the route and VLAN status read back
correctly as well. A later stream failure is handled by `clear` and normal app
reconnect, never by re-pairing or re-registering.

## Phase 4 verification plan (no phone)

Use a second minimal guest in `ps-impair`, or a CT950 VLAN sub-interface only
if that does not disturb CT950's management route. Give it a fixed address and
pass that address as `--phone`; never move the S22 in Phase 4.

1. Record routes, `ip rule`, forwarding/firewall state, interface offload
   state, and `tc -s -d` for both physical NICs and IFBs. Confirm the test client
   reaches the PS5 and CT950 under `clean`.
2. Take clean baselines: at least 1000 timestamped pings each to
   `192.168.1.164` and `192.168.1.114`, plus bidirectional `iperf3` between the
   impair client and CT950. Save raw output, not only summaries.
3. Apply `4g --peer 192.168.1.164 --phone $TEST_IP --ttl 10m`. In parallel,
   repeat both pings and the CT950 `iperf3` test. PS5 median RTT should increase
   by about 45 ms; CT950 RTT and throughput should stay within the clean-run
   noise. ICMP loss may approach 1.99% because 1% is applied independently to
   request and reply.
4. Show `tc -s` counter deltas: the PS5 tuple increments band `1:2` and both
   netem instances; the CT950 tuple increments only band `1:1`. Capture a short
   `tcpdump` on both guest NICs to prove that the test client's source address
   is preserved and replies use the guest route.
5. To measure one-way loss/reorder/rate precisely, temporarily use an
   `iperf3` server on the main LAN as `--peer`, run UDP in each direction, and
   compare with the profile values. Reapply the literal PS5 peer afterward and
   prove its classifier counters with ping. The PS5 is not assumed to run an
   `iperf3` server.
6. Run `clear`; verify the qdiscs/IFBs and blip loop are gone and both ping
   distributions return to baseline. Separately apply `4g --ttl 1m`, make no
   clear call, and prove the watchdog reaches the same clean state.
7. Repeat the classifier/counter check for every named profile. For
   `blip-200ms`, retain timestamped ping output long enough to show at least
   three approximately 20-second intervals.

This proves PS5-bound impairment and the clean **ADB-equivalent** CT950 path.
Actual Wi-Fi ADB cleanliness, direct manual-host streaming, and preservation of
the phone's registration remain Phase 5/7 device checks and must not be claimed
from the surrogate client.
