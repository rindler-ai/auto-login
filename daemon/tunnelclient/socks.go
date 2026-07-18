package tunnelclient

import (
	"bufio"
	"context"
	"io"
	"log/slog"
	"net"
	"strings"
)

// HandleStream reads "CONNECT host:port\n" then pipes bytes between the yamux
// stream and a TCP connection to that target. DNS resolves locally so the
// exit IP is this machine's residential IP.
//
// The dialer enforces an egress deny-list (RFC1918, loopback, link-local,
// multicast) unless allow_private_egress is set in the daemon config. A
// blocked target closes the stream with a logged reason.
func HandleStream(ctx context.Context, stream net.Conn, dialer *checkedDialer) {
	defer recoverStream() // CRASH-5: outermost defer; recover before unwinding.
	// Closing here intentionally aborts the other copy direction once the
	// first one finishes (half-teardown); the close error is conventional
	// net.Conn teardown noise, not a data-loss signal.
	defer func() { _ = stream.Close() }()
	br := bufio.NewReader(stream)
	line, err := br.ReadString('\n')
	if err != nil {
		slog.Info("stream: read header", "err", err)
		return
	}
	line = strings.TrimSpace(line)
	if !strings.HasPrefix(line, "CONNECT ") {
		slog.Info("stream: bad header", "line", line)
		return
	}
	target := strings.TrimPrefix(line, "CONNECT ")

	upstream, err := dialer.Dial(ctx, target)
	if err != nil {
		slog.Info("stream: dial target", "target", target, "err", err)
		return
	}
	defer func() { _ = upstream.Close() }() // same teardown semantics as stream above

	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(upstream, br); done <- struct{}{} }()
	go func() { _, _ = io.Copy(stream, upstream); done <- struct{}{} }()
	<-done
}
