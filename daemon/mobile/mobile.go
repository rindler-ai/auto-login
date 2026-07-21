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
	"sync/atomic"
	"time"

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

// CodeExpectationSink is an OPTIONAL native hook that turns a swallowed
// sms_otp_code / email_otp_code SecretPing into an "arm the reader" signal. The Go
// core calls OnExpectingSMSCode / OnExpectingEmailCode only when an AUTHENTICATED,
// replay-checked ping of that kind arrives — i.e. a login is now, for up to
// ttlSeconds, waiting for a texted (SMS) or emailed 2FA code. The native reader uses
// this to read the corresponding channel ONLY during that window and ignore it
// otherwise, so the app is exposed to the user's SMS / mailbox solely while a real
// login awaits a code — never the general stream. A nil sink is fine (the signal is
// simply dropped).
//
// OnExpectingEmailCode is ADDITIVE: the proven SMS path (OnExpectingSMSCode) is
// unchanged, and Start still takes a single codeSink — the native class implements
// both methods, so no bound-signature growth. Unlike SMS (a passive receiver the OS
// pushes even when the app is closed), email has no OS broadcast, so the native side
// must actively drive a bounded on-device mailbox poll while this window is armed.
type CodeExpectationSink interface {
	OnExpectingSMSCode(site string, ttlSeconds int)
	OnExpectingEmailCode(site string, ttlSeconds int)
}

// Session is a running custody agent. Stop ends it and drops the hub connection.
// revoked records that Run stopped on a PERMANENT hub rejection (this device was
// revoked/unlinked server-side) rather than a transient drop it keeps retrying.
type Session struct {
	cancel  context.CancelFunc
	revoked atomic.Bool
}

// Stop tears the session down (idempotent).
func (s *Session) Stop() {
	if s != nil && s.cancel != nil {
		s.cancel()
	}
}

// Revoked reports whether the relay stopped because the hub PERMANENTLY rejected
// this device — its row was revoked/unlinked server-side (a web sign-out, or the
// 30-day-inactivity unlink), so the token is dead and retrying can never reconnect.
// It mirrors EgressSession.Terminated(): the shell polls it and, when true, signs
// the user out (wipes local identity, returns to the Sign-in screen).
//
// It is set ONLY on an actual hub hello-rejection. A network drop (offline, airplane
// mode, a backgrounded radio) leaves the session retrying and Revoked() false, so
// losing connectivity NEVER signs the user out. gomobile-safe (bool return, no args).
func (s *Session) Revoked() bool {
	return s != nil && s.revoked.Load()
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
	sess := &Session{cancel: cancel}
	go func() {
		// Run returns only when Stop cancels ctx OR the hub PERMANENTLY rejected this
		// device's hello (revoked/unauthorized). Flag the revoke case so the shell can
		// poll Revoked() and sign out; a Stop() cancel or any transient path leaves the
		// flag false. IsPermanentAuthRejection is the airplane-mode-safe classifier —
		// a dial/read error while offline is NOT a rejection, so it never flips this.
		err := agent.Run(ctx, cfg)
		if agent.IsPermanentAuthRejection(err) {
			sess.revoked.Store(true)
		}
	}()
	return sess, nil
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

// Mailbox-read errors surfaced by FetchEmailOTPOnce. They are TYPED (not a bare "")
// so the native reader can tell a dead credential apart from a transient blip and a
// steady "no code yet": a revoked app-password must surface as a BROKEN mailbox (a
// warning badge + a one-shot notice), never as an infinite silent retry that stalls
// the login with zero signal. gomobile flattens a Go error to a Java Exception whose
// message is Error(); the native side classifies on these stable messages.
var (
	// ErrMailboxAuth: the mailbox rejected the app-password (revoked or wrong). The
	// shell must surface this as a broken mailbox and stop polling it — retrying a
	// dead credential can never succeed. Static message: never echoes the credential.
	ErrMailboxAuth = errors.New("email-otp: mailbox rejected the app password")
	// ErrMailboxUnavailable: a transient failure reaching the mailbox (dial / search /
	// fetch / timeout). The shell may keep polling within the armed window; it
	// self-heals, so this must NOT brand the mailbox as broken.
	ErrMailboxUnavailable = errors.New("email-otp: mailbox temporarily unreachable")
)

// FetchEmailOTPOnce reads the user's linked mailbox ON THE DEVICE over IMAP and
// returns ONLY the single extracted one-time code ("" when none has arrived yet),
// reusing the SAME otp.IMAPMailbox client + otp.ExtractCode extractor the server and
// desktop daemon use — so every platform shares ONE reader and ONE notion of "what
// counts as a code," and the native shells implement no IMAP or extraction of their
// own.
//
// host/user/appPassword are the mailbox credential the NATIVE side holds in the
// keystore and passes in per read; they are DURABLE secrets that stay on the device
// and NEVER transit — only the returned code is ever eligible to be relayed (via
// SmsRelayClient.submitEmailOtpCode → POST /devices/email-relay/manual). fromContains
// restricts candidates to the expected OTP sender for the site; sinceEpochSec bounds
// the read to mail that arrived at/after the arm time; timeoutSeconds bounds a single
// windowed poll (0 = one pass, then return).
//
// The native side calls this ONLY inside an armed, verified window
// (OnExpectingEmailCode fired this process-life). Errors are TYPED (see ErrMailboxAuth
// / ErrMailboxUnavailable): a mailbox that rejects its app-password surfaces as broken
// instead of hiding behind an empty string. "No code yet" is NOT an error — it returns
// ("", nil), the steady state a polling reader expects.
func FetchEmailOTPOnce(host, user, appPassword, fromContains string, sinceEpochSec, timeoutSeconds int64) (string, error) {
	mbox := otp.IMAPMailbox{Host: host, User: user, Password: appPassword}
	return fetchEmailOTP(mbox, fromContains, sinceEpochSec, timeoutSeconds)
}

// VerifyMailboxLogin checks a mailbox app-password by authenticating ONLY — no
// SEARCH, FETCH, or body read. The Link screen calls THIS (not FetchEmailOTPOnce)
// to validate a credential, so linking a mailbox never reads the user's mail
// outside an armed login window. Error is TYPED via classifyMailboxErr and never
// echoes the credential; nil means the login succeeded.
func VerifyMailboxLogin(host, user, appPassword string) error {
	mbox := otp.IMAPMailbox{Host: host, User: user, Password: appPassword}
	if err := mbox.VerifyLogin(); err != nil {
		return classifyMailboxErr(err)
	}
	return nil
}

// fetchEmailOTP is the seam FetchEmailOTPOnce delegates to, taking a MailboxReader so
// the freshness / sender bounds and the typed-error classification are unit-tested
// against otp.FakeMailbox without any network. It builds the FetchOptions (RequireContext
// forced on — a bare number without a verification cue must never be relayed), runs the
// reader, and maps its outcome onto the three device-relevant classes.
func fetchEmailOTP(reader otp.MailboxReader, fromContains string, sinceEpochSec, timeoutSeconds int64) (string, error) {
	opts := otp.FetchOptions{
		FromContains: fromContains,
		Extract:      otp.Options{RequireContext: true},
	}
	if sinceEpochSec > 0 {
		opts.Since = time.Unix(sinceEpochSec, 0)
	}
	if timeoutSeconds > 0 {
		opts.Timeout = time.Duration(timeoutSeconds) * time.Second
	}
	code, err := reader.FetchLatestOTP(context.Background(), opts)
	if err == nil {
		// A found code (FetchLatestOTP only returns nil with a non-empty code).
		return code, nil
	}
	return "", classifyMailboxErr(err)
}

// classifyMailboxErr maps a raw otp/IMAP error onto the device-relevant classes,
// shared by fetchEmailOTP and VerifyMailboxLogin so both agree on what "broken"
// vs "transient" vs "fine" means. ErrNoCode (no matching mail yet) and a nil error
// are both the healthy steady state and map to nil.
func classifyMailboxErr(err error) error {
	switch {
	case err == nil, errors.Is(err, otp.ErrNoCode):
		return nil
	case errors.Is(err, otp.ErrIMAPAuth):
		// Revoked / wrong app-password — the mailbox is broken until re-linked.
		return ErrMailboxAuth
	default:
		// Dial / search / fetch / timeout — transient; the caller may retry.
		return ErrMailboxUnavailable
	}
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
//
// androidID is the RAW stable OS device id (Android Settings.Secure.ANDROID_ID) read
// fresh from the OS at pair time — the durable identity the server salts+hashes to
// dedup a re-pair (reinstall/sign-out) to ONE record. The native shell reads it fresh
// each pairing and NEVER persists it (sign-out/reset wipes app storage, so a stored
// value could not survive; only an OS-provided id does). Pass "" if none.
func Pair(pairURL, pairingCode, deviceName, platform, devicePubkeyB64, androidID string) (*PairResult, error) {
	pub, err := base64.StdEncoding.DecodeString(devicePubkeyB64)
	if err != nil {
		return nil, errors.New("mobile: device pubkey not base64")
	}
	res, err := agent.CompletePairing(context.Background(), pairURL, pairingCode, deviceName, platform, androidID, ed25519.PublicKey(pub))
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
	err := agent.RevokeSelf(context.Background(), hubURL, deviceToken)
	// Unpair's contract is "ensure this device is off the account", so a device
	// the server already considers gone satisfies it. Collapsed to nil HERE rather
	// than in RevokeSelf so the Go core keeps the distinction (and can be tested on
	// it), while the shell — which only gets a bare string across gomobile and
	// could not match reliably on it — sees the plain success it should act on.
	if errors.Is(err, agent.ErrAlreadyUnlinked) {
		return nil
	}
	return err
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
		case protocol.SecretEmailOTPCode:
			// Identical shape to the SMS case (arm-and-decline). The email code never
			// rides this lane either — it is read ON THE DEVICE from the user's linked
			// mailbox and handed back out-of-band via POST /devices/email-relay/manual,
			// so we ALWAYS decline the release ("", false): nothing is ever sealed over
			// this HPKE ping. This authenticated ping IS the signal that a login now
			// awaits an emailed code: surface it so the native mailbox reader arms for
			// the ping's TTL and actively polls the linked inbox ONLY during that window.
			if codeSink != nil {
				codeSink.OnExpectingEmailCode(ping.Site, ping.TTLSeconds)
			}
			return "", false
		default:
			// manual code kind is not served on this lane.
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
