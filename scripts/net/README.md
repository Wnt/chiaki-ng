# Network impairment tools

## UniFi phone VLAN switch

`unifi-vlan.py` applies a per-client network override to the one phone whose
MAC is configured as `PHONE_MAC`. It refuses every other MAC. A successful move
keeps the previous override in a mode-0600 state file so `revert` can restore
it. If the confirmation command does not succeed before the deadline, the tool
restores the previous override automatically and exits non-zero.

The tool first reads `~/.config/pleikkari/unifi.env`. Until that per-project
file exists, it falls back to the PLE-189 verified
`~/.config/unifi/config.env`. The UniFi file needs:

```text
UNIFI_HOST=https://controller.example
UNIFI_SITE_NAME=default
UNIFI_TOKEN_FILE=/path/to/mode-0600-api-key
UNIFI_CURL_INSECURE=1
```

`~/.config/pleikkari/impair.env` must contain the real `PHONE_MAC`; start from
[`impair.env.example`](impair.env.example). Neither populated file nor the API
key belongs in git.

Show the connected network/VLAN, IP, and access point:

```bash
scripts/net/unifi-vlan.py status <phone-mac>
```

Move the phone and retry Wi-Fi ADB discovery for up to two minutes:

```bash
scripts/net/unifi-vlan.py move <phone-mac> Impair \
  --revert-after 2m \
  --confirm-cmd "scripts/dev/phone.sh connect"
```

After the impairment run, restore the exact saved override (including no
override, which returns control to the SSID):

```bash
scripts/net/unifi-vlan.py revert <phone-mac>
```

Add `--dry-run` to `move` or `revert` to print each controller write without
sending it. Reads still occur so names can be resolved and safety checks can be
performed. Dry runs do not create or remove saved state.

Run the state-machine unit tests without controller access:

```bash
python3 -m unittest scripts/net/test_unifi_vlan.py
```

## PS5 network impairment

`impair.sh` runs as root on the Debian/Ubuntu netem guest. `impairctl.py` runs
on CT950 and invokes it over SSH. The controller requires a TTL on every apply,
so an abandoned test cannot leave the PS5 path impaired indefinitely.

### Traffic layout

The script discovers the interfaces used to reach the PS5 (`--peer`) and phone
(`--phone`). On the normal two-NIC routed guest, ingress from each interface is
redirected to its own IFB. Each IFB has a two-band `prio` qdisc whose default
band is clean; a `flower` tuple filter sends only phone-to-PS5 or PS5-to-phone
packets through the impaired band. SSH, adb, DHCP, DNS, and mDNS traverse the
clean band.

`--phone` remains optional for bring-up and one-interface layouts. Without it,
the peer-facing interface uses direct egress shaping plus an ingress IFB and
matches the PS5 address alone. Supply `--phone` for the deployed two-NIC guest
so a future second PS5 client also remains clean.

The guest needs `iproute2`, the `ifb` kernel module, `awk`, `flock`, and root.
Install the script somewhere on root's PATH, for example:

```sh
sudo install -m 0755 scripts/net/impair.sh /usr/local/sbin/impair.sh
```

### Profiles

| Profile | Netem parameters in each direction |
| --- | --- |
| `clean` | no qdisc or IFB remains |
| `5g` | 20 ms added RTT (10 ms per leg), 5 ms RTT jitter (2.5 ms per leg), 0.3% random loss per leg |
| `4g` | 45 ms added RTT (22.5 ms per leg), 15 ms RTT jitter, 1% loss, 0.5% reorder, 30 Mbit/s per leg |
| `wifi-slow` | 10 ms added RTT (5 ms per leg), 25 ms RTT jitter with 25% correlation, 2% loss and 2% reorder with 25% correlation, 20 Mbit/s per leg |
| `blip-200ms` | normally 0 ms delay; toggles 200 ms delay for 200 ms every 20 seconds |
| `loss-2` | 2% random loss only |
| `custom` | caller supplies delay, jitter, loss, reorder, and/or rate |

The profile labels are round-trip budgets and the script splits delay and
jitter evenly between directions. Loss and reorder remain per-direction packet
probabilities. Rate caps use TBF above netem rather than netem's approximate
rate option.

### CT950 setup and use

Create `~/.config/pleikkari/impair.env` (shell export syntax is also accepted):

```sh
IMPAIR_HOST=root@192.168.1.250
IMPAIR_PEER=192.168.1.164
```

Then run:

```sh
scripts/net/impairctl.py apply 4g --ttl 30m --phone 10.20.0.22
scripts/net/impairctl.py status
scripts/net/impairctl.py clear

scripts/net/impairctl.py apply custom --ttl 5m \
  --delay 30ms --jitter 8ms --loss 1.5% --reorder 0.5% --rate 25mbit
```

`impair.sh` uses a transient systemd timer for auto-clear when systemd is
available. It falls back to a detached sleep watchdog otherwise. A new apply
first cancels the old watchdog and blip loop and removes the old tree, making
apply and clear idempotent. Runtime state is under `/run/pleikkari-impair`.

`status` prints the recorded profile followed by the live qdisc and filter
trees (including counters). The controller uses batch-mode SSH with a ten
second connection timeout and returns SSH's non-zero status unchanged.

### Tests

The command-generation suite is rootless and asserts the complete `tc` command
list for every built-in profile and for custom impairment:

```sh
python3 -m unittest -v scripts/net/test_impair.py
shellcheck scripts/net/impair.sh
```

CT950 cannot run the requested namespace integration test as its normal user:
`ip netns add` fails while creating `/run/netns` with `Permission denied`.
Phase 4 should perform the following manual check on the real routed topology:

1. From a test client in the impair VLAN, record at least 200 pings each to the
   PS5 (`192.168.1.164`) and CT950 (`192.168.1.114`) with the profile clean.
2. Run `impairctl.py apply 4g --ttl 5m`, repeat both ping sets, and save the raw
   output. The PS5 path should gain about 45 ms RTT and show up to roughly 2%
   ping loss (1% independently on request and reply); the CT950 path should
   remain near its baseline.
3. Run `impairctl.py status` and confirm packet counters grow only on the
   PS5-address filters. Run `clear`, repeat both pings, and verify the PS5 path
   returns to baseline.
4. Apply `4g --ttl 1m` without clearing it. After 70 seconds, verify status is
   `clean` and both destinations remain reachable.

Do not infer PS5 availability from these defaults; phase 4 must probe the
address before making a hardware claim.
