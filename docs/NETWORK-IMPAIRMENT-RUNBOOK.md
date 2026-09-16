# Network impairment runbook

## 0. Access and addressing

Verified from CT950 on 2026-09-16. Use `ssh -n lab '<cmd>'` as the only door
to the Proxmox host; do not nest SSH. The host identifies itself as
`pve-nvme` and reports Proxmox `pve-manager/9.2.4/5e5ae681198514d4` on kernel
`7.0.14-4-pve`. Both `pct list` and `qm list` are readable.

The lab host has `vmbr0`, `vmbr-rn`, `vmbr-wi`, and the per-client `wibr*`
bridges. `vmbr0` is backed by `nic3`, has `bridge-vlan-aware yes`, and permits
VLAN IDs 2–4094. It can therefore carry the impairment VLAN without a host
bridge change.

The UniFi Integration API is readable using the local
`~/.config/unifi/config.env` configuration and an `X-API-KEY` loaded from
`UNIFI_TOKEN_FILE`. Never print or commit that key. `GET /sites` returns the
Default site. `GET /sites/{siteId}/networks` returns:

| Network | VLAN ID | Enabled | Management |
| --- | ---: | --- | --- |
| Default | 1 | yes | gateway |
| Greenhouse | 30 | yes | gateway |

VLAN 40 is unused and is reserved for the impairment network. The Integration
API client list identifies the Galaxy S22 Ultra as MAC
`8e:75:9e:7b:ab:32`, IP `192.168.1.105`, with default network access.

Read-only evidence (irrelevant bridge members are omitted):

```text
$ ssh -n lab 'hostname; pveversion; pct list; qm list; brctl show || ip -br link'
pve-nvme
pve-manager/9.2.4/5e5ae681198514d4 (running kernel: 7.0.14-4-pve)
VMID  Status   Name
112   stopped  serenity-build
210   running  garage
220   running  locator-kiosk-dev
950   running  osgallery-dev
951   running  retronet-gw
952   running  walkin-gw
VMID  NAME                  STATUS
200   greenhouse-k3s        running
221   locator-kiosk-vm      running
222   locator-kiosk-alpine  running
223   locator-kiosk-netbsd  stopped
bridge name  interfaces
vmbr-rn      ...
vmbr-wi      ...
vmbr0        nic3, tap200i0, tap200i1, tap221i0, tap222i0,
             veth210i0, veth220i0, veth950i0
wibr256...wibr280 ...

$ ssh -n lab 'sed -n "/^auto vmbr0/,/^$/p" /etc/network/interfaces'
auto vmbr0
iface vmbr0 inet static
        address 192.168.1.126/24
        gateway 192.168.1.1
        bridge-ports nic3
        bridge-stp off
        bridge-fd 0
        bridge-vlan-aware yes
        bridge-vids 2-4094
```

The API responses, filtered to non-secret addressing fields, were:

```json
{"sites":[{"name":"Default","internalReference":"default"}]}
{"networks":[
  {"name":"Default","vlanId":1,"enabled":true,"management":"GATEWAY"},
  {"name":"Greenhouse","vlanId":30,"enabled":true,"management":"GATEWAY"}
]}
{"matching_s22":[{
  "name":"Kayttajan-Jonni-S22-Ultra ab:32",
  "macAddress":"8e:75:9e:7b:ab:32",
  "ipAddress":"192.168.1.105",
  "access":{"type":"DEFAULT"}
}],"candidate_250_entries":[],"client_count":25}
```

CT950 cannot open a raw ping socket (`Operation not permitted`), so the free-IP
probe ran through the one lab door. Three ICMP requests from `pve-nvme` to
`192.168.1.250` received no replies. `labctl who` subsequently listed both
`ip/192.168.1.250` and `vmid/240` as held by `ple-189`.

Use these values in later phases:

| Variable | Value | Evidence / ownership |
| --- | --- | --- |
| `LAB_SSH` | `lab` | SSH channel verified |
| `IMPAIR_VMID` | `240` | `kh-claim` class `vmid`, owner `ple-189` |
| `IMPAIR_VLAN_ID` | `40` | absent from the UniFi network list |
| `IMPAIR_SUBNET` | `192.168.40.0/24` | paired with VLAN 40 |
| `IMPAIR_GW` | `192.168.40.1` | future netem guest VLAN-side address |
| `IMPAIR_GUEST_LAN_IP` | `192.168.1.250` | no UniFi client entry, no ping reply from `pve-nvme`, and `kh-claim` class `ip` owned by `ple-189` |
| `PHONE_MAC` | `8e:75:9e:7b:ab:32` | UniFi client at `192.168.1.105` |
| `PS5_IP` | `192.168.1.164` | ticket-provided fixed address; availability not probed in phase 0 |

The populated, untracked copy is `~/.config/pleikkari/impair.env`; the checked-in
template is `scripts/net/impair.env.example`. The VMID and LAN IP claims are
intentionally retained for downstream PLE-194. Before provisioning, verify
ownership with:

```sh
ssh -n lab 'kh-claim who vmid 240; kh-claim who ip 192.168.1.250'
```

Do not substitute a value after a check-then-create race. If either claim is no
longer owned by the impairment work, atomically take a new value with
`kh-claim` and update both the local env and this table.

## 1. Running netem guest

Deployed by PLE-194 on 2026-09-16. The permanent guest is privileged Debian 13
LXC **240**, hostname `pleikkari-netem`, on `pve-nvme`. It has 1 vCPU, 1 GiB
RAM, a 4 GiB `data` rootfs, `onboot=1`, and `nesting=1`. The retained claims
for VMID 240 and `192.168.1.250` are owned by `ple-194`; do not release them
while the guest is the active impairment gateway.

The LXC choice was tested before deployment: with the host `ifb` module loaded,
the container created an IFB, attached `netem delay 1ms`, showed the live
qdisc, and removed both successfully. A VM is therefore unnecessary.

| Guest NIC | Proxmox attachment | Guest address | Purpose |
| --- | --- | --- | --- |
| `eth0` | `vmbr0`, untagged | `192.168.1.250/24`, gateway `192.168.1.1` | main LAN, SSH, PS5 side |
| `eth1` | `vmbr0`, VLAN tag 40 | `192.168.40.1/24`, no gateway | `ps-impair` gateway |

Persistent configuration comes from `scripts/net/guest/`:

- `/etc/sysctl.d/99-pleikkari-netem.conf`: `net.ipv4.ip_forward=1`, no IPv4
  redirects, and no IPv6/RA on `eth1`.
- `/etc/dnsmasq.d/pleikkari-impair.conf`: authoritative DHCP
  `192.168.40.100-199`, router/DNS `192.168.40.1`, bound to `eth1`; upstream
  DNS is `192.168.1.1`.
- Proxmox `/etc/modules-load.d/pleikkari-ifb.conf`: loads `ifb` after a host
  reboot.
- `/usr/local/sbin/impair.sh`: the tracked `scripts/net/impair.sh` installed
  mode 0755.

The guest has no NAT table or masquerade rule. Its nftables forwarding policy
is accept; traffic retains the VLAN client's `192.168.40.x` source address.
After a guest reboot, forwarding remained `1`, `eth1` remained IPv4-only,
`ssh` and `dnsmasq` were active, and a live `5g` apply/clear succeeded.

Root SSH accepts CT950's `~/.ssh/id_ed25519` key. The untracked env contains:

```sh
IMPAIR_HOST=root@192.168.1.250
IMPAIR_PEER=192.168.1.164
```

From CT950, this must print `active_profile=clean` when idle:

```sh
scripts/net/impairctl.py status
```

Every apply from the controller requires `--ttl`. The guest uses the transient
`pleikkari-impair-watchdog.timer`; there is no separate long-running watchdog
daemon to maintain.

## 2. Routed return paths

The UniFi Network application is `10.6.106` on the UDM Pro. PLE-194 created
the enabled route `pleikkari-impair` with distance 1:

```text
192.168.40.0/24 via 192.168.1.250
```

The authenticated local readback endpoint is
`GET /proxy/network/api/s/default/rest/routing`; it returns an object with
`type=static-route`, `static-route_type=nexthop-route`, the network and next
hop above, and `enabled=true`. The modern Integration API exposes networks but
not static-route CRUD on this controller, so creation used that local endpoint
with the same `X-API-KEY` from `UNIFI_TOKEN_FILE`. Never put that key in a
command transcript or repository.

To recreate it in the UI instead, open UniFi Network and create a static route
named `pleikkari-impair` under the routing/policy table: destination
`192.168.40.0/24`, next-hop IP `192.168.1.250`, distance 1, enabled. Confirm the
local API readback before moving a device.

CT950 and the netem LXC are veth peers on the same Proxmox `vmbr0`. The UDM
route works for the physical PS5, but its same-interface redirect was not a
reliable CT950 return path: tcpdump saw CT950 emit replies that never reached
the netem guest. CT950 therefore also has the deterministic direct route below
as `/etc/systemd/network/eth0.network.d/pleikkari-impair-route.conf`, sourced
from `scripts/net/guest/ct950-impair-route.conf`:

```text
192.168.40.0/24 via 192.168.1.250 dev eth0 metric 10
```

This is the clean ADB/control return path. It does not bypass impairment for
PS5 traffic because the classifier runs on the netem guest after VLAN traffic
enters `eth1`.

## 3. Phone-free verification

PLE-194 temporarily created LXC 241 at `192.168.40.2/24` on tagged VLAN 40,
with default gateway `192.168.40.1`. The client claim and VM were destroyed
after the run; LXC 241 and IP `.2` are now unclaimed. No phone setting or VLAN
assignment changed.

Immediately before measurement,
`scripts/dev/ps5-discover.py` found `PS5-466` at `192.168.1.164:9302`, target
PS5, status `ready`. Each main result below used 1,000 timestamped ICMP probes
from the temporary client; all raw output is under
`/home/wnt/gta6/build/dispatch/ple-194/evidence/`.

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
both IFBs and left only each physical NIC's `noqueue` qdisc.

For the watchdog test, `4g --ttl 1m` armed the systemd timer with 59 seconds
remaining. Polling from CT950 observed `active_profile=clean` at 62 seconds
without a clear call. Both post-expiry probe sets then matched baseline. Live
apply/status/clear also succeeded for `5g`, `wifi-slow`, `loss-2`, and
`blip-200ms`.

Key evidence files:

- `ps5-discover.txt`
- `ping-clean-{ps5,ct950}.txt`
- `ping-4g-{ps5,ct950}.txt`
- `status-4g-after.txt`
- `ping-clear-{ps5,ct950}.txt`
- `watchdog-armed.txt`, `watchdog-poll.txt`, `watchdog-clean-state.txt`
- `ping-watchdog-clean-{ps5,ct950}.txt`
- `pct-config-240.txt`, `guest-final-state-after-reboot.txt`
- `unifi-static-route.json`, `ct950-route.txt`

This proves the PS5-only impairment classifier and the clean CT950
**ADB-equivalent** path. Actual Wi-Fi ADB and streaming remain phone tests for
later phases.
