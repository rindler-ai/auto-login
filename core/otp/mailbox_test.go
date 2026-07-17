package otp

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestFakeMailboxReturnsNewestCode(t *testing.T) {
	base := time.Date(2026, 7, 12, 10, 0, 0, 0, time.UTC)
	mb := &FakeMailbox{Messages: []FakeMessage{
		{From: "Acme <no-reply@acme.com>", Subject: "Sign-in", Body: "Your verification code is 111111.", Date: base},
		{From: "Acme <no-reply@acme.com>", Subject: "Sign-in", Body: "Your verification code is 222222.", Date: base.Add(2 * time.Minute)},
		{From: "News <news@acme.com>", Subject: "Weekly digest", Body: "No code here, order 90011234 shipped.", Date: base.Add(3 * time.Minute)},
	}}

	// Newest message with a code (222222) wins; the newer digest has no code.
	code, err := mb.FetchLatestOTP(context.Background(), FetchOptions{FromContains: "acme.com"})
	if err != nil {
		t.Fatalf("FetchLatestOTP: %v", err)
	}
	if code != "222222" {
		t.Fatalf("got %q, want 222222", code)
	}
}

func TestFakeMailboxSinceFilter(t *testing.T) {
	base := time.Date(2026, 7, 12, 10, 0, 0, 0, time.UTC)
	mb := &FakeMailbox{Messages: []FakeMessage{
		{From: "a@x.com", Subject: "old", Body: "code 333333", Date: base},
		{From: "a@x.com", Subject: "new", Body: "code 444444", Date: base.Add(10 * time.Minute)},
	}}
	code, err := mb.FetchLatestOTP(context.Background(), FetchOptions{Since: base.Add(5 * time.Minute)})
	if err != nil {
		t.Fatal(err)
	}
	if code != "444444" {
		t.Fatalf("got %q, want 444444 (older message should be filtered by Since)", code)
	}
}

// Invariant: the code source returns ONLY the extracted code — never the raw
// email body or any credential-bearing text.
func TestSourceFromMailboxLeaksNoBody(t *testing.T) {
	secretToken := "ya29.SUPER-SECRET-OAUTH-TOKEN"
	body := "Your verification code is 246810.\n[mailbox oauth: " + secretToken + "]"
	mb := &FakeMailbox{Messages: []FakeMessage{
		{From: "Acme <no-reply@acme.com>", Subject: "Verify", Body: body, Date: time.Now()},
	}}

	src := SourceFromMailbox(mb, FetchOptions{FromContains: "acme"})
	got, err := src(context.Background())
	if err != nil {
		t.Fatalf("code source: %v", err)
	}
	if got != "246810" {
		t.Fatalf("got %q, want 246810", got)
	}
	if strings.Contains(got, secretToken) || strings.Contains(got, "oauth") || strings.Contains(got, body) {
		t.Fatalf("code source leaked non-code material: %q", got)
	}
	if len(got) != 6 {
		t.Fatalf("expected a 6-char code, got %d chars", len(got))
	}
}

func TestSourceFromString(t *testing.T) {
	got, err := SourceFromString("135790")(context.Background())
	if err != nil || got != "135790" {
		t.Fatalf("SourceFromString: got %q err=%v", got, err)
	}
	if _, err := ManualSMSSource("")(context.Background()); err == nil {
		t.Fatal("empty manual code: want error")
	}
}
