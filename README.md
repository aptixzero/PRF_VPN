# Professor VPN — Public Distribution

This repository is intentionally limited to public runtime artifacts.

- Latest release: [Professor VPN v9.7](https://github.com/aptixzero/my_prFF_vP_N/releases/latest)
- Download page: <https://professorvpn.vercel.app/>
- Android remote configuration: `adminpanel/app_config.json`
- Config bridge: [`bridge/aggregate.py`](./bridge/aggregate.py) — run every 3 hours by
  [`.github/workflows/bridge.yml`](./.github/workflows/bridge.yml), which publishes the
  aggregated snapshot to the [`bridge`](https://github.com/aptixzero/my_prFF_vP_N/releases/tag/bridge)
  pre-release and to the `gh-pages` branch. This gives the app origins independent of
  `raw.githubusercontent.com`. Static files only; no user request is proxied.
  Only the script is tracked here — the generated lists are published output, not
  source, so they are never committed to `main`.
- Website assets and release APK are retained for uninterrupted compatibility.

The Android source, build pipeline, and signing material are maintained in private repositories.

## Integrity

`ProfessorVPN-v9.7-universal.apk`

```text
SHA-256  69cf971f8879e222daaf72da28bd60ea0ce11798b5a15aa6f609019c3441d6be
```

Security reports should be sent privately to the repository owner rather than opened as public issues.
