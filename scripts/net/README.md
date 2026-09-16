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
