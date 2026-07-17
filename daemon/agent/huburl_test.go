package agent

import "testing"

// The relay channel carries the device token + every sealed secret, so it must be
// wss:// — ws:// is allowed only for a loopback dev hub, never a remote one.
func TestValidateHubURL(t *testing.T) {
	ok := []string{
		"wss://your-hub.example/v1/devices/connect",
		"wss://your-hub.example/v1/devices/connect",
		"ws://localhost:8080/v1/devices/connect",
		"ws://127.0.0.1:8080/v1/devices/connect",
	}
	for _, u := range ok {
		if err := ValidateHubURL(u); err != nil {
			t.Errorf("ValidateHubURL(%q) = %v, want nil", u, err)
		}
	}
	bad := []string{
		"ws://your-hub.example/v1/devices/connect", // cleartext to a real hub — the leak
		"ws://evil.example.com/connect",
		"http://your-hub.example/connect", // wrong scheme entirely
		"https://your-hub.example/connect",
		"://nonsense",
	}
	for _, u := range bad {
		if err := ValidateHubURL(u); err == nil {
			t.Errorf("ValidateHubURL(%q) = nil, want an error (insecure/invalid)", u)
		}
	}
}
