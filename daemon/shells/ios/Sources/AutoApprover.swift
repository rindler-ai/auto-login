// AutoApprover — frictionless, autonomous release authorization for iOS/macOS.
//
// The product is autonomous login for AI agents: a paired device releases a
// credential the instant a login worker needs it, with NO per-release human
// interaction. A biometric tap on every release would defeat that — the whole
// point is that logins complete hands-free while the app stays closed.
//
// This is safe because the release is ALREADY gated, cryptographically, before
// the Go core ever calls approve(): the core verifies the SecretPing's Ed25519
// signature against the server key pinned at pairing and rejects any replay (see
// agent + PARITY.md). So a call to approve() means "the paired hub
// cryptographically authorized exactly this release" — it is authorized by
// construction. We therefore always approve. This is the iOS/macOS analog of
// Android's AutoApprover; approval is uniform and automatic on every platform.
//
// Conforms to `MobileApproverProtocol` (gomobile-bound; the ObjC header also
// emits a same-named `MobileApprover` CLASS as the Go-interface wrapper, so
// Swift's Clang importer disambiguates the protocol with a `Protocol` suffix —
// see PARITY.md). The Go core calls approve() from a background goroutine and
// blocks on the boolean; returning immediately keeps releases instant.
//
// Extensibility note (for anyone forking this open-source app): an integrator who
// WANTS an interactive per-release confirmation would return false here until the
// user approves out-of-band (e.g. an LAContext / BiometricPrompt gate). The
// shipped app is intentionally frictionless.

import Foundation
import Custody // for the MobileApproverProtocol protocol

final class AutoApprover: NSObject, MobileApproverProtocol {
    /// site = the login host; kind = "password" / "totp" / etc. Called off the main
    /// thread. Auto-approves: a ping only reaches here after the Go core verified
    /// its server signature and anti-replay, so the release is already authorized.
    func approve(_ site: String?, kind: String?) -> Bool {
        return true
    }
}
