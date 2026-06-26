# build/

This folder holds the **latest signed universal APK** for Professor VPN.
Only the newest `ProfessorVPN-v<version>-universal.apk` is kept; older APKs
are removed on each rebuild.

## Current artifact

- **`ProfessorVPN-v4.4-universal.apk`** — version 4.4 (versionCode 25)
- Universal: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (Android 7.0+).
- Signed with the release key (`CN=NeonVPN`); v1+v2+v3 signature schemes.
- v4.4 highlights (TRUST-WORTHY PING + FASTER, STABLER TUNNEL):
  - **A green ping now means it really connects — 100%.** The ping no longer
    tests Google (Google is open on every ISP, so it proves nothing). It now
    probes only **censored** endpoints that are reachable *only through a
    genuinely working anti-censorship tunnel*: Cloudflare's edge/trace,
    Telegram and Instagram. If the proxy can fetch these, it can carry real
    blocked traffic.
  - **Confirmed, not guessed.** A node must succeed on **two** independent
    censored endpoints before it is shown green, and the reported latency is the
    **median** of the confirmed probes — so a single fluke success can never
    fake a "working" node, which is how dead configs used to slip through.
  - **No more Google in any check** — the live-tunnel watchdog
    (`XrayManager.measureDelay`) also probes Cloudflare's filtered edge instead
    of gstatic, so it can actually tell a working tunnel from a broken one.
  - **Faster, fuller-bandwidth tunnel** — smart per-transport multiplexing:
    `ws`/`grpc`/`h2` carriers now pool requests over a few mux streams
    (concurrency 8) for snappier, more stable browsing on disrupted internet,
    while Reality/XTLS-vision and raw TCP+TLS keep one dedicated full-rate
    stream (mux is incompatible / slower there). Combined with the 1 MiB
    per-connection buffer and TLS-record fragmentation, the tunnel pulls the
    full line-rate the config can offer.
  - **Auto-Test == manual ping** — identical engine, identical
    two-confirmation censored-endpoint check, identical accept/reject. Auto-Test
    only ever saves nodes that genuinely bypass censorship, and adds each one to
    My Configs the INSTANT it confirms.
- Free configs are fetched from the `aptixzero/con_new` feed.

Install on a device/emulator with:

```bash
adb install -r ProfessorVPN-v4.4-universal.apk
```
