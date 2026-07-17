// AutoApprover — the custody relay releases a credential to any ping that PASSES
// verification, with NO human tap and NO setting. That IS the product: the user binds
// their Rin account, saves their logins (encrypted on save), closes the app, and the
// phone then serves the right agent hands-free — forever, without ever reopening it.
//
// There is deliberately no toggle. A verified request is authorized by construction; a
// human tap adds nothing a person could actually vet (the prompt shows only site + kind)
// and would break the "never reopen the app" guarantee. Approval is unconditional
// because every gate that matters has ALREADY run in agent.serveOne BEFORE this is
// consulted, and BuildRelease re-checks at the crypto boundary:
//
//   1. verifyPing (app verifies the SERVER) — the ping carries the server's Ed25519
//      signature over the worker's ephemeral public key. Only the hub's
//      DEVICE_RELAY_SIGNING_KEY can produce it, so a forged or on-path-substituted ping
//      is refused before this approver ever runs. Right server, right agent-key binding.
//   2. Server verifies the APP — the hub only dispatches a ping to a device whose bearer
//      token authenticated its connection (AuthenticateDevice -> the device row for this
//      Rin account); a revoked/unknown device is never pinged. Right, live device.
//   3. Account scope — this device holds ONLY its own Rin account's logins, so it can
//      only ever release the correct account's credential.
//   4. Site match — handlePing serves only the credential enrolled for the ping's Site.
//   5. Replay guard — a request_id is answered at most once.
//   6. HPKE seal — BuildRelease seals the secret to the worker's per-login ephemeral
//      key, so only that one agent can open it; the plaintext is decrypted only at the
//      moment it is typed into the field, and never persisted.

package ai.rindler.autologin

import ai.rindler.mobile.Approver

internal object AutoApprover : Approver {
    override fun approve(site: String, kind: String): Boolean = true
}
