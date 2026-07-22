package relay

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hpke"
	"encoding/hex"
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/store"
)

// newWorkerKey mimics the server minting a per-login recipient key; returns
// (pubBytes, open func) so the test can decrypt what the client seals.
func newWorkerKey(t *testing.T) (pub []byte, open func(info, ct []byte) ([]byte, error)) {
	t.Helper()
	kem := hpke.DHKEM(ecdh.X25519())
	kp, err := kem.GenerateKey()
	if err != nil {
		t.Fatalf("gen worker key: %v", err)
	}
	open = func(info, ct []byte) ([]byte, error) {
		return hpke.Open(kp, hpke.HKDFSHA256(), hpke.AES256GCM(), info, ct)
	}
	return kp.PublicKey().Bytes(), open
}

func newEd25519(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("gen ed25519 key: %v", err)
	}
	return pub, priv
}

// signPing is the SERVER side: sign the canonical ping message and attach it.
func signPing(serverPriv ed25519.PrivateKey, ping protocol.SecretPing) protocol.SecretPing {
	signed := ping
	signed.ServerSignature = ed25519.Sign(serverPriv, PingSigningMessage(ping))
	return signed
}

// basePing is a well-formed (as yet UNSIGNED) ping for worker key pub.
func basePing(pub []byte) protocol.SecretPing {
	return protocol.SecretPing{
		RequestID:             "req-42",
		Site:                  "instacart.com",
		SecretKind:            protocol.SecretPassword,
		WorkerEphemeralPubkey: pub,
		Challenge:             []byte("server-nonce-xyz"),
		TTLSeconds:            30,
	}
}

func TestResolveSecret(t *testing.T) {
	rec := store.Record{
		Site: "s.com", Username: "john", Password: "pw",
	}
	if v, err := ResolveSecret(rec, protocol.SecretUsername, ""); err != nil || v != "john" {
		t.Errorf("username: %q %v", v, err)
	}
	if v, err := ResolveSecret(rec, protocol.SecretPassword, ""); err != nil || v != "pw" {
		t.Errorf("password: %q %v", v, err)
	}
	if v, err := ResolveSecret(rec, protocol.SecretManualCode, "482913"); err != nil || v != "482913" {
		t.Errorf("manual: %q %v", v, err)
	}
	if _, err := ResolveSecret(rec, protocol.SecretEmailOTPCode, ""); err != ErrManualCodeRequired {
		t.Errorf("email w/o code: want ErrManualCodeRequired, got %v", err)
	}
	if _, err := ResolveSecret(store.Record{Site: "s"}, protocol.SecretPassword, ""); err == nil {
		t.Error("password missing: want error")
	}
}

// End-to-end client core: build a release for a server-signed ping, prove (a)
// the worker can open the sealed secret and it equals what we resolved, and (b)
// the device signature verifies against the device public key.
func TestBuildReleaseRoundTrip(t *testing.T) {
	pub, open := newWorkerKey(t)
	devPub, devPriv := newEd25519(t)
	srvPub, srvPriv := newEd25519(t)

	ping := signPing(srvPriv, basePing(pub))
	if !VerifyPingSignature(srvPub, ping) {
		t.Fatal("VerifyPingSignature failed on a correctly signed ping")
	}
	secret := "correct-horse-battery"
	rel, err := BuildRelease(ping, secret, devPriv, srvPub)
	if err != nil {
		t.Fatalf("BuildRelease: %v", err)
	}
	// (a) worker opens the sealed secret
	info := protocol.SealInfo(ping.RequestID, ping.Site, ping.SecretKind)
	got, err := open(info, rel.SealedSecret)
	if err != nil {
		t.Fatalf("worker open: %v", err)
	}
	if string(got) != secret {
		t.Fatalf("opened %q, want %q", got, secret)
	}
	// (b) signature verifies
	if !VerifyRelease(rel, ping, devPub) {
		t.Fatal("VerifyRelease failed for a valid release")
	}
}

// THE ATTACK. An active on-path party takes a validly server-signed ping
// and swaps in its OWN X25519 key, leaving the server signature untouched. Before
// this fix the device happily sealed the real credential to the attacker's key.
// Now it must decline, and must not seal anything at all.
func TestBuildReleaseRejectsSubstitutedWorkerKey(t *testing.T) {
	honestPub, _ := newWorkerKey(t)
	attackerPub, attackerOpen := newWorkerKey(t)
	_, devPriv := newEd25519(t)
	srvPub, srvPriv := newEd25519(t)

	signed := signPing(srvPriv, basePing(honestPub)) // server signed the HONEST key
	tampered := signed
	tampered.WorkerEphemeralPubkey = attackerPub // ... attacker rewrites it in flight

	rel, err := BuildRelease(tampered, "correct-horse-battery", devPriv, srvPub)
	if !errors.Is(err, ErrBadServerSignature) {
		t.Fatalf("substituted worker key: want ErrBadServerSignature, got %v", err)
	}
	if !reflect.DeepEqual(rel, protocol.SecretRelease{}) {
		t.Fatalf("device produced a release for a substituted key: %+v", rel)
	}
	// Nothing was sealed, so there is nothing the attacker's key could open.
	if len(rel.SealedSecret) != 0 {
		info := protocol.SealInfo(tampered.RequestID, tampered.Site, tampered.SecretKind)
		if pt, err := attackerOpen(info, rel.SealedSecret); err == nil {
			t.Fatalf("attacker opened the credential: %q", pt)
		}
	}
}

// Every field the ping signature covers must break the signature when tampered,
// and the device must decline (and seal nothing) in every case.
func TestBuildReleaseFailsClosedOnUnauthenticatedPing(t *testing.T) {
	honestPub, _ := newWorkerKey(t)
	attackerPub, _ := newWorkerKey(t)
	_, devPriv := newEd25519(t)
	srvPub, srvPriv := newEd25519(t)
	_, otherSrvPriv := newEd25519(t)

	cases := []struct {
		name    string
		ping    func() protocol.SecretPing // produced from a validly signed ping
		serverP ed25519.PublicKey
		wantErr error
	}{
		{
			name: "worker key substituted (the attack)",
			ping: func() protocol.SecretPing {
				p := signPing(srvPriv, basePing(honestPub))
				p.WorkerEphemeralPubkey = attackerPub
				return p
			},
			serverP: srvPub,
			wantErr: ErrBadServerSignature,
		},
		{
			name:    "no server signature at all",
			ping:    func() protocol.SecretPing { return basePing(honestPub) }, // unsigned
			serverP: srvPub,
			wantErr: ErrUnsignedPing,
		},
		{
			name:    "signed by the wrong server key",
			ping:    func() protocol.SecretPing { return signPing(otherSrvPriv, basePing(honestPub)) },
			serverP: srvPub,
			wantErr: ErrBadServerSignature,
		},
		{
			name: "garbage signature of the right length",
			ping: func() protocol.SecretPing {
				p := signPing(srvPriv, basePing(honestPub))
				p.ServerSignature[0] ^= 0xff
				return p
			},
			serverP: srvPub,
			wantErr: ErrBadServerSignature,
		},
		{
			name:    "device holds no server key (paired pre-v2)",
			ping:    func() protocol.SecretPing { return signPing(srvPriv, basePing(honestPub)) },
			serverP: nil,
			wantErr: ErrNoServerKey,
		},
		{
			name:    "device server key is short/corrupt",
			ping:    func() protocol.SecretPing { return signPing(srvPriv, basePing(honestPub)) },
			serverP: ed25519.PublicKey("short"),
			wantErr: ErrNoServerKey,
		},
		{
			name: "request_id tampered",
			ping: func() protocol.SecretPing {
				p := signPing(srvPriv, basePing(honestPub))
				p.RequestID = "req-99"
				return p
			},
			serverP: srvPub,
			wantErr: ErrBadServerSignature,
		},
		{
			name:    "site tampered",
			ping:    func() protocol.SecretPing { p := signPing(srvPriv, basePing(honestPub)); p.Site = "evil.com"; return p },
			serverP: srvPub,
			wantErr: ErrBadServerSignature,
		},
		{
			name: "secret_kind tampered (to another valid kind)",
			ping: func() protocol.SecretPing {
				p := signPing(srvPriv, basePing(honestPub))
				p.SecretKind = protocol.SecretUsername
				return p
			},
			serverP: srvPub,
			wantErr: ErrBadServerSignature,
		},
		{
			name: "challenge tampered",
			ping: func() protocol.SecretPing {
				p := signPing(srvPriv, basePing(honestPub))
				p.Challenge = []byte("other-nonce")
				return p
			},
			serverP: srvPub,
			wantErr: ErrBadServerSignature,
		},
		{
			name: "ttl_seconds tampered",
			ping: func() protocol.SecretPing {
				p := signPing(srvPriv, basePing(honestPub))
				p.TTLSeconds = 86400
				return p
			},
			serverP: srvPub,
			wantErr: ErrBadServerSignature,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ping := tc.ping()
			rel, err := BuildRelease(ping, "correct-horse-battery", devPriv, tc.serverP)
			if !errors.Is(err, tc.wantErr) {
				t.Fatalf("want %v, got %v", tc.wantErr, err)
			}
			if !reflect.DeepEqual(rel, protocol.SecretRelease{}) {
				t.Fatalf("declined ping still produced a release: %+v", rel)
			}
			if VerifyPingSignature(tc.serverP, ping) {
				t.Fatal("VerifyPingSignature accepted a ping BuildRelease declined")
			}
		})
	}
}

func TestVerifyPingSignature(t *testing.T) {
	pub, _ := newWorkerKey(t)
	srvPub, srvPriv := newEd25519(t)
	otherPub, _ := newEd25519(t)
	signed := signPing(srvPriv, basePing(pub))

	if !VerifyPingSignature(srvPub, signed) {
		t.Error("valid ping: want true")
	}
	if VerifyPingSignature(otherPub, signed) {
		t.Error("wrong server key: want false")
	}
	if VerifyPingSignature(ed25519.PublicKey("short"), signed) {
		t.Error("short server key: want false")
	}
	if VerifyPingSignature(nil, signed) {
		t.Error("nil server key: want false")
	}
	unsigned := basePing(pub)
	if VerifyPingSignature(srvPub, unsigned) {
		t.Error("unsigned ping: want false")
	}
	shortSig := signed
	shortSig.ServerSignature = signed.ServerSignature[:10]
	if VerifyPingSignature(srvPub, shortSig) {
		t.Error("truncated signature: want false")
	}
}

func TestVerifyRejectsTampering(t *testing.T) {
	pub, _ := newWorkerKey(t)
	devPub, devPriv := newEd25519(t)
	srvPub, srvPriv := newEd25519(t)
	ping := signPing(srvPriv, basePing(pub))

	rel, err := BuildRelease(ping, "secret", devPriv, srvPub)
	if err != nil {
		t.Fatal(err)
	}
	// tamper the sealed secret
	bad := rel
	bad.SealedSecret = append([]byte{}, rel.SealedSecret...)
	bad.SealedSecret[0] ^= 0xff
	if VerifyRelease(bad, ping, devPub) {
		t.Error("verify passed on tampered sealed secret")
	}
	// replay under a different challenge must fail
	otherPing := ping
	otherPing.Challenge = []byte("different-nonce")
	if VerifyRelease(rel, otherPing, devPub) {
		t.Error("verify passed under a different challenge (replay)")
	}
	// wrong device key must fail
	otherPub, _ := newEd25519(t)
	if VerifyRelease(rel, otherPing, otherPub) {
		t.Error("verify passed under wrong device key")
	}
	// missing / short device key must fail (and not panic)
	if VerifyRelease(rel, ping, ed25519.PublicKey("short")) {
		t.Error("verify passed under a short device key")
	}
}

// The release signature now covers the worker key, so the server can detect a
// swap in the OTHER direction: a release built for worker key A must not verify
// against a ping that claims worker key B.
func TestVerifyReleaseBindsWorkerKey(t *testing.T) {
	pubA, _ := newWorkerKey(t)
	pubB, _ := newWorkerKey(t)
	devPub, devPriv := newEd25519(t)
	srvPub, srvPriv := newEd25519(t)

	pingA := signPing(srvPriv, basePing(pubA))
	rel, err := BuildRelease(pingA, "secret", devPriv, srvPub)
	if err != nil {
		t.Fatal(err)
	}
	if !VerifyRelease(rel, pingA, devPub) {
		t.Fatal("release must verify against its own ping")
	}
	pingB := pingA
	pingB.WorkerEphemeralPubkey = pubB // same request_id + challenge, different recipient
	if VerifyRelease(rel, pingB, devPub) {
		t.Error("release for worker key A verified against a ping claiming worker key B")
	}
}

func TestBuildReleaseValidates(t *testing.T) {
	pub, _ := newWorkerKey(t)
	_, devPriv := newEd25519(t)
	srvPub, srvPriv := newEd25519(t)
	good := signPing(srvPriv, basePing(pub))

	if _, err := BuildRelease(good, "", devPriv, srvPub); err == nil {
		t.Error("empty secret: want error")
	}
	if _, err := BuildRelease(good, "s", ed25519.PrivateKey("short"), srvPub); err == nil {
		t.Error("bad device key: want error")
	}

	// Structural validation still applies, and now includes the signature.
	for _, tc := range []struct {
		name string
		mut  func(p *protocol.SecretPing)
	}{
		{"no request_id", func(p *protocol.SecretPing) { p.RequestID = "" }},
		{"unknown secret_kind", func(p *protocol.SecretPing) { p.SecretKind = protocol.SecretKind("not_a_kind") }},
		{"no worker key", func(p *protocol.SecretPing) { p.WorkerEphemeralPubkey = nil }},
		{"no challenge", func(p *protocol.SecretPing) { p.Challenge = nil }},
		{"no server signature", func(p *protocol.SecretPing) { p.ServerSignature = nil }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			bad := good
			tc.mut(&bad)
			rel, err := BuildRelease(bad, "s", devPriv, srvPub)
			if err == nil {
				t.Fatal("want error")
			}
			if !reflect.DeepEqual(rel, protocol.SecretRelease{}) {
				t.Fatalf("invalid ping still produced a release: %+v", rel)
			}
		})
	}
}

// WHY we length-prefix instead of joining with 0x1f: an attacker controls the
// worker pubkey bytes, so under a separator-joined encoding they could shift the
// field boundary and make one signature valid for a DIFFERENT (challenge, key)
// tuple. These two tuples collide under a naive 0x1f join; they must not collide
// under the canonical encoding.
func TestSigningMessagesAreUnambiguous(t *testing.T) {
	// naive: "A" 0x1f "B\x1fC" == "A\x1fB" 0x1f "C"
	chalA, keyA := []byte("A"), []byte("B\x1fC")
	chalB, keyB := []byte("A\x1fB"), []byte("C")
	if !bytes.Equal(naiveJoin(chalA, keyA), naiveJoin(chalB, keyB)) {
		t.Fatal("test premise broken: the two tuples should collide under a 0x1f join")
	}

	pingA := protocol.SecretPing{RequestID: "r", Site: "s", SecretKind: protocol.SecretPassword, WorkerEphemeralPubkey: keyA, Challenge: chalA, TTLSeconds: 30}
	pingB := pingA
	pingB.WorkerEphemeralPubkey, pingB.Challenge = keyB, chalB
	if bytes.Equal(PingSigningMessage(pingA), PingSigningMessage(pingB)) {
		t.Error("PingSigningMessage collides across distinct field tuples")
	}
	if bytes.Equal(
		ReleaseSigningMessage("r", chalA, keyA, []byte("sealed")),
		ReleaseSigningMessage("r", chalB, keyB, []byte("sealed")),
	) {
		t.Error("ReleaseSigningMessage collides across distinct field tuples")
	}

	// Adjacent string fields must not be shiftable either.
	p1 := protocol.SecretPing{RequestID: "ab", Site: "c", SecretKind: protocol.SecretPassword, WorkerEphemeralPubkey: keyA, Challenge: chalA, TTLSeconds: 1}
	p2 := p1
	p2.RequestID, p2.Site = "a", "bc"
	if bytes.Equal(PingSigningMessage(p1), PingSigningMessage(p2)) {
		t.Error("PingSigningMessage collides across a request_id/site boundary shift")
	}

	// The domain-separation tags keep the two messages disjoint: a ping signature
	// can never be replayed as a release signature.
	sameFields := ReleaseSigningMessage(pingA.RequestID, pingA.Challenge, pingA.WorkerEphemeralPubkey, []byte("x"))
	if bytes.Equal(PingSigningMessage(pingA), sameFields) {
		t.Error("ping and release signing messages are not domain-separated")
	}
	if !bytes.HasPrefix(PingSigningMessage(pingA), append([]byte{0, 0, 0, byte(len(pingDomainTag))}, pingDomainTag...)) {
		t.Error("ping message does not start with enc(ping domain tag)")
	}
	if !bytes.HasPrefix(sameFields, append([]byte{0, 0, 0, byte(len(releaseDomainTag))}, releaseDomainTag...)) {
		t.Error("release message does not start with enc(release domain tag)")
	}
}

// naiveJoin is the OLD, ambiguous encoding — kept only to prove the collision the
// canonical encoding closes.
func naiveJoin(fields ...[]byte) []byte {
	return bytes.Join(fields, []byte{0x1f})
}

// GOLDEN VECTOR. the server's device-relay package is an independent implementation
// of the same spec; these exact bytes are the contract between them. If this test
// fails, the wire encoding changed and BOTH sides plus ProtocolVersion must move.
func TestSigningMessageGoldenVectors(t *testing.T) {
	ping := protocol.SecretPing{
		RequestID:             "req-1",
		Site:                  "s.com",
		SecretKind:            protocol.SecretPassword,
		WorkerEphemeralPubkey: []byte{0x01, 0x02, 0x03},
		Challenge:             []byte{0xaa, 0xbb},
		TTLSeconds:            30,
	}
	wantPing := unhex(t, strings.Join([]string{
		"0000001c", "72696e646c65722d6465766963652d72656c61792f70696e672f7632", // enc("rindler-device-relay/ping/v2")
		"00000005", "7265712d31", // enc("req-1")
		"00000005", "732e636f6d", // enc("s.com")
		"00000008", "70617373776f7264", // enc("password")
		"00000003", "010203", // enc(worker_ephemeral_pubkey)
		"00000002", "aabb", // enc(challenge)
		"00000002", "3330", // enc("30")
	}, ""))
	if got := PingSigningMessage(ping); !bytes.Equal(got, wantPing) {
		t.Errorf("PingSigningMessage golden mismatch:\n got %x\nwant %x", got, wantPing)
	}

	wantRelease := unhex(t, strings.Join([]string{
		"0000001f", "72696e646c65722d6465766963652d72656c61792f72656c656173652f7632", // enc("rindler-device-relay/release/v2")
		"00000005", "7265712d31", // enc("req-1")
		"00000002", "aabb", // enc(challenge)
		"00000003", "010203", // enc(worker_ephemeral_pubkey)
		"00000004", "deadbeef", // enc(sealed_secret)
	}, ""))
	got := ReleaseSigningMessage(ping.RequestID, ping.Challenge, ping.WorkerEphemeralPubkey, []byte{0xde, 0xad, 0xbe, 0xef})
	if !bytes.Equal(got, wantRelease) {
		t.Errorf("ReleaseSigningMessage golden mismatch:\n got %x\nwant %x", got, wantRelease)
	}
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad golden hex: %v", err)
	}
	return b
}

// GOLDEN VECTOR (pairing-channel TOFU, follow-up). PairingFingerprint is an
// independent re-implementation of the server's devicerelay.PairingFingerprint;
// these EXACT hex values are the cross-language contract. The device embeds this
// fingerprint in a pairing code and the server mints codes against the same bytes,
// so a one-sided edit to the encoding turns this red on ONE side and the two stop
// agreeing. Keep byte-identical to the server's golden test.
func TestPairingFingerprint_GoldenVector(t *testing.T) {
	// Keyed lane: server_pubkey = 32 bytes {1, 2, …, 32}.
	keyed := make(ed25519.PublicKey, ed25519.PublicKeySize)
	for i := range keyed {
		keyed[i] = byte(i + 1)
	}
	if got, want := hex.EncodeToString(PairingFingerprint(keyed)), "0f109e9ce4b15ffb09b42256f901c8ee"; got != want {
		t.Errorf("keyed fingerprint = %s, want %s", got, want)
	}
	// Keyless lane: a nil server_pubkey (0 raw bytes).
	if got, want := hex.EncodeToString(PairingFingerprint(nil)), "2c4b6e9960d586e2b25c840d2e62e40e"; got != want {
		t.Errorf("keyless fingerprint = %s, want %s", got, want)
	}
	// The commitment is truncated SHA-256 to PairingFingerprintLen bytes.
	if n := len(PairingFingerprint(nil)); n != PairingFingerprintLen {
		t.Errorf("fingerprint length = %d, want %d", n, PairingFingerprintLen)
	}
}
