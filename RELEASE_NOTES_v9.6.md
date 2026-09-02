# Professor VPN v9.6

## Faster first working config

- **Search no longer waits for 240 configs.** The connection-test page races
  sources, then hands ~36 configs to Auto Test so pinging starts immediately.
  Later cycles still fill the rest.
- **Discovery uses the fast FIRST_ANSWER lane** (real e2e through a live core,
  no 100 KB payload tax). A dead row is skipped instead of burning a second
  15 s retry in-place — that was why the first green took minutes.
- **Fresh network profile is reused.** If this link was measured in the last
  12 hours, Search skips the 32 s profiler and applies the cached filter
  (ports, DNS, DPI) immediately.

Connect still verifies the device path. A green ping is still a real e2e
answer, just found much sooner.

versionCode 77. Same signing key as v9.5. Universal APK.
