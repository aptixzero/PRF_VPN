# Professor VPN — v4.1 Release Notes

**Version:** 4.1 · **versionCode:** 22
**APK:** `ProfessorVPN-v4.1-universal.apk` (universal — `arm64-v8a`,
`armeabi-v7a`, `x86`, `x86_64`; Android 7.0+; signed with the existing release
key so users can update in place)

This release is focused on **stability — the app must open and never crash** —
and on switching the free-config feed to the new source.

---

## 1. Crash fixes — "the app wouldn't open / kept stopping"

A set of layered **handlers** were designed and implemented so a fault on any
single path can no longer take the whole app down:

### Global crash handler now self-heals (`CrashHandler.kt`)
- Background / worker threads (VPN, tun2socks, stats, watchdog, coroutine
  dispatchers, thread pools) keep their existing **swallow-and-survive**
  behaviour.
- **NEW — main/UI-thread crash recovery:** instead of deferring to the platform
  handler (which showed the *"Professor VPN keeps stopping"* dialog and left the
  user with an app that "won't open"), the handler now **schedules a clean
  relaunch of the app via `AlarmManager` and kills the broken process**. To the
  user the app simply reopens itself and recovers from a one-off bad state.
- A **crash-loop guard** (`RELAUNCH_GUARD_MS = 10s`) prevents infinite restart
  spins: if we relaunched very recently we fall back to the platform handler so
  a deterministic startup crash can't loop forever.

### Boot path fully guarded (`SplashActivity.kt`)
- The entire boot routine is wrapped so a failure in **any** step (native init,
  prefs write, animation) can never strand the user on the splash screen.
- Navigation to `MainActivity` is now in a `finally {}` block via a single,
  idempotent `goToMain()` — **the app always proceeds**, because the heavy
  native warm-up is a "nice to have", not a hard prerequisite (the VPN service
  re-inits the core lazily on connect anyway).

### Home screen wiring guarded (`MainActivity.kt`)
- All tab / drawer / fragment wiring runs inside a guarded `setupUi()`. A
  failure wiring one control can no longer crash the home screen — at worst a
  single control is inert.

### Crash-safe native library load (`TProxyService.kt`)
- `System.loadLibrary("hev-socks5-tunnel")` previously sat unguarded in an
  `object init{}` block, so a missing / ABI-mismatched `.so` could throw an
  `ExceptionInInitializerError` and crash the app the first time the class was
  touched. It is now loaded defensively, exposing a `nativeAvailable` flag; the
  Xray SOCKS path keeps working even if the native counter fallback is absent.

---

## 2. New free-config source — `aptixzero/con_new`

- Free configs are fetched **exclusively** from
  <https://github.com/aptixzero/con_new> (`configs_000.txt … configs_NNN.txt`,
  3-digit zero-padded, file count discovered at runtime via the GitHub trees API
  with a 24 h cached fallback).
- Each file is fetched through a CDN fallback chain (raw → jsDelivr → gitcdn)
  so it survives DPI inside Iran. Only `vless://` / `vmess://` lines are kept.

---

## 3. Build / release

- Version bumped to **4.1 / code 22**.
- A fresh **signed universal APK** was built and verified
  (`apksigner verify` → `CN=NeonVPN`, v1+v2+v3 schemes) and placed in
  [`build/`](./build) as the **single** current artifact
  (`ProfessorVPN-v4.1-universal.apk`); the old v4.0 APK was removed.
- Download-page manifest updated to **4.1** with a Persian changelog.

---

## Golden rules preserved

Real `VpnService` tunnel · no `Random` in any stat path · post-connect health
check · only VLESS/VMESS · no ad SDKs · no hardcoded credentials · watchdog-
backed honest "connected" state.
