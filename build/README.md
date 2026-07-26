# Build output

The signed **universal** release APK for Professor VPN is produced by the
GitHub Actions workflow `.github/workflows/build.yml` on every push to
`main` / `genspark_ai_developer`, and on manual dispatch.

- Output name: `ProfessorVPN-v<versionName>-universal.apk`
  (current: `ProfessorVPN-v6.5-universal.apk`)
- ABIs bundled in the single APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  (Android 7.0+ / minSdk 24)
- Download the **v6.5** APK from the
  [Releases page](https://github.com/aptixzero/my_prFF_vP_N/releases/tag/v6.5)
  (single latest release, refreshed on every push to `main` / manual dispatch),
  or from the workflow run's **Artifacts**.

Only the latest version is kept here; the previous
`ProfessorVPN-v6.4-universal.apk` was removed in favour of v6.5 — see
[`RELEASE_NOTES_v6.5.md`](../RELEASE_NOTES_v6.5.md) for what changed
(reconnect/config-switch fix, stall fix, ping-vs-connect fix, and the barcode
interleave fix).
