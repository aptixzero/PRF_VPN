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
| `config/XrayConfigBuilder.kt` | Builds the Xray JSON (inbounds: SOCKS5 10808 + API 10809; outbound: the selected server with Reality/XTLS/TLS + TLS-record fragmentation for anti-DPI). |
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
      6. broadcast STATE_CONNECTED + start stats pump + watchdog
```

If you remove or weaken step 4, the "fake connected" bug comes back. **Don't.**

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

### Free Configs tab
- Modes: **Manual (Search)**, **Automatic**, **Auto Test** (+ combined fallback).
- Ping all with **real, color-coded pings** (green=low / orange=medium / red=high).
- Configs that ping are pinned to the top; non-pinging fall to the bottom.
- **Auto Test:** adds 100 configs (Server 1–100); every 24h it resets to Server 1
  with fresh configs, tests 10 at a time, keeps **6 working** ones and deletes the
  rest.
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

---

## 6. ADMIN PANEL (`adminpanel/`)

- `index.html` + `app.js`, fully client-side, no server.
- Login: user/pass are stored **only as SHA-256 hashes** in `app.js`. The raw
  credentials are never in source.
- Tabs: **In-app Ad** (with a live phone preview), **Contact**, **Publish**.
- Publish writes `app_config.json` to the panel repo via the GitHub Contents API
  using a token the operator pastes at runtime (kept in `localStorage`, never
  committed).
- The panel `README.md` must contain **only the panel link** — no instructions, no
  credentials, no token. Keep it that way.

---

## 7. BUILD

- There is **no Android SDK / JDK in the sandbox**. The APK is built by
  **GitHub Actions** (`.github/workflows/build.yml`).
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
