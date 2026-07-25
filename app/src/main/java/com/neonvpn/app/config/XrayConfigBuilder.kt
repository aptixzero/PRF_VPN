package com.neonvpn.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full, valid Xray-core JSON config from a [ServerConfig].
 *
 * v5.5 — REAL WORKING TUNNEL (restored from the last known-good method).
 *
 * The v4.6 → v5.4 line was BROKEN: those versions kept stripping the pieces that
 * actually make the tunnel carry traffic on a filtered network. v5.3 hid the
 * anti-DPI fragmentation behind a `dialerProxy` chain (which measureOutboundDelay
 * does NOT walk, so ping lied and the live path often broke); v5.4 then removed
 * the DNS block AND the fragmentation entirely — leaving a config that finishes
 * a TCP/TLS handshake (so ping is green and the UI turns "Connected") yet gets
 * its ClientHello RST-injected by DPI, so NO real bytes ever flow. That is
 * exactly the "shows connected but nothing works / doesn't use the config" bug.
 *
 * This version goes back to the proven approach — the one that WORKED:
 *
 *   1. A proper `dns` block (encrypted DoH for the free internet to beat DNS
 *      poisoning + a domestic resolver for Iranian domains) so name resolution
 *      never wedges and local sites stay fast.
 *   2. TLS-ClientHello FRAGMENTATION applied DIRECTLY in the proxy outbound's own
 *      `streamSettings.sockopt.fragment` (NOT via a dialerProxy chain). Splitting
 *      the ClientHello across tiny TCP segments hides the SNI from DPI, so the
 *      connection survives instead of being reset — this is what makes real
 *      traffic actually flow through the selected config.
 *   3. Full-tunnel routing: everything except Iranian domains / private LAN goes
 *      through the proxy, at full bandwidth — a fast filtershkan, not a proxy.
 *
 * Only vless / vmess are supported (project policy).
 */
object XrayConfigBuilder {

    const val SOCKS_PORT = 10808
    const val API_PORT = 10809

    const val PROXY_TAG = "proxy"

    fun build(cfg: ServerConfig): String {
        val root = JSONObject()

        // ---- log ----
        root.put("log", JSONObject().apply { put("loglevel", "warning") })

        // ---- stats + api (so queryStats returns real up/down counters) ----
        root.put("stats", JSONObject())
        root.put("api", JSONObject().apply {
            put("tag", "api")
            put("services", JSONArray().apply { put("StatsService") })
        })
        root.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    // v6.2 — TUNED FOR CELLULAR STABILITY + FULL SPEED. The earlier
                    // values (handshake 4s, connIdle 300s) were tuned for WiFi and
                    // caused two problems on SIM/cellular data:
                    //   • a 4s handshake budget is too tight on a high-RTT mobile
                    //     link whose first hop can itself take 2-3s, so a perfectly
                    //     good node was falsely declared dead on cellular (the
                    //     "doesn't work / unstable on SIM data" report);
                    //   • connIdle 300s + uplinkOnly/downlinkOnly 1s meant a momentary
                    //     lull in traffic (common on a flapping cellular link during
                    //     a switch between cell towers) tore the connection down.
                    // The new settings give the tunnel a generous handshake window,
                    // a long idle grace so a transient cellular dip never drops it,
                    // and larger buffers so a bursty mobile link sustains full speed.
                    // v6.3 — WEAK-LINK TUNING. The brief asks the app to "connect
                    // fast on very weak internet, work reliably, never drop". Two
                    // more adjustments on top of the v6.2 cellular tuning:
                    //
                    //   • handshake 8s → 15s. On a genuinely weak link the very
                    //     first TLS handshake through the tunnel routinely needs
                    //     more than 8s (the local hop alone can eat 4-5s). An
                    //     8s budget aborted the handshake and the user saw
                    //     "connects then immediately fails" on a bad signal. A
                    //     wide budget costs nothing when the link is good, because
                    //     a healthy handshake still completes in well under a
                    //     second — it only helps the weak case.
                    //
                    //   • connIdle 600s → 900s and uplinkOnly/downlinkOnly 12 → 20.
                    //     A half-open direction is normal on a dozing radio; being
                    //     patient here is what stops the "disconnects by itself"
                    //     complaint.
                    // ── v6.4 — THE "WORKS FOR A MINUTE THEN FREEZES" FIX ─────
                    // Reported bug: *"I connect, open Instagram, it works for
                    // about a minute, then the videos freeze / lock up. If I
                    // disconnect and reconnect once inside the app, it starts
                    // working again."*
                    //
                    // `connIdle = 900` was a large part of that. A free public
                    // node typically caps how many concurrent connections one
                    // user may hold. Instagram opens dozens of short-lived
                    // sockets per minute of scrolling; with a 15-MINUTE idle
                    // timeout every one of those finished sockets stayed
                    // allocated on both the phone AND the server. Within about
                    // a minute of browsing the node's per-user limit was
                    // exhausted, so NEW connections stopped being accepted —
                    // videos froze, while the tunnel itself still looked
                    // perfectly healthy (which is why the watchdog never
                    // noticed). Disconnecting dropped every stale socket at
                    // once, which is exactly why a manual reconnect "fixed" it.
                    //
                    // 120s is generous enough that a paused video or a
                    // backgrounded app is never cut off, while dead sockets are
                    // reclaimed fast enough that the pool can never fill up.
                    // Half-open directions are released promptly for the same
                    // reason (a stalled half-open socket is pure dead weight).
                    put("handshake", 12)
                    put("connIdle", 120)
                    put("uplinkOnly", 4)
                    put("downlinkOnly", 4)
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                    // Per-connection buffer. v6.4 tunes this DOWN from 4096 KiB
                    // to 512 KiB — and that is a speed FIX, not a speed cut.
                    //
                    // `bufferSize` is per CONNECTION, not per tunnel. A phone
                    // scrolling Instagram holds 50-100 simultaneous connections;
                    // at 4 MiB each, Xray was being asked to reserve multiple
                    // GIGABYTES of buffer. The Android allocator obviously can't
                    // give it that, so the core started thrashing and stalling
                    // under exactly the load pattern the user described (video
                    // scrolling), which reads as "everything freezes after a
                    // minute". 512 KiB per connection is already well above the
                    // bandwidth-delay product of any Iranian mobile link, so
                    // single-stream throughput is unchanged, while total memory
                    // stays sane and nothing stalls.
                    put("bufferSize", 512)
                })
            })
            put("system", JSONObject().apply {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        // ---- dns (anti-poisoning + Iran-aware) ----
        // Encrypted DoH resolvers defeat Iran's DNS poisoning for the free
        // internet; a fast domestic resolver handles Iranian domains so they stay
        // direct and snappy. Without a working DNS block, name resolution can
        // wedge on some ISPs and nothing loads even on a live tunnel.
        //
        // v6.2 — CELLULAR STABILITY. On SIM data the carrier often blocks or
        // throttles plain 53 to foreign resolvers, and a DoH handshake to a
        // throttled endpoint can stall the whole tunnel's first request. We keep
        // the encrypted DoH resolvers FIRST (they are what beat the poisoning),
        // but we no longer list the plain foreign 53 entries before the domestic
        // resolver — that ordering made a cellular link try a blocked plain-DNS
        // query first and wait for it to time out before falling back. The
        // domestic resolver stays last-and-skipFallback so Iranian domains
        // resolve fast and direct. We also keep the cache ENABLED so a flapping
        // cellular link doesn't re-resolve on every request.
        // v6.4 — DNS is also part of the "freezes after a minute" story. When a
        // name lookup wedges, every NEW request in the app hangs while already-
        // open sockets keep working — which looks exactly like "the feed froze
        // but the VPN says connected".
        //
        //   • `queryStrategy` is pinned to **UseIPv4**. Almost no free node
        //     relays IPv6, so every AAAA record the resolver handed back became
        //     a connection attempt to an unreachable address that had to time
        //     out before the app retried over IPv4. On Instagram (which is fully
        //     dual-stack) that meant a multi-second stall on every fresh domain,
        //     compounding until the feed locked up. Asking for A records only
        //     removes the entire class of failure.
        //   • Cloudflare's DoH is listed first (and reachable by IP), matching
        //     the Cloudflare-only probe policy used everywhere else in v6.4.
        //   • The domestic resolver still serves Iranian domains directly.
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https://1.1.1.1/dns-query")
                put("https://1.0.0.1/dns-query")
                put(JSONObject().apply {
                    put("address", "78.157.42.100")           // domestic resolver
                    put("port", 53)
                    put("domains", JSONArray().apply {
                        put("geosite:category-ir")
                        put("domain:ir")
                    })
                    put("skipFallback", true)
                })
                put("1.1.1.1")
                put("8.8.8.8")
            })
            put("queryStrategy", "UseIPv4")
            put("disableCache", false)
            put("disableFallbackIfMatch", true)
            put("tag", "dns-out-tag")
        })

        // ---- inbounds ----
        // SOCKS inbound that tun2socks feeds all device traffic into, and a
        // dokodemo-door for the stats API.
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", SOCKS_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("address", "127.0.0.1")
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply { put("http"); put("tls"); put("quic") })
                put("routeOnly", false)
            })
        })
        inbounds.put(JSONObject().apply {
            put("tag", "api-in")
            put("port", API_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "dokodemo-door")
            put("settings", JSONObject().apply {
                put("address", "127.0.0.1")
                put("port", 0)
                put("network", "tcp")
            })
        })
        root.put("inbounds", inbounds)

        // ---- outbounds ----
        // proxy FIRST so measureDelay / stats target it. Then a plain freedom for
        // Iranian/local traffic, a dns outbound so the core's own queries resolve,
        // and a blackhole for blocks.
        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(cfg))
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            // v6.4 — IPv4 only here too, for the same reason as the DNS block:
            // a v6 address on a link with no working v6 path is a guaranteed
            // multi-second stall before the fallback.
            put("settings", JSONObject().apply { put("domainStrategy", "UseIPv4") })
        })
        outbounds.put(JSONObject().apply {
            put("tag", "dns-out")
            put("protocol", "dns")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        })
        root.put("outbounds", outbounds)

        // ---- routing (full-tunnel, Iran-aware) ----
        // Order matters (first match wins):
        //   1. stats api  -> api outbound
        //   2. DNS (53)   -> dns outbound
        //   3. bittorrent -> block (so it can't saturate the tunnel)
        //   4. Iranian domains / Iran IPs / private LAN -> DIRECT (fast, no tunnel)
        //   5. EVERYTHING ELSE -> proxy (full bandwidth through the tunnel)
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("api-in") })
                    put("outboundTag", "api")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", 53)
                    put("outboundTag", "dns-out")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("protocol", JSONArray().apply { put("bittorrent") })
                })
                // ── v6.4 — BLOCK QUIC. THE INSTAGRAM-FREEZE FIX. ─────────────
                // Reported bug: *"I connect, go into Instagram, it works for
                // about a minute, then the videos freeze and go stiff. I toggle
                // the VPN off and on once and it starts working again."*
                //
                // That is the textbook signature of QUIC leaking through a
                // tun2socks tunnel, and it is why every serious client (v2rayNG,
                // Nekobox, Clash) ships a "Block QUIC" switch that users are told
                // to enable:
                //
                //   • Instagram, YouTube and Facebook prefer HTTP/3 over QUIC
                //     (UDP 443). QUIC is a UDP protocol carried here as SOCKS5
                //     UDP-ASSOCIATE through hev-socks5-tunnel and then through
                //     the node. Almost no free public VLESS/VMESS node relays UDP
                //     reliably — many drop it entirely after the first few
                //     seconds, and Iranian DPI shapes UDP 443 aggressively.
                //   • QUIC is designed to survive packet loss, so the app does
                //     NOT fail fast and retry: it keeps a "connection" it thinks
                //     is alive and just waits. The video buffer drains and the
                //     feed locks up — while the tunnel itself is perfectly
                //     healthy, which is exactly why the watchdog saw nothing
                //     wrong and why only a manual reconnect (which forces the
                //     app to renegotiate) appeared to fix it.
                //
                // Blocking UDP 443 makes the block INSTANT and EXPLICIT, so
                // Instagram/YouTube immediately fall back to HTTP/2 over TCP —
                // which this tunnel carries perfectly, permanently, at full
                // speed. Nothing is lost: HTTP/3 is only a transport, every one
                // of these services works fully over TCP.
                //
                // The `sniffing.destOverride` list includes "quic", so QUIC is
                // caught both by port and by protocol signature — a client that
                // tries QUIC on a non-standard port is blocked too.
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("network", "udp")
                    put("port", "443")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("protocol", JSONArray().apply { put("quic") })
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply {
                        put("geosite:category-ir")
                        put("domain:ir")
                    })
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:ir")
                        put("geoip:private")
                    })
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", PROXY_TAG)
                    put("port", "0-65535")
                })
            })
        })

        return root.toString()
    }

    /**
     * Minimal config for a per-config ping — the SAME outbound the live connect
     * uses (identical fragmentation + stream settings), so the measured latency
     * is the real latency the tunnel will have. "ping == connects", done honestly.
     */
    fun buildPingConfig(cfg: ServerConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("loglevel", "none") })
        root.put("outbounds", JSONArray().apply {
            put(buildProxyOutbound(cfg))
        })
        return root.toString()
    }

    private fun buildProxyOutbound(cfg: ServerConfig): JSONObject {
        return when (cfg.protocol) {
            "vless" -> buildVlessOutbound(cfg)
            "vmess" -> buildVmessOutbound(cfg)
            else -> buildVlessOutbound(cfg)
        }
    }

    private fun buildVlessOutbound(cfg: ServerConfig): JSONObject {
        val out = JSONObject()
        out.put("tag", PROXY_TAG)
        out.put("protocol", "vless")
        out.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", cfg.userId)
                            put("encryption", "none")
                            if (cfg.flow.isNotBlank()) put("flow", cfg.flow)
                            put("level", 8)
                        })
                    })
                })
            })
        })
        out.put("streamSettings", buildStreamSettings(cfg))
        // Mux stays OFF for VLESS. Reality and XTLS-Vision (flow) are outright
        // incompatible with mux — enabling it corrupts the Vision splice and the
        // tunnel comes up carrying zero bytes ("fake connected"). Even on plain
        // TLS, a dedicated 1:1 stream gives the best single-stream bandwidth with
        // no head-of-line blocking. v6.4 solves connection-pool exhaustion the
        // right way instead: short idle timeouts + blocked QUIC (see build()).
        out.put("mux", JSONObject().apply {
            put("enabled", false)
            put("concurrency", -1)
        })
        return out
    }

    private fun buildVmessOutbound(cfg: ServerConfig): JSONObject {
        val out = JSONObject()
        out.put("tag", PROXY_TAG)
        out.put("protocol", "vmess")
        out.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", cfg.userId)
                            put("alterId", cfg.alterId)
                            put("security", cfg.security.ifBlank { "auto" })
                            put("level", 8)
                        })
                    })
                })
            })
        })
        out.put("streamSettings", buildStreamSettings(cfg))
        out.put("mux", JSONObject().apply {
            put("enabled", false)
            put("concurrency", -1)
        })
        return out
    }

    private fun buildStreamSettings(cfg: ServerConfig): JSONObject {
        val stream = JSONObject()
        var net = cfg.network.ifBlank { "tcp" }.lowercase()
        if (net == "xhttp" || net == "splithttp") net = "tcp"
        if (net !in setOf("tcp", "ws", "grpc", "h2", "http", "kcp")) net = "tcp"
        stream.put("network", net)

        val sec = when {
            cfg.tls.equals("reality", true) -> "reality"
            cfg.tls.equals("tls", true) -> "tls"
            cfg.tls.equals("xtls", true) -> "tls"
            else -> ""
        }
        if (sec.isNotBlank()) stream.put("security", sec)

        if (sec == "tls") {
            stream.put("tlsSettings", JSONObject().apply {
                put("allowInsecure", true)   // public free nodes often use self-signed / fronted certs
                val server = cfg.sni.ifBlank { cfg.host.ifBlank { cfg.address } }
                if (server.isNotBlank()) put("serverName", server)
                put("fingerprint", cfg.fingerprint.ifBlank { "chrome" })  // uTLS browser mimicry
                if (cfg.alpn.isNotBlank()) {
                    put("alpn", JSONArray().apply {
                        cfg.alpn.split(",").forEach { if (it.isNotBlank()) put(it.trim()) }
                    })
                }
            })
        } else if (sec == "reality") {
            stream.put("realitySettings", JSONObject().apply {
                val server = cfg.sni.ifBlank { cfg.address }
                if (server.isNotBlank()) put("serverName", server)
                put("fingerprint", cfg.fingerprint.ifBlank { "chrome" })
                if (cfg.publicKey.isNotBlank()) put("publicKey", cfg.publicKey)
                if (cfg.shortId.isNotBlank()) put("shortId", cfg.shortId)
                put("spiderX", cfg.spiderX.ifBlank { "/" })
            })
        }

        when (net) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                put("path", cfg.path.ifBlank { "/" })
                val hdrHost = cfg.host.ifBlank { cfg.sni }
                if (hdrHost.isNotBlank()) {
                    put("headers", JSONObject().apply { put("Host", hdrHost) })
                }
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", cfg.path)
                put("multiMode", false)
                put("idle_timeout", 60)
                put("health_check_timeout", 20)
            })
            "h2", "http" -> stream.put("httpSettings", JSONObject().apply {
                put("path", cfg.path.ifBlank { "/" })
                if (cfg.host.isNotBlank()) {
                    put("host", JSONArray().apply {
                        cfg.host.split(",").forEach { if (it.isNotBlank()) put(it.trim()) }
                    })
                }
            })
            "kcp" -> stream.put("kcpSettings", JSONObject().apply {
                put("header", JSONObject().apply { put("type", cfg.headerType.ifBlank { "none" }) })
                if (cfg.seed.isNotBlank()) put("seed", cfg.seed)
                put("mtu", 1350)
                put("tti", 50)
                put("uplinkCapacity", 12)
                put("downlinkCapacity", 100)
                put("congestion", false)
            })
            "tcp" -> {
                if (cfg.headerType.equals("http", true)) {
                    stream.put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            put("request", JSONObject().apply {
                                put("path", JSONArray().apply { put(cfg.path.ifBlank { "/" }) })
                                if (cfg.host.isNotBlank()) {
                                    put("headers", JSONObject().apply {
                                        put("Host", JSONArray().apply {
                                            cfg.host.split(",").forEach { if (it.isNotBlank()) put(it.trim()) }
                                        })
                                    })
                                }
                            })
                        })
                    })
                }
            }
        }

        // Socket options + TLS-record FRAGMENTATION applied DIRECTLY on the proxy
        // outbound (NOT via a dialerProxy chain — that was the v5.3 mistake that
        // made ping lie and broke the live path). Splitting the ClientHello across
        // tiny TCP segments means DPI never sees the SNI in one packet, so SNI /
        // keyword blocking and RST-injection can't fingerprint and tear down the
        // connection. This is what makes real traffic actually flow through the
        // selected config on a filtered network. uTLS mimicry (above) + keep-alive
        // keep the tunnel persistent and full-speed.
        //
        // v6.2 — CELLULAR STABILITY TUNING. The earlier keep-alive timers (30s
        // idle / 15s interval) are fine on WiFi but too aggressive on a SIM-data
        // link: a mobile radio that dozes between bursts can miss a 30s keep-alive
        // and the carrier NAT then reaps the idle socket, so the tunnel silently
        // drops "every minute or two" on cellular. We widen the keep-alive idle so
        // the radio's own dormancy windows don't trip it, and we enable TCP
        // Fast Open + no-delay so the first byte after a dormancy resumes at full
        // speed. tcpMptcp stays OFF (MPTCP is poorly supported on Iranian carrier
        // middleboxes and caused spurious resets).
        //
        // v6.3 — WEAK-LINK / NEVER-DROP TUNING.
        //   • keep-alive idle 60s → 45s with interval 15s and count 12. The v6.2
        //     values (60/30/9) survive a carrier NAT with a ~5 min timeout, but
        //     several Iranian mobile NATs reap at ~90s. Probing a little sooner
        //     and more often keeps the mapping warm, and 12 probes means the
        //     tunnel survives ~3 minutes of total radio blackout (a tunnel that
        //     comes back beats one that has to be re-dialled).
        //   • tcpUserTimeout is set generously so the kernel does not abort a
        //     socket that is merely slow — the exact failure mode on weak signal.
        // v6.4 — KEEP-ALIVE / TIMEOUT RETUNE (part of the never-freeze fix).
        //   • tcpKeepAliveIdle 45 → 30 with interval 10 and count 6. A carrier
        //     NAT that reaps at ~60-90s is the norm on Iranian mobile; probing
        //     at 30s keeps every mapping warm with room to spare. Six probes
        //     (~90s of blackout tolerated) is plenty — the previous 12 meant a
        //     socket that was genuinely dead lingered for ~3 minutes, holding a
        //     slot in the node's connection pool and starving new requests. That
        //     is the same pool-exhaustion that froze Instagram after a minute.
        //   • tcpUserTimeout 100s → 30s. Same reasoning: a socket the kernel
        //     cannot drain in 30s is not "slow", it is wedged, and keeping it
        //     alive only blocks the replacement connection from being made.
        stream.put("sockopt", JSONObject().apply {
            put("tcpKeepAliveIdle", 30)
            put("tcpKeepAliveInterval", 10)
            put("tcpKeepAliveCount", 6)
            put("tcpNoDelay", true)
            put("tcpMptcp", false)
            put("mark", 0)
            put("tcpUserTimeout", 30_000)
            if (sec == "tls" || sec == "reality") {
                put("tcpFastOpen", true)
                put("fragment", JSONObject().apply {
                    put("packets", "tlshello")
                    put("length", "100-200")
                    put("interval", "10-20")
                })
            } else {
                // for plaintext transports, fragment the first few packets so
                // protocol-pattern DPI still can't lock on immediately.
                put("fragment", JSONObject().apply {
                    put("packets", "1-3")
                    put("length", "100-200")
                    put("interval", "10-20")
                })
            }
        })

        return stream
    }
}
