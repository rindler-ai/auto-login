package otp

import (
	"bytes"
	"encoding/base64"
	"io"
	"mime"
	"mime/multipart"
	"mime/quotedprintable"
	"net/mail"
	"strings"
)

// decodeMailText turns a raw RFC 822 message (headers + body, as fetched from
// IMAP) into readable text, decoding the Content-Transfer-Encoding and walking
// multipart bodies. Without this, a base64- or quoted-printable-encoded body is
// fed to ExtractCode as its ENCODED bytes, so a spurious digit run inside the
// base64 gets relayed instead of the real code (device_relay review finding).
// Std-lib only; on any parse failure it falls back to the raw text so we never
// do worse than before.
func decodeMailText(raw []byte) string {
	m, err := mail.ReadMessage(bytes.NewReader(raw))
	if err != nil {
		return string(raw)
	}
	mediaType, params, _ := mime.ParseMediaType(m.Header.Get("Content-Type"))
	if strings.HasPrefix(mediaType, "multipart/") && params["boundary"] != "" {
		if text := decodeMultipart(m.Body, params["boundary"]); text != "" {
			return text
		}
		return string(raw)
	}
	return decodePart(m.Header.Get("Content-Transfer-Encoding"), m.Body)
}

// decodePart reads a single body applying its transfer encoding.
func decodePart(transferEncoding string, body io.Reader) string {
	switch strings.ToLower(strings.TrimSpace(transferEncoding)) {
	case "base64":
		b, err := io.ReadAll(base64.NewDecoder(base64.StdEncoding, stripWhitespace(body)))
		if err == nil {
			return string(b)
		}
	case "quoted-printable":
		b, err := io.ReadAll(quotedprintable.NewReader(body))
		if err == nil {
			return string(b)
		}
	}
	b, _ := io.ReadAll(body)
	return string(b)
}

// decodeMultipart concatenates the decoded text of every part, preferring
// text/* parts (so the code is read from text, not attachment noise).
func decodeMultipart(body io.Reader, boundary string) string {
	mr := multipart.NewReader(body, boundary)
	var out strings.Builder
	for {
		part, err := mr.NextPart()
		if err != nil {
			break
		}
		ct, params, _ := mime.ParseMediaType(part.Header.Get("Content-Type"))
		if strings.HasPrefix(ct, "multipart/") && params["boundary"] != "" {
			out.WriteString(decodeMultipart(part, params["boundary"]))
			out.WriteString("\n")
			continue
		}
		if ct == "" || strings.HasPrefix(ct, "text/") {
			out.WriteString(decodePart(part.Header.Get("Content-Transfer-Encoding"), part))
			out.WriteString("\n")
		}
	}
	return out.String()
}

// stripWhitespace wraps a reader to drop CR/LF/space, since base64 email bodies
// are line-wrapped and the std decoder rejects embedded newlines.
func stripWhitespace(r io.Reader) io.Reader {
	b, _ := io.ReadAll(r)
	b = bytes.Map(func(c rune) rune {
		if c == '\r' || c == '\n' || c == ' ' || c == '\t' {
			return -1
		}
		return c
	}, b)
	return bytes.NewReader(b)
}
