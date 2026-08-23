# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName` in `app/build.gradle.kts`.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v8.6-universal.apk`, `versionCode 67`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / `minSdk 24`) — a genuinely universal build, so one file
  installs on every common Android device.
- APK SHA-256: `f8da60f3779b93d6443440220995c3e5ba2092952a2adf0b5007823780531b0b`
- Size: 60,054,374 bytes
- Signed with the same release key used since v6.7
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`,
  SHA-1 `df7d72072d0593cd2ed4554c95f283266eaec569`, DN `C=US, O=NeonVPN, CN=NeonVPN`),
  verified with `apksigner verify --print-certs` against the shipped v8.5 APK — so
  v8.6 installs **over** an existing Professor VPN without uninstalling first.
- `zipalign -c 4` verified; APK Signature Scheme v2 verified with `apksigner`.

## Only one APK lives here

When a new version ships, `build/*.apk` is cleared in the same commit before the
fresh APK is copied in, so this folder can never offer a stale download. The
previous `ProfessorVPN-v8.5-universal.apk` was removed in favour of v8.6.

## How it is built

`./gradlew :app:assembleRelease` with JDK 17 and `compileSdk`/`targetSdk` 34.

Signing secrets are read from the `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
`KEY_PASSWORD` environment variables when present, falling back to the bundled
dev key so the source contains no hardcoded secret while still preserving the
same release identity.

## What changed in v8.6

See [`RELEASE_NOTES_v8.6.md`](../RELEASE_NOTES_v8.6.md) for the full write-up.

v8.6 is a bug-fix release. Disconnect tears the session down and cancels the
notification, a ping is not shown unless payload was proven, the connect gate
requires a live device path, and the home connect button stays on screen.

| Change | How |
|---|---|
| **Disconnect actually stops** | Stop is sent as `ACTION_STOP`. A `START_STICKY` null-intent restart cannot resurrect the tunnel. Notification updates are ignored while stopping. A superseded connect generation tears down TUN/xray/tun2socks. |
| **Connect means a live path** | The connect gate only accepts `probeDevicePath`. A core-only delay is not treated as connected. |
| **Ping requires payload** | A failed throughput probe (`kbps < 0`) now returns unreachable instead of showing the latency. |
| **Home connect button stays visible** | The outer home `ScrollView` is gone so the terminal well can cap its height. Exit summaries are not re-appended on every resume. |
