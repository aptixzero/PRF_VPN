# Professor VPN v9.2

## Fixed

- **Free list no longer resets.** A new search batch is merged into the list you already have. Server 1…270 is not replaced by a new Server 1…93. Duplicates (same hash or same address:port) are dropped.
- **Pings actually show.** List / Auto Test measure the first-answer e2e path (no 100 KB sustain gate that hid every number on a throttled link). A config that proves traffic gets a millisecond.
- **Ping counter resets per batch** and then only moves forward (`tested X / total N`).
- **Greens stay pinned at the top.** Unreachable rows sink to the bottom. They are not deleted at the end of a cycle.
- **Notification does not show ping.** Auto Test notification is “N working configs” only.

Same signing key as v9.1. versionCode 73.
