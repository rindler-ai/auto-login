package agent

import (
	"context"
	"errors"
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

// ErrAlreadyUnlinked reports that this device's token is not one the server will
// authenticate, so there is nothing left to unlink — the device is already off
// the account. Callers should finish their sign-out (wipe local state) rather
// than surface a failure: the state the user asked for is the state they have.
var ErrAlreadyUnlinked = errors.New("custody: device is already unlinked")

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

	// 2xx = unlinked (204 on success today).
	//
	// 401/404 = the server will not authenticate this token, so it cannot unlink
	// anything — and never will, which makes a retry pointless. The ordinary way
	// to reach this is that the device is ALREADY unlinked: revoked from the web,
	// or swept for 30-day inactivity. Treating it as a transport failure told the
	// user the phone "is still linked" and sent them looking for a device row that
	// no longer exists. It is reported as [ErrAlreadyUnlinked] so the caller can
	// finish the sign-out instead, and matched with errors.Is rather than on the
	// message (which crosses gomobile as a bare string).
	//
	// Everything else stays a hard failure. That asymmetry is deliberate: calling a
	// live device "already unlinked" would wipe the phone locally while leaving a
	// working device row on the account, with no affordance left to remove it.
	// The message never echoes the token.
	switch {
	case resp.StatusCode >= 200 && resp.StatusCode <= 299:
		return nil
	case resp.StatusCode == http.StatusUnauthorized, resp.StatusCode == http.StatusNotFound:
		return fmt.Errorf("custody: revoke-self returned %s: %w", resp.Status, ErrAlreadyUnlinked)
	default:
		return fmt.Errorf("custody: revoke-self returned %s", resp.Status)
	}
}
