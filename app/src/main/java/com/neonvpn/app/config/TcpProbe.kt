package com.neonvpn.app.config

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * v6.6 — THE FAST PRE-GATE. This is the single biggest reason the ping sweep
 * went from "minutes" to "seconds".
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE PROBLEM IT SOLVES
 * ─────────────────────────────────────────────────────────────────────────────
 * Up to v6.5 every config in a batch — including the ~80 % of a public free
 * batch that is stone dead — was handed straight to [Pinger], which spins up a
 * throwaway native Xray core and spends up to 12 s (24 s with the retry) proving
 * what a single TCP SYN could have proven in 300 ms. With a bounded concurrency
 * of 4-8 cores, a 240-config batch therefore needed many minutes, which is
 * exactly the reported «پینگ‌ها دیر گرفته می‌شوند».
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY A TCP HANDSHAKE IS A *SOUND* REJECTION TEST
 * ─────────────────────────────────────────────────────────────────────────────
 * Xray dials `address:port` from THIS device, over THIS ISP link, with a plain
 * TCP connect — precisely what [reachable] does. So:
 *
 *   • TCP connect FAILS  ⇒ the core's dial would fail too ⇒ the node is
 *     genuinely unusable. Rejecting it is correct and costs ~300 ms.
 *   • TCP connect SUCCEEDS ⇒ proves NOTHING about the tunnel (DPI commonly lets
 *     the SYN through and resets on the ClientHello). So a success here is NOT
 *     a ping; it only earns the config the right to be measured for real by
 *     [Pinger].
 *
 * That asymmetry is the whole design: this file may only ever REJECT or ORDER.
 * It can never mark a config reachable, and it never produces a displayed
 * number — so it cannot re-introduce a fake ping. Golden Rule #2 is intact.
 *
 * v6.7 adds [connectMs], which returns the REAL measured handshake time instead
 * of throwing it away. See that function for why the number is sound and for
 * the strict rules on what it may be used for.
 */
object TcpProbe {

    private const val TAG = "TcpProbe"

    /** Budget for one SYN/ACK. Generous enough for a high-RTT Iranian link. */
    const val CONNECT_TIMEOUT_MS = 2_200

    /**
     * How many pre-gate probes may run at once. These are bare sockets — no
     * native core, no TLS, a few KiB of state each — so this can be an order of
     * magnitude above the core-probe concurrency without any memory risk. This
     * is what makes a whole batch get triaged in about a second.
     */
    const val MAX_CONCURRENCY = 48

    /**
     * True when a TCP connection to the node's dial address can be established.
     *
     * A `false` return is authoritative proof the node cannot be used; a `true`
     * return is only permission to be measured properly. Never treat the elapsed
     * time here as a ping — it is the latency to the node's front door, not
     * through the tunnel, and displaying it would be exactly the fake ping this
     * project forbids.
     */
    suspend fun reachable(cfg: ServerConfig): Boolean = connectMs(cfg) >= 0L

    /**
     * v6.7 — THE MEASURED FORM OF THE PRE-GATE.
     *
     * Returns the REAL, measured wall-clock milliseconds the TCP handshake to
     * `address:port` took, or [UNREACHABLE] when no connection could be made.
     * Nothing here is estimated, smoothed or invented — it is
     * `System.nanoTime()` around one `Socket.connect()`. Golden Rule #2 holds.
     *
     * ── WHY v6.7 NEEDS THE NUMBER, NOT JUST THE BOOLEAN ─────────────────────
     * The complaint this release fixes is «پینگ‌ها بالای ۲۵۰ هستند» — Auto Test
     * kept handing back nodes that were technically alive but far away and slow.
     * v6.6 threw away everything the pre-gate learned except one bit, so the
     * deep-probe wave then measured a 240-config batch in ARBITRARY order and
     * the user watched slow nodes get tested (and accepted) before fast ones.
     *
     * The handshake time is a genuine, causally-sound lower bound on the tunnel
     * latency: the tunnel's round trip physically CONTAINS this round trip. So a
     * node whose front door is already 900 ms away can never produce a 200 ms
     * tunnel ping, and a node whose door answers in 60 ms is the only kind that
     * can. Sorting the deep wave by this measurement means the fastest nodes are
     * measured FIRST, which is what makes v6.7 surface low-ping configs within
     * seconds instead of minutes.
     *
     * ── WHAT IT MAY *NOT* BE USED FOR ───────────────────────────────────────
     * This is the latency to the node's FRONT DOOR, not through the tunnel, so
     * it is NEVER displayed and NEVER stored as a ping. It may only:
     *   • reject (no connection at all ⇒ the core's dial would fail too), and
     *   • order/rank work.
     * Every number the user ever sees still comes from [Pinger].
     */
    suspend fun connectMs(cfg: ServerConfig): Long = withContext(Dispatchers.IO) {
        if (cfg.address.isBlank() || cfg.port !in 1..65535) return@withContext UNREACHABLE
        var sock: Socket? = null
        val t0 = System.nanoTime()
        try {
            sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(cfg.address, cfg.port), CONNECT_TIMEOUT_MS)
            if (!sock.isConnected) return@withContext UNREACHABLE
            val ms = (System.nanoTime() - t0) / 1_000_000L
            // A measured 0 ms is indistinguishable from "no measurement" for the
            // callers, so clamp the floor to 1 ms. Still a real observation.
            if (ms <= 0L) 1L else ms
        } catch (e: Throwable) {
            // Unresolvable host, refused, filtered, timed out — all mean the same
            // thing for our purposes: the core could not dial it either.
            Log.v(TAG, "tcp pre-gate rejected ${cfg.address}:${cfg.port} (${e.javaClass.simpleName})")
            UNREACHABLE
        } finally {
            try { sock?.close() } catch (_: Throwable) {}
        }
    }

    /** Sentinel for [connectMs] when no TCP connection could be established. */
    const val UNREACHABLE = -1L
}
