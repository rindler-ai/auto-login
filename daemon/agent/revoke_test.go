package agent

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The revoke URL rides the SAME host+port as the hub, with wss->https and
// ws->http, exactly like the pairing URL.
func TestRevokeURLFromHub(t *testing.T) {
	cases := []struct{ hub, want string }{
		{"wss://your-hub.example/v1/devices/connect", "https://your-hub.example/devices/revoke-self"},
		{"wss://hub2.example/v1/devices/connect", "https://hub2.example/devices/revoke-self"},
		{"ws://localhost:8080/v1/devices/connect", "http://localhost:8080/devices/revoke-self"},
		{"ws://127.0.0.1:8080/v1/devices/connect", "http://127.0.0.1:8080/devices/revoke-self"},
	}
	for _, tc := range cases {
		got, err := RevokeURLFromHub(tc.hub)
		if err != nil {
			t.Errorf("RevokeURLFromHub(%q): %v", tc.hub, err)
			continue
		}
		if got != tc.want {
			t.Errorf("RevokeURLFromHub(%q) = %q, want %q", tc.hub, got, tc.want)
		}
	}
	// Pairing and revoke derive from the same seam, so they always agree on host.
	pair, _ := PairURLFromHub("wss://h.example:9443/v1/devices/connect", "")
	revoke, _ := RevokeURLFromHub("wss://h.example:9443/v1/devices/connect")
	if !strings.HasPrefix(pair, "https://h.example:9443/") || !strings.HasPrefix(revoke, "https://h.example:9443/") {
		t.Errorf("pair=%q revoke=%q must share scheme+host", pair, revoke)
	}
}

// RevokeSelf sends ONLY the device token, as a bearer, to /devices/revoke-self —
// no device id anywhere in the request, which is what makes it structurally
// unable to unlink another device.
func TestRevokeSelf_SendsOnlyBearerToken(t *testing.T) {
	var gotPath, gotAuth, gotMethod string
	var gotBody []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotAuth, gotMethod = r.URL.Path, r.Header.Get("Authorization"), r.Method
		gotBody = make([]byte, 512)
		n, _ := r.Body.Read(gotBody)
		gotBody = gotBody[:n]
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	// httptest serves plain http on 127.0.0.1, so the loopback ws:// hub form is
	// what ValidateHubURL accepts here.
	hub := "ws://" + strings.TrimPrefix(srv.URL, "http://") + "/v1/devices/connect"
	if err := RevokeSelf(context.Background(), hub, "cd_dev_secret"); err != nil {
		t.Fatalf("RevokeSelf: %v", err)
	}
	if gotMethod != http.MethodPost {
		t.Errorf("method = %q, want POST", gotMethod)
	}
	if gotPath != "/devices/revoke-self" {
		t.Errorf("path = %q, want /devices/revoke-self", gotPath)
	}
	if gotAuth != "Bearer cd_dev_secret" {
		t.Errorf("Authorization = %q, want the device token as a bearer", gotAuth)
	}
	if len(gotBody) != 0 {
		t.Errorf("body = %q, want empty (no device id is ever sent)", gotBody)
	}
}

func TestRevokeSelf_Errors(t *testing.T) {
	// A token the server will not authenticate cannot unlink anything, so the
	// device is ALREADY unlinked as far as this token can ever tell — the common
	// causes are a revoke from the web and the 30-day inactivity sweep. Reporting
	// that as a transport failure sent the user hunting for a device row that no
	// longer exists, and the retry it invited could only 401 again. Signalled as a
	// sentinel so callers match on errors.Is, not on message text (the message
	// crosses gomobile as a bare string and would be brittle to compare).
	t.Run("a dead token means already unlinked", func(t *testing.T) {
		for _, code := range []int{http.StatusUnauthorized, http.StatusNotFound} {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(code)
			}))
			hub := "ws://" + strings.TrimPrefix(srv.URL, "http://") + "/v1/devices/connect"
			err := RevokeSelf(context.Background(), hub, "cd_dev_dead")
			srv.Close()
			if !errors.Is(err, ErrAlreadyUnlinked) {
				t.Errorf("status %d: want ErrAlreadyUnlinked, got %v", code, err)
			}
			if err != nil && strings.Contains(err.Error(), "cd_dev_dead") {
				t.Errorf("status %d: error echoes the device token: %v", code, err)
			}
		}
	})

	// The direction that actually costs the user something: treating a real
	// server-side failure as "already unlinked" wipes the phone locally while a
	// LIVE device row stays on the account, and the user has no way left to reach
	// it. A 5xx and a dead connection must both stay hard failures.
	t.Run("a real failure is never reported as already-unlinked", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusInternalServerError)
		}))
		hub := "ws://" + strings.TrimPrefix(srv.URL, "http://") + "/v1/devices/connect"
		err := RevokeSelf(context.Background(), hub, "cd_dev_live")
		srv.Close()
		if err == nil {
			t.Fatal("a 500 must be an error — this device may still be linked")
		}
		if errors.Is(err, ErrAlreadyUnlinked) {
			t.Errorf("a 500 must NOT be treated as already-unlinked: %v", err)
		}

		// Same for a hub that is not answering at all: srv is already closed.
		err = RevokeSelf(context.Background(), hub, "cd_dev_live")
		if err == nil {
			t.Fatal("an unreachable hub must be an error")
		}
		if errors.Is(err, ErrAlreadyUnlinked) {
			t.Errorf("an unreachable hub must NOT be treated as already-unlinked: %v", err)
		}
	})

	t.Run("2xx unlinks", func(t *testing.T) {
		for _, code := range []int{http.StatusNoContent, http.StatusOK} {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(code)
			}))
			hub := "ws://" + strings.TrimPrefix(srv.URL, "http://") + "/v1/devices/connect"
			err := RevokeSelf(context.Background(), hub, "cd_dev_ok")
			srv.Close()
			if err != nil {
				t.Errorf("status %d: want nil, got %v", code, err)
			}
		}
	})

	t.Run("empty token", func(t *testing.T) {
		if err := RevokeSelf(context.Background(), "wss://h/v1/devices/connect", ""); err == nil {
			t.Error("empty device token must error")
		}
	})

	// The request carries the long-lived device token, so a cleartext ws:// to a
	// REMOTE host is refused by the same rule that guards the relay channel.
	t.Run("cleartext ws:// to a remote host is refused", func(t *testing.T) {
		if err := RevokeSelf(context.Background(), "ws://your-hub.example/v1/devices/connect", "cd_dev_x"); err == nil {
			t.Error("plaintext ws:// to a remote hub must be refused")
		}
	})
}
