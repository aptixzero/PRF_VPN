# Professor VPN — v6.7

**Auto Test that actually tests. Real ranked sources, fast triage, low-ping
configs, and a barcode that opens the browser.**

APK: `build/ProfessorVPN-v6.7-universal.apk` (universal — arm64-v8a, armeabi-v7a,
x86, x86_64)
versionCode `48` · versionName `6.7`

---

## The report this release answers

> «الان توی بخش free می‌زنیم اتو تست … می‌گردد رندوم منبع پیدا می‌کند اضافه
> می‌کند، خیلی طول می‌کشد پینگ بدهد و پینگ‌هایی که می‌دهد بالای ۲۵۰ است — خیلی
> ضعیف، اصلا نمی‌شود استفاده کرد.»

Four separate defects were hiding behind that one sentence, and v6.7 fixes each
of them at its root rather than tuning a number.

---

## 1. The 0 → 60 % bar now performs a REAL, RANKED connection test

**What was wrong.** Phase 1 walked the source feeds **one at a time** and stopped
at the **first** one that answered. Two consequences, both bad:

* The winner was an accident of list order and of whichever feed happened to
  reply first. There is no `Random()` anywhere in the code, but from the user's
  seat the outcome was indistinguishable from a coin toss — which is exactly why
  they described it as *«رندوم منبع پیدا می‌کند»*. It was a fair description of a
  real defect.
* It tested the wrong thing entirely. Downloading a text file from
  `raw.githubusercontent.com` says **nothing** about whether the servers *inside*
  that file work for this user. The bar measured GitHub's availability, then
  handed back a batch of nodes that had never been checked against the user's own
  connection.

**What v6.7 does instead**, per the brief *«باید از ۰ تا ۶۰ درصد اتصال کاربر رو
با منابع بررسی کنی … کدام منابع واقعا وصل می‌شوند و کدام پینگ بهتری می‌دهند»*:

| Step | What happens | Why |
|---|---|---|
| **A** | **10 feeds per kind fetched in parallel**, each fetch individually timed, each capped at 9 s | v6.6 paid the slowest feed's latency *serially*, over and over. This is most of the wait, gone. |
| **B** | From each reachable feed, **6 nodes sampled evenly across the whole list** and given a **real TCP handshake from this device** (40-wide) | This is the measurement the brief asked for: which sources genuinely connect **for me**, and which are **faster for me**. Bare sockets, so it finishes in seconds. |
| **C** | Feeds **ranked and the best one bonded**, per kind | Phase 2 then pulls its 240 configs from the feed measured fastest and most reliable for this user. |

The ranking score is built only from measured values:

```
score = median(measured handshake ms of the live samples)
        × (1 / hit-rate)          ← a feed where 2 of 6 answered is 3× worse
                                     than an equally fast feed where all 6 did
        + (measured feed download ms / 10)
```

Sampling is **evenly spread** across each feed rather than taking the first *N* —
the head of these lists is usually the same stale block in every feed, so
head-sampling would have scored them all alike.

A feed must have **at least 2 of its 6 samples connect** before the app is
willing to bond to it. One lucky node out of six is not a working source, and
bonding to it is how v6.6 ended up serving dead batches.

The retry window also **rotates**: a round that finds nothing inspects a
*different* set of feeds next time instead of re-testing the same dead ones.

---

## 2. Auto Test now triages fast-first, so low-ping configs arrive in seconds

**What was wrong.** The batch went straight into the deep prober in whatever
order the feed printed it. With only 3–6 native-core permits, a 240-config batch
meant minutes of waiting — and the nodes that could have given a 100 ms ping were
tested **last**, if the cycle reached them at all.

**v6.7** spends ~2 seconds up front on a wide wave of bare TCP handshakes
(48 concurrent) that does two things:

* **Drops** every config that accepts no connection at all. On a public feed
  that is most of them, and each one dropped is a multi-second deep probe never
  run.
* **Orders the survivors by their real measured handshake time.**

Why ordering by the handshake is sound and is **not** a fake ping: the tunnel
round trip *physically contains* the TCP round trip to the same host, so the
measured door time is a hard **lower bound** on the ping that node could ever
report. A node whose front door is 900 ms away can never produce a 200 ms tunnel
ping; one that answers in 60 ms is the only kind that can. Ordering by a genuine
lower bound is precisely the right way to reach the fast nodes first.

It changes only **when** a config is measured — never **what** is reported.

The same fastest-first ordering was applied to the manual **PING ALL** sweep, so
the top of the list fills with green rows almost immediately instead of after the
whole sweep finishes.

---

## 3. The acceptance bar: 8 000 ms → 2 500 ms

This is the direct cause of *«هر کانفیگی پینگ بالا می‌دهد و نمی‌شود به آن وصل
شد»*.

v6.6 copied a config into **My Configs** if it answered within **eight seconds**.
Eight seconds is not a working VPN — it is a node that technically completes a
handshake and then makes every page load feel broken. Because Auto Test runs
unattended for hours, the user's list filled up with exactly those nodes, so
every config they tapped was slow. The engine was faithfully doing what it was
told; it was told the wrong thing.

**2 500 ms** is the honest ceiling for "worth keeping". It sits well above a good
tunnel ping on an Iranian link (the nodes this build surfaces first land in the
80–400 ms band), so nothing genuinely usable is discarded, while everything that
produced the reported misery is refused.

This is a **stricter** filter than `Pinger`'s own `MAX_VALID_MS`, never a looser
one — Auto Test may only ever accept a **subset** of what a manual ping accepts,
so it can never mark something good that a manual ping would reject.

Accepted configs are also written into **My Configs sorted ascending by their
real measured ping**, so the first row — which is what the app auto-selects — is
the best node found.

The notification now reports real quality, not just a tally:
`اتو تست روشن است · 34 کانفیگ سالم (21 کم‌پینگ) · آخرین: 142ms`

---

## 4. Twenty more sources — added, none removed

Per *«اگه منابع کم هست چندتا منابع دیگه هم اضافه کن … فقط vless , vmess …
قبلی‌ها رو پاک نکن»*:

**50 → 70 feeds.** All 20 additions are `vless` / `vmess` only, and **every URL
was verified with a live HTTP request before being added** — each returns a
non-empty body containing real links (several are base64 subscription blobs,
which the fetcher already decodes). The original 50 are **untouched**: v6.7 only
grows the pool.

New feeds include `mheidari98/.proxy` (1317 vless / 880 vmess),
`Surfboardv2ray/Proxy-sorter` (1134 / 65), `ALIILAPRO/v2rayNG-Config` (427 / 139),
`Epodonios/All_Configs_Sub` (1030 vless), `mahdibland/V2RayAggregator` (866
vmess), `Leon406/SubCrawler` (561 vless), and others.

Because phase 1 now *ranks* feeds instead of taking the first that answers, more
sources directly means better nodes rather than just more noise.

---

## 5. The barcode opens the browser

Reported: *«زمانی که اون بارکد رو اسکن کرد باید خودکار منتقل بشه به مرورگر»* —
scanning copied the link instead of opening it.

Three distinct causes, all fixed:

1. **The payload was not a URL.** Operators routinely paste `example.com/app.apk`
   with no scheme. A QR payload without a scheme is, to every phone camera, just
   **text** — and a camera that decodes text offers *copy*, because it has no way
   to know it is a web address. The download-link fallback is now normalised to an
   explicit `https://…`, exactly as the panel link already was. The operator's
   address is otherwise untouched: no query parameters, no `bt=1`, no tracking.

2. **Android 11 package-visibility filtering.** Without a `<queries>` declaration
   an app cannot see, resolve, or launch another package's activity. So
   `startActivity(ACTION_VIEW, https://…)` threw `ActivityNotFoundException` on
   strict devices — and the old code swallowed that exception **in silence**. The
   user tapped, nothing happened, and only COPY ever seemed to work. The manifest
   now declares the browsable `http`/`https` view intents.

3. **No fallback.** `openInBrowser` now tries the direct view, then a
   system-resolved chooser, and only then copies to the clipboard **and says so** —
   never silence.

Additionally: on a phone that already has the app installed, the system can hand
a scanned `professorvpn://get` code to **us** instead of to a browser. If that
link carries a destination we no longer swallow it — we forward it straight to
the browser, so behaviour is identical with or without the app installed.

---

## Everything is real — Golden Rule #2 holds

Per *«هیچوقت هیچ داده‌ای نباید رندوم باشه، همه آمار‌ها واقعی باشه»*:

* Every value in this release is a `System.nanoTime()` measurement taken on the
  spot from the user's own connection. No estimate, no synthesis, no cached
  guess. The instrumented `NoRandomInStatsTest` still scans the ping/stats
  sources and still passes.
* **No proxies** anywhere in the path — *«برای پینگ گرفتن اصلا از پروکسی استفاده
  نکن»*. Feeds and probes go through the shared proxy-free client
  (`Proxy.NO_PROXY`) with **Cloudflare DoH** resolution, which defeats the ISP
  DNS poisoning that made a proxy look necessary in the first place.
* **Cloudflare only, never Google** — `cp.cloudflare.com/generate_204`,
  `1.1.1.1/cdn-cgi/trace` (IP literal, zero DNS), `speed.cloudflare.com`.
* The TCP measurement introduced here may **only reject or order**. It is the
  latency to the node's front door, not through the tunnel, so it is **never
  displayed and never stored as a ping**. Every number the user sees still comes
  from the full three-stage `Pinger` pipeline, including the v6.6 payload verdict
  (a fresh connection carrying real bytes) — which is what keeps the promise that
  a config showing 90 ms genuinely behaves like 90 ms.

---

## Files changed

| File | Change |
|---|---|
| `config/LiveSources.kt` | 50 → 70 verified vless/vmess feeds; none removed |
| `config/ConnectivityProbe.kt` | Rewritten phase 1: parallel fetch → real node measurement → ranked bond |
| `config/AutoTestEngine.kt` | Triage wave; `WORKING_MAX_MS` 8000 → 2500; fastest-first flush; fast counter |
| `config/TcpProbe.kt` | New `connectMs()` — the measured (reject/order-only) form of the pre-gate |
| `config/PingService.kt` | Deep wave ordered fastest-first by measured handshake |
| `ui/DownloadsActivity.kt` | Browsable payload normalisation; 3-stage `openInBrowser`; scan hand-off |
| `AndroidManifest.xml` | `<queries>` for `http`/`https` browsable view intents |
| `net/DirectHttp.kt` | UA → 6.7 |
| `app/build.gradle.kts` | versionCode 48, versionName 6.7 |
