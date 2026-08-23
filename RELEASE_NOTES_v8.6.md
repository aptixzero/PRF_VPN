# Professor VPN v8.6 — Disconnect That Sticks, Honest Ping, Home Button Stays On Screen

**versionCode 67 · versionName 8.6 · universal APK (arm64-v8a, armeabi-v7a, x86, x86_64) · minSdk 24**

Built locally with `./gradlew :app:assembleRelease` (JDK 17, compileSdk 34).
Signed with the same release key as v6.7 onwards (certificate SHA-256
`6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`, SHA-1
`df7d72072d0593cd2ed4554c95f283266eaec569`), so v8.6 installs **over** an
existing install.

APK SHA-256 `f8da60f3779b93d6443440220995c3e5ba2092952a2adf0b5007823780531b0b`,
60,054,374 bytes.

v8.6 is a **bug-fix** release. The tunnel engine and the three-stage ping
pipeline stay. What changes is that disconnect actually tears the session
down, a ping is not shown unless payload was proven, and the home connect
button stays on screen.

---

## 1. Disconnect stops the tunnel and the notification

- The UI no longer forces `DISCONNECTED` after 1.5s if a newer connect has
  already started (`stopEpoch`).
- Stop is sent as `ACTION_STOP` first. The service does not re-post
  "Disconnecting…" when a session is already running.
- Notification updates are ignored while `stopping || !running`.
- A `START_STICKY` restart with a null intent no longer brings the tunnel
  back after a stop.
- If a connect generation is superseded mid-setup, the half-built TUN /
  xray / tun2socks session is torn down instead of left running.
- A failed start (for example no TUN) now drops the foreground
  notification after `pendingConnects` is released, instead of leaving
  "Connecting…" stuck.

## 2. Connected means the device path works

The connect gate only accepts a live device-path probe. A core-only
`measureDelayInstant()` success is not treated as connected.

## 3. Ping without payload is not a ping

`Pinger.adjustForThroughput` used to return the latency when the 100 KB
throughput probe failed (`kbps < 0`). That config now comes back
unreachable, same as a throughput below the minimum.

## 4. Home log no longer pushes the connect button off-screen

The outer `ScrollView` around the home column is gone, so the terminal
well (`0dp` + weight 1) can actually cap its height. The log buffer is
48 lines. Exit-IP summaries are appended only when the IP or country
changes, not on every `onResume`.

## 5. Start Search is unchanged in shape

Free-tab Start Search still opens the connection-test page, measures the
link, applies the optimizer, and starts the auto-test engine. It does
not silently start the VPN.

---

## Upgrade notes

- Installs over v8.5 — same signing identity.
- Persisted config format is unchanged.
