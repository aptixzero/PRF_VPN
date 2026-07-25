package com.neonvpn.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.neonvpn.app.R

/**
 * v6.4 — THE COUNTRY-FLAG TILE ON THE HOME SCREEN.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE BRIEF (two hard requirements)
 * ─────────────────────────────────────────────────────────────────────────────
 *  1. **"The flag is smaller than the square. Make the flag exactly the width
 *     and height of that square — square."**
 *     In v6.3 the flag was an emoji inside a TextView. A glyph is laid out with
 *     font ascent/descent padding around it, so no matter what `textSize` we
 *     chose the visible flag was always noticeably smaller than the tile it sat
 *     in, and its aspect never matched. Text layout simply cannot be made to
 *     fill a box exactly.
 *
 *     [FlagView] fixes that by dropping TextView entirely and painting the flag
 *     itself on a Canvas. It measures the glyph's REAL ink bounds
 *     ([Paint.getTextBounds]) and then scales X and Y independently so the ink
 *     lands on the tile's exact width and height — the flag now fills the square
 *     edge-to-edge, with the tile's own rounded background clipping it.
 *
 *  2. **"The real flag of Iran is the Lion and Sun. Even on weak internet, show
 *     the Lion-and-Sun flag instead of the Islamic-Republic one. The
 *     Islamic-Republic flag must NEVER be displayed in the app."**
 *     The system font's `IR` emoji is the Islamic-Republic flag, so for IR we
 *     never touch the font at all: we draw the bundled vector
 *     `R.drawable.flag_ir_lion_sun`. Because it is a local vector asset this
 *     works offline, on a weak link, and on every device and Android version —
 *     there is no code path anywhere in the app that can produce the
 *     Islamic-Republic flag.
 *
 * The view is dumb and cheap: no I/O, no bitmaps, no allocation while drawing.
 */
class FlagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }

    private val inkBounds = Rect()
    private val dst = RectF()

    /** The emoji currently painted ("" = nothing). Never the IR emoji. */
    private var emoji: String = ""

    /** Non-null when we must paint a bundled vector instead of a glyph (Iran). */
    private var special: Drawable? = null

    // ───────────────────────────────────────────────────────────── public API ──

    /**
     * Paint the flag of [iso2] (ISO-3166-1 alpha-2, case-insensitive).
     * Pass null/blank to clear the tile.
     *
     * `IR` is intercepted here and rendered from the bundled Lion-and-Sun
     * vector; the Islamic-Republic emoji is never produced.
     */
    fun setCountry(iso2: String?) {
        val code = iso2?.trim()?.uppercase().orEmpty()
        if (code == IRAN) {
            if (special == null) {
                special = AppCompatResources.getDrawable(context, R.drawable.flag_ir_lion_sun)
            }
            if (emoji.isNotEmpty()) emoji = ""
            invalidate()
            return
        }
        special = null
        val next = emojiOf(code)
        if (next == emoji) return
        emoji = next
        invalidate()
    }

    /**
     * Convenience overload used by callers that already hold an emoji string
     * (e.g. a cached value). An IR flag emoji arriving from ANY source is
     * re-routed to the Lion-and-Sun vector, so a stale cache can never surface
     * the Islamic-Republic flag.
     */
    fun setFlagEmoji(flag: String?) {
        val s = flag.orEmpty()
        if (s.isBlank()) { setCountry(null); return }
        setCountry(decodeIso(s) ?: run { special = null; emoji = s; invalidate(); return })
    }

    /** Clear the tile (the default, disconnected state). */
    fun clearFlag() {
        special = null
        if (emoji.isEmpty()) return
        emoji = ""
        invalidate()
    }

    /** True when the tile currently has something to show. */
    fun hasFlag(): Boolean = special != null || emoji.isNotEmpty()

    // ─────────────────────────────────────────────────────────────── drawing ──

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        // ---- Iran: the bundled Lion-and-Sun vector ------------------------
        val d = special
        if (d != null) {
            // The vector is 3:2; the tile is square. We scale it to COVER the
            // square (fill both axes) and centre-crop, so the tile is filled
            // edge-to-edge exactly as the brief requires.
            val vw = d.intrinsicWidth.takeIf { it > 0 } ?: 3
            val vh = d.intrinsicHeight.takeIf { it > 0 } ?: 2
            val scale = maxOf(w.toFloat() / vw, h.toFloat() / vh)
            val dw = (vw * scale).toInt()
            val dh = (vh * scale).toInt()
            val left = paddingLeft + (w - dw) / 2
            val top = paddingTop + (h - dh) / 2
            d.setBounds(left, top, left + dw, top + dh)
            val save = canvas.save()
            canvas.clipRect(paddingLeft, paddingTop, paddingLeft + w, paddingTop + h)
            d.draw(canvas)
            canvas.restoreToCount(save)
            return
        }

        // ---- everything else: the emoji glyph, stretched to fill ----------
        if (emoji.isEmpty()) return

        // Measure the glyph's real ink box at a reference size, then map that box
        // onto the tile. This is what makes the flag exactly as wide and as tall
        // as the square instead of leaving font padding around it.
        paint.textSize = REFERENCE_TEXT_PX
        paint.getTextBounds(emoji, 0, emoji.length, inkBounds)
        val iw = inkBounds.width().toFloat()
        val ih = inkBounds.height().toFloat()
        if (iw <= 0f || ih <= 0f) return

        dst.set(
            paddingLeft.toFloat(), paddingTop.toFloat(),
            (paddingLeft + w).toFloat(), (paddingTop + h).toFloat()
        )

        val sx = dst.width() / iw
        val sy = dst.height() / ih

        val save = canvas.save()
        canvas.clipRect(dst)
        canvas.translate(dst.left, dst.top)
        canvas.scale(sx, sy)
        // Shift so the ink box's own origin lands on (0,0).
        canvas.drawText(emoji, -inkBounds.left.toFloat(), -inkBounds.top.toFloat(), paint)
        canvas.restoreToCount(save)
    }

    // ───────────────────────────────────────────────────────────────── utils ──

    private fun emojiOf(iso2: String): String {
        if (iso2.length != 2) return ""
        if (iso2[0] !in 'A'..'Z' || iso2[1] !in 'A'..'Z') return ""
        val base = 0x1F1E6
        return String(Character.toChars(base + (iso2[0] - 'A'))) +
            String(Character.toChars(base + (iso2[1] - 'A')))
    }

    /** Turn a regional-indicator pair back into its two ASCII letters. */
    private fun decodeIso(s: String): String? {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                val n = i + Character.charCount(cp)
                if (n < s.length) {
                    val cp2 = s.codePointAt(n)
                    if (cp2 in 0x1F1E6..0x1F1FF) {
                        return "${'A' + (cp - 0x1F1E6)}${'A' + (cp2 - 0x1F1E6)}"
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return null
    }

    private companion object {
        const val IRAN = "IR"
        /** Big enough that the measured ink box is precise at any tile size. */
        const val REFERENCE_TEXT_PX = 160f
    }
}
