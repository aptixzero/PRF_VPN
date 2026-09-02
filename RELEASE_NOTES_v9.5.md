# Professor VPN v9.5

## Fixed

- **Connection-test page no longer freezes at 97%.** v9.4 started Auto Test in
  parallel with the source collect. Both hammered the same feeds on a slow
  Iranian link, the bar reached 97%, and the page never handed configs to the
  sweep. Analysis now finishes first; the engine starts only after that — or
  in `finally`, so a hung collect cannot trap the UI.
- **A 97% collect cannot block forever.** The probe budget returns on time even
  if OkHttp is still sitting on a socket. Collect loops check cancellation.
- **Progress bar updates stay on the UI thread.** Reaching 100% no longer
  crashes the analysis coroutine, which is what stopped pin / My Configs /
  the ping sweep from starting.

versionCode 76. Same signing key as v9.4. Universal APK.
