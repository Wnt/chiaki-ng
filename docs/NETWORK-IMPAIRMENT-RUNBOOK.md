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
