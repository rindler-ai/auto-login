package agent

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/store"
)

// isPermanentAuthRejection is the whole revoke-vs-offline decision: a *helloError
// (the hub refused this device's hello) is PERMANENT — stop the relay and sign the
// user out; anything else (a dial/read error, a cancelled context, a plain error) is
// TRANSIENT — keep retrying, so airplane mode NEVER signs the user out.
func TestIsPermanentAuthRejection(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"helloError with message", &helloError{"unauthorized"}, true},
		{"helloError empty message", &helloError{}, true},
		{"helloError wrapped", fmt.Errorf("connect: %w", &helloError{"device revoked"}), true},

		{"nil error", nil, false},
		{"plain errors.New", errors.New("boom"), false},
		{"wrapped dial error", fmt.Errorf("dial hub: %w", errors.New("connection refused")), false},
		{"context canceled", context.Canceled, false},
		{"context deadline exceeded", context.DeadlineExceeded, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isPermanentAuthRejection(tc.err); got != tc.want {
				t.Fatalf("isPermanentAuthRejection(%v) = %v, want %v", tc.err, got, tc.want)
			}
			// The exported boundary the mobile shell calls must agree with the pure fn.
			if got := IsPermanentAuthRejection(tc.err); got != tc.want {
				t.Fatalf("IsPermanentAuthRejection(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}

// Run must NOT hot-loop on a hub that rejects the hello: it stops after ONE
// connection and returns a permanent-auth-rejection error (ctx is NOT cancelled),
// so the shell can sign the user out. Before the fix Run reconnected on any error,
// so a revoked device looped forever and never returned. The fake hub counts
// connections; a loop would produce a second one (and Run would never return).
func TestRun_StopsOnHelloRejection(t *testing.T) {
	_, devPriv := devicePair(t)
	var conns int32

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer func() { _ = c.Close(websocket.StatusNormalClosure, "") }()
		atomic.AddInt32(&conns, 1)
		var hello wire
		if readWire(r.Context(), c, &hello) != nil || hello.Type != "hello" {
			return
		}
		// A revoked device: the hub answers the handshake but refuses it. agent.go
		// turns a non-empty hello error into a *helloError.
		_ = writeWire(r.Context(), c, wire{Type: "hello_ok", Error: "unauthorized", V: protocol.ProtocolVersion})
	}))
	defer srv.Close()

	// A generous ctx: if Run looped it would only return when this expires (returning
	// ctx.Err()), which the assertions below reject. On the fixed code Run returns
	// almost immediately with the helloError, well before the deadline.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	errCh := make(chan error, 1)
	go func() {
		errCh <- Run(ctx, Config{
			HubURL:      "ws" + strings.TrimPrefix(srv.URL, "http"),
			DeviceToken: "cd_dev_revoked",
			DeviceKey:   devPriv,
			Store:       store.NewMemStore(),
			Approve:     approveWithoutCode,
		})
	}()

	select {
	case err := <-errCh:
		if ctx.Err() != nil {
			t.Fatal("Run only returned because the context expired — it looped instead of stopping on the rejection")
		}
		if !IsPermanentAuthRejection(err) {
			t.Fatalf("Run returned %v, want a permanent-auth-rejection error", err)
		}
	case <-ctx.Done():
		t.Fatal("Run never returned — it hot-looped on the hello rejection")
	}

	// The relay must not have reconnected: exactly one hello attempt, no backoff loop.
	if n := atomic.LoadInt32(&conns); n != 1 {
		t.Fatalf("hub saw %d connections, want 1 (a revoked device must not reconnect)", n)
	}
}
