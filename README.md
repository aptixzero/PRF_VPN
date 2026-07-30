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
in [`build/`](./build). The current artifact is `ProfessorVPN-v6.8-universal.apk`.
Free configs are fetched live from the **70** public feeds in `LiveSources.kt`.

### What's new in v6.8 — see [`RELEASE_NOTES_v6.8.md`](./RELEASE_NOTES_v6.8.md)

v6.8 is the **"make it fast again"** release. It answers one blunt report —
*"the updates made pinging, Auto Test and adding configs painfully slow; I wait
over 5 minutes and the app no longer feels fast and stable like v4.2."*

- **Every ping now spins ~3 native cores instead of ~5.** Each real ping builds
  a throwaway native Xray core, and the count per config is what dominated the
  wait. v6.8 takes **2** latency samples instead of 3, and — the big one — the
  Stage-2 payload verdict now issues **one** zero-DNS probe instead of looping
  over up to three heavy payload URLs (a node that only answered the last one
  used to pay three full cores). Budgets are tighter too, so a *dying* node stops
  burning the long tail. The verdict is still a fresh connection carrying real
  bytes, so *"if it pings, it connects"* stays true — we just stopped paying for
  it several times over.
- **Wider deep gates, because each config is now cheaper.** Manual PING ALL runs
  **6–12** deep probes at once (was 4–8) and Auto Test **5–10** (was 3–6), so the
  live survivors are measured with real parallelism and low-ping configs land in
  My Configs within seconds.
- **"Ping All fires but pings nothing" in My Configs — fixed.** If a momentary
  link drop makes the wide TCP pre-gate reject *every* node, v6.8 no longer
  trusts it — it hands the whole list straight to the real prober instead of
  painting everything red. The pre-gate may only reject when it also let
  something through.

Everything measured is still **real** — no `Random`, no proxies, Cloudflare-only
probe endpoints, only VLESS/VMESS. The connection core (health check, device-path
watchdog, IPv4-only TUN, BBR, QUIC-block) is untouched.

<details>
<summary>What was new in v6.7</summary>

v6.7 is the release that made **Auto Test** produce **low-ping** configs, and
produce them **fast**. It answers one report — *"Auto Test finds sources at
random, takes forever to ping, and every ping is above 250 ms"* — which turned
out to be four separate defects.

- **The 0 → 60 % bar now really tests your connection against the sources.**
  Phase 1 used to walk the feeds one at a time and stop at the **first** one that
  answered, which made the winner an accident of list order — and, worse, it only
  tested whether a *text file* could be downloaded, which says nothing about
  whether the servers inside it work for you. It now fetches **10 feeds per kind
  in parallel**, gives **6 nodes from each a real TCP handshake from your own
  device** (sampled evenly across the feed, 40 at a time), and **ranks** them by
  `median measured latency × (1 / hit-rate) + feed load time`. It bonds to the
  source measured best **for you**, and refuses to bond to a feed where fewer
  than 2 of 6 samples answered.
- **Low-ping configs arrive in seconds, not minutes.** Before the expensive
  prober runs, a **48-wide TCP triage wave** drops every config that accepts no
  connection at all (most of a public feed — each one dropped is a multi-second
  deep probe never run) and **orders the survivors by real measured handshake
  time**. That ordering is sound because the tunnel round trip physically
  *contains* the TCP round trip, making the door time a hard **lower bound** on
  the ping a node can ever report — so the nearest nodes are probed first. It
  changes only *when* a config is measured, never *what* is reported.
- **No more 250 ms+ junk in My Configs.** Auto Test's acceptance bar was
  **8 000 ms**. Eight seconds is not a working VPN, and because Auto Test runs
  unattended for hours, that one number is why the list filled with unusable
  nodes. It is now **2 500 ms** — comfortably above a good tunnel ping, far below
  anything painful. Accepted configs are stored **sorted ascending by their real
  measured ping**, so the row the app auto-selects is the best one found.
- **20 more sources — 70 total, none removed.** All `vless`/`vmess`, and every
  new URL was verified with a live request before being added.
- **The barcode opens the browser.** A payload without an explicit scheme is
  *text* to a phone camera, and cameras offer *copy* for text — that was the bug.
  The payload is now always `https://…`; the manifest declares the `<queries>`
  entry Android 11+ requires before an app may launch a browser at all (its
  absence made `startActivity` throw, and the old code swallowed that in
  silence); and `openInBrowser` now falls back direct → chooser → clipboard-with-
  a-toast instead of doing nothing.

Everything measured is real — no `Random`, no proxies, Cloudflare-only probe
endpoints. The TCP measurement introduced here may **only reject or order**; it
is never displayed and never stored as a ping. Every number you see still comes
from the full three-stage `Pinger` pipeline, payload verdict included.

</details>

<details>
<summary>What was new in v6.6</summary>

v6.6 fixes the four defects reported against v6.5: fake pings, slow pings,
"connects but doesn't work", and the 30-second wait. **No proxies are used
anywhere** — the anti-censorship mechanism is now Cloudflare DNS-over-HTTPS.

- **Pings are real.** A ping is now a three-stage verdict, not a number: a cheap
  TCP pre-gate, then median latency over warm samples, then a **payload verdict**
  — a *brand-new* connection must fetch a *real body* (including a 32 KiB
  download from `speed.cloudflare.com`). This is what kills the fake ping: a
  zero-byte `204` probe proves only that one tiny request completed, and Iranian
  DPI routinely admits the first handshake then resets the next, while a node at
  its connection cap accepts one trivial request and then stops. Both used to
  show green. **If a config shows a ping, it has already proven it can move real
  bytes on a fresh connection.**
- **Pings are fast.** The sweep runs in two waves: ~48 concurrent plain socket
  connects reject the dead majority in ~300 ms each, and only the survivors pay
  for the expensive probe (which needs a native Xray core, so it stays narrow).
  Probes are also **zero-DNS** — they target the IP literal
  `https://1.1.1.1/cdn-cgi/trace` and the config carries a static `dns.hosts`
  table, removing a 2–4 s cold DoH lookup from *every sample*. Per-config budget
  12 s → 9 s.
- **"Connected" now means it works — immediately.** The connect sequence was
  inverted: the TUN bridge starts *first*, then verification tests the **real
  device path** (TUN → tun2socks → local SOCKS5 → core) and drains the response
  body. v6.5 verified with a core-only probe that dials straight out of itself and
  therefore could not see a broken bridge — the exact cause of "it says connected
  but nothing works". A tunnel that can't carry a payload is now torn down with
  *"Server not responding — pick another"* instead of showing a green light. With
  the blind `Thread.sleep(450)` replaced by 25 ms inbound polling and a retry ramp
  starting at 120 ms, the 30-second wait is gone.
- **Zero proxies.** `r.jina.ai`, `api.allorigins.win`, `ghproxy.net`,
  `cors.isomorphic-git.org`, `gh.api.99988866.xyz`, `cdn.statically.io` and
  `gitcdn.link` are all deleted. (v6.9 finished the job by deleting the last
  CDN mirror, `cdn.jsdelivr.net`, and the third-party paste host too — see the
  v6.9 notes.) They were third-party servers in the middle of
  the config supply chain, rate-limited, mostly blocked from Iran anyway, and each
  dead one burned a ~9 s timeout before the next was tried. The **mechanism** was
  replaced rather than the mirror: since the dominant block in Iran is **DNS
  poisoning**, the app now resolves through **Cloudflare DoH** (`CfDns`, querying
  `1.1.1.1` by IP literal so there's no bootstrap lookup to poison) and connects
  **directly to the true origin** with `Proxy.NO_PROXY` and full certificate
  validation, over a shared HTTP/2 connection pool.

</details>

<details>
<summary>What was new in v6.5</summary>

v6.5 was a reliability release; it fixed four defects rather than adding features.

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

The admin panel gained a **«لینک پشت بارکد»** tab: paste a link, press
«ساخت بارکد», and the barcode is generated and previewed with the app's own
encoder (so panel and app can never disagree). Scanning it opens that link in the
visitor's browser.

</details>

**Admin panel:** <https://aptixzero.github.io/my_prFF_vP_N/>

## Build

Built automatically by GitHub Actions (`.github/workflows/build.yml`). It reads the
version from `app/build.gradle.kts`, produces a signed universal APK, uploads it as
a workflow **artifact**, and on manual dispatch / tag publishes the **GitHub
Release** `v<versionName>` with the APK attached.

The APK mirrored in [`build/`](./build) is the release binary for the current
`versionName`; only the latest version is kept there, and the previous one is
deleted whenever a new version ships.

Signing secrets are supplied via CI environment variables; none are stored in
source (an unset secret falls back to the bundled dev key so the build always
succeeds).

## License

App code: MIT. Bundled libraries (`libv2ray`, `hev-socks5-tunnel`) follow their own
upstream licenses.
