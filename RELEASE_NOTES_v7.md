# Professor VPN v7

Release date: 2026-08-02

Professor VPN v7 makes ping, Auto Test, and connected-country reporting strict, independent, and deterministic while preserving the real Xray + tun2socks VPN lifecycle.

## Real ping contract

- TCP reachability is reject-only and is never displayed as latency.
- A visible ping requires a real Xray tunnel measurement against Cloudflare.
- Every accepted ping also requires a fresh Xray connection to transfer a real response body.
- Historical blending, synthetic smoothing, random values, and conditional payload shortcuts are removed.
- Manual and automatic tests run in exact list order using bounded groups of about ten.

## Auto Test

- The 0–60% phase must find both one usable VLESS source and one usable VMESS source.
- The 60–100% phase installs the 240-config batch into Free Configs and starts real tunnel/payload tests.
- Successful configs pin to the top as results arrive and are copied live to My Configs.
- A newly copied row inherits its validated result once; existing My Configs results are never overwritten.
- Finishing one 240-config batch automatically advances to the next.
- Source cursors now advance correctly across wrapped source waves.
- Shared VLESS/VMESS dedup mutation is synchronized.
- Persistent `Server N` numbering remains monotonic across batches and app sessions.

## Independent ping state

My Configs and Free Configs now have separate:

- status maps,
- sweep jobs,
- progress state,
- cancellation,
- hydration and persistence.

Auto Test operates only on the FREE bucket and cannot reset or cancel a My Configs sweep.

## Exit identity and flags

- Connected exit IP and country come from Cloudflare `/cdn-cgi/trace` through the active tunnel.
- Session-generation checks before and after the trace request prevent an old connection from overwriting a newer connection's country.
- The first trace attempt begins immediately after the already-verified connection; only retries wait.
- Third-party geo-IP fallback services were removed.
- Iran uses a bundled real historical Lion-and-Sun PNG; the Islamic-Republic flag emoji remains suppressed.

## Direct network policy

- Config and remote-data requests connect directly to their origin with `Proxy.NO_PROXY`.
- DNS bootstrap uses Cloudflare DoH over IP literals.
- Runtime probes are Cloudflare-only.
- No generic CORS relay, ghproxy variant, forwarding CDN mirror, Google probe, or third-party geo API is used.

## Build

- Version code: 51
- Version name: 7
- Minimum Android: 7.0 / API 24
- Target/compile SDK: 34
- Universal APK ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- Java/Kotlin target: 17

The signed artifact is `ProfessorVPN-v7-universal.apk`.

Release validation:

- CI release compilation: passed
- APK SHA-256: `7a90e39c9b8dd155965fbd941da213a19eefe5ec10a87f7625d61856288a9e3a`
- APK Signature Scheme v2: verified
- Signing certificate SHA-256: `6a5ed5e32014ee77b41ca9ef9c71c5ab3397156d25fe22c7f1d52bb8907eb82d`
- Package: `com.neonvpn.app`, version code 51, version name 7
- ZIP integrity and 4-byte alignment: verified
- Bundled ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
