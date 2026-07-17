// Package totp generates RFC 6238 TOTP codes on the device. The seed is
// imported once (from an otpauth:// QR) and kept in the OS keychain; only the
// generated code is ever relayed — the seed never transits (issue,
// ).
package totp

import (
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base32"
	"errors"
	"fmt"
	"hash"
	"math"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Algorithm is the HMAC hash used by HOTP/TOTP.
type Algorithm string

const (
	SHA1   Algorithm = "SHA1"
	SHA256 Algorithm = "SHA256"
	SHA512 Algorithm = "SHA512"
)

func (a Algorithm) newHash() (func() hash.Hash, error) {
	switch a {
	case SHA1, "":
		return sha1.New, nil
	case SHA256:
		return sha256.New, nil
	case SHA512:
		return sha512.New, nil
	default:
		return nil, fmt.Errorf("totp: unsupported algorithm %q", a)
	}
}

// Config is a single TOTP credential (an otpauth:// entry), minus the account
// label. Secret is the raw seed bytes (already base32-decoded).
type Config struct {
	Secret    []byte
	Digits    int
	Period    int // seconds
	Algorithm Algorithm
	Issuer    string
	Account   string
}

var (
	errNoSecret  = errors.New("totp: empty secret")
	errBadDigits = errors.New("totp: digits must be 6, 7, or 8")
	errBadPeriod = errors.New("totp: period must be positive")
)

// normalize fills defaults and validates.
func (c *Config) normalize() error {
	if len(c.Secret) == 0 {
		return errNoSecret
	}
	if c.Digits == 0 {
		c.Digits = 6
	}
	if c.Digits < 6 || c.Digits > 8 {
		return errBadDigits
	}
	if c.Period == 0 {
		c.Period = 30
	}
	if c.Period < 0 {
		return errBadPeriod
	}
	if c.Algorithm == "" {
		c.Algorithm = SHA1
	}
	return nil
}

// hotp computes the RFC 4226 HOTP value for a counter.
func hotp(secret []byte, counter uint64, digits int, alg Algorithm) (string, error) {
	newHash, err := alg.newHash()
	if err != nil {
		return "", err
	}
	var msg [8]byte
	for i := 7; i >= 0; i-- {
		msg[i] = byte(counter & 0xff)
		counter >>= 8
	}
	mac := hmac.New(newHash, secret)
	mac.Write(msg[:])
	sum := mac.Sum(nil)
	offset := sum[len(sum)-1] & 0x0f
	bin := (uint32(sum[offset])&0x7f)<<24 |
		uint32(sum[offset+1])<<16 |
		uint32(sum[offset+2])<<8 |
		uint32(sum[offset+3])
	code := bin % uint32(math.Pow10(digits))
	return fmt.Sprintf("%0*d", digits, code), nil
}

// GenerateAt returns the TOTP code for the given time.
func (c Config) GenerateAt(t time.Time) (string, error) {
	if err := c.normalize(); err != nil {
		return "", err
	}
	counter := uint64(t.Unix() / int64(c.Period))
	return hotp(c.Secret, counter, c.Digits, c.Algorithm)
}

// Now returns the current TOTP code. Isolated from GenerateAt so callers can
// test with a fixed clock.
func (c Config) Now() (string, error) {
	return c.GenerateAt(time.Now())
}

// ParseOTPAuthURL parses an otpauth://totp/... URI (the QR payload) into a
// Config. Only the `totp` type is supported; `hotp` is rejected (no counter
// custody in this app).
func ParseOTPAuthURL(raw string) (Config, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		// Never wrap the *url.Error: its Error() embeds the full raw URI,
		// which carries secret=<base32 seed> — the one value that must not
		// reach error strings/logs. Surface only the inner cause.
		var uerr *url.Error
		if errors.As(err, &uerr) {
			return Config{}, fmt.Errorf("totp: parse url: %w", uerr.Err)
		}
		return Config{}, errors.New("totp: invalid otpauth URI")
	}
	if u.Scheme != "otpauth" {
		return Config{}, fmt.Errorf("totp: not an otpauth URI (scheme %q)", u.Scheme)
	}
	if u.Host != "totp" {
		return Config{}, fmt.Errorf("totp: unsupported otpauth type %q (only totp)", u.Host)
	}
	q := u.Query()
	secretB32 := strings.ToUpper(strings.TrimSpace(q.Get("secret")))
	if secretB32 == "" {
		return Config{}, errNoSecret
	}
	// RFC 4648 base32, no padding is common in otpauth URIs.
	secret, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(strings.TrimRight(secretB32, "="))
	if err != nil {
		return Config{}, fmt.Errorf("totp: decode secret: %w", err)
	}
	cfg := Config{
		Secret:    secret,
		Issuer:    q.Get("issuer"),
		Algorithm: Algorithm(strings.ToUpper(q.Get("algorithm"))),
	}
	// Label is "Issuer:Account" or just "Account".
	label := strings.TrimPrefix(u.Path, "/")
	if i := strings.Index(label, ":"); i >= 0 {
		if cfg.Issuer == "" {
			cfg.Issuer = label[:i]
		}
		cfg.Account = strings.TrimSpace(label[i+1:])
	} else {
		cfg.Account = label
	}
	if d := q.Get("digits"); d != "" {
		if cfg.Digits, err = strconv.Atoi(d); err != nil {
			return Config{}, fmt.Errorf("totp: bad digits %q: %w", d, err)
		}
	}
	if p := q.Get("period"); p != "" {
		if cfg.Period, err = strconv.Atoi(p); err != nil {
			return Config{}, fmt.Errorf("totp: bad period %q: %w", p, err)
		}
	}
	if err := cfg.normalize(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}
