package dev.codetether

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var bar: LinearLayout
    private lateinit var updateButton: Button
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var currentBase: String? = null
    private var probing = false
    private var suppressAuto = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var cm: ConnectivityManager
    private lateinit var prefs: SharedPreferences

    private fun lanUrl() = (prefs.getString("lan", DEFAULT_LAN) ?: "").trim().trimEnd('/')
    private fun wanUrl() = (prefs.getString("wan", "") ?: "").trim().trimEnd('/')
    private fun timeout() = prefs.getInt("timeout", 1200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = openPrefs()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F2F3F5"))
        }
        status = TextView(this).apply {
            text = getString(R.string.status_searching)
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setTypeface(Typeface.MONOSPACE)
            maxLines = 2
        }
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "📷"; contentDescription = getString(R.string.action_scan)
            setOnClickListener { startScan() }
        })
        updateButton = Button(this).apply {
            text = "⬆"; contentDescription = getString(R.string.action_update)
            visibility = android.view.View.GONE
            setOnClickListener { checkUpdates(manual = true) }
        }
        bar.addView(updateButton)
        bar.addView(Button(this).apply {
            text = "⟳"; contentDescription = getString(R.string.action_reload)
            setOnClickListener { connect(force = true) }
        })
        bar.addView(Button(this).apply {
            text = "⇄"; contentDescription = getString(R.string.action_switch)
            setOnClickListener { toggle() }
        })
        bar.addView(Button(this).apply {
            text = "⚙"; contentDescription = getString(R.string.action_settings)
            setOnClickListener { showSettings() }
        })

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
            ) {
                val pw = prefs.getString("password", "") ?: ""
                if (handler != null && pw.isNotBlank()) handler.proceed("opencode", pw)
                else handler?.cancel()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = cb
                return try {
                    startActivityForResult(params!!.createIntent(), FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        applyInsets(root)

        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { connect(force = false) }
            })
        } catch (_: Exception) {
        }

        if (!openFromIntent(intent)) connect(force = false)
        maybeCheckUpdates()
    }

    private fun openPrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            this, "oc_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        getSharedPreferences("oc", Context.MODE_PRIVATE)
    }

    private fun applyInsets(root: LinearLayout) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                bar.setPadding(dp(12), dp(8) + bars.top, dp(12), dp(8))
                web.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- настройка подключения ----------------

    private fun applySetupPayload(text: String): Boolean {
        val json = when {
            text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
            text.startsWith("codetether://") -> runCatching {
                val d = Uri.parse(text).getQueryParameter("d") ?: return false
                JSONObject(String(Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP)))
            }.getOrNull()
            else -> null
        } ?: return false

        val lan = json.optString("lan").ifBlank { null }
        val wan = json.optString("wan").ifBlank { null }
        val pw = json.optString("password").ifBlank { null }
        if (lan == null && wan == null) return false

        prefs.edit().apply {
            lan?.let { putString("lan", it) }
            wan?.let { putString("wan", it) }
            pw?.let { putString("password", it) }
        }.apply()

        suppressAuto = false
        currentBase = null
        ui.post {
            Toast.makeText(this, getString(R.string.setup_applied), Toast.LENGTH_SHORT).show()
            connect(force = true)
        }
        return true
    }

    private fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            launchScanner()
        }
    }

    private fun launchScanner() {
        try {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "scan error", Toast.LENGTH_LONG).show()
        }
    }

    private fun openFromIntent(intent: Intent?): Boolean {
        if (intent == null) return false

        intent.getStringExtra("lan")?.let { if (it.isNotBlank()) prefs.edit().putString("lan", it).apply() }
        intent.getStringExtra("wan")?.let { if (it.isNotBlank()) prefs.edit().putString("wan", it).apply() }
        intent.getStringExtra("password")?.let { prefs.edit().putString("password", it).apply() }

        val data = intent.data?.toString() ?: intent.getStringExtra("url")
        if (data.isNullOrBlank()) {
            val hadExtras = intent.hasExtra("lan") || intent.hasExtra("wan") || intent.hasExtra("password")
            if (!hadExtras) return false
            suppressAuto = false
            currentBase = null
            connect(force = true)
            return true
        }

        // QR-полезная нагрузка или codetether://setup
        if (applySetupPayload(data)) return true

        // ссылка пейринга
        val origin = runCatching {
            val u = URL(data); "${u.protocol}://${u.host}:${u.port}"
        }.getOrNull()
        if (origin != null) {
            val isTailnet = origin.startsWith("http://100.") || origin.contains("ts.net")
            if (isTailnet) prefs.edit().putString("wan", origin).apply()
            else prefs.edit().putString("lan", origin).apply()
        }

        suppressAuto = true
        status.text = getString(R.string.status_pairing, data.take(70))
        web.loadUrl(data)
        return true
    }

    private fun probe(base: String?, timeoutMs: Int): Boolean {
        if (base.isNullOrBlank()) return false
        return try {
            val conn = URL("$base/api/info").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "CodeTether/${BuildConfig.VERSION_NAME}")
            conn.responseCode
            conn.disconnect()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun connect(force: Boolean) {
        if (force) suppressAuto = false
        if (suppressAuto && !force) return
        if (probing) return
        probing = true
        val lan = lanUrl()
        val wan = wanUrl()
        val t = timeout()
        io.execute {
            val pool = Executors.newFixedThreadPool(2)
            // LAN: две попытки — на старте Wi-Fi/WebView первый запрос может не успеть
            val lanJob = FutureTask { probe(lan, t) || probe(lan, t + 400) }
            val wanJob = FutureTask { probe(wan, t + 2700) }
            pool.execute(lanJob)
            pool.execute(wanJob)
            val lanOk = runCatching { lanJob.get() }.getOrDefault(false)
            val wanOk = if (lanOk) false else runCatching { wanJob.get() }.getOrDefault(false)
            pool.shutdown()
            val base = if (lanOk) lan else if (wanOk) wan else lan
            val label = when {
                lanOk -> getString(R.string.label_lan)
                wanOk -> getString(R.string.label_wan)
                else -> getString(R.string.label_offline)
            }
            ui.post {
                probing = false
                if (base.isNullOrBlank()) {
                    status.text = getString(R.string.status_no_address)
                    return@post
                }
                status.text = "$label · $base"
                if (base != currentBase || force) {
                    currentBase = base
                    web.loadUrl(base)
                }
                // ушли на WAN — через 6 секунд проверим, не появилась ли локальная сеть
                if (!lanOk && wanOk) {
                    ui.postDelayed({
                        if (currentBase == wan) {
                            suppressAuto = false
                            currentBase = null
                            connect(force = true)
                        }
                    }, 6000)
                }
            }
        }
    }

    private fun toggle() {
        val lan = lanUrl()
        val wan = wanUrl()
        val other = if (currentBase == lan) wan else lan
        if (other.isNullOrBlank()) {
            status.text = getString(R.string.status_no_second)
            return
        }
        currentBase = other
        status.text = getString(R.string.status_manual, other)
        web.loadUrl(other)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun field(hint: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                this.inputType = inputType
                setSingleLine(true)
            }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 12f }

        val eLan = field(getString(R.string.hint_lan), lanUrl())
        val eWan = field(getString(R.string.hint_wan), wanUrl())
        val eTimeout = field(getString(R.string.hint_timeout), timeout().toString(), InputType.TYPE_CLASS_NUMBER)
        val ePass = field(getString(R.string.hint_password), prefs.getString("password", "") ?: "")

        box.addView(label(getString(R.string.label_lan)))
        box.addView(eLan)
        box.addView(label(getString(R.string.label_wan)))
        box.addView(eWan)
        box.addView(label(getString(R.string.hint_timeout)))
        box.addView(eTimeout)
        box.addView(label(getString(R.string.hint_password)))
        box.addView(ePass)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(box)
            .setPositiveButton(getString(R.string.save)) { _: DialogInterface, _: Int ->
                prefs.edit()
                    .putString("lan", eLan.text.toString().trim())
                    .putString("wan", eWan.text.toString().trim())
                    .putInt("timeout", eTimeout.text.toString().toIntOrNull() ?: 1200)
                    .putString("password", ePass.text.toString().trim())
                    .apply()
                currentBase = null
                connect(force = true)
            }
            .setNeutralButton(getString(R.string.scan_qr)) { _: DialogInterface, _: Int -> startScan() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ---------------- автообновление ----------------

    private fun maybeCheckUpdates() {
        val last = prefs.getLong("lastUpdateCheck", 0L)
        if (System.currentTimeMillis() - last < 24 * 3600 * 1000L) {
            if (prefs.getBoolean("updateAvailable", false)) updateButton.visibility = android.view.View.VISIBLE
            return
        }
        checkUpdates(manual = false)
    }

    private fun checkUpdates(manual: Boolean) {
        io.execute {
            val info = runCatching {
                val conn = URL("$REPO_API/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "CodeTether")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val o = JSONObject(body)
                val tag = o.optString("tag_name").removePrefix("v")
                val apk = o.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length())
                        .map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                        ?.optString("browser_download_url")
                }
                Triple(tag, apk, o.optString("html_url"))
            }.getOrNull()

            ui.post {
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                if (info == null) {
                    if (manual) Toast.makeText(this, getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                    return@post
                }
                val (tag, apk, page) = info
                val newer = tag.isNotBlank() && apk != null && isNewer(tag, BuildConfig.VERSION_NAME)
                prefs.edit().putBoolean("updateAvailable", newer).apply()
                updateButton.visibility = if (newer) android.view.View.VISIBLE else android.view.View.GONE
                when {
                    newer -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_title, tag))
                        .setMessage(getString(R.string.update_message, BuildConfig.VERSION_NAME, tag))
                        .setPositiveButton(getString(R.string.update_install)) { _: DialogInterface, _: Int -> downloadAndInstall(apk!!) }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()

                    manual -> Toast.makeText(this, getString(R.string.update_none), Toast.LENGTH_SHORT).show()
                    else -> Unit
                }
            }
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.split(".", "-").mapNotNull { it.toIntOrNull() }
        val a = parts(remote)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun downloadAndInstall(url: String) {
        Toast.makeText(this, getString(R.string.update_downloading), Toast.LENGTH_SHORT).show()
        io.execute {
            val ok = runCatching {
                val out = File(cacheDir, "codetether-update.apk")
                URL(url).openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                out
            }.getOrNull()
            ui.post {
                if (ok == null) {
                    Toast.makeText(this, getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                    return@post
                }
                installApk(ok)
            }
        }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_title_perm))
                .setMessage(getString(R.string.update_message_perm))
                .setPositiveButton(getString(R.string.update_open_settings)) { _: DialogInterface, _: Int ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    // ---------------- жизненный цикл ----------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            filePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            filePathCallback = null
            return
        }
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            val text = scan.contents
            if (!text.isNullOrBlank() && !applySetupPayload(text)) {
                Toast.makeText(this, getString(R.string.scan_unknown), Toast.LENGTH_LONG).show()
            }
            return
        }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchScanner()
            else Toast.makeText(this, getString(R.string.scan_no_permission), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onResume() {
        super.onResume()
        connect(force = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 1001
        private const val REQ_CAMERA = 1002
        private const val DEFAULT_LAN = "http://192.168.1.2:49374"
        private const val REPO_API = "https://api.github.com/repos/frantishak0/codetether"
    }
}
