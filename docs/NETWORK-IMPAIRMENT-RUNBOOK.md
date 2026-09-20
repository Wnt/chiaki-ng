# Network impairment runbook

**The operating guide for the impairment tooling lives in the workspace, not
this fork.** The tooling itself is workspace-side
(`scripts/net/`: `impairctl.py`, `impair.sh`, `impair-vlan.sh`,
`impair_vlan.py`, `unifi_rest.py`, `host/pleikkari-impair-revert`, plus the
test suite) — [PLE-378](https://linear.app/pleikkari/issue/PLE-378) deleted
this fork's dead copy of it (`e73bfc1f`). A runbook here that names
workspace-only paths as if they belonged to this fork is exactly how a worker
was misled into rewriting 441 lines of that dead copy instead of touching the
real tooling ([PLE-373](https://linear.app/pleikkari/issue/PLE-373)). The
day-to-day commands, the profile table, the safety contract, and the test
invocation are documented at `scripts/net/README-IMPAIR-VLAN.md` in the
workspace — on this box, `/home/wnt/gta6/scripts/net/README-IMPAIR-VLAN.md`.
This fork does not, and should not, keep a second copy of that guide. The
original topology plan is the workspace's `docs/IMPAIRMENT-VLAN-PLAN.md`.

What follows is this fork's historical record of the initial deployment: how
the lab network was addressed and claimed (§0), and the phone-free
verification evidence from the first rollout (§3). It is **not** the
operating guide, and parts of it no longer match what is deployed — each
correction below is called out with what was checked and how, on
2026-09-20, read-only via `ssh -n lab 'pct exec 240 -- <cmd>'` (one hop, no
nesting) and locally on CT950. No impairment configuration was changed, no
profile was applied or cleared, and CT 240 was not stopped or started.

## 0. Access and addressing (verified 2026-09-16, historical)

Verified from CT950 on 2026-09-16. Use `ssh -n lab '<cmd>'` as the only door
to the Proxmox host; do not nest SSH. The host identifies itself as
`pve-nvme` and reports Proxmox `pve-manager/9.2.4/5e5ae681198514d4` on kernel
`7.0.14-4-pve`. Both `pct list` and `qm list` are readable.

The lab host has `vmbr0`, `vmbr-rn`, `vmbr-wi`, and the per-client `wibr*`
bridges. `vmbr0` is backed by `nic3`, has `bridge-vlan-aware yes`, and permits
VLAN IDs 2–4094. It can therefore carry the impairment VLAN without a host
bridge change. **Correction (2026-09-20):** the impairment VLAN did not end
up tagged on `vmbr0`. The deployed host bridge is a dedicated
`vmbr-impair` (see §1) — `ssh -n lab 'ip -br link show vmbr-impair'` and
`brctl show vmbr-impair` both confirm it holds only `nic0` and CT 240's
`veth240i1`, matching the workspace's
`scripts/net/README-IMPAIR-VLAN.md` design (a trunk dedicated to VLANs
40–49, kept off `vmbr0` on purpose so the main LAN has no representation on
that wire). This section's addressing history is otherwise unaffected.

The UniFi Integration API is readable using the local
`~/.config/unifi/config.env` configuration and an `X-API-KEY` loaded from
`UNIFI_TOKEN_FILE`. Never print or commit that key. `GET /sites` returns the
Default site. `GET /sites/{siteId}/networks` returns:

| Network | VLAN ID | Enabled | Management |
| --- | ---: | --- | --- |
| Default | 1 | yes | gateway |
| Greenhouse | 30 | yes | gateway |

PLE-228 resolved an address collision on 2026-09-16. The Default network's
DHCP pool is `192.168.1.6-192.168.1.254`; its live client table identified
`192.168.1.250` as wireless MAC `74:40:be:be:4f:e6`, saved name `Telkkari`,
hostname `LGwebOSTV`. LXC 240 simultaneously had static MAC
`bc:24:11:4b:fa:8b` on that address. The guest moved to `192.168.1.5`, after
checks found no saved or live UniFi client, ARP response, ICMP response, or
existing lab claim for `.5`. The LXC configuration, UDM static route, CT950
direct route, and local impairment environment all use `.5` now
(re-confirmed live on 2026-09-20: `pct config 240` still shows
`net0: ...,ip=192.168.1.5/24`). Do not reuse `.250` for the guest.

VLAN 40 is unused and is reserved for the impairment network. The Integration
API client list identifies the Galaxy S22 Ultra as MAC
`8e:75:9e:7b:ab:32`, IP `192.168.1.105`, with default network access.

Use these values in later phases:

| Variable | Value | Evidence / ownership |
| --- | --- | --- |
| `LAB_SSH` | `lab` | SSH channel verified |
| `IMPAIR_VMID` | `240` | `kh-claim` class `vmid`, owner `ple-189` |
| `IMPAIR_VLAN_ID` | `40` | absent from the UniFi network list |
| `IMPAIR_SUBNET` | `192.168.40.0/24` | paired with VLAN 40 |
| `IMPAIR_GW` | `192.168.40.1` | netem guest VLAN-side address (live) |
| `IMPAIR_GUEST_LAN_IP` | `192.168.1.5` | outside the Default DHCP pool; `kh-claim` class `ip` owned by `ple-194` |
| `PHONE_MAC` | `8e:75:9e:7b:ab:32` | UniFi client at `192.168.1.105` |
| `PS5_IP` | `192.168.1.164` | ticket-provided fixed address |

**Correction (2026-09-20):** there is no checked-in `impair.env.example`
template — `test -e scripts/net/impair.env.example` fails in the workspace,
and no file by that name exists in either repo. The populated env file is
`~/.config/pleikkari/impair.env` (untracked, as before); the keys it may set
are exactly the four `impairctl.py` reads back —
`LAB_SSH`, `IMPAIR_VMID`, `IMPAIR_EGRESS_IFACE`, `IMPAIR_INGRESS_IFB`
(`scripts/net/impairctl.py`, `CONFIG_KEYS`) — plus CLI overrides
(`--lab-ssh`, `--vmid`, `--egress-iface`, `--ingress-ifb`). If a template is
wanted, it belongs next to that file in the workspace, not here; this is a
documentation ticket, so none was added — see Follow-ups.

Before provisioning, verify ownership with:

```sh
ssh -n lab 'kh-claim who vmid 240; kh-claim who ip 192.168.1.5'
```

Do not substitute a value after a check-then-create race. If either claim is
no longer owned by the impairment work, atomically take a new value with
`kh-claim` and update both the local env and this table.

## 1. Running netem guest (updated 2026-09-20)

Deployed by PLE-194 on 2026-09-16, since re-plumbed for the transparent-VLAN
design in the workspace's `scripts/net/README-IMPAIR-VLAN.md`. The permanent
guest is privileged Debian 13 LXC **240**, hostname `pleikkari-netem`, on
`pve-nvme`. Live-checked 2026-09-20 via `pct config 240` and
`pct exec 240 -- ip -br addr`:

| Guest NIC | Proxmox attachment | Guest address | Purpose |
| --- | --- | --- | --- |
| `eth0` | `vmbr0`, untagged | `192.168.1.5/24`, gateway `192.168.1.1` | main LAN, SSH, PS5 side |
| `eth1` | **`vmbr-impair`**, VLAN tag 40 | `192.168.40.1/24` | `ps-impair` gateway |

The **`eth1` bridge changed from `vmbr0` (tagged) to a dedicated
`vmbr-impair`** since this section was first written; see the §0 correction.
Everything else about the NIC roles is unchanged and reconfirmed live.

Persistent guest configuration, checked read-only on 2026-09-20:

- IPv4 forwarding is on (`pct exec 240 -- cat /proc/sys/net/ipv4/ip_forward`
  → `1`). **Unverified:** the persistence mechanism. The runbook previously
  cited `/etc/sysctl.d/99-pleikkari-netem.conf`; that file does not exist on
  the live guest today. Forwarding is on right now, but how it survives a
  reboot was not checked (would require a reboot or reading systemd-sysctl
  drop-ins not covered by this read-only pass) — do not assume the cited path
  is where it comes from.
- `/etc/dnsmasq.d/pleikkari-impair.conf` exists and `dnsmasq` is `active`.
  Its live content (`pct exec 240 -- cat ...`):
  `interface=eth1`, `except-interface=eth0`, `except-interface=lo`,
  `bind-interfaces`, `dhcp-range=192.168.40.100,192.168.40.200,12h`,
  `dhcp-option=3,192.168.40.1` (router), `dhcp-option=6,192.168.1.1` (DNS).
  This narrows the previously documented `192.168.40.100-199` pool to
  `.100`–`.200`, and the lease time (`12h`) was not documented before.
- `/usr/local/sbin/pleikkari-impair` exists on the guest (`ls -la
  /usr/local/sbin/`), mode `0755` — this is `GUEST_SCRIPT` in
  `scripts/net/impairctl.py`, pushed there by `impairctl.py install` from the
  workspace's tracked `scripts/net/impair.sh`. A separate
  `/usr/local/sbin/impair.sh` is also present (an earlier install under the
  old name) and a disabled `pleikkari-bridge-up.disabled-ple349`, an artifact
  of an earlier PLE-349 design that is no longer active.
- **`scripts/net/guest/` does not exist.** `test -e scripts/net/guest` fails
  in the workspace, and no such directory exists in this fork either — it was
  never checked in. The persistent files this runbook previously said came
  "from `scripts/net/guest/`" (the sysctl drop-in, the dnsmasq conf, the
  `ifb` modules-load conf) live only on the guest and the host, applied by
  hand or by a since-removed provisioning step; they are not currently
  reproducible from a tracked file in either repo. Treat that as a gap, not a
  documentation error to silently paper over — see Follow-ups.

Root SSH accepts CT950's `~/.ssh/id_ed25519` key. The untracked env still
contains:

```sh
IMPAIR_HOST=root@192.168.1.5
IMPAIR_PEER=192.168.1.164
```

To check the guest's idle state from CT950, run the workspace tool by its
real path:

```sh
cd /home/wnt/gta6 && scripts/net/impairctl.py status
```

This is read-only (it does not require `--commit`) but it was **not** run for
this ticket: PLE-366 is using the rig for a live measurement right now, and
even a read-only status call adds noise to a run in progress for no benefit
to a documentation ticket. The facts checked instead were read directly via
`pct exec 240` (hostname, interfaces, config, forwarding, dnsmasq, installed
scripts), none of which touch `tc` state or the active profile.

The TTL/watchdog behavior described lower in this doc is owned by
`scripts/net/impair.sh` in the workspace and matches the deployed script
(confirmed present on the guest, above); see
`scripts/net/README-IMPAIR-VLAN.md` for the current, tested description of
apply/clear semantics — it supersedes the paragraph that used to be here.

## 2. Routed return paths (updated 2026-09-20)

The UniFi Network application is `10.6.106` on the UDM Pro. PLE-194 created
the enabled route `pleikkari-impair` with distance 1:

```text
192.168.40.0/24 via 192.168.1.5
```

This section describes the UniFi-side route to the PS5; it was not
re-verified against the UniFi API on 2026-09-20 (out of scope for this
documentation pass, and it changes nothing about impairment state). What
**was** re-verified, locally on CT950 (no ssh needed, no lab state touched):

```sh
$ ip route show | grep 192.168.40
192.168.40.0/24 via 192.168.1.5 dev eth0 metric 10
$ cat /etc/systemd/network/eth0.network.d/pleikkari-impair-route.conf
# Keep ADB/control return traffic on the direct path to the netem guest.
[Route]
Destination=192.168.40.0/24
Gateway=192.168.1.5
Metric=10
```

The route is live and matches what this runbook always claimed. **Correction:**
the file is not "sourced from `scripts/net/guest/ct950-impair-route.conf`" —
that path does not exist in the workspace (`test -e` fails) and never has, as
far as this pass could determine. The systemd-networkd drop-in on CT950 is
the only copy; nothing in either repo currently reproduces it from source.
Same gap as the guest-side files in §1 — see Follow-ups.

This is the clean ADB/control return path. It does not bypass impairment for
PS5 traffic because the classifier runs on the netem guest after VLAN traffic
enters `eth1`.

## 3. Phone-free verification (historical, PLE-194, 2026-09-16)

PLE-194 temporarily created LXC 241 at `192.168.40.2/24` on tagged VLAN 40,
with default gateway `192.168.40.1`. The client claim and VM were destroyed
after the run; LXC 241 and IP `.2` are now unclaimed. No phone setting or VLAN
assignment changed. This section was not re-verified on 2026-09-20 — it is a
point-in-time record of the first rollout and is unaffected by whether the
traffic classifier has since changed (see the design doc's correction for
that). Its raw evidence is still under
`/home/wnt/gta6/build/dispatch/ple-194/evidence/` (existence not re-checked
here; not cited elsewhere in this pass).

Immediately before measurement,
`scripts/dev/ps5-discover.py` found `PS5-466` at `192.168.1.164:9302`, target
PS5, status `ready`. Each main result below used 1,000 timestamped ICMP probes
from the temporary client.

| State | Target | Received | Loss | RTT avg | RTT mdev | Delta from clean |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| clean | PS5 `192.168.1.164` | 1000/1000 | 0% | 0.151 ms | 0.019 ms | baseline |
| clean | CT950 `192.168.1.114` | 1000/1000 | 0% | 0.054 ms | 0.017 ms | baseline |
| `4g` | PS5 `192.168.1.164` | 983/1000 | 1.7% | 45.119 ms | 10.799 ms | **+44.968 ms** |
| `4g` | CT950 `192.168.1.114` | 1000/1000 | 0% | 0.082 ms | 0.014 ms | +0.028 ms |
| explicit clear | PS5 `192.168.1.164` | 500/500 | 0% | 0.153 ms | 0.019 ms | +0.002 ms |
| explicit clear | CT950 `192.168.1.114` | 500/500 | 0% | 0.057 ms | 0.017 ms | +0.003 ms |
| watchdog clear | PS5 `192.168.1.164` | 500/500 | 0% | 0.159 ms | 0.050 ms | +0.008 ms |
| watchdog clear | CT950 `192.168.1.114` | 500/500 | 0% | 0.062 ms | 0.016 ms | +0.008 ms |

The observed 1.7% ICMP loss is consistent with 1% independently configured on
each direction: echo-request and echo-reply survival predicts 1.99% round-trip
loss. It must not be interpreted as a one-way 1.7% setting.

After the `4g` run, `tc -s` showed PS5 packets in impaired classes `1:2` and
`2:2`, with 9 and 8 netem drops respectively. The concurrent CT950 probes were
in clean classes `1:1` and `2:1`; their measured loss was zero. `clear` removed
both IFBs and left only each physical NIC's `noqueue` qdisc. **Note:** this
two-IFB, address-classified layout was the *design* (see
`docs/design/NETWORK-IMPAIRMENT.md`); the currently deployed
`scripts/net/impair.sh` classifies by ADB port on a single leg instead (see
that doc's correction). This table is left as the historical record of the
first rollout, not a claim about today's classifier internals.

For the watchdog test, `4g --ttl 1m` armed the systemd timer with 59 seconds
remaining. Polling from CT950 observed `active_profile=clean` at 62 seconds
without a clear call. Both post-expiry probe sets then matched baseline. Live
apply/status/clear also succeeded for `5g`, `wifi-slow`, `loss-2`, and
`blip-200ms`.

This proves the PS5-only impairment classifier *as it existed on 2026-09-16*
and the clean CT950 **ADB-equivalent** path. Actual Wi-Fi ADB and streaming
remain phone tests for later phases.
