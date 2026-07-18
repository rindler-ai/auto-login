package tunnelclient

import (
	"context"
	"fmt"
	"net"
	"time"
)

// cgnatRange is RFC 6598 shared address space (100.64.0.0/10, carrier-grade NAT).
// A device on a mobile/CGNAT network sits behind it, so a gateway-requested CONNECT
// to a 100.64.x.x host could reach carrier-internal infrastructure — refuse it.
var cgnatRange = mustCIDR("100.64.0.0/10")

// nat64Range is the RFC 6052 well-known NAT64 prefix (64:ff9b::/96). On an
// IPv6-only carrier (NAT64/DNS64, or on-device CLAT), an address in this range is
// translated to an embedded IPv4 target — so a gateway-requested CONNECT to
// 64:ff9b::<v4> would bypass the IPv4 deny-list and reach metadata/private IPv4.
// Refuse the whole prefix; a device should never egress via carrier NAT64.
var nat64Range = mustCIDR("64:ff9b::/96")

func mustCIDR(s string) *net.IPNet {
	_, n, err := net.ParseCIDR(s)
	if err != nil {
		panic(err)
	}
	return n
}

// isBlockedIP reports whether an IP should be refused as an egress target.
// Covers loopback, RFC1918, RFC4193 (unique-local v6), link-local (which
// includes the cloud metadata range 169.254.169.254), unspecified, multicast,
// RFC 6598 CGNAT (100.64.0.0/10), RFC 6052 NAT64 (64:ff9b::/96), and the limited
// broadcast address (255.255.255.255). IPv4-mapped IPv6 (::ffff:a.b.c.d) is
// normalized by To4() so mapped private/metadata targets are caught too. The
// check is conservative on purpose: when in doubt, refuse.
func isBlockedIP(ip net.IP) bool {
	if ip == nil {
		return true
	}
	if ip.Equal(net.IPv4bcast) { // 255.255.255.255 limited broadcast
		return true
	}
	if v4 := ip.To4(); v4 != nil && cgnatRange.Contains(v4) {
		return true
	}
	if nat64Range.Contains(ip) { // 64:ff9b::/96 carrier NAT64 → embedded IPv4
		return true
	}
	return ip.IsLoopback() ||
		ip.IsPrivate() ||
		ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() ||
		ip.IsUnspecified() ||
		ip.IsMulticast()
}

// checkedDialer enforces the egress deny-list before dialing a target.
// It resolves names once and dials the resolved IP directly, so a name with
// a public A record at lookup time cannot be swapped for a private one at
// dial time (DNS rebinding).
type checkedDialer struct {
	allowPrivate bool
	resolver     *net.Resolver
	dialer       *net.Dialer
}

func newCheckedDialer(allowPrivate bool) *checkedDialer {
	return &checkedDialer{
		allowPrivate: allowPrivate,
		resolver:     net.DefaultResolver,
		dialer:       &net.Dialer{Timeout: 15 * time.Second},
	}
}

// Dial resolves target, validates every resolved address against the
// deny-list (unless allowPrivate is true), and dials the first allowed IP
// directly. Returns a descriptive error on block; the gateway sees a stream
// that closes immediately and can surface that to the caller.
func (d *checkedDialer) Dial(ctx context.Context, target string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(target)
	if err != nil {
		return nil, fmt.Errorf("split host:port: %w", err)
	}

	// Literal IP target: validate directly, dial as-is.
	if ip := net.ParseIP(host); ip != nil {
		if !d.allowPrivate && isBlockedIP(ip) {
			return nil, fmt.Errorf("egress to %s blocked (private/loopback/link-local/multicast)", ip)
		}
		return d.dialer.DialContext(ctx, "tcp", target)
	}

	// Name target: resolve once, validate all results, dial first allowed IP.
	ips, err := d.resolver.LookupIP(ctx, "ip", host)
	if err != nil {
		return nil, fmt.Errorf("resolve %s: %w", host, err)
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("resolve %s: no addresses", host)
	}
	if !d.allowPrivate {
		for _, ip := range ips {
			if isBlockedIP(ip) {
				return nil, fmt.Errorf("egress to %s (resolved %s) blocked (private/loopback/link-local/multicast)", host, ip)
			}
		}
	}
	// Dial the first resolved IP directly. Resolving once and dialing the
	// literal IP prevents an attacker-controlled DNS server from returning a
	// public IP to LookupIP and a private IP to a second resolution inside
	// net.Dialer.
	return d.dialer.DialContext(ctx, "tcp", net.JoinHostPort(ips[0].String(), port))
}
