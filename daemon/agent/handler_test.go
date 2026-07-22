package agent

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hpke"
	"errors"
	"testing"

	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/relay"
	"github.com/rindler-ai/auto-login/core/store"
)

// workerKey mimics the login worker minting a per-login HPKE recipient; returns
// the pubkey for the ping + an opener for the sealed release.
func workerKey(t *testing.T) (pub []byte, open func(info, ct []byte) ([]byte, error)) {
	t.Helper()
	kp, err := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	if err != nil {
		t.Fatalf("worker key: %v", err)
	}
	return kp.PublicKey().Bytes(), func(info, ct []byte) ([]byte, error) {
		return hpke.Open(kp, hpke.HKDFSHA256(), hpke.AES256GCM(), info, ct)
	}
}

func devicePair(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	return pub, priv
}

// serverKey mimics the hub's device-relay signing key. The server signs
// every ping; the device verifies with the PUBLIC half it was handed at pairing.
func serverKey(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	return pub, priv
}

// signPing is the SERVER side: attach a valid signature over the canonical ping
// message. That message covers worker_ephemeral_pubkey, so any later edit of the
// recipient key invalidates it — which is exactly what the attack tests exploit.
func signPing(t *testing.T, serverPriv ed25519.PrivateKey, ping protocol.SecretPing) protocol.SecretPing {
	t.Helper()
	signed := ping
	signed.ServerSignature = ed25519.Sign(serverPriv, relay.PingSigningMessage(ping))
	return signed
}

// The end-to-end daemon core: a ping for a stored password is resolved, sealed,
// and signed; the worker opens the secret and the release signature verifies.
func TestHandlePing_Password(t *testing.T) {
	pub, open := workerKey(t)
	devPub, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	st := store.NewMemStore()
	if err := st.Put(store.Record{Site: "instacart.com", Username: "john", Password: "hunter2"}); err != nil {
		t.Fatal(err)
	}
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "r1", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: pub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})
	rel, err := handlePing(ping, st, devPriv, srvPub, "")
	if err != nil {
		t.Fatalf("handlePing: %v", err)
	}
	// worker opens the sealed secret
	got, err := open(protocol.SealInfo("r1", "instacart.com", protocol.SecretPassword), rel.SealedSecret)
	if err != nil {
		t.Fatalf("worker open: %v", err)
	}
	if string(got) != "hunter2" {
		t.Fatalf("opened %q, want hunter2", got)
	}
	// signature verifies against the device pubkey
	if !relay.VerifyRelease(rel, ping, devPub) {
		t.Fatal("release signature did not verify")
	}
}

// A ping for a config subdomain (secure.bankofamerica.com) resolves a credential
// enrolled under the apex (bankofamerica.com) — the domain the inventory advertises.
// Without the apex<->subdomain fallback the device declines "no credential" even
// though it holds the login (the live BofA device_relay decline, 2026-07-14).
func TestHandlePing_ApexSubdomainMatch(t *testing.T) {
	pub, open := workerKey(t)
	devPub, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	st := store.NewMemStore()
	if err := st.Put(store.Record{Site: "bankofamerica.com", Username: "john", Password: "s3cret"}); err != nil {
		t.Fatal(err)
	}
	const site = "secure.bankofamerica.com" // the config domain the login runs on
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "r-apex", Site: site, SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: pub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})
	rel, err := handlePing(ping, st, devPriv, srvPub, "")
	if err != nil {
		t.Fatalf("handlePing (apex<->subdomain): %v", err)
	}
	got, err := open(protocol.SealInfo("r-apex", site, protocol.SecretPassword), rel.SealedSecret)
	if err != nil {
		t.Fatalf("worker open: %v", err)
	}
	if string(got) != "s3cret" {
		t.Fatalf("opened %q, want s3cret", got)
	}
	if !relay.VerifyRelease(rel, ping, devPub) {
		t.Fatal("release signature did not verify")
	}
	// A genuinely unrelated site still declines (the dot boundary isn't fuzzy).
	bad := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "r-bad", Site: "notbankofamerica.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: pub, Challenge: []byte("n"), TTLSeconds: 30,
	})
	if _, err := handlePing(bad, st, devPriv, srvPub, ""); err == nil {
		t.Fatal("expected a no-credential error for an unrelated site")
	}
}

// THE ATTACK, at the handler boundary: an on-path party rewrites
// worker_ephemeral_pubkey to its own X25519 key. The server's signature covers
// that field, so it no longer verifies — the device errors out having sealed
// NOTHING, and the attacker's key gets no credential. Before the fix, the device
// happily sealed the real password to the attacker.
func TestHandlePing_RejectsSubstitutedWorkerKey(t *testing.T) {
	honestPub, _ := workerKey(t)
	attackerPub, attackerOpen := workerKey(t)
	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)

	st := store.NewMemStore()
	if err := st.Put(store.Record{Site: "instacart.com", Username: "john", Password: "hunter2"}); err != nil {
		t.Fatal(err)
	}
	// The server signs a ping for the HONEST worker key...
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "r-mitm", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: honestPub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})
	// ...and the on-path attacker swaps in its own, keeping the signature.
	ping.WorkerEphemeralPubkey = attackerPub

	rel, err := handlePing(ping, st, devPriv, srvPub, "")
	if !errors.Is(err, relay.ErrBadServerSignature) {
		t.Fatalf("handlePing error = %v, want ErrBadServerSignature", err)
	}
	if len(rel.SealedSecret) != 0 || len(rel.DeviceSignature) != 0 {
		t.Fatal("a rejected ping produced sealed bytes — nothing may be sealed for an unverified worker key")
	}
	// Belt and braces: the attacker's key can open nothing.
	if _, err := attackerOpen(protocol.SealInfo("r-mitm", "instacart.com", protocol.SecretPassword), rel.SealedSecret); err == nil {
		t.Fatal("attacker opened a payload — the credential was sealed to the substituted key")
	}
}

// A device that holds no server public key (paired before this change) can authenticate
// nothing, so it declines every ping — even a perfectly-signed one. No unsigned /
// unverifiable fallback exists, by design.
func TestHandlePing_NoServerKeyDeclines(t *testing.T) {
	pub, _ := workerKey(t)
	_, devPriv := devicePair(t)
	_, srvPriv := serverKey(t)
	st := store.NewMemStore()
	if err := st.Put(store.Record{Site: "instacart.com", Password: "hunter2"}); err != nil {
		t.Fatal(err)
	}
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "r-nokey", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: pub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})
	rel, err := handlePing(ping, st, devPriv, nil, "")
	if !errors.Is(err, relay.ErrNoServerKey) {
		t.Fatalf("handlePing error = %v, want ErrNoServerKey", err)
	}
	if len(rel.SealedSecret) != 0 {
		t.Fatal("a keyless device sealed something")
	}
}

// An unsigned ping is refused outright: pre- servers (and any injected ping)
// carry no server_signature, and there is no path that seals for one.
func TestHandlePing_UnsignedPingDeclines(t *testing.T) {
	pub, _ := workerKey(t)
	_, devPriv := devicePair(t)
	srvPub, _ := serverKey(t)
	st := store.NewMemStore()
	if err := st.Put(store.Record{Site: "instacart.com", Password: "hunter2"}); err != nil {
		t.Fatal(err)
	}
	ping := protocol.SecretPing{ // no ServerSignature
		RequestID: "r-unsigned", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: pub, Challenge: []byte("nonce"), TTLSeconds: 30,
	}
	if _, err := handlePing(ping, st, devPriv, srvPub, ""); !errors.Is(err, relay.ErrUnsignedPing) {
		t.Fatalf("handlePing error = %v, want ErrUnsignedPing", err)
	}
}

// A forged ping must not even cause a plaintext credential load: verification
// happens before the store is touched.
func TestHandlePing_ForgedPingNeverReadsTheStore(t *testing.T) {
	honestPub, _ := workerKey(t)
	attackerPub, _ := workerKey(t)
	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)

	st := store.NewMemStore()
	if err := st.Put(store.Record{Site: "instacart.com", Password: "hunter2"}); err != nil {
		t.Fatal(err)
	}
	tracked := &getCountingStore{Store: st}
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "r-noread", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: honestPub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})
	ping.WorkerEphemeralPubkey = attackerPub

	if _, err := handlePing(ping, tracked, devPriv, srvPub, ""); err == nil {
		t.Fatal("forged ping was served")
	}
	if got := tracked.gets.Load(); got != 0 {
		t.Fatalf("credential store read %d times for a forged ping, want 0", got)
	}
}

// No credential for the pinged site -> a clean error (the daemon declines), and
// the error never carries a secret (there is none).
func TestHandlePing_NoCredential(t *testing.T) {
	pub, _ := workerKey(t)
	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "r3", Site: "unknown.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: pub, Challenge: []byte("n"), TTLSeconds: 30,
	})
	if _, err := handlePing(ping, store.NewMemStore(), devPriv, srvPub, ""); err == nil {
		t.Fatal("expected an error when no credential is stored")
	}
}

// The shipped mobile Approver is boolean-only, so it cannot supply these code
// kinds through the relay protocol yet. They must decline instead of releasing
// an empty value; the separate manual/SMS endpoints are not this ping adapter.
func TestHandlePing_CodeKindsRequireSuppliedCode(t *testing.T) {
	pub, _ := workerKey(t)
	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	st := store.NewMemStore()
	if err := st.Put(store.Record{Site: "site.com", Username: "john", Password: "pw"}); err != nil {
		t.Fatal(err)
	}

	for _, kind := range []protocol.SecretKind{
		protocol.SecretEmailOTPCode,
		protocol.SecretSMSOTPCode,
		protocol.SecretManualCode,
	} {
		t.Run(string(kind), func(t *testing.T) {
			ping := signPing(t, srvPriv, protocol.SecretPing{
				RequestID: "code-" + string(kind), Site: "site.com", SecretKind: kind,
				WorkerEphemeralPubkey: pub, Challenge: []byte("n"), TTLSeconds: 30,
			})
			if _, err := handlePing(ping, st, devPriv, srvPub, ""); !errors.Is(err, relay.ErrManualCodeRequired) {
				t.Fatalf("handlePing error = %v, want ErrManualCodeRequired", err)
			}
		})
	}
}
