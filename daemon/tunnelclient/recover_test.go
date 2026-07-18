package tunnelclient

import "testing"

// TestRecoverStream_SwallowsPanic proves a panic while servicing one tunnel
// stream is recovered rather than crashing the operator's residential daemon
// (CRASH-5).
func TestRecoverStream_SwallowsPanic(t *testing.T) {
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("panic escaped recoverStream: %v", r)
		}
	}()

	func() {
		defer recoverStream()
		panic("bad stream state")
	}()
}
