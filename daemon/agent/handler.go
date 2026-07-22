// Package agent is the reusable, platform-independent custody core ():
// it keeps one outbound WebSocket to the device hub and answers each
// on-demand SecretPing by relaying exactly one secret — HPKE-sealed to the login
// worker and Ed25519-signed. It wraps the proven, golden-vector-verified
// custody-app library (protocol/relay/store/otp).
//
// BOTH the desktop daemon (package main) and the mobile gomobile binding
// (package mobile) drive this same core, so the relay/crypto/wire protocol
// behaves identically on every platform — the whole point of the "Go shared core
// + thin native shells" strategy. The wire envelope lives here once (agent.go),
// so client and hub can never drift.
//
// This file is the transport-independent heart: given a ping + the device key +
// the local credential store, produce a signed SecretRelease. Fully testable
// without a live hub.
package agent

import (
	"crypto/ed25519"
	"fmt"
	"strings"

	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/relay"
	"github.com/rindler-ai/auto-login/core/store"
)

// handlePing resolves the one secret the ping asks for from the on-device store
// and returns the sealed+signed release. suppliedCode carries a manually-entered
// or out-of-band code (email/SMS/manual kinds); for password it is ignored.
// The plaintext secret exists only for the life of this call.
//
// serverPub is the Ed25519 key provisioned at pairing. The ping is
// authenticated against it FIRST — before the credential store is touched — so a
// forged ping does not even cause a plaintext load, let alone a seal. A zero
// serverPub fails every ping (relay.ErrNoServerKey): that device must re-pair.
// relay.BuildRelease re-verifies at the crypto boundary, so no path can seal to
// an unauthenticated worker key even if a caller forgets to check.
func handlePing(
	ping protocol.SecretPing,
	st store.Store,
	deviceKey ed25519.PrivateKey,
	serverPub ed25519.PublicKey,
	suppliedCode string,
) (protocol.SecretRelease, error) {
	if err := verifyPing(ping, serverPub); err != nil {
		return protocol.SecretRelease{}, err
	}
	rec, err := st.Get(ping.Site)
	if err != nil {
		// The ping's site is a config domain (e.g. "secure.bankofamerica.com") which
		// need not be the exact key the credential was enrolled under (e.g. the apex
		// "bankofamerica.com" the inventory advertises). Fall back to an apex<->
		// subdomain match so a login for one resolves the other, mirroring the
		// server's device-relay auto-detect (domainCovers). Without this the ping is
		// declined "no credential" even though the device holds the login.
		alt, ok := matchStoredSite(st, ping.Site)
		if ok {
			rec, err = st.Get(alt)
		}
		if !ok || err != nil {
			return protocol.SecretRelease{}, fmt.Errorf("custody: no credential for %q: %w", ping.Site, err)
		}
	}
	secret, err := relay.ResolveSecret(rec, ping.SecretKind, suppliedCode)
	if err != nil {
		return protocol.SecretRelease{}, err
	}
	return relay.BuildRelease(ping, secret, deviceKey, serverPub)
}

// verifyPing authenticates a ping against the server key this device was given at
// pairing. It is the ONE place the device decides a ping is genuine, and
// it is deliberately callable before any user prompt and before any store read:
// a ping we already know is forged must never reach the human approver, and must
// never load a plaintext credential.
//
// The returned error names the reason (safe to log — it carries no secret, no key
// material, and no sealed bytes):
//
//	relay.ErrNoServerKey       this device holds no server pubkey (must re-pair)
//	relay.ErrUnsignedPing      the ping carries no server_signature at all
//	relay.ErrBadServerSignature the signature does not verify (e.g. a substituted
//	                            worker_ephemeral_pubkey — the whole point)
//
// There is NO "unsigned pings still allowed" branch. Adding one would reinstate
// the key-substitution attack this exists to close.
func verifyPing(ping protocol.SecretPing, serverPub ed25519.PublicKey) error {
	if len(serverPub) != ed25519.PublicKeySize {
		return relay.ErrNoServerKey
	}
	if len(ping.ServerSignature) == 0 {
		return relay.ErrUnsignedPing
	}
	if !relay.VerifyPingSignature(serverPub, ping) {
		return relay.ErrBadServerSignature
	}
	return nil
}

// matchStoredSite returns a stored site whose domain covers (or is covered by) the
// requested site on a dot boundary (apex "x.com" <-> subdomain "secure.x.com"). It
// is best-effort: a store that cannot enumerate (ListSites errors) yields no match.
func matchStoredSite(st store.Store, site string) (string, bool) {
	sites, err := st.ListSites()
	if err != nil {
		return "", false
	}
	for _, s := range sites {
		if domainCovers(s, site) || domainCovers(site, s) {
			return s, true
		}
	}
	return "", false
}

// domainCovers reports whether `held` covers `want`: equal (case-insensitive), or
// `want` is a dot-boundary subdomain of `held` (held "x.com" covers want
// "secure.x.com"). The dot boundary keeps "notx.com" from matching "x.com".
func domainCovers(held, want string) bool {
	held = strings.ToLower(strings.TrimSpace(held))
	want = strings.ToLower(strings.TrimSpace(want))
	if held == "" || want == "" {
		return false
	}
	return held == want || strings.HasSuffix(want, "."+held)
}
