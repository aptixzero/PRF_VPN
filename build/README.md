# Build output

The signed **universal** release APK for Professor VPN is produced by the
GitHub Actions workflow `.github/workflows/build.yml` on every push to
`main` / `genspark_ai_developer`, and on manual dispatch.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v7-universal.apk`, `versionCode 51`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / minSdk 24)
- APK SHA-256: `7a90e39c9b8dd155965fbd941da213a19eefe5ec10a87f7625d61856288a9e3a`
  (byte-identical to the asset published on Release v7)
- Signed with the same release key as v6.7 / v6.8 / v6.9
  (SHA-256 `6a5ed5e3…07eb82d`), so v7 installs **over** an existing
  Professor VPN without uninstalling first.
- Download the **v7** APK from the
  [Releases page](https://github.com/aptixzero/my_prFF_vP_N/releases/tag/v7)
  (refreshed automatically on every push to `main` and on manual dispatch),
  or from the workflow run's **Artifacts**.

## Only one APK lives here

This folder holds exactly one APK — the release binary for the current
`versionName` in `app/build.gradle.kts`. When a new version ships, the previous
APK is deleted in the same commit, so this folder can never offer a stale
download.

The previous `ProfessorVPN-v6.9-universal.apk` was removed in favour of v7.

## What changed in v7

See [`RELEASE_NOTES_v7.md`](../RELEASE_NOTES_v7.md) for the full write-up.
v7 requires a real Xray tunnel and payload transfer for every visible ping,
processes configs in ordered bounded groups, isolates My/Free ping state, keeps
Auto Test moving across consecutive 240-config batches, and reports exit country
from the active tunnel with a bundled Lion-and-Sun image for Iran.

| Fix | How |
|---|---|
| **Visible ping proves a usable VPN** | TCP is reject-only. A visible result requires a real Xray Cloudflare measurement and a fresh connection that transfers a real response body. |
| **Ordered, bounded sweeps** | My Configs, Free Configs, and Auto Test process stable windows of about ten in exact list order; successful rows pin immediately while untouched rows keep their order. |
| **Independent tabs** | My and Free use separate result stores, sweep jobs, progress, cancellation, hydration, and persistence. Auto Test cannot reset My Configs. |
| **Continuous Auto Test** | Phase one finds usable VLESS and VMESS sources; phase two installs and tests 240 configs, advances persistent cursors and monotonic names, then automatically continues with the next batch. |
| **Stable exit identity** | IP and country are read from Cloudflare trace through the active tunnel and guarded by session generation. Iran uses the bundled historical Lion-and-Sun PNG. |
| **Zero intermediaries** | Runtime network requests use direct origins, `Proxy.NO_PROXY`, Cloudflare DoH, and Cloudflare-only probes—no forwarding proxy, CDN mirror, Google probe, or third-party geo API. |

The APK passed CI compilation, ZIP integrity, four-ABI inspection, zip alignment,
APK Signature Scheme v2 verification, package/version inspection, and signing-key
continuity checks.
