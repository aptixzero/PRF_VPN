# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName` in `app/build.gradle.kts`.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v8.4-universal.apk`, `versionCode 65`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / `minSdk 24`) — a genuinely universal build, so one file
  installs on every common Android device.
- APK SHA-256: `19c02dd9054f2e91b4c727f7fb3be84c7e4fa88109576f9e29094466ac727a53`
- Signed with the same release key used since v6.7
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`),
  verified byte-for-byte identical to the key on the v8.3 APK — so v8.4 installs
  **over** an existing Professor VPN without uninstalling first.
- `zipalign -c 4` verified; APK Signature Scheme v2 verified with `apksigner`.

## Only one APK lives here

When a new version ships, `build/*.apk` is cleared in the same commit before the
fresh APK is copied in, so this folder can never offer a stale download. The
previous `ProfessorVPN-v8.3-universal.apk` was removed in favour of v8.4.

## How it is built

`./gradlew :app:assembleRelease` with JDK 17 and `compileSdk`/`targetSdk` 34.

Signing secrets are read from the `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
`KEY_PASSWORD` environment variables when present, falling back to the bundled
dev key so the source contains no hardcoded secret while still preserving the
same release identity.

## What changed in v8.4

See [`RELEASE_NOTES_v8.4.md`](../RELEASE_NOTES_v8.4.md) for the full write-up.

v8.4 makes the network tuning **measured rather than assumed**, adds ten named
connection modes, makes the auto-search survive being backgrounded/swiped/killed,
and reaches working configs faster by spending the cheap probe budget first.

| Change | How |
|---|---|
| **Ten connection modes** | `Auto/Master/King/Turbo/Gaming/Stream/Stealth/Ghost/Lite/Shield`, each a preset bundle over primitives the bundled core is **already verified to have** (guide §9a/§9d). No new Xray wire key is invented — that was the v7.3 failure mode. |
| **Shaping is measured, not guessed** | `DpiProbe` writes a real TLS ClientHello unsplit and split-across-writes; fragmentation is enabled only when the split one demonstrably succeeds where the unsplit one fails. Reality/XTLS-Vision still forced `CLEAN`. |
| **Ping path == connect path, structurally** | Both take the same immutable `TransportPlan`, so a new tuning knob cannot apply to only one of them. |
| **New verified keys** | `tcpMptcp` (survives Wi-Fi↔mobile handover), `tcpWindowClamp` (bounds the in-flight buffer on a lossy link), per-mode keep-alive/user-timeout, and §9d freedom padding noise behind a runtime capability probe. |
| **Automatic settings stop fighting the settings page** | Automatic decisions moved to a `net_auto_*` namespace; manual picks became sticky per-control overrides, so both "it is automatic" and "the page is editable" are true. |
| **Faster working-config discovery** | Port prefilter using measured reachable ports, a 64-wide cheap TCP wave across the whole batch, then the expensive native-core wave ordered by measured evidence. |
| **Durable auto-search** | Swiping the app away no longer stops the sweep; `START_STICKY` resume, self-restart, a `BootReceiver` re-arm and an unbounded offline wait keep it going. Only the user's STOP ends it — and the notification now carries a STOP action. |
| **Crash handling** | `UiCrashGuard` adds call-site, fragment-lifecycle and activity-phase guards; tab switching fixed at source with `commitAllowingStateLoss` + an `isStateSaved` guard. |
