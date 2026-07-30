# Build output

The signed **universal** release APK for Professor VPN is produced by the
GitHub Actions workflow `.github/workflows/build.yml` on every push to
`main` / `genspark_ai_developer`, and on manual dispatch.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v6.9-universal.apk`, `versionCode 50`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / minSdk 24)
- APK SHA-256: `feca62786fc7a1570bf95084bbca7696a77b21fb2af73a6b1c01e6449db5a6d8`
  (byte-identical to the asset published on Release v6.9)
- Signed with the same release key as v6.7 / v6.8
  (SHA-256 `6a5ed5e3…07eb82d`), so v6.9 installs **over** an existing
  Professor VPN without uninstalling first.
- Download the **v6.9** APK from the
  [Releases page](https://github.com/aptixzero/my_prFF_vP_N/releases/tag/v6.9)
  (refreshed automatically on every push to `main` and on manual dispatch),
  or from the workflow run's **Artifacts**.

## Only one APK lives here

This folder holds exactly one APK — the release binary for the current
`versionName` in `app/build.gradle.kts`. When a new version ships, the previous
APK is deleted in the same commit, so this folder can never offer a stale
download.

The previous `ProfessorVPN-v6.8-universal.apk` was removed in favour of v6.9.

## What changed in v6.9

See [`RELEASE_NOTES_v6.9.md`](../RELEASE_NOTES_v6.9.md) for the full write-up.
v6.9 is the **fundamental** release: Auto Test no longer hunts for the *best*
source, it takes the **first reachable** VLESS source and the **first reachable**
VMESS source, in parallel, and every intermediary/proxy has been deleted from
the codebase.

| Fix | How |
|---|---|
| **Auto Test no longer parks at ~30% for 5+ minutes** | `ConnectivityProbe` used to fetch 10 feeds per kind *sequentially* (VLESS band ended at 32%), TCP-sample 6 nodes per feed, then score and rank them. v6.9 opens **14 feeds of each kind simultaneously**, and the first usable body per kind wins (`CompletableDeferred`). Ranking, `SourceScore`, `median` and `orderByProximity` are **deleted**. Overall budget 120 s → **45 s**. |
| **`0%→60%` = reach the sources, `60%→100%` = ping them** | Phase 1 races the feeds and collects **120 VLESS + 120 VMESS = 240**; at 60% the batch is written to the Free list and the real ping sweep starts, visibly, in front of the user. |
| **Configs actually land in the Free list** | The old code skipped `saveResult` when the probe returned empty and then called `PingService.clear(FREE)`, which wiped badges *and cancelled the running sweep*. Now there is a **rescue pass** (`FreeConfigSource.nextBatch` under a 25 s budget when the race reached a source but produced no links) and only `PingStore(FREE).clear()` is used. |
| **"It stops after 240 and never loads the next 240"** | The sticky source bond re-read the **same exhausted feed** for ever, producing empty batches until `MAX_EMPTY_STREAK = 6` stopped the engine permanently. v6.9 treats the bond as a **hint only** (first in the first wave, never exclusive), always advances the cursor past the furthest source touched, adds **recycle-and-retry** (reset seen-keys + clear bond + invalidate fetcher, then a second parallel pass), raises the streak limit to **24**, and adds a full cold-restart recovery step. |
| **Pings: fast, real, stable, multi-handler** | `Pinger` is now an explicit **H0→H4 handler chain**: H0 TCP handshake (reject-only), H1 zero-DNS Cloudflare IP literal, H2 alternate Cloudflare edges, H3 warm confirmation, H4 payload verdict — and **H4 is now conditional** (only for marginal/single-sample configs), cutting native Xray core spin-ups from ~3 to **1** per typical config. Manual gate 6–12 → **8–16**. `stabilise()` blends consecutive **real** measurements 70/30 so the number stops jerking around. |
| **`Ping all` no longer "skips"** | `pingAll` flipped `running = true` *inside* the coroutine, so a caller polling `isSweepRunning` right after the call saw `false` and bailed. State is now published **synchronously before** the launch, completion is guaranteed by `try/finally`, and admission uses the new `isManualSweepRunning` (so Auto Test's own sweep no longer blocks the button). |
| **The ping process is visible everywhere** | New `PingService.publishExternalSweep()` lets `AutoTestEngine` drive the same progress bar the manual sweep uses. Both **My Configs** and **Free configs** now show `Pinging… tested/total · NN%` for automatic *and* manual runs, and the Auto Test screen has a live phase label. |
| **Rapid config switching** | Tapping a config while connected/connecting now sends a start `Intent` straight to `NeonVpnService` (which already handled switching via a generation counter on a single session thread) and flips the UI to `CONNECTING`. Wired in **both** `ConfigsFragment.selectServer` and `FreeConfigsFragment.saveToMyConfigs`. |
| **ZERO intermediaries** | `cdn.jsdelivr.net` / `fastly` / `gcore` mirror chains and the 7 `bin.mudfish.net` paste sources are **gone**; `CcNewFeed.mirrorChain()` returns exactly one origin URL. There is no `gh.proxy.com` anywhere in the repo — it never existed here. All HTTP pins `Proxy.NO_PROXY`, DNS is **Cloudflare DoH** over IP literals (`1.1.1.1` / `1.0.0.1`, no bootstrap lookup to poison), and every probe endpoint is Cloudflare (`https://1.1.1.1/cdn-cgi/trace`). **No Google, no proxy, no CDN — the ping is the user's own internet.** |

Everything measured is real: no `Random`, no estimated or faked numbers
(enforced by `app/src/androidTest/.../NoRandomInStatsTest.kt`), Cloudflare-only
probe endpoints, and the TCP handshake is used **only** to reject and to order —
never displayed. Every number the user sees comes from the full `Pinger`
pipeline. Only **VLESS** and **VMESS** are supported, including
paste-from-clipboard.
