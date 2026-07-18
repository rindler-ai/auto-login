package mobile

import (
	"context"
	"errors"
	"log"
	"sync/atomic"
	"time"

	tunnelclient "github.com/rindler-ai/auto-login/daemon/tunnelclient"
)

// mobile_egress.go — the gomobile surface for the per-user device-egress proxy.
// When the user turns the in-app toggle ON, the paired device runs a tunnel
// egress client: an outbound wss:// to the hub gateway, which dials TCP streams
// FROM this device's network — so the sites the user automates see THIS device's
// IP (the most legitimate residential IP there is), replacing the
// data-center/residential stealth tiers for that user's own agent sessions.
//
// The device only ever carries opaque TCP bytes to the gateway-requested target
// (site traffic is TLS to the site); it never sees credentials or agent context.
// The egress deny-list stays ON (AllowPrivateEgress=false) so the gateway can
// never pivot into the user's home LAN or a cloud metadata endpoint.

// EgressSession is a running device-egress tunnel. Stop() tears it down.
type EgressSession struct {
	cancel    context.CancelFunc
	connected atomic.Bool
}

// Stop ends the egress loop and closes the tunnel. Safe to call more than once.
func (s *EgressSession) Stop() {
	if s != nil && s.cancel != nil {
		s.cancel()
	}
}

// Connected reports whether the tunnel is currently live (the gateway handshake
// succeeded and RunOnce is serving). False while dialing/reconnecting or after a
// terminal failure — so the UI can show a TRUTHFUL "on" only when egress actually
// works, not merely because StartEgress was called.
func (s *EgressSession) Connected() bool {
	return s != nil && s.connected.Load()
}

// StartEgress connects this device to the tunnel gateway and keeps it connected
// (reconnecting with capped backoff) until Stop(). gatewayURL is the wss:// (or
// ws:// in tests) gateway; token is the rt_live_ device-egress token minted for
// this user; name is a device label shown in the egress device list.
//
// A revoked/upgrade-required token STOPS the loop (the app must re-mint or
// prompt an update) rather than hot-looping on a permanent rejection.
func StartEgress(gatewayURL, token, name string) (*EgressSession, error) {
	if gatewayURL == "" || token == "" {
		return nil, errors.New("gateway url and token are required")
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &EgressSession{cancel: cancel}
	go s.runEgressLoop(ctx, gatewayURL, token, name)
	return s, nil
}

func (s *EgressSession) runEgressLoop(ctx context.Context, gateway, token, name string) {
	const (
		baseBackoff = time.Second
		maxBackoff  = 60 * time.Second
	)
	backoff := baseBackoff
	for {
		if ctx.Err() != nil {
			return
		}
		err := tunnelclient.RunOnce(ctx, tunnelclient.Options{
			Token:              token,
			Gateway:            gateway,
			Name:               name,
			AllowPrivateEgress: false, // never let the gateway reach this device's LAN/metadata
			OnConnected:        func() { s.connected.Store(true) },
		})
		s.connected.Store(false) // RunOnce returned -> not connected (disconnected/failed/backing off)
		if ctx.Err() != nil {
			return // Stop() was called
		}
		if errors.Is(err, tunnelclient.ErrRevoked) || errors.Is(err, tunnelclient.ErrUpgradeRequired) {
			log.Printf("device-egress: terminal (%v) — stopping; re-mint/update required", err)
			return
		}
		// A clean return (nil) or a transient error both mean "reconnect".
		log.Printf("device-egress: disconnected (%v) — retrying in %s", err, backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < maxBackoff {
			if backoff *= 2; backoff > maxBackoff {
				backoff = maxBackoff
			}
		}
	}
}
