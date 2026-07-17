package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/rindler-ai/auto-login/core/store"
	"github.com/rindler-ai/auto-login/daemon/agent"
)

// The desktop custody daemon entrypoint. It wires the OS keychain (store +
// device identity) to the reusable agent core (package agent), which owns the
// hub WebSocket + ping→relay. The mobile shells drive the SAME agent core via
// package mobile, so behavior is identical everywhere.
//
// The headless desktop build has no approval UI, so releases fail closed
// (Approve=nil). Every native shell auto-approves a cryptographically-verified
// ping with NO per-release user interaction (AutoApprover on iOS/macOS/Android):
// a server-signed ping is authorized by construction, so releases are frictionless
// and hands-free — the autonomous-login-for-AI-agents design.
// Identity + store come from the keychain (loadIdentity/loadStore), pairing on
// first run.
func main() {
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg, err := loadConfig(ctx, log)
	if err != nil {
		log.Error("custody: startup config failed", "err", err)
		os.Exit(1)
	}
	log.Warn("custody: desktop approval UI unavailable; secret releases are disabled")

	if err := agent.Run(ctx, cfg); err != nil && ctx.Err() == nil {
		log.Error("custody: daemon exited", "err", err)
		os.Exit(1)
	}
}

// loadConfig assembles the runtime config from the environment + OS keychain.
// The native keychain (macOS `security` / Linux `secret-tool`) backs BOTH the
// credential store and the persisted device identity; when it is unavailable
// (unsupported OS or the CLI missing) the daemon falls back to an in-memory
// store and env/pairing identity — dev only, nothing persists across restarts.
func loadConfig(ctx context.Context, log *slog.Logger) (agent.Config, error) {
	hub := os.Getenv("RINDLER_HUB_URL")
	if hub == "" {
		hub = "wss://your-hub.example/v1/devices/connect"
	}

	kr, st := loadStore(log)

	id, err := loadIdentity(ctx, kr, hub, log)
	if err != nil {
		return agent.Config{}, err
	}
	return agent.Config{
		HubURL:      hub,
		DeviceToken: id.token,
		DeviceKey:   id.key,
		// From pairing. Empty on a device paired before this change, which then declines
		// every ping (agent.Run warns at startup) until it re-pairs.
		ServerPubkey: id.serverPub,
		Store:        st,
		Approve:      nil, // headless desktop has no approval UI, so releases are disabled
		Log:          log,
	}, nil
}

// loadStore returns the OS-keychain backend (nil when unavailable) and the
// store.Store to use: a KeyringStore over the native keychain, or MemStore as a
// dev fallback. The same backend is reused for the device identity.
func loadStore(log *slog.Logger) (keyringBackend, store.Store) {
	kr, err := newSystemBackend()
	if err != nil {
		log.Warn("custody: no OS keychain — using in-memory store (dev only, secrets not persisted)", "err", err)
		return nil, store.NewMemStore()
	}
	log.Info("custody: using OS keychain for credential storage", "service", keyringService)
	return kr, NewKeyringStore(kr)
}
