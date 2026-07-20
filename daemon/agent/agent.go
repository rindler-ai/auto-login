package agent

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"time"

	"github.com/coder/websocket"

	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/store"
)

// wire is the JSON control envelope, byte-compatible with the server hub's wire
// type (the server). The daemon speaks: hello -> hello_ok,
// then answers each ping with a release (or declined).
type wire struct {
	Type           string                         `json:"type"`
	Token          string                         `json:"token,omitempty"`
	V              int                            `json:"v,omitempty"`
	DeviceID       string                         `json:"device_id,omitempty"`
	Error          string                         `json:"error,omitempty"`
	RequestID      string                         `json:"request_id,omitempty"`
	Ping           *protocol.SecretPing           `json:"ping,omitempty"`
	Release        *protocol.SecretRelease        `json:"release,omitempty"`
	Inventory      *protocol.SiteInventoryRequest `json:"inventory,omitempty"`
	InventoryReply *protocol.SiteInventoryReply   `json:"inventory_reply,omitempty"`
}

// Config is what the daemon needs to run: where the hub is, the device bearer
// token (from pairing), the device signing key, the server's ping-signing public
// key (also from pairing), and the credential store.
type Config struct {
	HubURL      string // wss://.../v1/devices/connect
	DeviceToken string
	DeviceKey   ed25519.PrivateKey
	// ServerPubkey authenticates every incoming SecretPing. It is handed
	// to the device once, at pairing (PairResult.ServerPubkeyB64), and persisted
	// next to the device token. The server's signature covers
	// worker_ephemeral_pubkey, so verifying it is what stops an on-path party from
	// substituting its own recipient key and harvesting the sealed credential.
	//
	// FAIL CLOSED: a zero ServerPubkey declines EVERY ping (see serveOne). There is
	// no unsigned-ping fallback — that would reinstate the attack. A device paired
	// before holds no server key and must re-pair.
	ServerPubkey ed25519.PublicKey
	Store        store.Store
	// Approve authorizes each release. Every relay shell auto-approves a
	// cryptographically-verified ping without any per-release user interaction
	// (frictionless by design). Return (suppliedCode, ok). ok=false
	// declines. nil fails closed: the daemon may stay connected for inventory
	// queries, but releases nothing.
	Approve func(ctx context.Context, ping protocol.SecretPing) (code string, ok bool)
	Log     *slog.Logger
}

const (
	helloTimeout = 10 * time.Second
	writeTimeout = 5 * time.Second
	minBackoff   = 1 * time.Second
	maxBackoff   = 60 * time.Second
)

// pingInterval / pingTimeout drive the liveness heartbeat (see heartbeat). The
// hub keeps an idle connection open forever (no server read deadline) and TCP
// keepalives default to ~2h, so without this a socket the OS/carrier silently
// dropped while the phone was backgrounded is never noticed: Read blocks forever
// and Run never reconnects, leaving the device registered-but-dead. 30s is a
// deliberate battery-vs-latency balance — frequent enough that a drop is caught
// (and the connection stays reachable) within ~40s of a login request, sparse
// enough that the ping payload is negligible next to holding the socket at all.
// The zero-idle-cost successor is FCM push-to-wake (disconnect when idle). They
// are vars, not consts, only so a test can shorten them.
var (
	pingInterval = 30 * time.Second
	pingTimeout  = 10 * time.Second
)

// ValidateHubURL rejects a hub URL that would carry the device bearer token and
// every relayed secret over a cleartext socket. The relay channel MUST be wss://
// (TLS): the hello frame ships the long-lived device token in the clear, and the
// SecretPing is unauthenticated, so a plaintext ws:// to a real hub lets an on-path
// attacker read the token AND inject a ping carrying its own worker key to harvest
// the sealed secret. ws:// is tolerated ONLY for a loopback host (local dev), never
// a remote one. (credential-safety review 2026-07-14, finding F3.)
func ValidateHubURL(hubURL string) error {
	u, err := url.Parse(hubURL)
	if err != nil {
		return fmt.Errorf("custody: invalid hub URL: %w", err)
	}
	switch u.Scheme {
	case "wss":
		return nil
	case "ws":
		switch u.Hostname() {
		case "localhost", "127.0.0.1", "::1":
			return nil
		default:
			return fmt.Errorf("custody: refusing plaintext ws:// hub %q — the device token and relayed secrets must not cross a cleartext socket; use wss://", u.Host)
		}
	default:
		return fmt.Errorf("custody: hub URL must be wss:// (got scheme %q)", u.Scheme)
	}
}

// Run holds one outbound connection to the hub, reconnecting with jittered
// backoff (tunnel-daemon lifecycle). It blocks until ctx is cancelled.
func Run(ctx context.Context, cfg Config) error {
	log := cfg.Log
	if log == nil {
		log = slog.Default()
	}
	// Fail closed before dialing: a cleartext relay channel would leak the device
	// token + every relayed secret (never loop-retry an insecure URL).
	if err := ValidateHubURL(cfg.HubURL); err != nil {
		log.Error("custody: refusing to connect", "err", err)
		return err
	}
	// Say it once, loudly, at startup rather than only per declined ping: a device
	// with no server key is connected but cannot relay anything. It still
	// serves the non-secret inventory query, so the connection is worth keeping.
	if len(cfg.ServerPubkey) != ed25519.PublicKeySize {
		log.Warn("custody: no server public key — EVERY secret ping will be declined; re-pair this device")
	}
	// The replay guard is daemon-lifetime (survives reconnects) so a ping
	// replayed on a fresh connection is still refused.
	guard := newReplayGuard(replayWindow)
	backoff := minBackoff
	for ctx.Err() == nil {
		err := runOnce(ctx, cfg, log, guard)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		// A PERMANENT auth rejection (the hub answered the handshake but refused this
		// device — its token was revoked/unlinked/unauthorized) will never succeed by
		// retrying, so STOP the loop and return the error. The shell polls
		// Session.Revoked() (via IsPermanentAuthRejection) and signs the user out. A
		// transient dial/read error (offline, airplane mode, a backgrounded radio) is
		// NOT this: it falls through to backoff-and-retry, so losing the network never
		// signs anyone out. This split is the whole revoke-vs-offline invariant.
		if isPermanentAuthRejection(err) {
			log.Warn("custody: hub rejected this device — token revoked/unauthorized; stopping relay (re-pair to reconnect)")
			return err
		}
		log.Warn("custody: hub connection closed, reconnecting", "err", err, "backoff", backoff)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}
		if backoff *= 2; backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
	return ctx.Err()
}

func runOnce(ctx context.Context, cfg Config, log *slog.Logger, guard *replayGuard) error {
	c, _, err := websocket.Dial(ctx, cfg.HubURL, nil)
	if err != nil {
		return err
	}
	defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()

	// Hello handshake.
	hctx, cancel := context.WithTimeout(ctx, helloTimeout)
	err = writeWire(hctx, c, wire{Type: "hello", Token: cfg.DeviceToken, V: protocol.ProtocolVersion})
	cancel()
	if err != nil {
		return err
	}
	var reply wire
	if err := readWire(ctx, c, &reply); err != nil {
		return err
	}
	if reply.Type != "hello_ok" || reply.Error != "" {
		return &helloError{reply.Error}
	}
	log.Info("custody: connected to hub", "device_id", reply.DeviceID)

	// Heartbeat detects a silently-dropped connection and forces a reconnect. It
	// runs in its own goroutine and cancels serveCtx when the link goes dead, which
	// unblocks the Read below so runOnce returns and Run's backoff loop redials.
	serveCtx, cancelServe := context.WithCancel(ctx)
	defer cancelServe()
	// pingInterval/pingTimeout are read HERE (synchronously, in this goroutine)
	// rather than inside heartbeat, so a test that swaps them can't race the
	// spawned goroutine's read.
	go heartbeat(serveCtx, c, cancelServe, pingInterval, pingTimeout, log)

	// Approval may block for human input. Keep it off the sole websocket Read
	// loop: coder/websocket's Ping waits for that reader to process its pong, so a
	// synchronous approval would make the heartbeat tear down a healthy link. One
	// worker serializes prompts; one queued follow-up supports username -> password
	// recipes without allowing a prompt flood.
	pings := make(chan protocol.SecretPing, 1)
	go servePings(serveCtx, c, cfg, log, guard, pings)

	// Serve hub frames until the connection drops (or heartbeat cancels serveCtx).
	for {
		var msg wire
		if err := readWire(serveCtx, c, &msg); err != nil {
			return err
		}
		switch msg.Type {
		case "ping":
			if msg.Ping != nil {
				select {
				case pings <- *msg.Ping:
				default:
					log.Warn("custody: approval queue full, declining ping", "request_id", msg.Ping.RequestID)
					_ = writeWire(serveCtx, c, wire{Type: "declined", RequestID: msg.Ping.RequestID})
				}
			}
		case "inventory":
			serveInventory(serveCtx, c, cfg, msg, log)
		}
	}
}

func servePings(
	ctx context.Context,
	c *websocket.Conn,
	cfg Config,
	log *slog.Logger,
	guard *replayGuard,
	pings <-chan protocol.SecretPing,
) {
	for {
		select {
		case <-ctx.Done():
			return
		case ping := <-pings:
			if ctx.Err() != nil {
				return
			}
			serveOne(ctx, c, cfg, ping, log, guard)
		}
	}
}

// heartbeat pings the hub every interval. coder/websocket answers a control ping
// with a pong via the Read loop that runOnce is already running, so a ping that
// isn't answered within timeout proves the connection dead (a half-open socket
// the OS never surfaced) — it then cancels the serve context to unblock Read and
// let Run reconnect. Sending the ping also keeps NAT/carrier idle timeouts from
// quietly parking the socket while the phone is backgrounded.
func heartbeat(ctx context.Context, c *websocket.Conn, onDead func(), interval, timeout time.Duration, log *slog.Logger) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			pctx, cancel := context.WithTimeout(ctx, timeout)
			err := c.Ping(pctx)
			cancel()
			if err != nil {
				if ctx.Err() != nil {
					return // shutdown/redial in progress, not a dead link
				}
				log.Warn("custody: heartbeat ping failed, forcing reconnect", "err", err)
				onDead()
				return
			}
		}
	}
}

// serveInventory answers the non-secret site-inventory query: the domains the
// device currently holds a login for, so the server can prefer the device-relay
// lane over a hosted Connect browser. It NEVER sends a credential, needs no
// approval gate, and stays fast (a single store index read). The request_id is
// echoed BOTH top-level and inside the nested reply, per the wire contract.
func serveInventory(ctx context.Context, c *websocket.Conn, cfg Config, msg wire, log *slog.Logger) {
	reqID := msg.RequestID
	if msg.Inventory != nil && msg.Inventory.RequestID != "" {
		reqID = msg.Inventory.RequestID
	}
	domains, err := cfg.Store.ListSites()
	if err != nil {
		// Best-effort: a store that cannot enumerate answers with an empty list
		// rather than dropping the query. The error never carries a secret.
		log.Warn("custody: cannot list sites for inventory", "err", err)
		domains = nil
	}
	if domains == nil {
		domains = []string{} // marshal an empty JSON array, never null
	}
	reply := protocol.SiteInventoryReply{RequestID: reqID, Domains: domains}
	if err := writeWire(ctx, c, wire{Type: "inventory_reply", RequestID: reqID, InventoryReply: &reply}); err != nil {
		log.Warn("custody: inventory_reply write failed", "err", err)
	}
}

// serveOne handles a single ping: authenticate it, refuse a replay, gate on
// approval, build the signed release, and send it (or a decline). Best-effort —
// a failure to build declines rather than dropping the connection.
func serveOne(ctx context.Context, c *websocket.Conn, cfg Config, ping protocol.SecretPing, log *slog.Logger, guard *replayGuard) {
	// — AUTHENTICATE FIRST, before anything else touches this ping.
	//
	// The server's signature covers worker_ephemeral_pubkey, the key we would seal
	// the credential to. An on-path party that rewrites that field is caught here,
	// and a caught ping is declined outright: it does NOT prompt the user (never
	// ask a human to approve a ping we already know is forged — the approval UI
	// shows only site + kind, so no human could catch the swap anyway), it does NOT
	// read the credential store, and it seals NOTHING.
	//
	// It also runs before the replay guard so a forged ping cannot burn the
	// request_id of a legitimate one still in flight.
	if err := verifyPing(ping, cfg.ServerPubkey); err != nil {
		// The reason is safe to log and worth logging: it is the only signal that a
		// key substitution (or an unpaired-for- device) was refused. It carries
		// no secret, no key bytes, and no sealed payload.
		log.Warn("custody: declining unauthenticated ping",
			"request_id", ping.RequestID, "site", ping.Site, "kind", ping.SecretKind, "reason", err)
		_ = writeWire(ctx, c, wire{Type: "declined", RequestID: ping.RequestID})
		return
	}
	// Replay defense: a request_id already answered is never released a second
	// time (checked before the approver runs, so a replay can't even reach it).
	if guard != nil && !guard.firstSee(ping.RequestID) {
		log.Warn("custody: refusing replayed ping", "request_id", ping.RequestID, "site", ping.Site)
		_ = writeWire(ctx, c, wire{Type: "declined", RequestID: ping.RequestID})
		return
	}
	// A missing approval surface must never become an approval policy. Decline
	// before touching the credential store so headless/background clients cannot
	// even load plaintext as a side effect of an unapprovable ping.
	if cfg.Approve == nil {
		_ = writeWire(ctx, c, wire{Type: "declined", RequestID: ping.RequestID})
		return
	}
	code, ok := cfg.Approve(ctx, ping)
	if !ok {
		_ = writeWire(ctx, c, wire{Type: "declined", RequestID: ping.RequestID})
		return
	}
	if ctx.Err() != nil {
		return
	}
	rel, err := handlePing(ping, cfg.Store, cfg.DeviceKey, cfg.ServerPubkey, code)
	if err != nil {
		// The secret is never in err (relay wraps HPKE/lookup errors only), but be
		// explicit: log the kind/site, not the value.
		log.Warn("custody: cannot fulfill ping", "site", ping.Site, "kind", ping.SecretKind, "err", err)
		_ = writeWire(ctx, c, wire{Type: "declined", RequestID: ping.RequestID})
		return
	}
	if err := writeWire(ctx, c, wire{Type: "release", RequestID: ping.RequestID, Release: &rel}); err != nil {
		log.Warn("custody: release write failed", "err", err)
	}
}

func writeWire(ctx context.Context, c *websocket.Conn, v wire) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	return c.Write(wctx, websocket.MessageText, b)
}

func readWire(ctx context.Context, c *websocket.Conn, v *wire) error {
	_, b, err := c.Read(ctx)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

type helloError struct{ msg string }

func (e *helloError) Error() string {
	if e.msg == "" {
		return "custody: hub rejected hello"
	}
	return "custody: hub rejected hello: " + e.msg
}

// isPermanentAuthRejection classifies a runOnce error. It is TRUE only for a
// *helloError — the hub completed the handshake but refused this device (its token
// was revoked/unlinked/unauthorized), so retrying the SAME token can never succeed
// and the relay must stop. It is FALSE for everything else: a dial failure, a read
// error, a dropped socket, a cancelled context — all TRANSIENT (offline / airplane
// mode / a parked radio), which MUST keep retrying. This one predicate carries the
// entire airplane-mode-safety invariant: only an actual hub rejection is permanent,
// never a network blip. errors.As (not a bare type assertion) so a wrapped dial
// error correctly reports false.
func isPermanentAuthRejection(err error) bool {
	var he *helloError
	return errors.As(err, &he)
}

// IsPermanentAuthRejection is the exported boundary the mobile shell uses to tell a
// real server-side revoke (→ sign the user out) apart from being offline (→ keep the
// session, keep retrying). It delegates to the pure decision above.
func IsPermanentAuthRejection(err error) bool {
	return isPermanentAuthRejection(err)
}
