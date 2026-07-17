package otp

import (
	"errors"
	"testing"
)

func TestExtractCode(t *testing.T) {
	tests := []struct {
		name string
		body string
		opts Options
		want string // "" means expect ErrNoCode
	}{
		// --- positive: common real-world phrasings ---
		{"code-is", "Your verification code is 123456", Options{}, "123456"},
		{"code-after", "482913 is your Acme verification code.", Options{}, "482913"},
		{"colon", "Your login code: 774812\nExpires in 10 minutes.", Options{}, "774812"},
		{"otp-word", "OTP: 220145. Do not share it with anyone.", Options{}, "220145"},
		{"passcode", "Use passcode 903214 to finish signing in.", Options{}, "903214"},
		{"security-code", "Your security code is 665201.", Options{}, "665201"},
		{"one-time", "Your one-time passcode is 917340.", Options{}, "917340"},
		{"access-code", "Enter access code 551020 to continue.", Options{}, "551020"},
		{"confirmation-code", "Confirmation code: 348812", Options{}, "348812"},
		{"your-brand-code", "Your Auto-Login code is 700123.", Options{}, "700123"},
		{"enter-this-code", "Enter this code to verify: 431900", Options{}, "431900"},
		{"subject-line", "123987 is your code\nSomeone requested a sign-in code.", Options{}, "123987"},
		{"7-digit", "Your one-time passcode is 9271043.", Options{}, "9271043"},
		{"8-digit", "Your verification code is 90183472.", Options{}, "90183472"},
		{"grouped-space", "Enter this code: 048 913 to continue.", Options{}, "048913"},
		{"grouped-hyphen", "Your verification code is 771-204.", Options{}, "771204"},
		{"grouped-8", "Your access code is 1234-5678.", Options{}, "12345678"},
		{"magic-link-param", "Sign in: https://acme.com/verify?code=558712&u=42", Options{}, "558712"},
		{"lone-code-no-keyword", "553201\n\nAcme", Options{}, "553201"},
		{"exact-length-opt", "Your PIN is 1234 for the door", Options{Length: 4, MinLength: 4, MaxLength: 4}, "1234"},

		// --- code wins over a competing number in the same body ---
		{"code-beats-order", "Your code is 12345678 for order 87654321.", Options{}, "12345678"},
		{"code-beats-year", "Copyright 2024 Acme Inc. Your login code: 908771", Options{}, "908771"},
		{"code-beats-phone", "Your code is 447120. Questions? Call 1-800-555-0134.", Options{}, "447120"},
		{"code-beats-amount", "Your receipt total is 145.99. Verification code: 662014", Options{}, "662014"},

		// --- false-positive rejection: no real code present ---
		{"phone-only", "Call us at 1-800-123-4567 for support.", Options{}, ""},
		{"phone-intl", "Reach us at +44 20 7946 0958 anytime.", Options{}, ""},
		{"order-number", "Order4567 has shipped.", Options{}, ""},
		{"order-number-8", "Your order 87654321 is confirmed and on its way.", Options{}, ""},
		{"tracking", "Tracking number 940055551234 updated.", Options{}, ""},
		{"account-number", "Your account number is 55501234 as always.", Options{}, ""},
		{"year-only", "See you in 2024! Thanks for being a member since 2019.", Options{}, ""},
		{"invoice", "Invoice 20240612 is now available to download.", Options{}, ""},
		{"amount-only", "Your total is 129900 cents this month.", Options{}, ""},
		{"grouped-no-keyword", "Ref 123 456 was logged in our system today.", Options{}, ""},
		{"no-digits", "Welcome to Acme! Please confirm your email address.", Options{}, ""},
		{"ssn", "Your social security number ending 123456789 is on file.", Options{}, ""},

		// --- alphanumeric + variable-length codes (anchored on a verification cue) ---
		{"alnum-code-is", "Your verification code is G7X2QP", Options{}, "G7X2QP"},
		{"alnum-colon", "Login code: AB12CD — do not share it.", Options{}, "AB12CD"},
		{"alnum-lower", "Your code is a1b2c3 to continue.", Options{}, "a1b2c3"},
		{"alnum-short", "Your one-time code is X7Y2.", Options{}, "X7Y2"},
		{"alnum-long", "Enter this code: R4T9K2M0PQ to verify.", Options{}, "R4T9K2M0PQ"},
		{"alnum-beats-word", "Hi Sam, your Acme verification code is 4F9K1. Ignore if not you.", Options{}, "4F9K1"},
		// A mixed token with NO verification cue is prose, not a code.
		{"alnum-no-cue", "Ref AB12CD was logged in our system today.", Options{}, ""},

		// --- RequireContext (the SMS auto-read path): a bare number needs a cue ---
		{"sms-code-cued", "Your verification code is 903472", Options{RequireContext: true}, "903472"},
		{"sms-alnum-cued", "Your code is G7X2QP", Options{RequireContext: true}, "G7X2QP"},
		{"sms-lone-code-rejected", "553201\n\nAcme", Options{RequireContext: true}, ""},
		{"sms-promo-rejected", "Use promo 553201 for 20% off today!", Options{RequireContext: true}, ""},
		{"sms-package-rejected", "Your package 483920 arrives today.", Options{RequireContext: true}, ""},
		{"sms-reservation-rejected", "Your reservation 448190 is confirmed.", Options{RequireContext: true}, ""},
		{"sms-date-rejected", "Statement ready for 20240612.", Options{RequireContext: true}, ""},
		// The same lone code, WITHOUT RequireContext (email path), still extracts.
		{"lone-code-default-accepts", "553201\n\nAcme", Options{}, "553201"},

		// --- adversarial-review regressions (wrong-code injection) ---
		// The real alnum code is nearer the cue than a digit decoy and must win.
		{"alnum-beats-digit-decoy", "Your verification code is G7X2QP (ref 123456)", Options{}, "G7X2QP"},
		{"alnum-beats-digit-decoy-sms", "Your verification code is G7X2QP (ref 123456)", Options{RequireContext: true}, "G7X2QP"},
		{"alnum-beats-order-decoy", "Your login code is AB12CD. Order 987654 shipped.", Options{}, "AB12CD"},
		// A hyphen-grouped alnum code must reassemble, not truncate to one half.
		{"alnum-hyphen-grouped", "Your verification code is X7G2-9K1P", Options{}, "X7G29K1P"},
		// A weakly-cued date-shaped digit code must NOT be dropped.
		{"weak-cued-date-code", "Your PIN is 200112", Options{}, "200112"},
		{"weak-cued-date-code-sms", "Your PIN is 200112", Options{RequireContext: true}, "200112"},
		{"weak-cued-date-code-8", "Your PIN is 20240115", Options{RequireContext: true}, "20240115"},
		// Backward-compat: a lone date-shaped code on the email path still extracts.
		{"lone-date-email-accepts", "200112\n\nAcme", Options{}, "200112"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := ExtractCode(tc.body, tc.opts)
			if tc.want == "" {
				if !errors.Is(err, ErrNoCode) {
					t.Fatalf("want ErrNoCode, got code=%q err=%v", got, err)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v (body=%q)", err, tc.body)
			}
			if got != tc.want {
				t.Fatalf("ExtractCode = %q, want %q (body=%q)", got, tc.want, tc.body)
			}
		})
	}
}

// The returned value is always exactly the code — never any surrounding text,
// which is what makes it safe to relay.
func TestExtractCodeReturnsOnlyDigits(t *testing.T) {
	body := "Hi John, your verification code is 654321. It expires soon."
	got, err := ExtractCode(body, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if got != "654321" {
		t.Fatalf("got %q, want 654321", got)
	}
	for _, r := range got {
		if r < '0' || r > '9' {
			t.Fatalf("code contains a non-digit rune %q", r)
		}
	}
}

func TestExtractCodeLengthBounds(t *testing.T) {
	// A 5-digit number is below the default floor and must not be returned.
	if _, err := ExtractCode("Your code is 12345 today", Options{}); !errors.Is(err, ErrNoCode) {
		t.Fatalf("5-digit should be rejected by default bounds, got err=%v", err)
	}
	// ...but is accepted when the caller widens the range.
	got, err := ExtractCode("Your code is 12345 today", Options{Length: 5, MinLength: 5, MaxLength: 8})
	if err != nil || got != "12345" {
		t.Fatalf("widened bounds: got %q err=%v, want 12345", got, err)
	}
}
