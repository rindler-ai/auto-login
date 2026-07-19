package mobile

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Unpair is the shell-facing half of "Sign out". Its contract is "ensure this
// device is off the account", which is why it differs from the Go core it wraps:
// agent.RevokeSelf keeps "already unlinked" distinguishable (it is a real
// outcome worth testing on), and Unpair collapses it to success because the
// shell cannot match a sentinel across gomobile — it receives only a string.
//
// The asymmetry is the point. Reporting an already-revoked device as a failure
// left the user with copy claiming the phone "is still linked" and a retry that
// could only fail the same way, and — since the in-app re-pair affordance is
// hidden for accounts on the default server — sign-out is the ONLY recovery
// path, so a wrong answer here strands the device.
func TestUnpair_AlreadyRevokedDeviceSignsOutCleanly(t *testing.T) {
	for _, code := range []int{http.StatusUnauthorized, http.StatusNotFound} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(code)
		}))
		hub := "ws://" + strings.TrimPrefix(srv.URL, "http://") + "/v1/devices/connect"
		err := Unpair(hub, "cd_dev_dead")
		srv.Close()
		if err != nil {
			t.Errorf("status %d: a device the server no longer knows must sign out cleanly, got %v", code, err)
		}
	}
}

// The direction that costs the user something: a live device wiped locally while
// its row stays on the account, with no affordance left to remove it.
func TestUnpair_RealFailureStillFails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	hub := "ws://" + strings.TrimPrefix(srv.URL, "http://") + "/v1/devices/connect"
	err := Unpair(hub, "cd_dev_live")
	srv.Close()
	if err == nil {
		t.Fatal("a 500 must stay an error — this device may still be linked")
	}

	// An unreachable hub is the same class: srv is closed above.
	if err := Unpair(hub, "cd_dev_live"); err == nil {
		t.Fatal("an unreachable hub must stay an error")
	}
}

func TestUnpair_SuccessAndPreconditions(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	hub := "ws://" + strings.TrimPrefix(srv.URL, "http://") + "/v1/devices/connect"
	if err := Unpair(hub, "cd_dev_ok"); err != nil {
		t.Errorf("204 must unlink cleanly, got %v", err)
	}
	// No token means nothing was ever linked to revoke; that is a caller bug, not
	// an "already unlinked" success, so it must not be silently swallowed.
	if err := Unpair(hub, ""); err == nil {
		t.Error("an empty device token must error")
	}
}
