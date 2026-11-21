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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatService extends Service {

    public static final String ACTION_DONE = "com.dockgo.encai.CHAT_DONE";
    public static final String EXTRA_ID = "req_id";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_KEY = "key";
    private static final String EXTRA_HERMES = "hermes";
    private static final String EXTRA_PAYLOAD = "payload_path";

    private static final String CH_PROGRESS = "encai_progress";
    private static final String CH_DONE = "encai_done";
    private static final int NOTIF_PROGRESS = 1;

    /** Id request yang sedang berjalan (kosong lagi bila proses app mati). */
    public static final Set<String> ACTIVE = Collections.synchronizedSet(new HashSet<String>());
    /** Diisi MainActivity: true saat app tampil (notifikasi selesai tidak perlu muncul). */
    public static volatile boolean appVisible = false;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private PowerManager.WakeLock wakeLock;

    // ---------- API statis ----------

    public static File resultFile(Context c, String id) {
        return new File(c.getFilesDir(), "result_" + id + ".json");
    }

    public static File payloadFile(Context c, String id) {
        return new File(c.getCacheDir(), "payload_" + id + ".json");
    }

    public static void start(Context c, String id, String url, String key, boolean hermes, String payloadPath) {
        ACTIVE.add(id);
        Intent i = new Intent(c, ChatService.class)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_HERMES, hermes)
                .putExtra(EXTRA_PAYLOAD, payloadPath);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void writeError(Context c, String id, String message) {
        try {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", message);
            writeFile(resultFile(c, id), r.toString());
        } catch (Exception ignored) { }
    }

    private static void writeFile(File f, String text) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
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

        final String id = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        final String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        final String key = intent == null ? "" : intent.getStringExtra(EXTRA_KEY);
        final boolean hermes = intent != null && intent.getBooleanExtra(EXTRA_HERMES, false);
        final String payloadPath = intent == null ? null : intent.getStringExtra(EXTRA_PAYLOAD);

        if (id == null || url == null || payloadPath == null) {
            if (ACTIVE.isEmpty()) stopSelfClean();
            return START_NOT_STICKY;
        }

        acquireWakeLock();

        pool.execute(() -> {
            try {
                JSONObject result;
                try {
                    result = run(url, key == null ? "" : key, hermes, payloadPath);
                } catch (Exception e) {
                    result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    } catch (Exception ignored) { }
                }
                try {
                    writeFile(resultFile(this, id), result.toString());
                } catch (Exception ignored) { }

                ACTIVE.remove(id);
                sendBroadcast(new Intent(ACTION_DONE).setPackage(getPackageName()).putExtra(EXTRA_ID, id));
                if (!appVisible) notifyDone(result);
            } finally {
                ACTIVE.remove(id);
                if (ACTIVE.isEmpty()) stopSelfClean();
            }
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

    private JSONObject run(String urlStr, String key, boolean hermes, String payloadPath) throws Exception {
        byte[] body = readBytes(new File(payloadPath));

        HttpURLConnection c = DohNet.open(urlStr);
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(hermes ? 90000 : 1200000); // Hermes: keep-alive SSE tiap 10 dtk
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }

            int code = c.getResponseCode();
            JSONObject out = new JSONObject();
            JSONArray files = new JSONArray();

            if (code >= 200 && code < 300 && hermes) {
                StringBuilder acc = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String d = line.substring(5).trim();
                        if (d.isEmpty()) continue;
                        if (d.equals("[DONE]")) break;
                        try {
                            JSONArray ch = new JSONObject(d).optJSONArray("choices");
                            if (ch == null || ch.length() == 0) continue;
                            JSONObject delta = ch.getJSONObject(0).optJSONObject("delta");
                            if (delta == null) continue;
                            if (!delta.isNull("content")) acc.append(delta.optString("content"));
                            JSONArray fs = delta.optJSONArray("files");
                            if (fs != null) {
                                for (int k = 0; k < fs.length(); k++) {
                                    JSONObject f = fs.optJSONObject(k);
                                    if (f != null && !f.optString("data", "").isEmpty()) files.put(f);
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                }
                String text = acc.toString();
                if (text.startsWith("[Error:")) throw new Exception(text);
                out.put("ok", true);
                out.put("text", text);
                out.put("files", files);
                return out;
            }

            InputStream stream = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String response = new String(readStream(stream), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + response);

            JSONObject json = new JSONObject(response);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new Exception("Respons API tidak berisi choices: " + response);
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new Exception("Respons API tidak berisi message.");
            Object content = message.opt("content");
            JSONArray mf = message.optJSONArray("files");
            if (mf != null) files = mf;

            out.put("ok", true);
            out.put("text", content == null ? "" : content.toString());
            out.put("files", files);
            return out;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readBytes(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in);
        }
    }

    private static byte[] readStream(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
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

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void showForeground() {
        Notification n = builder(CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ENC AI")
                .setContentText("ENC AI sedang berpikir...")
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_PROGRESS, n);
        }
    }

    private void notifyDone(JSONObject result) {
        boolean ok = result.optBoolean("ok");
        String preview;
        if (ok) {
            preview = result.optString("text", "").replaceAll("\\s+", " ").trim();
            if (preview.length() > 120) preview = preview.substring(0, 120);
            if (preview.isEmpty()) preview = "Ketuk untuk membuka.";
        } else {
            preview = "Terjadi kesalahan, ketuk untuk melihat.";
        }
        Notification n = builder(CH_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(ok ? "Jawaban sudah siap" : "Proses gagal")
                .setContentText(preview)
                .setStyle(new Notification.BigTextStyle().bigText(preview))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        getSystemService(NotificationManager.class).notify(2, n);
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
