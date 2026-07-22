// Package protocol is the custody-app client's implementation of the
// device-relay wire contract. It is deliberately a
// SEPARATE implementation of the same shapes declared in schema/device_relay.yaml
// and served by the server's device-relay package — the contract is
// language-agnostic so the client stays decoupled from the server module. The
// golden-vector test (seal_test.go) proves this client's HPKE seal interoperates
// with the std crypto/hpke worker on the server.
//
// INVARIANT (mirrors the server): no wire message carries a durable secret. The
// device reads SMS/email one-time codes locally and relays only the code; only
// the password + short-lived codes ever cross, inside the HPKE-sealed SecretRelease.
package protocol

// ProtocolVersion is the wire version; must match the server's. v2 adds the
// server-signed SecretPing (server_signature) + the pairing-time server_pubkey
// that authenticates the worker ephemeral key (issue). It is a breaking
// shape change: a device paired under v1 holds no server_pubkey and must re-pair.
const ProtocolVersion = 2

// SecretKind is the single secret a ping asks for / a release carries.
type SecretKind string

const (
	SecretUsername     SecretKind = "username"
	SecretPassword     SecretKind = "password"
	SecretEmailOTPCode SecretKind = "email_otp_code"
	SecretSMSOTPCode   SecretKind = "sms_otp_code"
	SecretManualCode   SecretKind = "manual_code"
)

// AllSecretKinds is the closed set; order matches schema/device_relay.yaml.
var AllSecretKinds = []SecretKind{
	SecretUsername, SecretPassword,
	SecretEmailOTPCode, SecretSMSOTPCode, SecretManualCode,
}

// Valid reports whether k is a known secret kind.
func (k SecretKind) Valid() bool {
	for _, v := range AllSecretKinds {
		if k == v {
			return true
		}
	}
	return false
}

// DevicePairRequest is device -> server: begin pairing with a single-use token.
type DevicePairRequest struct {
	PairingToken string `json:"pairing_token"`
	DeviceName   string `json:"device_name"`
	Platform     string `json:"platform"`
	DevicePubkey []byte `json:"device_pubkey"` // Ed25519 public key
}

// DevicePairComplete is server -> device: the pairing result. ServerPubkey is
// the Ed25519 key the device stores next to its device token and thereafter
// requires every SecretPing to be signed with; without it the device cannot
// authenticate the worker ephemeral key in a ping and declines every ping.
type DevicePairComplete struct {
	DeviceID     string `json:"device_id"`
	Status       string `json:"status"`
	Reason       string `json:"reason,omitempty"`
	ServerPubkey []byte `json:"server_pubkey"` // Ed25519 public key
}

// SecretPing is server -> device: ask for exactly one secret for one login.
// ServerSignature is the server's Ed25519 signature over relay.PingSigningMessage,
// which covers WorkerEphemeralPubkey — that is what stops an on-path party from
// substituting its own recipient key and having the device seal to it.
type SecretPing struct {
	RequestID             string     `json:"request_id"`
	Site                  string     `json:"site"`
	SecretKind            SecretKind `json:"secret_kind"`
	WorkerEphemeralPubkey []byte     `json:"worker_ephemeral_pubkey"`
	Challenge             []byte     `json:"challenge"`
	TTLSeconds            int        `json:"ttl_seconds"`
	ServerSignature       []byte     `json:"server_signature"`
}

// SecretRelease is device -> server: one secret, HPKE-sealed to the worker key,
// after explicit per-secret user approval.
type SecretRelease struct {
	RequestID       string `json:"request_id"`
	SealedSecret    []byte `json:"sealed_secret"`
	DeviceSignature []byte `json:"device_signature"`
}

// Ack is server -> device: terminal receipt so the device clears pending state.
type Ack struct {
	RequestID string `json:"request_id"`
	Status    string `json:"status"`
}

// SiteInventoryRequest is server -> device: ask which sites the device currently
// holds a login for, so the server can prefer the device-relay lane over a hosted
// Connect browser. It carries no secret and asks for none — the reply is domains
// only. request_id ties the reply back to this query.
type SiteInventoryRequest struct {
	RequestID string `json:"request_id"`
}

// SiteInventoryReply is device -> server: the domains the device currently holds
// a stored login for. Domains ONLY — never a credential. An empty list is valid.
// RequestID echoes the request.
type SiteInventoryReply struct {
	RequestID string   `json:"request_id"`
	Domains   []string `json:"domains"`
}
