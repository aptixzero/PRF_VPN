# build/

This folder holds the **latest signed universal APK** for Professor VPN.
Only the newest `ProfessorVPN-v<version>-universal.apk` is kept; older APKs
are removed on each rebuild.

## Current artifact

- **`ProfessorVPN-v4.1-universal.apk`** — version 4.1 (versionCode 22)
- Universal: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- Signed with the release key (`CN=NeonVPN`); v1+v2+v3 signature schemes.
- v4.1 hardening: global crash handler now performs a clean auto-restart on a
  main-thread crash (no more "keeps stopping" — the app reopens itself),
  fully-guarded Splash boot + MainActivity wiring, and a crash-safe native
  tun2socks library load.
- Free configs are fetched from the new `aptixzero/con_new` feed.

Install on a device/emulator with:

```bash
adb install -r ProfessorVPN-v4.1-universal.apk
```
