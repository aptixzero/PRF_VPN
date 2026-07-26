# Professor VPN ⚡

> ## 🤖 AI AGENT / CODING MODEL: STOP AND READ FIRST
> **Before changing ANY code, you MUST read [`AI_AGENT_GUIDE.md`](./AI_AGENT_GUIDE.md).**
> This app has a **real, working VPN core** (real connection, real ping/upload/download,
> and a post-connect health check that prevents the "fake connected" bug). The guide
> explains how the core works and the rules you must not break.

A real Android VPN client written in **Kotlin**, built on the real **Xray-core**
(`libv2ray`) plus a real **tun2socks** layer (`hev-socks5-tunnel`). It establishes
an actual `VpnService` TUN interface and tunnels device traffic through the
selected server. Only **VLESS** and **VMESS** configs are supported.

## Features

- Real VPN connection through Android `VpnService` (real TUN device).
- Real Xray-core engine with a post-connect health check (internet off ⇒ not connected).
- Real, live ping / upload / download / uptime (no fake or random values).
- Animated **Liquid Orb** connect control with five states (idle / connecting /
  connected / disconnecting / error) and a live connection-progress arc.
- My Configs (paste / select / copy / delete / ping all, persisted) and Free Configs
  (manual search + auto test, real color-coded pings, auto-sorted) backed by a single
  app-scoped ping service shared across tabs.
- Panel-controlled Sponsor banner + Contact page (no ad-network scripts).
- Universal APK: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (Android 7.0+).

## Download

The latest signed universal APK is published on the
[Releases](https://github.com/aptixzero/my_prFF_vP_N/releases/latest) page and mirrored
in [`build/`](./build). The current artifact is `ProfessorVPN-v6.5-universal.apk`.
Free configs are fetched live from the 50 public feeds in `LiveSources.kt`.

### What's new in v6.5 — see [`RELEASE_NOTES_v6.5.md`](./RELEASE_NOTES_v6.5.md)

v6.5 is a reliability release; it fixes four defects rather than adding features.

- **Reconnect works every time.** v6.4 connected once and then refused to connect
  again until the app was killed — four separate races in the teardown path
  (`XrayManager.start()` short-circuiting on an already-running core, detached
  start/stop threads with no handshake, `TProxyService` having no native run-state,
  and a stale watchdog reviving a closed descriptor). Every lifecycle request now
  goes through one single-threaded executor with a generation counter, so a start
  can never begin before the preceding stop has finished. Disconnect → connect to a
  *different* config, repeatedly, without closing the app.
- **No more stalling mid-stream.** Congestion control is now `bbr`, which paces from
  measured bandwidth × RTT instead of reading mobile packet loss as congestion — the
  cause of "the first three videos load, then it freezes". There is no session time
  limit or connection-type limit anywhere.
- **A config that pings will connect.** The live gate was stricter than the list ping,
  so a 3–5 s node showed green and then failed; the gate now has a 14 s budget and
  accepts a real packet through the local SOCKS5 proxy. The ping side gained a
  *sustain check* that rejects a node which answers one burst and then dies.
- **The barcode actually scans.** `QrCode.interleave()` subtracted the
  error-correction codewords twice, so every block was short and only part of the
  matrix was written — the codes looked flawless but failed Reed–Solomon in every
  scanner (e.g. 44 of 70 codewords written). Fixed, guarded against regression, and
  verified by decoding the output with a from-scratch ISO 18004 decoder.

The admin panel gains a **«لینک پشت بارکد»** tab: paste a link, press
«ساخت بارکد», and the barcode is generated and previewed with the app's own
encoder (so panel and app can never disagree). Scanning it opens that link in the
visitor's browser.

**Admin panel:** <https://aptixzero.github.io/my_prFF_vP_N/>

## Build

Built automatically by GitHub Actions (`.github/workflows/build.yml`) — it reads the
version from `app/build.gradle.kts`, produces a signed universal APK, replaces the old
`build/*.apk`, and publishes a release. Signing secrets are supplied via CI
environment variables; none are stored in source.

## License

App code: MIT. Bundled libraries (`libv2ray`, `hev-socks5-tunnel`) follow their own
upstream licenses.
