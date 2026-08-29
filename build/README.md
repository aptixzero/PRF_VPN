# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName` in `app/build.gradle.kts`.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v9.0-universal.apk`, `versionCode 71`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / `minSdk 24`) — a genuinely universal build, so one file
  installs on every common Android device.
- APK SHA-256: `b5227e372b4d568c8d2c0d8fdeab63af6a8767d24c7e1d74f894e46773ad0b02`
- Size: 60,448,718 bytes
- Signed with the same release key used since v6.7
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`,
  SHA-1 `df7d72072d0593cd2ed4554c95f283266eaec569`, DN `C=US, O=NeonVPN, CN=NeonVPN`),
  verified with `apksigner verify --print-certs` against the shipped v8.8 APK — so
  v8.9 installs **over** an existing Professor VPN without uninstalling first.
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

## What changed in v9.0

See [`RELEASE_NOTES_v9.0.md`](../RELEASE_NOTES_v9.0.md) for the full write-up.

v9.0 is the STOP & Truth release: STOP really stops everything, the disconnect button always obeys, and green configs are always visible
(bounded ledgers, corpus, orphan rows, releasable pressure latch, periodic
housekeeping), manual pings are always fresh real measurements, the list keeps
its order while sweeping, verified connections auto-failover to the best
measured alternative, and light theme + banner display are fixed.

| Change | How |
|---|---|
| **No week-one slowdown** | Diag ledgers prune every 32nd flush; FREE corpus capped at 20k with config_state orphan cleanup; memory-pressure latch releases on recovery; 2-minute housekeeping heartbeat. |
| **Fresh pings** | Manual PING ALL forces `forceFresh`; hydration is re-runnable; sweep order frozen visually until completion. |
| **Self-healing connection** | Watchdog give-up now auto-fails over (max 3 hops) to the best measured-connectable config through the full connect gate. |
| **WiFi roaming survives** | Same-type network identity changes revive the tunnel, not just WiFi↔cellular. |
| **Full banner, legible light theme** | FIT_CENTER + 140dp banner; every low-contrast label routed through `?attr`. |
