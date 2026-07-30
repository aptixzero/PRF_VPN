# 🤖 AI AGENT — READ THIS BEFORE TOUCHING ANY CODE

> **STOP.** If you are an AI model / coding agent assigned to work on this
> repository, you **MUST** read this entire file first and obey it. This project
> ("Professor VPN") has a **real, working VPN core**. A previous version was
> "fake" (it lied about being connected and showed random ping/upload/download).
> That was fixed. **Do not regress it.** Breaking the rules below will ship a
> broken app to real users in Iran who depend on it to bypass censorship.

This document is written **for the AI model, not the end user.** It explains how
the app actually works so the next agent can extend it **without breaking the
core**.

---

## 0. THE GOLDEN RULES (non-negotiable)

1. **The connection must be 100% REAL.** When the UI says "Connected", the device
   traffic is genuinely tunneled through a live Xray outbound. Never fake it.
2. **Never show fake / random ping, upload, or download.** All stats come from the
   live core. There must be **no `Random()`** feeding any stat anywhere.
3. **When the internet is OFF, pressing connect MUST NOT show "connected".** This is
   guaranteed by the **post-connect health check** (see §3). Do not remove it.
4. **Only VLESS and VMESS** configs are supported. When parsing mixed/pasted text,
   ignore trojan / shadowsocks / ss / anything else — add only `vless://` and
   `vmess://`.
5. **No ad-network scripts in the app.** The in-app banner is a plain,
   panel-controlled placeholder. Do not add Adsterra / effectivecpm / etc.
6. **No hardcoded usernames/passwords/tokens** anywhere in source. Admin panel
   uses SHA-256 hashes only.
7. **The reference repo `perofesor/VPVPN` is the source of truth for the core.**
   It must remain untouched. This repo (`prfgame/prf-VPN`) is built on top of it.

---

## 1. WHAT THE CORE IS (do not rewrite from scratch)

The real VPN engine lives in these files. **Treat them as load-bearing.** Read
before editing; prefer additive changes.

| File | Responsibility |
|---|---|
| `service/NeonVpnService.kt` | The `VpnService`. Establishes the TUN interface, starts Xray, runs the **health check**, the **stats pump** (real up/down/ping), and the **watchdog**. |
| `service/XrayManager.kt` | Owns the libv2ray (`libv2ray.aar`) core instance: start/stop, `measureDelay()` (real latency through the live outbound), `queryTrafficDelta()` (real byte counters). |
| `service/TProxyService.kt` | `hev-socks5-tunnel` (tun2socks) JNI bridge: TUN ⇄ local SOCKS5. Native byte counters fallback. |
| `config/XrayConfigBuilder.kt` | Builds the Xray JSON (inbounds: SOCKS5 10808 + API 10809; proxy outbound for the selected server with Reality/XTLS/TLS). **v5.0 (CRITICAL):** `sockopt.dialerProxy` is set **ONLY** for plain-TLS-without-flow configs (chaining to the `frag-dialer` freedom outbound that fragments the ClientHello). It is **NEVER** set for Reality, XTLS-Vision (flow), or plaintext — chaining those through a freedom dialer corrupts the handshake / breaks the Vision splice so the TUN comes up but **no bytes flow** ("fake connected"). v4.9 set dialerProxy on EVERY config, which was the bug. Keep `usesFragmentDialer()` as the single gate. Only emit the `mux` block when mux is actually enabled (never `concurrency: -1`). |
| `config/ConfigParser.kt` | Parses `vless://` and `vmess://` (and only those) into `ServerConfig`. Handles emoji/symbols/mixed text. |
| `config/Pinger.kt` | Real proxied ping through an actual Xray outbound to CENSORED endpoints only (NEVER Google). v4.7: ONE confirmed real round-trip == reachable; probes are truly cancellable. |
| `config/LiveSources.kt` / `SourceFetcher.kt` | 50 live free-config feeds (25 vless + 25 vmess) + resilient mirror fetching. |

### How a connection actually happens (the happy path)
```
user taps eye
  → VpnService.prepare()  (system VPN permission)
  → NeonVpnService.startVpn()
      1. build Xray config (XrayConfigBuilder) for the selected server
      2. establish TUN (Builder.establish())
      3. start Xray core (XrayManager.start)
      4. >>> HEALTH CHECK <<<  measureDelay() through the LIVE core, retried a
         few times. If it never returns a valid delay → STATE_ERROR + stop.
         (THIS is what makes "internet off ⇒ not connected" true.)
      5. start tun2socks (TProxyService) bridging TUN ⇄ SOCKS5 10808
      6. >>> v5.0 REAL-TRAFFIC PROOF <<<  verifyRealTunnelTraffic(): drive real
         HTTP request THROUGH the local SOCKS5 inbound (the socket tun2socks
         feeds) to a censored endpoint and require actual response bytes. If no
         bytes flow → STATE_ERROR + stop. This proves the FULL device path works,
         not just that the outbound can dial. (This is the authoritative fix for
         the "shows connected but no upload/download" bug.)
      7. broadcast STATE_CONNECTED + start stats pump + watchdog
```

If you remove or weaken step 4 OR step 6, the "fake connected" bug comes back.
**Don't.**

---

## 2. STATS ARE REAL (don't fake them)

The stats pump in `NeonVpnService.kt`:
- **up/down speed + totals** come from `XrayManager.queryTrafficDelta()` (Xray stats
  API). If that returns 0 for a tick, it falls back to **`TProxyService.TProxyGetStats()`**
  native TUN tx/rx counters. Either way: **real bytes that actually moved.**
- **ping** comes from `XrayManager.measureDelay()` every ~5s — a real probe through
  the live outbound.
- **uptime** is wall-clock since connect.

There is intentionally **no random number generator** in this path. Keep it that way.

---

## 3. THE WATCHDOG (keeps "connected" honest)

After connecting, a watchdog periodically re-checks core health (`measureDelay`).
On repeated failure it broadcasts `STATE_ERROR` ("Connection lost — pick another
server") and tears the session down, rather than lying that you're still connected.
Don't disable it.

---

## 4. UI — what the user sees

- **Connect button** = `ui/widget/ConnectControlView.kt` — a premium connect
  button that morphs into a **3D reptilian eye drawn on Canvas** (no PNGs). Three
  states: `IDLE` (disconnected), `CONNECTING`, `CONNECTED`, each with its own
  animation + color (violet/amber/emerald). The glow fades to transparent (no
  rectangular bounds). Tapping calls `onClick`.
  - **v3.3 "alive eye":** the eye is a wide **almond that fills the whole control
    area** (no empty black bands above/below). It has a randomised, non-looping
    **blink** schedule (incl. occasional double-blink), subtle **idle breathing**
    (whole-eye scale), organic **pupil look-around** (drifts left / right / back to
    centre via `gaze`/`gazeTarget`, smoothed every frame), a slightly **smaller,
    centred cornea** with layered reflections + a sharp catch-light, and subtle
    swept **eyelashes** on the upper lid. All motion is jittered so it never feels
    robotic. There is **no `Random()` in any stat path** — the randomness here is
    cosmetic animation only (blink/gaze timing), never a connection stat.
- **Home brand row** = a themed **Telegram icon** (purple/black, `ic_telegram` +
  `telegram_icon_bg`, tinted violet — NOT Telegram blue) to the LEFT of the
  "Professor VPN" wordmark. Tapping it opens the admin-controlled
  `RemoteConfig.homeTelegramUrl` (the panel's **"In-App Telegram Link"**, falling
  back to the contact Telegram URL). **No hardcoded Telegram link.**
- **No matrix background and NO glitch text.** The full-screen matrix/hacker rain
  (`HackerBackgroundView` / `GlitchTextView`) was deleted on purpose, and the
  leftover RGB-split "glitch" on the brand wordmark (the `brand_ghost_a/b` ghost
  TextViews + `startGlitch()` + `kotlin.random.Random`) was **removed** in v2.8.1.
  The brand is now a single clean premium emerald wordmark. Do **not** re-add any
  glitch / falling-symbol / matrix effect anywhere (app or download page).
- **Bottom tabs:** Connect / My Configs / Free / Sponsor (`MainActivity`). Exactly
  these four — do not add more.
- **Hamburger menu (top):** contains "Contact Us" → `ContactActivity`.
- **Ad banner** = `ui/widget/AdBanner.kt` — renders the panel-controlled
  `RemoteConfig.ad` (title/subtitle/colors/image + click action). No scripts.

### My Configs tab
- "Paste From Clipboard" button at top. Detects and adds **only** vless/vmess even
  inside mixed text (trojan/ss/emoji/symbols are ignored).
- Select all / delete all / ping all / Copy (when selected).
- **v6.1 — PERMANENT & CLEAN bucket.** My Configs contains ONLY: (a) configs the
  user pasted/added by hand, and (b) configs that **actually ping** — the ones
  the `AutoTestEngine` copies in one-by-one AFTER they pass the ping test. It is
  **never** stuffed with a raw 240 batch, and it is **never** auto-wiped by a
  search / Auto Test / batch rotation — only a manual delete clears it.
- **No auto-ping here.** Pings are taken ONLY on the per-row PING button or PING
  ALL. Opening the tab / relaunch / screen off-on must never start a sweep.
- **Last ping sticks.** Ping results are content-keyed (`ConfigParser.pingKey`),
  so the last measured ping survives restart / tab switch / rename to "Server N".

### Free Configs tab
- Modes: **Manual (Search)**, **Automatic**, **Auto Test** (+ combined fallback).
- Ping all with **real, color-coded pings** (green=low / orange=medium / red=high).
- Configs that ping are pinned to the top; non-pinging fall to the bottom.
- **v6.1 — Search / Auto Test open the two-phase connection-test page** (0→60 %
  real connectivity test, 60→100 % pull a fresh **240** batch from the reached
  source). The 240 batch is placed in **Free Configs** (the old Free batch is
  wiped and replaced) — NOT My Configs.
- **v6.1 — Auto Test loop** (`AutoTestEngine`): pings the whole Free batch, copies
  EACH config that pings into My Configs live, and when the 240 are exhausted it
  wipes the Free list and pulls a brand-new 240 from the same bonded source, then
  repeats — until the user presses Cancel. My Configs is preserved across cycles.
- Clicking a free config auto-saves it permanently to My Configs.

### Sponsor tab
- The Sponsor tab is **NOT a server list**. It is a single, fully
  **admin-controlled advertisement** placeholder (`SponsorConfigsFragment` +
  the `AdBanner` widget) driven by the same `RemoteConfig.ad` the panel
  publishes. Default copy = "محل تبلیغ شما" / "جهت ثبت تبلیغ …"; default click
  action opens the Contact page. No ad-network scripts. (`SponsorConfigStore`
  is legacy/unused — the tab does not read it.)

---

## 5. PANEL ⇄ APP SYNC (RemoteConfig)

The admin panel publishes a single JSON file; the app fetches it on launch.

- **Model:** `config/RemoteConfig.kt` (`ad` + `contact`).
- **Fetch/cache:** `config/RemoteConfigStore.kt`.
  - `REMOTE_URL = https://prfgame.github.io/adminpanel/app_config.json`
  - Uses a cached copy instantly, refreshes in the background, has jsDelivr / jina
    mirror fallbacks so it loads inside Iran.
- **Loaded in** `NeonApp.onCreate()` (cache load + background refresh).

If you change the JSON schema, change **all three** in lockstep:
`RemoteConfig.kt` (parser) ⇄ `adminpanel/app.js` (`defaultModel`/`readForm`) ⇄
`adminpanel/app_config.json` (the published file). They must match exactly.

**v3.3 added** `inAppTelegramUrl` (+ nested `inAppTelegram.url`) — the home-screen
Telegram icon link. Edited in the panel's Contact tab ("In-App Telegram Link"),
parsed by `RemoteConfig.parse`, exposed via `RemoteConfig.homeTelegramUrl`.

**Server-name numbering (v3.3 fix):** `FreeConfigSource` keeps a PERSISTENT,
monotonically-increasing `KEY_NAME_COUNTER`. Consecutive searches produce
Server 1-100, then 101-200, then 201-300 … — the visible number does **not**
restart per search and is **not** derived from the in-memory list size. It resets
to 0 ONLY when the source repo (`aptixzero/con_new`) rotation resets (a new publish,
which also resets file/offset). Identity/dedup is still by config CONTENT
(`ConfigParser.dedupKey`), never by visible name.

**v6.3 added two panel-driven blocks** (`config/DownloadLinks.kt`):

- `downloadLinks` (alias `downloads`) → `DownloadLinksConfig(enabled, heading,
  note, items[{id,title,url,note}])`. Rendered by `ui/DownloadsActivity` as a
  SCROLLABLE list, each row with a COPY button. The first item also seeds the QR
  payload. Unlimited entries; added from the panel's **Download Links** tab.
- `notice` (aliases `notification` / `notifications`) →
  `NoticeConfig(enabled, title, text, id, color)`. Rendered as the `notice_card`
  at the top of `fragment_connect.xml`. The title is a BRANDED CONSTANT
  `اعلان Professor Vpn` — the app must **never** say the message came from an
  admin panel. `id` is stable per text; `AppPrefs.isNoticeDismissed/dismissNotice`
  make a given announcement dismissible once while a NEW text reappears.

---

## 5b. v6.3 INVARIANTS (do not regress)

- **`AutoTestEngine` must NOT use `ProcessLifecycleOwner.lifecycleScope`.** It owns
  a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` + a
  `CoroutineExceptionHandler`. Screen-off / background / weak-net must never kill it.
  When it truly cannot add configs (`MAX_EMPTY_STREAK = 6`) it calls `autoStop()`
  and reports `Progress.autoStopped` so the SWITCH TURNS ITSELF OFF.
- **`NeonVpnService.stopVpn()` must never block the main thread.** Flip state +
  broadcast synchronously, then run `cleanup()` on the `vpn-stop` daemon thread with
  a `finally { stopForegroundCompat(); stopSelf() }`. `stopThread` gives idempotency.
- **`Pinger` needs `MIN_GOOD_SAMPLES = 2`.** A single successful round-trip is NOT
  proof a node works. (v6.4 changed the reported statistic — see §5c.)
- **Country flags are Unicode regional-indicator emoji** (`util/CountryFlags.kt`) —
  never block the UI thread. Two-phase: `cachedFlagFor()` (zero I/O) then
  `resolveAsync()`. **Superseded for `IR` only in v6.4** — see §5c.
- **`util/QrCode.kt` is a self-contained ISO/IEC 18004 encoder.** Do not add a QR
  dependency.
- **Quick actions `auto_connect` / `kill_switch` / `protocol` are DISPLAY-ONLY.**
  Only `qa_settings` is clickable.
- **All colors in layouts/drawables go through `?attr/…`** (see `values/attrs.xml`)
  so the LIGHT theme stays correct. The only exception is `activity_splash.xml`,
  which is intentionally always dark.

---

## 5c. v6.4 INVARIANTS (do not regress)

- **EVERY latency probe in the app goes through `config/ProbeEndpoints.kt`.**
  Cloudflare only — `cp.cloudflare.com/generate_204`, `1.1.1.1/cdn-cgi/trace`,
  `www.cloudflare.com/cdn-cgi/trace`, `speed.cloudflare.com/__down?bytes=0`.
  **Never re-introduce a Google endpoint** (`gstatic.com/generate_204`,
  `dns.google`, …). The list-ping and the post-connect ping MUST use the same
  endpoint list, otherwise the "120 in the list, 1000 after connecting" bug returns.
- **One latency statistic on both sides:** discard the first (cold-handshake)
  sample, then report the **median of the warm samples**. `Pinger` and
  `XrayManager.measureDelayStable()` must stay identical. The stats pump calls
  `measureDelayStable()` (never the single-shot `measureDelay()`).
- **QUIC / HTTP-3 stays BLOCKED** in `XrayConfigBuilder` routing: one rule for
  `network: udp` + `port: 443`, one for `protocol: ["quic"]`. Free VLESS/VMESS
  nodes do not relay UDP reliably; QUIC tolerates loss so apps never fail-fast and
  video feeds silently freeze while the tunnel still looks healthy. Removing these
  rules brings back the "works 1 minute then locks" bug.
- **`policy.bufferSize` is PER CONNECTION.** Keep it small (512 KiB). Values like
  4096 KiB ask the core to reserve gigabytes under video-scrolling load.
  Keep the pool tight: `connIdle 120`, `uplinkOnly/downlinkOnly 4`, `handshake 12`.
- **The TUN is IPv4-only.** No IPv6 address, no `::/0` route; `allowFamily(AF_INET)`
  on API 29+. DNS `queryStrategy` and the `direct` outbound `domainStrategy` are
  both `UseIPv4`. This removes per-connection Happy-Eyeballs stalls.
- **hev-socks5-tunnel timeouts must stay bounded:** `read-write-timeout: 60000`,
  `udp-read-write-timeout: 20000`, `max-session-count: 1024`,
  `limit-nofile: 65535`. A 300 s read-write timeout wedges the session table.
- **Health checks must probe the DEVICE PATH, not just the core.** `probeDevicePath()`
  makes a real request through the **local SOCKS5 inbound** (the socket tun2socks
  feeds). Two consecutive failures ⇒ `restartTun2Socks()`, which rebuilds only the
  tun2socks bridge — the TUN, the Xray core and the user session stay up, no
  permission dialog, the UI never leaves Connected. Never "fix" a silent stall by
  tearing down the whole VPN.
- **The exit identity is read THROUGH the tunnel**, never guessed from the server
  hostname. `fetchTraceThroughTunnel()` parses `ip=` / `loc=` from Cloudflare
  `/cdn-cgi/trace`; `resolveExitIdentityAsync()` publishes `VpnStateBus.ExitIdentity`
  and `CountryFlags.rememberCode()` caches the country per host.
- **The Islamic-Republic flag must NEVER be rendered anywhere.** Four defences, keep
  all four: (1) `CountryFlags.emojiOf("IR")` returns `""`; (2)
  `FlagView.setCountry("IR")` draws the bundled `drawable/flag_ir_lion_sun.xml`
  (Lion-and-Sun) — this is the **one sanctioned bundled flag image**, and it is local
  so it works offline / on weak links; (3) `FlagView.setFlagEmoji()` re-routes any
  incoming IR emoji to that vector; (4) `CountryFlags.sanitizeForDisplay()` strips
  the glyph out of feed-supplied remarks (Home card + `ConfigsFragment` rows),
  replacing it with `[IR]`.
- **The flag fills its tile EXACTLY.** Use `ui/widget/FlagView` — never a `TextView`
  (font ascent/descent padding makes a glyph impossible to fit). It measures the real
  ink box with `Paint.getTextBounds` and scales X and Y **independently** onto the
  tile rect. Tile is 38dp with `drawable/flag_tile_bg`.

---

## 5d. v6.5 INVARIANTS (do not regress)

v6.5 fixed four *reported* defects. Each fix is one small, specific change, and
each is easy to undo by accident. Read this section before touching the service
lifecycle, the ping gate, or the QR encoder.

### The session lifecycle is SERIALISED. Never spawn a bare start/stop thread.

The v6.4 "connects once, then never again until you kill the app" bug was four
races in the teardown path. All four are closed by one design rule:

> **Every** connect / disconnect / config-switch request is queued on the single
> `sessionExecutor` in `NeonVpnService`, and every worker thread checks the
> session `generation` before it acts.

- `sessionExecutor` is a **single-thread** executor (`Executors.newSingleThreadExecutor`,
  thread name `vpn-session`). Do **not** replace it with a pool, and do **not**
  `Thread { … }.start()` a lifecycle step — that is exactly what allowed a new core
  to race the old one for ports 10808 / 10809.
- `generation` is bumped on every new request; `isStale(myGen)` must be re-checked
  at **every stage** of `startVpn(myGen)`. Worker threads are named
  `watchdog-$gen` / `stats-$gen` so a leaked thread is visible in logcat.
- `pendingConnects` + `finishIfIdle()` exist so a disconnect that is immediately
  followed by a connect does **not** `stopSelf()` the service out from under the
  queued connect. Only stop when `pendingConnects.get() == 0`.
- **`XrayManager.start()` must NOT short-circuit when a core is already running.**
  The v6.4 line `if (isRunning) return true` meant switching configs reported
  success while never loading the new JSON. `start()` now always `stopLocked()`s,
  then `waitForLocalPortsFree()` (a `ServerSocket` bind test on both local ports),
  then builds a **fresh** `CoreController` and resets `totalUp`/`totalDown`.
- **`TProxyService` tracks native run-state.** Use `startBlocking(configPath, fd)`
  and `stopAndWait(timeoutMs)`; never call the raw JNI `TProxyStartService` /
  `TProxyStopService` from new code. Two native tunnel sessions must never coexist
  on the same (or a closed) fd. `stopAndWait` **must not hold `nativeLock` while
  polling** — holding it deadlocks against the `startBlocking` thread that has to
  observe the flag change in order to exit.

### The teardown ORDER is load-bearing

```
teardownSession(reason):
  1. interrupt watchdog + stats threads
  2. TProxyService.stopAndWait(2500); tunnelThread interrupt + join(600)
  3. xray.stop()
  4. tunInterface?.close()
  5. unregisterNetworkCallback(); releaseWakeLock()
```

Stopping the core before the native tunnel leaves hev writing into a dead SOCKS
port; closing the TUN first makes the native tunnel spin on `EBADF`. Do not
reorder these steps.

### NO connection-time or connection-type limits. Ever.

The requirement is explicit: «هیچ محدودیتی روی زمان اتصال یا نوع اتصال نباشد».

- There is **no session timer** anywhere. Do not add one, not even for "safety".
- `TUN_MAX_RESTARTS = 20` bounds **consecutive failures only**, and the counter
  **resets after 120 s of healthy life**. Never convert it into a lifetime cap.
- `policy.handshake` is **16** (raised from 12). Lowering it re-breaks slow ISPs.

### The live gate must never be stricter than the list ping

If the connect gate is tighter than the ping, a node shows a green ping and then
fails to connect — the reported «پینگ می‌دهد ولی وصل نمی‌شود» bug.

- `CONNECT_VERIFY_BUDGET_MS = 14_000` (list-ping budget is 12 s), with a ramped
  inter-probe gap (300 → 900 ms), and from attempt 3 a `probeDevicePath()` success
  is also accepted — a real packet through the local SOCKS5 proxy is *stronger*
  evidence than a latency echo, so it must count.
- `Pinger` keeps its **sustain check**: after the initial samples pass, wait
  `SUSTAIN_PAUSE_MS` (700 ms) and probe once more. Some nodes accept the first
  burst and then die; without this they show green and fail on connect. Do not
  remove it as an "optimisation".

> **Superseded by v6.6 — see §5e.** The *principle* above (the gate must never be
> stricter than the list ping) still holds and is still enforced, but the numbers
> and the mechanism changed: the budget is now **10 s** against a **9 s** list
> ping, and the sustain check was replaced by the stronger **payload verdict**.
> Do not "restore" the numbers in this section.

### `tcpcongestion: bbr` is a FIX, not a tweak

In `XrayConfigBuilder`'s per-outbound `sockopt`: `bbr` paces from measured
bandwidth × RTT instead of treating loss as congestion. On lossy Iranian mobile
links a loss-based controller collapses the window — the reported "first three
Instagram videos load, then it freezes". Keep `bbr`, and keep
`sockopt.domainStrategy = UseIPv4` next to it.

### `QrCode.kt` — the interleave formula, and why it must never change back

`shortBlockLen` is the **TOTAL** length of a short block: data codewords **plus**
that block's EC codewords.

```kotlin
val shortBlockLen = rawCount / numBlocks          // CORRECT (v6.5)
// NOT (rawCount - totalEcc) / numBlocks          // WRONG  (v6.4)
```

v6.4 subtracted the EC codewords here **and again** when deriving each block's
data length, so every block was `eccLen` codewords short and only part of the
matrix was written — e.g. **44 of 70** codewords for a 41-char URL. The finder
patterns, timing patterns and format bits were all perfect, so the result *looked*
like a flawless QR code, but every scanner failed Reed–Solomon and gave up
silently. That is the whole «بارکد کار نمی‌کند» bug.

- The `require(idx == rawCount)` guard at the end of `interleave()` is a
  **regression tripwire**. Never weaken it to a `Log.w`: rendering nothing is
  correct, rendering a dead barcode is not.
- `QrCode.kt` stays **dependency-free** (no ZXing, no Play Services).
- The QR payload is the operator's link **verbatim**. Do **not** re-wrap it in
  `professorvpn://get?bt=1`: `DownloadsActivity` is exported with a `BROWSABLE`
  filter for that scheme, so a scan could be swallowed by the app instead of
  opening the visitor's browser — which defeats the entire purpose.
- ECC is `QUARTILE` with a `MEDIUM` fallback for long links.

### The panel preview uses the APP's encoder

`adminpanel/qrcode.js` is a port of `QrCode.kt`, interleave fix included. Do
**not** swap in a QR web service or a third-party library:

1. the panel could then show a working barcode while the app draws a broken one
   (exactly the v6.4 situation), and
2. the panel is served from GitHub Pages and must keep working with **zero**
   third-party requests.

If you change `QrCode.kt`, change `qrcode.js` in the same commit. Same rule for
`normalizeLink()` (JS) and `QrLinkConfig.normalizedUrl()` (Kotlin): scheme-less
input gets `https://`, other schemes (`tg:`, `mailto:`) pass through untouched.

---

## 5e. v6.6 INVARIANTS (do not regress)

v6.6 fixed four reported defects: fake pings, slow pings, "connects but doesn't
work", and the 30-second wait. Each fix is load-bearing. **Read this whole
section before touching `Pinger`, `PingService`, `NeonVpnService.connect`, or
anything in `net/`.**

### A ping is a THREE-STAGE verdict. All three stages are required.

`Pinger.ping()` is a pipeline, not a measurement:

1. **`TcpProbe.reachable()`** — reject-only pre-gate.
2. **Latency samples** — `SAMPLE_COUNT = 3`, need `MIN_GOOD_SAMPLES = 2`, report
   the **median of the warm samples** (drop the cold first when ≥3).
3. **`verdictProbe()` → `XrayManager.measureConfigThroughput()`** — after
   `VERDICT_PAUSE_MS` (350 ms), a **fresh** connection must fetch a **real body**
   (including a 32 KiB `speed.cloudflare.com` download).

**Why stage 3 exists, and why deleting it brings the fake-ping bug straight back:**
a zero-byte `204` latency probe proves only that ONE tiny request completed.
Iranian DPI routinely admits the first handshake and **resets the next**; a node
at its connection cap accepts one trivial request and then stops serving. Both
pass a 204 probe. Stage 3 is the only thing that catches them.

This works *because* `Libv2ray.measureOutboundDelay(json, url)` builds its **own
throwaway core per call**. That property is what makes stage 3 a genuinely new
connection. If you ever refactor to reuse a core here, the verdict becomes
worthless.

### The TCP pre-gate may only ever REJECT

`TcpProbe` must **never** produce a number that reaches the UI. It answers one
question — "does anything accept a TCP connection on `address:port`?" — and:

- a **failure** is proof: Xray dials that same socket, so the core would fail too;
- a **success** proves nothing (DPI resets on the ClientHello), it only earns the
  right to be measured by stage 2.

This is exactly why the pre-gate does not violate Golden Rule #2 (no fake stats).
Keep it reject-only.

### The sweep is TWO WAVES with two different concurrency limits

`PingService`:

- **Wave 1** — `TcpProbe`, gated by `TCP_GATE_CONCURRENCY` (48). One socket each:
  no native core, no TLS. This is what makes the sweep feel instant, because a
  public feed is mostly dead entries.
- **Wave 2** — the full `Pinger` pipeline, gated by `MAX_CONCURRENCY` (4–8).

**Do not raise `MAX_CONCURRENCY`** to speed things up. Each deep probe spins up a
native Xray core worth tens of MB; that ceiling exists because higher values
exhausted native memory on 1–2 GB devices (the v4.7 crash). Widen wave 1 instead
— that is the cheap wave, and it is where the time actually goes.

`probeWithRetry()` retries only when a cheap socket check still says the node is
alive. Re-running a full deep probe on a corpse cannot change the answer and
doubles the user's wait.

### Probes are ZERO-DNS. Never point a probe at a bare hostname.

Every latency probe must resolve to nothing at all:

- `ProbeEndpoints.INSTANT = "https://1.1.1.1/cdn-cgi/trace"` — an **IP literal**;
- `ProbeEndpoints.HOSTS` is injected as a static `dns.hosts` table into **both**
  `XrayConfigBuilder.build()` **and** `buildPingConfig()`.

Both must keep the block, for two reasons: a cold DoH lookup costs 2–4 s on
*every sample* (this was most of the "slow ping" complaint), and if the live
config and the ping config resolve differently they are no longer measuring the
same thing.

### The connect gate verifies the DEVICE PATH, with the bridge already running

The order in `NeonVpnService` is **load-bearing** and was inverted in v6.6:

```
start core → waitForSocksInbound() → startTun2Socks()  ← bridge FIRST
           → verify probeDevicePath()                   ← then verify
```

v6.5 verified with `xray.measureDelay()`, which dials **straight out of the
core** and therefore **cannot see a broken TUN → tun2socks → SOCKS bridge** —
which is precisely what decides whether the *device* has internet. That is the
whole "it says connected but nothing works" bug. Never move the verification
back above `startTun2Socks`, and never downgrade it to a core-only probe.

`probeDevicePath()` must keep **draining the response body**
(`conn.inputStream.use { it.readBytes() }` → `isNotEmpty()`). A status code alone
can be produced by a tunnel that cannot actually carry data.

If verification fails the session **must** be torn down and reported as
`STATE_ERROR`. Never report `STATE_CONNECTED` on an unverified tunnel.

### Budgets: 10 s gate vs 9 s list ping

`CONNECT_VERIFY_BUDGET_MS = 10_000`, list ping `PER_CONFIG_BUDGET_MS = 9_000`.
The §5d rule still applies — **the gate must never be stricter than the list
ping** — so if you change one, re-check the other. The gate is *faster* than
v6.5's 14 s yet *more thorough*, because it tests the right thing.

Never restore the blind `Thread.sleep(450)` before the bridge:
`waitForSocksInbound()` polls every 25 ms and returns as soon as the inbound is
actually up. The retry ramp starts at **120 ms** (v6.5 used 300–900 ms).

### NO PROXIES. Not one. This is a hard product requirement.

Every public forwarder was deleted in v6.6: `r.jina.ai`, `api.allorigins.win`,
`ghproxy.net`, `cors.isomorphic-git.org`, `gh.api.99988866.xyz`,
`cdn.statically.io`, `gitcdn.link`. v6.9 removed the last two survivors as well:
the `cdn.jsdelivr.net` CDN mirror and the `bin.mudfish.net` paste host.
**Do not add another one, ever**, and do not "temporarily" reintroduce one to fix
a fetch failure. There is now EXACTLY ONE candidate URL per source: the origin.

They are third-party servers that see and can rewrite every config the user is
about to route their traffic through; they are rate-limited and mostly blocked
from Iran anyway; and each dead one had to time out (~9 s) before the next was
tried — five of them was most of a minute wasted per source.

**The correct fix attacks the real blocking mechanism.** On Iranian ISPs the
dominant block on `raw.githubusercontent.com` is **DNS poisoning** — the host is
reachable once you learn its true address. So:

- **`net/CfDns.kt`** — an `okhttp3.Dns` that resolves over **Cloudflare DoH**
  (`1.1.1.1` / `1.0.0.1` **by IP literal**, so there is no bootstrap lookup to
  poison), 10-minute cache, IPv4-only, system-resolver fallback. The fallback
  matters: on an unfiltered link, or where DoH itself is blocked, the system
  resolver is fine and we must not fail hard.
- **`net/DirectHttp.kt`** — the ONE shared HTTP client. `.dns(CfDns)`,
  `.proxy(java.net.Proxy.NO_PROXY)` (also stops OkHttp inheriting a
  carrier-injected system proxy), pooled connections + HTTP/2, tight timeouts.

**All new network code must go through `DirectHttp`.** Do not open a fresh
`HttpURLConnection` for a feed fetch — you lose DoH resolution, the
`NO_PROXY` guarantee, and the connection pool that makes many small fetches fast.
As of v6.9 there is **no fallback mirror at all**. `cdn.jsdelivr.net` was removed
too: whether or not it counts as "a proxy", it is still somebody else's server
sitting between the user and their config list, and a dead mirror still cost a
full extra timeout on every dead source. The replacement for mirror-chaining is
PARALLELISM — `SourceFetcher` + `FreeConfigSource` open many ORIGIN feeds at once
(waves of 8), so one wave costs one timeout instead of eight.

Exempt from `DirectHttp`, by design: `CfDns` itself (it must not recurse into the
client that depends on it) and the geo-IP / counter helpers in
`util/CountryFlags.kt` and `stats/UserStatsReporter.kt`, which are plain public
APIs and not part of the config supply chain.

### PING always means: clear → "Pinging…" → new value

Both `pingAll()` and `pingOne()` set the affected rows to `PingStatus.Testing`
**before** probing and write the result only when the probe finishes. The UI maps
`Testing → "Pinging…"`. Values must never auto-jump, drift, or reset on their
own. `cancel()` flips orphaned `Testing` rows back to `Idle` and leaves every
finished measurement untouched.

---

## 5f. v6.7 INVARIANTS (do not regress)

v6.7 is the release that made Auto Test produce **low-ping** configs **fast**.
Everything below is load-bearing; each rule exists because breaking it
re-introduces a specific, reported bug.

### The TCP measurement may ONLY reject or order — NEVER display

`TcpProbe.connectMs()` returns the real, measured handshake time to a node's
`address:port`. It exists because the tunnel round trip **physically contains**
the TCP round trip to the same host, which makes the measurement a hard **lower
bound** on the ping that node could ever report — and therefore the correct key
for ordering work.

It is **not a ping**. It is the latency to the node's front door, not through the
tunnel. Three consumers use it, and all three obey the same contract:

| Consumer | Uses it to | Must never |
|---|---|---|
| `PingService.pingAll()` wave 1 | reject the dead; sort wave 2 ascending | write it into `statuses` |
| `AutoTestEngine.triageBatch()` | drop undialable configs; order the batch | mark a config working |
| `ConnectivityProbe` phase 1 | score/rank source feeds; order the batch | report it as a latency |

**Every number the user ever sees still comes out of `Pinger`**, including the
v6.6 Stage-2 payload verdict. If you ever find yourself writing a `connectMs()`
result into `PingStatus.Reachable`, stop — that is the fake ping this project
forbids, and it is exactly what §0 Golden Rule #2 is about.

### Auto Test must be STRICTER than the manual ping, never looser

`AutoTestEngine.WORKING_MAX_MS = 2_500` vs `Pinger.MAX_VALID_MS = 8_000`.

Auto Test runs unattended for hours and writes directly into **My Configs**. At
the old 8 000 ms bar it filled the user's list with nodes that technically
answered and then made every page load feel broken — the reported *«هر کانفیگی
پینگ بالا می‌دهد»*. The invariant:

> `AutoTestEngine.WORKING_MAX_MS` **≤** `Pinger.MAX_VALID_MS`, always.

Auto Test may only accept a **subset** of what a manual ping accepts, so it can
never bless something a manual ping would reject. Raising it back toward 8 s
re-creates the bug; lowering it below ~1 s starts discarding usable nodes on a
high-RTT Iranian link.

Accepted configs are written **sorted ascending by their real measured ping**
(`flushWorking`), because the app auto-selects the first row.

### Phase 1 (0→60 %) must MEASURE, not merely FETCH

The pre-v6.7 phase 1 stopped at the first feed that answered. That made the
winner an accident of list order, and — more importantly — it tested GitHub's
availability rather than whether the *servers inside the feed* work for this
user. Do not go back to it.

Phase 1 must:

1. fetch `CANDIDATES_PER_KIND` feeds **in parallel** (never serially),
2. handshake `SAMPLES_PER_SOURCE` nodes from each, sampled **evenly across the
   feed** (never the first N — the head of these lists is the same stale block
   everywhere, so head-sampling scores all feeds alike),
3. rank with `scoreOf()` = `median RTT × (1 / hit-rate) + fetchMs/10`,
4. refuse to bond to a feed with fewer than `MIN_LIVE_SAMPLES` live nodes.

The candidate window **rotates by round** so a retry inspects different feeds.

The bar is driven by completed network work only. It may never regress (the
`AtomicInteger` CAS in `emit()` guarantees this under the parallel probes), it
must **hold** rather than sprint to 60 % while the link is down, and it may only
reach `PHASE1_END` on a genuine success.

### Concurrency: wide for sockets, narrow for cores

| Wave | Constant | Value | Why |
|---|---|---|---|
| socket triage | `TcpProbe.MAX_CONCURRENCY` | 48 | a bare socket is an fd + a few hundred bytes |
| deep probe | `PingService.MAX_CONCURRENCY` | 4–8 | each spins a throwaway native Xray core (tens of MB) |
| Auto Test deep | `AutoTestEngine.MAX_CONCURRENCY` | 3–6 | same, on a lower-RAM budget |

**Never raise the deep-probe numbers.** That was the v4.7 low-RAM crash. Widening
the *socket* waves is safe and is where the speed comes from.

### `LiveSources` grows, it never shrinks

70 feeds as of v6.7 (50 original + 20 added). Rules:

- **vless / vmess only** (Golden Rule #4).
- **Never delete an existing entry** — the user's instruction is explicit:
  *«قبلی‌ها رو پاک نکن از همون‌ها هم استفاده کن»*. A feed that goes dark costs
  one timed-out fetch inside a parallel wave and is then simply outranked; that
  is far cheaper than losing a feed that comes back.
- **Verify every new URL with a real HTTP request before adding it.** All 20
  v6.7 additions were confirmed to return real links. Do not add a URL you have
  not fetched.
- A feed may legitimately appear twice with different `Kind`s when it is a mixed
  subscription (e.g. `Surfboardv2ray/converted.txt`); `extractLinks` filters by
  kind, so this is correct and not a duplicate.

### The barcode must resolve to a real URL

Three things must all stay true or the scan degrades to "it just copies the
link":

1. **The payload carries an explicit scheme.** A payload without one is *text* to
   a phone camera, and cameras offer *copy* for text. Both the panel link
   (`QrLinkConfig.normalizedUrl()`) and the download-link fallback
   (`asBrowsableUrl()`) must normalise to `https://…`. Never append `bt=1`,
   version markers, or tracking to a barcode payload.
2. **The manifest declares `<queries>` for `http`/`https` browsable VIEW
   intents.** Without it, Android 11+ package-visibility filtering makes
   `startActivity(ACTION_VIEW, https://…)` throw. Do not remove that block.
3. **`openInBrowser()` never fails silently.** Direct view → system chooser →
   copy-to-clipboard *with a toast*. The original bug was a bare
   `runCatching { startActivity(...) }` swallowing the exception.

The `professorvpn://get` deep link must **forward** a `url`/`link`/`to` parameter
straight to the browser rather than swallowing it, so a scan behaves identically
whether or not the scanner's phone has the app installed.

---

## 5g. v6.8 INVARIANTS (do not regress)

v6.8 is the SPEED release. It changed the **cost** of a ping, never its meaning.
Read this before touching `Pinger`, `XrayManager.measureConfigThroughput`,
`PingService`, or `AutoTestEngine`.

### A ping still spins native cores — v6.8 just spins FEWER of them

Every `Libv2ray.measureOutboundDelay` call constructs a full throwaway native
Xray core. The number of cores per config is what dominates the sweep's
wall-clock, and it is the lever v6.8 pulls:

- `Pinger.SAMPLE_COUNT = 2` (was 3). Two samples: discard the cold first one,
  report the warm second. Do not raise it back to 3 "for accuracy" — the accuracy
  that matters comes from the Stage-2 verdict, not from more latency samples.
- `Pinger.MIN_GOOD_SAMPLES = 1` (was 2). The **verdict** (a fresh connection
  carrying real bytes) is the proof a node works; requiring a second latency
  sample on top of it double-charged the most expensive step and discarded good
  high-RTT Iranian nodes. Do not raise it.
- `XrayManager.measureConfigThroughput()` issues **ONE** probe to
  `ProbeEndpoints.INSTANT` (zero-DNS IP literal, real ~350 B body) — NOT a loop
  over several payload URLs. The old loop paid up to THREE extra cores for a
  single config. Never reintroduce the `PAYLOAD_URLS` walk. One real-body probe
  through a brand-new core already proves both a fresh DPI handshake and real
  payload throughput, which is the entire verdict.
- Budgets tightened: `PER_CONFIG_BUDGET_MS = 6 000`, `PER_PROBE_BUDGET_MS = 2 500`,
  `VERDICT_BUDGET_MS = 3 500`. These are floors on how long a *dying* node may
  cost the sweep; do not raise them without a measured reason.

### The verdict is still mandatory — the fake-ping guarantee depends on it

Cutting samples is fine; cutting the Stage-2 payload verdict is **not**. It is the
only thing that separates "answers a tiny 204 once" from "carries a real payload
on a fresh connection", which is exactly the «پینگ فیک» class of node. Keep it.

### Deep-gate concurrency went UP because per-config cost went DOWN

`PingService.MAX_CONCURRENCY` is now **6–12** and `AutoTestEngine.MAX_CONCURRENCY`
**5–10** (both CPU-scaled). This is only safe because each config now spins ~3
cores instead of ~5. If you ever restore the extra samples/verdict probes, you
MUST also drop these back or low-RAM devices will OOM (the v4.7 crash). The wide
**socket** waves (`TcpProbe.MAX_CONCURRENCY = 48`) are unrelated and stay wide.

### PING ALL must never be emptied by a momentary link blip

`PingService.pingAll()` wave 1 (TCP pre-gate) may reject a config only when it ALSO
let at least one other through. If the pre-gate rejects **every** node (`anyLive
== false` — a transient drop, not 200 dead nodes), the whole list is handed to the
deep prober unchanged instead of being marked Unreachable. This is the fix for
«Ping All می‌پرد و هیچ کانفیگی پینگ نمی‌گیرد» in My Configs. Do not "simplify" it
back to unconditionally trusting wave 1.

---

## 6. ADMIN PANEL (`adminpanel/`)

- `index.html` + `app.js`, fully client-side, no server.
- Login: user/pass are stored **only as SHA-256 hashes** in `app.js`. The raw
  credentials are never in source.
- Tabs: **Tracking**, **Links**, **Home** (with a live phone preview), **Donate**,
  **Download Links** (v6.3), **Notifications** (v6.3), **Preview**, **Publish**.
- Publish writes `app_config.json` to the panel repo via the GitHub Contents API
  using a token the operator pastes at runtime (kept in `localStorage`, never
  committed).
- The panel `README.md` must contain **only the panel link** — no instructions, no
  credentials, no token. Keep it that way.

---

## 7. BUILD

- The sandbox has **no Android SDK / JDK preinstalled**. The authoritative APK is
  built by **GitHub Actions** (`.github/workflows/build.yml`). A local toolchain can
  be provisioned into `.androidenv/` (JDK 17 + `platforms;android-34` +
  `build-tools;34.0.0`) with `local.properties → sdk.dir`; both are **gitignored**
  and must never be committed.
- Output: `ProfessorVPN-v<version>-universal.apk`. The workflow reads the version
  **dynamically** from `app/build.gradle.kts` (`versionName`), clears **all** old
  `build/*.apk` (`rm -f build/*.apk`) before copying the freshly-built one, commits
  the new artifacts back to `main`, and publishes a GitHub Release tagged
  `v<version>` with the APK attached.
- Version lives in `app/build.gradle.kts` (`versionCode` / `versionName`). Bump both
  together when releasing. The CI artifact name follows automatically.
- Signing: `neonvpn.keystore` (alias `neonvpn`). The password is read from the
  `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` env (CI secrets) with a dev
  fallback so source contains **no hardcoded secret**, while the same release key
  is preserved so existing users can still update.

---

## 8. CHECKLIST BEFORE YOU COMMIT (run through this every time)

- [ ] Did I keep the post-connect **health check**? (internet off ⇒ not connected)
- [ ] Are all stats still **real** (no `Random`)?
- [ ] Only **vless/vmess** added when parsing configs?
- [ ] No **ad-network scripts** added to the app?
- [ ] No **hardcoded credentials/tokens**?
- [ ] If I changed the config JSON, did I update **all three** (Kotlin / panel / json)?
- [ ] Did I bump `versionCode` **and** `versionName` if releasing?
- [ ] Panel `README.md` still only the link?

If any box is unchecked, **fix it before committing.**
