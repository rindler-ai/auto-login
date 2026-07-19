package otp

import (
	"testing"
	"time"

	"github.com/emersion/go-imap"
)

// senderDomainMatches is the DOMAIN-ANCHORED sender bound. It replaced a bare
// substring test (address OR display name) that let any sender pass by choosing a
// display name or a look-alike host. These cases pin the boundary.
func TestSenderDomainMatches(t *testing.T) {
	cases := []struct {
		name         string
		host, needle string
		want         bool
	}{
		{"exact domain", "airbnb.com", "airbnb.com", true},
		{"sender is a subdomain of the site", "e.airbnb.com", "airbnb.com", true},
		{"site host is a subdomain of the sender", "airbnb.com", "secure.airbnb.com", true},
		// The attacks the old substring match let through:
		{"look-alike suffix domain", "airbnb.com.evil.com", "airbnb.com", false},
		{"unrelated domain", "evil.com", "airbnb.com", false},
		{"needle as a bare substring of an unrelated host", "notairbnb.com", "airbnb.com", false},
		{"empty host", "", "airbnb.com", false},
		{"empty needle", "airbnb.com", "", false},
	}
	for _, tc := range cases {
		if got := senderDomainMatches(tc.host, tc.needle); got != tc.want {
			t.Errorf("%s: senderDomainMatches(%q,%q)=%v want %v", tc.name, tc.host, tc.needle, got, tc.want)
		}
	}
}

// envelopeFromContains must authenticate on the From ADDRESS domain only, never the
// attacker-controlled display name (PersonalName) — the exact Finding-2 vector.
func TestEnvelopeFromContains_IgnoresDisplayName(t *testing.T) {
	// Attacker sends from evil.com but sets the display name to look like the site.
	spoof := &imap.Envelope{From: []*imap.Address{
		{PersonalName: "Airbnb.com Security", MailboxName: "no-reply", HostName: "evil.com"},
	}}
	if envelopeFromContains(spoof, "airbnb.com") {
		t.Error("a display name containing the site domain must NOT satisfy the sender bound")
	}
	// Genuine sender from the site domain.
	real := &imap.Envelope{From: []*imap.Address{
		{PersonalName: "Airbnb", MailboxName: "automated", HostName: "airbnb.com"},
	}}
	if !envelopeFromContains(real, "airbnb.com") {
		t.Error("a genuine sender on the site domain must satisfy the sender bound")
	}
	// A look-alike suffix host must be rejected.
	lookalike := &imap.Envelope{From: []*imap.Address{
		{MailboxName: "no-reply", HostName: "airbnb.com.evil.com"},
	}}
	if envelopeFromContains(lookalike, "airbnb.com") {
		t.Error("a look-alike suffix host (airbnb.com.evil.com) must be rejected")
	}
	if envelopeFromContains(nil, "airbnb.com") {
		t.Error("a nil envelope must not match")
	}
}

// withinFreshness bounds a code to the login's arm time (server InternalDate), with
// a small clock-skew grace. This is what makes a pre-arm / stale code ineligible.
func TestWithinFreshness(t *testing.T) {
	arm := time.Unix(1_700_000_000, 0)
	cases := []struct {
		name string
		when time.Time
		want bool
	}{
		{"after arm", arm.Add(5 * time.Second), true},
		{"exactly at arm", arm, true},
		{"within the skew grace", arm.Add(-freshnessSkew + time.Second), true},
		{"at the skew boundary", arm.Add(-freshnessSkew), true},
		{"just beyond the grace (stale)", arm.Add(-freshnessSkew - time.Second), false},
		{"an hour stale", arm.Add(-time.Hour), false},
	}
	for _, tc := range cases {
		if got := withinFreshness(tc.when, arm); got != tc.want {
			t.Errorf("%s: withinFreshness=%v want %v", tc.name, got, tc.want)
		}
	}
	// A zero arm time means unbounded (no login-scoped freshness applies).
	if !withinFreshness(time.Unix(1, 0), time.Time{}) {
		t.Error("a zero since must be unbounded")
	}
}
