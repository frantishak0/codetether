package dev.codetether

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Process
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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
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
    private var recheckScheduled = false
    private var swCleared = false
    private var forceFresh = false
    private var healAttempts = 0
    private var lastHealAt = 0L
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var cm: ConnectivityManager
    private lateinit var prefs: SharedPreferences

    /** Пароль держим в памяти, если шифрованное хранилище недоступно (без тихого отката в plaintext). */
    private var memoryPassword: String? = null
    private var secureStorage = true

    private fun getPassword(): String =
        memoryPassword ?: if (secureStorage) (prefs.getString("password", "") ?: "") else ""

    private fun putPassword(value: String) {
        if (secureStorage) prefs.edit().putString("password", value).apply() else memoryPassword = value
    }

    /** Хосты, которым разрешено отдавать пароль (только настроенные адреса сервера). */
    private fun trustedHosts(): Set<String> = setOfNotNull(
        runCatching { Uri.parse(lanUrl()).host }.getOrNull(),
        runCatching { Uri.parse(wanUrl()).host }.getOrNull()
    )

    private fun isTrustedHost(host: String?): Boolean = host != null && trustedHosts().contains(host)

    private fun lanUrl() = (prefs.getString("lan", DEFAULT_LAN) ?: "").trim().trimEnd('/')
    private fun wanUrl() = (prefs.getString("wan", "") ?: "").trim().trimEnd('/')
    private fun timeout() = prefs.getInt("timeout", 1200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = openPrefs()

        // Полный сброс профиля WebView: по запросу, после обновления приложения
        // или когда страница упорно показывает старую версию интерфейса.
        val installedVersion = prefs.getString("appVersion", null)
        if (prefs.getBoolean("needsWebReset", false) ||
            prefs.getBoolean("needsFullWipe", false) ||
            installedVersion != BuildConfig.VERSION_NAME
        ) {
            prefs.edit()
                .putBoolean("needsWebReset", false)
                .putBoolean("needsFullWipe", false)
                .putBoolean("needsSwClear", true)
                .putString("appVersion", BuildConfig.VERSION_NAME)
                .apply()
            wipeWebViewData()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(HEADER_COLOR)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(HEADER_COLOR)
        }
        status = TextView(this).apply {
            text = getString(R.string.status_searching)
            textSize = 12f
            setTextColor(HEADER_TEXT_COLOR)
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
            setOnClickListener { connect(force = true, reload = true) }
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
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            setSaveFormData(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
            ) {
                val pw = getPassword()
                // отдаём пароль только своему серверу, а не любому хосту, который попросит авторизацию
                if (handler != null && pw.isNotBlank() && isTrustedHost(host)) handler.proceed("opencode", pw)
                else handler?.cancel()
            }

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                // UI OpenCode регистрирует Workbox-Service-Worker, который на ЛЮБУЮ навигацию
                // отдаёт закэшированный index.html (query-строку он игнорирует). В WebView он
                // не нужен: подменяем на пустой SW, который сразу снимает себя с регистрации.
                if (url.contains("/sw.js")) {
                    val js = "self.addEventListener('install',e=>self.skipWaiting());" +
                        "self.addEventListener('activate',e=>e.waitUntil(" +
                        "(async()=>{try{await self.registration.unregister()}catch(err){}})()));"
                    return WebResourceResponse(
                        "text/javascript", "UTF-8", ByteArrayInputStream(js.toByteArray())
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme != "http" && scheme != "https") {
                    return openExternally(uri)   // mailto:, tel:, intent: и т.п.
                }
                val serverHost = runCatching { Uri.parse(currentBase ?: "").host }.getOrNull()
                if (uri.host != null && uri.host == serverHost) return false  // свой сервер — внутри
                // прямые ссылки на файлы качаем сами (в «Загрузки»), остальное — в браузер
                if (looksLikeDownload(uri)) {
                    enqueueDownload(uri.toString(), null, null, null)
                    return true
                }
                return openExternally(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                if (forceFresh) {
                    forceFresh = false
                    web.settings.cacheMode = WebSettings.LOAD_DEFAULT
                }
                clearServiceWorkerOnce()
                checkPageHealth()
            }
        }
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val helper = WebView(this@MainActivity)
                helper.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                        val uri = r?.url
                        if (uri != null) openExternally(uri)
                        helper.destroy()
                        return true
                    }
                }
                transport.webView = helper
                resultMsg.sendToTarget()
                return true
            }

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

        for (i in 0 until bar.childCount) {
            (bar.getChildAt(i) as? Button)?.let {
                it.setTextColor(HEADER_TEXT_COLOR)
                it.backgroundTintList = android.content.res.ColorStateList.valueOf(HEADER_BUTTON_COLOR)
            }
        }

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        applyDarkSystemBars()
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
        askNotificationPermission()
    }

    private fun openPrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            this, "oc_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Keystore недоступен — работаем без сохранения пароля (в памяти до перезапуска)
        secureStorage = false
        getSharedPreferences("oc", Context.MODE_PRIVATE)
    }

    /** Удаляет данные WebView: HTTP-кэш, Code Cache, Local Storage, Service Worker. Cookies не трогаем. */
    /**
     * Полностью удаляет профиль WebView.
     *
     * Важно: `WebView.clearCache()` и удаление отдельных подпапок НЕ убирают CacheStorage
     * и регистрации Service Worker — именно они отдают старый index.html на любую
     * навигацию (игнорируя `?v=`). Поэтому сносим каталог `app_webview` целиком.
     * Вызывать только до создания WebView (или после перезапуска процесса).
     */
    private fun wipeWebViewData() {
        listOf("app_webview", "app_textures").forEach { name ->
            runCatching { File(dataDir, name).deleteRecursively() }
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    /** Перезапускает процесс приложения (нужно, чтобы Chromium отпустил профиль WebView). */
    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 300, pi)
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    /** Чёрная шапка и системные панели с белыми иконками. */
    private fun applyDarkSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
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

    /**
     * Удаляет Service Worker и его CacheStorage изнутри страницы.
     * Файловый сброс не работает: процесс Chromium держит файлы открытыми.
     */
    private fun clearServiceWorkerOnce() {
        if (swCleared || !prefs.getBoolean("needsSwClear", false)) return
        val js = """
            (async () => {
              try {
                if (navigator.serviceWorker) {
                  const regs = await navigator.serviceWorker.getRegistrations();
                  for (const r of regs) { await r.unregister(); }
                }
                if (window.caches) {
                  const keys = await caches.keys();
                  for (const k of keys) { await caches.delete(k); }
                }
                return 'ok';
              } catch (e) { return 'err:' + e; }
            })();
        """.trimIndent()
        swCleared = true
        web.evaluateJavascript(js) { _ ->
            prefs.edit().putBoolean("needsSwClear", false).apply()
            status.text = getString(R.string.cache_cleared)
            forceFresh = true
            web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            web.clearCache(true)
            val base = currentBase ?: lanUrl()
            web.loadUrl("$base/?v=" + System.currentTimeMillis())
        }
    }

    /**
     * Проверяет, что реально отрисовано: версию в подвале и текст ошибки.
     * Service Worker мог отдать старый index.html — тогда версия на странице не совпадёт
     * с /api/info. В этом случае снимаем SW, чистим CacheStorage и перезагружаем документ.
     * После нескольких неудач — полный сброс профиля WebView и перезапуск.
     */
    private fun checkPageHealth() {
        web.evaluateJavascript(HEALTH_JS) { raw ->
            android.util.Log.i("CodeTetherHealth", "raw=$raw")
            val parts = (raw ?: "").trim().removePrefix("\"").removeSuffix("\"").split("|")
            if (parts.size < 4) return@evaluateJavascript
            val pageVer = parts[0].trim()
            val err = parts[1].trim() == "1"
            val ctrl = parts[2].trim()
            val href = parts[3].trim()
            val expected = prefs.getString("serverVersion", null)
            android.util.Log.i(
                "CodeTetherHealth",
                "href=$href pageVer=$pageVer serverVer=$expected err=$err controller=$ctrl"
            )
            // Диагностика: сколько регистраций Service Worker осталось после загрузки.
            web.evaluateJavascript(
                "(function(){try{navigator.serviceWorker.getRegistrations()" +
                    ".then(function(r){window.__ctSw=r.length})" +
                    ".catch(function(){window.__ctSw=-1})}catch(e){window.__ctSw=-2}return 'ok'})()"
            ) {}
            ui.postDelayed({
                web.evaluateJavascript(
                    "(window.__ctSw===undefined?'?':String(window.__ctSw))"
                ) { v -> android.util.Log.i("CodeTetherHealth", "swRegistrations=$v") }
            }, 3000)

            val stale = pageVer.isNotEmpty() && !expected.isNullOrEmpty() && pageVer != expected
            if (stale || err) heal(pageVer, expected) else healAttempts = 0
        }
    }

    private fun heal(pageVer: String, expected: String?) {
        val now = System.currentTimeMillis()
        if (now - lastHealAt < 8000) return
        lastHealAt = now
        healAttempts++
        if (healAttempts > 3) {
            prefs.edit().putBoolean("needsFullWipe", true).apply()
            restartApp()
            return
        }
        status.text = getString(R.string.status_recovering, pageVer.ifEmpty { "?" })
        forceFresh = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        val base = currentBase ?: lanUrl()
        web.loadUrl("$base/?v=${expected ?: now}&r=$now")
    }

    /**
     * Документ всегда грузим с версией в URL: так HTTP-кэш не может отдать старый index.html,
     * который ссылается на уже не существующие файлы.
     */
    private fun pageUrl(base: String): String {
        val v = prefs.getString("serverVersion", null) ?: BuildConfig.VERSION_NAME
        return "$base/?v=$v"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Открывает ссылку во внешнем приложении (браузере). */
    private fun openExternally(uri: Uri): Boolean {
        return try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
            true
        }
    }

    // ---------------- настройка подключения ----------------

    /** Разбирает payload из QR/JSON. Возвращает (lan, wan, password). */
    private fun parseSetupPayload(text: String): Triple<String?, String?, String?>? {
        val json = when {
            text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
            text.startsWith("codetether://") -> runCatching {
                val d = Uri.parse(text).getQueryParameter("d") ?: return null
                JSONObject(String(Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP)))
            }.getOrNull()
            else -> null
        } ?: return null

        val lan = json.optString("lan").ifBlank { null }
        val wan = json.optString("wan").ifBlank { null }
        val pw = json.optString("password").ifBlank { null }
        if (lan == null && wan == null) return null
        return Triple(lan, wan, pw)
    }

    /** Показывает подтверждение перед сменой сервера: любая ссылка/интент могут перенастроить клиент. */
    private fun confirmAndApply(lan: String?, wan: String?, pw: String?) {
        val lines = buildString {
            if (lan != null) append(getString(R.string.confirm_host_lan, lan)).append("\n")
            if (wan != null) append(getString(R.string.confirm_host_wan, wan)).append("\n")
            if (pw != null) append(getString(R.string.confirm_with_password))
        }.trim()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_setup_title))
            .setMessage(getString(R.string.confirm_setup_message, lines))
            .setPositiveButton(getString(R.string.confirm_apply)) { _: DialogInterface, _: Int ->
                prefs.edit().apply {
                    lan?.let { putString("lan", it) }
                    wan?.let { putString("wan", it) }
                }.apply()
                if (pw != null) putPassword(pw)
                suppressAuto = false
                currentBase = null
                connect(force = true)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

        val xLan = intent.getStringExtra("lan")?.ifBlank { null }
        val xWan = intent.getStringExtra("wan")?.ifBlank { null }
        val xPw = intent.getStringExtra("password")?.ifBlank { null }
        val data = intent.data?.toString() ?: intent.getStringExtra("url")

        // 1. только extras (автоматизация, отладка)
        if (data.isNullOrBlank()) {
            if (xLan == null && xWan == null && xPw == null) return false
            confirmAndApply(xLan, xWan, xPw)
            return true
        }

        // 2. QR / codetether://setup
        val payload = parseSetupPayload(data)
        if (payload != null) {
            confirmAndApply(payload.first, payload.second, payload.third)
            return true
        }

        // 3. ссылка пейринга — тоже требуем подтверждение хоста
        val origin = runCatching {
            val u = URL(data); "${u.protocol}://${u.host}:${u.port}"
        }.getOrNull()
        if (origin == null) return false
        val isTailnet = origin.startsWith("http://100.") || origin.contains("ts.net")
        confirmAndApply(
            if (isTailnet) null else origin,
            if (isTailnet) origin else null,
            xPw
        )
        return true
    }

    /** Проверяет доступность сервера. Возвращает пару: доступен ли, версия сервера (если отдал). */
    private fun probeInfo(base: String?, timeoutMs: Int): Pair<Boolean, String?> {
        if (base.isNullOrBlank()) return false to null
        val pw = getPassword()
        val host = runCatching { Uri.parse(base).host }.getOrNull()
        return try {
            val conn = URL("$base/api/info").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "CodeTether/${BuildConfig.VERSION_NAME}")
            if (pw.isNotBlank() && isTrustedHost(host)) {
                val token = Base64.encodeToString("opencode:$pw".toByteArray(), Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            val code = conn.responseCode
            val version = if (code == 200) {
                runCatching {
                    JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optString("version").ifBlank { null }
                }.getOrNull()
            } else null
            conn.disconnect()
            (code in 200..499) to version
        } catch (_: Exception) {
            false to null
        }
    }

    private fun connect(force: Boolean, reload: Boolean = force) {
        if (force) suppressAuto = false
        if (suppressAuto && !force) return
        if (probing) return
        probing = true
        val lan = lanUrl()
        val wan = wanUrl()
        val t = timeout()
        io.execute {
            var chosen: String? = null
            var serverVersion: String? = null
            val cur = currentBase

            // 1. если уже подключены — проверяем ТОЛЬКО текущий адрес и никуда не прыгаем
            if (cur != null) {
                val r = probeWithRetry(cur, t)
                if (r.first) {
                    chosen = cur
                    serverVersion = r.second
                }
            }

            // 2. если текущий недоступен (или ещё не выбрано) — ищем рабочий
            if (chosen == null) {
                val lanRes = probeWithRetry(lan, t)
                if (lanRes.first) {
                    chosen = lan
                    serverVersion = lanRes.second
                } else {
                    val wanRes = probeInfo(wan, t + 2700)
                    if (wanRes.first) {
                        chosen = wan
                        serverVersion = wanRes.second
                    } else {
                        chosen = lan
                    }
                }
            }

            // 3. сидим на WAN — раз в минуту пробуем вернуться в локальную сеть
            if (chosen == wan && lan.isNotBlank()) {
                val lanRes = probeInfo(lan, t)
                if (lanRes.first) {
                    chosen = lan
                    serverVersion = lanRes.second ?: serverVersion
                }
            }

            val base = chosen
            val label = when {
                base == lan -> getString(R.string.label_lan)
                base == wan -> getString(R.string.label_wan)
                else -> getString(R.string.label_offline)
            }
            ui.post {
                probing = false
                if (base.isNullOrBlank()) {
                    status.text = getString(R.string.status_no_address)
                    return@post
                }
                status.text = "$label · $base"

                // сервер обновился (или это первый запуск после установки) — в WebView остался
                // старый кэш, включая Service Worker, который отдаёт исчезнувшие файлы.
                // Поэтому делаем жёсткий сброс данных WebView и перезапускаем активность.
                val known = prefs.getString("serverVersion", null)
                if (!serverVersion.isNullOrBlank() && known != serverVersion) {
                    // Сервер обновился — в WebView остался старый интерфейс (и его Service
                    // Worker). Полный сброс профиля возможен только в новом процессе,
                    // поэтому перезапускаемся, а не просто recreate().
                    prefs.edit()
                        .putString("serverVersion", serverVersion)
                        .putBoolean("needsFullWipe", true)
                        .apply()
                    restartApp()
                    return@post
                }

                // перезагружаем страницу только если адрес изменился (или это ручное ⟳)
                if (base != currentBase || reload) {
                    currentBase = base
                    web.loadUrl(pageUrl(base))
                }
                // сидим на WAN — раз в минуту пробуем вернуться в локальную сеть,
                // страницу при этом не перезагружаем
                if (base == wan && !recheckScheduled) {
                    recheckScheduled = true
                    ui.postDelayed({
                        recheckScheduled = false
                        if (currentBase == wan) {
                            suppressAuto = false
                            connect(force = true, reload = false)
                        }
                    }, 60000)
                }
            }
        }
    }

    private fun probeWithRetry(base: String?, t: Int): Pair<Boolean, String?> {
        val first = probeInfo(base, t)
        if (first.first) return first
        return probeInfo(base, t + 400)
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
        web.loadUrl(pageUrl(other))
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
                    .also { putPassword(ePass.text.toString().trim()) }
                    .apply()
                currentBase = null
                connect(force = true)
            }
            .setNeutralButton(getString(R.string.more)) { _: DialogInterface, _: Int -> showMoreMenu() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showMoreMenu() {
        val items = arrayOf(
            getString(R.string.scan_qr),
            getString(R.string.discover),
            getString(R.string.sessions),
            getString(R.string.check_updates),
            getString(R.string.about),
            getString(R.string.clear_cache),
            getString(R.string.clear_cookies)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.more))
            .setItems(items) { _: DialogInterface, which: Int ->
                when (which) {
                    0 -> startScan()
                    1 -> discoverServers()
                    2 -> showSessions()
                    3 -> checkUpdates(manual = true)
                    4 -> showAbout()
                    5 -> {
                        // Полный сброс: профиль WebView удаляем в новом процессе
                        prefs.edit().putBoolean("needsFullWipe", true).apply()
                        restartApp()
                    }
                    6 -> {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        status.text = getString(R.string.cookies_cleared)
                    }
                }
            }
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


    // ---------------- скачивание файлов ----------------

    private val downloadExtensions = setOf(
        "zip", "tar", "gz", "tgz", "7z", "rar", "pdf", "apk",
        "docx", "xlsx", "pptx", "csv", "bin", "img", "iso", "dmg", "exe", "msi"
    )

    private fun looksLikeDownload(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        val ext = path.substringAfterLast('.', "")
        return ext in downloadExtensions
    }

    private fun guessFileName(disposition: String?, url: String, mime: String?): String {
        val fromHeader = disposition?.let {
            Regex("filename\\*?=\"?([^\";]+)\"?").find(it)?.groupValues?.getOrNull(1)
        }
        val fromUrl = Uri.parse(url).lastPathSegment
        val name = (fromHeader ?: fromUrl ?: "download").trim().trim('"')
        return if (name.contains('.')) name else name + when {
            mime?.contains("zip") == true -> ".zip"
            mime?.contains("pdf") == true -> ".pdf"
            mime?.startsWith("image/") == true -> ".png"
            else -> ""
        }
    }

    private fun enqueueDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        try {
            if (!url.startsWith("http")) { openExternally(Uri.parse(url)); return }
            val uri = Uri.parse(url)
            val fileName = guessFileName(disposition, url, mime)
            val request = DownloadManager.Request(uri).apply {
                setTitle(fileName)
                mime?.let { setMimeType(it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                val pw = getPassword()
                if (pw.isNotBlank() && isTrustedHost(uri.host)) {
                    val token = Base64.encodeToString("opencode:$pw".toByteArray(), Base64.NO_WRAP)
                    addRequestHeader("Authorization", "Basic $token")
                }
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            openExternally(Uri.parse(url))  // запасной путь — отдать браузеру
        }
    }

    // ---------------- список сессий ----------------

    private fun showSessions() {
        val base = currentBase ?: lanUrl()
        if (base.isBlank()) { Toast.makeText(this, getString(R.string.status_no_address), Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, getString(R.string.loading), Toast.LENGTH_SHORT).show()
        io.execute {
            val items = runCatching {
                val conn = URL("$base/api/session").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 7000
                val pw = getPassword()
                if (pw.isNotBlank() && isTrustedHost(Uri.parse(base).host)) {
                    val token = Base64.encodeToString("opencode:$pw".toByteArray(), Base64.NO_WRAP)
                    conn.setRequestProperty("Authorization", "Basic $token")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val root = JSONObject("{\"data\":\"\"}")
                val arr = runCatching { JSONArray(body) }.getOrElse {
                    JSONObject(body).optJSONArray("data") ?: JSONArray()
                }
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val model = o.optJSONObject("model")?.optString("id").orEmpty()
                    val ts = o.optJSONObject("time")?.optLong("updated") ?: 0L
                    val when_ = if (ts > 0) java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts)) else ""
                    "${o.optString("title").ifBlank { o.optString("id") }}  ·  $model  ·  $when_"
                }
            }.getOrDefault(emptyList())
            ui.post {
                if (items.isEmpty()) {
                    Toast.makeText(this, getString(R.string.sessions_empty), Toast.LENGTH_SHORT).show()
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.sessions))
                    .setItems(items.toTypedArray()) { _: DialogInterface, _: Int ->
                        // веб-интерфейс не поддерживает прямые ссылки на сессию — открываем список
                        currentBase = base
                        web.loadUrl(pageUrl(base))
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }


    // ---------------- поиск сервера в локальной сети ----------------

    private fun discoverServers() {
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val queue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val info = queue.poll() ?: return
            resolving = true
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        resolving = false
                        ui.post { resolveNext() }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                        val host = serviceInfo?.host?.hostAddress
                        val port = serviceInfo?.port ?: 0
                        val name = serviceInfo?.serviceName ?: "service"
                        if (host == null || port == 0) {
                            resolving = false; ui.post { resolveNext() }; return
                        }
                        io.execute {
                            val url = "http://$host:$port"
                            val ok = probeInfo(url, 1500).first
                            ui.post {
                                if (ok) results.add(url to "$name · $url")
                                resolving = false
                                resolveNext()
                            }
                        }
                    }
                })
            }.onFailure {
                resolving = false
                ui.post { resolveNext() }
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                ui.post { Toast.makeText(this@MainActivity, getString(R.string.discover_failed), Toast.LENGTH_SHORT).show() }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null) { queue.add(serviceInfo); ui.post { resolveNext() } }
            }
        }

        Toast.makeText(this, getString(R.string.discovering), Toast.LENGTH_SHORT).show()
        runCatching { nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Toast.makeText(this, getString(R.string.discover_failed), Toast.LENGTH_SHORT).show()
                return
            }

        ui.postDelayed({
            runCatching { nsd.stopServiceDiscovery(listener) }
            val items = results.map { it.second }
            if (items.isEmpty()) {
                Toast.makeText(this, getString(R.string.discover_empty), Toast.LENGTH_LONG).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.discover_title))
                    .setItems(items.toTypedArray()) { _: DialogInterface, which: Int ->
                        val url = results[which].first
                        prefs.edit().putString("lan", url).apply()
                        currentBase = null
                        connect(force = true, reload = true)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }, 4000)
    }

    // ---------------- о приложении ----------------

    private fun showAbout() {
        val server = prefs.getString("serverVersion", null) ?: "—"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about))
            .setMessage(
                getString(
                    R.string.about_message,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE.toString(),
                    currentBase ?: "—",
                    server
                )
            )
            .setPositiveButton(getString(R.string.close), null)
            .setNeutralButton(getString(R.string.check_updates)) { _: DialogInterface, _: Int -> checkUpdates(manual = true) }
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
            return
        }
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            val text = scan.contents
            val payload = if (!text.isNullOrBlank()) parseSetupPayload(text) else null
            if (payload != null) {
                confirmAndApply(payload.first, payload.second, payload.third)
            } else {
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

    override fun onStart() {
        super.onStart()
        runCatching { stopService(Intent(this, WatchService::class.java)) }
    }

    override fun onStop() {
        super.onStop()
        startWatcher()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    /** Пока приложение в фоне — слушаем события сервера и показываем уведомление о завершении задачи. */
    private fun startWatcher() {
        if (prefs.getString("password", "")?.isBlank() != false) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(Intent(this, WatchService::class.java))
            else
                startService(Intent(this, WatchService::class.java))
        }
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
        private val HEADER_COLOR = Color.parseColor("#0B1220")
        private val HEADER_TEXT_COLOR = Color.parseColor("#E6EDF7")
        private val HEADER_BUTTON_COLOR = Color.parseColor("#1B2540")
        private const val FILE_CHOOSER_REQUEST = 1001
        private const val REQ_CAMERA = 1002
        private const val REQ_NOTIFICATIONS = 1003
        /**
         * Снимает регистрацию Service Worker и чистит CacheStorage, затем сообщает
         * версию из подвала страницы и наличие экрана ошибки. Формат: "ver|sw|кэши|err".
         */
        /**
         * Синхронно сообщает версию из подвала, наличие экрана ошибки, контроллера SW
         * и текущий URL. Снятие регистрации SW и чистка CacheStorage запускаются
         * «в фоне» — evaluateJavascript не умеет ждать Promise.
         * Формат: "ver|err|controller|href".
         */
        private val HEALTH_JS = """
            (function () {
              let ver = '';
              try {
                const t = (document.body && document.body.innerText) || '';
                const m = t.match(/Версия:\s*([0-9][0-9.]*)/);
                if (m) ver = m[1];
              } catch (e) {}
              let err = 0;
              try {
                const t = (document.body && document.body.innerText) || '';
                if (/Failed to fetch|Что-то пошло не так/.test(t)) err = 1;
              } catch (e) {}
              let ctrl = 0;
              try { ctrl = (navigator.serviceWorker && navigator.serviceWorker.controller) ? 1 : 0; } catch (e) {}
              try {
                if (navigator.serviceWorker) {
                  navigator.serviceWorker.getRegistrations().then(function (rs) {
                    rs.forEach(function (r) { try { r.unregister(); } catch (e) {} });
                  }).catch(function () {});
                }
              } catch (e) {}
              try {
                if (window.caches) {
                  caches.keys().then(function (ks) {
                    ks.forEach(function (k) { try { caches.delete(k); } catch (e) {} });
                  }).catch(function () {});
                }
              } catch (e) {}
              return ver + '|' + err + '|' + ctrl + '|' + location.href;
            })();
        """.trimIndent()

        private const val DEFAULT_LAN = "http://192.168.1.2:49374"
        private const val REPO_API = "https://api.github.com/repos/frantishak0/codetether"
    }
}
