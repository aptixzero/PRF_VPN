# Professor VPN — Public Distribution

This repository is intentionally limited to public runtime artifacts.

- Latest release: [Professor VPN v7.4](https://github.com/aptixzero/my_prFF_vP_N/releases/latest)
- Download page: <https://professorvpn.vercel.app/>
- Android remote configuration: `adminpanel/app_config.json`
- Config bridge: `bridge/` — refreshed every 3 hours by
  [`.github/workflows/bridge.yml`](./.github/workflows/bridge.yml) and served from this
  repository's Pages site, giving the app a second origin independent of
  `raw.githubusercontent.com`. Static files only; no user request is proxied.
- Website assets and release APK are retained for uninterrupted compatibility.

The Android source, build pipeline, signing material, and admin-console source are maintained in private repositories. No administrator login is hosted from this repository.

## Integrity

`ProfessorVPN-v7.4-universal.apk`

```text
SHA-256  d033732034af06fb74709c55cb3b5d8fe22d35c8395735eba4faaba0e777c2ce
```

Security reports should be sent privately to the repository owner rather than opened as public issues.
