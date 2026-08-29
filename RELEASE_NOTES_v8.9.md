# Professor VPN v8.9 — Stability & Truth Release

The theme of v8.9 is **"never degrades, never lies, never drops"**: the app
must work exactly as well in week six as in the first hour, every visible
ping must be a fresh real measurement, and a verified connection must heal
itself instead of giving up.

---

## 1. The week-one degradation is fixed at the root (4 unbounded states bounded)

The reported *"after a week of heavy use it no longer works like day one —
it stops finding good configs and connections feel worse"* had four concrete
causes, all fixed:

1. **The diagnostics ledgers never pruned.** `probe_events` and `decisions`
   had declared ring caps (`DiagDao.pruneProbeEvents` / `pruneDecisions`)
   that **no code ever called**. A week of sweeping wrote hundreds of
   thousands of rows. The prune now runs deterministically every 32nd
   ledger flush, inside the same transaction as the write (`RoomLedgerSink`).
2. **The FREE discovery corpus never pruned.** Only ENGINE rows were capped;
   FREE rows accumulated without limit (the "200 000" number the user saw).
   The corpus is now bounded at 20 000 newest rows; USER and ENGINE rows are
   never touched, and orphaned `config_state` rows are removed with it.
3. **The memory-pressure latch never released.** One pressure event halved
   the probe cap toward 2 **for the rest of the process lifetime** — sweeps
   got slower every day after any single spike. The latch now releases when
   the heap settles (`MemoryGuardHandler`), so the AIMD controller can grow
   the cap back on healthy rounds.
4. **Housekeeping only ran on user actions.** A long connected session with
   no interaction never triggered any cleanup. A new `PeriodicSystemHandler`
   heartbeat (2-minute cadence, supervisor-isolated, fully guarded) keeps
   every bound enforced even during hours of idle connection.

## 2. Ping truth (the three reported ping bugs)

- **"Ping starts from the middle of the list."** Manual PING ALL now measures
  **fresh** (`forceFresh = true`): every row shows "Pinging…" and gets a new
  number. v8.8 served 10-minute-TTL cached verdicts instantly for recently
  successful rows while only really probing the failed ones the previous
  sweep had pushed to the bottom — so it *looked* like it skipped the head
  of the list. A manual sweep is an explicit user ask (§5e: clear →
  "Pinging…" → new value).
- **"Pings vanish after closing and reopening the app."** Hydration was
  latched once per process and never retried if the first read failed; it is
  now re-runnable on every tab open, and live results always win the merge.
- **"The list jumps around while pinging."** The Free list keeps its visual
  order for the whole sweep (rows test in place, `Server N` ascending) and
  re-sorts exactly once, when the sweep completes. Auto Test gets the same
  freeze-and-final-sort treatment.
- Per-row sweep armor: one row's unexpected throw can no longer cancel its
  chunk siblings or the whole sweep.

## 3. Auto-failover: a verified connection heals itself

When the watchdog exhausts its full revive ladder (14 consecutive failures),
v8.8 just reported "Connection lost" and stopped. v8.9 picks the best
**measured-connectable** alternative (real measured latency, not
hysteresis-DEAD), switches to it and reconnects through the **same complete
connect gate** — health check plus device-path proof. A failover that cannot
prove itself still ends in `STATE_ERROR`: the "never fake connected" rule is
untouched. The chain is bounded (3 hops without a verified connect) and
resets the moment a session verifies.

Network-transition detection also now covers **same-type swaps** (WiFi
roaming between APs, cellular cell handover that yields a new `Network`
object) — previously only a WiFi↔cellular *type* change revived the tunnel,
so roaming left the session stalled until the watchdog ladder gave up.

## 4. Throughput & experience (the "Instagram is slow" complaint)

- **AUTO now resolves healthy links to STREAM** (the feed/video-tuned mode:
  3 MB buffers, long idle, MPTCP) instead of the generic MASTER/TURBO.
  TFO stays OFF on it per §5l — fixed-line middleboxes that drop TFO SYNs
  must never zero an automatic mode.
- **DNS inside the tunnel has a fallback resolver.** A single resolver meant
  one rate-limited/flapped DoH endpoint stalled every new lookup to its
  timeout. The measured winner plus one core-verified fallback are now
  emitted (`disableFallback` stays false).
- **The port policy and fragmentation settings are real now.** The Ports
  screen's preference feeds the triage ranking (ordering bias only — never a
  filter, §5i), and the Fragmentation screen's custom length/interval ranges
  reach the freedom fragmenter through the `TransportPlan` (§9d-verified
  keys only; GHOST's fixed camouflage is never weakened).
- **The port catalogue grew from 16 to 24 distinct ports** (6443, 5228,
  5349, 10000, 2080, 2088, 2090, 2091 added; original indices untouched so
  persisted fingerprints and strategy weights stay valid).

## 5. Free tab works like the user actually uses it

- **A config that pings green is promoted to My Configs immediately** — no
  manual trip to the other tab (idempotent, through the sanctioned
  `addPromoted` path, never capped per-location).
- **Selecting a row in Free selects it everywhere**: Home re-renders on every
  tab entry (tab switches use show/hide, so onResume never fired before —
  the Home card stayed stale after a Free-tab selection).
- **The Free list keeps its order while the engine sweeps** (see §2) and its
  selected-row highlight now reflects the app-wide selection.

## 6. UI fixes

- **Home banner shows the WHOLE image** (FIT_CENTER instead of CENTER_CROP),
  at a proper height (140dp / 160dp tall screens) with a small inset from
  the screen edges — no more fullscreen-cropped strip.
- **Light theme is legible everywhere.** The invisible PING ALL label
  (white-on-white), the near-black labels on green pill buttons, the
  low-contrast red danger labels, the light dialog's negative button, the
  title shadow glows and the checkbox tints are all theme-aware (`?attr`)
  now — verified against both theme blocks.
- **No raw config name anywhere.** The notification title, every toast, the
  terminal boot line and the connection summary all go through the numbered
  display name guard (`ServerNamer.safeDisplay`).

## 7. Crash armor (the "never crash" requirement)

- The new `PeriodicSystemHandler` runs under a `SupervisorJob` with
  `runCatching` around every step; a housekeeping failure cancels nothing
  but that tick.
- The corpus prune is fire-and-forget on its own IO scope with an in-flight
  flag (never blocks the main thread; the cleanup entry points are called
  from main).
- The new fragment callbacks all carry `::adapter.isInitialized` guards
  (state-restore ordering) and `runCatching` around hydration.
- Nothing in the VPN data path goes through any guard — a tunnel failure
  still surfaces as `STATE_ERROR` (crash containment never hides a tunnel
  failure).

---

## Invariants preserved

Every rule in `AI_AGENT_GUIDE.md` was checked against this release: the
post-connect health check and device-path proof are untouched; no `Random()`
anywhere near a stat; only VLESS/VMESS; all tuning flows through
`TransportPlan` fields (the new fragment overrides are plan fields, and the
measurement plan signature includes them so the ping and connect paths stay
identical); no Xray key outside §9a and no spelling-sensitive value outside
`CoreCapabilities`; deep-probe concurrency bounds unchanged; automatic
decisions never write user keys; Reality/Vision stay `Shaping.CLEAN`;
TFO stays off on the AUTO modes; `strings.xml` untouched (append-only rule).
