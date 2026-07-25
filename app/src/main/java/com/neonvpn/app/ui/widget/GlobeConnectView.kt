package com.neonvpn.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v4.0 — animated wireframe GLOBE visualizer (the centrepiece of the Home
 * screen, per the UI sheet — screens 02/03/04).
 *
 * A rotating latitude/longitude wireframe sphere with glowing nodes. Its colour
 * tracks the connection lifecycle:
 *   IDLE/DISCONNECTED → violet, slow spin (the "world map" look).
 *   CONNECTING        → violet, faster spin + pulsing radial arcs.
 *   CONNECTED         → emerald green, bright steady glow + a check shield in the
 *                       centre (drawn on canvas, no PNG).
 *   ERROR             → red, quick flicker.
 *
 * 100% Canvas — no images, no PNGs, universal on every API ≥ 24. The animation
 * is frame-throttled (~40fps) so it is buttery smooth without burning battery,
 * and it is fully cancelled when detached so it never leaks or lags a paused tab.
 */
class GlobeConnectView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class State { IDLE, CONNECTING, CONNECTED, ERROR }

    @Volatile var state: State = State.IDLE
        private set

    private var spin = 0f          // 0..1 looping rotation
    private var pulse = 0f         // 0..1 looping pulse
    private var intensity = 0.4f   // eased glow target

    private var anim: ValueAnimator? = null
    private var lastFrameMs = 0L

    /**
     * v6.3 — SMOOTHER ANIMATION. 24 ms between frames is ~41 fps, which reads as
     * a faint stutter on a 60/90/120 Hz panel — exactly the "animation is not
     * good" feedback. 16 ms lets the view repaint on every vsync of a 60 Hz
     * display (and as often as the compositor allows on faster ones). The draw
     * itself is a handful of arcs and circles, so this is essentially free.
     */
    private val frameIntervalMs = 16L

    // ------------------------------------------------------------------
    // v6.3 — EASED STATE TRANSITIONS.
    //
    // Previously the rotation speed and the colour flipped INSTANTLY the moment
    // the state changed, so tapping connect produced a visible jolt: the globe
    // jumped from a slow idle spin straight to a fast connecting spin, and the
    // violet snapped to green the instant the tunnel came up.
    //
    // We now keep a continuously-integrated rotation phase plus an eased
    // "transition" value, so speed ramps and colours cross-fade. Nothing about
    // the drawing code changes — it just receives smoothed inputs.
    // ------------------------------------------------------------------

    /** Continuously integrated rotation phase (never jumps when speed changes). */
    private var spinPhase = 0f
    /** Smoothed rotation speed, chases the per-state target. */
    private var spinSpeed = 0.7f
    /** 0→1 progress of the cross-fade into the CURRENT state's colours. */
    private var transition = 1f
    /** The colours we are fading FROM. */
    private var prevColors: Triple<Int, Int, Int>? = null
    /** Wall-clock of the previous frame, for frame-rate-independent easing. */
    private var lastTickNs = 0L
    /** 0..1 ping ripple used by the CONNECTED state. */
    private var ripple = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { startLoop() }

    fun setState(s: State) {
        if (state == s) return
        // v6.3 — remember the outgoing palette so onDraw can cross-fade into the
        // new one instead of snapping (smooth connect/disconnect transition).
        prevColors = colors()
        transition = 0f
        ripple = 0f
        state = s
        // Ensure the render loop is alive whenever the state changes — if the
        // view was detached/re-attached (app closed & reopened, tab switch,
        // screen off/on) the ValueAnimator may have been cancelled, which froze
        // the connect animation. Re-arm it defensively so the orb never sticks.
        ensureLoopRunning()
        invalidate()
    }

    /**
     * Restart the render loop when the view is (re)attached to the window. The
     * old code only started it in init{} and cancelled it on detach, so after
     * the app was backgrounded/reopened the animation stayed frozen. Restarting
     * on attach guarantees a smooth, always-live connect animation.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureLoopRunning()
    }

    /** Start the loop only if it isn't already running (idempotent). */
    private fun ensureLoopRunning() {
        if (anim?.isRunning == true) return
        startLoop()
    }

    /**
     * Public re-arm hook. The Connect screen calls this from onResume so the
     * animation is guaranteed to be live after the app returns to the foreground,
     * even in the case where the view was never fully detached (so
     * onAttachedToWindow didn't fire) but the animator was paused by the system.
     */
    fun resumeAnimation() = ensureLoopRunning()

    private fun startLoop() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 14_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                // ---- frame-rate-independent time step -------------------
                val nowNs = System.nanoTime()
                val dt = if (lastTickNs == 0L) 0.016f
                         else ((nowNs - lastTickNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastTickNs = nowNs

                // ---- eased rotation speed (no more jolt on state change) --
                val targetSpeed = when (state) {
                    State.CONNECTING -> 2.4f
                    State.CONNECTED -> 1.0f
                    State.ERROR -> 3.0f
                    State.IDLE -> 0.7f
                }
                // Exponential approach: fast enough to feel responsive, slow
                // enough that the ramp is visible and pleasant.
                spinSpeed += (targetSpeed - spinSpeed) * (1f - exp(-6f * dt))

                // Integrate the phase so a speed change bends the motion rather
                // than teleporting it.
                spinPhase = (spinPhase + spinSpeed * dt / 14f) % 1f
                spin = spinPhase

                // ---- pulse + connected ripple ----------------------------
                val f = it.animatedFraction
                pulse = (0.5f + 0.5f * sin(f * 2f * PI * 3f).toFloat())
                // A slow 2.4s ripple that only the CONNECTED state renders — it
                // makes "connected" feel alive instead of static.
                ripple = (ripple + dt / 2.4f) % 1f

                // ---- eased glow intensity --------------------------------
                val target = when (state) {
                    State.IDLE -> 0.42f
                    State.CONNECTING -> 0.6f + 0.25f * pulse
                    State.CONNECTED -> 0.85f + 0.1f * pulse
                    State.ERROR -> 0.5f + 0.4f * pulse
                }
                intensity += (target - intensity) * (1f - exp(-7f * dt))

                // ---- colour cross-fade progress --------------------------
                if (transition < 1f) {
                    transition = (transition + dt / COLOR_FADE_SEC).coerceAtMost(1f)
                    if (transition >= 1f) prevColors = null
                }

                val now = System.currentTimeMillis()
                if (now - lastFrameMs >= frameIntervalMs) {
                    lastFrameMs = now
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel(); anim = null
    }

    private fun colors(): Triple<Int, Int, Int> = when (state) {
        State.CONNECTED -> Triple(0xFF10B981.toInt(), 0xFF34F0AE.toInt(), 0xFF059669.toInt())
        State.CONNECTING -> Triple(0xFF8A3FFC.toInt(), 0xFFB983FF.toInt(), 0xFF4C2896.toInt())
        State.ERROR -> Triple(0xFFEF4444.toInt(), 0xFFFF8A8A.toInt(), 0xFF991B1B.toInt())
        State.IDLE -> Triple(0xFF8A3FFC.toInt(), 0xFFB983FF.toInt(), 0xFF4C2896.toInt())
    }

    /**
     * v6.3 — the palette actually used for drawing: the destination colours
     * cross-faded from the previous state's colours. This is what turns the old
     * hard violet→green snap into a smooth "locking on" transition.
     */
    private fun blendedColors(): Triple<Int, Int, Int> {
        val to = colors()
        val from = prevColors ?: return to
        val t = ease(transition)
        return Triple(
            mix(from.first, to.first, t),
            mix(from.second, to.second, t),
            mix(from.third, to.third, t)
        )
    }

    /** Smoothstep — no linear ramp-in/out edges. */
    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        return Color.argb(
            (Color.alpha(a) * inv + Color.alpha(b) * t).toInt(),
            (Color.red(a) * inv + Color.red(b) * t).toInt(),
            (Color.green(a) * inv + Color.green(b) * t).toInt(),
            (Color.blue(a) * inv + Color.blue(b) * t).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f * 0.74f
        val (main, bright, deep) = blendedColors()

        // ---- outer ambient glow ----
        val glowR = r * (1.5f + 0.25f * intensity)
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(withAlpha(main, (90 * intensity).toInt().coerceIn(0, 160)), Color.TRANSPARENT),
            floatArrayOf(0.25f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)

        // ---- connecting orbital arcs ----
        // v6.3 — the three arcs now counter-rotate at different rates and
        // breathe with the pulse, so "connecting" reads as active searching
        // rather than three rings sliding together in lock-step.
        if (state == State.CONNECTING || state == State.ERROR) {
            arcPaint.strokeWidth = r * 0.025f
            for (k in 0 until 3) {
                val rr = r * (1.12f + k * 0.16f)
                // alternate direction + slightly different speed per ring
                val dir = if (k % 2 == 0) 1f else -1f
                val rate = 1f + k * 0.35f
                val start = (spin * 360f * rate * dir + k * 120f).mod(360f)
                // sweep length breathes so the arcs feel like a live scan
                val sweep = 55f + 30f * pulse + k * 8f
                arcPaint.color = withAlpha(bright, (110 + 70 * pulse).toInt().coerceIn(0, 255))
                canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, start, sweep, false, arcPaint)
            }
        }

        // ---- the wireframe sphere ----
        // longitude meridians (rotating)
        val rot = spin * 2f * PI.toFloat()
        linePaint.strokeWidth = r * 0.012f
        val meridians = 7
        for (i in 0 until meridians) {
            val phase = rot + i * PI.toFloat() / meridians
            // ellipse width factor = |cos(phase)| so meridians foreshorten as they turn
            val ew = cos(phase)
            val alpha = (60 + 120 * kotlin.math.abs(ew)).toInt().coerceIn(40, 200)
            linePaint.color = withAlpha(main, alpha)
            drawMeridian(canvas, cx, cy, r, ew)
        }
        // latitude rings (static)
        val lats = 5
        for (j in 1 until lats) {
            val t = j.toFloat() / lats           // 0..1 from top
            val ang = (t - 0.5f) * PI.toFloat()  // -pi/2..pi/2
            val ry = sin(ang) * r
            val rr = cos(ang) * r
            linePaint.color = withAlpha(main, 90)
            canvas.drawOval(cx - rr, cy + ry - r * 0.04f, cx + rr, cy + ry + r * 0.04f, linePaint.alsoStrokeThin(r))
        }
        // outer circle
        linePaint.color = withAlpha(bright, 180)
        linePaint.strokeWidth = r * 0.016f
        canvas.drawCircle(cx, cy, r, linePaint)

        // ---- glowing nodes scattered on the sphere ----
        drawNodes(canvas, cx, cy, r, bright)

        // ---- v6.3 connected: an expanding "secure" ripple ----
        // A single soft ring that grows out of the globe and fades. It gives the
        // connected state a calm heartbeat instead of a frozen picture.
        if (state == State.CONNECTED) {
            val e = ease(ripple)
            val rr = r * (1.0f + 0.55f * e)
            val a = ((1f - e) * 110f).toInt().coerceIn(0, 255)
            if (a > 0) {
                arcPaint.color = withAlpha(bright, a)
                arcPaint.strokeWidth = r * 0.02f * (1f - e) + r * 0.004f
                canvas.drawCircle(cx, cy, rr, arcPaint)
            }
        }

        // ---- connected: central check shield ----
        if (state == State.CONNECTED) drawShield(canvas, cx, cy, r, bright, deep)
    }

    private fun Paint.alsoStrokeThin(r: Float): Paint { this.strokeWidth = r * 0.01f; return this }

    private fun drawMeridian(canvas: Canvas, cx: Float, cy: Float, r: Float, ew: Float) {
        // a meridian is an ellipse with full height r and half-width |ew|*r
        val halfW = kotlin.math.abs(ew) * r
        canvas.drawOval(cx - halfW, cy - r, cx + halfW, cy + r, linePaint)
    }

    private fun drawNodes(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        // deterministic node positions (no Random) spun around the globe
        val rot = spin * 2f * PI.toFloat()
        val pts = NODE_SEEDS
        for ((idx, p) in pts.withIndex()) {
            val lon = p[0] + rot
            val lat = p[1]
            val x = cos(lat) * sin(lon)
            val z = cos(lat) * cos(lon)
            val y = sin(lat)
            if (z < -0.1f) continue  // back of sphere — skip
            val px = cx + x * r
            val py = cy + y * r
            val depth = (z + 1f) / 2f
            val rad = r * (0.018f + 0.022f * depth)
            val a = (120 + 120 * depth * (0.6f + 0.4f * pulse)).toInt().coerceIn(0, 255)
            nodePaint.color = withAlpha(color, a)
            canvas.drawCircle(px, py, rad, nodePaint)
            // tiny glow
            if (idx % 3 == 0) {
                nodePaint.color = withAlpha(color, (a * 0.4f).toInt())
                canvas.drawCircle(px, py, rad * 2.2f, nodePaint)
            }
        }
    }

    private fun drawShield(canvas: Canvas, cx: Float, cy: Float, r: Float, bright: Int, deep: Int) {
        val s = r * 0.5f
        // soft green halo
        shieldPaint.shader = RadialGradient(
            cx, cy, s * 1.8f,
            intArrayOf(withAlpha(bright, 160), Color.TRANSPARENT),
            floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, s * 1.8f, shieldPaint)
        shieldPaint.shader = null

        // shield body
        val path = android.graphics.Path()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s * 0.82f, cy - s * 0.5f)
        path.lineTo(cx + s * 0.82f, cy + s * 0.18f)
        path.quadTo(cx + s * 0.82f, cy + s * 0.9f, cx, cy + s * 1.05f)
        path.quadTo(cx - s * 0.82f, cy + s * 0.9f, cx - s * 0.82f, cy + s * 0.18f)
        path.lineTo(cx - s * 0.82f, cy - s * 0.5f)
        path.close()
        shieldPaint.style = Paint.Style.FILL
        shieldPaint.color = withAlpha(0xFF065F46.toInt(), 235)
        canvas.drawPath(path, shieldPaint)
        shieldPaint.style = Paint.Style.STROKE
        shieldPaint.strokeWidth = r * 0.02f
        shieldPaint.color = withAlpha(bright, 255)
        canvas.drawPath(path, shieldPaint)

        // check mark
        val cm = android.graphics.Path()
        cm.moveTo(cx - s * 0.34f, cy + s * 0.02f)
        cm.lineTo(cx - s * 0.06f, cy + s * 0.30f)
        cm.lineTo(cx + s * 0.42f, cy - s * 0.30f)
        shieldPaint.strokeWidth = r * 0.05f
        shieldPaint.strokeCap = Paint.Cap.ROUND
        shieldPaint.strokeJoin = Paint.Join.ROUND
        shieldPaint.color = Color.WHITE
        canvas.drawPath(cm, shieldPaint)
        shieldPaint.style = Paint.Style.FILL
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    companion object {
        /** v6.3 — seconds the idle↔connecting↔connected colour cross-fade takes. */
        private const val COLOR_FADE_SEC = 0.55f

        // 24 fixed node positions [longitude, latitude] (radians). Deterministic.
        private val NODE_SEEDS: Array<FloatArray> = run {
            val list = ArrayList<FloatArray>()
            var seed = 12345L
            fun next(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 16) and 0x7FFF) / 32767f }
            for (i in 0 until 24) {
                val lon = (next() * 2f - 1f) * PI.toFloat()
                val lat = (next() * 2f - 1f) * (PI.toFloat() / 2.3f)
                list.add(floatArrayOf(lon, lat))
            }
            list.toTypedArray()
        }
    }
}
