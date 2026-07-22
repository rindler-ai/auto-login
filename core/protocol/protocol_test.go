package protocol

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSecretKindValidity(t *testing.T) {
	for _, k := range AllSecretKinds {
		if !k.Valid() {
			t.Errorf("%q should be valid", k)
		}
	}
	if SecretKind("not_a_kind").Valid() {
		t.Error("an unknown secret kind must be invalid")
	}
}

// The client's secret kinds must match the server contract exactly, or a code
// the client relays could be a kind the server does not accept (or vice versa).
func TestSecretKindsMatchSchema(t *testing.T) {
	// The wire contract is vendored at contract/device_relay.yaml (repo root);
	// from core/protocol that is ../../contract.
	raw, err := os.ReadFile(filepath.Join("..", "..", "contract", "device_relay.yaml"))
	if err != nil {
		t.Fatalf("read contract: %v", err)
	}
	text := string(raw)
	for _, k := range AllSecretKinds {
		if !strings.Contains(text, "- "+string(k)) {
			t.Errorf("kind %q in client but not in contract/device_relay.yaml", k)
		}
	}
}

func TestWireJSONKeys(t *testing.T) {
	ping := SecretPing{
		RequestID: "r", Site: "s", SecretKind: SecretPassword, WorkerEphemeralPubkey: []byte("k"),
		Challenge: []byte("c"), TTLSeconds: 30, ServerSignature: []byte("sig"),
	}
	b, err := json.Marshal(ping)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	for _, key := range []string{"request_id", "secret_kind", "worker_ephemeral_pubkey", "ttl_seconds", "server_signature"} {
		if !strings.Contains(string(b), `"`+key+`"`) {
			t.Errorf("missing wire key %q in %s", key, b)
		}
	}
	var got SecretPing
	if err := json.Unmarshal(b, &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got.SecretKind != SecretPassword || got.TTLSeconds != 30 || string(got.ServerSignature) != "sig" {
		t.Fatalf("round-trip lost data: %+v", got)
	}
}

// The device is provisioned the server's Ed25519 verification key at pairing;
// without it (a pre-v2 pairing) it can authenticate no ping and must re-pair.
func TestPairCompleteCarriesServerPubkey(t *testing.T) {
	if ProtocolVersion != 2 {
		t.Fatalf("ProtocolVersion = %d, want 2 (server-signed ping)", ProtocolVersion)
	}
	b, err := json.Marshal(DevicePairComplete{DeviceID: "d", Status: "paired", ServerPubkey: []byte("pk")})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if !strings.Contains(string(b), `"server_pubkey"`) {
		t.Errorf("missing wire key server_pubkey in %s", b)
	}
	var got DevicePairComplete
	if err := json.Unmarshal(b, &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if string(got.ServerPubkey) != "pk" {
		t.Fatalf("round-trip lost server_pubkey: %+v", got)
	}
}
