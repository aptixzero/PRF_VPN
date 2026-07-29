# Professor VPN — v6.8 release notes

**v6.8 is the "make it fast again" release.** It answers one blunt report:
*«آپدیت‌ها سرعت پینگ‌گرفتن، اتو تست و اضافه‌کردن کانفیگ را افتضاح کند کرده — بالای
۵ دقیقه صبر می‌کنم؛ برنامه دیگر مثل نسخه ۴.۲ سریع و پایدار نیست.»*

The connection core (real Xray outbound, health check, device-path watchdog,
IPv4-only TUN, BBR, QUIC-block) is untouched and still 100 % real — nothing in
v6.8 fakes a ping, a connection, or a stat. What changed is the **cost** of every
measurement, because that is where the minutes were hiding.

---

## The one root cause behind "everything got slow"

Every single ping in this app is a REAL round-trip through a REAL Xray outbound,
and each such probe constructs its **own throwaway native Xray core** (tens of MB
of native heap). That is what makes a green ping trustworthy — but it is also
expensive, so the number of cores spun up per config is the whole ball game.

Up to v6.7 one config could pay for **up to five** native cores before it was
judged:

* **3** latency samples (`SAMPLE_COUNT = 3`), then
* **up to 3** more for the Stage-2 payload verdict, which *looped over three
  payload URLs* and stopped at the first that answered — so a node that only
  answered the last one paid all three.

With the deep-probe gate only **4–8** wide, a 240-config batch therefore serialised
an enormous pile of native-core spin-ups. That is the «بالای ۵ دقیقه».

## What v6.8 does about it

| Knob | v6.7 | v6.8 | Effect |
|---|---|---|---|
| latency samples per config | 3 | **2** | −1 native core / config |
| verdict payload probes | up to 3 | **1** (zero-DNS IP literal) | −1 to −2 cores / config |
| min good latency samples | 2 | **1** | the verdict carries the proof, so high-RTT Iranian nodes stop being thrown away for one reset sample |
| per-config budget | 9 s | **6 s** | dying nodes stop burning the long tail |
| per-probe budget | 3.5 s | **2.5 s** | a reset probe is abandoned sooner |
| verdict budget | 5 s | **3.5 s** | one light probe needs no generous ceiling |
| manual PING-ALL deep gate | 4–8 | **6–12** | survivors measured with more parallelism |
| Auto Test deep gate | 3–6 | **5–10** | working configs reach My Configs faster |

Net result: each config is judged with roughly **3 native cores instead of ~5**,
each with tighter budgets, across a **wider** deep gate. The verdict is still a
FRESH connection carrying REAL response bytes — the exact thing the connect path
needs — so *"if it pings, it connects"* remains structurally true. We simply
stopped paying for it several times over.

**Nothing here is a fake or random value.** The pre-gate still only ever REJECTS
or ORDERS, and every displayed number still comes out of `Pinger`'s real
round-trip. Golden Rule #2 holds.

---

## The "Ping All fires but pings nothing" bug in My Configs — fixed

Report: *«توی My Configs می‌زنم Ping All، کلاً می‌پرد و پینگ هیچ کانفیگی را
نمی‌گیرد.»*

Cause: the sweep's wide TCP pre-gate (wave 1) rejects every node whose socket
does not answer. If the device has a **momentary** drop the instant PING ALL
starts, *all* handshakes refuse, the whole list is marked Unreachable, and wave 2
(the real ping) has nothing left to do — the bar flies by and measures nothing.

Fix: if the pre-gate rejects **everything**, v6.8 does **not** trust it — it hands
the whole list to the deep prober unchanged (which has its own, more forgiving
reachability logic and a retry). The pre-gate only gets to reject when it also let
*something* through, i.e. when its verdict is actually meaningful. The result:
PING ALL on My Configs now measures real pings even across a brief link glitch,
and the top progress bar fills honestly.

---

## Home / connect / switching

No regressions were introduced in the session lifecycle. Fast switching between
configs still routes through the single serialised `sessionExecutor` with the
`generation` guard (no port races), the connect gate still verifies the real
device path before saying "Connected", and the watchdog still self-heals the
tun2socks bridge in place instead of dropping the session. Because config
selection and ping now resolve far faster, switching *feels* immediate.

---

## Auto Test — same contract, faster

* Phase 1 (0→60 %) still fetches feeds in parallel and ranks them by a REAL TCP
  handshake from the user's own device, then bonds to the measured-best feed.
* Phase 2 (60→100 %) still pulls a fresh **240** batch (120 VLESS + 120 VMESS)
  from the winning source and orders it fastest-first.
* The loop still copies **only** configs that actually ping into My Configs, at
  the strict `WORKING_MAX_MS = 2 500` bar (≤ `Pinger.MAX_VALID_MS`), sorted
  ascending so the fastest node sits at the top and is auto-selected.

The difference is purely speed: the wide TCP triage plus the cheaper deep probe
mean low-ping configs land in My Configs within seconds of pressing Auto Test.

---

## Only VLESS & VMESS. No proxies. Cloudflare-only probes. No ad scripts.

All unchanged and still enforced.
