package dev.codetether

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
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
import android.text.InputType
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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var bar: LinearLayout
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
    private fun timeout() = prefs.getInt("timeout", 800)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this, "oc_secure", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // если Keystore недоступен — работаем без шифрования, но не падаем
            getSharedPreferences("oc", Context.MODE_PRIVATE)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
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
    }

    private fun applyInsets(root: LinearLayout) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                bar.setPadding(dp(16), dp(10) + bars.top, dp(16), dp(10))
                web.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
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
            conn.setRequestProperty("User-Agent", "CodeTether/1.0")
            conn.responseCode // любой ответ (200/401/403) = сервер доступен
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
            val lanJob = FutureTask { probe(lan, t) }
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

        val eLan = field(getString(R.string.hint_lan), lanUrl())
        val eWan = field(getString(R.string.hint_wan), wanUrl())
        val eTimeout = field(getString(R.string.hint_timeout), timeout().toString(), InputType.TYPE_CLASS_NUMBER)
        val ePass = field(getString(R.string.hint_password), prefs.getString("password", "") ?: "")

        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 12f }

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
                    .putInt("timeout", eTimeout.text.toString().toIntOrNull() ?: 800)
                    .putString("password", ePass.text.toString().trim())
                    .apply()
                currentBase = null
                connect(force = true)
            }
            .setNeutralButton(getString(R.string.clear_cookies)) { _: DialogInterface, _: Int ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                status.text = getString(R.string.cookies_cleared)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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
        } else {
            @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
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
        private const val DEFAULT_LAN = "http://192.168.1.2:49374"
    }
}
