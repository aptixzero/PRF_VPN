# Professor VPN v4.7 — The Ping Works Again (Real, No Google) + Crash-Proof Auto Test

## 🎯 What v4.7 fixes (the reported bugs)

### 1. «هیچ کانفیگی پینگ نمی‌داد» — no config ever showed a ping
**Root cause (v4.6):** the ping demanded **two** successful round-trips on **two
distinct censored endpoints** inside a 9-second budget with a 4s per-probe cap.
On Iran's high-RTT links the first endpoint alone can eat 3–4s, so the second
confirmation never fit the budget → **every** config, even perfectly working
ones, was reported "unreachable". On top of that, the native
`measureOutboundDelay` JNI call **ignores coroutine cancellation**, so the
per-probe timeout silently never fired and one hung probe froze the whole sweep.

**The v4.7 fix:**
- **ONE** confirmed real round-trip through the actual Xray outbound to a
  **censored** endpoint now counts as reachable — the same honest standard
  v2rayNG applies. The number shown is the real measured latency of that
  round-trip: the truest ping an Iranian user will actually experience.
- **No Google, ever.** Probe endpoints are exclusively filtered targets
  (Cloudflare edge `generate_204`, Cloudflare trace, Telegram, Instagram) —
  reaching them proves genuine censorship bypass. Google is open on every
  Iranian ISP and proves nothing, so it is deliberately absent from every
  probe list (ping, connect health check, watchdog).
- **Truly cancellable probes:** the blocking native call now runs in a
  supervised `async` job awaited **with** a timeout; a hung native probe is
  abandoned instead of stalling the sweep.
- Budgets widened for Iran reality: 12s per config, 5s per probe.

### 2. «هیچ کانفیگی وصل نمی‌شد» — nothing connected
The connect-time health check and the watchdog used the same impossible
two-confirmation rule, so the service rejected every server ("Server not
responding") and tore down live sessions. Both now use the repaired
one-real-round-trip rule against censored endpoints — a config that pings
**connects**, and a connected tunnel is no longer killed by a rule it can't
satisfy. The post-connect health check itself is fully preserved (internet
off ⇒ still never shows "connected").

### 3. کرش هنگام اضافه‌شدن لیستِ ۲۴۰تاییِ بعدی در اتو تست
Three compounding causes, all fixed:
- **RecyclerView desync:** the list reload swapped `adapter.items` *before*
  running DiffUtil, so the diff's "old list" was already the new list while the
  RecyclerView still displayed the previous one → "Inconsistency detected.
  Invalid view holder adapter position" exactly on the batch transition.
  Every list change now goes through a single atomic `submitList()` that diffs
  against what is actually displayed (both tabs).
- **Unbounded growth:** the engine *appended* every new 240-batch onto the old
  one and the shared ping-status map never shrank — memory grew every cycle
  until OOM. The engine now **replaces** the visible batch each cycle (working
  configs are already saved to My Configs), prunes dead ping states
  (`PingService.prune`), and caps the dedup set (re-seeded from the bounded
  persistent store).
- **Native memory pressure:** ping concurrency lowered (each probe spins a
  throwaway native Xray core): Auto Test 3–6, PING ALL 4–8, scaled by cores.

### 4. Crash handler hardened
- The global `CrashHandler` now detects `OutOfMemoryError` anywhere in the
  cause chain and immediately sheds load: stops the Auto-Test engine, prunes
  ping caches, forces GC — the process survives instead of dying.
- All existing guarantees kept: background-thread crashes are swallowed and
  logged (sanitized), a main-thread crash relaunches the app cleanly with a
  crash-loop guard.

## ✅ Golden rules re-verified
- Connection is 100% real (TUN + Xray + tun2socks) — unchanged.
- Post-connect health check kept (internet off ⇒ not connected).
- No `Random()` anywhere in any stat path — all pings/speeds are measured.
- Only VLESS/VMESS parsed. No ad scripts. No hardcoded credentials.

## 📦 Build
- `versionCode 28`, `versionName "4.7"`.
- CI now also **replaces `build/*.apk`** with the freshly-built signed
  universal APK on every `main` build and publishes the `v4.7` GitHub Release
  automatically.
