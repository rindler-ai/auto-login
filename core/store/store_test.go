package store

import (
	"sync"
	"testing"
)

func TestMemStoreCRUD(t *testing.T) {
	s := NewMemStore()
	if _, err := s.Get("nope"); err != ErrNotFound {
		t.Errorf("empty get: want ErrNotFound, got %v", err)
	}
	rec := Record{Site: "s.com", Username: "u", Password: "p"}
	if err := s.Put(rec); err != nil {
		t.Fatalf("put: %v", err)
	}
	got, err := s.Get("s.com")
	if err != nil || got.Username != "u" || got.Password != "p" {
		t.Fatalf("get: %+v %v", got, err)
	}
	if sites, err := s.ListSites(); err != nil || len(sites) != 1 || sites[0] != "s.com" {
		t.Errorf("sites: %v (err %v)", sites, err)
	}
	if err := s.Delete("s.com"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Get("s.com"); err != ErrNotFound {
		t.Errorf("after delete: want ErrNotFound, got %v", err)
	}
}

func TestMemStorePutRejectsNoSite(t *testing.T) {
	if err := NewMemStore().Put(Record{Username: "u"}); err == nil {
		t.Error("put w/o site: want error")
	}
}

func TestMemStoreConcurrent(t *testing.T) {
	s := NewMemStore()
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			site := string(rune('a'+i%26)) + ".com"
			_ = s.Put(Record{Site: site, Username: "u"})
			_, _ = s.Get(site)
			_, _ = s.ListSites()
		}(i)
	}
	wg.Wait()
}
