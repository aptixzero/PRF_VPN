# Professor VPN — v6.5

**versionName `6.5` · versionCode `46` · universal APK (arm64-v8a, armeabi-v7a, x86_64, x86)**

v6.5 is a *reliability* release. It does not add servers, protocols or screens —
it removes the four things that made v6.4 feel unreliable:

1. the app connected once and then refused to connect again (the "cache" bug),
2. the connection stalled after ~30 seconds of video,
3. a config could show a green ping and still refuse to connect,
4. the barcode on the Download-Links page was mathematically undecodable.

All four had a single, findable root cause. This document records what each one
actually was, because "made it more stable" is not a fix and cannot be verified.

---

## 1 · The reconnect bug — connect works once, then never again

### What the user saw

> «اولین بار وصل می‌شود، بار دوم و سوم دیگر وصل نمی‌شود. پینگ نشان می‌دهد،
> کانکت را می‌زنی، وصل نمی‌شود.»

Connect → works. Disconnect → connect again (same or different config) → the
button animates, the notification appears, and no traffic ever flows. Killing
the app from Recents was the only cure. That is the signature of leaked state,
not of a bad server — hence the user calling it a "cache" problem.

### Root cause — four independent races, all in the teardown path

**(a) `XrayManager.start()` short-circuited on an already-running core.**

```kotlin
// v6.4
fun start(configJson: String): Boolean {
    if (isRunning) return true      // <-- silently ignores the NEW config
    …
}
```

Switching configs called `start(newJson)` while the old core was still up. The
function returned `true` — reporting success — without ever loading the new
JSON. The app was still tunnelling through the *previous* server, or through a
core that was mid-shutdown. This alone explains "I picked a different config and
it behaved exactly like the old one".

v6.5 removes the short-circuit entirely. `start()` now *always* tears the old
core down first, waits for the local ports to actually come free, and builds a
fresh `CoreController`:

```kotlin
fun start(configJson: String): Boolean = synchronized(lifecycleLock) {
    stopLocked()                 // unconditional
    waitForLocalPortsFree()      // 10808 / 10809 must be bindable again
    controller = Libv2ray.newCoreController(callback)
    totalUp = 0; totalDown = 0   // stats belong to THIS session
    controller!!.startLoop(configJson, 1)
}
```

**(b) Start and stop ran on detached threads with no handshake.**

v6.4 spawned `Thread(name = "vpn-stop")` and `Thread(name = "vpn-start")`. When
the user tapped disconnect-then-connect quickly (or tapped a different config,
which does both), the two threads ran *concurrently*: the new core tried to bind
`127.0.0.1:10808` while the old core still held it. `startLoop` failed, the
service reported "connected" because the TUN was up, and nothing routed.

v6.5 funnels **every** lifecycle request through one single-threaded executor,
so a start can never begin before the preceding stop has finished:

```kotlin
private val sessionExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "vpn-session").apply { isDaemon = true }
}
```

**(c) `TProxyService` (hev-socks5-tunnel) had no run-state.**

It was a bare object wrapping two JNI calls. Nothing stopped two native tunnel
sessions from existing at once on the same (or a closed) file descriptor. v6.5
tracks the native run-state and drains a previous session before starting a new
one:

```kotlin
@JvmStatic fun startBlocking(configPath: String, fd: Int): Boolean {
    if (nativeRunning) { stopAndWait(1500) }   // drain, don't overlap
    …
}
@JvmStatic fun stopAndWait(timeoutMs: Long = 2500): Boolean { … }
```

`stopAndWait` deliberately does **not** hold the lock while polling — holding it
would deadlock against the `startBlocking` thread that must observe the flag
change in order to exit.

**(d) A stale keep-alive thread resurrected the dead session.**

The tun2socks watchdog from session *N* survived into session *N+1* and happily
called `restartTun2Socks()` on session N's already-closed descriptor. v6.5 gives
every session a monotonically increasing generation, and every worker thread
checks it:

```kotlin
@Volatile private var generation = 0
private fun isStale(myGen: Int) = myGen != generation || stopping
```

Threads are named `watchdog-$gen` / `stats-$gen`, so a stale thread is visible in
a logcat trace instead of being invisible.

### The ordered teardown

Order matters: stopping the core before the tunnel leaves hev writing into a
dead SOCKS port; closing the TUN first makes the native tunnel spin on `EBADF`.

```
teardownSession(reason):
  1. interrupt watchdog + stats threads     (stop new work being scheduled)
  2. TProxyService.stopAndWait(2500)        (native tunnel first)
     tunnelThread.interrupt(); join(600)
  3. xray.stop()                            (then the core)
  4. tunInterface?.close()                  (then the fd nobody is using)
  5. unregisterNetworkCallback(); releaseWakeLock()
```

### Result

Connect / disconnect / switch config, repeatedly, without closing the app —
including leaving the app, returning, pinging, and connecting to the next
config. Every request is queued and ordered; nothing overlaps.

---

## 2 · Stalling — "first 3 Instagram videos load, then it freezes"

Two separate causes.

**QUIC / HTTP-3.** Instagram opens HTTP-3 (UDP 443) as soon as it can. Those
flows do not survive the TUN cleanly, so the app kept waiting on a transport
that was never going to answer, while TCP was fine. v6.5 keeps the v6.4 policy
of routing UDP 443 and `protocol: ["quic"]` to blackhole, which forces a fast
fallback to TCP instead of a 30-second hang.

**Congestion control on lossy mobile links.** The default loss-based controller
reads Iranian mobile packet loss as congestion and collapses the window — which
is *exactly* the "videos keep cutting out" symptom. v6.5 sets, per-outbound:

```kotlin
sockopt.put("tcpcongestion", "bbr")     // loss-tolerant, model-based
sockopt.put("domainStrategy", "UseIPv4")
```

BBR paces from measured bandwidth × RTT rather than treating loss as a stop
signal, so a few percent loss no longer stalls the stream.

**No time or type limits.** Per the requirement «هیچ محدودیتی روی زمان اتصال یا
نوع اتصال نباشد»: there is no session timer anywhere. `TUN_MAX_RESTARTS = 20`
bounds *consecutive failures*, and the counter resets after 120 s of healthy
life, so a long-lived healthy session can never exhaust it. `policy.handshake`
was raised 12 → 16 s so a slow ISP is given time rather than being cut off.

---

## 3 · "It pings but won't connect"

The list-side ping and the live-connect gate were measuring different things
with different budgets, so a node could pass one and fail the other:

| | v6.4 list ping | v6.4 live gate |
|---|---|---|
| budget | 12 s | 8 s |
| attempts | 4 samples | 4 |
| accepted | ≤ 8 s | ≤ ~2 s effective |

A node at 3–5 s showed a green ping and then failed to connect. Fixed from both
ends:

**Live gate is no longer stricter than the ping.** `CONNECT_VERIFY_BUDGET_MS =
14_000` with a ramped inter-probe gap (300 → 900 ms), and from attempt 3 it also
accepts a `probeDevicePath()` success — a real packet through the local SOCKS5
proxy, which is stronger evidence than a latency echo.

**The ping is no longer fooled by a node that answers one burst.** Some servers
accept the first handshakes and then die. v6.5 adds a *sustain check*: after the
initial samples pass, wait, then probe once more on the same connection.

```kotlin
if (samples.size < 3) {
    delay(SUSTAIN_PAUSE_MS)                 // 700 ms
    val sustain = singleProbe(json, refUrl)
    if (sustain !in 1..MAX_VALID_MS) return@withTimeoutOrNull UNREACHABLE
    samples.add(sustain)
}
```

A node that collapses is now marked unreachable *before* the user taps connect,
instead of showing green and failing.

---

## 4 · The barcode — it was genuinely undecodable

### What the user saw

> «بارکد بخش لینک‌های دانلود کار نمی‌کند.»

Scanning it with another phone did nothing. Not the camera, not the size, not
the lighting: the codes v6.4 drew could not be decoded by *any* scanner.

### Root cause — one expression, in `QrCode.interleave()`

In ISO/IEC 18004, `shortBlockLen` is the **total** length of a short block: data
codewords **plus** that block's error-correction codewords. v6.4 subtracted the
EC codewords *before* dividing, and then subtracted `eccLen` a **second** time
when deriving each block's data length:

```kotlin
// v6.4 — WRONG
val shortBlockLen = (rawCount - totalEcc) / numBlocks
…
val len = shortBlockLen - eccLen + (if (i < numShortBlocks) 0 else 1)
//                      ^^^^^^^^ eccLen removed twice
```

Every block came out `eccLen` codewords short, so only a fraction of the matrix
was ever written. The finder patterns, timing patterns and format info were all
perfect — so it *looked* like a flawless QR code — but the data region was
partly blank. Scanners locate the code, read the format bits, attempt
Reed–Solomon, fail, and give up silently.

```kotlin
// v6.5 — CORRECT
val shortBlockLen = rawCount / numBlocks
val numShortBlocks = numBlocks - rawCount % numBlocks
```

### Verified, not assumed

The encoder was ported to Python with a `buggy` switch reproducing the v6.4
expression, and a from-scratch ISO 18004 **decoder** (with the Reed–Solomon
syndrome check — the exact step a phone fails on) was written as a test oracle:

```
--- v6.4  shortBlockLen = (rawCount - totalEcc)/numBlocks   [BUG] ---
p1   filled=69/70   UNSCANNABLE -> RS check FAILED on block 0
p3   filled=131/134 UNSCANNABLE -> RS check FAILED on block 0
p4   filled=44/70   UNSCANNABLE -> invalid UTF-8 continuation byte
p5   filled=13/26   UNSCANNABLE -> unexpected mode 0000
result: all decodable = False

--- v6.5  shortBlockLen = rawCount/numBlocks                [FIX] ---
p1   v3  filled=70/70   DECODED OK
p3   v5  filled=134/134 DECODED OK
p4   v3  filled=70/70   DECODED OK
p5   v1  filled=26/26   DECODED OK
p6   v9  filled=292/292 DECODED OK
p7   v4  filled=100/100 DECODED OK   (Persian URL, UTF-8)
result: all decodable = True

VERDICT: FIX CONFIRMED
```

`filled=44/70` is the bug in one number: 26 of 70 codewords never written.

A permanent guard now makes this class of regression impossible to ship
silently — the caller renders nothing rather than a dead barcode:

```kotlin
require(idx == rawCount) {
    "QR interleave produced $idx of $rawCount codewords (v$version) — " +
        "the code would not be scannable"
}
```

### Also changed on the app side

- **ECC raised to QUARTILE** (~25 % damage tolerance, up from MEDIUM's ~15 %) —
  more headroom for a camera pointed at a glossy phone screen. Falls back to
  MEDIUM if a very long link needs the capacity.
- **The payload is now the operator's link, verbatim.** v6.4 wrapped it in a
  `professorvpn://get?bt=1` deep link, which — because `DownloadsActivity` is
  exported with a `BROWSABLE` filter for that scheme — meant a scan could be
  swallowed by the app itself instead of opening a browser. The QR now carries
  the plain `https://…` URL, so any scanner opens it in the browser, which is
  what was asked for.
- The QR is tappable and re-renders when the panel config refreshes.

---

## 5 · New admin-panel section — «لینک پشت بارکد»

A new tab between **Downloads** and **Notice**:

- a URL box (LTR) for the link that should sit behind the barcode,
- **«ساخت بارکد»**, which normalises the link, generates the code and previews it,
- an optional Persian caption shown under the barcode in the app,
- an on/off switch,
- a live 320 px preview plus a **PNG download** of the generated code.

Two details worth stating explicitly:

**The preview uses the app's own encoder.** `adminpanel/qrcode.js` is a port of
the fixed `QrCode.kt`, so the code the operator approves is module-for-module the
code the app renders. Using a QR *service* or a different library would allow the
panel to show a working barcode while the app draws a broken one — precisely the
v6.4 situation. A headless test confirms the canvas is pixel-identical to the
encoder's matrix, and no third-party request is made (the panel is on GitHub
Pages and must survive a CDN outage).

**Scheme-less input is accepted.** Typing `example.com/x` publishes
`https://example.com/x`; the normalised form is written back into the box so what
the operator sees is exactly what is published and encoded. `tg:`, `mailto:` and
other schemes pass through untouched. `normalizeLink()` in JS and
`QrLinkConfig.normalizedUrl()` in Kotlin implement the same rule, so panel and
app can never disagree.

Config shape (`qrcode` is a legacy alias so an older build still finds it):

```json
"qrLink": {
  "enabled": true,
  "url": "https://professorvpn.vercel.app/",
  "caption": "این بارکد را با دوربین گوشی اسکن کنید"
}
```

---

## Files changed

| File | Change |
|---|---|
| `service/NeonVpnService.kt` | session state machine: `sessionExecutor`, `generation`, `pendingConnects`, `sessionEpoch`, `isStale`, `requestStop`, ordered `teardownSession`, new health gate, generation-scoped workers |
| `service/XrayManager.kt` | removed the `if (isRunning) return true` short-circuit; `stopLocked`, `waitForLocalPortsFree`, `isPortFree` |
| `com/v2ray/ang/service/TProxyService.kt` | native run-state; `startBlocking`, idempotent `stopAndWait` |
| `config/XrayConfigBuilder.kt` | `handshake` 12 → 16; `tcpcongestion: bbr`; `domainStrategy: UseIPv4` |
| `config/Pinger.kt` | sustain check (`SUSTAIN_PAUSE_MS`) |
| `ui/ConnectFragment.kt` | publishes `STATE_CONNECTING`; defensive intent delivery |
| `util/QrCode.kt` | **interleave fix** + `require(idx == rawCount)` guard |
| `ui/DownloadsActivity.kt` | verbatim link payload, QUARTILE ECC, caption, tappable, redraw on refresh |
| `config/DownloadLinks.kt` | new `QrLinkConfig` |
| `config/RemoteConfig.kt` | `qrLink` field + `qrLink`/`qrcode`/`barcode` parse aliases |
| `adminpanel/qrcode.js` | **new** — port of the fixed encoder |
| `adminpanel/index.html` | «لینک پشت بارکد» tab |
| `adminpanel/app.js` | `refreshQrPreview`, `normalizeLink`, wiring, `PUB_REPO` corrected to `aptixzero/my_prFF_vP_N` |
| `adminpanel/app_config.json` | `version` 22, `latestApkVersion` 6.5, `qrLink` block |
| `app/build.gradle.kts` | `versionCode 46`, `versionName "6.5"` |

## Invariants preserved

No `Random`/fake statistics · vless & vmess only · Cloudflare-only latency
probes · QUIC stays blackholed · `bufferSize 512`, `connIdle 120` · IPv4-only TUN
· bounded hev timeouts · `QrCode.kt` remains dependency-free (no ZXing) · no
hardcoded tokens.
