# Professor VPN v8.8

**The privacy, naming and WiFi release.** Server names are now always numbered
(Server 1, Server 2, …) everywhere and forever; the app carries no reference to
its own distribution infrastructure; and the engine's behavior on WiFi /
fixed-line internet is fixed and measurably faster.

## Fixed

- **Real server names are never shown — anywhere.** Auto Test draws configs
  from the streaming corpus, and those rows kept their original feed names
  (channel tags and remarks), which then surfaced in the lists and in copied
  links. Now every config is renamed to a numbered `Server N` the moment it
  enters the app — searched, pasted, imported, promoted or copied — from one
  persistent counter that never resets. A one-time upgrade migration renames
  everything already stored (chunked and resumable, so huge lists upgrade
  without a hiccup). Your configs' identities, pings and selections are
  untouched.
- **WiFi networks found no working configs.** Three root causes, all fixed:
  the WiFi auto-modes sent TCP Fast Open, which many fixed-line ISPs' equipment
  silently drops — every handshake died before it started; all WiFi networks
  shared one 12-hour cached network profile, so measurements taken on one
  network were replayed on another; and the per-network strategy the engine
  learned never actually reached the connection settings. TFO is now off on
  WiFi auto modes, every WiFi network gets its own measured profile (privacy
  preserved — only a hash is kept), and the learned strategy now genuinely
  shapes both probing and connecting.
- **Searches that found nothing fresh.** Configs that failed a deep test were
  re-tested every cycle, burning the search budget on dead entries. Failed
  configs now cool down for 30 minutes and are then honestly retried — never
  permanently blacklisted.
- **Phantom timeouts under load.** A probe's deadline used to start while it
  was still waiting for a slot, so busy moments produced "timeout" verdicts on
  configs that were never actually probed. The clock now starts when the probe
  really starts.
- **The probe speed controller never adapted.** It was built in 8.7 but never
  received any feedback, so it stayed at its initial setting forever. It now
  speeds up on healthy links and backs off on timeout clusters, within the
  same RAM-safe bounds as always.

## New

- **No infrastructure identity in the app.** The app no longer contains any
  readable reference to the operator's own distribution hosts — addresses it
  needs are assembled at runtime. Third-party public sources are unaffected.
- **Sharper home banner.** Banner images now fill the banner exactly (no more
  tiny picture in a big box) and are decoded at the banner's real resolution,
  so the artwork is crisp. The banner's size is unchanged.
- **More crash armor.** A new ANR watchdog detects a stuck main thread,
  records diagnostics and relieves probe pressure; a safe-mode boot protects
  the launch after a startup crash; the existing crash handler, UI guards and
  lifecycle handlers are all preserved.
- **Faster first answer.** The unknown-network profile no longer clamps deep
  probing to the minimum (it is "unknown", not "weak"), and the adaptive
  controller now actually learns — the first working config arrives sooner,
  especially on fast links.

## Under the hood

One universal APK for every device (arm64-v8a, armeabi-v7a, x86, x86_64), as
always built from the private source repo. New named behaviors documented in
`AI_AGENT_GUIDE.md` §5l; the measurement, strategy and ingestion algorithms
remain as specified in `docs/algorithms/`. Still true: every number on screen
comes from a real measurement on your device — no fake stats, ever.
