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
// freshnessSkew is how far before the login's arm time a code may have arrived
// and still count as fresh — a clock-skew grace between the server's InternalDate
// and the device's arm time. Kept small so it does not widen the window an
// attacker or a prior login could exploit.
const freshnessSkew = 90 * time.Second

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
// VerifyLogin checks the mailbox credential by connecting and authenticating ONLY
// — it performs NO SEARCH, FETCH, or body read, so it never touches the user's
// mail. It exists so the Link screen can confirm an app-password works WITHOUT the
// "read up to 20 message bodies outside a login window" side effect a full fetch
// would have (the reader's whole promise is that it reads mail only while a login
// is armed). Returns nil on success, ErrIMAPAuth on a rejected credential (never
// echoing it), or a wrapped transient error.
func (m IMAPMailbox) VerifyLogin() error {
	c, err := client.DialTLS(m.Host+":993", nil)
	if err != nil {
		return fmt.Errorf("otp: dial %s: %w", m.Host, err)
	}
	defer func() { _ = c.Logout() }()
	if err := c.Login(m.User, m.Password); err != nil {
		return ErrIMAPAuth
	}
	return nil
}

// returns the newest matching message's extracted code (or "" if none yet).
func (m IMAPMailbox) fetchOnce(opts FetchOptions) (string, error) {
	c, err := client.DialTLS(m.Host+":993", nil)
	if err != nil {
		return "", fmt.Errorf("otp: dial %s: %w", m.Host, err)
	}
	defer func() { _ = c.Logout() }()

	if err := c.Login(m.User, m.Password); err != nil {
		// Never wrap: an IMAP LOGIN error can echo the credential. Surface the
		// static, TYPED sentinel only (ErrIMAPAuth), so a caller can distinguish a
		// revoked/wrong app-password from a transient failure without ever seeing
		// the credential.
		return "", ErrIMAPAuth
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
		// Freshness + newest-wins are keyed on the server's InternalDate, NEVER the
		// message's own Date: header (Envelope.Date). Date: is set by the sender, so
		// preferring it let an attacker pre-plant a code with a far-future Date: that
		// both passed the freshness lower bound AND out-ranked the genuine code in the
		// newest-wins tiebreak below. InternalDate is when THIS server received the
		// mail — the actual freshness signal, and unspoofable by the sender.
		when := msg.InternalDate
		if !withinFreshness(when, opts.Since) {
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
		if a != nil && senderDomainMatches(strings.ToLower(strings.TrimSpace(a.HostName)), needle) {
			return true
		}
	}
	return false
}

// senderDomainMatches reports whether an email's From-address host belongs to the
// same domain the site is expected to send from ([needle], the site host). It is
// DOMAIN-ANCHORED, never a bare substring, and it deliberately ignores the display
// name (PersonalName): the old `Contains(addr, needle) || Contains(personalName,
// needle)` let any sender pass by (a) setting a display name like "Airbnb.com
// Security" from an arbitrary address, or (b) sending from a look-alike host such
// as "airbnb.com.evil.com" whose address string still contains "airbnb.com". A
// match now requires the host to BE the domain, be a sub-domain of it, or be the
// parent the site is a sub-domain of (site host = secure.airbnb.com, sender =
// airbnb.com) — so "airbnb.com.evil.com" (host ends in .evil.com) is rejected.
func senderDomainMatches(host, needle string) bool {
	if host == "" || needle == "" {
		return false
	}
	return host == needle ||
		strings.HasSuffix(host, "."+needle) ||
		strings.HasSuffix(needle, "."+host)
}

// withinFreshness reports whether a message received at [when] (the server's
// InternalDate) is recent enough for a login armed at [since]. A small skew grace
// tolerates a code that landed just before the arm; a zero [since] means unbounded.
func withinFreshness(when, since time.Time) bool {
	if since.IsZero() {
		return true
	}
	return !when.Before(since.Add(-freshnessSkew))
}

// ErrIMAPAuth is returned when the mailbox rejects the credential at IMAP LOGIN — a
// revoked or wrong app-password. It is a TYPED sentinel so a caller can tell a dead
// credential apart from a transient reach failure or a "no code yet", and surface the
// mailbox as broken (needs re-linking) instead of retrying a credential that will
// never work. The message stays STATIC and is never wrapped with the credential: an
// IMAP LOGIN error can echo the password, so only this fixed text is surfaced.
var ErrIMAPAuth = errors.New("otp: IMAP login failed")

// Compile-time assertion that IMAPMailbox satisfies MailboxReader.
var _ MailboxReader = IMAPMailbox{}
