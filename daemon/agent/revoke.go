package agent

import (
	"context"
	"fmt"
	"net/http"
	"time"
)

// RevokeURLFromHub derives the self-revoke endpoint from the hub websocket URL:
// wss://host/v1/devices/connect -> https://host/devices/revoke-self. Same
// host+port, same ws->http / wss->https mapping as PairURLFromHub (both go
// through endpointFromHub), so a device always unlinks from the SAME server it
// paired with and talks to.
func RevokeURLFromHub(hubURL string) (string, error) {
	return endpointFromHub(hubURL, "/devices/revoke-self")
}

// RevokeSelf unlinks THIS device from the user's account by calling the server's
// device-authed POST /devices/revoke-self with its own bearer token. It is the
// network half of the app's "Sign out": after it returns nil, the device row is
// revoked, its hub socket is dropped server-side, and the token is dead.
//
// The device token is the ONLY identity sent — the request carries no device id,
// so it can never unlink anything but itself (see devicereg.RevokeSelf).
//
// The hub URL is validated first: this request carries the long-lived device
// token in an Authorization header, so it must not cross a cleartext socket to a
// remote host, exactly as ValidateHubURL requires of the relay channel itself.
func RevokeSelf(ctx context.Context, hubURL, deviceToken string) error {
	if deviceToken == "" {
		return fmt.Errorf("custody: no device token to revoke")
	}
	if err := ValidateHubURL(hubURL); err != nil {
		return err
	}
	revokeURL, err := RevokeURLFromHub(hubURL)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, revokeURL, nil)
	if err != nil {
		return fmt.Errorf("custody: build revoke request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+deviceToken)

	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return fmt.Errorf("custody: POST %s: %w", revokeURL, err)
	}
	defer func() { _ = resp.Body.Close() }()

	// 2xx = unlinked (204 on success today). Anything else is a real failure the
	// caller must surface rather than swallow: a 401 means the token did not
	// authenticate, so this call unlinked NOTHING and the shell must not report a
	// successful sign-out on the server side. The message never echoes the token.
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("custody: revoke-self returned %s", resp.Status)
	}
	return nil
}
