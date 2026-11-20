package com.dockgo.encai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatService extends Service {

    public static final String EXTRA_CHAT_ID = "chat_id";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_BODY = "body";
    public static final String ACTION_DONE = "com.dockgo.encai.CHAT_DONE";

    private static final String CH_PROGRESS = "encai_progress";
    private static final String CH_DONE = "encai_done";
    private static final int NOTIF_PROGRESS = 1;
    private static final int READ_TIMEOUT_MS = 12 * 60 * 1000; // proxy menunggu maks 10 menit

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger active = new AtomicInteger(0);
    private PowerManager.WakeLock wakeLock;

    // ---------- Helper statis ----------

    public static File answersDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "answers");
        d.mkdirs();
        return d;
    }

    public static File resultFile(Context ctx, String chatId) {
        return new File(answersDir(ctx), safeName(chatId) + ".json");
    }

    private static String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public static void start(Context ctx, String chatId, String url, String key, String body) {
        Intent i = new Intent(ctx, ChatService.class)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_BODY, body);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    // ---------- Lifecycle ----------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels();
        showForeground();

        final String chatId = intent != null ? intent.getStringExtra(EXTRA_CHAT_ID) : null;
        final String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        final String key = intent != null ? intent.getStringExtra(EXTRA_KEY) : null;
        final String body = intent != null ? intent.getStringExtra(EXTRA_BODY) : null;

        if (chatId == null || url == null || key == null || body == null) {
            if (active.get() == 0) stopSelfClean();
            return START_NOT_STICKY;
        }

        acquireWakeLock();
        active.incrementAndGet();

        pool.execute(() -> {
            JSONObject result;
            try {
                result = callProxy(url, key, body);
            } catch (Exception e) {
                result = new JSONObject();
                try {
                    result.put("ok", false);
                    result.put("status", 0);
                    result.put("body", "Error: " + e.getMessage());
                } catch (Exception ignored) { }
            }

            // simpan ke file dulu, baru beri tahu UI (aman walau Activity sudah mati)
            try (OutputStream os = new FileOutputStream(resultFile(this, chatId))) {
                os.write(result.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }

            sendBroadcast(new Intent(ACTION_DONE)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_CHAT_ID, chatId));

            if (!AppState.inForeground) notifyDone(chatId, result);

            if (active.decrementAndGet() <= 0) stopSelfClean();
        });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        pool.shutdown();
        super.onDestroy();
    }

    // ---------- Jaringan ----------

    private JSONObject callProxy(String url, String key, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);

            JSONObject r = new JSONObject();
            r.put("ok", code >= 200 && code < 300);
            r.put("status", code);
            r.put("body", text);
            return r;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ---------- Notifikasi ----------

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CH_PROGRESS, "Proses AI", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(
                new NotificationChannel(CH_DONE, "Jawaban selesai", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }

    private void showForeground() {
        Notification n = builder(CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ENC AI")
                .setContentText("ENC AI sedang berpikir...")
                .setOngoing(true)
                .setContentIntent(openAppIntent(null))
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_PROGRESS, n);
        }
    }

    private PendingIntent openAppIntent(String chatId) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (chatId != null) i.putExtra(EXTRA_CHAT_ID, chatId);
        String k = chatId != null ? chatId : "app";
        return PendingIntent.getActivity(this, k.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void notifyDone(String chatId, JSONObject result) {
        boolean ok = result.optBoolean("ok");
        String preview = ok ? extractPreview(result.optString("body"))
                : "Terjadi kesalahan, ketuk untuk melihat.";

        Notification n = builder(CH_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(ok ? "Jawaban sudah siap" : "Proses gagal")
                .setContentText(preview)
                .setStyle(new Notification.BigTextStyle().bigText(preview))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(chatId))
                .build();

        getSystemService(NotificationManager.class).notify(chatId.hashCode(), n);
    }

    private String extractPreview(String body) {
        try {
            String c = new JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            c = c.replaceAll("\\s+", " ").trim();
            return c.length() > 120 ? c.substring(0, 120) : c;
        } catch (Exception e) {
            return "Ketuk untuk membuka.";
        }
    }

    // ---------- WakeLock & penutup ----------

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "encai:chat");
        wakeLock.acquire(12 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) { }
        wakeLock = null;
    }

    @SuppressWarnings("deprecation")
    private void stopSelfClean() {
        releaseWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }
}
