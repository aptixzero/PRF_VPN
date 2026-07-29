# Build output

The signed **universal** release APK for Professor VPN is produced by the
GitHub Actions workflow `.github/workflows/build.yml` on every push to
`main` / `genspark_ai_developer`, and on manual dispatch.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v6.8-universal.apk`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / minSdk 24)
- Download the **v6.8** APK from the
  [Releases page](https://github.com/aptixzero/my_prFF_vP_N/releases/tag/v6.8)
  (refreshed automatically on every push to `main` and on manual dispatch),
  or from the workflow run's **Artifacts**.

## Only one APK lives here

This folder holds exactly one APK — the release binary for the current
`versionName` in `app/build.gradle.kts`. When a new version ships, the previous
APK is deleted in the same commit, so this folder can never offer a stale
download.

The previous `ProfessorVPN-v6.7-universal.apk` was removed in favour of v6.8.

## What changed in v6.8

See [`RELEASE_NOTES_v6.8.md`](../RELEASE_NOTES_v6.8.md) for the full write-up.
In short — v6.8 is the **"make it fast again"** release. It attacks the one root
cause of the slowdown: every real ping builds its own throwaway native Xray core,
and up to v6.7 a single config paid for **up to five** of them.

| Fix | How |
|---|---|
| **~3 native cores per config instead of ~5** | Latency samples 3→2, and the payload verdict now issues **one** zero-DNS probe instead of looping over up to three heavy payload URLs. Tighter budgets (per-config 9→6 s) stop dying nodes burning the long tail. |
| **Wider deep gates** | Manual PING ALL 4–8 → **6–12** concurrent; Auto Test 3–6 → **5–10**. Safe because each config is now much cheaper, so low-ping configs land in My Configs within seconds. |
| **"Ping All fires but pings nothing" — fixed** | If a momentary link drop makes the wide TCP pre-gate reject *every* node, v6.8 hands the whole list to the real prober instead of painting it all red. |
| **The verdict is still real** | The one probe still opens a fresh connection and reads a real body, so *"if it pings, it connects"* stays true. |

Everything measured is real: no `Random`, no proxies, Cloudflare-only probe
endpoints, and the TCP measurement is used **only** to reject and to order —
never displayed. Every number the user sees still comes from the full `Pinger`
pipeline including the payload verdict. Only VLESS/VMESS supported.
