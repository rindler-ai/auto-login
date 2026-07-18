package mobile

import "testing"

func TestStartEgress_RequiresGatewayAndToken(t *testing.T) {
	if _, err := StartEgress("", "tok", "dev"); err == nil {
		t.Error("empty gateway should error")
	}
	if _, err := StartEgress("wss://gw", "", "dev"); err == nil {
		t.Error("empty token should error")
	}
}

func TestStartEgress_StartsAndStops(t *testing.T) {
	// A bogus gateway means RunOnce fails fast and the loop backs off; we are only
	// asserting the session starts and Stop() cancels it without panicking.
	s, err := StartEgress("ws://127.0.0.1:1", "rt_live_test", "dev")
	if err != nil {
		t.Fatalf("StartEgress: %v", err)
	}
	s.Stop()
	s.Stop() // idempotent / safe on an already-stopped session
}

func TestEgressSession_NilStopIsSafe(t *testing.T) {
	var s *EgressSession
	s.Stop() // must not panic on a nil receiver
}
