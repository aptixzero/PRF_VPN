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
[Releases](https://github.com/aptixzero/PRF_VPN/releases/latest) page and mirrored in
[`build/`](./build). The current artifact is `ProfessorVPN-v4.5-universal.apk`.
Free configs are fetched from <https://github.com/aptixzero/con_new>.

## Build

Built automatically by GitHub Actions (`.github/workflows/build.yml`) — it reads the
version from `app/build.gradle.kts`, produces a signed universal APK, replaces the old
`build/*.apk`, and publishes a release. Signing secrets are supplied via CI
environment variables; none are stored in source.

## License

App code: MIT. Bundled libraries (`libv2ray`, `hev-socks5-tunnel`) follow their own
upstream licenses.
