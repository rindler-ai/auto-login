package otp

import (
	"context"
	"errors"
	"sort"
	"strings"
	"time"
)

// FetchOptions constrains which message a MailboxReader reads a code from and
// how the code is extracted from it.
type FetchOptions struct {
	// FromContains, if non-empty, requires the sender (address or display name)
	// to contain this substring, case-insensitively.
	FromContains string
	// Since ignores any message older than this instant (skew-tolerant). Set it
	// to just before the login step that triggered the code send so a stale code
	// from an earlier attempt is never returned.
	Since time.Time
	// Extract is passed through to ExtractCode.
	Extract Options
	// Timeout bounds a polling reader's wait for the code to arrive (the IMAP
	// impl re-searches until it lands or this elapses). Zero means a single pass.
	Timeout time.Duration
}

// MailboxReader reads the newest matching message on the device and returns
// ONLY the extracted one-time code.
//
// Contract (load-bearing): an implementation MUST return just the code — never
// the raw message body, headers, or the mailbox credential that read it. The
// mailbox OAuth token / IMAP password stays on the device; only the code is
// eligible to be relayed.
type MailboxReader interface {
	FetchLatestOTP(ctx context.Context, opts FetchOptions) (string, error)
}

// CodeSource yields one one-time code on demand. It is the seam the relay uses
// to answer an email/SMS/manual-code ping without knowing whether the code came
// from a mailbox, a user keystroke, or a test fake. Like MailboxReader it must
// return only a code.
type CodeSource func(ctx context.Context) (string, error)

// SourceFromMailbox adapts a MailboxReader into a CodeSource bound to one fetch.
func SourceFromMailbox(r MailboxReader, opts FetchOptions) CodeSource {
	return func(ctx context.Context) (string, error) {
		return r.FetchLatestOTP(ctx, opts)
	}
}

// SourceFromString returns a CodeSource that yields a fixed, already-known code
// (the desktop manual-entry / user-typed SMS path).
func SourceFromString(code string) CodeSource {
	return func(context.Context) (string, error) {
		if code == "" {
			return "", ErrNoCode
		}
		return code, nil
	}
}

// FakeMessage is one message in a FakeMailbox.
type FakeMessage struct {
	From    string // e.g. "Acme Security <no-reply@acme.com>"
	Subject string
	Body    string
	Date    time.Time
}

// FakeMailbox is an in-memory MailboxReader for tests and the dev fallback. It
// applies the same From/Since filtering and newest-first selection as a real
// reader, then runs ExtractCode over subject+body — so a test that drives the
// relay through it exercises the true extraction path while asserting that only
// the code (never a FakeMessage.Body) is returned.
type FakeMailbox struct {
	Messages []FakeMessage
}

// FetchLatestOTP returns the extracted code from the newest message that passes
// the From/Since filters. It never returns a message body.
func (f *FakeMailbox) FetchLatestOTP(_ context.Context, opts FetchOptions) (string, error) {
	from := strings.ToLower(strings.TrimSpace(opts.FromContains))

	matches := make([]FakeMessage, 0, len(f.Messages))
	for _, m := range f.Messages {
		if !opts.Since.IsZero() && m.Date.Before(opts.Since.Add(-90*time.Second)) {
			continue
		}
		if from != "" && !strings.Contains(strings.ToLower(m.From), from) {
			continue
		}
		matches = append(matches, m)
	}
	if len(matches) == 0 {
		return "", errors.New("otp: no matching message")
	}
	// Newest first; scan until one yields a code.
	sort.SliceStable(matches, func(i, j int) bool { return matches[i].Date.After(matches[j].Date) })
	for _, m := range matches {
		hay := m.Subject + "\n" + m.Body
		if code, err := ExtractCode(hay, opts.Extract); err == nil {
			return code, nil
		}
	}
	return "", ErrNoCode
}
