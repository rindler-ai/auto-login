package mobile

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/rindler-ai/auto-login/core/otp"
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

// An email_otp_code ping, exactly like sms_otp_code, must fire the code-expectation
// sink (with the ping's site + TTL) so the native mailbox reader arms — but must NOT
// touch the SMS sink, and still declines the release ("", false): the code is read
// on-device and handed back out-of-band, NEVER sealed over this HPKE ping. The sink
// fires with a nil Approver (email reading needs no approval). Mirrors
// TestApproveAdapterArmsSmsSink for the email kind (arm-and-decline).
func TestApproveAdapterArmsEmailSink(t *testing.T) {
	sink := &recordingSink{}
	adapter := approveAdapter(nil, sink)

	if code, ok := adapter(nil, protocol.SecretPing{Site: "airbnb.example", SecretKind: protocol.SecretEmailOTPCode, TTLSeconds: 90}); ok || code != "" {
		t.Fatalf("email ping returned code=%q ok=%v, want empty/false (arm-and-decline; NOTHING sealed)", code, ok)
	}
	if sink.emailCalls != 1 || sink.emailSite != "airbnb.example" || sink.emailTTL != 90 {
		t.Fatalf("email sink calls=%d site=%q ttl=%d, want 1/airbnb.example/90", sink.emailCalls, sink.emailSite, sink.emailTTL)
	}
	if sink.smsCalls != 0 {
		t.Fatalf("email ping fired the SMS sink %d times, want 0", sink.smsCalls)
	}
	// A non-email kind must not arm the email reader.
	adapter(nil, protocol.SecretPing{Site: "s", SecretKind: protocol.SecretPassword, TTLSeconds: 99})
	adapter(nil, protocol.SecretPing{Site: "s", SecretKind: protocol.SecretSMSOTPCode, TTLSeconds: 99})
	if sink.emailCalls != 1 {
		t.Fatalf("email sink fired %d times, want only the one email_otp_code ping", sink.emailCalls)
	}
}

type approverFunc func(site, kind string) bool

func (f approverFunc) Approve(site, kind string) bool { return f(site, kind) }

type codeSinkFunc func(site string, ttlSeconds int)

func (f codeSinkFunc) OnExpectingSMSCode(site string, ttlSeconds int) { f(site, ttlSeconds) }

// OnExpectingEmailCode is a no-op for this SMS-only fake so codeSinkFunc still
// satisfies the two-method CodeExpectationSink; the email arm is covered by
// recordingSink in TestApproveAdapterArmsEmailSink.
func (f codeSinkFunc) OnExpectingEmailCode(string, int) {}

// recordingSink implements the FULL CodeExpectationSink (both methods) so the email
// test can assert the email arm fired without disturbing the SMS counter.
type recordingSink struct {
	smsSite, emailSite   string
	smsTTL, emailTTL     int
	smsCalls, emailCalls int
}

func (r *recordingSink) OnExpectingSMSCode(site string, ttlSeconds int) {
	r.smsSite = site
	r.smsTTL = ttlSeconds
	r.smsCalls++
}

func (r *recordingSink) OnExpectingEmailCode(site string, ttlSeconds int) {
	r.emailSite = site
	r.emailTTL = ttlSeconds
	r.emailCalls++
}

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
	res, err := Pair(srv.URL, code, "iPhone", "ios", pub, "os-device-id")
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

// fetchEmailOTP must honour BOTH bounds a windowed on-device read depends on:
//   - freshness (sinceEpochSec): a code from BEFORE the arm time is never returned, so a
//     stale code from an earlier attempt can't be replayed into this login.
//   - sender (fromContains): only mail from the expected OTP sender is a candidate, so an
//     unrelated cued number from a different sender is never forwarded.
//
// Driven through otp.FakeMailbox (same From/Since filtering + ExtractCode path as the real
// IMAP reader) so ONLY the extracted code — never a message body — can come back.
func TestFetchEmailOTP_FreshnessAndSenderBounds(t *testing.T) {
	arm := time.Now()
	sender := "no-reply@bank.example"
	fresh := otp.FakeMessage{From: "Bank <" + sender + ">", Subject: "Your code", Body: "Your verification code is 314159", Date: arm.Add(30 * time.Second)}
	stale := otp.FakeMessage{From: "Bank <" + sender + ">", Subject: "Your code", Body: "Your verification code is 271828", Date: arm.Add(-10 * time.Minute)}
	wrongSender := otp.FakeMessage{From: "Promo <deals@spam.example>", Subject: "Your code", Body: "Your verification code is 999999", Date: arm.Add(30 * time.Second)}

	sinceArm := arm.Unix()

	// A fresh message from the expected sender yields its code.
	code, err := fetchEmailOTP(&otp.FakeMailbox{Messages: []otp.FakeMessage{fresh}}, sender, sinceArm, 0)
	if err != nil || code != "314159" {
		t.Fatalf("fresh matching mail: code=%q err=%v, want 314159/nil", code, err)
	}

	// A stale code (before the arm time) is out of the freshness window — no code, and it
	// must NOT be misread as a broken mailbox (auth failure).
	code, err = fetchEmailOTP(&otp.FakeMailbox{Messages: []otp.FakeMessage{stale}}, sender, sinceArm, 0)
	if code != "" {
		t.Fatalf("stale mail returned code=%q, want \"\" (freshness bound)", code)
	}
	if errors.Is(err, ErrMailboxAuth) {
		t.Fatalf("stale-mail miss classified as auth failure: %v", err)
	}

	// A cued code from the WRONG sender is not a candidate — no code returned.
	code, _ = fetchEmailOTP(&otp.FakeMailbox{Messages: []otp.FakeMessage{wrongSender}}, sender, sinceArm, 0)
	if code != "" {
		t.Fatalf("wrong-sender mail returned code=%q, want \"\" (sender bound)", code)
	}

	// Sender + freshness together: given the fresh right-sender code AND a wrong-sender
	// decoy, only the right one comes back.
	code, err = fetchEmailOTP(&otp.FakeMailbox{Messages: []otp.FakeMessage{fresh, wrongSender}}, sender, sinceArm, 0)
	if err != nil || code != "314159" {
		t.Fatalf("fresh+decoy: code=%q err=%v, want 314159/nil", code, err)
	}
}

// The three outcomes the native reader acts on differently must map to distinct TYPED
// results — the whole point of NOT collapsing everything to "". A revoked app-password
// (ErrIMAPAuth) becomes ErrMailboxAuth (broken → badge + stop); a "no code yet"
// (ErrNoCode) becomes ("", nil) (keep polling); anything else becomes ErrMailboxUnavailable
// (transient → keep polling). Driven through a reader double returning each error.
func TestFetchEmailOTP_ClassifiesErrors(t *testing.T) {
	authFailed := readerFunc(func(otp.FetchOptions) (string, error) { return "", otp.ErrIMAPAuth })
	if _, err := fetchEmailOTP(authFailed, "s", 0, 0); !errors.Is(err, ErrMailboxAuth) {
		t.Fatalf("IMAP auth failure -> %v, want ErrMailboxAuth (revoked app-password must surface as broken)", err)
	}
	// Cross-language contract: the native reader (MailboxReader.kt) classifies a broken
	// mailbox by matching this substring on the gomobile Exception message, so a rename
	// here without updating isMailboxAuthError() would silently break the warning badge.
	if !strings.Contains(ErrMailboxAuth.Error(), "rejected the app password") {
		t.Fatalf("ErrMailboxAuth message = %q; the Kotlin isMailboxAuthError() matches on \"rejected the app password\"", ErrMailboxAuth.Error())
	}

	noCode := readerFunc(func(otp.FetchOptions) (string, error) { return "", otp.ErrNoCode })
	if code, err := fetchEmailOTP(noCode, "s", 0, 0); code != "" || err != nil {
		t.Fatalf("no-code-yet -> code=%q err=%v, want \"\"/nil (steady polling state, not a failure)", code, err)
	}

	networkBlip := readerFunc(func(otp.FetchOptions) (string, error) {
		return "", errors.New("otp: dial imap.example: connection refused")
	})
	if err := func() error { _, e := fetchEmailOTP(networkBlip, "s", 0, 0); return e }(); !errors.Is(err, ErrMailboxUnavailable) {
		t.Fatalf("transient failure -> %v, want ErrMailboxUnavailable", err)
	}
	// A transient blip must NEVER be misclassified as auth (which would wrongly brand a
	// healthy mailbox broken on a momentary outage).
	if _, err := fetchEmailOTP(networkBlip, "s", 0, 0); errors.Is(err, ErrMailboxAuth) {
		t.Fatal("transient failure misclassified as auth failure")
	}
}

// readerFunc is an otp.MailboxReader test double: it returns a canned (code, error) so
// the classification can be exercised without a real inbox.
type readerFunc func(otp.FetchOptions) (string, error)

func (f readerFunc) FetchLatestOTP(_ context.Context, opts otp.FetchOptions) (string, error) {
	return f(opts)
}
