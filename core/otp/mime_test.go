package otp

import (
	"encoding/base64"
	"strings"
	"testing"
)

// A base64-encoded body must be decoded before scanning; the raw base64 would
// otherwise yield a wrong digit run.
func TestDecodeMailText_Base64(t *testing.T) {
	plain := "Your Auto-Login verification code is 481902. It expires in 10 minutes."
	enc := base64.StdEncoding.EncodeToString([]byte(plain))
	// email base64 is line-wrapped at 76 cols
	var wrapped strings.Builder
	for i := 0; i < len(enc); i += 76 {
		end := i + 76
		if end > len(enc) {
			end = len(enc)
		}
		wrapped.WriteString(enc[i:end])
		wrapped.WriteString("\r\n")
	}
	raw := "Subject: code\r\nContent-Transfer-Encoding: base64\r\nContent-Type: text/plain\r\n\r\n" + wrapped.String()

	got := decodeMailText([]byte(raw))
	if !strings.Contains(got, "481902") {
		t.Fatalf("base64 body not decoded; got %q", got)
	}
	code, err := ExtractCode(got, Options{})
	if err != nil || code != "481902" {
		t.Fatalf("ExtractCode on decoded body = %q, %v; want 481902", code, err)
	}
}

func TestDecodeMailText_QuotedPrintable(t *testing.T) {
	raw := "Content-Transfer-Encoding: quoted-printable\r\nContent-Type: text/plain\r\n\r\n" +
		"Your code is 730025=2E Thanks=21\r\n"
	got := decodeMailText([]byte(raw))
	if !strings.Contains(got, "730025.") {
		t.Fatalf("quoted-printable not decoded; got %q", got)
	}
}

func TestDecodeMailText_MultipartPrefersText(t *testing.T) {
	b := "BOUND"
	raw := "Content-Type: multipart/alternative; boundary=" + b + "\r\n\r\n" +
		"--" + b + "\r\nContent-Type: text/plain\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
		base64.StdEncoding.EncodeToString([]byte("Code: 556677")) + "\r\n" +
		"--" + b + "--\r\n"
	got := decodeMailText([]byte(raw))
	if !strings.Contains(got, "556677") {
		t.Fatalf("multipart text/plain base64 not decoded; got %q", got)
	}
}

// Plain (no transfer-encoding) still works — the fix never does worse than raw.
func TestDecodeMailText_PlainPassthrough(t *testing.T) {
	raw := "Content-Type: text/plain\r\n\r\nYour code is 112233\r\n"
	if !strings.Contains(decodeMailText([]byte(raw)), "112233") {
		t.Fatal("plain body lost")
	}
	// Even a non-RFC822 blob falls back to raw text.
	if !strings.Contains(decodeMailText([]byte("just 998877 here")), "998877") {
		t.Fatal("non-message fallback lost the text")
	}
}
