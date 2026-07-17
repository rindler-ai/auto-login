package mobile

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"testing"

	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/relay"
	"github.com/rindler-ai/auto-login/core/store"
)

type fakeSource map[string]string

func (f fakeSource) Lookup(site string) string { return f[site] }

// ListSites mirrors the native side: a JSON array string of the domains held.
func (f fakeSource) ListSites() string {
	sites := make([]string, 0, len(f))
	for s := range f {
		sites = append(sites, s)
	}
	sort.Strings(sites)
	b, _ := json.Marshal(sites)
	return string(b)
}

func TestSourceStore_ParsesRecordIncludingTOTP(t *testing.T) {
	src := fakeSource{"instacart.com": `{"username":"john","password":"pw","totp":{"Secret":"MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=","Digits":6,"Period":30,"Algorithm":"SHA1"}}`}
	ss := sourceStore{src: src}
	rec, err := ss.Get("instacart.com")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if rec.Username != "john" || rec.Password != "pw" {
		t.Fatalf("bad record: %+v", rec)
	}
	if rec.TOTP == nil || rec.TOTP.Digits != 6 || rec.TOTP.Algorithm != "SHA1" {
		t.Fatalf("totp not parsed: %+v", rec.TOTP)
	}
}

func TestSourceStore_EmptyLookupIsNotFound(t *testing.T) {
	ss := sourceStore{src: fakeSource{}}
	if _, err := ss.Get("x.com"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestSourceStore_ListSitesParsesJSONArray(t *testing.T) {
	ss := sourceStore{src: fakeSource{"a.com": `{"password":"p"}`, "b.com": `{"password":"q"}`}}
	got, err := ss.ListSites()
	if err != nil {
		t.Fatalf("ListSites: %v", err)
	}
	sort.Strings(got)
	if want := []string{"a.com", "b.com"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("ListSites = %v, want %v", got, want)
	}
}

// An empty JSON string from the native side means "holds nothing", not an error.
func TestSourceStore_ListSitesEmptyStringIsEmpty(t *testing.T) {
	ss := sourceStore{src: emptyStringSource{}}
	got, err := ss.ListSites()
	if err != nil || len(got) != 0 {
		t.Fatalf("ListSites = %v (err %v), want empty", got, err)
	}
}

// emptyStringSource returns "" from ListSites (a native side holding nothing).
type emptyStringSource struct{}

func (emptyStringSource) Lookup(string) string { return "" }
func (emptyStringSource) ListSites() string    { return "" }

func TestSourceStore_ReadOnly(t *testing.T) {
	ss := sourceStore{src: fakeSource{}}
	if err := ss.Put(store.Record{Site: "x"}); err == nil {
		t.Fatal("Put must be rejected (native owns writes)")
	}
	if err := ss.Delete("x"); err == nil {
		t.Fatal("Delete must be rejected")
	}
}

func TestGenerateAndPublicKeyMatch(t *testing.T) {
	b64, err := GenerateDeviceKey()
	if err != nil {
		t.Fatal(err)
	}
	pubB64, err := DevicePublicKey(b64)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := base64.StdEncoding.DecodeString(b64)
	want := base64.StdEncoding.EncodeToString(ed25519.PrivateKey(raw).Public().(ed25519.PublicKey))
	if pubB64 != want {
		t.Fatalf("public key mismatch:\n got %s\nwant %s", pubB64, want)
	}
}

// ExtractOTPCode returns the code for a real 2FA text and "" for a non-code
// message — the boundary that keeps ONLY codes leaving the device.
func TestExtractOTPCode(t *testing.T) {
	if got := ExtractOTPCode("Your Bank of America authorization code is 481920. Do not share it."); got != "481920" {
		t.Fatalf("ExtractOTPCode(otp text) = %q, want 481920", got)
	}
	if got := ExtractOTPCode("Hey, are we still on for dinner at 7?"); got != "" {
		t.Fatalf("ExtractOTPCode(non-code text) = %q, want \"\" (nothing leaves)", got)
	}
	if got := ExtractOTPCode(""); got != "" {
		t.Fatalf("ExtractOTPCode(empty) = %q, want \"\"", got)
	}
}

func TestStart_RequiresSourceKeyAndToken(t *testing.T) {
	key, _ := GenerateDeviceKey()
	srvPub, _ := serverPubkeyB64()
	if _, err := Start("wss://h", "tok", "not-base64!!", srvPub, fakeSource{}, nil, nil); err == nil {
		t.Fatal("expected error on bad key")
	}
	if _, err := Start("wss://h", "tok", key, srvPub, nil, nil, nil); err == nil {
		t.Fatal("expected error on nil source")
	}
	if _, err := Start("wss://h", "", key, srvPub, fakeSource{}, nil, nil); err == nil {
		t.Fatal("expected error on empty token")
	}
}

// A device with no stored server pubkey can verify nothing, so it would decline
// every ping. Refuse to start at all — the shell surfaces the error and
// the user re-pairs, rather than running a relay that can never serve. A garbage
// key is likewise refused, never downgraded to "no key".
func TestStart_RequiresServerPubkey(t *testing.T) {
	key, _ := GenerateDeviceKey()
	if _, err := Start("wss://h", "tok", key, "", fakeSource{}, nil, nil); err == nil {
		t.Fatal("expected error when the device holds no server pubkey (must re-pair)")
	}
	if _, err := Start("wss://h", "tok", key, "not-base64!!", fakeSource{}, nil, nil); err == nil {
		t.Fatal("expected error on a malformed server pubkey")
	}
	if _, err := Start("wss://h", "tok", key, base64.StdEncoding.EncodeToString([]byte("short")), fakeSource{}, nil, nil); err == nil {
		t.Fatal("expected error on a wrong-size server pubkey")
	}
}

// serverPubkeyB64 mints a base64 Ed25519 public key, standing in for the one the
// server hands the device at pairing.
func serverPubkeyB64() (string, error) {
	pub, _, err := ed25519.GenerateKey(nil)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(pub), nil
}

func TestApproveAdapter(t *testing.T) {
	missing := approveAdapter(nil, nil)
	if missing == nil {
		t.Fatal("nil Approver must still yield an adapter")
	}
	if code, ok := missing(nil, protocol.SecretPing{Site: "s", SecretKind: protocol.SecretPassword}); ok || code != "" {
		t.Fatalf("nil Approver returned code=%q ok=%v, want empty/false", code, ok)
	}
	deny := approveAdapter(approverFunc(func(string, string) bool { return false }), nil)
	if _, ok := deny(nil, protocol.SecretPing{Site: "s", SecretKind: protocol.SecretPassword}); ok {
		t.Fatal("adapter should have declined")
	}
	calls := 0
	allow := approveAdapter(approverFunc(func(string, string) bool { calls++; return true }), nil)
	if code, ok := allow(nil, protocol.SecretPing{Site: "s", SecretKind: protocol.SecretPassword}); !ok || code != "" {
		t.Fatalf("boolean mobile Approver returned code=%q ok=%v, want empty/true", code, ok)
	}
	for _, kind := range []protocol.SecretKind{
		protocol.SecretEmailOTPCode, protocol.SecretSMSOTPCode, protocol.SecretManualCode,
	} {
		if code, ok := allow(nil, protocol.SecretPing{Site: "s", SecretKind: kind}); ok || code != "" {
			t.Fatalf("unsupported %s returned code=%q ok=%v, want empty/false", kind, code, ok)
		}
	}
	if calls != 1 {
		t.Fatalf("native Approver called %d times, want only the supported password prompt", calls)
	}
}

// An sms_otp_code ping must fire the code-expectation sink (with the ping's site
// and TTL) so the native SMS reader arms — but must NOT for any other kind, and
// still declines the release either way (the code rides the HTTP relay). The sink
// fires even with a nil Approver (SMS reading needs no approval).
func TestApproveAdapterArmsSmsSink(t *testing.T) {
	var gotSite string
	var gotTTL, calls int
	sink := codeSinkFunc(func(site string, ttl int) { gotSite = site; gotTTL = ttl; calls++ })
	adapter := approveAdapter(nil, sink)

	if code, ok := adapter(nil, protocol.SecretPing{Site: "bank.example", SecretKind: protocol.SecretSMSOTPCode, TTLSeconds: 120}); ok || code != "" {
		t.Fatalf("sms ping returned code=%q ok=%v, want empty/false", code, ok)
	}
	if calls != 1 || gotSite != "bank.example" || gotTTL != 120 {
		t.Fatalf("sink calls=%d site=%q ttl=%d, want 1/bank.example/120", calls, gotSite, gotTTL)
	}
	// A non-SMS kind must not arm the reader.
	adapter(nil, protocol.SecretPing{Site: "s", SecretKind: protocol.SecretPassword, TTLSeconds: 99})
	adapter(nil, protocol.SecretPing{Site: "s", SecretKind: protocol.SecretEmailOTPCode, TTLSeconds: 99})
	if calls != 1 {
		t.Fatalf("sink fired %d times, want only the one sms_otp_code ping", calls)
	}
}

type approverFunc func(site, kind string) bool

func (f approverFunc) Approve(site, kind string) bool { return f(site, kind) }

type codeSinkFunc func(site string, ttlSeconds int)

func (f codeSinkFunc) OnExpectingSMSCode(site string, ttlSeconds int) { f(site, ttlSeconds) }

// Pair hands the native shell BOTH halves of the enrollment: the device token and
// the server's ping-signing pubkey. A shell that persisted only the token would
// build a device that declines every ping, so the pubkey must be on the
// bound return value.
func TestPair_ReturnsTokenAndServerPubkey(t *testing.T) {
	key, _ := GenerateDeviceKey()
	pub, _ := DevicePublicKey(key)
	wantServerPub, _ := serverPubkeyB64()
	// The v2 pairing code commits to the server key the lane returns; the device
	// recomputes the fingerprint over it at pair/complete and requires a match.
	rawServerPub, _ := base64.StdEncoding.DecodeString(wantServerPub)
	fp := relay.PairingFingerprint(ed25519.PublicKey(rawServerPub))
	code := "cd_pair2_" + hex.EncodeToString(append(bytes.Repeat([]byte{0xab}, relay.PairingFingerprintLen), fp...))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{
			"device_id": "dev-m", "device_token": "cd_dev_m", "server_pubkey": wantServerPub,
		})
	}))
	defer srv.Close()
	res, err := Pair(srv.URL, code, "iPhone", "ios", pub)
	if err != nil {
		t.Fatalf("Pair: %v", err)
	}
	if res.DeviceToken != "cd_dev_m" {
		t.Fatalf("device token = %q, want cd_dev_m", res.DeviceToken)
	}
	if res.ServerPubkey != wantServerPub {
		t.Fatalf("server pubkey = %q, want %q", res.ServerPubkey, wantServerPub)
	}
	// The pubkey Pair returns is exactly what Start accepts — the shells hand one
	// straight to the other, so a mismatch here would strand every device.
	session, err := Start("wss://h", res.DeviceToken, key, res.ServerPubkey, fakeSource{}, nil, nil)
	if err != nil {
		t.Fatalf("Start rejected the pubkey Pair returned: %v", err)
	}
	session.Stop() // it is already dialing an unreachable hub in the background
}
