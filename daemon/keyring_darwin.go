//go:build darwin

package main

import (
	"errors"
	"os/exec"
	"strings"
)

// keyringService is the macOS Keychain service (and Linux Secret Service
// attribute) under which every custody account is stored.
const keyringService = "ai.rindler.autologin"

// macKeyring drives the macOS login Keychain via the built-in `security` tool.
// No CGO, no external module: the secret is stored in the user's Keychain and
// (with an ACL prompt on first access) gated by the OS. `security` is present on
// every macOS install.
type macKeyring struct{}

func newSystemBackend() (keyringBackend, error) {
	if _, err := exec.LookPath("security"); err != nil {
		return nil, errors.New("macOS `security` tool not found on PATH")
	}
	return macKeyring{}, nil
}

func (macKeyring) set(account, secret string) error {
	// -U updates the item if it already exists instead of erroring.
	cmd := exec.Command("security", "add-generic-password",
		"-U", "-s", keyringService, "-a", account, "-w", secret)
	return runQuiet(cmd)
}

func (macKeyring) get(account string) (string, error) {
	out, err := exec.Command("security", "find-generic-password",
		"-s", keyringService, "-a", account, "-w").Output()
	if err != nil {
		// `security` exits 44 (errSecItemNotFound) when the item is absent.
		if ee := (&exec.ExitError{}); errors.As(err, &ee) {
			return "", errNoEntry
		}
		return "", err
	}
	return strings.TrimRight(string(out), "\n"), nil
}

func (macKeyring) del(account string) error {
	cmd := exec.Command("security", "delete-generic-password",
		"-s", keyringService, "-a", account)
	if err := runQuiet(cmd); err != nil {
		if ee := (&exec.ExitError{}); errors.As(err, &ee) {
			return errNoEntry
		}
		return err
	}
	return nil
}

func runQuiet(cmd *exec.Cmd) error {
	cmd.Stdout, cmd.Stderr = nil, nil
	return cmd.Run()
}
