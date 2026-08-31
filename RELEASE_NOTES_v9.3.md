# Professor VPN v9.3

## Fixed

- **Tunnel no longer dies after a few minutes.** A process kill used to restart the VPN service with a null intent, which the app treated as “user stopped”. The user’s connect intent is now persisted; START_STICKY restores the tunnel. The VPN service is `stopWithTask=false` and uses the system-exempted foreground type Android expects for VpnService.
- **Server names are identity, not list position.** “Server 1” is one config. Sorting or adding a batch does not rename a different config to Server 1. Duplicate address:port / hash rows are still merged out.
- **Live pin/sink.** A config that gets a real ping jumps to the top immediately. A config that fails sinks to the bottom immediately.
- **PING ALL clears old numbers first**, then measures fresh. After force-stop, only the last real connectable ping is restored — never a leftover or invented number.
- **Disconnect is immediate.** TUN and core are closed on the stop tap, not after the session queue drains, so rapid connect/disconnect does not leave an orphan tunnel.

versionCode 74. Same signing key as v9.2.
