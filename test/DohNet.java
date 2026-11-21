package com.dockgo.encai;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Koneksi HTTPS yang tidak bergantung pada DNS bawaan jaringan.
 *
 * Nama host (mis. *.workers.dev) di-resolve lewat DNS-over-HTTPS (Cloudflare/Google).
 * Koneksi TCP dibuat ke IP hasilnya, lalu TLS dijalankan dengan SNI dan verifikasi
 * sertifikat memakai nama host asli. Seluruh HTTP/1.1 ditangani sendiri di sini, bukan
 * oleh HttpURLConnection bawaan Android, karena yang bawaan mengirim SNI = IP dan gagal.
 *
 * Urutan percobaan: (1) IP dari DoH, (2) DNS sistem. Jadi bila DoH gagal, perilakunya
 * sama seperti koneksi biasa.
 *
 * Pemakaian: ganti url.openConnection() dengan DohNet.open(urlString).
 */
public final class DohNet {
    private DohNet() {}

    /** Titik masuk. Selain HTTPS ke nama host publik, dipakai koneksi bawaan. */
    public static HttpURLConnection open(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        if ("https".equalsIgnoreCase(url.getProtocol()) && needsDoh(url.getHost())) {
            return new RawConnection(url);
        }
        return (HttpURLConnection) url.openConnection();
    }

    // ------------------------------------------------------------------ DoH

    interface Resolver { String resolve(String host); }

    // Hook untuk pengujian; di aplikasi biarkan default.
    static volatile Resolver resolver = DohNet::resolveViaDoh;
    static volatile HostnameVerifier verifier = null;
    static volatile SSLSocketFactory socketFactory = null;

    private static final String[] DOH_ENDPOINTS = {
            "https://1.1.1.1/dns-query?name=",
            "https://8.8.8.8/resolve?name=",
            "https://1.0.0.1/dns-query?name="
    };
    private static final long CACHE_MS = 5 * 60 * 1000L;
    private static final long NEG_MS = 60 * 1000L;
    private static final Pattern A_RECORD =
            Pattern.compile("\"data\"\\s*:\\s*\"(\\d{1,3}(?:\\.\\d{1,3}){3})\"");

    private static final class Entry {
        final String ip;      // null = DoH gagal (cache negatif)
        final long expires;
        Entry(String ip, long expires) { this.ip = ip; this.expires = expires; }
    }

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    private static boolean needsDoh(String host) {
        if (host == null || host.isEmpty()) return false;
        if ("localhost".equalsIgnoreCase(host)) return false;
        if (host.indexOf('.') < 0 || host.indexOf(':') >= 0) return false;
        return !host.matches("^[0-9.]+$"); // IP literal tidak perlu di-resolve
    }

    static String resolveViaDoh(String host) {
        long now = System.currentTimeMillis();
        Entry e = CACHE.get(host);
        if (e != null && e.expires > now) return e.ip;
        for (String endpoint : DOH_ENDPOINTS) {
            String ip = queryDoh(endpoint + host + "&type=A");
            if (ip != null) {
                CACHE.put(host, new Entry(ip, now + CACHE_MS));
                return ip;
            }
        }
        CACHE.put(host, new Entry(null, now + NEG_MS));
        return null;
    }

    private static String queryDoh(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("Accept", "application/dns-json");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            Matcher m = A_RECORD.matcher(new String(out.toByteArray(), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (Exception ignored) {
            return null; // coba server DoH berikutnya
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ----------------------------------------------------- HTTP/1.1 manual

    static final class RawConnection extends HttpURLConnection {
        private final String host;
        private final int port;
        private final boolean tls;
        private final String target;
        private final Map<String, String[]> reqHeaders = new LinkedHashMap<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, List<String>> respHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        private Socket socket;
        private BufferedInputStream rawIn;
        private int status = -1;
        private String message = "";
        private InputStream bodyStream;
        private String statusLine = "";

        RawConnection(URL url) {
            super(url);
            this.host = url.getHost();
            this.tls = "https".equalsIgnoreCase(url.getProtocol());
            this.port = url.getPort() == -1 ? (tls ? 443 : 80) : url.getPort();
            String f = url.getFile();
            this.target = (f == null || f.isEmpty()) ? "/" : f;
        }

        // ---- request header

        @Override
        public void setRequestProperty(String key, String value) {
            if (key == null) throw new NullPointerException("key == null");
            reqHeaders.put(key.toLowerCase(Locale.ROOT), new String[]{key, value == null ? "" : value});
        }

        @Override
        public void addRequestProperty(String key, String value) {
            if (key == null) throw new NullPointerException("key == null");
            String lk = key.toLowerCase(Locale.ROOT);
            String[] old = reqHeaders.get(lk);
            String v = value == null ? "" : value;
            reqHeaders.put(lk, new String[]{key, old == null ? v : old[1] + ", " + v});
        }

        @Override
        public String getRequestProperty(String key) {
            if (key == null) return null;
            String[] kv = reqHeaders.get(key.toLowerCase(Locale.ROOT));
            return kv == null ? null : kv[1];
        }

        @Override
        public Map<String, List<String>> getRequestProperties() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (String[] kv : reqHeaders.values()) out.put(kv[0], Collections.singletonList(kv[1]));
            return Collections.unmodifiableMap(out);
        }

        // ---- koneksi

        @Override
        public void connect() throws IOException {
            if (socket != null) return;
            int ct = getConnectTimeout() > 0 ? getConnectTimeout() : 15000;

            List<String> targets = new ArrayList<>();
            String ip = null;
            if (tls) {
                try { ip = resolver.resolve(host); } catch (Throwable ignored) { }
            }
            if (ip != null) targets.add(ip);
            targets.add(null); // null = DNS sistem

            IOException last = null;
            for (String t : targets) {
                Socket s = null;
                try {
                    s = new Socket();
                    InetSocketAddress addr = t != null
                            ? new InetSocketAddress(InetAddress.getByName(t), port)
                            : new InetSocketAddress(host, port);
                    if (addr.isUnresolved()) throw new UnknownHostException(host);
                    s.connect(addr, ct);
                    s.setSoTimeout(ct);
                    if (tls) s = handshake(s);
                    s.setSoTimeout(Math.max(0, getReadTimeout()));
                    socket = s;
                    rawIn = new BufferedInputStream(s.getInputStream(), 8192);
                    connected = true;
                    return;
                } catch (IOException e) {
                    last = e;
                    closeQuietly(s);
                    if (t != null) CACHE.remove(host); // IP dari DoH tidak bekerja: resolve ulang nanti
                }
            }
            throw last != null ? last : new IOException("Tidak bisa terhubung ke " + host);
        }

        private Socket handshake(Socket plain) throws IOException {
            SSLSocketFactory f = socketFactory != null
                    ? socketFactory : (SSLSocketFactory) SSLSocketFactory.getDefault();
            // Nama host asli diberikan di sini supaya SNI = host walau soket tersambung ke IP.
            SSLSocket ss = (SSLSocket) f.createSocket(plain, host, port, true);
            try {
                ss.setUseClientMode(true);
                ss.startHandshake();
                HostnameVerifier v = verifier != null
                        ? verifier : HttpsURLConnection.getDefaultHostnameVerifier();
                if (!v.verify(host, ss.getSession())) {
                    throw new SSLPeerUnverifiedException("Hostname " + host + " tidak cocok dengan sertifikat");
                }
            } catch (IOException e) {
                closeQuietly(ss);
                throw e;
            }
            return ss;
        }

        private static void closeQuietly(Socket s) {
            if (s == null) return;
            try { s.close(); } catch (Exception ignored) { }
        }

        @Override
        public void disconnect() {
            closeQuietly(socket);
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        // ---- body request

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (!doOutput) throw new ProtocolException("setDoOutput(true) belum dipanggil");
            if (status != -1) throw new ProtocolException("Permintaan sudah terkirim");
            return body;
        }

        private String hostHeader() {
            int def = tls ? 443 : 80;
            return port == def ? host : host + ":" + port;
        }

        private static String clean(String v) {
            return v.replace('\r', ' ').replace('\n', ' ');
        }

        private void sendRequest() throws IOException {
            byte[] payload = body.toByteArray();
            boolean hasBody = doOutput || payload.length > 0;

            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(hostHeader()).append("\r\n");
            sb.append("Connection: close\r\n");
            sb.append("Accept-Encoding: identity\r\n");
            if (!reqHeaders.containsKey("user-agent")) sb.append("User-Agent: Dalvik/2.1.0 (Linux; U; Android; ENC AI)\r\n");
            if (!reqHeaders.containsKey("accept")) sb.append("Accept: */*\r\n");
            for (String[] kv : reqHeaders.values()) {
                String lk = kv[0].toLowerCase(Locale.ROOT);
                if (lk.equals("host") || lk.equals("connection") || lk.equals("content-length")
                        || lk.equals("accept-encoding") || lk.equals("transfer-encoding")) continue;
                sb.append(clean(kv[0])).append(": ").append(clean(kv[1])).append("\r\n");
            }
            if (hasBody) sb.append("Content-Length: ").append(payload.length).append("\r\n");
            sb.append("\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (payload.length > 0) os.write(payload);
            os.flush();
        }

        // ---- respons

        private void ensureResponse() throws IOException {
            if (status != -1) return;
            connect();
            sendRequest();
            readHead();
        }

        private void readHead() throws IOException {
            while (true) {
                String line = readLine(rawIn);
                if (line == null) throw new IOException("Server menutup koneksi tanpa respons");
                int sp1 = line.indexOf(' ');
                if (!line.startsWith("HTTP/") || sp1 < 0) {
                    throw new IOException("Respons tidak valid: " + (line.length() > 80 ? line.substring(0, 80) : line));
                }
                int sp2 = line.indexOf(' ', sp1 + 1);
                String code = sp2 < 0 ? line.substring(sp1 + 1) : line.substring(sp1 + 1, sp2);
                int parsed;
                try {
                    parsed = Integer.parseInt(code.trim());
                } catch (NumberFormatException e) {
                    throw new IOException("Kode status tidak valid: " + code);
                }
                statusLine = line;
                message = sp2 < 0 ? "" : line.substring(sp2 + 1);
                respHeaders.clear();
                String h;
                while ((h = readLine(rawIn)) != null && !h.isEmpty()) {
                    int c = h.indexOf(':');
                    if (c <= 0) continue;
                    String k = h.substring(0, c).trim();
                    String v = h.substring(c + 1).trim();
                    List<String> list = respHeaders.get(k);
                    if (list == null) {
                        list = new ArrayList<>();
                        respHeaders.put(k, list);
                    }
                    list.add(v);
                }
                if (parsed == 100) continue; // 100 Continue: baca respons sebenarnya
                status = parsed;
                break;
            }

            String te = first("transfer-encoding");
            String cl = first("content-length");
            InputStream raw;
            if ("HEAD".equals(method) || status == 204 || status == 304 || (status >= 100 && status < 200)) {
                raw = new LimitedStream(rawIn, 0);
            } else if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
                raw = new ChunkedStream(rawIn);
            } else if (cl != null) {
                long n;
                try {
                    n = Long.parseLong(cl.trim());
                } catch (NumberFormatException e) {
                    throw new IOException("Content-Length tidak valid: " + cl);
                }
                raw = new LimitedStream(rawIn, n);
            } else {
                raw = rawIn; // sampai koneksi ditutup
            }
            bodyStream = new SocketGuard(raw, this);
        }

        private String first(String name) {
            List<String> l = respHeaders.get(name);
            return l == null || l.isEmpty() ? null : l.get(0);
        }

        @Override
        public int getResponseCode() throws IOException {
            ensureResponse();
            return status;
        }

        @Override
        public String getResponseMessage() throws IOException {
            ensureResponse();
            return message;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            ensureResponse();
            if (status >= 400) throw new IOException("Server returned HTTP response code: " + status);
            return bodyStream;
        }

        @Override
        public InputStream getErrorStream() {
            return status >= 400 ? bodyStream : null;
        }

        @Override
        public String getHeaderField(String name) {
            try { ensureResponse(); } catch (IOException e) { return null; }
            if (name == null) return statusLine;
            List<String> l = respHeaders.get(name);
            return l == null || l.isEmpty() ? null : l.get(l.size() - 1);
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            try { ensureResponse(); } catch (IOException e) { return Collections.emptyMap(); }
            return Collections.unmodifiableMap(respHeaders);
        }

        @Override
        public String getHeaderField(int n) {
            try { ensureResponse(); } catch (IOException e) { return null; }
            if (n == 0) return statusLine;
            int i = 1;
            for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                for (String v : e.getValue()) {
                    if (i++ == n) return v;
                }
            }
            return null;
        }

        @Override
        public String getHeaderFieldKey(int n) {
            try { ensureResponse(); } catch (IOException e) { return null; }
            if (n == 0) return null;
            int i = 1;
            for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                for (int k = 0; k < e.getValue().size(); k++) {
                    if (i++ == n) return e.getKey();
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------- aliran respons

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 65536) throw new IOException("Baris header terlalu panjang");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Menutup soket saat aliran respons ditutup. */
    private static final class SocketGuard extends FilterInputStream {
        private final HttpURLConnection owner;
        SocketGuard(InputStream in, HttpURLConnection owner) {
            super(in);
            this.owner = owner;
        }
        @Override
        public void close() throws IOException {
            try { owner.disconnect(); } finally { /* soket sudah ditutup */ }
        }
    }

    private static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long remaining;
        LimitedStream(InputStream in, long len) { this.in = in; this.remaining = len; }
        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b < 0) throw new IOException("Respons terpotong");
            remaining--;
            return b;
        }
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            if (len == 0) return 0;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) throw new IOException("Respons terpotong");
            remaining -= n;
            return n;
        }
    }

    private static final class ChunkedStream extends InputStream {
        private final InputStream in;
        private long remaining = 0;
        private boolean eof = false;
        ChunkedStream(InputStream in) { this.in = in; }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (len == 0) return 0;
            if (remaining == 0 && !nextChunk()) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) throw new IOException("Respons terpotong");
            remaining -= n;
            if (remaining == 0) readLine(in); // CRLF penutup chunk
            return n;
        }

        private boolean nextChunk() throws IOException {
            String line = readLine(in);
            if (line != null && line.trim().isEmpty()) line = readLine(in);
            if (line == null) throw new IOException("Respons terpotong");
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            long size;
            try {
                size = Long.parseLong(line.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("Ukuran chunk tidak valid: " + line);
            }
            if (size == 0) {
                String t;
                while ((t = readLine(in)) != null && !t.isEmpty()) { /* lewati trailer */ }
                eof = true;
                return false;
            }
            remaining = size;
            return true;
        }
    }
}
