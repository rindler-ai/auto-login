package tunnelclient

import (
	"context"
	"net"
	"strings"
	"testing"
)

func TestIsBlockedIP(t *testing.T) {
	cases := []struct {
		name    string
		ip      string
		blocked bool
	}{
		// Loopback
		{"loopback v4", "127.0.0.1", true},
		{"loopback v4 high", "127.255.255.254", true},
		{"loopback v6", "::1", true},

		// RFC1918
		{"10/8", "10.0.0.1", true},
		{"172.16/12", "172.16.0.1", true},
		{"172.16/12 high", "172.31.255.255", true},
		{"192.168/16", "192.168.1.1", true},

		// RFC4193 unique-local v6
		{"unique-local v6", "fd00::1", true},
		{"aws ipv6 metadata", "fd00:ec2::254", true},

		// Link-local (includes cloud metadata IP)
		{"link-local v4", "169.254.1.1", true},
		{"aws/gcp metadata", "169.254.169.254", true},
		{"link-local v6", "fe80::1", true},

		// Unspecified
		{"unspecified v4", "0.0.0.0", true},
		{"unspecified v6", "::", true},

		// Multicast
		{"multicast v4", "224.0.0.1", true},
		{"multicast v6", "ff02::1", true},

		// Public — must NOT be blocked
		{"public 1.1.1.1", "1.1.1.1", false},
		{"public 8.8.8.8", "8.8.8.8", false},
		{"public github", "140.82.121.4", false},
		{"public v6", "2606:4700:4700::1111", false},

		// Boundary: 172.32.0.0 is not in 172.16/12
		{"172.32 is public", "172.32.0.1", false},
		// Boundary: 11.0.0.0 is not in 10/8
		{"11.0.0.1 is public", "11.0.0.1", false},

		// CGNAT (RFC 6598, 100.64.0.0/10) — blocked
		{"cgnat low", "100.64.0.1", true},
		{"cgnat mid", "100.100.100.100", true},
		{"cgnat high", "100.127.255.255", true},
		// CGNAT boundaries — 100.63.x and 100.128.x are public
		{"100.63 is public", "100.63.255.255", false},
		{"100.128 is public", "100.128.0.1", false},
		// Limited broadcast — blocked
		{"limited broadcast", "255.255.255.255", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ip := net.ParseIP(tc.ip)
			if ip == nil {
				t.Fatalf("ParseIP returned nil for %q", tc.ip)
			}
			got := isBlockedIP(ip)
			if got != tc.blocked {
				t.Errorf("isBlockedIP(%s) = %v, want %v", tc.ip, got, tc.blocked)
			}
		})
	}
}

func TestIsBlockedIP_NilIsBlocked(t *testing.T) {
	if !isBlockedIP(nil) {
		t.Error("nil IP must be blocked")
	}
}

func TestCheckedDialer_RejectsLiteralPrivate(t *testing.T) {
	d := newCheckedDialer(false)
	cases := []string{
		"127.0.0.1:6379",
		"169.254.169.254:80",
		"192.168.1.1:22",
		"10.0.0.1:8080",
		"[::1]:80",
		"[fd00:ec2::254]:80",
	}
	for _, target := range cases {
		t.Run(target, func(t *testing.T) {
			conn, err := d.Dial(context.Background(), target)
			if err == nil {
				_ = conn.Close()
				t.Errorf("expected rejection for %s, got nil error", target)
				return
			}
			if !strings.Contains(err.Error(), "blocked") {
				t.Errorf("error should mention 'blocked' for %s, got: %v", target, err)
			}
		})
	}
}

func TestCheckedDialer_AllowsLiteralPrivateWhenOverridden(t *testing.T) {
	// With allowPrivate=true we no longer reject by policy. The dial itself
	// will fail (nothing listening), but the error must not be a policy block.
	d := newCheckedDialer(true)
	_, err := d.Dial(context.Background(), "127.0.0.1:1")
	if err != nil && strings.Contains(err.Error(), "blocked") {
		t.Errorf("with allow_private_egress=true, must not policy-block; got: %v", err)
	}
}

func TestCheckedDialer_RejectsBadHostPort(t *testing.T) {
	d := newCheckedDialer(false)
	_, err := d.Dial(context.Background(), "not-a-valid-target")
	if err == nil {
		t.Error("expected error on missing port")
	}
}
