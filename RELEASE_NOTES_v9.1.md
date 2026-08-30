# Professor VPN v9.1 — The Truthful Ping & Rock-Solid Tunnel

The one law: **if a config shows a ping, it must connect. If it will not connect, it must not show a ping.**

## Fixed

- **Fake / unstable pings.** The "unstable" badge is gone. A config is either
  Testing, Reachable with a real millisecond, or Unreachable. A throughput-floor
  miss no longer paints a green number.
- **PING ALL order.** Sweeps take a frozen snapshot and walk it from row 1.
  Previous results are cleared first. Progress `tested X / total N` never goes
  backwards.
- **Pings survive restart.** Room `config_state` is the store of record; each
  verdict is written as it lands.
- **Free-tab tap selects** the config for Home without leaving the tab.
- **My Configs numbering** is 1, 2, 3… on screen. COPY never carries a feed name.
- **Connect/disconnect.** The pending-stop latch is unchanged (a double-tap
  Disconnect is still STOP). Ports 10808/10809 are asserted free before every
  core start; a held port force-releases rather than sharing.
- **STOP** goes through one `SweepController.stopAll()`.
- **Light / Dark / System** theme persists.
- **Home banner** decodes at the view's real size.

## Not claimed without a device measurement

Iranian-mobile throughput re-derivation, Instagram speed, 50-cycle connect
stress, and a 200k soak were **not** run in the build sandbox (no outbound
network on the emulator). The 50 KB/s floor is unchanged and is a rejection,
not a warning.

## Artifact

`ProfessorVPN-v9.1-universal.apk` — versionCode 72, versionName 9.1.
