# custody-daemon

The customer's local credential custody app (): it holds the user's
credentials on **this device**, keeps one outbound WebSocket to the device
hub, and answers each on-demand `SecretPing` by relaying exactly one
secret — HPKE-sealed to the login worker and Ed25519-signed. It wraps the
golden-vector-verified `custody-app` library (`protocol` / `relay` / `store` /
`otp`) unchanged — no new crypto.

**Cross-platform strategy: one Go core, thin native shells.** The reusable core
is package [`agent`](./agent) (hub WebSocket + ping→relay + replay guard + the
wire protocol, defined once so client and hub can't drift). Both the desktop
daemon (`package main`) and the mobile binding ([`mobile`](./mobile), the
gomobile surface) drive that same core, so the relay/crypto behaves identically
on every platform. See [`BUILD.md`](./BUILD.md) and [`shells/`](./shells).

## Layout

| Path | Role |
|---|---|
| `agent/` | Reusable core: `Run(ctx, Config)` = hub `wss` loop (hello handshake, reconnect backoff), `handlePing` (resolve→seal→sign), replay guard, `CompletePairing`. The wire envelope lives here once. |
| `main.go` | Desktop daemon entrypoint — wires the OS keychain to `agent.Run`. |
| `keyringstore.go` + `keyring_{darwin,linux,other}.go` | Native credential store: macOS Keychain (`security`), Linux Secret Service (`secret-tool`), behind an injectable interface (logic unit-tested against a fake). |
| `identity.go` | Device identity: keychain-first, then env, then **pair on first run** (`RINDLER_PAIRING_CODE` → `/devices/pair/complete`), persisting token+key to the keychain. |
| `mobile/` | `gomobile bind` surface: `Start` / `Pair` / `GenerateDeviceKey`, plus `SecretSource` + `Approver` callbacks the native shells implement. |
| `shells/` | Thin native shells (iOS/macOS SwiftUI, Android Compose, desktop service). |
| `Makefile` | `make desktop` / `make android` / `make ios` / `make doctor`. |

## What's done + tested (race-clean)

- Ping→relay for password + no-credential; **hermetic end-to-end** (real
  `Run` loop vs a fake hub; the release opens under the worker key + verifies).
- **Replay defense**: a replayed `request_id` is declined, never released twice.
- **Keychain store**: put/get round-trips every field; site index;
  delete. Native backends compile for darwin/linux/windows.
- **Pairing**: the `/devices/pair/complete` call (only the PUBLIC key leaves the
  device) + keychain persistence + keychain-first reload.
- **Mobile binding**: `SecretSource` JSON parsing, `Pair`, key generation.

## Run (desktop)

```
make desktop                       # -> ./custody-daemon (any OS)
RINDLER_PAIRING_CODE=<code minted at your-hub.example/settings/devices> ./custody-daemon
```

On first run it pairs, then persists the device token + key in the OS keychain;
subsequent runs load identity from the keychain. The headless binary has no
approval surface and therefore declines every secret request; use a native shell
for approved releases. `make doctor` reports the toolchain for the mobile/Mac
targets.

## Genuinely remaining (needs hardware this repo can't provide)

The native **UI shells** are skeletons in `shells/`; turning them into signed,
store-submitted apps needs a Mac + Xcode + Apple Developer certs (iOS/macOS) and
the Android SDK/NDK. Those steps — and only those — are tracked under.
`make ios` / `make android` produce the framework/`.aar` the moment that
toolchain is present; nothing else is stubbed.
