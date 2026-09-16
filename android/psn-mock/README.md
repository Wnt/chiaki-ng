# PSN sign-in mock (PLE-284)

A small stand-in for Sony's private Remote Play sign-in, so that onboarding can be
tested on the emulator or a phone repeatedly, unattended, and without a real Sony
account. Every code and token it issues is fake by construction, and none is ever
logged.

It runs behind the [forwarder](https://github.com/Wnt/forwarder) on two public
hosts that have real Let's Encrypt certificates. Nothing needs a custom CA, and
WebAuthn gets a genuine secure origin:

| Host | `/.well-known/assetlinks.json` | Stands for |
|---|---|---|
| `pleikkari-psn.lab.madekivi.fi` | served (`com.metallic.chiaki.psnmock`, debug key `52:71:7A…A0:1C`) | a **verified** app link |
| `pleikkari-psn-nolink.lab.madekivi.fi` | 404 | an **unverifiable** link: what `remoteplay.dl.playstation.net` is in production |

## Run it

```bash
android/psn-mock/serve.sh start     # mock on 127.0.0.1:18284 + forwarder-agent, both backgrounded
android/psn-mock/serve.sh status    # processes, and assetlinks HTTP status on both hosts
android/psn-mock/serve.sh stop
```

`serve.sh` fetches `forwarder-agent` from the Wnt/forwarder CI artifact into
`~/.cache/pleikkari/forwarder/` if it is missing. It reads the agent token from
`$FORWARDER_AGENT_TOKEN`, or else from the first env file it finds:
`$PSN_MOCK_AGENT_ENV`, `~/.config/pleikkari/forwarder-agent.env`, then
`~/.config/locator-kiosk/forwarder-agent.env`. The token reaches the agent
through the environment, never on a command line. Logs and stored passkeys live
in `~/.local/state/pleikkari-psn-mock/`.

To keep the mock up across reboots, run the foreground form from a systemd user unit:

```ini
# ~/.config/systemd/user/pleikkari-psn-mock.service
[Service]
ExecStart=/home/wnt/gta6/chiaki-ng/android/psn-mock/serve.sh run
Restart=always
[Install]
WantedBy=default.target
```

The mock's own tests are `python3 -m unittest discover -s android/psn-mock` (about 7 s, no network).

## Endpoints

These are the same paths as Sony's, so the app only swaps the host (`PsnServiceEndpoints.mock`):

| Path | Mirrors |
|---|---|
| `GET /2.0/oauth/authorize` | Sony's sign-in page. It checks `response_type`, `client_id` and a registered `redirect_uri`, then offers a password form, *Sign in with a passkey*, *Create a passkey* and *Cancel* |
| `GET /remoteplay/redirect?code=…` | the redirect that carries the code. When no app takes the link, the page is blank, like Sony's |
| `POST /2.0/oauth/token` | code exchange and refresh. Needs the app's Basic client auth; codes are single-use and bound to their `redirect_uri` |
| `GET /2.0/oauth/token/{access}` | token info with `user_id`; the token response itself has none, like Sony's |
| `GET /api/cloudAssistedNavigation/v2/users/me/clients` | the console list: one PS5 with `remotePlay`, and one without it (filtered out) |
| `GET /__mock/events?since=N` | what happened, for tests. Account names only |

Anything else returns `501 not mocked`, including the push WebSocket lookup, sessions and wake commands.

## Scenarios

The **account name** picks the scenario, so parallel runs never share state. The
part before `+` or `@` is the name: `consoles-500+run7@mock` behaves as
`consoles-500`. Any password works except `wrong`, which is rejected on the page.

| Account | Behaviour |
|---|---|
| `ok` (or any other name) | signs in; the console list has one PS5 |
| `expired-code` | the code has expired before it is exchanged (`400 invalid_grant`) |
| `code-used` | the code counts as already exchanged (`400 invalid_grant`) |
| `cancel` | redirects with `error=access_denied` and no code; the *Cancel* button does the same |
| `network-drop` | the exchange stalls 20 s (past the app's 15 s timeout), then the connection closes |
| `token-refused` | sign-in works; every later refresh is refused (`400 invalid_grant`) |
| `empty-consoles` | sign-in works; the console list is empty |
| `consoles-500` | sign-in works; the console list fails with HTTP 500 |

Passkeys are real WebAuthn (ES256/RS256, discoverable credentials). A passkey is
bound to the host it was created on. The account it was created for picks the
scenario. Attestation statements are accepted without checking.

## The app build

```bash
cd android && ./gradlew -PchiakiPsnMock=verified assembleDebug   # or =nolink
```

- The build is a **separate app**, `com.metallic.chiaki.psnmock`, labelled *PSN MOCK Chiaki*, with a red *PSN MOCK · host* strip over the status bar of every activity. It can never overwrite a real install's PSN credentials or PS5 registration.
- Both mock hosts are declared as links. The verified host is `autoVerify`; the nolink host is declared without it, exactly like Sony's redirect host in `src/main`.
- **It cannot ship.** The mock host comes from `BuildConfig.PSN_MOCK_HOST`, which only the debug build type defines. The release source set's `psnMockHost()` returns `null` and has no mock code at all. A release task with `-PchiakiPsnMock` fails the build.
- The asset link names the debug key in `~/.android/debug.keystore`. A build signed with another key (`chiakiKeystore` in `local.properties`) cannot verify.

## The onboarding test

```bash
android/psn-mock/onboarding_test.py --build                          # verified link, happy path
android/psn-mock/onboarding_test.py --build --link nolink            # production's situation
android/psn-mock/onboarding_test.py --link nolink --select-domain    # the user enabled the link
android/psn-mock/onboarding_test.py --scenario network-drop          # any scenario above
node android/psn-mock/passkey_check.mjs                              # passkey branch, headless Chrome
```

The driver needs the mock up and the emulator booted (`scripts/dev/emu.sh start`),
and it takes the emulator reservation. It resets the mock app to a first-run
state by clearing the app's own data with `run-as`, because the adb wrapper
refuses every uninstall. It taps through onboarding and fills the mock's form in
the browser. It fails on:

- any other app in front, above all Android Settings,
- an app `TextView` of 14 or more words (an instruction paragraph),
- 40 s with no screen change short of an end state (a dead end),
- an app crash,
- for `ok`, the console list never showing *PS5 mock*.

A failure scenario passes when the app settles on one of its own screens with a
control. Artifacts go to `build/psn-mock/onboarding-*/` in the workspace:
`summary.json`, a screenshot and UI dump per distinct screen, and the mock's
events. One run takes 30–90 s.

## What the mock cannot reproduce about Sony

This list is where the next surprise will come from.

1. **Sony's page is a multi-step JavaScript app. The mock's is a single form.** Sony asks for the ID and the password on separate steps. It can add 2-step verification, captcha or bot checks, "trust this browser", consent and age screens, account-locked and region errors. The mock models none of these.
2. **How Sony redirects.** The mock answers the password form with a plain `302` straight after the user's tap. That is the navigation Chrome most readily hands to an app link. Sony's redirect may come from script, after asynchronous steps and after the tap's user activation has expired. Chrome may then show the blank redirect page even for a link it would otherwise open in the app. The mock's passkey branch does navigate from script (`location.assign`), which is the closer model.
3. **Passkeys.** On the mock, the relying party is the mock host. On Sony it is Sony's domain, whose assetlinks list only Sony's apps. Credential Manager in the app's WebView can never use a Sony passkey. The mock deliberately does not delegate (`handle_all_urls` only). Still, no Android passkey provider was exercised here: the emulator has no Google account, and the check uses a CDP virtual authenticator in desktop Chrome. The S22's Samsung Pass and Google Password Manager are untested.
4. **The nolink build still carries one verified link.** The mock app declares both hosts, so Android's link settings show "1 verified link". Production has none. What the app does is the same, but the Settings screen text differs.
5. **A network drop is only a stall.** Through Caddy and the forwarder, the app sees a 20 s stall and a closed connection or a `502`. It does not see a TCP reset, DNS failure, airplane mode, captive portal or a network switch mid-exchange.
6. **Error bodies, code lifetime and cancel are guesses.** The `invalid_grant` body and `error_code` numbers are unverified against Sony. Sony's real code lifetime is unknown; the mock uses 300 s. What Sony sends back on cancel is also unknown; the mock uses OAuth's `error=access_denied`.
7. **No sessions or SSO.** Sony may remember a signed-in browser. With `prompt=always` it should still ask, but the mock always shows the form and ignores `duid`, `smcid`, `ui`, `layout_type` and locale.
8. **Token lifetime and refresh.** The mock's refresh tokens never expire and accept any scope. Sony rotates and expires them.
9. **Anything after the console list.** Push WebSocket, session creation, wake and remote play commands return `501`. A mock console cannot be registered or streamed.
10. **Browsers.** The emulator has Chrome only. The S22's default browser, Samsung Internet or Firefox, handles Custom Tabs and app links differently (see PLE-279 on Firefox's blocked background activity start).
