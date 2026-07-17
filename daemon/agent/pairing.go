package agent

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/rindler-ai/auto-login/core/relay"
)

// pairV2Prefix marks a pairing-channel-TOFU v2 code (follow-up). The body
// after it is 64 lowercase hex chars = 32 bytes = [16-byte token || 16-byte
// server-key fingerprint]. A v1 "cd_pair_" code lacks this prefix and is refused,
// which also blocks any downgrade to the pre-TOFU format.
const pairV2Prefix = "cd_pair2_"

// PairResult is what a successful pairing yields. ServerPubkeyB64 is the base64
// Ed25519 PUBLIC key the device verifies every SecretPing against: it is
// provisioned here, at pairing, and nowhere else. The device MUST persist it
// alongside the token — without it every ping is declined (fail-closed; see
// agent.Config.ServerPubkey).
type PairResult struct {
	DeviceID        string
	DeviceToken     string
	ServerPubkeyB64 string
}

// CompletePairing redeems a single-use pairing code (minted by the user at
// your hub → Settings → Devices) and enrolls THIS device: it POSTs the
// device's PUBLIC Ed25519 key to POST /devices/pair/complete and returns the
// long-lived device bearer token plus the server's ping-signing PUBLIC key. The
// device private key never leaves the device.
//
// Request/response contract is the server's devicereg.PairComplete:
//
//	POST {"pairing_token","device_name","platform","device_pubkey"(base64)}
//	  -> {"device_id","device_token","server_pubkey"(base64)}
//
// Pairing-channel TOFU (follow-up). The v2 pairing code carries, in its
// trailing 16 bytes, a fingerprint of the lane's server_pubkey. The user copies
// that code over the clean authenticated browser→human channel, which a network
// MITM cannot alter. CompletePairing recomputes the fingerprint over the
// server_pubkey it RECEIVES at pair/complete and constant-time-compares it to the
// embedded one, failing closed on any mismatch: it seals, persists, and returns
// NOTHING unless the received key is the one the user's code committed to. An
// active MITM that swaps server_pubkey to its own key (or STRIPS it to force the
// keyless lane) computes a different fingerprint and is rejected — closing both
// the key-injection and the downgrade-to-denial attacks. An empty server_pubkey
// is therefore accepted only when the code committed to the keyless-lane
// fingerprint; on a keyed lane a stripped response computes the keyless
// fingerprint, which does not match the embedded real one, so it is rejected.
func CompletePairing(ctx context.Context, pairURL, pairingToken, deviceName, platform string, pub ed25519.PublicKey) (PairResult, error) {
	// (a) The code MUST be a v2 (TOFU) code. This rejects a v1 "cd_pair_" code and
	// any downgrade. The generic message deliberately reveals no internals.
	if !strings.HasPrefix(pairingToken, pairV2Prefix) {
		return PairResult{}, fmt.Errorf("pairing: unrecognized pairing code")
	}
	// (b) The body after the prefix is 64 hex chars = 32 bytes.
	decoded, err := hex.DecodeString(strings.TrimPrefix(pairingToken, pairV2Prefix))
	if err != nil || len(decoded) != 2*relay.PairingFingerprintLen {
		return PairResult{}, fmt.Errorf("pairing: unrecognized pairing code")
	}
	// (c) The trailing 16 bytes are the fingerprint the user carried; the leading
	// 16 are the single-use token the server matches on.
	embeddedFP := decoded[relay.PairingFingerprintLen:]

	if len(pub) != ed25519.PublicKeySize {
		return PairResult{}, fmt.Errorf("pairing: device pubkey wrong size (%d)", len(pub))
	}
	reqBody, _ := json.Marshal(map[string]string{
		"pairing_token": pairingToken,
		"device_name":   deviceName,
		"platform":      platform,
		"device_pubkey": base64.StdEncoding.EncodeToString(pub),
	})

	// (d) Redeem the code at the server.
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, pairURL, bytes.NewReader(reqBody))
	if err != nil {
		return PairResult{}, fmt.Errorf("pairing: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return PairResult{}, fmt.Errorf("pairing: POST %s: %w", pairURL, err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return PairResult{}, fmt.Errorf("pairing: server returned %s (code invalid, expired, or already used?)", resp.Status)
	}
	// server_pubkey is a Go []byte on the server side, so it arrives base64-std.
	var out struct {
		DeviceID     string `json:"device_id"`
		DeviceToken  string `json:"device_token"`
		ServerPubkey string `json:"server_pubkey"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return PairResult{}, fmt.Errorf("pairing: decode response: %w", err)
	}
	if out.DeviceToken == "" {
		return PairResult{}, fmt.Errorf("pairing: server returned no device_token")
	}
	// server_pubRaw is nil for an empty string, a 32-byte key otherwise, and an
	// error on a wrong-size key (a server bug or tampering, never a config gap).
	serverPubRaw, err := ParseServerPubkey(out.ServerPubkey)
	if err != nil {
		return PairResult{}, err
	}
	// (e)+(f) THE CHOKE POINT: recompute the fingerprint over the key we RECEIVED
	// and constant-time-compare it to the one the user carried. On any mismatch
	// return with NOTHING sealed, NOTHING persisted, and NO PairResult — the
	// caller/shell never sees a token it could store.
	computedFP := relay.PairingFingerprint(serverPubRaw)
	if subtle.ConstantTimeCompare(embeddedFP, computedFP) != 1 {
		return PairResult{}, fmt.Errorf("pairing: could not verify the hub's identity (untrusted network?)")
	}
	// (g) Verified: the received key is exactly the one the code committed to.
	// ServerPubkeyB64 may legitimately be "" on a keyless lane whose code embedded
	// the keyless fingerprint.
	return PairResult{DeviceID: out.DeviceID, DeviceToken: out.DeviceToken, ServerPubkeyB64: out.ServerPubkey}, nil
}

// ParseServerPubkey decodes a base64 Ed25519 server public key. An empty string
// yields (nil, nil) — "this device holds no server key", which the ping path
// treats as decline-everything. Anything present but unusable is an error: a
// wrong-size key must never be silently downgraded to "no key".
func ParseServerPubkey(b64 string) (ed25519.PublicKey, error) {
	if b64 == "" {
		return nil, nil
	}
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, fmt.Errorf("custody: server pubkey not base64: %w", err)
	}
	if len(raw) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("custody: server pubkey wrong size (%d, want %d)", len(raw), ed25519.PublicKeySize)
	}
	return ed25519.PublicKey(raw), nil
}

// PairURLFromHub derives the pairing endpoint from the hub websocket URL:
// wss://host/v1/devices/connect -> https://host/devices/pair/complete. A raw
// RINDLER_PAIR_URL always wins so a non-standard deployment can override it.
func PairURLFromHub(hubURL, override string) (string, error) {
	if override != "" {
		return override, nil
	}
	u, err := url.Parse(hubURL)
	if err != nil {
		return "", fmt.Errorf("pairing: bad hub url %q: %w", hubURL, err)
	}
	scheme := "https"
	if strings.EqualFold(u.Scheme, "ws") {
		scheme = "http" // dev/plaintext hub → plaintext pairing
	}
	return (&url.URL{Scheme: scheme, Host: u.Host, Path: "/devices/pair/complete"}).String(), nil
}
