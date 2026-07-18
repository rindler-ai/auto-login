module github.com/rindler-ai/auto-login/daemon

go 1.26.0

// The daemon reuses the golden-vector-proven core crypto/protocol library
// in-repo; no separate publish.
replace github.com/rindler-ai/auto-login/core => ../core

require (
	github.com/coder/websocket v1.8.15
	github.com/hashicorp/yamux v0.1.2
	github.com/rindler-ai/auto-login/core v0.0.0-00010101000000-000000000000
)

require (
	github.com/emersion/go-imap v1.2.1 // indirect
	github.com/emersion/go-sasl v0.0.0-20200509203442-7bfe0ed36a21 // indirect
	golang.org/x/mobile v0.0.0-20260709172247-6129f5bee9d5 // indirect
	golang.org/x/mod v0.38.0 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/text v0.3.8 // indirect
	golang.org/x/tools v0.48.0 // indirect
)

tool golang.org/x/mobile/cmd/gobind
