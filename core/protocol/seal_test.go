package protocol

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func mustB64(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		t.Fatalf("base64 %q: %v", s, err)
	}
	return b
}

func TestSealRoundTrip(t *testing.T) {
	// Generate a worker keypair the same way the server does, seal to it, open.
	kp, err := suiteKEM().GenerateKey()
	if err != nil {
		t.Fatalf("gen key: %v", err)
	}
	priv, err := kp.Bytes()
	if err != nil {
		t.Fatalf("priv bytes: %v", err)
	}
	info := SealInfo("req-1", "site.com", SecretPassword)
	secret := []byte("not-a-real-password")
	ct, err := SealToWorker(kp.PublicKey().Bytes(), info, secret)
	if err != nil {
		t.Fatalf("seal: %v", err)
	}
	if bytes.Contains(ct, secret) {
		t.Fatal("ciphertext leaks plaintext")
	}
	got, err := openForTest(priv, info, ct)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if !bytes.Equal(got, secret) {
		t.Fatalf("round-trip mismatch: %q != %q", got, secret)
	}
}

type goldenVector struct {
	WorkerPrivKeyB64 string `json:"worker_private_key_b64"`
	WorkerPubKeyB64  string `json:"worker_public_key_b64"`
	InfoB64          string `json:"info_b64"`
	CiphertextB64    string `json:"ciphertext_b64"`
	PlaintextUTF8    string `json:"plaintext_utf8"`
}

func loadGolden(t *testing.T) goldenVector {
	t.Helper()
	// The golden vector is vendored from the language-agnostic wire contract
	// (contract/device_relay.yaml) into contract/testdata at the repo root; from
	// core/protocol that is ../../contract/testdata. It stays byte-identical to the
	// server's copy so the two implementations are proven interoperable.
	raw, err := os.ReadFile(filepath.Join("..", "..", "contract", "testdata", "device_relay_hpke_golden_vector.json"))
	if err != nil {
		t.Fatalf("read golden: %v", err)
	}
	var gv goldenVector
	if err := json.Unmarshal(raw, &gv); err != nil {
		t.Fatalf("unmarshal golden: %v", err)
	}
	return gv
}

// The server's golden ciphertext must open under this client's HPKE — proving
// the two implementations share the suite, info construction, and separator.
func TestGoldenCiphertextOpens(t *testing.T) {
	gv := loadGolden(t)
	got, err := openForTest(mustB64(t, gv.WorkerPrivKeyB64), mustB64(t, gv.InfoB64), mustB64(t, gv.CiphertextB64))
	if err != nil {
		t.Fatalf("open server golden ciphertext: %v", err)
	}
	if string(got) != gv.PlaintextUTF8 {
		t.Fatalf("plaintext mismatch: %q != %q", got, gv.PlaintextUTF8)
	}
}

// The inverse interop direction: a secret this client seals to the golden worker
// public key must open under the golden worker private key — proving the server
// worker can open what this client produces.
func TestClientSealOpensUnderGoldenKey(t *testing.T) {
	gv := loadGolden(t)
	info := SealInfo("req-golden-0001", "example-auth-site.com", SecretPassword)
	// info must reconstruct to exactly the golden info.
	if !bytes.Equal(info, mustB64(t, gv.InfoB64)) {
		t.Fatal("client SealInfo does not byte-match the server golden info; separator/prefix drift")
	}
	secret := []byte("client-sealed-secret")
	ct, err := SealToWorker(mustB64(t, gv.WorkerPubKeyB64), info, secret)
	if err != nil {
		t.Fatalf("client seal: %v", err)
	}
	got, err := openForTest(mustB64(t, gv.WorkerPrivKeyB64), info, ct)
	if err != nil {
		t.Fatalf("open client seal under golden key: %v", err)
	}
	if !bytes.Equal(got, secret) {
		t.Fatalf("mismatch: %q != %q", got, secret)
	}
}

func TestSealRejectsEmpty(t *testing.T) {
	if _, err := SealToWorker(nil, []byte("i"), []byte("s")); err == nil {
		t.Error("want error on empty pubkey")
	}
	gv := loadGolden(t)
	if _, err := SealToWorker(mustB64(t, gv.WorkerPubKeyB64), []byte("i"), nil); err == nil {
		t.Error("want error on empty secret")
	}
}
