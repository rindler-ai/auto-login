package main

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"fmt"
	"log/slog"
	"os"
	"runtime"

	"github.com/rindler-ai/auto-login/daemon/agent"
)

// Reserved keyring accounts holding this device's persisted identity (shared
// "rindler-meta:" namespace with the site index; never a real site host).
const (
	acctDeviceToken  = "rindler-meta:device-token"
	acctDeviceKey    = "rindler-meta:device-key"    // base64 Ed25519 private key
	acctServerPubkey = "rindler-meta:server-pubkey" // base64 Ed25519 PUBLIC key
)

// identity is this device's persisted enrollment: the hub bearer token, the
// device signing key, and the server's ping-signing public key from pairing.
// serverPub may be empty on a device paired before this change (or against a server
// with no signing key configured) — such a device declines every ping.
type identity struct {
	token     string
	key       ed25519.PrivateKey
	serverPub ed25519.PublicKey
}

// loadIdentity returns the device bearer token, Ed25519 signing key, and the
// server's ping-verification public key, in priority order:
//
//  1. the OS keychain (persisted from a prior pairing) — the steady state;
//  2. explicit env (RINDLER_DEVICE_TOKEN + RINDLER_DEVICE_KEY [+ RINDLER_SERVER_PUBKEY])
//     — dev / pre-provisioned;
//  3. a one-time pairing: RINDLER_PAIRING_CODE (the code minted at
//     your hub → Settings → Devices) → POST /devices/pair/complete with a
//     freshly generated key, then persist token+key+server pubkey to the keychain.
//
// kr may be nil when no native keychain is available (dev fallback): identity
// then comes from env or an in-run pairing, and is NOT persisted across restarts.
func loadIdentity(ctx context.Context, kr keyringBackend, hubURL string, log *slog.Logger) (identity, error) {
	// 1. Keychain (steady state).
	if kr != nil {
		if id, ok := readKeyringIdentity(kr); ok {
			log.Info("custody: loaded device identity from keychain")
			return id, nil
		}
	}

	// 2. Explicit env.
	envTok := os.Getenv("RINDLER_DEVICE_TOKEN")
	if b64 := os.Getenv("RINDLER_DEVICE_KEY"); b64 != "" {
		k, derr := parseDeviceKey(b64)
		if derr != nil {
			return identity{}, derr
		}
		serverPub, perr := agent.ParseServerPubkey(os.Getenv("RINDLER_SERVER_PUBKEY"))
		if perr != nil {
			return identity{}, perr
		}
		if envTok != "" {
			return identity{token: envTok, key: k, serverPub: serverPub}, nil
		}
	}

	// 3. Pair on demand.
	if code := os.Getenv("RINDLER_PAIRING_CODE"); code != "" {
		return pairAndPersist(ctx, kr, hubURL, code, log)
	}

	return identity{}, fmt.Errorf("custody: no device identity — set RINDLER_PAIRING_CODE to pair this device, " +
		"or RINDLER_DEVICE_TOKEN + RINDLER_DEVICE_KEY for a pre-provisioned one")
}

// pairAndPersist generates a device key, redeems the pairing code, and (when a
// keychain is available) persists the resulting token + device key + server
// pubkey for next launch.
func pairAndPersist(ctx context.Context, kr keyringBackend, hubURL, code string, log *slog.Logger) (identity, error) {
	pub, priv, gerr := ed25519.GenerateKey(nil)
	if gerr != nil {
		return identity{}, fmt.Errorf("custody: generate device key: %w", gerr)
	}
	pairURL, perr := agent.PairURLFromHub(hubURL, os.Getenv("RINDLER_PAIR_URL"))
	if perr != nil {
		return identity{}, perr
	}
	name := os.Getenv("RINDLER_DEVICE_NAME")
	if name == "" {
		if h, herr := os.Hostname(); herr == nil {
			name = h
		} else {
			name = "custody-device"
		}
	}
	log.Info("custody: pairing this device", "pair_url", pairURL, "name", name, "platform", runtime.GOOS)
	// The headless desktop daemon has no OS-provided stable device id analogous to
	// Android's ANDROID_ID, so it sends "" and the server degrades to pubkey-only
	// dedup — fine here, since the desktop key persists in the OS keychain across
	// restarts (it does not rotate the way the mobile Keystore key does).
	res, err := agent.CompletePairing(ctx, pairURL, code, name, runtime.GOOS, "", pub)
	if err != nil {
		return identity{}, err
	}
	// CompletePairing already rejected a malformed key, so this cannot fail; an
	// empty one stays empty and every ping is declined.
	serverPub, err := agent.ParseServerPubkey(res.ServerPubkeyB64)
	if err != nil {
		return identity{}, err
	}
	id := identity{token: res.DeviceToken, key: priv, serverPub: serverPub}
	if kr != nil {
		if serr := writeKeyringIdentity(kr, id); serr != nil {
			log.Warn("custody: paired but could not persist identity to keychain", "err", serr)
		} else {
			log.Info("custody: paired and persisted identity", "device_id", res.DeviceID)
		}
	} else {
		log.Warn("custody: paired but no keychain to persist to — re-pair needed after restart", "device_id", res.DeviceID)
	}
	return id, nil
}

func parseDeviceKey(b64 string) (ed25519.PrivateKey, error) {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, fmt.Errorf("custody: device key not base64: %w", err)
	}
	if len(raw) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("custody: device key wrong size (%d, want %d)", len(raw), ed25519.PrivateKeySize)
	}
	return ed25519.PrivateKey(raw), nil
}

// readKeyringIdentity returns (identity, true) only when the token AND device key
// are present and the key parses; any absence yields ok=false so the caller falls
// through.
//
// The server pubkey is read best-effort and may be empty: a device paired before
// still has a usable token+key (it can hold the connection and answer the
// non-secret inventory query) but declines every ping until it re-pairs. Treating
// its absence as "no identity" would instead silently re-pair on the next launch,
// which needs a fresh single-use code the daemon does not have.
func readKeyringIdentity(kr keyringBackend) (identity, bool) {
	tok, err := kr.get(acctDeviceToken)
	if err != nil || tok == "" {
		return identity{}, false
	}
	b64, err := kr.get(acctDeviceKey)
	if err != nil || b64 == "" {
		return identity{}, false
	}
	key, err := parseDeviceKey(b64)
	if err != nil {
		return identity{}, false
	}
	var serverPub ed25519.PublicKey
	if sb64, serr := kr.get(acctServerPubkey); serr == nil && sb64 != "" {
		// A stored-but-corrupt key stays nil (decline every ping), never a partial key.
		serverPub, _ = agent.ParseServerPubkey(sb64)
	}
	return identity{token: tok, key: key, serverPub: serverPub}, true
}

func writeKeyringIdentity(kr keyringBackend, id identity) error {
	if err := kr.set(acctDeviceToken, id.token); err != nil {
		return err
	}
	if err := kr.set(acctDeviceKey, base64.StdEncoding.EncodeToString(id.key)); err != nil {
		return err
	}
	if len(id.serverPub) == 0 {
		return nil // nothing to persist; the device will decline every ping
	}
	return kr.set(acctServerPubkey, base64.StdEncoding.EncodeToString(id.serverPub))
}
