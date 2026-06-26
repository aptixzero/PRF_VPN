# Professor VPN v4.2 — Accurate Ping, Instant Auto-Test, Crash-Proof Stability

This release rebuilds the parts users complained about: fake pings, instability,
and Auto-Test crashes. Nothing in the real VPN core's golden rules was relaxed —
the connection is still 100% real, all stats are still live, and only vless/vmess
are accepted.

## Ping is now REAL and against FILTERED targets
- Pings are measured against services that are actually **blocked for Iranian
  users** — **Cloudflare**, **Telegram**, and **Instagram** endpoints — instead
  of `google.com` (which is often reachable even without a VPN and produced
  misleadingly "green" pings on dead proxies).
- The 2-stage confirmation now requires a genuine proxied round-trip to **two
  different filtered endpoints** before a config is marked reachable. This makes
  the rule **"if it shows a ping, it connects 100% and won't drop"** hold in
  practice — no more fake pings.
- The post-connect health check + watchdog also probe a filtered Cloudflare edge,
  so "connected" truly means "censored sites are reachable".

## Auto-Test: instant add, never waits, crash-proof
- The moment a config returns a real ping it is **immediately** moved into **My
  Configs** (flush threshold = 1). It no longer waits for the list to finish —
  whether there's 1 config or 120, each working one appears live, one by one.
- A persistent **heads-up notification at the top of the screen** announces
  **"Auto Test روشن است · در حال تست…"** while a run is active, updating the count
  of healthy configs found, and clears automatically when stopped.
- Concurrency is now **adaptive to the device** (CPU-core based) so Auto-Test is
  fast on strong phones and gentle on weak ones — fixing the "crashes after a few
  minutes of testing" report. Every probe is isolated; a malformed/slow config
  can never tear down the loop.

## Stability — never disconnects, survives anything
- The watchdog is far more **patient and resilient**: failures are double-probed
  before reacting, the core is silently re-spun in place, and a "connection lost"
  is only surfaced after many sustained hard failures **while a live network
  exists**. Temporary outages (doze, dead-zones, screen-off) no longer drop you.
- If the physical network is gone (airplane mode / tunnel), the VPN **holds the
  tunnel armed** and re-pins the underlying network when it returns.
- The native **tun2socks bridge auto-restarts** if it ever returns unexpectedly.
- Full-bandwidth tunnelling retained (large per-connection buffers, mux off,
  TLS-record fragmentation anti-DPI, aggressive keep-alive).

## Crash handlers everywhere
- The global crash handler now swallows uncaught exceptions on **every background
  thread** (coroutine dispatchers, OkHttp, ping/auto-test workers, GL/render
  threads, timers, pools). The app only ever exits on a true main-thread crash —
  which it then recovers from by relaunching cleanly.
- Screen-off, switching to another app, or switching tabs no longer crashes,
  freezes, or bugs out — Auto-Test and the tunnel keep running app-scoped.
- Notification permission is requested on Android 13+ so the Auto-Test banner and
  VPN status always show.

## Build
- `versionCode 23`, `versionName 4.2`. Universal signed APK
  (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, Android 7.0+) built by GitHub
  Actions and mirrored into `build/`.
