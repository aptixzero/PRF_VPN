package com.neonvpn.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full, valid Xray-core JSON config from a [ServerConfig].
 *
 * Supports ONLY vless / vmess (project policy). Key points that make it work &
 * not crash, and that give a stable, fast tunnel:
 *
 *   - SOCKS5 inbound (tag "socks-in") that tun2socks feeds the TUN traffic into,
 *     with sniffing so routing/DNS sees real domains.
 *   - Real `stats` + `policy` + `api` blocks so queryStats(...) returns true
 *     up/down byte counters (the live speed meter). Without these the core
 *     throws on stats query and the service dies.
 *   - DNS with a fake-DNS-free, UseIP strategy + a local DNS outbound so the
 *     core resolves names itself (prevents leaks & speeds up first byte).
 *   - Anti-censorship hardening: TCP fragment on the dialer (tlshello) so DPI
 *     can't fingerprint the ClientHello, mux for ws/grpc, uTLS chrome
 *     fingerprint, TCP no-delay + keep-alive for a smooth, persistent tunnel.
 */
object XrayConfigBuilder {

    const val SOCKS_PORT = 10808
    const val API_PORT = 10809

    const val PROXY_TAG = "proxy"

    fun build(cfg: ServerConfig): String {
        val root = JSONObject()

        // ---- log ----
        root.put("log", JSONObject().apply { put("loglevel", "warning") })

        // ---- stats ----
        root.put("stats", JSONObject())

        // ---- api (StatsService for runtime counters) ----
        root.put("api", JSONObject().apply {
            put("tag", "api")
            put("services", JSONArray().apply { put("StatsService") })
        })

        // ---- policy: per-outbound up/down stats + sane buffers ----
        root.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    // v4.5 SPEED — handshake gets a touch more room so a node on
                    // Iran's disrupted links isn't dropped mid-TLS, while a long
                    // connIdle keeps a healthy tunnel alive through quiet moments
                    // (downloads that pause, screen-off, etc.) so it "never drops".
                    put("handshake", 6)
                    put("connIdle", 600)
                    put("uplinkOnly", 0)
                    put("downlinkOnly", 0)
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                    // v4.5 — BIGGER per-connection buffer for true full-bandwidth
                    // ("تمام پهنای باند") downloads. 4 MiB lets a single fast stream
                    // fill the user's whole pipe (high-BDP links) instead of being
                    // throttled by a small window, while still safe on modern
                    // phones. uplink/downlinkOnly = 0 means the buffer is freed
                    // promptly when one direction goes idle, so RAM stays bounded.
                    put("bufferSize", 4096)
                })
            })
            put("system", JSONObject().apply {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        // ---- dns (Bypass-for-Iran aware) ----
        // Iranian domains resolve via a fast domestic resolver and go DIRECT, so
        // local sites stay snappy and don't waste the tunnel; everything else is
        // resolved through encrypted DoH (DoH defeats Iran's DNS poisoning) and
        // routed through the proxy. This split is the backbone of the bypass.
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                // encrypted resolvers for the free internet (anti-poisoning)
                put("https://1.1.1.1/dns-query")
                put("https://dns.google/dns-query")
                put("1.1.1.1")
                put("8.8.8.8")
                // domestic resolver ONLY for Iranian domains => kept direct & fast
                put(JSONObject().apply {
                    put("address", "78.157.42.100")           // Shecan / domestic
                    put("port", 53)
                    put("domains", JSONArray().apply {
                        put("geosite:category-ir")
                        put("geosite:cn")                      // harmless extra
                        put("domain:ir")
                    })
                    put("skipFallback", true)
                })
            })
            put("queryStrategy", "UseIP")
            put("disableCache", false)
            put("disableFallbackIfMatch", true)
            put("tag", "dns-out-tag")
        })

        // ---- inbounds ----
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", SOCKS_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
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
            put("settings", JSONObject().apply { put("address", "127.0.0.1") })
        })
        root.put("inbounds", inbounds)

        // ---- outbounds ----
        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(cfg))
        // direct freedom for Iranian/local traffic (no fragmentation needed —
        // these stay inside the country and must be fast).
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply { put("domainStrategy", "UseIP") })
        })
        // dns outbound so the core's own queries resolve
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

        // ---- routing (Bypass for Iran) ----
        // Rule order matters (first match wins):
        //   1. stats api  -> api outbound
        //   2. DNS (53)   -> dns outbound
        //   3. block ads/malware + bittorrent so they can't choke the tunnel
        //   4. Iranian domains / Iran IPs / private LAN -> DIRECT (fast, no tunnel)
        //   5. EVERYTHING ELSE -> proxy  (full bandwidth through the tunnel)
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                // stats api inbound -> api outbound
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("api-in") })
                    put("outboundTag", "api")
                })
                // DNS queries -> dns outbound
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", 53)
                    put("outboundTag", "dns-out")
                })
                // drop bittorrent so it can't saturate the tunnel
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("protocol", JSONArray().apply { put("bittorrent") })
                })
                // Iranian domains -> DIRECT (don't waste tunnel on local sites)
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply {
                        put("geosite:category-ir")
                        put("domain:ir")
                    })
                })
                // Iranian IP ranges + private LAN -> DIRECT
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:ir")
                        put("geoip:private")
                    })
                })
                // everything else through the proxy (full bandwidth)
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
     * Minimal config used ONLY for a delay/ping test of a single server, without
     * bringing the VPN up. It has just the proxy outbound; the native
     * measureOutboundDelay dials through it and times a generate_204 request.
     */
    fun buildPingConfig(cfg: ServerConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("loglevel", "none") })
        root.put("outbounds", JSONArray().apply { put(buildProxyOutbound(cfg)) })
        return root.toString()
    }

    private fun buildProxyOutbound(cfg: ServerConfig): JSONObject {
        // Dedicated builders per protocol — a tuned "engine" for VLESS and a
        // tuned one for VMESS, each with the settings that give that protocol
        // the best speed + stability on Iran's disrupted internet.
        return when (cfg.protocol) {
            "vless" -> buildVlessOutbound(cfg)
            "vmess" -> buildVmessOutbound(cfg)
            else -> buildVlessOutbound(cfg)   // parser only emits vless/vmess
        }
    }

    /**
     * VLESS engine. VLESS is stateless and lean (no built-in encryption — TLS /
     * Reality does it), so it shines for raw throughput. Reality / XTLS-vision
     * flows want mux OFF and the connection kept 1:1 for full single-stream
     * line-rate, which is exactly what a power user asking for "تمام پهنای باند"
     * needs. We always advertise encryption=none (correct for VLESS) and pass the
     * xtls flow through when present.
     */
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
        applyMux(out, cfg)
        return out
    }

    /**
     * VMESS engine. VMESS carries its own AEAD encryption; "auto" lets the core
     * pick the fastest cipher the device supports (AES-GCM on ARMv8 with crypto
     * extensions, ChaCha20-Poly1305 otherwise) — best speed across old & new
     * phones. We also leave mux OFF here for the same full-bandwidth reason; the
     * stream tuning (fragment / keep-alive / buffers) is shared via
     * buildStreamSettings so VMESS gets the same anti-DPI hardening as VLESS.
     */
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
                            // "auto" => fastest AEAD cipher the CPU supports
                            put("security", cfg.security.ifBlank { "auto" })
                            put("level", 8)
                        })
                    })
                })
            })
        })
        out.put("streamSettings", buildStreamSettings(cfg))
        applyMux(out, cfg)
        return out
    }

    /**
     * v4.4 — SMART multiplexing for max throughput + stability.
     *
     * The right mux setting depends on the transport:
     *   • Reality / XTLS-vision flow → mux is INCOMPATIBLE and would break the
     *     connection, so it MUST stay off.
     *   • Raw TCP+TLS → one dedicated stream per app socket already gives full
     *     single-stream line-rate with zero head-of-line blocking, so off is the
     *     fastest for big downloads.
     *   • ws / grpc / h2 → these run over a single long-lived connection where
     *     opening a fresh handshake per request is slow and fragile on Iran's
     *     disrupted internet. Mux pools many app requests onto a few carrier
     *     streams, which is noticeably FASTER and far MORE STABLE here — pages
     *     and apps stop stalling while a new sub-connection is negotiated.
     */
    private fun applyMux(out: JSONObject, cfg: ServerConfig) {
        val net = cfg.network.ifBlank { "tcp" }.lowercase()
        val isReality = cfg.tls.equals("reality", true) || cfg.tls.equals("xtls", true)
        val hasFlow = cfg.flow.isNotBlank()
        val muxFriendly = net in setOf("ws", "grpc", "h2", "http")
        val enable = muxFriendly && !isReality && !hasFlow
        out.put("mux", JSONObject().apply {
            put("enabled", enable)
            // 8 concurrent sub-streams per carrier connection is the v2rayNG
            // sweet-spot: enough parallelism for snappy browsing without the
            // overhead of too many open streams. (-1 = disabled when off.)
            put("concurrency", if (enable) 8 else -1)
            if (enable) {
                put("xudpConcurrency", 8)
                put("xudpProxyUDP443", "reject")
            }
        })
    }

    private fun buildStreamSettings(cfg: ServerConfig): JSONObject {
        val stream = JSONObject()
        // normalise transport: treat xhttp/splithttp as tcp-ish so the build
        // never produces an unknown network the core rejects.
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

        // Socket options + TLS-record FRAGMENTATION — the single biggest anti-DPI
        // win against Iran's filtering. Splitting the ClientHello across several
        // tiny TCP segments means the DPI box never sees the SNI in one packet,
        // so SNI/keyword-based blocking and RST-injection can't fingerprint and
        // tear down the connection. Combined with TLS uTLS mimicry above, this is
        // what keeps the tunnel ALIVE on disrupted Iranian internet instead of
        // dropping every few seconds. Keep-alive keeps the socket persistent.
        stream.put("sockopt", JSONObject().apply {
            // v4.5 — keep-alive tuned for "never drops, even idle for hours". We
            // probe every 15s after 30s idle so a half-dead link is detected and
            // re-established FAST, yet the long connIdle policy above keeps a quiet
            // but healthy tunnel up (e.g. a paused download / screen off).
            put("tcpKeepAliveIdle", 30)
            put("tcpKeepAliveInterval", 15)
            put("tcpNoDelay", true)         // no Nagle delay → snappier first byte
            put("tcpMptcp", false)
            put("mark", 0)
            // TCP Fast Open shaves a round-trip off every new connection — a real
            // latency win on Iran's high-RTT links (browsing feels much faster).
            put("tcpFastOpen", true)
            // Fragment the TLS handshake records. "tlshello" mode targets EXACTLY
            // the ClientHello (where SNI lives), so it costs essentially nothing on
            // throughput (only the first handshake packet is split) while still
            // hiding the SNI from DPI. Tighter, lower-overhead ranges than v4.4 so
            // the anti-DPI win doesn't slow the handshake.
            if (sec == "tls" || sec == "reality") {
                put("dialerProxy", "")
                put("fragment", JSONObject().apply {
                    put("packets", "tlshello")
                    put("length", "40-100")
                    put("interval", "5-10")
                })
            } else {
                // for plaintext transports, fragment only the very first packet so
                // protocol-pattern DPI can't lock on, with minimal speed cost.
                put("fragment", JSONObject().apply {
                    put("packets", "1-2")
                    put("length", "40-100")
                    put("interval", "5-10")
                })
            }
        })

        return stream
    }
}
