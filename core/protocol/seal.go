package protocol

import (
	"bytes"
	"crypto/ecdh"
	"crypto/hpke"
	"errors"
	"fmt"
)

// The HPKE ciphersuite MUST match the server (schema/device_relay.yaml,
// the server's device-relay package/seal.go): RFC 9180 base mode,
// DHKEM(X25519, HKDF-SHA256) + HKDF-SHA256 + AES-256-GCM.
func suiteKEM() hpke.KEM   { return hpke.DHKEM(ecdh.X25519()) }
func suiteKDF() hpke.KDF   { return hpke.HKDFSHA256() }
func suiteAEAD() hpke.AEAD { return hpke.AES256GCM() }

// infoPrefix domain-separates this application's HPKE contexts. MUST byte-match
// the server's infoPrefix or seals will not open.
var infoPrefix = []byte("rindler-device-relay-v1")

var (
	errEmptyWorkerPub = errors.New("protocol: worker public key is empty")
	errEmptySecret    = errors.New("protocol: secret is empty")
)

// SealInfo builds the HPKE `info` binding a sealed secret to exactly one
// request. MUST match the server's SealInfo byte-for-byte (0x1f separator).
func SealInfo(requestID, site string, kind SecretKind) []byte {
	sep := []byte{0x1f}
	var b bytes.Buffer
	b.Write(infoPrefix)
	b.Write(sep)
	b.WriteString(requestID)
	b.Write(sep)
	b.WriteString(site)
	b.Write(sep)
	b.WriteString(string(kind))
	return b.Bytes()
}

// SealToWorker seals exactly one secret to the worker's per-login public key
// (from the SecretPing) under the request-bound info. The output is the
// SecretRelease.SealedSecret. Only the worker process holding the matching
// private key can open it.
func SealToWorker(workerPubkey, info, secret []byte) ([]byte, error) {
	if len(workerPubkey) == 0 {
		return nil, errEmptyWorkerPub
	}
	if len(secret) == 0 {
		return nil, errEmptySecret
	}
	pub, err := suiteKEM().NewPublicKey(workerPubkey)
	if err != nil {
		return nil, fmt.Errorf("protocol: parse worker public key: %w", err)
	}
	ct, err := hpke.Seal(pub, suiteKDF(), suiteAEAD(), info, secret)
	if err != nil {
		return nil, fmt.Errorf("protocol: hpke seal: %w", err)
	}
	return ct, nil
}

// openForTest opens a sealed secret with an RFC 9180-serialized private key.
// Exported only for the interop test (the client never opens in production —
// the worker does). Kept here so the golden-vector round-trip lives beside Seal.
func openForTest(workerPrivkey, info, ciphertext []byte) ([]byte, error) {
	priv, err := suiteKEM().NewPrivateKey(workerPrivkey)
	if err != nil {
		return nil, fmt.Errorf("protocol: parse worker private key: %w", err)
	}
	pt, err := hpke.Open(priv, suiteKDF(), suiteAEAD(), info, ciphertext)
	if err != nil {
		return nil, fmt.Errorf("protocol: hpke open: %w", err)
	}
	return pt, nil
}
