package com.dockgo.encai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Penanda apakah app sedang tampil (diisi dari MainActivity.onStart/onStop). */
object AppState {
    @Volatile var inForeground = false
}

class ChatService : Service() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_URL = "url"
        const val EXTRA_KEY = "key"
        const val EXTRA_BODY = "body"
        const val ACTION_DONE = "com.dockgo.encai.CHAT_DONE"

        private const val CH_PROGRESS = "encai_progress"
        private const val CH_DONE = "encai_done"
        private const val NOTIF_PROGRESS = 1
        private const val READ_TIMEOUT_MS = 12 * 60 * 1000 // proxy menunggu maks 10 menit

        fun answersDir(ctx: Context): File =
            File(ctx.filesDir, "answers").apply { mkdirs() }

        fun resultFile(ctx: Context, chatId: String): File =
            File(answersDir(ctx), safeName(chatId) + ".json")

        private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

        fun start(ctx: Context, chatId: String, url: String, key: String, body: String) {
            val i = Intent(ctx, ChatService::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_BODY, body)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private val pool = Executors.newCachedThreadPool()
    private val active = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        showForeground()

        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val url = intent?.getStringExtra(EXTRA_URL)
        val key = intent?.getStringExtra(EXTRA_KEY)
        val body = intent?.getStringExtra(EXTRA_BODY)

        if (chatId == null || url == null || key == null || body == null) {
            if (active.get() == 0) stopSelfClean()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        active.incrementAndGet()

        pool.execute {
            val result = runCatching { callProxy(url, key, body) }
                .getOrElse { JSONObject().put("ok", false).put("status", 0).put("body", "Error: ${it.message}") }

            // simpan ke file dulu, baru beri tahu UI (supaya aman walau Activity sudah mati)
            runCatching { resultFile(this, chatId).writeText(result.toString()) }

            sendBroadcast(Intent(ACTION_DONE).setPackage(packageName).putExtra(EXTRA_CHAT_ID, chatId))

            if (!AppState.inForeground) notifyDone(chatId, result)

            if (active.decrementAndGet() <= 0) stopSelfClean()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        pool.shutdown()
        super.onDestroy()
    }

    // ---------- Jaringan ----------

    private fun callProxy(url: String, key: String, body: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            return JSONObject()
                .put("ok", code in 200..299)
                .put("status", code)
                .put("body", text)
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Notifikasi ----------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Proses AI", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Jawaban selesai", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel)
        else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun showForeground() {
        val n = builder(CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ENC AI")
            .setContentText("ENC AI sedang berpikir...")
            .setOngoing(true)
            .setContentIntent(openAppIntent(null))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, n)
        }
    }

    private fun openAppIntent(chatId: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (chatId != null) i.putExtra(EXTRA_CHAT_ID, chatId)
        return PendingIntent.getActivity(
            this, (chatId ?: "app").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifyDone(chatId: String, result: JSONObject) {
        val ok = result.optBoolean("ok")
        val preview = if (ok) extractPreview(result.optString("body")) else "Terjadi kesalahan, ketuk untuk melihat."

        val n = builder(CH_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (ok) "Jawaban sudah siap" else "Proses gagal")
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(chatId))
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(chatId.hashCode(), n)
    }

    private fun extractPreview(body: String): String = try {
        JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
            .replace(Regex("\\s+"), " ").trim().take(120)
    } catch (e: Exception) {
        "Ketuk untuk membuka."
    }

    // ---------- WakeLock & penutup ----------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "encai:chat").apply {
            acquire(12 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun stopSelfClean() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }
}
