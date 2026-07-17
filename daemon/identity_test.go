package main

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/rindler-ai/auto-login/core/relay"
)

// mintPairV2Code builds a well-formed v2 pairing code committing to serverPub (the
// device recomputes this fingerprint over the key it receives and compares).
func mintPairV2Code(serverPub ed25519.PublicKey) string {
	fp := relay.PairingFingerprint(serverPub)
	body := append(bytes.Repeat([]byte{0xab}, relay.PairingFingerprintLen), fp...)
	return "cd_pair2_" + hex.EncodeToString(body)
}

func quietLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

// clearIdentityEnv nils out the env inputs so a stray shell/CI value cannot leak
// into a test.
func clearIdentityEnv(t *testing.T) {
	t.Helper()
	for _, k := range []string{"RINDLER_DEVICE_TOKEN", "RINDLER_DEVICE_KEY", "RINDLER_SERVER_PUBKEY", "RINDLER_PAIRING_CODE", "RINDLER_PAIR_URL", "RINDLER_DEVICE_NAME"} {
		t.Setenv(k, "")
	}
}

func TestLoadIdentity_KeychainFirst(t *testing.T) {
	clearIdentityEnv(t)
	kr := newFakeKeyring()
	_, priv, _ := ed25519.GenerateKey(nil)
	srvPub, _, _ := ed25519.GenerateKey(nil)
	if err := writeKeyringIdentity(kr, identity{token: "cd_dev_persisted", key: priv, serverPub: srvPub}); err != nil {
		t.Fatal(err)
	}
	id, err := loadIdentity(context.Background(), kr, "wss://hub/x", quietLog())
	if err != nil {
		t.Fatalf("loadIdentity: %v", err)
	}
	if id.token != "cd_dev_persisted" || !id.key.Equal(priv) {
		t.Fatalf("did not load persisted identity: tok=%q keyEq=%v", id.token, id.key.Equal(priv))
	}
	// The server pubkey persists with the token — without it the device would
	// decline every ping after a restart.
	if !id.serverPub.Equal(srvPub) {
		t.Fatal("server pubkey did not survive the keychain round-trip")
	}
}

// A device enrolled before has a token + key but NO server pubkey. It must
// still load (it can hold the hub connection and answer inventory) with an empty
// serverPub, which makes every ping decline until it re-pairs.
func TestLoadIdentity_KeychainWithoutServerPubkey(t *testing.T) {
	clearIdentityEnv(t)
	kr := newFakeKeyring()
	_, priv, _ := ed25519.GenerateKey(nil)
	if err := writeKeyringIdentity(kr, identity{token: "cd_dev_legacy", key: priv}); err != nil {
		t.Fatal(err)
	}
	id, err := loadIdentity(context.Background(), kr, "wss://hub/x", quietLog())
	if err != nil {
		t.Fatalf("loadIdentity: %v", err)
	}
	if id.token != "cd_dev_legacy" || len(id.serverPub) != 0 {
		t.Fatalf("legacy identity = (%q, serverPub len %d), want the token and no server key", id.token, len(id.serverPub))
	}
}

func TestLoadIdentity_PairsAndPersists(t *testing.T) {
	clearIdentityEnv(t)
	srvPub, _, _ := ed25519.GenerateKey(nil)
	srvPubB64 := base64.StdEncoding.EncodeToString(srvPub)
	var gotPub string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &body)
		gotPub = body["device_pubkey"]
		_ = json.NewEncoder(w).Encode(map[string]string{
			"device_id": "d1", "device_token": "cd_dev_fresh", "server_pubkey": srvPubB64,
		})
	}))
	defer srv.Close()

	t.Setenv("RINDLER_PAIRING_CODE", mintPairV2Code(srvPub))
	t.Setenv("RINDLER_PAIR_URL", srv.URL)
	kr := newFakeKeyring()

	id, err := loadIdentity(context.Background(), kr, "wss://hub/v1/devices/connect", quietLog())
	if err != nil {
		t.Fatalf("loadIdentity pair: %v", err)
	}
	if id.token != "cd_dev_fresh" {
		t.Fatalf("token = %q, want cd_dev_fresh", id.token)
	}
	if !id.serverPub.Equal(srvPub) {
		t.Fatal("pairing did not surface the server's ping-signing pubkey")
	}
	// The pubkey POSTed must be the public half of the returned private key.
	wantPub := base64.StdEncoding.EncodeToString(id.key.Public().(ed25519.PublicKey))
	if gotPub != wantPub {
		t.Fatalf("server got pubkey %q, want %q", gotPub, wantPub)
	}
	// Persisted: a second load reads straight from the keychain (no pairing).
	got, ok := readKeyringIdentity(kr)
	if !ok || got.token != "cd_dev_fresh" || !got.key.Equal(id.key) || !got.serverPub.Equal(srvPub) {
		t.Fatalf("identity not persisted to keychain: ok=%v tok=%q", ok, got.token)
	}
}

func TestLoadIdentity_ErrorsWithNothingConfigured(t *testing.T) {
	clearIdentityEnv(t)
	if _, err := loadIdentity(context.Background(), newFakeKeyring(), "wss://hub/x", quietLog()); err == nil {
		t.Fatal("expected error when no keychain identity, no env, no pairing code")
	}
}

func TestLoadIdentity_EnvTokenAndKey(t *testing.T) {
	clearIdentityEnv(t)
	_, priv, _ := ed25519.GenerateKey(nil)
	srvPub, _, _ := ed25519.GenerateKey(nil)
	t.Setenv("RINDLER_DEVICE_TOKEN", "cd_dev_env")
	t.Setenv("RINDLER_DEVICE_KEY", base64.StdEncoding.EncodeToString(priv))
	t.Setenv("RINDLER_SERVER_PUBKEY", base64.StdEncoding.EncodeToString(srvPub))
	id, err := loadIdentity(context.Background(), nil, "wss://hub/x", quietLog())
	if err != nil {
		t.Fatalf("loadIdentity env: %v", err)
	}
	if id.token != "cd_dev_env" || !id.key.Equal(priv) || !id.serverPub.Equal(srvPub) {
		t.Fatalf("env identity not honored: tok=%q", id.token)
	}
}

// A malformed RINDLER_SERVER_PUBKEY is an error, never a silent "no key".
func TestLoadIdentity_RejectsMalformedEnvServerPubkey(t *testing.T) {
	clearIdentityEnv(t)
	_, priv, _ := ed25519.GenerateKey(nil)
	t.Setenv("RINDLER_DEVICE_TOKEN", "cd_dev_env")
	t.Setenv("RINDLER_DEVICE_KEY", base64.StdEncoding.EncodeToString(priv))
	t.Setenv("RINDLER_SERVER_PUBKEY", "not-base64!!")
	if _, err := loadIdentity(context.Background(), nil, "wss://hub/x", quietLog()); err == nil {
		t.Fatal("expected an error on a malformed RINDLER_SERVER_PUBKEY")
	}
}
