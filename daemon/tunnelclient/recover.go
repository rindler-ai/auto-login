package tunnelclient

import (
	"log/slog"
	"runtime/debug"
)

// recoverStream converts a panic while servicing one tunnel stream into a
// logged error instead of crashing the whole daemon process (CRASH-5). One bad
// stream should not take down an operator's residential daemon and every other
// in-flight stream with it. Call as `defer recoverStream()` at the top of the
// per-stream goroutine.
func recoverStream() {
	if rec := recover(); rec != nil {
		slog.Error("stream: recovered panic",
			"recover", rec,
			"stack", string(debug.Stack()),
		)
	}
}
