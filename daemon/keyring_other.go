//go:build !darwin && !linux

package main

import "errors"

// keyringService keeps the constant defined on every platform for reference.
const keyringService = "ai.rindler.autologin"

// newSystemBackend has no native keychain wiring yet on this OS (Windows
// Credential Manager / DPAPI is tracked under). The daemon falls back to
// the in-memory store with a warning — see loadStore.
func newSystemBackend() (keyringBackend, error) {
	return nil, errors.New("no native keychain backend for this OS yet")
}
