package otp

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"time"

	"github.com/emersion/go-imap"
	"github.com/emersion/go-imap/client"
)

// IMAPMailbox is a device-local, IMAP-backed MailboxReader. It is the production
// path for email-OTP sites: it connects to the user's inbox, finds the newest
// matching verification message, and returns ONLY the extracted code.
//
// INVARIANT: the credentials in this struct (Host/User/Password — in production
// a mailbox app-password or OAuth token) are DURABLE secrets. They are held on
// the device (store/keychain) and used only to read the inbox locally; they NEVER
// transit. The single value that ever leaves the device is the extracted code.
//
// This type performs network I/O and is therefore not unit-tested; FakeMailbox
// exercises the same MailboxReader contract in tests (mirroring how
// the server is tested with a fake inbox reader,
// whose live IMAP fetch this deliberately parallels).
type IMAPMailbox struct {
	Host     string // e.g. imap.gmail.com (TLS :993 is assumed)
	User     string
	Password string
	// PollInterval is how often the inbox is re-searched while waiting for the
	// code to arrive (defaults to 4s, matching the server lane).
	PollInterval time.Duration
}

// pollInterval defaults the re-search cadence.
func (m IMAPMailbox) pollInterval() time.Duration {
	if m.PollInterval > 0 {
		return m.PollInterval
	}
	return 4 * time.Second
}

// FetchLatestOTP connects to the inbox and waits (bounded by opts.Timeout) for a
// verification code that arrived at/after opts.Since, optionally restricted to
// senders whose From header contains opts.FromContains. It returns the newest
// matching message's extracted code, and only that code.
func (m IMAPMailbox) FetchLatestOTP(ctx context.Context, opts FetchOptions) (string, error) {
	if m.Host == "" || m.User == "" || m.Password == "" {
		return "", errors.New("otp: IMAP mailbox not configured")
	}
	deadline := time.Now()
	if opts.Timeout > 0 {
		deadline = deadline.Add(opts.Timeout)
	}
	var lastErr error
	for {
		code, err := m.fetchOnce(opts)
		if err != nil {
			lastErr = err
		} else if code != "" {
			return code, nil
		}
		if !time.Now().Before(deadline) {
			break
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(m.pollInterval()):
		}
	}
	if lastErr != nil {
		return "", fmt.Errorf("otp: no code within %s (last error: %w)", opts.Timeout, lastErr)
	}
	return "", ErrNoCode
}

// fetchOnce performs a single connect / search / scan pass over the inbox and
// returns the newest matching message's extracted code (or "" if none yet).
func (m IMAPMailbox) fetchOnce(opts FetchOptions) (string, error) {
	c, err := client.DialTLS(m.Host+":993", nil)
	if err != nil {
		return "", fmt.Errorf("otp: dial %s: %w", m.Host, err)
	}
	defer func() { _ = c.Logout() }()

	if err := c.Login(m.User, m.Password); err != nil {
		// Never wrap: an IMAP LOGIN error can echo the credential. Surface a
		// static message only.
		return "", errors.New("otp: IMAP login failed")
	}

	// Prefer Gmail's All Mail (label-agnostic); fall back to INBOX elsewhere.
	if _, err := c.Select("[Gmail]/All Mail", true); err != nil {
		if _, err := c.Select("INBOX", true); err != nil {
			return "", fmt.Errorf("otp: select mailbox: %w", err)
		}
	}

	// IMAP SINCE filters on server INTERNALDATE (date only, and can lag), so
	// search from the day before opts.Since and do precise time/sender filtering
	// ourselves below.
	criteria := imap.NewSearchCriteria()
	if !opts.Since.IsZero() {
		criteria.Since = opts.Since.AddDate(0, 0, -1)
	}
	ids, err := c.Search(criteria)
	if err != nil {
		return "", fmt.Errorf("otp: search: %w", err)
	}
	if len(ids) == 0 {
		return "", nil
	}

	// Newest first, bounded to the most recent matches.
	sort.Slice(ids, func(i, j int) bool { return ids[i] > ids[j] })
	if len(ids) > 20 {
		ids = ids[:20]
	}

	seqset := new(imap.SeqSet)
	seqset.AddNum(ids...)
	section := &imap.BodySectionName{}
	messages := make(chan *imap.Message, len(ids))
	done := make(chan error, 1)
	go func() {
		done <- c.Fetch(seqset, []imap.FetchItem{imap.FetchEnvelope, imap.FetchInternalDate, section.FetchItem()}, messages)
	}()

	from := strings.ToLower(strings.TrimSpace(opts.FromContains))
	type hit struct {
		when time.Time
		code string
	}
	var best *hit
	for msg := range messages {
		if msg == nil {
			continue
		}
		when := msg.InternalDate
		if msg.Envelope != nil && !msg.Envelope.Date.IsZero() {
			when = msg.Envelope.Date
		}
		if !opts.Since.IsZero() && when.Before(opts.Since.Add(-90*time.Second)) {
			continue
		}
		if from != "" && !envelopeFromContains(msg.Envelope, from) {
			continue
		}
		// Subject first (codes frequently land there), then the body.
		var hay strings.Builder
		if msg.Envelope != nil {
			hay.WriteString(msg.Envelope.Subject)
			hay.WriteString("\n")
		}
		if r := msg.GetBody(section); r != nil {
			if b, rerr := io.ReadAll(r); rerr == nil {
				// Decode the transfer-encoding (base64 / quoted-printable) and walk
				// multipart before scanning: feeding the raw encoded body to
				// ExtractCode relays a spurious digit run out of the base64.
				hay.WriteString(decodeMailText(b))
			}
		}
		code, err := ExtractCode(hay.String(), opts.Extract)
		if err != nil {
			continue
		}
		if best == nil || when.After(best.when) {
			best = &hit{when: when, code: code}
		}
	}
	// Drain the fetch error; only surface it if we found nothing.
	if ferr := <-done; ferr != nil && best == nil {
		return "", fmt.Errorf("otp: fetch: %w", ferr)
	}
	if best != nil {
		return best.code, nil
	}
	return "", nil
}

// envelopeFromContains reports whether any From address (mailbox@host or the
// personal name) contains the lowercase substring needle.
func envelopeFromContains(env *imap.Envelope, needle string) bool {
	if env == nil {
		return false
	}
	for _, a := range env.From {
		if a == nil {
			continue
		}
		addr := strings.ToLower(a.MailboxName + "@" + a.HostName)
		if strings.Contains(addr, needle) || strings.Contains(strings.ToLower(a.PersonalName), needle) {
			return true
		}
	}
	return false
}

// Compile-time assertion that IMAPMailbox satisfies MailboxReader.
var _ MailboxReader = IMAPMailbox{}
