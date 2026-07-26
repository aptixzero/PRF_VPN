package com.neonvpn.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neonvpn.app.R
import com.neonvpn.app.config.DownloadLinksConfig
import com.neonvpn.app.config.RemoteConfig
import com.neonvpn.app.config.RemoteConfigStore
import com.neonvpn.app.util.QrCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * v6.3 — the "Download Links" page reached from the hamburger menu.
 *
 * It does three things, exactly as the brief describes:
 *
 *  1. **Generates a QR / bar code.** The code is produced locally by our own
 *     [QrCode] encoder — no library, no network, so it renders instantly and
 *     works completely offline.
 *
 *  2. **Bluetooth hand-off.** The payload inside the QR code is a deep link
 *     (`professorvpn://get?bt=1&…`). When the nearby person scans it, their
 *     phone opens this same page with `bt=1`, which makes the app turn THEIR
 *     Bluetooth on automatically and immediately announce that the *other*
 *     device's Bluetooth must be on too. Tapping "Send app over Bluetooth"
 *     then hands our own installed APK to the system Bluetooth share sheet, so
 *     the install file is transferred at the radio's full speed — the receiver
 *     can install it and start using the app. If a phone has no Bluetooth (or
 *     the user declines), we fall back to the plain share sheet + the download
 *     links below, so the page is never a dead end.
 *
 *  3. **Lists the admin-published download links** in a scrollable list, each
 *     row with a COPY button that puts the raw URL on the clipboard so the user
 *     can paste it into a browser.
 */
class DownloadsActivity : BaseActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var qrImage: ImageView
    private lateinit var btStatus: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val adapter = LinkAdapter()

    /** Set when we arrived here from a scanned QR code (receiver side). */
    private var arrivedFromScan = false

    // ---------------------------------------------------------------- launchers

    /** Result of the system "turn Bluetooth on?" dialog. */
    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Whatever the user answered, re-evaluate and continue.
            if (isBluetoothOn()) {
                btStatus.setText(R.string.downloads_bt_ready)
                shareApkOverBluetooth()
            } else {
                btStatus.setText(R.string.downloads_bt_prompt)
            }
        }

    /** Runtime BLUETOOTH_CONNECT / BLUETOOTH_SCAN permissions (Android 12+). */
    private val btPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) {
                turnBluetoothOnThenShare()
            } else {
                btStatus.setText(R.string.downloads_bt_denied)
                // Never a dead end — offer the plain share sheet instead.
                shareApkGeneric()
            }
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        qrImage = findViewById(R.id.qr_image)
        btStatus = findViewById(R.id.bt_status)
        empty = findViewById(R.id.dl_empty)
        list = findViewById(R.id.dl_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.isNestedScrollingEnabled = false

        findViewById<View>(R.id.btn_bluetooth).setOnClickListener { onBluetoothTapped() }

        // Someone scanned our code: their phone opened us with bt=1. Turn their
        // Bluetooth on right away and tell them the other side must be on too.
        // v6.5 — resolved BEFORE bind()/renderQr() so the caption logic knows
        // whether it may overwrite the hand-off prompt.
        arrivedFromScan = intent?.data?.getQueryParameter("bt") == "1"

        bind(RemoteConfigStore.current())

        // v6.5 — tapping the barcode opens the same link in a browser, so the
        // owner of the phone can use it too rather than needing a second device.
        qrImage.setOnClickListener { openInBrowser(buildQrPayload()) }
        if (arrivedFromScan) {
            btStatus.setText(R.string.downloads_bt_prompt)
            qrImage.post { onBluetoothTapped() }
        }

        // Pull the freshest links from the panel (best effort, never blocking).
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { RemoteConfigStore.refresh(applicationContext) } }
            if (!isFinishing && !isDestroyed) bind(RemoteConfigStore.current())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    // ---------------------------------------------------------------- rendering

    private fun bind(cfg: RemoteConfig) {
        val dl = cfg.downloadLinks
        findViewById<TextView>(R.id.dl_heading).text =
            dl.heading.ifBlank { getString(R.string.downloads_heading) }
        findViewById<TextView>(R.id.dl_note).text =
            dl.note.ifBlank { getString(R.string.downloads_note) }

        val items = if (dl.enabled) dl.items else emptyList()
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        // v6.5 — REDRAW THE BARCODE whenever the panel config changes.
        // v6.4 only encoded the QR once, in onCreate(), using whatever config was
        // cached at that moment. The panel refresh a second later then updated the
        // link list but left the barcode showing the STALE payload — so publishing
        // a new "لینک پشت بارکد" appeared to do nothing until the app was
        // restarted. Re-rendering here keeps the code and the published link in
        // lockstep (encoding costs well under a millisecond).
        renderQr()
    }

    /**
     * Build the QR payload and draw it. Encoding a few dozen bytes takes well
     * under a millisecond, so this is safe on the main thread and the page never
     * shows an empty box.
     */
    private fun renderQr() {
        val payload = buildQrPayload()
        // v6.5 — QUARTILE error correction (25% recoverable) instead of MEDIUM.
        // A barcode on a phone screen is scanned off a GLOSSY, reflective surface
        // by another phone's camera, often at an angle and under room lighting;
        // glare wipes out whole modules. The stronger level costs a slightly
        // denser code (still trivially scannable at this size) and buys a large
        // margin against exactly the real-world conditions the user described.
        val bmp = runCatching {
            QrCode.bitmap(payload, sizePx = 640, ecc = QrCode.Ecc.QUARTILE)
        }.getOrNull() ?: runCatching {
            // If the payload is too long for QUARTILE at any version, fall back
            // rather than showing an empty box.
            QrCode.bitmap(payload, sizePx = 640)
        }.getOrNull()
        if (bmp != null) qrImage.setImageBitmap(bmp)
        renderQrCaption()
    }

    /** v6.5 — show the operator's optional caption under the barcode. */
    private fun renderQrCaption() {
        val cfg = RemoteConfigStore.current()
        val caption = cfg.qrLink.caption.trim()
        if (caption.isBlank()) return
        // Reuse the Bluetooth status line (it sits directly under the code) so no
        // layout change is needed and the text can never overlap anything. The
        // scan hand-off prompt still wins when someone arrived via a scan.
        if (!arrivedFromScan) btStatus.text = caption
    }

    /**
     * v6.5 — WHAT THE SCANNER ACTUALLY RECEIVES.
     *
     * Reported bug: *"the barcode doesn't work — it must be a REAL barcode that
     * takes the scanner to the link."* Two separate things were wrong, and both
     * are fixed:
     *
     *   1. the encoder itself produced an undecodable matrix — fixed in
     *      [QrCode] (see the interleave comment there);
     *   2. the PAYLOAD was not a clean link. v6.4 took the first download link
     *      and appended `?bt=1&v=6.4`. The `bt=1` marker only means something to
     *      THIS app, and because the app registers a deep link for this very
     *      page, a scan could be swallowed by the app instead of opening the
     *      scanner's browser — and on a phone that doesn't have the app, the
     *      extra query string is just noise on the operator's URL.
     *
     * So v6.5 encodes ONE thing, verbatim: the link the operator typed into the
     * panel's "لینک پشت بارکد" box. Nothing is appended. A plain `https://…`
     * payload is what every phone camera recognises and offers to open in the
     * browser, which is exactly the requested behaviour.
     *
     * Fallback order when the operator hasn't published a barcode link yet:
     * first download link → the releases landing page. Both are still emitted as
     * clean URLs with no `bt=1`.
     */
    private fun buildQrPayload(): String {
        val cfg = RemoteConfigStore.current()

        // 1) the operator's explicit barcode link wins, used EXACTLY as given.
        val panelLink = cfg.qrLink.normalizedUrl()
        if (cfg.qrLink.enabled && panelLink.isNotBlank()) return panelLink

        // 2) otherwise the first published download link, unmodified.
        val first = cfg.downloadLinks.items.firstOrNull()?.url?.trim().orEmpty()
        if (first.isNotBlank()) return first

        // 3) last resort: the public releases page.
        return DEFAULT_LANDING
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    // ---------------------------------------------------------------- bluetooth

    private fun onBluetoothTapped() {
        val adapterBt = bluetoothAdapter()
        if (adapterBt == null) {
            btStatus.setText(R.string.downloads_bt_unsupported)
            shareApkGeneric()
            return
        }
        // Android 12+ needs runtime BLUETOOTH_CONNECT before we may enable or
        // even read the adapter state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPermissions()) {
            btPermLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            )
            return
        }
        turnBluetoothOnThenShare()
    }

    /**
     * Turn this device's Bluetooth ON automatically, then start the transfer.
     *
     * On Android 13+ `BluetoothAdapter.enable()` is a no-op for normal apps, so
     * the supported way to "turn it on automatically" is the one-tap system
     * request — we fire it immediately without the user having to hunt through
     * Settings. On older versions we flip the radio directly.
     */
    private fun turnBluetoothOnThenShare() {
        val bt = bluetoothAdapter() ?: run { shareApkGeneric(); return }
        if (bt.isEnabled) {
            btStatus.setText(R.string.downloads_bt_ready)
            shareApkOverBluetooth()
            return
        }
        btStatus.setText(R.string.downloads_bt_enabling)
        // Legacy direct enable (silent) where the platform still allows it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val ok = runCatching { @Suppress("DEPRECATION") bt.enable() }.getOrDefault(false)
            if (ok) {
                btStatus.setText(R.string.downloads_bt_ready)
                // Give the radio a moment to come up before handing off.
                qrImage.postDelayed({ shareApkOverBluetooth() }, 1200L)
                return
            }
        }
        // Otherwise: one-tap system enable request.
        runCatching {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.onFailure {
            btStatus.setText(R.string.downloads_bt_failed)
            shareApkGeneric()
        }
    }

    /**
     * Hand our own installed APK to the system Bluetooth share target so it is
     * transferred to the nearby device at the radio's full speed.
     */
    private fun shareApkOverBluetooth() {
        Toast.makeText(this, R.string.downloads_bt_prompt, Toast.LENGTH_LONG).show()
        scope.launch {
            val uri = withContext(Dispatchers.IO) { exportOwnApk() }
            if (uri == null) {
                btStatus.setText(R.string.downloads_bt_failed)
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = APK_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Prefer the OPP (Bluetooth share) component when it exists so the
            // transfer starts with a single tap.
            val opp = resolveBluetoothShare(send)
            val started = if (opp != null) {
                runCatching {
                    startActivity(Intent(send).apply { setPackage(opp) })
                }.isSuccess
            } else false
            if (!started) {
                runCatching {
                    startActivity(Intent.createChooser(send, getString(R.string.downloads_share_bt)))
                }.onFailure { btStatus.setText(R.string.downloads_bt_failed) }
            }
        }
    }

    /** Plain share sheet — the graceful fallback when Bluetooth isn't available. */
    private fun shareApkGeneric() {
        scope.launch {
            val uri = withContext(Dispatchers.IO) { exportOwnApk() }
            val send = if (uri != null) {
                Intent(Intent.ACTION_SEND).apply {
                    type = APK_MIME
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, buildQrPayload())
                }
            }
            runCatching {
                startActivity(Intent.createChooser(send, getString(R.string.downloads_share_link)))
            }
        }
    }

    /** The package that owns the system Bluetooth "send file" activity, if any. */
    private fun resolveBluetoothShare(template: Intent): String? = runCatching {
        packageManager.queryIntentActivities(template, 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it.contains("bluetooth", ignoreCase = true) }
    }.getOrNull()

    /**
     * Copy our own installed APK into cache and expose it through the app's
     * FileProvider so other apps (Bluetooth share) may read it.
     */
    private fun exportOwnApk(): Uri? = runCatching {
        val src = File(applicationInfo.sourceDir)
        if (!src.exists()) return@runCatching null
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val version = appVersion().ifBlank { "app" }
        val out = File(dir, "ProfessorVPN-v$version.apk")
        // Re-copy only when missing or stale — keeps repeat shares instant.
        if (!out.exists() || out.length() != src.length()) {
            FileInputStream(src).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 1 shl 16) }
            }
        }
        FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
    }.getOrNull()

    private fun bluetoothAdapter(): BluetoothAdapter? = runCatching {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
    }.getOrNull()

    private fun isBluetoothOn(): Boolean =
        runCatching { bluetoothAdapter()?.isEnabled == true }.getOrDefault(false)

    private fun hasBtPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- links list

    private inner class LinkAdapter : RecyclerView.Adapter<LinkVH>() {
        private val items = ArrayList<DownloadLinksConfig.DownloadItem>()

        fun submit(next: List<DownloadLinksConfig.DownloadItem>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkVH =
            LinkVH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_download_link, parent, false)
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: LinkVH, position: Int) = holder.bind(items[position])
    }

    private inner class LinkVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.dl_title)
        private val url: TextView = v.findViewById(R.id.dl_url)
        private val note: TextView = v.findViewById(R.id.dl_item_note)
        private val copy: TextView = v.findViewById(R.id.dl_copy)
        private val open: ImageView = v.findViewById(R.id.dl_open)

        fun bind(item: DownloadLinksConfig.DownloadItem) {
            title.text = item.title
            url.text = item.url
            note.text = item.note
            note.visibility = if (item.note.isBlank()) View.GONE else View.VISIBLE
            copy.setOnClickListener { copyToClipboard(item.url) }
            open.setOnClickListener { openInBrowser(item.url) }
        }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("download_link", text))
            Toast.makeText(this, R.string.downloads_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInBrowser(link: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        /** Used when the panel hasn't published any link yet. */
        const val DEFAULT_LANDING = "https://github.com/aptixzero/my_prFF_vP_N/releases/latest"
    }
}
