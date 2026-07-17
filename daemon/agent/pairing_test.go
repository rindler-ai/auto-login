package agent

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"github.com/rindler-ai/auto-login/core/relay"
)

// mintPairV2Code builds a well-formed v2 pairing code embedding fp as its trailing
// 16 bytes. The leading 16 (the single-use token) are arbitrary — the device does
// not read them, only the server does.
func mintPairV2Code(t *testing.T, fp []byte) string {
	t.Helper()
	if len(fp) != relay.PairingFingerprintLen {
		t.Fatalf("fp wrong len %d, want %d", len(fp), relay.PairingFingerprintLen)
	}
	body := make([]byte, 0, 2*relay.PairingFingerprintLen)
	body = append(body, bytes.Repeat([]byte{0xab}, relay.PairingFingerprintLen)...) // token
	body = append(body, fp...)
	return "cd_pair2_" + hex.EncodeToString(body)
}

// pairServer answers /devices/pair/complete with the given server_pubkey (base64;
// pass "" for a keyless or MITM-stripped response).
func pairServer(serverPubkeyB64 string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{
			"device_id": "dev-1", "device_token": "cd_dev_tok", "server_pubkey": serverPubkeyB64,
		})
	}))
}

func TestCompletePairing_HappyPath(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(nil)
	srvPub, _, _ := ed25519.GenerateKey(nil)
	srvPubB64 := base64.StdEncoding.EncodeToString(srvPub)
	// A keyed-lane v2 code committing to the server key the lane returns.
	code := mintPairV2Code(t, relay.PairingFingerprint(srvPub))
	var gotBody map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/devices/pair/complete" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &gotBody)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"device_id": "dev-123", "device_token": "cd_dev_abc", "server_pubkey": srvPubB64,
		})
	}))
	defer srv.Close()

	res, err := CompletePairing(context.Background(), srv.URL+"/devices/pair/complete", code, "Example Mac", "darwin", pub)
	if err != nil {
		t.Fatalf("CompletePairing: %v", err)
	}
	if res.DeviceID != "dev-123" || res.DeviceToken != "cd_dev_abc" {
		t.Fatalf("got id=%q tok=%q", res.DeviceID, res.DeviceToken)
	}
	// The server's ping-signing key is provisioned HERE and nowhere else.
	if res.ServerPubkeyB64 != srvPubB64 {
		t.Fatalf("server_pubkey = %q, want %q", res.ServerPubkeyB64, srvPubB64)
	}
	// The device sends its PUBLIC key, base64-std, and the pairing code + platform.
	if gotBody["device_pubkey"] != base64.StdEncoding.EncodeToString(pub) {
		t.Fatalf("pubkey not sent as base64-std: %q", gotBody["device_pubkey"])
	}
	if gotBody["pairing_token"] != code || gotBody["platform"] != "darwin" || gotBody["device_name"] != "Example Mac" {
		t.Fatalf("unexpected body: %+v", gotBody)
	}
}

// A server_pubkey that is present but unusable is a server bug or tampering, not
// a config gap: reject the pairing rather than silently downgrade it to "no key".
// (Rejected at ParseServerPubkey, before the fingerprint compare is reached.)
func TestCompletePairing_RejectsMalformedServerPubkey(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(nil)
	code := mintPairV2Code(t, relay.PairingFingerprint(nil))
	for _, bad := range []string{"not-base64!!", base64.StdEncoding.EncodeToString([]byte("too-short"))} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_ = json.NewEncoder(w).Encode(map[string]string{"device_token": "cd_dev_x", "server_pubkey": bad})
		}))
		if _, err := CompletePairing(context.Background(), srv.URL, code, "n", "linux", pub); err == nil {
			t.Fatalf("expected an error on malformed server_pubkey %q", bad)
		}
		srv.Close()
	}
}

// THE ATTACK (follow-up: pairing-channel TOFU). A network MITM on
// pair/complete returns its OWN server key instead of the lane's. The user typed a
// code whose fingerprint commits to the REAL key (carried over the clean, authed
// browser→human channel a MITM cannot touch), so the device recomputes the
// fingerprint over the injected key, the compare fails, and it declines —
// surfacing NOTHING the shell could persist. This is the test that proves the vuln
// is closed.
func TestCompletePairing_RejectsInjectedServerKey(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(nil)
	realPub, _, _ := ed25519.GenerateKey(nil)
	attackerPub, _, _ := ed25519.GenerateKey(nil)

	// The code commits to the REAL lane key...
	code := mintPairV2Code(t, relay.PairingFingerprint(realPub))
	// ...but the (MITM'd) server hands back the ATTACKER's key.
	srv := pairServer(base64.StdEncoding.EncodeToString(attackerPub))
	defer srv.Close()

	res, err := CompletePairing(context.Background(), srv.URL, code, "dev", "linux", pub)
	if err == nil {
		t.Fatalf("device accepted an injected server key: %+v", res)
	}
	if !strings.Contains(err.Error(), "could not verify") {
		t.Fatalf("want a 'could not verify' error, got %v", err)
	}
	// No token, no server key — nothing usable ever reached the caller/shell.
	if !reflect.DeepEqual(res, PairResult{}) {
		t.Fatalf("attack surfaced a usable PairResult: %+v", res)
	}
	if res.DeviceToken != "" {
		t.Fatalf("attack surfaced a device token: %q", res.DeviceToken)
	}
}

// The full TOFU decision matrix over what the server returns vs. what the code
// committed to. Every rejection must be fail-closed: a zero PairResult, no token.
func TestCompletePairing_TOFU(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(nil)
	realPub, _, _ := ed25519.GenerateKey(nil)
	realB64 := base64.StdEncoding.EncodeToString(realPub)
	attackerPub, _, _ := ed25519.GenerateKey(nil)
	attackerB64 := base64.StdEncoding.EncodeToString(attackerPub)
	otherPub, _, _ := ed25519.GenerateKey(nil)

	keyedCode := mintPairV2Code(t, relay.PairingFingerprint(realPub))
	keylessCode := mintPairV2Code(t, relay.PairingFingerprint(nil))
	wrongFPCode := mintPairV2Code(t, relay.PairingFingerprint(otherPub)) // fp over a key the server never returns

	cases := []struct {
		name       string
		code       string
		serverPub  string // what the server returns in server_pubkey
		wantErr    string // substring; "" means expect success
		wantPubB64 string // expected PairResult.ServerPubkeyB64 on success
	}{
		{name: "ATTACK: server injects an attacker key", code: keyedCode, serverPub: attackerB64, wantErr: "could not verify"},
		{name: "positive control: server returns the real key", code: keyedCode, serverPub: realB64, wantPubB64: realB64},
		{name: "v1 cd_pair_ code is rejected", code: "cd_pair_deadbeef", serverPub: realB64, wantErr: "unrecognized"},
		{name: "well-formed code, wrong embedded fingerprint", code: wrongFPCode, serverPub: realB64, wantErr: "could not verify"},
		{name: "keyed code, server strips the key (downgrade to denial)", code: keyedCode, serverPub: "", wantErr: "could not verify"},
		{name: "keyless code, server returns no key (legit keyless)", code: keylessCode, serverPub: "", wantPubB64: ""},
		{name: "keyless code, server injects a key", code: keylessCode, serverPub: attackerB64, wantErr: "could not verify"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := pairServer(tc.serverPub)
			defer srv.Close()
			res, err := CompletePairing(context.Background(), srv.URL, tc.code, "dev", "linux", pub)
			if tc.wantErr != "" {
				if err == nil {
					t.Fatalf("want error containing %q, got nil (res=%+v)", tc.wantErr, res)
				}
				if !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("error %q does not contain %q", err.Error(), tc.wantErr)
				}
				// Fail-closed: nothing usable is surfaced on any rejection.
				if !reflect.DeepEqual(res, PairResult{}) {
					t.Fatalf("declined pairing surfaced a PairResult: %+v", res)
				}
				return
			}
			if err != nil {
				t.Fatalf("want success, got %v", err)
			}
			if res.ServerPubkeyB64 != tc.wantPubB64 {
				t.Fatalf("ServerPubkeyB64 = %q, want %q", res.ServerPubkeyB64, tc.wantPubB64)
			}
			if res.DeviceToken == "" {
				t.Fatal("success but no device token surfaced")
			}
		})
	}
}

func TestParseServerPubkey(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(nil)
	got, err := ParseServerPubkey(base64.StdEncoding.EncodeToString(pub))
	if err != nil || !got.Equal(pub) {
		t.Fatalf("ParseServerPubkey round-trip failed: %v", err)
	}
	// Empty means "this device holds no server key" — the decline-everything state.
	if got, err := ParseServerPubkey(""); err != nil || got != nil {
		t.Fatalf("ParseServerPubkey(\"\") = %v, %v; want nil, nil", got, err)
	}
	if _, err := ParseServerPubkey("!!!"); err == nil {
		t.Fatal("expected an error on non-base64")
	}
	if _, err := ParseServerPubkey(base64.StdEncoding.EncodeToString([]byte("short"))); err == nil {
		t.Fatal("expected an error on a wrong-size key")
	}
}

func TestCompletePairing_RejectsBadStatus(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(nil)
	code := mintPairV2Code(t, relay.PairingFingerprint(nil)) // well-formed, so the 401 path is actually reached
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	if _, err := CompletePairing(context.Background(), srv.URL, code, "n", "linux", pub); err == nil {
		t.Fatal("expected error on 401")
	}
}

func TestCompletePairing_ValidatesInputs(t *testing.T) {
	good, _, _ := ed25519.GenerateKey(nil)
	// Empty and non-v2 codes are rejected before any network call.
	if _, err := CompletePairing(context.Background(), "http://x", "", "n", "linux", good); err == nil {
		t.Fatal("expected error on empty pairing code")
	}
	if _, err := CompletePairing(context.Background(), "http://x", "cd_pair_v1code", "n", "linux", good); err == nil {
		t.Fatal("expected error on a v1 pairing code")
	}
	// A v2-prefixed code whose body is not 32 hex-decoded bytes is rejected too.
	if _, err := CompletePairing(context.Background(), "http://x", "cd_pair2_zzzz", "n", "linux", good); err == nil {
		t.Fatal("expected error on a malformed v2 code body")
	}
	// A well-formed v2 code but a wrong-size device pubkey.
	code := mintPairV2Code(t, relay.PairingFingerprint(nil))
	if _, err := CompletePairing(context.Background(), "http://x", code, "n", "linux", []byte("short")); err == nil {
		t.Fatal("expected error on wrong-size pubkey")
	}
}

func TestPairURLFromHub(t *testing.T) {
	cases := []struct{ hub, override, want string }{
		{"wss://your-hub.example/v1/devices/connect", "", "https://your-hub.example/devices/pair/complete"},
		{"ws://localhost:8080/v1/devices/connect", "", "http://localhost:8080/devices/pair/complete"},
		{"wss://your-hub.example/v1/devices/connect", "https://custom/x", "https://custom/x"},
	}
	for _, c := range cases {
		got, err := PairURLFromHub(c.hub, c.override)
		if err != nil {
			t.Fatalf("PairURLFromHub(%q): %v", c.hub, err)
		}
		if got != c.want {
			t.Fatalf("PairURLFromHub(%q,%q) = %q, want %q", c.hub, c.override, got, c.want)
		}
	}
	if _, err := PairURLFromHub("://bad", ""); err == nil {
		t.Fatal("expected error on unparseable hub url")
	}
}

// A defense-in-depth check: the request must not carry the private key anywhere.
func TestCompletePairing_NeverSendsPrivateKey(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(nil)
	privB64 := base64.StdEncoding.EncodeToString(priv)
	code := mintPairV2Code(t, relay.PairingFingerprint(nil)) // keyless lane -> empty server_pubkey succeeds
	var raw string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		raw = string(b)
		_ = json.NewEncoder(w).Encode(map[string]string{"device_token": "cd_dev_x"})
	}))
	defer srv.Close()
	if _, err := CompletePairing(context.Background(), srv.URL, code, "n", "linux", pub); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(raw, privB64) {
		t.Fatal("pairing request body contained the device PRIVATE key")
	}
}
