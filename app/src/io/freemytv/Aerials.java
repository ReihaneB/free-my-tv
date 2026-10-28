package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Les paysages de l'écran de veille, comme les vues de l'Apple TV : les fonds d'écran du jour chez Bing (photos de
 * lieux et de paysages, renouvelées chaque jour, sans clé d'API), huit à la fois, gardées sur la télé. La légende est
 * celle que Bing donne avec la photo (« Château de Kunětická hora, Bohême, Tchéquie »).
 *
 * Aucune dépendance à TMDB, au panel ou au réseau au moment de l'affichage : une photo déjà téléchargée reste
 * disponible hors ligne, et sans rien sur le disque l'écran de veille retombe sur son aurore.
 */
final class Aerials {

    interface Listener { void onAerials(); }

    private static final java.util.List<Listener> listeners = new java.util.ArrayList<>();

    static void listen(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    static void forget(Listener l) { listeners.remove(l); }

    private static void notifyAll_() {
        for (Listener l : new java.util.ArrayList<>(listeners)) l.onAerials();
    }

    static final class Photo {
        String url = "", caption = "";
    }

    private static final String LIST = "https://www.bing.com/HPImageArchive.aspx?format=js&n=8&mkt=fr-FR";
    private static final String BASE = "https://www.bing.com";
    private static final long EVERY = 6 * 3600_000L;   // the day's set changes once a day; six hours is plenty
    private static final int KEEP = 12;                // photos kept on disk

    private static Aerials instance;

    static synchronized Aerials get(Context c) {
        if (instance == null) instance = new Aerials(c.getApplicationContext());
        return instance;
    }

    private final File dir, listFile;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile List<Photo> photos = new ArrayList<>();
    private volatile long readAt;
    private boolean fetching;

    private Aerials(Context c) {
        dir = new File(c.getFilesDir(), "aerials");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        listFile = new File(c.getFilesDir(), "aerials.json");
        read();
    }

    /** The photos of the moment; empty when nothing has been fetched yet. */
    List<Photo> photos() {
        return photos;
    }

    /** Asks Bing for the day's set, at most once every six hours, off the UI thread. */
    void refresh() {
        if (fetching || System.currentTimeMillis() - readAt < EVERY) return;
        fetching = true;
        io.execute(() -> {
            try {
                JSONObject root = new JSONObject(new String(get(LIST), "UTF-8"));
                JSONArray images = root.optJSONArray("images");
                List<Photo> out = new ArrayList<>();
                if (images != null) {
                    for (int i = 0; i < images.length(); i++) {
                        JSONObject o = images.optJSONObject(i);
                        if (o == null) continue;
                        String base = o.optString("urlbase");
                        if (base.isEmpty()) continue;
                        Photo p = new Photo();
                        p.url = BASE + base + "_1920x1080.jpg";
                        p.caption = caption(o.optString("copyright"));
                        out.add(p);
                    }
                }
                if (!out.isEmpty()) {
                    photos = out;
                    write();
                    notifyAll_();
                }
            } catch (Exception e) {
                AppLog.w("aerials: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    /** Downloads the photos that are not on disk yet, a few at a time. */
    void prefetch() {
        io.execute(() -> {
            int done = 0;
            for (Photo p : photos) {
                if (done >= 3) break;
                File f = file(p.url);
                if (f.isFile() && f.length() > 10_000) continue;
                try {
                    byte[] bytes = get(p.url);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(bytes);
                    }
                    done++;
                } catch (Exception e) {
                    AppLog.w("aerials image: " + e.getMessage());
                }
            }
            prune();
            notifyAll_();
        });
    }

    /** The photo already on disk, or null (the screensaver then tries the next one). */
    File readyImage(String url) {
        File f = file(url);
        return f.isFile() && f.length() > 10_000 ? f : null;
    }

    // MARK: - Disk

    private File file(String url) {
        long h = 1125899906842597L;
        for (int i = 0; i < url.length(); i++) h = 31 * h + url.charAt(i);
        return new File(dir, Long.toHexString(h) + ".jpg");
    }

    private void read() {
        try {
            if (!listFile.isFile()) return;
            String text = new String(java.nio.file.Files.readAllBytes(listFile.toPath()), "UTF-8");
            JSONObject root = new JSONObject(text);
            JSONArray a = root.optJSONArray("photos");
            List<Photo> out = new ArrayList<>();
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    Photo p = new Photo();
                    p.url = o.optString("url");
                    p.caption = o.optString("caption");
                    if (!p.url.isEmpty()) out.add(p);
                }
            }
            photos = out;
            readAt = root.optLong("at", 0);
        } catch (Exception e) {
            AppLog.w("aerials read: " + e.getMessage());
        }
    }

    private void write() {
        try {
            JSONArray a = new JSONArray();
            for (Photo p : photos) a.put(new JSONObject().put("url", p.url).put("caption", p.caption));
            try (FileOutputStream out = new FileOutputStream(listFile)) {
                out.write(new JSONObject().put("photos", a).put("at", System.currentTimeMillis())
                        .toString().getBytes("UTF-8"));
            }
            readAt = System.currentTimeMillis();
        } catch (Exception e) {
            AppLog.w("aerials write: " + e.getMessage());
        }
    }

    /** Twelve photos at most on disk: past that, the oldest go. */
    private void prune() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - KEEP; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - Network

    /** « Château de Kunětická hora, Bohême, Tchéquie (© Marek Saroch/Alamy) » → the part before the parenthesis. */
    private static String caption(String copyright) {
        if (copyright == null) return "";
        int cut = copyright.indexOf('(');
        String s = (cut > 0 ? copyright.substring(0, cut) : copyright).trim();
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) FreeMyTV");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
