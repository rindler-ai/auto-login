// Package relay is the custody-app client core: given a SecretPing from the
// device hub, VERIFY the server's signature over that ping, resolve exactly the
// one secret it asks for, HPKE-seal it to the worker key the server signed, and
// sign the release with the device key (issue;).
//
// The ping signature is the security boundary. It covers
// worker_ephemeral_pubkey, the key the device seals the credential to. Without
// it, an on-path party rewrites that field, the device seals the real credential
// to the attacker's key, and nothing anywhere can tell. So the device verifies
// the ping against the server key it was given at pairing BEFORE it seals, and
// declines otherwise — always, with no unsigned fallback.
//
// Approval is the caller's responsibility: BuildRelease assumes the per-secret
// user tap-to-approve has already happened (the daemon UI gates it). This
// package performs no I/O and holds no long-lived secret, so it is fully
// testable.
package relay

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"strconv"

	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/store"
)

// ErrManualCodeRequired means the ping asked for a code that must be supplied
// out-of-band (email/SMS/manual) but none was provided to ResolveSecret.
var ErrManualCodeRequired = errors.New("relay: this secret kind needs a supplied code")

// The decline reasons for a ping the device refuses to seal for. They
// are distinct so a caller can log WHY it declined without ever logging the
// secret, the ping, or any key material.
var (
	// ErrUnsignedPing: the ping carries no server_signature at all. A device
	// never seals for an unsigned ping — that is the pre- vulnerability.
	ErrUnsignedPing = errors.New("relay: ping carries no server signature")
	// ErrBadServerSignature: a signature is present but does not verify against
	// the stored server key. The worker key (or any other covered field) was
	// substituted in flight, or the ping came from an impostor server.
	ErrBadServerSignature = errors.New("relay: ping server signature does not verify")
	// ErrNoServerKey: this device holds no usable server public key (paired
	// before v2, or a corrupt key). It must re-pair; it declines every ping.
	// There is deliberately no unsigned-ping fallback.
	ErrNoServerKey = errors.New("relay: device holds no server public key (re-pair required)")
)

// Domain-separation tags for the two signatures. Distinct tags mean a ping
// signature can never be replayed as a release signature, or vice versa. These
// strings MUST byte-match the server's device-relay package.
const (
	pingDomainTag    = "rindler-device-relay/ping/v2"
	releaseDomainTag = "rindler-device-relay/release/v2"

	// pairingFPTag domain-separates the pairing-code fingerprint (
	// follow-up: pairing-channel TOFU). MUST byte-match the server's pairingFPTag
	// in the server's device-relay package/signature.go.
	pairingFPTag = "rindler-device-relay/pairing-fp/v2"

	// PairingFingerprintLen is the truncated SHA-256 length (bytes) embedded in a
	// pairing code (128-bit second-preimage target — see the server's constant).
	PairingFingerprintLen = 16
)

// encField writes the canonical field encoding enc(x) := uint32be(len(x)) || x.
//
// Length-prefixed, NOT separator-joined. The old 0x1f-joined encoding is
// ambiguous over variable-length binary fields: an attacker who controls
// worker_ephemeral_pubkey (they do — it is the field this whole fix exists to
// authenticate) could embed a 0x1f and make one signature valid for a different
// (challenge, pubkey) split. Length prefixes make the parse unique.
func encField(b *bytes.Buffer, x []byte) {
	var n [4]byte
	binary.BigEndian.PutUint32(n[:], uint32(len(x)))
	b.Write(n[:])
	b.Write(x)
}

// PairingFingerprint is the 16-byte commitment to a lane's server_pubkey that
// the DEVICE recomputes over the server_pubkey it received at pair/complete and
// compares to the fingerprint carried inside the pairing code (follow-up:
// pairing-channel TOFU). A MITM on pair/complete can swap server_pubkey but
// cannot alter the fingerprint the user carried from the authenticated browser,
// so the compare fails and the device refuses to pair.
//
//	SHA-256( enc("rindler-device-relay/pairing-fp/v2") || enc(server_pubkey_raw) )[:16]
//
// serverPub is the RAW Ed25519 public key bytes (32 on a keyed lane, 0 on a
// keyless lane). MUST byte-match the server's device-relay package.PairingFingerprint;
// the golden-vector test proves it.
func PairingFingerprint(serverPub ed25519.PublicKey) []byte {
	var b bytes.Buffer
	encField(&b, []byte(pairingFPTag))
	encField(&b, serverPub)
	sum := sha256.Sum256(b.Bytes())
	return sum[:PairingFingerprintLen]
}

// ResolveSecret maps a ping's secret_kind to the actual value to relay, reading
// only what that one kind needs from the record. A durable secret (password) is
// read verbatim; email/SMS/manual codes are supplied by the caller (mailbox
// fetch / user entry) — the mailbox token behind them never leaves the device.
func ResolveSecret(rec store.Record, kind protocol.SecretKind, suppliedCode string) (string, error) {
	switch kind {
	case protocol.SecretUsername:
		if rec.Username == "" {
			return "", errors.New("relay: no username on record")
		}
		return rec.Username, nil
	case protocol.SecretPassword:
		if rec.Password == "" {
			return "", errors.New("relay: no password on record")
		}
		return rec.Password, nil
	case protocol.SecretEmailOTPCode, protocol.SecretSMSOTPCode, protocol.SecretManualCode:
		if suppliedCode == "" {
			return "", ErrManualCodeRequired
		}
		return suppliedCode, nil
	default:
		return "", fmt.Errorf("relay: unknown secret kind %q", kind)
	}
}

// PingSigningMessage is the canonical byte string the SERVER signs and the
// device verifies before it seals anything:
//
//	enc("rindler-device-relay/ping/v2")
//	 || enc(request_id) || enc(site) || enc(secret_kind)
//	 || enc(worker_ephemeral_pubkey)   <-- the point: the recipient key is signed
//	 || enc(challenge) || enc(itoa(ttl_seconds))
//
// the server's device-relay package is an independent implementation of this same
// contract and MUST produce these bytes exactly; the golden vector proves it.
func PingSigningMessage(ping protocol.SecretPing) []byte {
	var b bytes.Buffer
	encField(&b, []byte(pingDomainTag))
	encField(&b, []byte(ping.RequestID))
	encField(&b, []byte(ping.Site))
	encField(&b, []byte(ping.SecretKind))
	encField(&b, ping.WorkerEphemeralPubkey)
	encField(&b, ping.Challenge)
	encField(&b, []byte(strconv.Itoa(ping.TTLSeconds)))
	return b.Bytes()
}

// ReleaseSigningMessage is the canonical byte string the DEVICE signs and the
// server verifies:
//
//	enc("rindler-device-relay/release/v2")
//	 || enc(request_id) || enc(challenge)
//	 || enc(worker_ephemeral_pubkey)   <-- NEW: a key swap is detectable both ways
//	 || enc(sealed_secret)
//
// It replaces the v1 SigningMessage(request_id, challenge, sealed), which did
// not cover the recipient key.
func ReleaseSigningMessage(requestID string, challenge, workerPub, sealed []byte) []byte {
	var b bytes.Buffer
	encField(&b, []byte(releaseDomainTag))
	encField(&b, []byte(requestID))
	encField(&b, challenge)
	encField(&b, workerPub)
	encField(&b, sealed)
	return b.Bytes()
}

// VerifyPingSignature reports whether ping.ServerSignature is a valid signature
// by serverPub over PingSigningMessage(ping). False on a missing signature or an
// unusable server key — it never panics and never fails open.
func VerifyPingSignature(serverPub ed25519.PublicKey, ping protocol.SecretPing) bool {
	if len(serverPub) != ed25519.PublicKeySize || len(ping.ServerSignature) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(serverPub, PingSigningMessage(ping), ping.ServerSignature)
}

// BuildRelease produces the SecretRelease for an APPROVED ping.
//
// It FIRST authenticates the ping against the server key provisioned at pairing
// — the signature covers worker_ephemeral_pubkey, so a substituted recipient key
// fails here — and only then seals the resolved secret to that worker key under
// the request-bound info and signs (request_id, challenge, worker_pubkey, sealed)
// with the device key.
//
// Fail-closed: if serverPub is missing/wrong-size, or the ping carries no
// server signature, or the signature does not verify, BuildRelease returns a
// zero SecretRelease and an error, having sealed NOTHING. There is deliberately
// no unsigned-ping path — that would reinstate the key-substitution attack.
func BuildRelease(ping protocol.SecretPing, secret string, deviceKey ed25519.PrivateKey, serverPub ed25519.PublicKey) (protocol.SecretRelease, error) {
	if err := validatePing(ping); err != nil {
		return protocol.SecretRelease{}, err
	}
	if len(serverPub) != ed25519.PublicKeySize {
		return protocol.SecretRelease{}, ErrNoServerKey
	}
	if !VerifyPingSignature(serverPub, ping) {
		return protocol.SecretRelease{}, ErrBadServerSignature
	}
	// Past this line the worker key is authenticated: it is the one the server
	// signed. Only now may we seal.
	if secret == "" {
		return protocol.SecretRelease{}, errors.New("relay: empty secret")
	}
	if len(deviceKey) != ed25519.PrivateKeySize {
		return protocol.SecretRelease{}, errors.New("relay: invalid device key")
	}
	info := protocol.SealInfo(ping.RequestID, ping.Site, ping.SecretKind)
	sealed, err := protocol.SealToWorker(ping.WorkerEphemeralPubkey, info, []byte(secret))
	if err != nil {
		return protocol.SecretRelease{}, err
	}
	sig := ed25519.Sign(deviceKey, ReleaseSigningMessage(ping.RequestID, ping.Challenge, ping.WorkerEphemeralPubkey, sealed))
	return protocol.SecretRelease{
		RequestID:       ping.RequestID,
		SealedSecret:    sealed,
		DeviceSignature: sig,
	}, nil
}

// VerifyRelease checks a release's signature against the device public key and
// the originating ping — now including the ping's worker ephemeral key, so a
// release built for one recipient key does not verify under a ping claiming
// another. This is what the server-side hub runs; it lives here so the client
// can self-test the round-trip.
func VerifyRelease(rel protocol.SecretRelease, ping protocol.SecretPing, devicePub ed25519.PublicKey) bool {
	if rel.RequestID != ping.RequestID || len(rel.SealedSecret) == 0 || len(devicePub) != ed25519.PublicKeySize {
		return false
	}
	msg := ReleaseSigningMessage(rel.RequestID, ping.Challenge, ping.WorkerEphemeralPubkey, rel.SealedSecret)
	return ed25519.Verify(devicePub, msg, rel.DeviceSignature)
}

func validatePing(p protocol.SecretPing) error {
	if p.RequestID == "" {
		return errors.New("relay: ping has no request_id")
	}
	if !p.SecretKind.Valid() {
		return fmt.Errorf("relay: ping has unknown secret_kind %q", p.SecretKind)
	}
	if len(p.WorkerEphemeralPubkey) == 0 {
		return errors.New("relay: ping has no worker key")
	}
	if len(p.Challenge) == 0 {
		return errors.New("relay: ping has no challenge")
	}
	if len(p.ServerSignature) == 0 {
		return ErrUnsignedPing
	}
	return nil
}
