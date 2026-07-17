package agent

import (
	"context"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/relay"
	"github.com/rindler-ai/auto-login/core/store"
)

// A fake hub: accept the ws, do the hello handshake, send one ping, and hand the
// received release back over a channel. Proves the daemon's real Run loop
// connects, handshakes, and answers a ping end to end.
func TestDaemon_EndToEndAgainstFakeHub(t *testing.T) {
	devPub, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	wpub, wopen := workerKey(t)
	// A genuine hub signs every ping with the key the device got at pairing.
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "req-daemon-1", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: wpub, Challenge: []byte("hub-nonce"), TTLSeconds: 30,
	})

	releaseCh := make(chan protocol.SecretRelease, 1)
	gotToken := make(chan string, 1)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		// read hello
		var hello wire
		if readWire(ctx, c, &hello) != nil || hello.Type != "hello" {
			return
		}
		gotToken <- hello.Token
		_ = writeWire(ctx, c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion})
		// dispatch one ping
		_ = writeWire(ctx, c, wire{Type: "ping", RequestID: ping.RequestID, Ping: &ping})
		// read the release
		var rel wire
		if readWire(ctx, c, &rel) == nil && rel.Type == "release" && rel.Release != nil {
			releaseCh <- *rel.Release
		}
	}))
	defer srv.Close()

	st := store.NewMemStore()
	_ = st.Put(store.Record{Site: "instacart.com", Username: "john", Password: "s3cr3t-relayed"})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	go func() {
		_ = Run(ctx, Config{
			HubURL:       "ws" + strings.TrimPrefix(srv.URL, "http"),
			DeviceToken:  "cd_dev_token_abc",
			DeviceKey:    devPriv,
			ServerPubkey: srvPub,
			Store:        st,
			Approve:      approveWithoutCode,
		})
	}()

	select {
	case tok := <-gotToken:
		if tok != "cd_dev_token_abc" {
			t.Fatalf("hub got token %q", tok)
		}
	case <-ctx.Done():
		t.Fatal("daemon never sent hello")
	}

	select {
	case rel := <-releaseCh:
		// the hub-side worker opens the sealed secret + verifies the signature
		got, err := wopen(protocol.SealInfo(ping.RequestID, ping.Site, ping.SecretKind), rel.SealedSecret)
		if err != nil {
			t.Fatalf("open relayed secret: %v", err)
		}
		if string(got) != "s3cr3t-relayed" {
			t.Fatalf("relayed %q, want s3cr3t-relayed", got)
		}
		if !relay.VerifyRelease(rel, ping, devPub) {
			t.Fatal("release signature did not verify against the device pubkey")
		}
	case <-ctx.Done():
		t.Fatal("daemon never answered the ping with a release")
	}
	cancel()
}

// A client with no approval surface stays connected but declines the request.
// This is the fail-closed default used by the headless desktop client and by any
// native binding that accidentally omits its approver.
func TestDaemon_NilApproverDeclines(t *testing.T) {
	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	wpub, _ := workerKey(t)
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "req-no-approver", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: wpub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})

	response := make(chan string, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		var hello wire
		if readWire(ctx, c, &hello) != nil || hello.Type != "hello" {
			return
		}
		_ = writeWire(ctx, c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion})
		_ = writeWire(ctx, c, wire{Type: "ping", RequestID: ping.RequestID, Ping: &ping})
		var got wire
		if readWire(ctx, c, &got) == nil {
			response <- got.Type
		}
	}))
	defer srv.Close()

	st := store.NewMemStore()
	_ = st.Put(store.Record{Site: "instacart.com", Password: "not-released"})
	tracked := &getCountingStore{Store: st}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	go func() {
		_ = Run(ctx, Config{
			HubURL: "ws" + strings.TrimPrefix(srv.URL, "http"), DeviceToken: "cd_dev_no_approval",
			DeviceKey: devPriv, ServerPubkey: srvPub, Store: tracked,
		})
	}()

	select {
	case got := <-response:
		if got != "declined" {
			t.Fatalf("response type = %q, want declined", got)
		}
		if tracked.gets.Load() != 0 {
			t.Fatalf("credential store read %d times without approval, want 0", tracked.gets.Load())
		}
	case <-ctx.Done():
		t.Fatal("daemon did not decline a ping without an approver")
	}
}

// The daemon answers a non-secret site-inventory query with exactly the domains
// its store holds, echoing the request_id. Proves the real Run loop dispatches an
// `inventory` frame to the handler and emits a well-formed `inventory_reply`.
//
// Deliberately configured with NO ServerPubkey: the ping gate is a
// SECRET-release gate. Inventory carries no credential and needs no signature, so
// a device that cannot serve pings must still answer it.
func TestDaemon_AnswersInventoryQuery(t *testing.T) {
	_, devPriv := devicePair(t)
	const reqID = "inv-req-1"

	replyCh := make(chan protocol.SiteInventoryReply, 1)
	gotType := make(chan string, 1)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		var hello wire
		if readWire(ctx, c, &hello) != nil || hello.Type != "hello" {
			return
		}
		_ = writeWire(ctx, c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion})
		// Ask which sites the device holds; request_id set BOTH top-level + nested.
		_ = writeWire(ctx, c, wire{
			Type:      "inventory",
			RequestID: reqID,
			Inventory: &protocol.SiteInventoryRequest{RequestID: reqID},
		})
		var resp wire
		if readWire(ctx, c, &resp) == nil {
			gotType <- resp.Type
			if resp.InventoryReply != nil {
				replyCh <- *resp.InventoryReply
			}
		}
	}))
	defer srv.Close()

	st := store.NewMemStore()
	_ = st.Put(store.Record{Site: "bankofamerica.com", Password: "p1"})
	_ = st.Put(store.Record{Site: "instacart.com", Password: "p2"})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	go func() {
		_ = Run(ctx, Config{
			HubURL:      "ws" + strings.TrimPrefix(srv.URL, "http"),
			DeviceToken: "cd_dev_inv",
			DeviceKey:   devPriv,
			Store:       st,
		})
	}()

	select {
	case reply := <-replyCh:
		if reply.RequestID != reqID {
			t.Fatalf("reply request_id = %q, want %q", reply.RequestID, reqID)
		}
		got := append([]string(nil), reply.Domains...)
		sort.Strings(got)
		if want := []string{"bankofamerica.com", "instacart.com"}; !reflect.DeepEqual(got, want) {
			t.Fatalf("inventory domains = %v, want %v", got, want)
		}
	case <-ctx.Done():
		ty := ""
		select {
		case ty = <-gotType:
		default:
		}
		t.Fatalf("daemon never answered the inventory query (last reply type=%q)", ty)
	}
	cancel()
}

// The heartbeat notices a connection that has gone silently dead — the socket is
// still "open" but the peer stopped answering (exactly the backgrounded-phone
// case: the OS parks the radio, pings go unanswered, Read would otherwise block
// forever) — and forces Run to redial. The fake hub completes the hello on the
// first connection then stops reading (so it never pongs the client's control
// pings); the test asserts a SECOND connection arrives, which only happens if the
// heartbeat detected the dead link. Without the heartbeat this test times out.
func TestDaemon_HeartbeatReconnectsDeadLink(t *testing.T) {
	// Shrink the heartbeat so the test runs in milliseconds; restored after Run stops.
	origI, origT := pingInterval, pingTimeout
	pingInterval, pingTimeout = 20*time.Millisecond, 40*time.Millisecond
	defer func() { pingInterval, pingTimeout = origI, origT }()

	_, devPriv := devicePair(t)
	conns := make(chan int, 4)
	serverStop := make(chan struct{})
	var n int32

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		which := int(atomic.AddInt32(&n, 1))
		var hello wire
		if readWire(r.Context(), c, &hello) != nil || hello.Type != "hello" {
			return
		}
		if writeWire(r.Context(), c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion}) != nil {
			return
		}
		conns <- which
		if which == 1 {
			// Go silent: never Read again, so control pings are never ponged. The
			// client heartbeat must time out and redial. Hold the socket open until
			// the test releases it, so httptest.Server.Close doesn't race the handler.
			select {
			case <-serverStop:
			case <-r.Context().Done():
			}
			return
		}
		// Later connections stay healthy: keep reading so pings are auto-ponged.
		var msg wire
		for readWire(r.Context(), c, &msg) == nil {
		}
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	done := make(chan struct{})
	go func() {
		_ = Run(ctx, Config{HubURL: "ws" + strings.TrimPrefix(srv.URL, "http"),
			DeviceToken: "cd_dev_hb", DeviceKey: devPriv, Store: store.NewMemStore()})
		close(done)
	}()

	select {
	case c1 := <-conns:
		if c1 != 1 {
			t.Fatalf("first connection numbered %d, want 1", c1)
		}
	case <-ctx.Done():
		t.Fatal("daemon never made its first connection")
	}
	select {
	case c2 := <-conns:
		if c2 != 2 {
			t.Fatalf("reconnect numbered %d, want 2", c2)
		}
	case <-ctx.Done():
		t.Fatal("heartbeat did not detect the dead link and reconnect")
	}

	cancel()
	<-done            // Run (and its heartbeat goroutines) fully stopped...
	close(serverStop) // ...so releasing the hung handler can't race the swap restore
}

// A human approval can legitimately outlast multiple heartbeat periods. The
// websocket reader must remain active throughout so Ping sees its pong and does
// not cancel the healthy connection before approval completes.
func TestDaemon_HeartbeatSurvivesDelayedApproval(t *testing.T) {
	origI, origT := pingInterval, pingTimeout
	pingInterval, pingTimeout = 10*time.Millisecond, 20*time.Millisecond

	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	wpub, _ := workerKey(t)
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "req-slow-approval", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: wpub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})
	response := make(chan string, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		var hello wire
		if readWire(ctx, c, &hello) != nil || hello.Type != "hello" {
			return
		}
		_ = writeWire(ctx, c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion})
		_ = writeWire(ctx, c, wire{Type: "ping", RequestID: ping.RequestID, Ping: &ping})
		var got wire
		if readWire(ctx, c, &got) == nil {
			response <- got.Type
		}
	}))
	defer srv.Close()

	st := store.NewMemStore()
	_ = st.Put(store.Record{Site: "instacart.com", Password: "pw"})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = Run(ctx, Config{
			HubURL: "ws" + strings.TrimPrefix(srv.URL, "http"), DeviceToken: "cd_dev_slow",
			DeviceKey: devPriv, ServerPubkey: srvPub, Store: st,
			Approve: func(context.Context, protocol.SecretPing) (string, bool) {
				time.Sleep(100 * time.Millisecond)
				return "", true
			},
		})
	}()
	defer func() {
		cancel()
		<-done
		pingInterval, pingTimeout = origI, origT
	}()

	select {
	case got := <-response:
		if got != "release" {
			t.Fatalf("response type = %q, want release", got)
		}
	case <-ctx.Done():
		t.Fatal("healthy connection was lost during delayed approval")
	}
}

// Human approval is serialized with one queued follow-up. Once both slots are
// occupied, another ping is declined immediately instead of creating an
// unbounded prompt backlog or blocking the websocket read loop.
func TestDaemon_ApprovalQueueDeclinesWhenFull(t *testing.T) {
	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	wpub, _ := workerKey(t)
	base := protocol.SecretPing{
		RequestID: "req-approval-active", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: wpub, Challenge: []byte("nonce"), TTLSeconds: 30,
	}
	// request_id is signed over, so each variant is signed after its id is set —
	// copying a signed ping and editing the id would produce three forgeries.
	firstPing := signPing(t, srvPriv, base)
	queued := base
	queued.RequestID = "req-approval-queued"
	queuedPing := signPing(t, srvPriv, queued)
	overflow := base
	overflow.RequestID = "req-approval-overflow"
	overflowPing := signPing(t, srvPriv, overflow)

	approvalStarted := make(chan struct{})
	unblockApproval := make(chan struct{})
	firstResponse := make(chan wire, 1)
	remainingResponses := make(chan []wire, 1)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		var hello wire
		if readWire(ctx, c, &hello) != nil || hello.Type != "hello" {
			return
		}
		if writeWire(ctx, c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion}) != nil ||
			writeWire(ctx, c, wire{Type: "ping", RequestID: firstPing.RequestID, Ping: &firstPing}) != nil {
			return
		}
		select {
		case <-approvalStarted:
		case <-ctx.Done():
			return
		}
		if writeWire(ctx, c, wire{Type: "ping", RequestID: queuedPing.RequestID, Ping: &queuedPing}) != nil ||
			writeWire(ctx, c, wire{Type: "ping", RequestID: overflowPing.RequestID, Ping: &overflowPing}) != nil {
			return
		}

		var response wire
		if readWire(ctx, c, &response) != nil {
			return
		}
		firstResponse <- response
		responses := make([]wire, 0, 2)
		for range 2 {
			if readWire(ctx, c, &response) != nil {
				return
			}
			responses = append(responses, response)
		}
		remainingResponses <- responses
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var approvals atomic.Int32
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = Run(ctx, Config{
			HubURL: "ws" + strings.TrimPrefix(srv.URL, "http"), DeviceToken: "cd_dev_queue",
			DeviceKey: devPriv, ServerPubkey: srvPub, Store: store.NewMemStore(),
			Approve: func(approvalCtx context.Context, _ protocol.SecretPing) (string, bool) {
				if approvals.Add(1) == 1 {
					close(approvalStarted)
					select {
					case <-unblockApproval:
					case <-approvalCtx.Done():
					}
				}
				return "", false
			},
		})
	}()

	select {
	case response := <-firstResponse:
		if response.Type != "declined" || response.RequestID != overflowPing.RequestID {
			t.Fatalf("first response = (%q, %q), want overflow declined", response.Type, response.RequestID)
		}
		if got := approvals.Load(); got != 1 {
			t.Fatalf("approval calls before unblock = %d, want active request only", got)
		}
	case <-ctx.Done():
		t.Fatal("full approval queue did not decline the overflow ping")
	}

	close(unblockApproval)
	select {
	case responses := <-remainingResponses:
		got := map[string]string{}
		for _, response := range responses {
			got[response.RequestID] = response.Type
		}
		if got[firstPing.RequestID] != "declined" || got[queuedPing.RequestID] != "declined" {
			t.Fatalf("drained responses = %v, want active and queued requests declined", got)
		}
		if calls := approvals.Load(); calls != 2 {
			t.Fatalf("approval calls after drain = %d, want active plus queued", calls)
		}
	case <-ctx.Done():
		t.Fatal("queued approval did not run after the active request completed")
	}
	cancel()
	<-done
}

// A replayed ping (same request_id sent twice) is released exactly once; the
// second is declined. Proves the daemon-lifetime replay guard is wired into the
// real serve loop, not just unit-tested in isolation.
func TestDaemon_RefusesReplayedPing(t *testing.T) {
	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	wpub, _ := workerKey(t)
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "req-replay-1", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: wpub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})

	types := make(chan string, 2)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		var hello wire
		if readWire(ctx, c, &hello) != nil || hello.Type != "hello" {
			return
		}
		_ = writeWire(ctx, c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion})
		// Same request_id dispatched twice.
		for i := 0; i < 2; i++ {
			_ = writeWire(ctx, c, wire{Type: "ping", RequestID: ping.RequestID, Ping: &ping})
			var resp wire
			if readWire(ctx, c, &resp) == nil {
				types <- resp.Type
			}
		}
	}))
	defer srv.Close()

	st := store.NewMemStore()
	_ = st.Put(store.Record{Site: "instacart.com", Password: "s3cr3t"})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	go func() {
		_ = Run(ctx, Config{HubURL: "ws" + strings.TrimPrefix(srv.URL, "http"),
			DeviceToken: "cd_dev_x", DeviceKey: devPriv, ServerPubkey: srvPub, Store: st,
			Approve: approveWithoutCode})
	}()

	got := map[string]int{}
	for i := 0; i < 2; i++ {
		select {
		case ty := <-types:
			got[ty]++
		case <-ctx.Done():
			t.Fatalf("only got %d responses: %v", i, got)
		}
	}
	cancel()
	if got["release"] != 1 || got["declined"] != 1 {
		t.Fatalf("want exactly one release + one declined, got %v", got)
	}
}

// THE ATTACK, end to end through the real Run loop. A hop between the
// server and the device rewrites `worker_ephemeral_pubkey` to its own X25519 key,
// leaving the server's signature (which covers that field) in place. The device
// must:
//
//  1. decline — never seal the credential to the substituted key;
//  2. NOT prompt the human. The approval UI shows only site + secret_kind, never
//     the worker key, so a user CANNOT catch the swap; asking them to approve a
//     ping we already know is forged only trains them to tap through. This is the
//     assertion that a "seal first, verify later" or "verify after approval"
//     refactor would break, and it is the one that matters most.
//  3. never read the credential store.
func TestDaemon_DeclinesSubstitutedWorkerKeyWithoutPrompting(t *testing.T) {
	_, devPriv := devicePair(t)
	srvPub, srvPriv := serverKey(t)
	honestPub, _ := workerKey(t)
	attackerPub, attackerOpen := workerKey(t)

	// The server signs a ping for the HONEST worker key…
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "req-mitm", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: honestPub, Challenge: []byte("hub-nonce"), TTLSeconds: 30,
	})
	// …and the on-path attacker swaps in its own before the device sees it.
	ping.WorkerEphemeralPubkey = attackerPub

	responses := make(chan wire, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		var hello wire
		if readWire(ctx, c, &hello) != nil || hello.Type != "hello" {
			return
		}
		_ = writeWire(ctx, c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion})
		_ = writeWire(ctx, c, wire{Type: "ping", RequestID: ping.RequestID, Ping: &ping})
		var got wire
		if readWire(ctx, c, &got) == nil {
			responses <- got
		}
	}))
	defer srv.Close()

	st := store.NewMemStore()
	_ = st.Put(store.Record{Site: "instacart.com", Username: "john", Password: "never-relay-this"})
	tracked := &getCountingStore{Store: st}

	var approvals atomic.Int32
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	go func() {
		_ = Run(ctx, Config{
			HubURL: "ws" + strings.TrimPrefix(srv.URL, "http"), DeviceToken: "cd_dev_mitm",
			DeviceKey: devPriv, ServerPubkey: srvPub, Store: tracked,
			Approve: func(context.Context, protocol.SecretPing) (string, bool) {
				approvals.Add(1)
				return "", true // a user who would have approved: the gate must not reach them
			},
		})
	}()

	select {
	case got := <-responses:
		if got.Type != "declined" {
			t.Fatalf("response type = %q, want declined", got.Type)
		}
		if got.Release != nil {
			t.Fatal("a release was sent for a ping with a substituted worker key")
		}
		if n := approvals.Load(); n != 0 {
			t.Fatalf("the human approver was prompted %d times for a ping we already knew was forged, want 0", n)
		}
		if n := tracked.gets.Load(); n != 0 {
			t.Fatalf("credential store read %d times for a forged ping, want 0", n)
		}
		// Nothing was sealed, so the attacker's key has nothing to open.
		if _, err := attackerOpen(protocol.SealInfo(ping.RequestID, ping.Site, ping.SecretKind), nil); err == nil {
			t.Fatal("attacker opened a payload — a credential was sealed to the substituted key")
		}
	case <-ctx.Done():
		t.Fatal("daemon never answered the substituted-key ping")
	}
	cancel()
}

// A device holding NO server pubkey (paired before this change, or against a server with
// no signing key) declines every ping — including a validly-signed one it simply
// cannot verify — and never prompts the user. There is no unsigned fallback: such
// a device must re-pair. Softening this back to "allow unsigned" is precisely the
// vulnerability, so this test exists to fail loudly if anyone tries.
func TestDaemon_NoServerPubkeyDeclinesEveryPing(t *testing.T) {
	_, devPriv := devicePair(t)
	_, srvPriv := serverKey(t) // a REAL, correctly-signed ping…
	wpub, _ := workerKey(t)
	ping := signPing(t, srvPriv, protocol.SecretPing{
		RequestID: "req-unpaired", Site: "instacart.com", SecretKind: protocol.SecretPassword,
		WorkerEphemeralPubkey: wpub, Challenge: []byte("nonce"), TTLSeconds: 30,
	})

	responses := make(chan wire, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		var hello wire
		if readWire(ctx, c, &hello) != nil || hello.Type != "hello" {
			return
		}
		_ = writeWire(ctx, c, wire{Type: "hello_ok", DeviceID: "dev-x", V: protocol.ProtocolVersion})
		_ = writeWire(ctx, c, wire{Type: "ping", RequestID: ping.RequestID, Ping: &ping})
		var got wire
		if readWire(ctx, c, &got) == nil {
			responses <- got
		}
	}))
	defer srv.Close()

	st := store.NewMemStore()
	_ = st.Put(store.Record{Site: "instacart.com", Password: "never-relay-this"})
	tracked := &getCountingStore{Store: st}

	var approvals atomic.Int32
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	go func() {
		_ = Run(ctx, Config{
			HubURL: "ws" + strings.TrimPrefix(srv.URL, "http"), DeviceToken: "cd_dev_unpaired",
			DeviceKey: devPriv, // …but NO ServerPubkey: this device can verify nothing.
			Store:     tracked,
			Approve: func(context.Context, protocol.SecretPing) (string, bool) {
				approvals.Add(1)
				return "", true
			},
		})
	}()

	select {
	case got := <-responses:
		if got.Type != "declined" || got.Release != nil {
			t.Fatalf("response = %q (release=%v), want declined with no release", got.Type, got.Release != nil)
		}
		if n := approvals.Load(); n != 0 {
			t.Fatalf("a device with no server key prompted the user %d times, want 0", n)
		}
		if n := tracked.gets.Load(); n != 0 {
			t.Fatalf("a device with no server key read the credential store %d times, want 0", n)
		}
	case <-ctx.Done():
		t.Fatal("a device with no server pubkey did not decline the ping")
	}
	cancel()
}

func approveWithoutCode(context.Context, protocol.SecretPing) (string, bool) {
	return "", true
}

type getCountingStore struct {
	store.Store
	gets atomic.Int32
}

func (s *getCountingStore) Get(site string) (store.Record, error) {
	s.gets.Add(1)
	return s.Store.Get(site)
}
