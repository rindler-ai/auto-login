// Package otp acquires one-time login codes ON THE DEVICE and hands only the
// extracted CODE to the relay.
//
// The load-bearing invariant of the custody app holds here too: a mailbox OAuth
// token / IMAP credential is a DURABLE secret. It lives in the device
// store/keychain and NEVER transits. This package reads the mailbox locally,
// extracts the short-lived code with ExtractCode, and returns ONLY that code to
// the relay — the raw email body and the mailbox credential never leave the
// device.
//
// ExtractCode is a pure function and is the testable core; the IMAP-backed
// MailboxReader (imap.go) performs network I/O and is not unit-tested (it is
// exercised via the FakeMailbox in tests, mirroring how the server
// tests its email-OTP lane with a fake inbox reader).
package otp

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"
)

// ErrNoCode is returned when no plausible one-time code is present in the body.
var ErrNoCode = errors.New("otp: no one-time code found")

// Options tunes ExtractCode. The zero value is sensible: a 6-digit code is
// preferred and digit codes of length 6–8 are accepted.
type Options struct {
	// Length is the preferred/expected code length (default 6). Digit candidates
	// whose length is in [MinLength, MaxLength] are all considered; one whose
	// length equals Length scores highest.
	Length int
	// MinLength and MaxLength bound the accepted DIGIT count (defaults 6 and 8).
	// Alphanumeric (mixed letter+digit) codes use a fixed [4,12] range because
	// their length varies far more by site.
	MinLength int
	MaxLength int
	// RequireContext rejects a candidate that has NO verification keyword/cue
	// nearby (a strong/weak keyword, or being anchored directly after one). Off
	// by default: the email-OTP callers accept a lone code in a body they only
	// read because a login is already waiting. The SMS auto-read path sets it
	// TRUE: the on-device SMS reader can see a text arriving mid-login that is
	// NOT the code (a promo, a delivery update, a date), and because the relay
	// is single-shot newest-wins, a wrong number extracted then would be injected
	// and consumed, failing the login. Requiring a verification cue means a bare
	// number is never mistaken for a code. (Mixed alphanumeric candidates always
	// require a cue regardless of this flag — a random mixed token is not a code.)
	RequireContext bool
}

func (o *Options) normalize() {
	if o.MinLength <= 0 {
		o.MinLength = 6
	}
	if o.MaxLength <= 0 {
		o.MaxLength = 8
	}
	if o.MaxLength < o.MinLength {
		o.MaxLength = o.MinLength
	}
	if o.Length <= 0 {
		o.Length = 6
	}
}

// Alphanumeric code length bounds (mixed letter+digit codes vary a lot by site).
const (
	alnumMinLen = 4
	alnumMaxLen = 12
)

var (
	// Codes are often printed grouped: "048 913" or "1234-5678".
	grouped6Re = regexp.MustCompile(`\b\d{3}[ \-]\d{3}\b`)
	grouped8Re = regexp.MustCompile(`\b\d{4}[ \-]\d{4}\b`)

	// A mixed alphanumeric code contains BOTH a letter and a digit ("G7X2QP",
	// "AB12CD", "4F9K1"). We gather every 4–12 char alphanumeric run and keep
	// only the mixed ones (pure-digit runs are handled by the digit path; a
	// pure-letter run is a word). A mixed run ALWAYS requires a verification cue
	// to be accepted, so gathering it is safe for every caller.
	alnumRe = regexp.MustCompile(`\b[A-Za-z0-9]{4,12}\b`)

	// Hyphen-grouped alphanumeric codes ("X7G2-9K1P"). Reassembled ONLY when both
	// halves carry a digit (checked below), so an ordinary "word-number" pair is
	// not swept in. Space-separated alphanumerics are deliberately NOT reassembled
	// (too word-like to tell from prose).
	groupedAlnumRe = regexp.MustCompile(`\b[A-Za-z0-9]{2,8}-[A-Za-z0-9]{2,8}\b`)

	// Phone numbers (3-3-4 with optional country code / punctuation). A candidate
	// overlapping a phone match is dropped so a "call us at 1-800-555-0134" line
	// never yields a fake code.
	phoneRe = regexp.MustCompile(`(?i)(?:\+?\d{1,3}[ .()\-]{1,4})?\(?\d{3}\)?[ .\-]\d{3}[ .\-]\d{4}\b`)

	// Strong keywords: an adjacent number is almost certainly the login code.
	// Longer phrases are listed first (Go regexp is leftmost-first).
	posStrongRe = regexp.MustCompile(`(?i)(?:\bverification code\b|\bone[ \-]?time(?: passcode| password| code| pin)?\b|\bsecurity code\b|\bauth(?:entication)? code\b|\baccess code\b|\bconfirmation code\b|\blog[ \-]?in code\b|\bsign[ \-]?in code\b|\byour(?: [a-z0-9]+){0,3} code\b|\bcode is\b|\bcode:|\benter (?:this |the )?code\b|\bpass ?code\b|\botp\b|\bverif(?:y|ication)\b)`)

	// Weak keywords: a mild signal that cannot, on its own, overcome a strong
	// negative (e.g. "No code here, order 90011234" must still be rejected).
	posWeakRe = regexp.MustCompile(`(?i)(?:\bcode\b|\bpin\b)`)

	// Negative keywords: an adjacent number is probably NOT a login code
	// (order/invoice/account/tracking/phone/amount ...).
	negKwRe = regexp.MustCompile(`(?i)(?:\border(?: ?number| ?no\.?)?\b|\binvoice\b|\baccount(?: number| no\.?| id)?\b|\bacct\b|\breference(?: number)?\b|\bref\.? ?#|\btracking(?: number)?\b|\bconfirmation number\b|\bamount\b|\bsubtotal\b|\btotal\b|\bbalance\b|\brouting(?: number)?\b|\bzip(?: ?code)?\b|\bpostal code\b|\bssn\b|\bsocial security\b|\bcard ending\b|\bmember id\b|\bpolicy(?: number)?\b|\bcustomer id\b|\bcall us\b)`)
)

// contiguousReFor caches the per-length-bound digit-run regex.
var (
	contiguousReMu    sync.Mutex
	contiguousReCache = map[[2]int]*regexp.Regexp{}
)

func contiguousReFor(minLen, maxLen int) *regexp.Regexp {
	key := [2]int{minLen, maxLen}
	contiguousReMu.Lock()
	defer contiguousReMu.Unlock()
	if re, ok := contiguousReCache[key]; ok {
		return re
	}
	re := regexp.MustCompile(fmt.Sprintf(`\b\d{%d,%d}\b`, minLen, maxLen))
	contiguousReCache[key] = re
	return re
}

type candidate struct {
	code    string
	start   int
	end     int
	grouped bool
	alnum   bool // mixed letter+digit (always requires a cue)
}

func nearestGap(spans [][]int, s, e int) int {
	best := 1 << 30
	for _, sp := range spans {
		var gap int
		switch {
		case sp[1] <= s:
			gap = s - sp[1]
		case sp[0] >= e:
			gap = sp[0] - e
		default:
			gap = 0
		}
		if gap < best {
			best = gap
		}
	}
	return best
}

// followsGap returns how far AFTER the nearest preceding span in `spans` the
// candidate begins (>=0), or a huge value if no span precedes it within reach.
// A small value means the candidate is anchored directly after that cue.
func followsGap(spans [][]int, s int) int {
	best := 1 << 30
	for _, sp := range spans {
		if sp[1] <= s {
			if gap := s - sp[1]; gap < best {
				best = gap
			}
		}
	}
	return best
}

func overlapsAny(spans [][]int, s, e int) bool {
	for _, sp := range spans {
		if s < sp[1] && sp[0] < e {
			return true
		}
	}
	return false
}

func digitsOnly(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteByte(byte(r))
		}
	}
	return b.String()
}

func hasDigit(s string) bool {
	for _, r := range s {
		if r >= '0' && r <= '9' {
			return true
		}
	}
	return false
}

func hasLetterAndDigit(s string) bool {
	var letter, digit bool
	for _, r := range s {
		switch {
		case r >= '0' && r <= '9':
			digit = true
		case (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z'):
			letter = true
		}
	}
	return letter && digit
}

// ExtractCode pulls a single one-time code out of an email/SMS body (callers
// should prepend the subject line so subject-borne codes are seen). It handles
// digit AND mixed alphanumeric codes of varying length, prefers a code adjacent
// to a code/verification/OTP/passcode keyword — and most strongly the token
// CLOSEST after such a phrase, so the code the message names beats an unrelated
// number nearby — rejects phone/order/tracking/account numbers, and returns
// ErrNoCode when nothing plausible is present.
//
// It never returns any input other than the code token itself.
func ExtractCode(body string, opts Options) (string, error) {
	opts.normalize()

	strongSpans := posStrongRe.FindAllStringIndex(body, -1)
	weakSpans := posWeakRe.FindAllStringIndex(body, -1)
	negSpans := negKwRe.FindAllStringIndex(body, -1)
	phoneSpans := phoneRe.FindAllStringIndex(body, -1)

	var cands []candidate
	for _, m := range contiguousReFor(opts.MinLength, opts.MaxLength).FindAllStringIndex(body, -1) {
		cands = append(cands, candidate{code: body[m[0]:m[1]], start: m[0], end: m[1]})
	}
	for _, re := range []*regexp.Regexp{grouped6Re, grouped8Re} {
		for _, m := range re.FindAllStringIndex(body, -1) {
			cands = append(cands, candidate{code: digitsOnly(body[m[0]:m[1]]), start: m[0], end: m[1], grouped: true})
		}
	}
	// Hyphen-grouped alphanumeric codes ("X7G2-9K1P"), reassembled when both halves
	// carry a digit. Added BEFORE the plain-fragment pass so the full code wins a
	// score tie over its own halves (ties keep the earliest candidate).
	for _, m := range groupedAlnumRe.FindAllStringIndex(body, -1) {
		a, b, found := strings.Cut(body[m[0]:m[1]], "-")
		if !found || !hasDigit(a) || !hasDigit(b) {
			continue
		}
		joined := a + b
		if n := len(joined); n < alnumMinLen || n > alnumMaxLen {
			continue
		}
		cands = append(cands, candidate{code: joined, start: m[0], end: m[1], alnum: true})
	}
	// Mixed alphanumeric candidates (letter+digit). Length is bounded separately.
	for _, m := range alnumRe.FindAllStringIndex(body, -1) {
		tok := body[m[0]:m[1]]
		if !hasLetterAndDigit(tok) {
			continue // pure digits (digit path) or pure letters (a word)
		}
		if n := len(tok); n < alnumMinLen || n > alnumMaxLen {
			continue
		}
		cands = append(cands, candidate{code: tok, start: m[0], end: m[1], alnum: true})
	}

	const (
		strongWindow = 64 // max gap for a strong keyword to score
		weakWindow   = 24 // max gap for a weak keyword to score
		negWindow    = 24 // max gap for a negative keyword to penalize
		groupWindow  = 40 // grouped codes need a STRONG keyword this close to count
		anchorWindow = 20 // a code THIS close AFTER a strong cue is "the code"
		rejectFloor  = -50
	)

	bestScore := rejectFloor
	bestIdx := -1
	for i, c := range cands {
		if !c.alnum {
			if n := len(c.code); n < opts.MinLength || n > opts.MaxLength {
				continue
			}
		}
		if overlapsAny(phoneSpans, c.start, c.end) {
			continue // phone-number fragment
		}
		strongGap := nearestGap(strongSpans, c.start, c.end)
		weakGap := nearestGap(weakSpans, c.start, c.end)
		anchorGap := followsGap(strongSpans, c.start)
		anchored := anchorGap <= anchorWindow

		hasCue := anchored || strongGap <= strongWindow || weakGap <= weakWindow
		// A mixed alphanumeric token is only a code with a verification cue; a
		// random "AB12CD" in prose is not. And when the caller requires context
		// (the SMS path), even a bare digit run needs a cue.
		if (c.alnum || opts.RequireContext) && !hasCue {
			continue
		}
		if c.grouped && strongGap > groupWindow {
			continue // a bare grouped triple with no code keyword is too risky
		}

		score := 0
		if anchored {
			// Decay with distance so the token IMMEDIATELY after the cue wins: "your
			// code is <THIS>" beats an unrelated number a few words later. Without the
			// decay a same-cue digit run could out-score the real (nearer) code via
			// its length bonus.
			score += 300 - min(anchorGap, anchorWindow)*10 // 100..300
		}
		if strongGap <= strongWindow {
			score += 220 - min(strongGap, 150) // 156..220
		}
		if weakGap <= weakWindow {
			score += 50 - min(weakGap, 40) // 10..50 — never beats a strong negative
		}
		if g := nearestGap(negSpans, c.start, c.end); g <= negWindow {
			score -= 200 - min(g, 150) // 176..200 penalty
		}
		if !c.alnum {
			switch len(c.code) {
			case opts.Length:
				score += 30
			case 6:
				score += 10
			}
		}
		if c.grouped {
			score -= 5
		}

		// Strictly greater keeps the earliest candidate on ties (deterministic).
		if score > bestScore {
			bestScore = score
			bestIdx = i
		}
	}

	if bestIdx < 0 {
		return "", ErrNoCode
	}
	return cands[bestIdx].code, nil
}
