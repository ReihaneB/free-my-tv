package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters and channel logos, fetched once and kept: memory first (a few dozen), then a file per URL, so the grid
 * scrolls without waiting and a reboot costs nothing.
 */
final class Posters {
    interface Done { void onPoster(String url, Bitmap bitmap); }

    private static final int HOLD = 60;                     // in memory
    private static final int MIN_W = 300, MIN_H = 300;     // decoded size floor: panel logos are 65x39 originals
    private static Posters instance;

    private final File dir;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Map<String, Bitmap> memory = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > HOLD; }
    };
    private final java.util.Set<String> pending = new java.util.HashSet<>();

    static synchronized Posters get(android.content.Context c) {
        if (instance == null) instance = new Posters(c.getApplicationContext());
        return instance;
    }

    private Posters(android.content.Context c) {
        dir = new File(c.getCacheDir(), "posters3");   // new decode: old 65x39 files must not be reused
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        trim();
    }

    /**
     * A cap on the pictures kept on disk (300 Mo): past it, the oldest go until it is back under 250 Mo. The home
     * screen walks thousands of posters; without this the cache grows until the TV is full.
     */
    private void trim() {
        final long cap = 300L << 20, keep = 250L << 20;
        io.execute(() -> {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= cap) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                if (total <= keep) break;
                long n = f.length();
                if (f.delete()) total -= n;
            }
            AppLog.w("posters: cache ramené à " + (total >> 20) + " Mo");
        });
    }

    /** The bitmap if it's already here (memory or disk); otherwise null and it's fetched in the background. */
    Bitmap get(String url, Done done) {
        if (url == null || url.isEmpty()) return null;
        synchronized (memory) {
            Bitmap b = memory.get(url);
            if (b != null) return b;
        }
        final File f = file(url);
        if (f.isFile()) {
            // Decoding here would run on the thread that draws: a 400 px JPEG costs 10-40 ms and lands mid-scroll.
            // So the frame goes without it and the callback asks for another frame when it is ready.
            synchronized (pending) {
                if (!pending.add(url)) return null;
            }
            io.execute(() -> {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                synchronized (pending) { pending.remove(url); }
                if (b != null) {
                    synchronized (memory) { memory.put(url, b); }
                    if (done != null) done.onPoster(url, b);
                }
            });
            return null;
        }
        synchronized (pending) {
            if (!pending.add(url)) return null;
        }
        io.execute(() -> {
            Bitmap b = null;
            try {
                b = download(url);
                if (b != null) write(f, b);
            } catch (Exception e) {
                AppLog.w("poster: " + e.getMessage());
            } finally {
                synchronized (pending) { pending.remove(url); }
            }
            if (b != null) {
                synchronized (memory) { memory.put(url, b); }
                if (done != null) done.onPoster(url, b);
            }
        });
        return null;
    }

    // Backdrops: full screen, so decoded at full size (the posters' 300 px floor would make them blurry). A few only.
    private final Map<String, Bitmap> large = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 2; }
    };

    /** A backdrop at full size: here if already decoded, otherwise null and `done` is called when it is. */
    Bitmap large(String url, Done done) {
        if (url == null || url.isEmpty()) return null;
        synchronized (large) {
            Bitmap b = large.get(url);
            if (b != null) return b;
        }
        final String key = "L:" + url;
        synchronized (pending) {
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            Bitmap b;
            try {
                b = largeNow(url);
            } finally {
                synchronized (pending) { pending.remove(key); }
            }
            if (b != null && done != null) done.onPoster(url, b);
        });
        return null;
    }

    /** A backdrop at full size, now: memory, else its file, else the network. Blocking: off the UI thread. */
    Bitmap largeNow(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (large) {
            Bitmap b = large.get(url);
            if (b != null) return b;
        }
        Bitmap b = null;
        File f = file("L:" + url);
        try {
            if (!f.isFile()) save(url, f);
            if (f.isFile()) b = decode(f, 1920);
        } catch (Exception e) {
            AppLog.w("backdrop: " + e.getMessage());
        }
        if (b != null) synchronized (large) { large.put(url, b); }
        return b;
    }

    /** Fetches a backdrop's file ahead of time, without decoding it: the focus will find it on disk. */
    void warm(String url) {
        if (url == null || url.isEmpty()) return;
        final File f = file("L:" + url);
        if (f.isFile()) return;
        final String key = "W:" + url;
        synchronized (pending) {
            if (!pending.add(key)) return;
        }
        io.execute(() -> {
            try {
                if (!f.isFile()) save(url, f);
            } catch (Exception e) {
                AppLog.w("backdrop: " + e.getMessage());
            } finally {
                synchronized (pending) { pending.remove(key); }
            }
        });
    }

    /** A poster or a logo, now: memory, else its file, else the network. Blocking: off the UI thread. */
    Bitmap posterNow(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (memory) {
            Bitmap b = memory.get(url);
            if (b != null) return b;
        }
        File f = file(url);
        Bitmap b = null;
        try {
            if (f.isFile()) {
                b = BitmapFactory.decodeFile(f.getAbsolutePath());
            } else {
                b = download(url);
                if (b != null) write(f, b);
            }
        } catch (Exception e) {
            AppLog.w("poster: " + e.getMessage());
        }
        if (b != null) synchronized (memory) { memory.put(url, b); }
        return b;
    }

    /** The file as the server sent it (no re-encoding: that cost ~100 ms a backdrop), written whole or not at all. */
    private static void save(String url, File f) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof javax.net.ssl.HttpsURLConnection) relax((javax.net.ssl.HttpsURLConnection) c);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        if (c.getResponseCode() != 200) {
            c.disconnect();
            return;
        }
        File tmp = new File(f.getPath() + ".part");
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    /** A file decoded at full size, halved only while wider than 1.5 × `maxW`. */
    private static Bitmap decode(File f, int maxW) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > maxW * 1.5f) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private File file(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", h[i]));
            return new File(dir, sb.toString());
        } catch (Exception e) {
            return new File(dir, "x" + url.hashCode());
        }
    }

    /**
     * Posters live on whatever CDN the panel uses, often with a certificate chain Android doesn't know. Nothing secret
     * is sent for an image, so the certificate is accepted here — and only here.
     */
    private static void relax(javax.net.ssl.HttpsURLConnection c) {
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String auth) { }
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String auth) { }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier((host, session) -> true);
        } catch (Exception e) {
            AppLog.w("poster tls: " + e.getMessage());
        }
    }

    private static Bitmap download(String url) throws IOException {
        return fetch(url, 0);
    }

    /** `maxW` > 0: decoded at full size up to that width (backdrops); 0: down to the posters' floor. */
    private static Bitmap fetch(String url, int maxW) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof javax.net.ssl.HttpsURLConnection) relax((javax.net.ssl.HttpsURLConnection) c);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        try {
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                // Downsample only what is bigger than the screen: a channel logo served at 65x39 must be decoded as
                // is, or it gets blown up blurry in a 230x129 tile.
                byte[] bytes = readAll(in);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                int sample = 1;
                if (maxW > 0) {
                    while (bounds.outWidth / sample > maxW * 1.5f) sample *= 2;
                } else {
                    while (bounds.outWidth / (sample * 2) >= MIN_W && bounds.outHeight / (sample * 2) >= MIN_H) sample *= 2;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            }
            // no disconnect(): the socket is reused for the next picture from the same server
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void write(File f, Bitmap b) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            // A channel logo is a transparent PNG: JPEG would flatten the transparency to black and the tile would
            // show a black rectangle instead of a logo.
            if (b.hasAlpha()) b.compress(Bitmap.CompressFormat.PNG, 100, out);
            else b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException ignored) { }
    }
}
