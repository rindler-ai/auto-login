package tunnelclient

import (
	"errors"
	"strings"
	"testing"
)

func TestRejectErrorInvalidToken(t *testing.T) {
	err := rejectError(reply{Error: "invalid_token"})
	if !errors.Is(err, ErrRevoked) {
		t.Fatalf("rejectError invalid_token = %v, want ErrRevoked", err)
	}
	if errors.Is(err, ErrUpgradeRequired) {
		t.Fatalf("invalid_token must not match ErrUpgradeRequired")
	}
}

func TestRejectErrorUpgradeRequired(t *testing.T) {
	err := rejectError(reply{
		Error:            "upgrade_required",
		MinDaemonVersion: "0.5.0",
		AcceptedVersion:  1,
	})
	if !errors.Is(err, ErrUpgradeRequired) {
		t.Fatalf("rejectError upgrade_required = %v, want ErrUpgradeRequired", err)
	}
	if errors.Is(err, ErrRevoked) {
		t.Fatalf("upgrade_required must not match ErrRevoked")
	}
	var upgradeErr *UpgradeRequiredError
	if !errors.As(err, &upgradeErr) {
		t.Fatalf("upgrade_required should expose UpgradeRequiredError, got %T", err)
	}
	if upgradeErr.MinDaemonVersion != "0.5.0" || upgradeErr.AcceptedVersion != 1 {
		t.Fatalf("upgrade metadata = %+v", upgradeErr)
	}
	msg := err.Error()
	for _, want := range []string{"minimum daemon version 0.5.0", "accepted protocol v1"} {
		if !strings.Contains(msg, want) {
			t.Fatalf("upgrade error %q missing %q", msg, want)
		}
	}
}

func TestRejectErrorGeneric(t *testing.T) {
	err := rejectError(reply{Error: "maintenance"})
	if err == nil {
		t.Fatal("expected generic rejection error")
	}
	if errors.Is(err, ErrRevoked) || errors.Is(err, ErrUpgradeRequired) {
		t.Fatalf("generic error must not match terminal typed errors: %v", err)
	}
	if !strings.Contains(err.Error(), "maintenance") {
		t.Fatalf("generic error should include gateway reason, got %q", err.Error())
	}
}
