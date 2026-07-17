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

- The **only network peer** the app talks to is the hub you configure
  (`your-hub.example`). There is no other backend.
- Per login, at most a single password or a short-lived one-time code crosses the
  wire, and only inside an HPKE-sealed envelope the hub cannot open.
- When asked, the device tells the hub which **domains** it can serve a login for
  — domains only, never a credential.

## No third-party tracking

Auto-Login contains **no analytics, advertising, or crash-reporting SDKs**. It does
not profile you, and it does not phone home to anyone but your configured hub.

## SMS and email one-time codes

When the app helps with an SMS or email one-time code, it extracts **only the code**
on your device and forwards only that code — never the message body, sender, or any
other content. Codes are consumed and forgotten; they are not retained.

- **Email:** the mailbox credential stays on the device; the app reads the message
  locally, pulls out the code, and relays the code.
- **SMS:** capture is user-visible on both platforms — Android uses the system SMS
  User Consent sheet (no blanket SMS permission), and iOS relies on your own
  Shortcuts automation forwarding a single message. Manual code entry is always
  available.

## Your control

Everything the app holds lives on your device. Removing a stored login, resetting
the device, or uninstalling the app removes that data from the only place it
exists.
