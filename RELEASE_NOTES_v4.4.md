# Professor VPN v4.4 — Trust-worthy Ping + Faster, Stabler Tunnel

The complaint about v4.3: **the ping lied.** A config would show a ping but then
fail to actually open censored sites. v4.4 fixes that so **a green ping means
the node really connects — 100%** — and makes the tunnel faster and more stable.

## Why v4.3's ping was wrong

v4.3 timed `generate_204` on **Google**. Google is reachable from almost every
ISP *without* a working proxy, so the measurement could "succeed" off a
half-open path that can't actually carry blocked traffic. A ping that doesn't
prove censorship-bypass is meaningless for a filter-breaker.

## The v4.4 ping rule

- **No Google.** It's open everywhere and proves nothing.
- **Test only censored endpoints** that are reachable *only through a genuinely
  working anti-censorship tunnel*: Cloudflare's edge / `cdn-cgi/trace`,
  Telegram (`core.telegram.org`) and Instagram (`i.instagram.com`). If the proxy
  can fetch these, it can carry real blocked traffic.
- **Confirm, don't guess.** A node must succeed on **two** independent censored
  endpoints before it is called reachable. One fluke success is rejected — that
  is exactly how dead nodes used to slip through as "green".
- **Honest latency.** The number shown is the **median** of the confirmed
  probes, not a lucky best-case.
- The probe always travels **through the real Xray outbound** (same outbound +
  stream settings as the live connect path), so it reflects the actual tunnel on
  Wi-Fi, mobile data, or any ISP — never the local link.

## Watchdog fixed too

The live-tunnel health check (`XrayManager.measureDelay`) also stopped using
Google gstatic and now probes Cloudflare's filtered edge, so it can actually
distinguish a working tunnel from a broken one.

## Faster, fuller-bandwidth, more stable tunnel

- **Smart per-transport multiplexing.** `ws` / `grpc` / `h2` carriers now pool
  app requests over a few mux streams (concurrency 8) — much snappier and far
  more stable on Iran's disrupted internet, where opening a fresh handshake per
  request stalls pages. Reality / XTLS-vision and raw TCP+TLS keep **one
  dedicated full-rate stream** (mux is incompatible / slower there).
- **Full line-rate.** 1 MiB per-connection buffer + `tcpNoDelay` +
  TLS-ClientHello fragmentation (anti-DPI) so the tunnel pulls the full
  bandwidth the config can offer while staying alive on flaky links.

## Auto-Test == manual ping

Auto-Test uses the **identical** engine, the **identical** two-confirmation
censored-endpoint check and the **identical** accept/reject threshold as a
manual ping. It only ever saves nodes that genuinely bypass censorship, and adds
each one to My Configs the INSTANT it confirms (one by one, 1 config or 120).

## Build

- `versionCode 25`, `versionName 4.4`. Universal signed APK
  (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, Android 7.0+), mirrored into
  `build/` (the old v4.3 artifact removed).
