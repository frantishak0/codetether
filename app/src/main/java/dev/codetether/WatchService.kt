package dev.codetether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Держит SSE-подключение к серверу OpenCode и показывает уведомление,
 * когда задача (session.execution.succeeded/failed) завершается.
 */
class WatchService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = false
    private var conn: HttpURLConnection? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(WATCH_ID, buildNotification(getString(R.string.watch_running), null))
        if (!running) {
            running = true
            worker = Thread { watchLoop() }.also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { conn?.disconnect() }
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prefs(): SharedPreferences? = runCatching {
        val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            this, "oc_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    private fun openPrefs(): SharedPreferences =
        prefs() ?: getSharedPreferences("oc", Context.MODE_PRIVATE)

    private fun authHeader(password: String): String =
        "Basic " + Base64.encodeToString("opencode:$password".toByteArray(), Base64.NO_WRAP)

    private fun watchLoop() {
        val p = openPrefs()
        val password = p.getString("password", "") ?: ""
        if (password.isBlank()) return
        val bases = listOf(p.getString("lan", "") ?: "", p.getString("wan", "") ?: "")
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http") }

        for (base in bases) {
            if (!running) return
            if (!isAlive(base, password)) continue
            streamEvents(base, password)
            if (running) break
        }
    }

    private fun isAlive(base: String, password: String): Boolean = try {
        val c = URL("$base/api/info").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.setRequestProperty("Authorization", authHeader(password))
        val code = c.responseCode
        c.disconnect()
        code in 200..499
    } catch (_: Exception) {
        false
    }

    private fun streamEvents(base: String, password: String) {
        try {
            val c = URL("$base/api/event").openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 0
            c.setRequestProperty("Authorization", authHeader(password))
            c.setRequestProperty("Accept", "text/event-stream")
            conn = c
            BufferedReader(c.inputStream.reader()).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (!payload.startsWith("{")) continue
                    handleEvent(JSONObject(payload), base, password)
                }
            }
        } catch (_: Exception) {
            // сеть пропала — сервис останется, при следующем запуске приложения переподключится
        } finally {
            runCatching { conn?.disconnect() }
            conn = null
        }
    }

    private fun handleEvent(event: JSONObject, base: String, password: String) {
        val type = event.optString("type")
        if (type != "session.execution.succeeded" && type != "session.execution.failed") return
        val sessionID = event.optJSONObject("data")?.optString("sessionID") ?: return
        val ok = type.endsWith("succeeded")
        val title = sessionTitle(base, password, sessionID) ?: sessionID
        val text = if (ok) getString(R.string.task_done, title) else getString(R.string.task_failed, title)
        notify(text, sessionID)
    }

    private fun sessionTitle(base: String, password: String, sessionID: String): String? = try {
        val c = URL("$base/api/session/$sessionID").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        c.setRequestProperty("Authorization", authHeader(password))
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        JSONObject(body).optString("title").ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_TASKS, getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_DEFAULT
        )
        mgr.createNotificationChannel(channel)
        val silent = NotificationChannel(
            CHANNEL_WATCH, getString(R.string.channel_watch), NotificationManager.IMPORTANCE_MIN
        )
        silent.setShowBadge(false)
        mgr.createNotificationChannel(silent)
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(text: String, channel: String?): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channel ?: CHANNEL_WATCH)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp())
            .setOngoing(channel == null || channel == CHANNEL_WATCH)
            .build()
    }

    private fun notify(text: String, tag: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(tag, NOTIFY_ID, buildNotification(text, CHANNEL_TASKS))
    }

    companion object {
        private const val WATCH_ID = 1001
        private const val NOTIFY_ID = 1002
        private const val CHANNEL_WATCH = "watch"
        private const val CHANNEL_TASKS = "tasks"
    }
}
