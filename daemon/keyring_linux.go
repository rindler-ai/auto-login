//go:build linux

package main

import (
	"bytes"
	"errors"
	"os/exec"
	"strings"
)

// keyringService is the Secret Service attribute under which every custody
// account is stored (mirrors the macOS Keychain service name).
const keyringService = "ai.rindler.autologin"

// linuxKeyring drives the freedesktop Secret Service via `secret-tool`
// (libsecret-tools), which talks D-Bus to gnome-keyring / KWallet. `store` reads
// the secret from STDIN, so the secret never appears in the process argv. No
// CGO, no libsecret dev headers at build time.
type linuxKeyring struct{}

func newSystemBackend() (keyringBackend, error) {
	if _, err := exec.LookPath("secret-tool"); err != nil {
		return nil, errors.New("`secret-tool` not found (install libsecret-tools)")
	}
	return linuxKeyring{}, nil
}

func (linuxKeyring) set(account, secret string) error {
	cmd := exec.Command("secret-tool", "store", "--label=Auto-Login",
		"service", keyringService, "account", account)
	cmd.Stdin = strings.NewReader(secret)
	return cmd.Run()
}

func (linuxKeyring) get(account string) (string, error) {
	out, err := exec.Command("secret-tool", "lookup",
		"service", keyringService, "account", account).Output()
	if err != nil {
		// secret-tool exits 1 with empty output when the attribute is absent.
		if ee := (&exec.ExitError{}); errors.As(err, &ee) {
			return "", errNoEntry
		}
		return "", err
	}
	if len(bytes.TrimSpace(out)) == 0 {
		return "", errNoEntry
	}
	return strings.TrimRight(string(out), "\n"), nil
}

func (linuxKeyring) del(account string) error {
	cmd := exec.Command("secret-tool", "clear",
		"service", keyringService, "account", account)
	return cmd.Run()
}
