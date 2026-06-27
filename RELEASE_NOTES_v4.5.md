# Professor VPN v4.5 — trustworthy ping, full bandwidth, crash-proof Auto Test

This release targets the three problems reported from real use inside Iran:
a **ping that lied**, **low speed / poor throughput**, and **crashes when Auto
Test moved from one list to the next**. Bypass-for-Iran stays on by default.

## 1. The ping no longer lies (ping == it really connects)

Previously the per-config ping required **two** independent censored-endpoint
confirmations, but the LIVE connection's health check and watchdog timed only a
**single** `generate_204` request. That mismatch is exactly what produced the
"pings 100 ms, 10 s later it's dead / nothing opens" bug — a node that scraped
one lucky probe showed a green ping yet couldn't actually carry traffic.

* New `XrayManager.confirmedHealth()` runs the **same two-censored-endpoint
  confirmation** the manual ping uses, but through the **already-running core**.
* Connect-time verification, and the watchdog's keep-alive decision, now all use
  `confirmedHealth()`. So:
  * a config that pings green **really connects** (the live check agrees), and
  * a silently-dead node is detected fast instead of lingering as "connected".
* Probe set is censored edges only (Cloudflare edge + trace, Telegram, Instagram)
  — **no Google** (open everywhere, proves nothing). Everything is real; there
  is no fake/test value anywhere.

## 2. Full bandwidth + much faster connection

* Per-connection buffer raised to **4 MiB** so a single fast stream can fill the
  user's whole pipe on high-BDP links ("تمام پهنای باند").
* **TCP Fast Open** on every dialer → one less round-trip per connection (big win
  on Iran's high-RTT links; browsing feels noticeably snappier).
* TLS-record fragmentation made **lighter** (`40-100` len / `5-10` interval,
  ClientHello only) — keeps the anti-DPI SNI-hiding win without slowing the
  handshake or throughput.
* tun2socks (hev) tuned for throughput + persistence: larger task stack, a 5-min
  read/write timeout (long idle downloads / hours in another app never drop), a
  tight connect-timeout so genuinely dead links fail fast.
* Smart mux kept: OFF for Reality/XTLS & raw TCP+TLS (full single-stream
  line-rate), ON for ws/grpc/h2 (fewer stalls on disrupted links).

## 3. Auto Test is crash-proof across list transitions

The crash on the 1→2→3 list transition was the classic RecyclerView
"Inconsistency detected" — two writers (the Auto-Test reload and the ping-status
flow) swapping the adapter's backing list mid-layout.

* `ServerAdapter.applyStatuses()` now takes an immutable snapshot, swaps the list
  **before** dispatching the DiffUtil update, and is fully `runCatching`-guarded
  with a `notifyDataSetChanged()` fallback — it can never crash the process.
* `reloadFromStore()` defers while the RecyclerView is computing layout/animating
  and drives the change through the same guarded path.

## 4. Persistence — "never drops"

* The wake lock is **renewed by the watchdog** during healthy operation and while
  riding out a network outage, so a multi-hour download / background session
  survives well beyond the original 10 h window.
* When wifi/data drops, the watchdog **holds** the tunnel (never tears it down)
  and re-pins the underlying network the instant connectivity returns.

## 5. Build

* Added the real GitHub Actions workflow at `.github/workflows/build.yml` (the
  previous file was misnamed `build.yml.github-workflow` and never ran). It reads
  the version from `app/build.gradle.kts`, builds the signed universal APK, wipes
  `build/` to contain **exactly one** artifact
  (`ProfessorVPN-v4.5-universal.apk`), commits it back, and publishes the release.
* One universal APK: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (Android 7.0+).
