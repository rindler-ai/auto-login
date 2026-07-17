package agent

import (
	"fmt"
	"sync"
	"testing"
)

func TestReplayGuard_FirstSeeThenRepeat(t *testing.T) {
	g := newReplayGuard(16)
	if !g.firstSee("r1") {
		t.Fatal("first sighting of r1 must be true")
	}
	if g.firstSee("r1") {
		t.Fatal("repeat of r1 must be false (replay)")
	}
	if !g.firstSee("r2") {
		t.Fatal("distinct r2 must be true")
	}
}

func TestReplayGuard_EmptyIdNeverDeduped(t *testing.T) {
	g := newReplayGuard(16)
	for i := 0; i < 3; i++ {
		if !g.firstSee("") {
			t.Fatalf("empty id must never be treated as a replay (iteration %d)", i)
		}
	}
}

func TestReplayGuard_EvictsOldestPastWindow(t *testing.T) {
	g := newReplayGuard(2)
	g.firstSee("a")
	g.firstSee("b")
	g.firstSee("c") // evicts "a"
	if !g.firstSee("a") {
		t.Fatal("a should have been evicted and thus be first-seen again")
	}
	// "b"/"c" — b was evicted when a re-inserted (window=2). Only the two most
	// recent are retained; verify the invariant that size never exceeds max.
	if len(g.order) > g.max || len(g.seen) > g.max {
		t.Fatalf("guard exceeded max: order=%d seen=%d max=%d", len(g.order), len(g.seen), g.max)
	}
}

func TestReplayGuard_ConcurrentSafe(t *testing.T) {
	g := newReplayGuard(4096)
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				g.firstSee(fmt.Sprintf("id-%d-%d", n, j))
			}
		}(i)
	}
	wg.Wait()
	if len(g.order) != len(g.seen) {
		t.Fatalf("order/seen desync: %d vs %d", len(g.order), len(g.seen))
	}
}
