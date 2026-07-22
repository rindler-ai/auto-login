package main

import (
	"errors"
	"reflect"
	"sort"
	"testing"

	"github.com/rindler-ai/auto-login/core/store"
)

// fakeKeyring is an in-memory keyringBackend for tests (shared across the
// package's *_test.go). It mimics the flat service+account→secret map the native
// CLIs expose, returning errNoEntry for a missing account.
type fakeKeyring struct{ m map[string]string }

func newFakeKeyring() *fakeKeyring { return &fakeKeyring{m: make(map[string]string)} }

func (f *fakeKeyring) set(account, secret string) error { f.m[account] = secret; return nil }
func (f *fakeKeyring) get(account string) (string, error) {
	v, ok := f.m[account]
	if !ok {
		return "", errNoEntry
	}
	return v, nil
}
func (f *fakeKeyring) del(account string) error { delete(f.m, account); return nil }

func TestKeyringStore_PutGetRoundTripsAllFields(t *testing.T) {
	s := NewKeyringStore(newFakeKeyring())
	rec := store.Record{
		Site:     "instacart.com",
		Username: "user@example.com",
		Password: "hunter2",
	}
	if err := s.Put(rec); err != nil {
		t.Fatalf("Put: %v", err)
	}
	got, err := s.Get("instacart.com")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if !reflect.DeepEqual(got, rec) {
		t.Fatalf("round-trip mismatch:\n got %+v\nwant %+v", got, rec)
	}
}

func TestKeyringStore_GetMissingIsErrNotFound(t *testing.T) {
	s := NewKeyringStore(newFakeKeyring())
	if _, err := s.Get("nope.com"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("want store.ErrNotFound, got %v", err)
	}
}

func TestKeyringStore_SitesTracksIndexAcrossPutAndDelete(t *testing.T) {
	s := NewKeyringStore(newFakeKeyring())
	for _, site := range []string{"b.com", "a.com", "c.com"} {
		if err := s.Put(store.Record{Site: site, Password: "x"}); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Put(store.Record{Site: "a.com", Password: "updated"}); err != nil { // dup must not double-list
		t.Fatal(err)
	}
	got, err := s.ListSites()
	if err != nil {
		t.Fatal(err)
	}
	sort.Strings(got)
	if want := []string{"a.com", "b.com", "c.com"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("ListSites = %v, want %v", got, want)
	}
	if err := s.Delete("b.com"); err != nil {
		t.Fatal(err)
	}
	if got, err := s.ListSites(); err != nil || !reflect.DeepEqual(got, []string{"a.com", "c.com"}) {
		t.Fatalf("after delete ListSites = %v (err %v), want [a.com c.com]", got, err)
	}
	if _, err := s.Get("b.com"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("deleted site still readable: %v", err)
	}
}

func TestKeyringStore_PutRejectsEmptySite(t *testing.T) {
	s := NewKeyringStore(newFakeKeyring())
	if err := s.Put(store.Record{Password: "x"}); err == nil {
		t.Fatal("expected error putting a record with no site")
	}
}

// The site index must never masquerade as a credential site.
func TestKeyringStore_IndexAccountNotListedAsSite(t *testing.T) {
	s := NewKeyringStore(newFakeKeyring())
	if err := s.Put(store.Record{Site: "a.com", Password: "x"}); err != nil {
		t.Fatal(err)
	}
	sites, err := s.ListSites()
	if err != nil {
		t.Fatal(err)
	}
	for _, site := range sites {
		if site == indexAccount {
			t.Fatalf("index account %q leaked into ListSites()", indexAccount)
		}
	}
}
