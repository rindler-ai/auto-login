package tunnelclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/coder/websocket"
	"github.com/hashicorp/yamux"
)

const DaemonVersion = "0.1.0"

// ErrRevoked is returned when the gateway rejects our token as invalid.
var ErrRevoked = errors.New("token revoked or invalid")

// ErrUpgradeRequired is returned when the gateway rejects this daemon version.
// Callers should exit with an actionable update message, not retry forever.
var ErrUpgradeRequired = errors.New("daemon upgrade required")

// UpgradeRequiredError carries optional gateway version metadata while still
// matching ErrUpgradeRequired through errors.Is.
type UpgradeRequiredError struct {
	MinDaemonVersion string
	AcceptedVersion  int
}

func (e *UpgradeRequiredError) Error() string {
	if e == nil {
		return ErrUpgradeRequired.Error()
	}
	msg := ErrUpgradeRequired.Error()
	if e.MinDaemonVersion != "" {
		msg += ": minimum daemon version " + e.MinDaemonVersion
	}
	if e.AcceptedVersion > 0 {
		msg += fmt.Sprintf(" (accepted protocol v%d)", e.AcceptedVersion)
	}
	return msg
}

func (e *UpgradeRequiredError) Unwrap() error { return ErrUpgradeRequired }

// Options configures one RunOnce invocation.
type Options struct {
	Token   string
	Gateway string // e.g. "wss://gateway.example" or "ws://127.0.0.1:NNNN" (tests)
	Name    string
	// AllowPrivateEgress, when true, disables the deny-list that refuses
	// gateway-requested CONNECT targets resolving to RFC1918, loopback,
	// link-local (incl. cloud metadata 169.254.169.254), or multicast.
	// Off by default: the daemon assumes the gateway should never need to
	// reach the operator's local network. Operators using the daemon as a
	// deliberate jump-box can opt in via tunnel.toml.
	AllowPrivateEgress bool

	// OnConnected, if set, is called once the gateway handshake succeeds (the
	// tunnel is live and serving streams). Callers use it to surface a truthful
	// "connected" state instead of an optimistic "started". It is NOT called on a
	// dial/handshake failure; RunOnce simply returns an error in that case.
	OnConnected func()
}

type hello struct {
	V             int    `json:"v"`
	Token         string `json:"token"`
	Name          string `json:"name"`
	DaemonVersion string `json:"daemon_version"`
}

type reply struct {
	OK               bool   `json:"ok"`
	DaemonID         string `json:"daemon_id,omitempty"`
	AssignedName     string `json:"assigned_name,omitempty"`
	Error            string `json:"error,omitempty"`
	AcceptedVersion  int    `json:"accepted_version,omitempty"`
	MinDaemonVersion string `json:"min_daemon_version,omitempty"`
}

func rejectError(r reply) error {
	switch r.Error {
	case "invalid_token":
		return ErrRevoked
	case "upgrade_required":
		return &UpgradeRequiredError{
			MinDaemonVersion: r.MinDaemonVersion,
			AcceptedVersion:  r.AcceptedVersion,
		}
	default:
		return fmt.Errorf("gateway rejected: %s", r.Error)
	}
}

// RunOnce dials, handshakes, and serves streams until the session closes or
// ctx is cancelled. Returns ErrRevoked on invalid_token and ErrUpgradeRequired
// on upgrade_required replies (caller should exit, not retry). Any other error
// means retry with backoff.
func RunOnce(ctx context.Context, opts Options) error {
	dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	conn, _, err := websocket.Dial(dialCtx, opts.Gateway+"/tunnel/connect", nil)
	cancel()
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}
	// The close frame is the gateway's only signal of a graceful departure;
	// log a failure to send it. Debug level because a remote-initiated
	// disconnect (the common reconnect case) always fails this close with
	// "already closed", which is expected noise.
	defer func() {
		if cerr := conn.Close(websocket.StatusGoingAway, "shutdown"); cerr != nil {
			slog.Debug("websocket close", "err", cerr)
		}
	}()

	h, _ := json.Marshal(hello{
		V:             1,
		Token:         opts.Token,
		Name:          opts.Name,
		DaemonVersion: DaemonVersion,
	})
	if err := conn.Write(ctx, websocket.MessageText, h); err != nil {
		return fmt.Errorf("write hello: %w", err)
	}
	_, respBytes, err := conn.Read(ctx)
	if err != nil {
		return fmt.Errorf("read reply: %w", err)
	}
	var r reply
	if err := json.Unmarshal(respBytes, &r); err != nil {
		return fmt.Errorf("unmarshal reply: %w", err)
	}
	if !r.OK {
		return rejectError(r)
	}
	slog.Info("tunnel connected", "daemon_id", r.DaemonID, "name", r.AssignedName)
	if opts.OnConnected != nil {
		opts.OnConnected()
	}

	nc := websocket.NetConn(ctx, conn, websocket.MessageBinary)
	sess, err := yamux.Client(nc, nil)
	if err != nil {
		return fmt.Errorf("yamux: %w", err)
	}
	// Teardown at exit: by now the remote closed the session or ctx was
	// cancelled, so the close error carries no signal (no data loss risk;
	// streams own their byte copying).
	defer func() { _ = sess.Close() }()

	dialer := newCheckedDialer(opts.AllowPrivateEgress)
	for {
		stream, err := sess.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return nil // session closed by remote
		}
		go HandleStream(ctx, stream, dialer)
	}
}
