# Professor VPN v9.0 — The STOP & Truth Release

v9.0 fixes what v8.9 got wrong. The theme: **STOP must really stop, the
disconnect button must always obey, and every working config must be visible
the moment it proves itself.**

---

## 1. STOP now stops EVERYTHING

The v8.9 STOP cancelled the engine job and nothing else. Four things kept
running after the user pressed STOP, confirmed on-device with a debug build:

1. **The in-process stall supervisor** kept looping every 30 seconds for the
   rest of the process lifetime. It is now cancelled with the sweep.
2. **The WorkManager 15-minute backstop** kept waking the process forever —
   the worker's tick no-opped, but the wake-up itself is the background
   activity the user asked to remove. STOP now cancels the periodic job;
   START re-arms it.
3. **Feed pumps and strategy writes** launched fire-and-forget on the shared
   engine scope finished their network+DB work seconds after STOP. They are
   now children of the engine job and die with it.
4. **Free rows stayed stuck at "Pinging…"** for up to two minutes after STOP
   (the periodic cleanup was the only thing clearing them) — and because no
   statuses emission ever fired after STOP, the list's frozen order was never
   released, so the green rows never re-sorted to the top. STOP now cancels
   the bucket's sweep state explicitly: leftover "Pinging…" rows clear
   instantly AND the emission releases the freeze, so the list sorts the
   working configs to the top right after STOP.

## 2. The disconnect button always obeys

Four concrete paths could silently swallow or override a disconnect, all
fixed:

- **The v8.9 auto-failover could override the user's stop.** The watchdog's
  give-up branch ran with a minutes-old guard; a failover initiated there
  bumped the session generation AFTER the user's STOP, making the stop look
  stale ("stop gen superseded — service kept alive") and reconnecting the
  app to a different server. A stop in flight now always wins: the give-up
  branch re-checks the user's intent at the decision point, the failover
  itself refuses to run while a stop is in flight, and the stop's interrupt
  can no longer be swallowed into a fall-through into the failover.
- **The double-tap inversion.** The pill optimistically showed
  "disconnected" before the service processed the stop, so a second tap
  400–1500 ms later (while the notification was still visible) read
  DISCONNECTED and became a CONNECT, superseding the pending stop. A
  pending-stop latch now makes any tap re-send the STOP until the service
  confirms.
- **A lost stop is re-sent.** The 1.5-second reconciler used to give up
  when the service still claimed to be connected; it now re-sends the STOP
  instead of leaving the tunnel up with a "disconnected" UI.
- **The connect gate can no longer announce "connected" after a stop** that
  landed during the final device-path probe (the "it ignored my disconnect"
  flash): the state is re-checked at the broadcast itself.

Per-cycle leaks fixed too: a straggler watchdog iteration can no longer
re-acquire the 10-hour wake lock after teardown, and both revive paths now
stop a native core that finished spinning after its session died — the
orphaned core held the SOCKS/API ports and was the mechanism behind "after
repeated connect/disconnect cycles the app stops connecting".

## 3. Working configs are always visible

- **A permanent "N working configs" counter** now sits above the progress
  bar, outside the progress box that used to hide 1.5 seconds after every
  sweep. It never disappears while the list has content, and it counts on
  both paths (engine sweeps and manual PING ALL).
- **A config that proves a ping floats to the top immediately** — the moment
  its verdict lands, mid-sweep, the row moves above the untested ones (a
  single move per new green row; the rest of the list stays stable, so there
  is no reshuffle-jumping). After STOP the whole list re-sorts with all
  green rows on top.

## 4. Faster, honest sweeps

The on-device reproduction measured the real costs and both are fixed:

- **Endpoint-level dedup.** The same `address:port` was deep-probed 3–4 times
  within seconds (public feeds carry one server under many UUIDs) — the
  triage draw now keeps only the best-ranked copy per endpoint. This is a
  pure waste cut; it can never empty a batch and the first copy is always
  kept.
- **The per-config wall dropped from 24 s to 15 s.** Dead rows were burning
  the full wall in Stage-1 TCP timeouts while their workers sat blocked —
  the trend line advanced ~1 row/second. The honest probe ladder completes
  or fails well inside 15 s; the wall now only clips pathological hangs.
  Deep-probe concurrency is untouched (§5g/§5i — parallelism is not the
  lever).

## 5. What was NOT wrong (verified, not assumed)

A debug build with full logging reproduced the reported "second run never
pings" scenario end to end: **the engine DOES measure on every run.** Promotions
continued in run 2, 3 and later cycles (23 ms, 75 ms, 94 ms, 102 ms …). The
perception came entirely from the fixed defects above: rows stuck at
"Pinging…", green rows never surfacing, and the slow trend line. The
measurement pipeline itself — one engine, forced-fresh manual sweeps, real
verdicts — is intact and unchanged.

## Invariants preserved

Everything from AI_AGENT_GUIDE.md §5m carries forward: the health check is
untouched, no fake stats, only VLESS/VMESS, all tuning through TransportPlan,
no new Xray keys, deep-probe concurrency bounds unchanged, mux never emitted
disabled, automatic paths never write manual keys, strings.xml untouched.
The new v9.0 rules (stop-completeness, pending-stop latch, endpoint dedup,
move-to-top) are documented in the guide's §5n.
