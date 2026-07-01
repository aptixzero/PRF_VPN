package com.neonvpn.app.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.neonvpn.app.config.RemoteConfig
import com.neonvpn.app.config.RemoteConfigStore
import com.neonvpn.app.ui.ContactActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The single in-app advertising banner on the Connect screen.
 *
 * IMPORTANT (project policy): there is **NO ad-network script** here. The banner
 * is a fully panel-controlled card:
 *   - title + subtitle text (default: "محل تبلیغ شما" / "جهت ثبت تبلیغ …"),
 *   - optional background + text colors,
 *   - optional image (when the operator uploads a banner),
 *   - a configurable CLICK ACTION:
 *       "contact" → open the Contact-Us screen (default when empty),
 *       "url"     → open an external link,
 *       "none"    → not clickable.
 *
 * Everything is driven by [RemoteConfig] so the operator changes it from the
 * admin panel and the app reflects it on the next launch (true sync).
 */
class AdBanner @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val card: LinearLayout
    private val title: TextView
    private val subtitle: TextView
    private val image: ImageView

    private var current: RemoteConfig.AdConfig = RemoteConfig.default().ad
    // remember the last image url we drew so a re-bind with the SAME url doesn't
    // refetch + flicker (fixes "slow ad loading" — the banner stays instant).
    private var lastImageUrl: String? = null

    private val listener: (RemoteConfig) -> Unit = { cfg ->
        post { bind(resolveBanner(cfg)) }
    }

    /**
     * v4.6 — the bottom Home banner. Prefer the dedicated [RemoteConfig.homeBanner]
     * (image OR coloured text + click url) when the operator has set it; otherwise
     * fall back to the legacy [RemoteConfig.ad] block so old published configs
     * keep working.
     */
    private fun resolveBanner(cfg: RemoteConfig): RemoteConfig.AdConfig {
        val b = cfg.homeBanner
        val hasContent = b.imageUrl.isNotBlank() || b.text.isNotBlank()
        if (b.enabled && hasContent) {
            return RemoteConfig.AdConfig(
                enabled = true,
                title = b.text,
                subtitle = "",
                bgColor = "#11161C",
                textColor = b.textColor,
                imageUrl = b.imageUrl,
                action = if (b.url.isNotBlank()) RemoteConfig.ACTION_URL else RemoteConfig.ACTION_NONE,
                actionUrl = b.url
            )
        }
        return cfg.ad
    }

    init {
        // build the view hierarchy programmatically (no separate xml needed).
        // v3.3: the card now uses only a SMALL safe margin (was a chunky 14-16dp
        // pad) so an uploaded image fills almost the whole taller banner — no big
        // empty regions, premium look.
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        image = ImageView(context).apply {
            visibility = View.GONE
            adjustViewBounds = false
            // v3.6 FIX: FIT_CENTER shows the WHOLE image scaled to fit inside the
            // banner — nothing is cropped, so text/logos in the artwork are never
            // cut off and the image can never overflow the card. It is centred with
            // a small safe margin around it.
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
            )
        }
        title = TextView(context).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        subtitle = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            alpha = 0.85f
            setPadding(dp(12), dp(7), dp(12), 0)
        }
        card.addView(image)
        card.addView(title)
        card.addView(subtitle)
        addView(card)
        // round-corner clipping so CENTER_CROP imagery never bleeds past the card
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, dp(16).toFloat())
            }
        }
        bind(resolveBanner(RemoteConfigStore.current()))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RemoteConfigStore.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        RemoteConfigStore.removeListener(listener)
    }

    private fun bind(ad: RemoteConfig.AdConfig) {
        current = ad
        visibility = if (ad.enabled) View.VISIBLE else View.GONE
        if (!ad.enabled) return

        // styled rounded card
        val bg = parseColor(ad.bgColor, 0xFF11161C.toInt())
        val fg = parseColor(ad.textColor, 0xFFE6F2EC.toInt())
        val drawable = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(bg)
            setStroke(dp(1), withAlpha(fg, 50))
        }
        card.background = drawable

        title.setTextColor(fg)
        subtitle.setTextColor(withAlpha(fg, 200))
        title.text = ad.title
        subtitle.text = ad.subtitle
        title.visibility = if (ad.title.isBlank()) View.GONE else View.VISIBLE
        subtitle.visibility = if (ad.subtitle.isBlank()) View.GONE else View.VISIBLE

        // image (optional) — loaded async, simple bitmap fetch
        if (ad.imageUrl.isNotBlank()) {
            // only refetch when the url actually changed; otherwise keep the
            // already-shown drawable so a publish/refresh never flickers or stalls.
            if (ad.imageUrl != lastImageUrl || image.drawable == null) {
                loadImage(ad.imageUrl)
            } else {
                image.visibility = View.VISIBLE
                card.setPadding(dp(8), dp(8), dp(8), dp(8))
                title.visibility = View.GONE
                subtitle.visibility = View.GONE
            }
        } else {
            image.visibility = View.GONE
            lastImageUrl = null
            image.setImageDrawable(null)
            // text-only banner: keep a small safe margin around the copy.
            card.setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // click action
        setOnClickListener {
            when (ad.action) {
                RemoteConfig.ACTION_URL -> if (ad.actionUrl.isNotBlank()) openUrl(ad.actionUrl)
                RemoteConfig.ACTION_NONE -> { /* not clickable */ }
                else -> openContact()  // ACTION_CONTACT (default)
            }
        }
        isClickable = ad.action != RemoteConfig.ACTION_NONE
    }

    private fun loadImage(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val bytes = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 9000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ProfessorVPN/3.6 (Android)")
                }
                conn.inputStream.use { it.readBytes() }
            } catch (_: Throwable) { null }

            // Decode to a Drawable so ANIMATED GIFs play (ImageDecoder, API 28+).
            // PNG / JPG / WEBP and static GIFs render as a normal bitmap drawable.
            val drawable: android.graphics.drawable.Drawable? = if (bytes != null) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val src = android.graphics.ImageDecoder.createSource(
                            java.nio.ByteBuffer.wrap(bytes)
                        )
                        android.graphics.ImageDecoder.decodeDrawable(src)
                    } else {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null)
                            android.graphics.drawable.BitmapDrawable(resources, bmp) else null
                    }
                } catch (_: Throwable) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null)
                        android.graphics.drawable.BitmapDrawable(resources, bmp) else null
                }
            } else null

            withContext(Dispatchers.Main) {
                if (drawable != null) {
                    image.setImageDrawable(drawable)
                    if (drawable is android.graphics.drawable.Animatable) {
                        try { (drawable as android.graphics.drawable.Animatable).start() } catch (_: Throwable) {}
                    }
                    lastImageUrl = url
                    image.visibility = View.VISIBLE
                    // with FIT_CENTER the whole image is shown; keep a small safe
                    // margin so it never touches the rounded corners / card edge.
                    card.setPadding(dp(8), dp(8), dp(8), dp(8))
                    title.visibility = View.GONE
                    subtitle.visibility = View.GONE
                } else {
                    lastImageUrl = null
                    image.visibility = View.GONE
                    card.setPadding(dp(6), dp(6), dp(6), dp(6))
                }
            }
        }
    }

    private fun openContact() {
        try {
            context.startActivity(Intent(context, ContactActivity::class.java))
        } catch (_: Throwable) {}
    }

    private fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun parseColor(s: String, def: Int): Int = try {
        Color.parseColor(s.trim())
    } catch (_: Throwable) { def }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
