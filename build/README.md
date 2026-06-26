# build/

This folder holds the **latest signed universal APK** for Professor VPN.
Only the newest `ProfessorVPN-v<version>-universal.apk` is kept; older APKs
are removed on each rebuild.

## Current artifact

- **`ProfessorVPN-v4.2-universal.apk`** — version 4.2 (versionCode 23)
- Universal: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (Android 7.0+).
- Signed with the release key (`CN=NeonVPN`); v1+v2+v3 signature schemes.
- v4.2 fixes:
  - **Real, accurate ping** against *filtered* targets (Cloudflare / Telegram /
    Instagram) instead of google.com — a green ping now means it truly connects.
  - **Auto-Test instant-add**: every config that pings is moved into My Configs
    immediately (no waiting for the list to finish), with a top-of-screen
    notification while Auto-Test is running.
  - **Crash-proof Auto-Test** with device-adaptive concurrency (gentle on weak
    phones) and full per-probe isolation.
  - **Never-disconnect stability**: resilient double-probed watchdog, in-place
    core revival, hold-tunnel-on-network-loss, tun2socks auto-restart.
  - **Global crash handler** swallows every background-thread crash; survives
    screen-off, app-switching and tab-switching.
- Free configs are fetched from the `aptixzero/con_new` feed.

Install on a device/emulator with:

```bash
adb install -r ProfessorVPN-v4.2-universal.apk
```
