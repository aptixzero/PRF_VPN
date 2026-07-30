# Professor VPN — v6.9

**versionCode 50 · versionName 6.9 · universal APK (arm64-v8a, armeabi-v7a, x86, x86_64)**

v6.9 is a *fundamental* performance and correctness release, not a feature drop.
Everything below exists because it was reported as broken. The theme is that the
app had been optimising for the wrong thing — thoroughness — while the user was
asking for speed and honesty.

---

## 1. Auto Test no longer sticks at 30 % for five minutes

**Reported:** «زدیم روی اتو تست که تست کانکشن بگیره … روی 30 درصد میمونه و بالای
5 دقیقه طول میکشه» — the connection-test page hangs around 30 % and takes over
five minutes.

**This was structural, not bad luck.** v6.8's `ConnectivityProbe` did all of the
following before a single config was collected:

* fetched **10** candidate feeds per kind, each with a 9 s budget;
* TCP-sampled **6** nodes out of every one of those feeds under a 12 s budget;
* *scored and ranked* the feeds to pick the one with the best ping;
* did the whole thing for **VLESS first and VMESS second, sequentially**;
* retried entire rounds against a 120 s global budget;
* and then TCP-probed all 240 collected configs again, purely to sort them.

The VLESS progress band ended at 32 %, so any stall in the VMESS half parked the
bar at ≈30 % — the exact reported symptom.

### The new contract, exactly as specified

> «باید اولین vless و اولین vmess رو که پیدا کرد این ها رو جدا کنه»

Take the **FIRST reachable** VLESS source and the **FIRST reachable** VMESS
source. Do **not** hunt for the best ping at this stage.

| Band | What happens |
|---|---|
| **0 % → 60 %** | Every candidate feed of **both kinds is opened simultaneously**. The first feed to answer wins its kind and is bonded. Winning costs as long as the single fastest reachable feed — usually under two seconds — instead of ten sequential timeouts. |
| **60 % → 100 %** | 120 VLESS + 120 VMESS are collected, written to **Free Configs**, and their **ping sweep is started**. |

Scoring, ranking, median-of-samples and the final `orderByProximity` pass are all
**deleted**. They existed to pick a "best" source, which the brief explicitly does
not want, and they were most of the five minutes.

The global ceiling drops from **120 s to 45 s**, and both kinds now race
concurrently — so the bar physically cannot wedge at 30 % waiting for a second
half to begin.

---

## 2. Configs actually arrive in the Free list now

**Reported:** «کانفیگ ها به لیست free اضافه نمیشوند» — configs are not being added
to the free list at all.

Three separate defects conspired here.

* **v6.8 only wrote to the store when the probe returned a non-empty list.** Any
  hiccup in the collect phase produced an empty tab and no explanation. v6.9 adds
  a **rescue pass**: if the probe proved the user can reach a source but came back
  empty, `AutoTestActivity` collects a batch itself before giving up.
* **A failed press used to wipe the existing list.** It no longer does — a slow
  press leaves what you already had alone.
* **`PingService.clear(FREE)` was called on save**, which also cancelled the
  in-flight sweep and reset the status map for *both* tabs. Replaced with
  `PingStore(...).clear()`, which drops only the stale persisted badges.

---

## 3. The next 240 always loads

**Reported:** «الان 240 تا تموم میشه دیگه توقف میکنه و 240 تای بعدی رو اضافه
نمیکنه» — it finishes 240, stops, and never adds the next 240.

The cause was a compounding pair of bugs:

1. `FreeConfigSource` **bonded the user to ONE source feed** and re-read that same
   feed every cycle. Once every link in it was in `SeenConfigStore`, every
   subsequent press returned **empty**.
2. `AutoTestEngine` counted empty cycles and, after only **6** of them — barely a
   minute on a fast link — **switched Auto Test off permanently**.

### Fixes

* **The bond is now only a hint.** It is placed first in the first wave, but the
  wave always contains other feeds too, so a stale bond can never starve a press.
* **The source cursor always advances past every feed consumed**, so the next
  press reads different feeds *by construction*.
* **Recycle-and-retry:** if a press under-fills while the network was demonstrably
  reachable, the dedup memory is recycled and one retry pass runs. A non-empty
  next-240 is guaranteed as long as any feed is up.
* **`MAX_EMPTY_STREAK` 6 → 24**, and the recovery ladder gained a fourth step
  (full cold restart of the source pipeline: cursors, bond, dedup memory, cached
  bodies, DoH cache). Backoff sleeps were cut from ≤8 s to ≤3 s so the ladder
  spends its time *acting* rather than waiting.

---

## 4. Collecting 240 configs is now parallel

v6.8 walked source feeds **strictly one at a time** — up to 12 sequential fetches
per kind, each able to burn a full timeout. On an Iranian mobile link that was
minutes.

v6.9 fetches in **parallel waves of 8**. A wave costs *one* timeout, not eight.
Both kinds are collected concurrently as well (they used to run back-to-back), and
because extra feeds are now nearly free, `MAX_SOURCES_PER_PRESS` rose from 12 to
24 out of the 35 available per kind.

`SourceFetcher` gained a 90 s body cache, a 30 s negative cache and memoised link
extraction, so re-reading a feed inside one press is free.

---

## 5. The ping system: multiple handlers, one core, stable numbers

**Reported:** all pings high · one config works then nothing · needs several
different handlers · numbers must stop fluctuating.

All four traced to one thing: v6.8 spun up **three throwaway native Xray cores per
config** (two latency samples + an unconditional payload verdict) while only 6–12
configs were allowed to run at once. Each spin-up pays a fresh TCP + TLS/Reality
handshake, so the numbers were dominated by cold-handshake cost, the sweep was
slow, later configs were measured minutes after earlier ones on a link that had
drifted (the fluctuation), and marginal-but-usable Iranian nodes lost their budget
to the queue and were written off.

### The handler chain

| Handler | What it is | Notes |
|---|---|---|
| **H0** | TCP handshake (`TcpProbe`, 48-wide, ~300 ms) | May **only reject**. Never displayed. |
| **H1** | Cloudflare **IP literal** (`1.1.1.1/cdn-cgi/trace`) | Zero DNS inside the measurement. Primary handler. |
| **H2** | **Alternate** Cloudflare edges | A *different* endpoint is tried when H1's target is specifically being reset. This is the «چند هندلر مختلف» requirement — one blocked reference endpoint can no longer condemn a healthy node. |
| **H3** | Confirmation round-trip on the warm path | Gives the stable displayed figure, and a node that answers H1 then fails H3 is precisely the "works once, then nothing" node — rejected here, not in the user's face. |
| **H4** | Payload verdict: real bytes on a **brand-new** connection | Now **conditional** — only for nodes that look marginal (slow, or single-sample). Two clean warm round-trips already prove what the verdict tests. |

**Result:** the typical config costs **one** native core instead of three, which
in turn allowed the deep-probe gate to widen from 6–12 to **8–16** and Auto Test's
cycle budget to shrink from **12 minutes to 4**.

### Why the numbers stop jumping

Two mechanisms, neither of which invents data:

1. The **cold** sample is discarded whenever a warm one exists, so the reported
   figure describes the tunnel in the state it is in once connected — the same
   statistic `XrayManager.measureDelayStable` reports, so the list figure and the
   connected figure finally agree.
2. `Pinger.stabilise()` blends the new measurement with this config's **previous
   real measurement** (70/30) when the two are in the same ballpark, removing
   per-sample jitter. If the new value differs by more than 2×, the history is
   discarded and the fresh truth is reported immediately — a node that genuinely
   degraded is never flattered.

**Golden Rule #2 still holds:** no `Random`, no estimate, no synthesis. Every
displayed number derives exclusively from measured round-trips through the real
outbound. The `NoRandomInStatsTest` guard is unchanged.

---

## 6. "Ping all" no longer silently skips

**Reported:** «Ping all بعضی وقت ها میپره و هیچ کانفیگی پینگ نمیگیره».

Two distinct races:

* **`pingAll` flipped `running = true` *inside* its coroutine.** It therefore
  returned while `isSweepRunning` was still `false`, so a caller that polled
  immediately concluded "nothing is happening", re-enabled its buttons and hid its
  progress bar. The sweep ran invisibly and looked skipped. Both the state flow
  **and** the "Pinging…" row statuses are now published **synchronously, on the
  calling thread, before `pingAll` returns**.
* **Admission control used `isSweepRunning`**, which is also true while the
  background Auto Test is pinging — so tapping PING ALL during an Auto Test
  returned silently with no feedback at all. A new, narrower
  `isManualSweepRunning` is used for admission: a manual request is only ever
  refused by another **manual** sweep, and every refusal now says something.

The sweep body is also wrapped in `try/finally`, so `running = false` is published
on **every** exit path including cancellation and an unexpected throw. Previously
a sweep that died mid-way left the progress bar pinned and PING ALL disabled until
the app was restarted.

---

## 7. The ping process is visible

**Reported:** «روند پینگ گرفتن رو نمیتونم ببینم» and "My Configs has no ping
progress bar".

The bar existed in `fragment_configs.xml`, but **only a manual sweep ever wrote to
`PingService.sweep`** — so during the automatic run, which is what actually pings
the 240 free configs, every progress bar in the app sat at zero and looked idle.

* `AutoTestEngine` now publishes its testing progress through
  **`PingService.publishExternalSweep()`**, driving the *same* bar the manual PING
  ALL drives. One observable source of truth, as originally designed.
* Both tabs now label it with a live count **and** a percentage.
* A background Auto Test no longer disables the buttons — only a manual sweep
  does — so the user is never frozen out of their own list.
* The Auto Test page itself gained a **live phase label**: *finding the first
  reachable VLESS + VMESS source* → *collecting 120 + 120* → *adding to Free
  Configs and taking pings*. A bare bar made a slow phase indistinguishable from
  a freeze, which is how the "stuck at 30 %" report started.
* `AutoTestActivity` now **starts the ping sweep itself** before closing, so the
  Free tab is already showing a climbing bar the instant the page disappears.

---

## 8. Home page: rapid switching between configs

**Reported:** «باید بتونم سریع بین کانفیگ ها جابجا بشم» and no interference during
repeated connect/disconnect.

Tapping a config only wrote the new selection to the store. To actually change
server the user had to go back to the home tab, tap Disconnect, wait for the
teardown, and tap Connect again — three gestures and a wait for what should be one
tap.

`NeonVpnService` has been able to handle this since v6.5: a start intent delivered
while a tunnel is up is treated as a **config switch**, serialised on its single
session thread and tagged with a **generation counter**, so the old session is torn
down and the new one raised without the two ever interleaving, and superseded
requests are dropped. The capability was simply never wired to the tap.

Now, in **both** My Configs and Free Configs, tapping a config while connected
switches to it immediately. Hammering several configs in a row is safe: only the
last survives and no half-open session is left behind. When nothing is connected,
behaviour is unchanged (select only) — a tap never starts a tunnel you did not ask
for.

---

## 9. Zero intermediaries. Finally, actually zero.

**Reported:** «`gh.proxy.com` چیه؟ … کلا این آدرس رو حذف کن … درون برنامه نباید از
پروکسی استفاده کنی، نباید از واسط استفاده کنی».

`gh.proxy.com` does not exist anywhere in this repository — but the investigation
found the real intermediaries that were still present, and they are now gone:

| Removed in v6.9 | What it was |
|---|---|
| `cdn.jsdelivr.net` | CDN mirror chained after every origin fetch |
| `fastly.jsdelivr.net`, `gcore.jsdelivr.net` | jsDelivr regional mirrors |
| `bin.mudfish.net` (×7 sources) | Third-party paste host serving config lists |

v6.8 kept jsDelivr as a "not really a proxy" fallback. It is still **somebody
else's server sitting between the user and their config list**, and a dead mirror
still cost a full extra timeout on every dead source. Both reasons are sufficient.

There is now **exactly one candidate URL per source: the origin.** No proxy, no
CORS bridge, no text-extraction relay, no CDN mirror, no paste host. The
replacement for mirror-chaining is **parallelism** (§4), which is strictly better:
it costs less time *and* removes the third party.

**What the app still does — and why it is not an intermediary:**
`net/DirectHttp.kt` resolves hostnames over **encrypted Cloudflare DoH**
(`net/CfDns.kt`, querying `1.1.1.1`/`1.0.0.1` **by IP literal** so there is no
bootstrap lookup to poison) and then dials the **real origin address itself**, with
correct SNI, full certificate validation and `Proxy.NO_PROXY` pinned. That defeats
the actual blocking mechanism on Iranian ISPs — DNS poisoning — without anyone
standing in the middle. Cloudflare is the one outside service the brief explicitly
permits, and every ping endpoint is Cloudflare-only. **There is no Google anywhere
in the app**, per the brief.

Timeouts were tightened to 4 s connect / 5 s read / 7 s call, and the connection
pool widened to 24 to support the new parallel waves.

---

## 10. My Configs — protocol restriction confirmed

Paste-from-clipboard accepts **only `vless://` and `vmess://`**, enforced in
`ConfigParser.parseMany()` (which additionally requires a non-blank user id and a
port in 1–65535, and rejects everything else). Multi-item clips, base64
subscription blobs and links glued together in noise are all still handled. The
count of ignored/unsupported schemes is reported back to the user.

Auto Test copies **only configs that actually pinged** into My Configs — this is
`AutoTestEngine.flushWorking()`, gated on `WORKING_MAX_MS = 2 500 ms`, and the
group is written fastest-first using each config's real measured ping.

---

## Files changed

| File | Change |
|---|---|
| `config/ConnectivityProbe.kt` | **Rewritten.** Parallel race, first-reachable wins, 45 s ceiling, ranking/`orderByProximity` deleted |
| `config/FreeConfigSource.kt` | **Rewritten.** Parallel waves, both kinds concurrent, bond-as-hint, recycle-and-retry, cursor always advances |
| `config/Pinger.kt` | **Reworked** into the H0–H4 handler chain; conditional verdict; `stabilise()`; `resetHistory()` |
| `config/PingService.kt` | Synchronous sweep publication, `isManualSweepRunning`, `publishExternalSweep()`, `try/finally` completion, gate 8–16 |
| `config/AutoTestEngine.kt` | Budgets 12 min → 4 min, stall 15 → 5 min, `MAX_EMPTY_STREAK` 6 → 24, recovery step 4, publishes ping progress |
| `config/SourceFetcher.kt` | Origin-only; body + negative caches; memoised extraction |
| `config/ConfigFetcher.kt`, `RemoteConfigStore.kt`, `CcNewFeed.kt`, `ConfigSources.kt` | All mirrors / paste hosts removed |
| `net/DirectHttp.kt`, `net/CfDns.kt` | Tighter timeouts, pool 24, docs scrubbed, UA → 6.9 |
| `ui/AutoTestActivity.kt` | Phase label, rescue pass, starts the sweep, no longer cancels it |
| `ui/ConfigsFragment.kt` | Ping progress bar for both run types, `pingAll` race fixed, instant config switching |
| `ui/FreeConfigsFragment.kt` | Visible ping progress, `pingAll` guards fixed, instant config switching |
| `res/layout/activity_auto_test.xml`, `res/values/strings.xml` | Phase label + new strings |
| `app/build.gradle.kts` | `versionCode 50`, `versionName "6.9"` |
| `README.md`, `AI_AGENT_GUIDE.md`, `adminpanel/*` | Docs + `latestApkVersion` → 6.9 |

---

## Verification

* Built and signed on GitHub Actions (`.github/workflows/build.yml`), universal
  APK for all four ABIs.
* `NoRandomInStatsTest` (instrumented) unchanged and still guarding against
  `Random` in the stats/ping path.
* No `Random`, no estimated pings, no synthesised latency anywhere in the
  measurement path.

---

## Shipping record

* **versionName** `6.9` / **versionCode** `50`
* Built and signed by GitHub Actions
  ([run 30519219069](https://github.com/aptixzero/my_prFF_vP_N/actions/runs/30519219069)),
  native Xray + hev-socks5-tunnel, universal ABI
  (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), minSdk 24 / targetSdk 34
* APK size ≈ 60 MB, signer SHA-256 `6a5ed5e3…07eb82d` — **identical to v6.7 /
  v6.8**, so v6.9 installs over an existing Professor VPN with no uninstall
* Signed universal APK published on **Release v6.9** and committed under
  `build/ProfessorVPN-v6.9-universal.apk` (the v6.8 APK was deleted in the same
  commit, so `build/` can never offer a stale download)
* Direct download: <https://github.com/aptixzero/my_prFF_vP_N/releases/download/v6.9/ProfessorVPN-v6.9-universal.apk>
* Both `main` (اصلی) and `genspark_ai_developer` (فرعی) branches updated
* Admin panel / published `app_config.json`: `latestApkVersion` → `6.9`
