# Professor VPN v8.5 — Immediate Promotion, Fast First Answer, Terminal Cockpit

**versionCode 66 · versionName 8.5 · universal APK (arm64-v8a, armeabi-v7a, x86, x86_64) · minSdk 24**

Built locally with `./gradlew :app:assembleRelease` (JDK 17, compileSdk 34).
There is no `build.yml` workflow in this repository, so the committed APK **is**
the release artifact — see `AI_AGENT_GUIDE.md` §7. Signed with the same release
key as v6.7 onwards (certificate SHA-256 `6a5ed5e3…07eb82d`, SHA-1
`df7d7207…ec569`, verified with `apksigner verify --print-certs` against the
shipped v8.4 APK), so v8.5 installs **over** an existing install.

APK SHA-256 `aaaf9277a267eb396b3dde1f3966c937a5aff6489c634be894b733334b82e4e0`,
60,054,250 bytes.

v8.5 is a **bug-fix and interface** release. v8.4 shipped a strong network
engine, but two defects made the auto-search feel broken: a config that had been
*proven to work* did not show up in **My Configs**, and the first usable result
took far too long to arrive. Both are fixed at the source, not papered over. The
home screen is also rebuilt as a real terminal.

---

## 1. A proven config now lands in My Configs immediately

**The defect.** In v8.4, `AutoTestEngine` collected working configs into a local
buffer and called `flushWorking()` **only after the entire batch loop had
completed** (v8.4 `AutoTestEngine.kt:457`). Nothing was persisted mid-sweep. A
sweep over a large batch runs for minutes, so a config proven working in the
first seconds sat in memory the whole time — and if the sweep was cancelled, or
the process died, it was lost entirely. From the user's side the app had found a
working config and then appeared to do nothing with it.

This was structural, not intermittent: no run of v8.4 could persist a promotion
before its batch loop finished, because there was no code path that wrote one.

**The fix.** `flushWorking()` is deleted. A new `promoteWorking()` runs **inside
the per-config coroutine**, the instant the three-stage verdict returns:

```kotlin
val promoted = promoteWorking(myStore, cfg, ms)
```

It is wrapped in `withContext(NonCancellable)` so the write completes even if the
sweep is being torn down at that moment — which is the whole point, since
`AutoTestService` is `START_STICKY` and the app may be backgrounded, swiped away
or killed at any time.

The `NonCancellable` region is deliberately **small**: it covers the store write
and the ping seed only, never the probing. `STOP` therefore remains unconditional
and still stops the sweep promptly, as guide §5i requires.

Promotion uses `addServersWithoutLimit` rather than `addServers`, because
`addServers` applies `ConfigFetcher.MAX_PER_LOCATION`, a cap that accumulates
across cycles and silently `continue`s — which would permanently discard configs
that had already been *proven* to work.

`ConfigsFragment` now reacts to a new `Progress.promotions` counter, bypassing
both the `!p.running` guard and the 700 ms refresh throttle, so the row appears
as soon as it is stored rather than at the next scheduled repaint.

## 2. The first working config arrives in seconds

Three separate costs were removed.

**The per-config budget was not a ceiling.** The TCP pre-gate sat *outside*
`withTimeoutOrNull(PER_CONFIG_BUDGET_MS)`. One `ping()` could therefore cost
3,500 ms (pre-gate) + 9,000 ms (budget) = 12.5 s, and through `pingWithRetry` up
to roughly 28.5 s for a single config. The pre-gate is now inside the budget, so
the constant means what it says.

**Fewer redundant probes.** `PROBE_URLS` went 3 → 2 and `NUM_SAMPLES` 4 →
`WARM_SAMPLES` 2. Both remaining endpoints are still Cloudflare-only via
`ProbeEndpoints`, per guide §5c — no Google endpoint was introduced.

**Promising candidates go first.** A first-answer lane single-probes the best 24
triage-ranked candidates before the general sweep. It calls the *same*
`Pinger.ping`, so all three verdict stages still run and nothing is marked
working on weaker evidence; only the *retry* is skipped, and a miss is
**requeued** rather than recorded as a verdict (a requeued miss does not advance
the `tested` counter).

The chunked `awaitAll` was replaced with a worker pool over one shared ordered
queue, so a slow config no longer holds up a whole chunk. **Concurrency was not
widened**: `MAX_CONCURRENCY` is still `(cores + 2).coerceIn(4, 8)` with the
semaphore held inside each worker. Guide §5i is explicit that the cheap wave may
be wide but the deep wave may not — a wider deep wave caused the v4.7 low-RAM
crash.

`TcpProbe` gained `UNMEASURED = Long.MIN_VALUE`, distinct from
`UNREACHABLE = -1L`, so a caller that already handshook the same `address:port`
forwards the measured number instead of re-dialling. A `TcpProbe` number is still
only ever used to reject or order candidates, never displayed (§5f).

## 3. My Configs growth is bounded

Promoting on every verdict rather than once per cycle raised the write frequency
by orders of magnitude, and `ConfigStore` had **no** global cap, prune or trim —
`MAX_PER_LOCATION` was the only growth guard, and promotion deliberately bypasses
it. Measured on a real device, one ~5-minute v8.4 search produced 54 stored
configs / 67,444 bytes; at that sustained rate an 8-hour run extrapolates to
roughly 5,000 entries and ~6 MB in a single `SharedPreferences` key, while each
write re-serialises the entire list.

`ConfigStore.addPromoted(cfg, pingMs)` now keeps the best-ping
**`MAX_PROMOTED = 200`** engine-promoted configs. The eviction rule is
deliberately narrow:

- **A config you pasted yourself is never evictable.** Provenance is recorded in
  a separate `promoted_pings` key; a pasted config goes through
  `addServersWithoutLimit`, is never recorded there, and does not count against
  the cap. The user's data is not the engine's to delete.
- **The selected config is never evicted**, even if it is the slowest row —
  pulling the selection out from under someone who may be connected through it is
  not worth one list slot. The effective ceiling is `MAX_PROMOTED + 1`.
- **The fastest are kept, not the newest**, so "the list is full" never comes to
  mean "and now you get the worse ones."
- **Fully deterministic** — measured ping with a `dedupKey` tie-break. No
  randomness anywhere near a measurement path (§0).

Nothing is hidden by an eviction: the config keeps its real measured ping in the
Free list and stays visible and connectable. Provenance is written in the *same*
`edit()` as the list, so the two keys cannot disagree and a promotion is one disk
write rather than two, and it is pruned on `removeServer`/`removeServers` so the
side table cannot grow unbounded on its own.

`commit()` was deliberately **kept** over `apply()`. The `NonCancellable` region
exists so a proven config survives the process dying moments later; with
`apply()` it would be protecting a write that had not landed — an in-memory value
plus a queued flush, gone with the process. The cost was bounded instead (the
list is capped, both keys go in one `edit()`, and the engine calls this from
`Dispatchers.IO`, never the main thread). The reasoning is documented at
`saveServersLocked` so it is not "optimised" back.

## 4. Terminal cockpit

The home screen was already terminal-themed; v8.5 makes it consistent and fixes
three concrete problems found by measuring the running app.

- **Bundled monospace face.** JetBrains Mono ships with the APK
  (`res/font/term_mono.xml`, regular/medium/bold) with OFL-1.1 and AUTHORS files
  committed, so the terminal renders identically on every device instead of
  depending on a system font.
- **A real terminal window** — title bar, prompt, caret and a bounded log well.
- **A 4dp spacing grid** in a new `res/values/dimens.xml`, plus a radius ramp,
  type ramp and fixed control heights. The base file is deliberately the
  **compact** bucket, with `res/values-h560dp/dimens.xml` overriding the
  height-sensitive tokens — Android has no "only short screens" qualifier, since
  every `hNdp` bucket matches everything *taller* than N.
- **Taller home banner**, as requested: 68dp → **88dp** (76dp on short screens),
  measured on device as 85px → 110px with the terminal well absorbing the delta.
- **The status line stopped crying wolf.** v8.4 showed "TAP TO CONNECT" in **red**
  at the top of the screen while the green pill said the same thing, so the red
  copy read as an error when nothing was wrong. The status line now reports a
  STATE (`READY · not connected`, `NEGOTIATING TUNNEL…`, `TUNNEL LIVE · traffic
  secured`, `TUNNEL FAILED`) and the button asks for an ACTION.
- **The terminal pane is no longer 89% empty.** It shows a colour-coded boot
  preamble built from static product facts and locally-read state — no fabricated
  measurement.
- **The Telegram button** is no longer a Telegram-brand blue gradient sitting
  between green elements; it is a bordered ghost button that belongs to the theme.
- **Light theme system bars fixed.** Both bars were painted `#FFFAFAFA` without
  ever requesting dark icons, so the clock, battery and navigation icons rendered
  white-on-near-white. Measured from screenshot pixels: status bar **1.04:1 →
  5.67:1**, navigation bar **1.30:1 → 5.50:1**, both now WCAG AA. Fixed with
  `windowLightStatusBar` (API 23+) and `windowLightNavigationBar` (API 27+, which
  degrades silently on 24–26), scoped to the Light theme only.

The app still defaults to the **dark** terminal theme
(`AppPrefs.getTheme()` → `THEME_DARK`).

`LiquidOrbConnectView.kt` was deleted — it was entirely dead. Several §5b
violations were fixed along the way: hardcoded `#RRGGBB` literals in
`activity_main.xml`, `activity_contact.xml` and `ConnectFragment.kt` now resolve
through `?attr/…` theme attributes.

## 5. Network settings are measured per network

- **New `NetWatcher`** re-measures when the network actually changes, keyed off a
  `networkKey` identity with a 2.5 s settle debounce, a 60 s floor and
  `Mutex.tryLock()` so an in-flight measurement is skipped rather than queued. It
  runs off the main thread, short-circuits before any probe when there is no
  network, and **never measures through a live tunnel** — that would both feed
  back on itself and compete with the revive path.
- **Fixed: measured settings were silently discarded after a handover.**
  `NetProfileStore.networkKey` used bare `cm.activeNetwork`, which returns the
  **VPN** network once the tunnel is up. The mid-session key therefore never
  matched the stored one, every `matches()` returned `EMPTY`, and the measured
  DNS block, MSS clamp and resolved IP mode were thrown away on the revive path
  after a Wi-Fi↔mobile handover. It now resolves the underlying non-VPN network.
- **Evidence-driven wire values.** `happyEyeballs` inner values were four
  literals, which pinned `prioritizeIPv6` to `false` and hardcoded the 250 ms
  delay; they are now derived from measurement, with §9d spelling. A new `v6only`
  key is emitted only on the measured verdict that IPv6 completed a real request
  where IPv4 did not. Both are gated inside `if (tier == Tier.FULL)`, so the SAFE
  and MINIMAL fallback rungs are untouched.
- **`SHIELD` added to the AUTO ladder** for links with multi-layer interference
  (a bare ClientHello is reset, splitting gains nothing, *and* DNS is broken).
  Previously such a link fell through to `MASTER`, which requests MPTCP and adds
  a second observable subflow on the worst possible link.
- **Real controls for existing readers.** Three advanced switches that had a
  reader but no UI control are now on the Network Settings page, alongside an
  ordered decision cascade that shows, step by step, what was measured and the
  single setting each step decided. A step with no evidence says so and changes
  nothing.

"Location" here means the **measured properties of the network path**. No
permission was added and no geo-IP service is consulted; the only geography
signal is `loc=` from a Cloudflare trace fetched *through* the tunnel, stored as
`exitCountry` and used solely to reorder which DNS resolvers are raced first.

## 6. One ping statistic everywhere

`XrayManager.measureDelayStable()` now defaults to **2** warm samples, matching
`Pinger.WARM_SAMPLES`. Both discard the cold sample and take the **median** of
the warm ones — never an EMA (§9e). Without this the ping path and the connect
path would report different numbers for the same server, which is exactly the
inconsistency guide §5c exists to prevent.

## 7. Smaller fixes

- `stats/UserStatsReporter` reported a hardcoded `ProfessorVPN/3.6` User-Agent,
  five releases stale. Both it and `net/DirectHttp` now derive the UA from
  `BuildConfig.VERSION_NAME`, so it cannot drift again.
- `NetWatcher` had a compile-level bug — it called `isTunnelUp()` as a bare local
  function when the flag lives on `NeonVpnService`'s companion.
- Dead code removed: `FreeConfigsFragment.startSearch()` / `onAutoTestClicked()`
  and an unreachable toast. `Progress.autoStopped` was deleted rather than wired
  up, because §5i means no correct implementation can ever set it true.
- The private `adminpanel/` copy was a v4.6-generation fork with a **weaker auth
  model**: SHA-256 `USER_HASH`/`PASS_HASH` digests checked in the browser, a
  GitHub token persisted in `localStorage`, and user-editable repo/path/token
  fields defaulting to the dead `aptixzero/PRF_VPN` repository. It is now the
  authoritative `prf-vpn-admin` console, which delegates auth to GitHub, keeps
  the token in memory only, and locks the publish target in source.
- Documentation corrected against the code: the README's APK filename and feed
  count (222 feeds — 138 VLESS + 84 VMESS, parsed from `LiveSources.kt`), and
  `AI_AGENT_GUIDE`'s repository slug and remote-config URL.

---

## Upgrade notes

- Installs over v8.4 — same signing identity, verified.
- The persisted config format is unchanged. The new `promoted_pings` key is
  absent on an upgraded install, which reads as "nothing engine-promoted yet" —
  the correct interpretation, and it means existing configs are treated as yours
  and are never evicted.
- `res/values/strings.xml` is append-only: 244 → 291 names, none removed,
  reworded or reordered.
