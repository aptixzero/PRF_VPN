# Professor VPN v6.6

**نسخه ۶.۶ — پینگ واقعی و سریع، اتصال فوری، بدون هیچ پروکسی**

This release targets the four defects reported against v6.5, all of which turned
out to be three underlying bugs plus one bad architectural decision.

---

## The reports, and what actually caused them

| Report | Real cause |
|---|---|
| «پینگ فیک میده» — fake ping | The number was real; the **verdict** was fake. Latency was measured with a single zero-byte `204` request. That proves one tiny request completed — not that the node can carry traffic. |
| «دیر وصل میشه» — slow | Every config, including the ~80 % of a public feed that is simply dead, was given a full multi-second budget inside its own native Xray core. Plus a cold DNS-over-HTTPS lookup on *every single sample*. |
| «وصل نمیشه / کار نمیکنه» — connects but nothing works | The connect gate verified the **core**, which dials straight out of itself. It could not see a broken TUN→tun2socks→SOCKS bridge — the exact thing that decides whether the *device* has internet. |
| «باید ۳۰ ثانیه صبر کنیم» — 30-second wait | Named-host probes forced a cold DoH round-trip; the retry ramp slept 300–900 ms between tries; and the bridge start was preceded by a blind `Thread.sleep(450)`. |

---

## 1. Pings are now real — the payload verdict

A ping is no longer a single latency number. `Pinger` runs a **three-stage
pipeline** and a config must survive all three:

1. **TCP pre-gate** (`TcpProbe`) — does *anything* accept a connection on
   `address:port`? Xray dials that same socket, so a refusal here is proof the
   core would fail too. ~300 ms, no native core.
2. **Latency samples** — locks onto the first answering Cloudflare reference
   endpoint and gathers 3 samples, requiring at least 2 good ones. The reported
   figure is the **median of the warm samples** (the cold first sample is
   dropped), so it is stable and honest.
3. **Payload verdict** — after a deliberate pause, a **brand-new** connection
   must fetch a **real body**, including a 32 KiB download from
   `speed.cloudflare.com`. This is what kills fake pings:
   - Iranian DPI frequently admits the first handshake and **resets the next**;
   - a node sitting at its connection cap accepts one trivial request and then
     stops serving.

   Both pass a 204 probe and both fail here. Because
   `Libv2ray.measureOutboundDelay` builds its own throwaway core per call, stage
   3 is inherently a fresh connection — exactly the property needed.

**Result: if a config shows a ping, it has already proven it can move real bytes
on a fresh connection.** That is the "پینگ داد پس باید وصل بشه" guarantee.

> The pre-gate can only ever **reject**. No displayed number ever originates
> there, so the project's no-fake-stats rule is fully intact.

## 2. Pings are now fast — "تند تند"

- **Two-wave sweep.** Wave 1 rejects the dead majority ~48 at a time with plain
  socket connects. Wave 2 runs the expensive pipeline only on nodes that actually
  answered. Same verdicts, a fraction of the wall-clock.
- **Zero-DNS probing.** Probes target the IP literal
  `https://1.1.1.1/cdn-cgi/trace`, and the generated Xray config carries a static
  `dns.hosts` table. A 2–4 s cold DoH lookup is removed from *every* sample.
- **No wasted retry.** The deep probe is only retried when a cheap socket check
  says the node is still alive — re-probing a corpse can't change the answer.
- **Tighter budgets:** per-config 12 s → **9 s**, per-probe 5 s → **3.5 s**.
- **Dead proxies deleted:** 5 mirrors × ~9 s of timeouts each was most of a
  minute burned per source.

## 3. "Connected" now means it actually works

The connect sequence was **inverted**. v6.5 verified, then started the bridge.
v6.6 starts the bridge first, then verifies **the real device path**:

```
TUN → tun2socks → local SOCKS5 (10808) → Xray core → internet
```

- `probeDevicePath()` goes through the local SOCKS inbound that tun2socks
  actually feeds — the same route your apps use — and **drains the response
  body**, so a headers-only reply cannot pass.
- If that path can't carry a real payload, the session is **torn down** and you
  get *"Server not responding — pick another"* instead of a green light on a
  dead tunnel.
- `waitForSocksInbound()` polls for the inbound every 25 ms instead of blindly
  sleeping 450 ms.
- The retry ramp now starts at **120 ms** (was 300–900 ms).
- Verification budget **14 s → 10 s**: faster *and* stricter.

**The moment it says Connected, traffic is already flowing.** No 30-second wait.

## 4. Zero proxies — a real anti-censorship fix

Per the brief («اصلا از پروکسی استفاده نکن»), every public proxy mirror is gone:
`r.jina.ai`, `api.allorigins.win`, `ghproxy.net`, `cors.isomorphic-git.org`,
`gh.api.99988866.xyz`, `cdn.statically.io`, `gitcdn.link`.

They deserved to go: they are third-party servers that see and could rewrite
every config you are about to route your traffic through, they are rate-limited
and mostly blocked from Iran anyway, and each dead one had to time out before the
next was tried.

**The mechanism was replaced, not the mirror.** The dominant block on Iranian
ISPs is **DNS poisoning** — the host is reachable once you learn its true
address. So:

- **`CfDns`** resolves through **Cloudflare DoH** (`1.1.1.1` by IP literal, so
  there is no bootstrap lookup to poison), with a 10-minute cache and a system
  fallback.
- **`DirectHttp`** connects **directly to the true origin** with `Proxy.NO_PROXY`
  and full certificate validation — encrypted lookup, authenticated origin, one
  hop, no middleman.
- Shared **connection pool + HTTP/2**, so the many small feed fetches reuse one
  handshake instead of paying for a new one every time.

Only `cdn.jsdelivr.net` remains as a fallback — GitHub's own immutable CDN, not a
forwarder.

## 5. Maximum capability from the selected config

Retained and reinforced from v6.5: BBR congestion control, XTLS-Vision / Reality
with uTLS fingerprinting, TLS-ClientHello fragmentation, 512 KiB buffers,
`UseIPv4` socket policy, QUIC blocked (UDP 443) so nothing silently falls back to
a throttled path, and MTU 1500.

---

## Ping button behaviour

Every press of **PING** (single row or PING ALL) clears the previous value,
shows **"Pinging…"**, and writes only the freshly measured result. Values never
auto-jump or reset on their own.

---

## Install

Universal signed APK — `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
Android 7.0 (API 24) and newer. Installs over v6.5 without data loss.
