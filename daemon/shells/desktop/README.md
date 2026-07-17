# Desktop shell (background daemon)

The desktop path has **no separate shell** — it runs the `custody-daemon` binary
(the same shared Go core, headless) as a background service. Build it on any OS
with no extra toolchain:

```sh
cd daemon
make desktop     # -> ./custody-daemon  (any OS, no NDK/Xcode)
```

First run pairs the device and persists identity to the OS keychain (macOS
`security`, Linux `secret-tool`/libsecret); after that it starts silently:

```sh
RINDLER_PAIRING_CODE=<code from your hub → Settings → Devices> ./custody-daemon
```

The headless binary has no approval surface, so it **declines every secret
request**. It may pair, stay connected, and answer non-secret inventory queries,
but cannot release a credential until an explicit desktop approval UI exists.
Use the macOS menu-bar app (`../macos`), which auto-approves releases hands-free;
Windows/Linux release support remains blocked on the tray UI below.

## Run as an inventory-only service

### Linux — systemd user unit

`~/.config/systemd/user/custody-daemon.service`:

```ini
[Unit]
Description=Auto-Login credential custody daemon
After=network-online.target
Wants=network-online.target

[Service]
# Pair once interactively first so the token+key are in the keychain; then this
# starts with no secrets in the unit. (secret-tool/libsecret must be available.)
ExecStart=%h/.local/bin/custody-daemon
Environment=RINDLER_HUB_URL=wss://your-hub.example/v1/devices/connect
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

```sh
install -Dm755 custody-daemon ~/.local/bin/custody-daemon
systemctl --user daemon-reload
systemctl --user enable --now custody-daemon
loginctl enable-linger "$USER"   # keep it running when logged out
journalctl --user -u custody-daemon -f
```

### macOS — launchd LaunchAgent

`~/Library/LaunchAgents/ai.rindler.autologin.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>            <string>ai.rindler.autologin</string>
  <key>ProgramArguments</key> <array><string>/usr/local/bin/custody-daemon</string></array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>RINDLER_HUB_URL</key><string>wss://your-hub.example/v1/devices/connect</string>
  </dict>
  <key>RunAtLoad</key>        <true/>
  <key>KeepAlive</key>        <true/>
  <key>StandardErrorPath</key><string>/tmp/custody-daemon.log</string>
</dict>
</plist>
```

```sh
sudo install -m755 custody-daemon /usr/local/bin/custody-daemon
launchctl load ~/Library/LaunchAgents/ai.rindler.autologin.plist
launchctl list | grep ai.rindler.autologin
```

Pair once in a terminal (`RINDLER_PAIRING_CODE=… custody-daemon`) before loading
the agent, so the identity is in the login Keychain and the service needs no
secrets. Unload with `launchctl unload …`.

## Future: tray UI

A cross-platform tray/menu app (Wails or Fyne) would wrap this **same core** and
add a desktop tap-to-approve, matching the mobile shells. Until such a UI exists,
secret release remains disabled in the headless daemon.
