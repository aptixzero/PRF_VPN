# Build output

The signed **universal** release APK for Professor VPN lives here — exactly one
APK, always the binary for the current `versionName` in `app/build.gradle.kts`.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v8.5-universal.apk`, `versionCode 66`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / `minSdk 24`) — a genuinely universal build, so one file
  installs on every common Android device.
- APK SHA-256: `ff14a82062f144ab3e4af634f6d028e1484dfae5d74758dbf4599a2aa3e75790`
- Size: 60,054,446 bytes
- Signed with the same release key used since v6.7
  (certificate SHA-256 `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`,
  SHA-1 `df7d72072d0593cd2ed4554c95f283266eaec569`, DN `C=US, O=NeonVPN, CN=NeonVPN`),
  verified with `apksigner verify --print-certs` against the shipped v8.4 APK — so
  v8.5 installs **over** an existing Professor VPN without uninstalling first.
- `zipalign -c 4` verified; APK Signature Scheme v2 verified with `apksigner`.

## Only one APK lives here

When a new version ships, `build/*.apk` is cleared in the same commit before the
fresh APK is copied in, so this folder can never offer a stale download. The
previous `ProfessorVPN-v8.4-universal.apk` was removed in favour of v8.5.

## How it is built

`./gradlew :app:assembleRelease` with JDK 17 and `compileSdk`/`targetSdk` 34.

Signing secrets are read from the `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
`KEY_PASSWORD` environment variables when present, falling back to the bundled
dev key so the source contains no hardcoded secret while still preserving the
same release identity.

## What changed in v8.5

See [`RELEASE_NOTES_v8.5.md`](../RELEASE_NOTES_v8.5.md) for the full write-up.

v8.5 is a bug-fix and interface release. It fixes the two defects that made the
auto-search feel broken — a proven config not reaching **My Configs**, and the
first result taking far too long — and rebuilds the home screen as a real
terminal.

| Change | How |
|---|---|
| **A proven config lands in My Configs immediately** | v8.4 called `flushWorking()` only *after* the entire batch loop finished, so nothing was persisted mid-sweep. That call is gone. `promoteWorking()` now runs inside the per-config coroutine under `withContext(NonCancellable)` the moment the three-stage verdict returns, so the write survives the app being backgrounded, swiped away, or killed. The `NonCancellable` region covers the store write only — never the probing — so STOP still stops the sweep promptly. |
| **The per-config ping budget is a real ceiling** | The TCP pre-gate used to sit *outside* `withTimeoutOrNull(PER_CONFIG_BUDGET_MS)`, so one `ping()` could cost 3.5s + 9s = 12.5s, and up to ~28.5s through `pingWithRetry`. It is now inside the budget. Probe URLs went 3 → 2 (both still Cloudflare, per guide §5c) and warm samples 4 → 2. |
| **The most promising configs are probed first** | A first-answer lane single-probes the best 24 triage-ranked candidates before the full sweep, using the *same* `Pinger.ping` so all three verdict stages still run. Only the retry is skipped, and a miss is requeued rather than recorded as a verdict. |
| **My Configs growth is bounded** | `ConfigStore.addPromoted()` keeps the best-ping `MAX_PROMOTED = 200` engine-promoted configs. Provenance lives in a separate `promoted_pings` key, so a config you pasted yourself is never evictable and never counts against the cap; the currently-selected config is never evicted either. Eviction is fully deterministic. |
| **Terminal cockpit** | Bundled JetBrains Mono (OFL-1.1, licence shipped), a real terminal window with a title bar and prompt, a 4dp spacing grid in `dimens.xml`, and a taller home banner (68dp → 88dp, 76dp on short screens). |
| **The status line stopped crying wolf** | v8.4 showed "TAP TO CONNECT" in **red** at the top of the screen while the green pill said the same thing, so the red copy read as an error when nothing was wrong. The status line now reports STATE (`READY · not connected`), the button asks for an ACTION. |
| **Light theme system bars fixed** | Both bars were painted `#FFFAFAFA` without requesting dark icons, so the clock, battery and nav icons were white-on-near-white (measured 1.04:1 and 1.30:1). Now 5.67:1 and 5.50:1 — both WCAG AA. |
| **Network settings are measured per network** | A new `NetWatcher` re-measures on a real network change (2.5s settle debounce, 60s floor, `tryLock` so an in-flight measurement is skipped rather than queued), and never measures through a live tunnel. |
| **Fixed: measured settings silently discarded after a handover** | `NetProfileStore.networkKey` used bare `cm.activeNetwork`, which returns the **VPN** network once the tunnel is up. The mid-session key therefore never matched the stored one, so every `matches()` returned `EMPTY` and the measured DNS block, MSS clamp and resolved IP mode were thrown away on the revive path. It now resolves the underlying non-VPN network. |
| **One ping statistic everywhere** | `XrayManager.measureDelayStable()` defaults to 2 warm samples, matching `Pinger`, so the ping path and the connect path report the same number (guide §5c). Median of warm samples after discarding the cold one — never an EMA. |
