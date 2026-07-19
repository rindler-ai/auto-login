# Privacy

Auto-Login is built around one principle: **your secrets stay on your device.**

## What is stored, and where

- Your site credentials, TOTP seeds, and mailbox/OTP tokens are stored **only on
  your own device**, in the OS keychain / keystore. They are never stored, logged,
  or persisted on any server.
- Long-lived second-factor material (TOTP seeds, mailbox tokens) **never leaves the
  device**. The device generates the one-time code locally and relays only the
  code.

## What crosses the network

- Your **credentials** only ever go to the hub you configure (`your-hub.example`).
  There is no other credential backend.
- The app also fetches **site icons** from Google's and DuckDuckGo's public favicon
  services, one request per saved login. Those services therefore see the **domains**
  you have saved (never a credential, username, or code) and your IP address. If you
  would rather not disclose that, the app falls back to a lettered placeholder icon
  when the fetch fails, and the request can be removed outright — see `SiteFavicon`.
- Per login, at most a single password or a short-lived one-time code crosses the
  wire. Passwords and app-generated codes travel inside an HPKE-sealed envelope the
  hub cannot open. **SMS and manually typed codes are the exception**: they are sent
  to the hub over TLS so it can hand them to the login that is waiting, which means
  the hub does see those short-lived codes. It never sees your durable credentials.
- When asked, the device tells the hub which **domains** it can serve a login for
  — domains only, never a credential.

## No third-party tracking

Auto-Login contains **no analytics, advertising, or crash-reporting SDKs**. It does
not profile you. Besides your configured hub, the only other hosts it contacts are
the favicon services noted above.

## SMS and email one-time codes

When the app helps with an SMS or email one-time code, it extracts **only the code**
on your device and forwards only that code — never the message body, sender, or any
other content. Codes are consumed and forgotten; they are not retained.

- **Email:** the mailbox credential stays on the device; the app reads the message
  locally, pulls out the code, and relays the code.
- **SMS (Android):** this is an explicit opt-in that requests the `RECEIVE_SMS`
  permission. Once granted, the app reads incoming texts **silently in the
  background** — there is no per-message prompt. (An earlier build used the system
  SMS User Consent sheet; it was reverted because it cannot work while the app is
  closed.) The receiver stays inert unless the opt-in is on, and it only reads a
  message while a sign-in is actively waiting for a code; only the extracted code
  leaves the phone.
- **SMS (iOS):** relies on your own Shortcuts automation forwarding a single message.
- Manual code entry is always available on both.

## Your control

Everything the app holds lives on your device. Removing a stored login, resetting
the device, or uninstalling the app removes that data from the only place it
exists.
