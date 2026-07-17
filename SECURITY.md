# Security Policy

## Reporting a vulnerability

Please report security vulnerabilities **privately**. Do not open a public GitHub
issue, pull request, or discussion for a suspected vulnerability — public
disclosure before a fix puts users at risk.

Use either channel:

- **GitHub private vulnerability reporting** — go to the repository's **Security**
  tab and choose **Report a vulnerability**. This opens a private advisory visible
  only to the maintainers.
- **Email** — [security@rindler.ai](mailto:security@rindler.ai). PGP-encrypted
  reports are welcome; ask for a key if you need one.

Please include enough detail to reproduce: affected component (`core/`, `daemon/`,
a shell, or the wire contract), version or commit, and a proof of concept or
clear reproduction steps.

## What to expect

- **Acknowledgement within 72 hours** of your report.
- An initial assessment and, where applicable, a severity rating and remediation
  plan after triage.
- Coordinated disclosure: we will agree a disclosure timeline with you and credit
  you in the advisory unless you prefer to remain anonymous.

## Supported versions

Security fixes are provided for the **latest released version** on the default
branch. Older versions are not maintained; please upgrade to receive fixes.

## Scope

In scope: the credential custody core, the daemon and its gomobile bridge, the
native shells, and the wire contract in this repository. The server-side hub is
operated separately by whoever deploys it and is out of scope for this repository;
report hub issues to the operator of the hub you connect to.
