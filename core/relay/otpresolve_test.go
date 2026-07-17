package relay

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/rindler-ai/auto-login/core/otp"
	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/store"
)

// An email_otp_code ping with no supplied code is answered by a mailbox-backed
// code source, and ONLY the extracted code is resolved — never the email body or
// the mailbox token embedded in it.
func TestResolveSecretWithSource_Mailbox(t *testing.T) {
	const token = "imap-app-password-SECRET"
	body := "Your verification code is 246810.\nX-Mailbox-Token: " + token
	mb := &otp.FakeMailbox{Messages: []otp.FakeMessage{
		{From: "Acme <no-reply@acme.com>", Subject: "Verify", Body: body, Date: time.Now()},
	}}
	src := otp.SourceFromMailbox(mb, otp.FetchOptions{FromContains: "acme"})

	rec := store.Record{Site: "acme.com", Username: "john"}
	got, err := ResolveSecretWithSource(context.Background(), rec, protocol.SecretEmailOTPCode, "", src)
	if err != nil {
		t.Fatalf("ResolveSecretWithSource: %v", err)
	}
	if got != "246810" {
		t.Fatalf("got %q, want 246810", got)
	}
	// Load-bearing invariant: nothing durable leaked into the resolved value.
	if strings.Contains(got, token) || strings.Contains(got, "Mailbox") || strings.Contains(got, body) {
		t.Fatalf("resolved value leaked durable material: %q", got)
	}
}

// A supplied (user-typed) code takes precedence and no source is consulted.
func TestResolveSecretWithSource_SuppliedWins(t *testing.T) {
	called := false
	src := otp.CodeSource(func(context.Context) (string, error) {
		called = true
		return "999999", nil
	})
	got, err := ResolveSecretWithSource(context.Background(), store.Record{Site: "s"}, protocol.SecretSMSOTPCode, "482913", src)
	if err != nil || got != "482913" {
		t.Fatalf("got %q err=%v, want 482913", got, err)
	}
	if called {
		t.Fatal("source consulted even though a code was supplied")
	}
}

// With neither a supplied code nor a source, the OTP kinds still require a code.
func TestResolveSecretWithSource_RequiresCode(t *testing.T) {
	_, err := ResolveSecretWithSource(context.Background(), store.Record{Site: "s"}, protocol.SecretEmailOTPCode, "", nil)
	if !errors.Is(err, ErrManualCodeRequired) {
		t.Fatalf("want ErrManualCodeRequired, got %v", err)
	}
}

// Non-code kinds delegate to ResolveSecret unchanged (source is irrelevant).
func TestResolveSecretWithSource_DelegatesNonCode(t *testing.T) {
	rec := store.Record{Site: "s", Username: "john", Password: "pw"}
	if v, err := ResolveSecretWithSource(context.Background(), rec, protocol.SecretPassword, "", nil); err != nil || v != "pw" {
		t.Fatalf("password: %q %v", v, err)
	}
	if v, err := ResolveSecretWithSource(context.Background(), rec, protocol.SecretUsername, "", nil); err != nil || v != "john" {
		t.Fatalf("username: %q %v", v, err)
	}
}

// A source that yields no code surfaces as ErrManualCodeRequired (fail-closed).
func TestResolveSecretWithSource_EmptyFromSource(t *testing.T) {
	src := otp.SourceFromString("") // yields ErrNoCode
	_, err := ResolveSecretWithSource(context.Background(), store.Record{Site: "s"}, protocol.SecretManualCode, "", src)
	if err == nil {
		t.Fatal("want error when source yields no code")
	}
}
