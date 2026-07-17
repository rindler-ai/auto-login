package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"

	"github.com/rindler-ai/auto-login/core/store"
	"github.com/rindler-ai/auto-login/core/totp"
)

// KeyringStore backs the device-local credential store with the OS keychain
// (macOS Keychain via `security`, Linux Secret Service via `secret-tool`,
// build-tagged in keyring_<os>.go). It is the production replacement for the
// in-memory store.MemStore: every durable secret lives ONLY in the OS keychain,
// never in a plaintext file and never on the wire.
//
// The keychain APIs we drive are a flat service+account→secret map with no
// enumeration, so KeyringStore keeps its own site index under a reserved account
// key. All storage/serialization logic is testable against a fake keyringBackend;
// the exec-based native backends are thin and compile-checked per platform.
type KeyringStore struct {
	mu sync.Mutex
	kr keyringBackend
}

// keyringBackend is the minimal OS-keychain surface KeyringStore needs. The
// native impls (keyring_darwin.go / keyring_linux.go) satisfy it via the
// platform CLI; tests inject a fake. get returns errNoEntry when absent.
type keyringBackend interface {
	set(account, secret string) error
	get(account string) (string, error)
	del(account string) error
}

// errNoEntry is what a backend returns from get when the account is not stored.
var errNoEntry = errors.New("keyring: no entry")

// indexAccount is the reserved account holding the JSON list of stored sites.
// The "rindler-meta:" prefix keeps it out of the site namespace — accounts are
// bare hostnames (e.g. "instacart.com"), which cannot contain a colon — and,
// unlike a NUL sentinel, it survives being passed as an exec argv to the native
// keychain CLIs. Device-identity accounts (keyring_identity.go) share the prefix.
const indexAccount = "rindler-meta:site-index"

// NewKeyringStore wraps a backend as a store.Store.
func NewKeyringStore(kr keyringBackend) *KeyringStore { return &KeyringStore{kr: kr} }

// keyringRecord is the JSON projection persisted per site. It mirrors
// store.Record with explicit fields so serialization is stable and independent
// of the library type's layout.
type keyringRecord struct {
	Site     string       `json:"site"`
	Username string       `json:"username,omitempty"`
	Password string       `json:"password,omitempty"`
	TOTP     *totp.Config `json:"totp,omitempty"`
}

func (s *KeyringStore) Get(site string) (store.Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	raw, err := s.kr.get(site)
	if errors.Is(err, errNoEntry) {
		return store.Record{}, store.ErrNotFound
	}
	if err != nil {
		return store.Record{}, fmt.Errorf("keyring get %q: %w", site, err)
	}
	var kr keyringRecord
	if err := json.Unmarshal([]byte(raw), &kr); err != nil {
		return store.Record{}, fmt.Errorf("keyring decode %q: %w", site, err)
	}
	return store.Record{Site: kr.Site, Username: kr.Username, Password: kr.Password, TOTP: kr.TOTP}, nil
}

func (s *KeyringStore) Put(rec store.Record) error {
	if rec.Site == "" {
		return errors.New("keyring: record has no site")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := json.Marshal(keyringRecord{Site: rec.Site, Username: rec.Username, Password: rec.Password, TOTP: rec.TOTP})
	if err != nil {
		return fmt.Errorf("keyring encode %q: %w", rec.Site, err)
	}
	if err := s.kr.set(rec.Site, string(b)); err != nil {
		return fmt.Errorf("keyring set %q: %w", rec.Site, err)
	}
	return s.addToIndex(rec.Site)
}

func (s *KeyringStore) Delete(site string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.kr.del(site); err != nil && !errors.Is(err, errNoEntry) {
		return fmt.Errorf("keyring delete %q: %w", site, err)
	}
	return s.removeFromIndex(site)
}

// ListSites returns the domains held, from the store's own site index. The
// keychain APIs have no native enumeration, so this reads the reserved index
// account; on any absence/parse issue readIndex yields an empty slice, so this
// is best-effort and never blocks (empty slice + nil error).
func (s *KeyringStore) ListSites() ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.readIndex(), nil
}

// readIndex returns the stored site list (empty on any absence/parse issue — the
// index is a convenience cache, never the source of a secret).
func (s *KeyringStore) readIndex() []string {
	raw, err := s.kr.get(indexAccount)
	if err != nil {
		return nil
	}
	var sites []string
	if json.Unmarshal([]byte(raw), &sites) != nil {
		return nil
	}
	return sites
}

func (s *KeyringStore) writeIndex(sites []string) error {
	sort.Strings(sites)
	b, err := json.Marshal(sites)
	if err != nil {
		return err
	}
	return s.kr.set(indexAccount, string(b))
}

func (s *KeyringStore) addToIndex(site string) error {
	sites := s.readIndex()
	for _, x := range sites {
		if x == site {
			return nil
		}
	}
	return s.writeIndex(append(sites, site))
}

func (s *KeyringStore) removeFromIndex(site string) error {
	sites := s.readIndex()
	out := sites[:0]
	for _, x := range sites {
		if x != site {
			out = append(out, x)
		}
	}
	return s.writeIndex(out)
}
