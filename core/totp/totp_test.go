package totp

import (
	"strings"
	"testing"
	"time"
)

// RFC 6238 Appendix B test vectors (8-digit). The SHA1 seed is the ASCII string
// "12345678901234567890"; SHA256/SHA512 use the extended seeds from the RFC.
func TestRFC6238Vectors(t *testing.T) {
	seedSHA1 := []byte("12345678901234567890")                                               // 20 bytes
	seedSHA256 := []byte("12345678901234567890123456789012")                                 // 32 bytes
	seedSHA512 := []byte("1234567890123456789012345678901234567890123456789012345678901234") // 64 bytes

	cases := []struct {
		unix int64
		alg  Algorithm
		seed []byte
		want string
	}{
		{59, SHA1, seedSHA1, "94287082"},
		{59, SHA256, seedSHA256, "46119246"},
		{59, SHA512, seedSHA512, "90693936"},
		{1111111109, SHA1, seedSHA1, "07081804"},
		{1111111111, SHA1, seedSHA1, "14050471"},
		{1234567890, SHA1, seedSHA1, "89005924"},
		{2000000000, SHA1, seedSHA1, "69279037"},
		{20000000000, SHA1, seedSHA1, "65353130"},
		{1111111109, SHA256, seedSHA256, "68084774"},
		{1111111109, SHA512, seedSHA512, "25091201"},
	}
	for _, c := range cases {
		cfg := Config{Secret: c.seed, Digits: 8, Period: 30, Algorithm: c.alg}
		got, err := cfg.GenerateAt(time.Unix(c.unix, 0))
		if err != nil {
			t.Fatalf("GenerateAt(%d,%s): %v", c.unix, c.alg, err)
		}
		if got != c.want {
			t.Errorf("TOTP(%d,%s) = %s, want %s", c.unix, c.alg, got, c.want)
		}
	}
}

func TestDefaults(t *testing.T) {
	// 6-digit default, SHA1, period 30 — the common case.
	cfg := Config{Secret: []byte("12345678901234567890")}
	got, err := cfg.GenerateAt(time.Unix(59, 0))
	if err != nil {
		t.Fatal(err)
	}
	// 8-digit at t=59 is 94287082; 6-digit is the low 6 digits: 287082.
	if got != "287082" {
		t.Errorf("6-digit TOTP = %s, want 287082", got)
	}
}

func TestParseOTPAuthURL(t *testing.T) {
	// secret "JBSWY3DPEHPK3PXP" is base32 for "Hello!\xDE\xAD\xBE\xEF".
	raw := "otpauth://totp/ACME%20Co:john@example.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30"
	cfg, err := ParseOTPAuthURL(raw)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if cfg.Issuer != "ACME Co" {
		t.Errorf("issuer = %q", cfg.Issuer)
	}
	if cfg.Account != "john@example.com" {
		t.Errorf("account = %q", cfg.Account)
	}
	if cfg.Digits != 6 || cfg.Period != 30 || cfg.Algorithm != SHA1 {
		t.Errorf("params: digits=%d period=%d alg=%s", cfg.Digits, cfg.Period, cfg.Algorithm)
	}
	// It must produce a stable 6-digit code.
	code, err := cfg.GenerateAt(time.Unix(0, 0))
	if err != nil {
		t.Fatal(err)
	}
	if len(code) != 6 {
		t.Errorf("code %q not 6 digits", code)
	}
}

func TestParseErrorNeverEchoesSecret(t *testing.T) {
	// A control character makes url.Parse fail; the wrapped *url.Error would
	// echo the full raw URI — including the seed — into the error string.
	const seed = "JBSWY3DPEHPK3PXPSUPERSECRETSEED"
	_, err := ParseOTPAuthURL("otpauth://totp/Acme:me@example.com?secret=" + seed + "\x7f&issuer=Acme")
	if err == nil {
		t.Fatal("expected parse error")
	}
	if strings.Contains(err.Error(), seed) {
		t.Fatalf("error string leaks the seed: %q", err.Error())
	}
}

func TestParseRejectsBad(t *testing.T) {
	for _, raw := range []string{
		"otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP",          // hotp unsupported
		"https://example.com",                               // not otpauth
		"otpauth://totp/x",                                  // no secret
		"otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=9", // digits out of range
	} {
		if _, err := ParseOTPAuthURL(raw); err == nil {
			t.Errorf("expected error for %q", raw)
		}
	}
}
