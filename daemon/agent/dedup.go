package agent

import "sync"

// replayWindow bounds how many recent request_ids the daemon remembers. A ping
// whose request_id was already answered is declined (never released twice) — the
// core replay defense on the device side, complementing the hub's Ed25519
// challenge. The window is generous; a replayed ping older than it is harmless
// anyway because its WorkerEphemeralPubkey is a stale per-login key and the ping
// TTL has long expired.
const replayWindow = 8192

// replayGuard is a concurrency-safe, bounded set of seen request_ids with
// insertion-order (FIFO) eviction.
type replayGuard struct {
	mu    sync.Mutex
	seen  map[string]struct{}
	order []string
	max   int
}

func newReplayGuard(max int) *replayGuard {
	return &replayGuard{seen: make(map[string]struct{}), max: max}
}

// firstSee records id and reports whether this is the FIRST time it has been
// seen. A repeat returns false (the caller declines). An empty id is never
// deduped (the handler rejects it on other grounds).
func (g *replayGuard) firstSee(id string) bool {
	if id == "" {
		return true
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if _, ok := g.seen[id]; ok {
		return false
	}
	g.seen[id] = struct{}{}
	g.order = append(g.order, id)
	if len(g.order) > g.max {
		oldest := g.order[0]
		g.order = g.order[1:]
		delete(g.seen, oldest)
	}
	return true
}
