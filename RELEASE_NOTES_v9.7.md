# Professor VPN v9.7

## Fixed

- **New configs actually get added and pinged.** After the first batch, every
  later cycle dropped the configs it had just collected (they were already in
  `seenKeys`). The Free list froze on the same Server 5 / 6 / 10… and the
  sweep had nothing new to measure. Live-source batches are now walked as
  collected.
- **No duplicate pings.** One measurement per canonical id and per
  `address:port` for this search. PING ALL uses the same rule.
- **Counter matches the row being measured.** Progress is `Checked n/N · Server X`
  (the remark of the config just probed), not a list index that looked like a
  server number.
- **A miss gets one fast retry** on the same FIRST_ANSWER lane so a real node
  is not silently left with no ping.

No RNG feeds any displayed ping, count, or server pick.

versionCode 78. Same signing key as v9.6. Universal APK.
