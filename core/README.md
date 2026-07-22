# custody-app

The customer-hosted credential custody client (). It keeps site
credentials **at rest only on the user's device** and, when the
The device hub pings it for one in-flight login, releases **exactly one
secret** — HPKE-sealed end-to-end to the login worker — after an explicit
per-secret user approval.

This module is the **testable client core**. The desktop daemon main loop
(outbound `wss` to the hub) and the OS-native keychain backings (macOS Keychain,
Windows Credential Manager, Linux libsecret) land in follow-ups; here the store is
an in-memory backing.

## Packages

| Package | Role |
|---|---|
| `protocol` | Client implementation of the device-relay wire contract (`schema/device_relay.yaml`) + the HPKE seal (RFC 9180, std `crypto/hpke`). The golden-vector test proves it interoperates byte-for-byte with the server's `crypto/hpke` worker. |
| `store` | The device-local `CredentialStore` interface + an in-memory backing (`MemStore`). The only place a durable secret lives, and only on the device. |
| `otp` | On-device acquisition of email/SMS one-time codes. `ExtractCode` (pure, the testable core) pulls a code out of an email body with keyword-adjacency + false-positive (phone/year/order-number) rejection; a `MailboxReader` (IMAP-backed impl + `FakeMailbox`) reads the newest matching message and returns **only** the code. The mailbox OAuth/IMAP credential stays on the device — only the code is relayed. Desktop SMS is manual-entry; mobile SMS read (Android SMS User Consent) is phase 2, not here. |
| `relay` | The core: resolve the one secret a `SecretPing` asks for, seal it to the worker key, and Ed25519-sign `(request_id, challenge, sealed)` so the server can prove the release is from this paired device and matches this ping (anti-replay). `ResolveSecretWithSource` answers an email/SMS/manual-code ping from an injected `otp.CodeSource`, falling back to a supplied/manual code. |

## Invariant

No durable secret ever transits. Mailbox tokens never leave the
device — the device reads the one-time code locally and relays only the code. Only the
password and short-lived one-time codes cross the wire, and only inside the
HPKE-sealed `SecretRelease`.

## Wire compatibility

`protocol` and the server's `the server's device-relay package` are deliberately
separate implementations of the same language-agnostic contract. Compatibility is
proven, not assumed: `schema/testdata/device_relay_hpke_golden_vector.json` is
opened by both, and `protocol.SealInfo` is asserted to byte-match the server's.
