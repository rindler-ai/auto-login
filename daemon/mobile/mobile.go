// Package mobile is the gomobile-bindable surface of the custody core. Running
// `gomobile bind` over this package emits an Android .aar and an iOS
// .xcframework (see ../Makefile), which the native SwiftUI / Compose shells
// import. It drives the SAME agent core as the desktop daemon, so the
// relay/crypto/wire behavior is identical on every platform — the whole point of
// "Go shared core + thin native shells."
//
// The exported surface uses only gomobile-bindable types (string, bool, error,
// []byte, bound structs, and callback interfaces). The native side owns the two
// things Go must not: secure storage (Secure Enclave / Android Keystore, via
// SecretSource) and per-release authorization (via Approver — every shell
// auto-approves a verified ping, no per-release user interaction).
package mobile

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"

	"github.com/rindler-ai/auto-login/core/otp"
	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/store"
	"github.com/rindler-ai/auto-login/core/totp"
	"github.com/rindler-ai/auto-login/daemon/agent"
)

// SecretSource is implemented on the NATIVE side (iOS Secure Enclave / Android
// Keystore). Lookup returns the credential record for a site as a JSON object
// — {"username":"…","password":"…","totp":{"Secret":"<base64>","Digits":6,
// "Period":30,"Algorithm":"SHA1"}} — or "" when the device holds nothing for the
// site. After approval, the Go core parses it, resolves+seals exactly ONE
// requested value, and discards the transient plaintext; the durable source of
// truth remains in native storage.
type SecretSource interface {
	Lookup(site string) string
	// ListSites returns the domains the device holds a login for, as a JSON array
	// string (e.g. `["a.com","b.com"]`) — gomobile cannot bind a []string return,
	// so this mirrors how Lookup returns a JSON string. "" or "[]" both mean the
	// device holds nothing. Domains ONLY — never a secret. The Go core parses it.
	ListSites() string
}

// Approver is implemented natively to authorize each release. Every shell
// (AutoApprover on iOS/macOS/Android) authorizes every cryptographically-verified
// ping without any per-release user interaction — a server-signed ping is
// authorized by construction, so releases stay frictionless. Approve
// reports whether to proceed. A nil Approver (or a Start with none) always
// declines (the headless desktop build, which has no relay app).
type Approver interface {
	Approve(site string, kind string) bool
}

// CodeExpectationSink is an OPTIONAL native hook that turns the swallowed
// sms_otp_code SecretPing into an "arm the SMS reader" signal. The Go core calls
// OnExpectingSMSCode only when an AUTHENTICATED, replay-checked sms_otp_code ping
// arrives — i.e. a login is now, for up to ttlSeconds, waiting for a texted 2FA
// code. The native SMS reader uses this to inspect an incoming text ONLY during
// that window and ignore every text otherwise, so the app is exposed to the
// user's SMS solely while a real login awaits a code — never the general stream.
// A nil sink is fine (the signal is simply dropped); the SMS path is passive.
type CodeExpectationSink interface {
	OnExpectingSMSCode(site string, ttlSeconds int)
}

// Session is a running custody agent. Stop ends it and drops the hub connection.
type Session struct{ cancel context.CancelFunc }

// Stop tears the session down (idempotent).
func (s *Session) Stop() {
	if s != nil && s.cancel != nil {
		s.cancel()
	}
}

// Start dials the hub and serves pings in the background until Stop.
// deviceKeyB64 is the base64 Ed25519 PRIVATE key the native side holds in the
// enclave. serverPubkeyB64 is the base64 Ed25519 PUBLIC key the server returned
// at pairing (PairResult.ServerPubkey) — the device verifies every SecretPing
// against it before sealing, so the native side MUST persist it next to
// the token and pass it back here.
//
// It is REQUIRED: a device that holds no server key can authenticate nothing and
// would decline every ping, so refusing to start (with a re-pair message the
// shell can surface) beats silently connecting a relay that can never serve. A
// device paired before this change has none and must re-pair.
func Start(hubURL, deviceToken, deviceKeyB64, serverPubkeyB64 string, src SecretSource, appr Approver, codeSink CodeExpectationSink) (*Session, error) {
	key, err := decodeDeviceKey(deviceKeyB64)
	if err != nil {
		return nil, err
	}
	if src == nil {
		return nil, errors.New("mobile: SecretSource is required")
	}
	if deviceToken == "" {
		return nil, errors.New("mobile: device token is required (pair first)")
	}
	if serverPubkeyB64 == "" {
		return nil, errors.New("mobile: server public key is required — re-pair this device")
	}
	serverPub, err := agent.ParseServerPubkey(serverPubkeyB64)
	if err != nil {
		return nil, err
	}
	// Reject a cleartext relay channel up front (the token + secrets ride it).
	if err := agent.ValidateHubURL(hubURL); err != nil {
		return nil, err
	}
	cfg := agent.Config{
		HubURL:       hubURL,
		DeviceToken:  deviceToken,
		DeviceKey:    key,
		ServerPubkey: serverPub,
		Store:        sourceStore{src: src},
		Approve:      approveAdapter(appr, codeSink),
		Log:          slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = agent.Run(ctx, cfg) }()
	return &Session{cancel: cancel}, nil
}

// ExtractOTPCode pulls a single one-time login code out of an SMS / notification
// body ON THE DEVICE and returns just that code, or "" when the body holds no
// plausible code. The native SMS reader calls this so ONLY the extracted code —
// never the raw message text — ever leaves the phone: a non-code text (a normal
// message, a promo, an order update) returns "" and is dropped, forwarded
// nowhere and logged nowhere.
//
// It is the same pure extractor the server runs (smsrelay.ExtractOTP mirrors
// custody-app/otp.ExtractCode), so an on-device extraction and a server-side one
// agree on what counts as a code. Passing the extracted code to
// POST /devices/sms-relay/manual is byte-identical, from the paused login's view,
// to a code the server extracted from a forwarded body — both converge on the
// same DeliverOTP rendezvous.
func ExtractOTPCode(body string) string {
	// RequireContext: the SMS reader inspects a text that arrived while a login
	// awaits a code, but that text is not guaranteed to BE the code — a promo, a
	// delivery update, or a date can land mid-login, and the relay is newest-wins,
	// so a bare number extracted from one would be injected and fail the login.
	// Requiring a verification cue means only a genuine code is ever forwarded.
	// (Also handles alphanumeric + variable-length codes.)
	code, err := otp.ExtractCode(body, otp.Options{RequireContext: true})
	if err != nil {
		return ""
	}
	return code
}

// GenerateDeviceKey returns a fresh base64 Ed25519 private key for the native
// shell to persist. NOTE: this is SOFTWARE key material — generated here in Go and
// handed back as bytes — NOT a hardware-bound, non-exportable Secure Enclave /
// Android Keystore key. The shell protects it AT REST inside the hardware-backed
// keychain / EncryptedSharedPreferences (a Keystore master key wraps it), but the
// private half is decrypted into app memory to sign each release, so exposure
// requires code execution already inside the app sandbox. Do not describe it as
// enclave-resident.
func GenerateDeviceKey() (string, error) {
	_, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(priv), nil
}

// DevicePublicKey returns the base64 PUBLIC half of a device key, for pairing.
func DevicePublicKey(deviceKeyB64 string) (string, error) {
	key, err := decodeDeviceKey(deviceKeyB64)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(key.Public().(ed25519.PublicKey)), nil
}

// PairResult is what Pair yields. gomobile binds it as a class with a getter per
// field (Swift `MobilePairResult.deviceToken` / `.serverPubkey`; Kotlin
// `PairResult.getDeviceToken()` / `.getServerPubkey()`).
//
// The native side MUST persist BOTH: the token authenticates the device to the
// hub, and ServerPubkey authenticates the hub's pings to the device.
// Persisting only the token yields a device that declines every ping.
type PairResult struct {
	DeviceID    string
	DeviceToken string
	// ServerPubkey is the base64 Ed25519 key every SecretPing is verified against.
	// It is EMPTY when the server has no device-relay signing key configured; such
	// a server also refuses to dispatch pings, so the device is simply unable to
	// relay until both sides are configured and it re-pairs.
	ServerPubkey string
}

// Pair redeems a single-use pairing code (minted at your hub) and returns
// the long-lived device token PLUS the server's ping-signing public key. Only the
// device's PUBLIC key is sent. pairURL is the server's /devices/pair/complete
// endpoint.
func Pair(pairURL, pairingCode, deviceName, platform, devicePubkeyB64 string) (*PairResult, error) {
	pub, err := base64.StdEncoding.DecodeString(devicePubkeyB64)
	if err != nil {
		return nil, errors.New("mobile: device pubkey not base64")
	}
	res, err := agent.CompletePairing(context.Background(), pairURL, pairingCode, deviceName, platform, ed25519.PublicKey(pub))
	if err != nil {
		return nil, err
	}
	return &PairResult{
		DeviceID:     res.DeviceID,
		DeviceToken:  res.DeviceToken,
		ServerPubkey: res.ServerPubkeyB64,
	}, nil
}

// Unpair unlinks THIS device from the user's account server-side — the network
// half of the app's "Sign out". After it returns nil the device row is revoked,
// its hub socket is dropped, and the token is dead.
//
// Only the token identifies the caller — there is no device-id parameter — so
// this can never unlink another device. A non-2xx response is returned as an
// error: the shell MUST NOT clear its local credentials on failure without
// telling the user the account is still linked. Signature stays gomobile-safe
// (strings + error).
func Unpair(hubURL, deviceToken string) error {
	return agent.RevokeSelf(context.Background(), hubURL, deviceToken)
}

// --- internals (not part of the bound surface) ---

func decodeDeviceKey(b64 string) (ed25519.PrivateKey, error) {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, errors.New("mobile: device key not base64")
	}
	if len(raw) != ed25519.PrivateKeySize {
		return nil, errors.New("mobile: device key wrong size")
	}
	return ed25519.PrivateKey(raw), nil
}

func approveAdapter(appr Approver, codeSink CodeExpectationSink) func(context.Context, protocol.SecretPing) (string, bool) {
	// This runs inside agent.serveOne AFTER verifyPing + the replay-guard, so a
	// ping reaching here is authenticated and non-replayed — the safe place to act
	// on its kind (never off the raw read loop, which sees unverified frames).
	return func(_ context.Context, ping protocol.SecretPing) (string, bool) {
		switch ping.SecretKind {
		case protocol.SecretUsername, protocol.SecretPassword, protocol.SecretTOTPCode:
			// A durable secret release, auto-approved on every shell (no per-release
			// user interaction). The bound Approver returns only a boolean;
			// the Go core seals the value.
			if appr == nil {
				return "", false
			}
			return "", appr.Approve(ping.Site, string(ping.SecretKind))
		case protocol.SecretSMSOTPCode:
			// The SMS code itself never rides this lane — it arrives out-of-band via
			// POST /devices/sms-relay/manual (OtpDelivery), so we always decline the
			// release. But this authenticated ping IS the signal that a login now
			// awaits a texted code: surface it so the native SMS reader arms for the
			// ping's TTL and inspects an incoming text only during that window.
			// SMS reading is a passive, code-only path.
			if codeSink != nil {
				codeSink.OnExpectingSMSCode(ping.Site, ping.TTLSeconds)
			}
			return "", false
		default:
			// email/manual code kinds are not served on this lane.
			return "", false
		}
	}
}

// sourceStore adapts the native SecretSource to store.Store. It is read-only:
// the native side owns writes (enrollment happens in the shell UI, into the
// enclave), so Put/Delete are rejected. ListSites reflects what the native side
// reports it holds.
type sourceStore struct{ src SecretSource }

type recordJSON struct {
	Username string       `json:"username"`
	Password string       `json:"password"`
	TOTP     *totp.Config `json:"totp"`
}

func (s sourceStore) Get(site string) (store.Record, error) {
	raw := s.src.Lookup(site)
	if raw == "" {
		return store.Record{}, store.ErrNotFound
	}
	var r recordJSON
	if err := json.Unmarshal([]byte(raw), &r); err != nil {
		return store.Record{}, errors.New("mobile: SecretSource returned invalid JSON")
	}
	return store.Record{Site: site, Username: r.Username, Password: r.Password, TOTP: r.TOTP}, nil
}

func (sourceStore) Put(store.Record) error { return errors.New("mobile: store is read-only") }
func (sourceStore) Delete(string) error    { return errors.New("mobile: store is read-only") }

// ListSites asks the native side which domains it holds, parsing the JSON array
// string it returns. An empty string tolerates as "none" (empty slice).
func (s sourceStore) ListSites() ([]string, error) {
	raw := s.src.ListSites()
	if raw == "" {
		return nil, nil
	}
	var domains []string
	if err := json.Unmarshal([]byte(raw), &domains); err != nil {
		return nil, errors.New("mobile: SecretSource.ListSites returned invalid JSON")
	}
	return domains, nil
}
