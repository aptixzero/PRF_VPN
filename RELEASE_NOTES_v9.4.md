# Professor VPN v9.4

## Fixed

- **Pings survive minimize / tab switch / screen-off.** Ping sweeps no longer run on `ProcessLifecycleOwner.lifecycleScope`, and backgrounding no longer cancels them. Completed numbers stay in memory and in Room; PING ALL no longer wipes stored results at start.
- **A ping is a connectable tunnel.** List ping and Auto Test use the STANDARD lane (real e2e plus sustained payload). A green number means the node already carried real bytes, so tapping Connect on it must work.
- **Displayed ping does not wobble.** Live stats hold the number still through jitter under 15% / 30 ms. Connecting no longer overwrites the list ping with a different device-path HTTP time.
- **Disconnect is instant and does not glitch.** TUN drops on the stop tap. Xray JNI stop runs on the session thread, so the button cannot freeze on `stopLoop`.
- **Watchdog no longer kills a working tunnel.** If Cloudflare delay misses but TUN counters are still moving, the session is held. Instagram scrolling must not look like a dead core.
- **Search survives screen-off and a long find.** The connection-test page analysis runs on a process-lifetime scope; the Auto Test FGS starts as soon as Search is pressed. Closing the page does not cancel discovery. A new batch is merged, not replaced, so greens stay.
- **Reset to default actually resets.** The hub button is renamed **Reset to default** and now clears ports, strategy, probe cap, and fragmentation overrides as well as the v8.4 controls, returning every setting to automatic.

versionCode 75. Same signing key as v9.3. Universal APK (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
