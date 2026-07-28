# Build output

The signed **universal** release APK for Professor VPN is produced by the
GitHub Actions workflow `.github/workflows/build.yml` on every push to
`main` / `genspark_ai_developer`, and on manual dispatch.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v6.7-universal.apk`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / minSdk 24)
- Download the **v6.7** APK from the
  [Releases page](https://github.com/aptixzero/my_prFF_vP_N/releases/tag/v6.7)
  (refreshed automatically on every push to `main` and on manual dispatch),
  or from the workflow run's **Artifacts**.

## Only one APK lives here

This folder holds exactly one APK — the release binary for the current
`versionName` in `app/build.gradle.kts`. When a new version ships, the previous
APK is deleted in the same commit, so this folder can never offer a stale
download.

The previous `ProfessorVPN-v6.6-universal.apk` was removed in favour of v6.7.

## What changed in v6.7

See [`RELEASE_NOTES_v6.7.md`](../RELEASE_NOTES_v6.7.md) for the full write-up.
In short — v6.7 is the release that made **Auto Test** produce **low-ping**
configs, **fast**:

| Fix | How |
|---|---|
| **The 0→60 % bar now really tests your connection** | Phase 1 fetches 10 feeds per kind **in parallel**, then handshakes 6 nodes from each **from your own device**, and **ranks** the sources by measured median latency × hit-rate. It bonds to the source measured best *for you* — instead of stopping at whichever feed answered first, which is what looked "random". |
| **Low-ping configs, fast** | A 48-wide TCP triage wave runs before the deep prober: it drops everything undialable (most of a public feed) and orders the survivors by real measured handshake time — a hard *lower bound* on tunnel ping — so the nearest nodes are probed first and land in My Configs within seconds. |
| **No more 250 ms+ junk** | Auto Test's acceptance bar dropped **8 000 ms → 2 500 ms**. Eight seconds was never a working VPN; that setting is why the list filled with unusable nodes. Accepted configs are also stored sorted ascending by their real ping. |
| **20 more sources (70 total)** | All vless/vmess, every URL verified live before being added. **No existing feed removed.** |
| **The barcode opens the browser** | The payload is now always an explicit `https://…` (a scheme-less payload is *text* to a camera, which is why it only offered "copy"), the manifest declares the Android 11+ `<queries>` needed to launch a browser at all, and `openInBrowser` falls back through a chooser instead of failing silently. |

Everything measured is real: no `Random`, no proxies, Cloudflare-only probe
endpoints, and the TCP measurement is used **only** to reject and to order —
never displayed. Every number the user sees still comes from the full `Pinger`
pipeline including the payload verdict.
