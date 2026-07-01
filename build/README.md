# Build output

The signed **universal** release APK for Professor VPN is produced by the
GitHub Actions workflow `.github/workflows/build.yml` on every push to
`main` / `genspark_ai_developer`, and on manual dispatch.

- Output name: `ProfessorVPN-v<versionName>-universal.apk` (e.g. `ProfessorVPN-v4.6-universal.apk`)
- Download it from the workflow run's **Artifacts**, or from the
  **Releases** page (published on `workflow_dispatch` / tag).

Only the latest version is kept — older APKs are not committed to the repo.
