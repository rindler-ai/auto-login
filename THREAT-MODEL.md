# Threat Model

This document describes the trust model, guarantees, and residual risks of
Auto Login. It is written to be precise about what the design protects and,
equally, about what it does not. Read it before relying on Auto Login for anything
sensitive.

## System overview

Three parties participate in a login:

- **Device** — your own machine or phone, running a native shell over the shared
  Go core. It holds your credentials at rest in the OS keychain / keystore and is
  the only place a durable secret lives.
- **Hub** — a **semi-trusted** server you (or your operator) run. It routes
  messages between your device and the login worker, maintains a per-user device
  registry, and mints pairing codes. It never holds your secrets.
- **Login worker** — the process that actually drives the target site's login. It
  receives one sealed secret per login, uses it in memory, and discards it.

The device speaks one language-agnostic wire contract
(`contract/device_relay.yaml`) to the hub.

## Assets

- **Durable secrets:** site passwords, TOTP seeds, and mailbox/OTP tokens (OAuth
  or IMAP credentials used to read email one-time codes).
- **Short-lived secrets:** a generated TOTP code, an email/SMS one-time code, or a
  manually typed code — each valid only briefly.
- **Device identity:** the device's long-lived Ed25519 signing key and its device
  bearer token.
- **Server ping-signing key:** the hub's Ed25519 public key, captured by the
  device at pairing, used to authenticate every request.

## Trust boundaries and assumptions

- The **device** is trusted. Secrets are decrypted and sealed there; a fully
  compromised device is out of scope (see Residual risks).
- The **hub** is semi-trusted: relied upon for availability and correct routing,
  but **not** trusted to read, forge, or substitute secrets. The design assumes
  the hub may be curious or partially compromised and still must not learn a
  secret.
- The **login worker** is trusted with exactly the one secret sealed to it for one
  login, for the lifetime of that login.
- The **network** between device and hub is untrusted; an active on-path attacker
  is assumed.

## Design guarantees

### 1. No durable secret at rest off the device

Durable secrets are stored only in the device's OS keychain / keystore. Nothing is
written to the hub or any server at rest. This is the load-bearing custody
property.

### 2. No durable secret ever transits

TOTP seeds and mailbox tokens **never cross the wire**. The device generates the
TOTP code locally from the seed, and reads the email/SMS one-time code locally
from the mailbox or message, then relays only the resulting **code**. The only
durable value that ever transits is the password, and only inside the sealed
envelope below, held in memory and discarded after the login.

### 3. End-to-end sealing to the worker (the hub cannot read durable secrets)

> Scope note: this section covers passwords, usernames, and app-generated (TOTP)
> codes. SMS and manually typed one-time codes do NOT ride the sealed lane — they
> are POSTed to the hub over TLS so it can route them to the waiting login, so the
> hub does see those short-lived codes.

Each released secret is sealed with **HPKE (RFC 9180 base mode:
DHKEM-X25519-HKDF-SHA256 + HKDF-SHA256 + AES-256-GCM)** to the login worker's
**per-login ephemeral public key**, which rides in the request. Only that worker
process holds the matching private key, so no intermediary hop — hub, gateway, or
load balancer — ever sees plaintext. The seal is independent of the transport
channel: even a fully compromised hub forwards ciphertext it cannot open.

### 4. Authenticated requests (no key substitution)

The request (`SecretPing`) is **Ed25519-signed by the server**, and the signature
covers the worker's ephemeral public key. The device verifies this signature
against the server public key it captured at pairing **before** it touches the
credential store. An on-path attacker that rewrites the worker key to its own is
detected here, and the request is declined outright — it never causes a secret to
be loaded or sealed.
There is deliberately **no** "unsigned requests still accepted" fallback; a device
that holds no server public key declines every request until it re-pairs.

### 5. Trust-on-first-use pairing binding

The server's public key is delivered over the pairing HTTPS response, which an
attacker could in principle MITM. To close that, a fingerprint of the server key
is bound into the **pairing code itself**, which travels the clean
browser-to-human-to-device channel the network attacker is not on. The device
recomputes the fingerprint over the key it received and rejects on mismatch,
using a constant-time comparison. This also blocks a downgrade that strips the key
to force a keyless lane. The pairing code is single-use, TTL-bounded, and
rate-limited server-side; its guessing entropy is a 128-bit random token.

### 6. Transport confidentiality is enforced, not assumed

The relay channel **must** be `wss://` (TLS). The client validates the hub URL and
refuses to connect over cleartext `ws://` to any non-loopback host, because the
connection carries the device bearer token and the sealed releases. A loopback
`ws://` is tolerated only for local development.

### 7. Release authorization is cryptographic and automatic (frictionless by design)

Every release is gated by the **server signature**: it only happens for a ping
carrying a valid signature from the hub key pinned at pairing (see §8 and "What
the hub cannot do"), so no party without that key can trigger a release at all.

That gate is enforced **before** any per-release user interaction, and there is
none: on **every** platform (iOS, macOS, Android) a cryptographically-verified
ping is authorized **automatically, with no per-release tap or biometric prompt**.
This is deliberate — the product is autonomous login for AI agents, which must be
hands-free (logins complete while the app stays closed); a per-release human tap
would defeat that. A verified ping is authorized by construction. See "Residual
risks" for the trade this makes. The only exception is the headless desktop
binary, which has no relay app and therefore declines every request (fails
closed).

### 8. Anti-replay

Requests carry a single-use `request_id`, a fresh server challenge (nonce), and a
TTL. The device Ed25519-signs the `(request_id, challenge, worker key, sealed
secret)` tuple with distinct domain-separated, length-prefixed encodings, so a
ping signature can never be replayed as a release signature. The device also keeps
a daemon-lifetime dedup set of seen `request_id`s (surviving reconnects), so a
replayed request is declined before it can release a secret a second time.

### 9. Inventory reveals domains only

When the hub asks which sites a device can serve, the reply is a **list of domains
only** — never a credential. The hub learns "this device can help with site X,"
never the login itself.

## What the hub can and cannot do

**The hub CAN:**

- See which device is connected, and route requests to it.
- Learn the set of domains a device holds a login for (via the inventory query).
- Observe timing and metadata: when a login happened, for which site, and that a
  release occurred.
- Deny service: refuse to route, or send requests the device will simply decline.

**The hub CANNOT:**

- Read any secret. Releases are HPKE-sealed to the worker; the hub only forwards
  ciphertext.
- Forge a request the device will honor. Requests must carry a valid server
  signature; the device rejects the rest.
- Substitute its own recipient key to harvest a secret. The signature covers the
  worker key, and the pairing binding pins the signing key.
- Extract a durable second-factor secret. TOTP seeds and mailbox tokens never
  leave the device.
- Release a secret without a validly-signed request, or replay one to get a
  secret twice. (A validly-signed ping IS auto-approved on every platform, by
  design — there is no per-release user tap anywhere; see Residual risks.)

## Residual risks and out of scope

These are real limitations. The design does not claim to defend against them.

- **Compromised device.** If your device is compromised (malware with keychain
  access, a jailbroken/rooted phone with your unlock, a coerced device unlock),
  the attacker can reach the same secrets you can. Device security is assumed, not
  provided.
- **Stolen hub signing key.** Releases are authorized automatically on every
  platform once a ping carries a valid hub signature. An attacker who both steals
  the hub's Ed25519 signing key *and* can deliver pings to your paired device
  (e.g. a fully compromised hub) can therefore make the device release secrets with
  no user interaction. This is the deliberate trade for hands-free, autonomous
  operation: the design has no per-release human tap to fall back on, so the hub
  signing key is the single most sensitive server-side secret — protect it
  accordingly (HSM/KMS custody, tight rotation). An integrator who wants a
  human-in-the-loop checkpoint can supply an interactive approver in the native
  shell (see `AutoApprover`).
- **Post-fill DOM exposure.** After the worker fills a secret into the target
  site's login form, the secret sits in a page field the login runtime can
  observe. The cryptographic path ends at fill; this exposure is **inherent** to
  any autofill topology and is mitigated operationally (in-memory-only handling,
  redaction, and submit-and-clear), not cryptographically. Do not assume the
  sealing covers post-fill observation.
- **Malicious or buggy login worker.** A worker is trusted with the one secret
  sealed to it. A compromised worker can misuse that single secret for that login.
  The blast radius is bounded to the single secret sealed per login (one secret,
  one login) during the compromise window; nothing at rest is exposed, and device
  revocation is the backstop.
- **Supply chain of the client and shells.** A malicious build, dependency, or
  store distribution of the app could subvert the on-device guarantees. Mitigation
  is signed/notarized builds, build reproducibility, and update-channel integrity
  — process controls outside the cryptographic core.
- **One-time-code delivery outside the app.** SMS and email codes are delivered by
  third parties before the device reads them; interception upstream (SIM swap,
  mailbox compromise) is outside this model. Neither platform permits silent
  third-party OTP reading on iOS, so capture there is user-visible. On **Android**
  this app holds `RECEIVE_SMS` and reads matching texts silently in the background
  (gated on an explicit opt-in, and only while a sign-in is waiting), so capture is
  NOT individually user-visible on that platform. Manual entry is always available
  as the reliability floor.
- **Metadata to the hub.** As noted above, the hub necessarily learns login
  timing, site, and the device's served domains. This is not a confidentiality
  leak of secrets, but it is observable activity.
- **Hub availability.** The hub is a routing dependency. If it is down, logins that
  need a relayed secret cannot complete; this is an availability property, not a
  confidentiality one.

## Cryptographic summary

| Purpose | Mechanism |
|---|---|
| End-to-end secret seal | HPKE, RFC 9180 base mode: DHKEM-X25519-HKDF-SHA256 + HKDF-SHA256 + AES-256-GCM, to the worker's per-login ephemeral key |
| Request authentication | Ed25519 signature by the server over the request (covers the worker key), verified against the pairing-captured server key |
| Release authentication / anti-replay | Ed25519 signature by the device over a domain-separated, length-prefixed `(request_id, challenge, worker key, sealed secret)` tuple; single-use `request_id`; TTL; on-device dedup |
| Pairing key binding | Server-key fingerprint bound into the single-use pairing code (trust-on-first-use over the human channel), constant-time verified device-side |
| Transport | Mandatory `wss://` (TLS); cleartext refused to non-loopback hosts |
| Second factors | On-device TOTP (RFC 6238) and on-device email/SMS code extraction; seeds and tokens never transit |
