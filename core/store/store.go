// Package store holds the customer's credentials on the device. The interface
// is backed in production by the OS keychain / secure enclave (macOS Keychain,
// Windows Credential Manager, Linux libsecret) — those platform impls land in a
// follow-up; MemStore here is the testable in-memory backing (issue,
// ).
//
// The store is the ONLY place a durable secret (password, mailbox
// token) lives, and it lives ONLY on the device. The relay package reads exactly
// one value out of it per approved ping and seals it; the store never serializes
// a secret onto the wire.
package store

import (
	"errors"
	"sync"
)

// Record is the credential set held for one site. Any field may be empty when
// the site does not use it (e.g. a passwordless email-OTP site has no Password).
type Record struct {
	Site     string
	Username string
	Password string
}

// ErrNotFound is returned when no record exists for a site.
var ErrNotFound = errors.New("store: no credential for site")

// Store is the device-local credential backing.
type Store interface {
	Get(site string) (Record, error)
	Put(rec Record) error
	Delete(site string) error
	// ListSites returns the domains the device currently holds a login for
	// (domains only — never a secret). An empty slice is valid. The error lets a
	// backend that cannot enumerate (e.g. some native keychains) report so; those
	// return an empty slice + nil error best-effort rather than blocking.
	ListSites() ([]string, error)
}

// MemStore is an in-memory Store for tests and the dev fallback. It is
// concurrency-safe. It is NOT a durable or secure backing — the OS-keychain
// impls replace it in production.
type MemStore struct {
	mu   sync.RWMutex
	recs map[string]Record
}

// NewMemStore returns an empty in-memory store.
func NewMemStore() *MemStore {
	return &MemStore{recs: make(map[string]Record)}
}

func (m *MemStore) Get(site string) (Record, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	r, ok := m.recs[site]
	if !ok {
		return Record{}, ErrNotFound
	}
	return r, nil
}

func (m *MemStore) Put(rec Record) error {
	if rec.Site == "" {
		return errors.New("store: record has no site")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.recs[rec.Site] = rec
	return nil
}

func (m *MemStore) Delete(site string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.recs, site)
	return nil
}

// ListSites returns the enrolled site keys. The error is always nil for the
// in-memory backing (it can always enumerate).
func (m *MemStore) ListSites() ([]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]string, 0, len(m.recs))
	for s := range m.recs {
		out = append(out, s)
	}
	return out, nil
}
