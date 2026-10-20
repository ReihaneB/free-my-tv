package io.freemytv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The playlist the TV is for: one M3U (or an Xtream Codes link that answers with an M3U) gives the live channels and
 * the films and series. Parsed once and kept in a file, so the home screen opens instantly and survives a reboot
 * without network.
 *
 * `config.properties`: iptv_url = the full playlist URL (Xtream: http://host:port/get.php?username=…&password=…
 * &type=m3u_plus&output=ts). Empty: Free My TV keeps its TMDB picks.
 */
final class Iptv {
    static final class Item {
        String name = "", logo = "", group = "", url = "";
        boolean live;
    }

    interface Listener { void onIptv(); }

    private static final long EVERY = 12 * 3600_000L;      // a playlist changes slowly
    private static Iptv instance;

    private final Context context;
    private final File file;
    private final List<Item> items = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean loading;
    private volatile long fetched;

    static synchronized Iptv get(Context c) {
        if (instance == null) instance = new Iptv(c.getApplicationContext());
        return instance;
    }

    private Iptv(Context c) {
        context = c;
        file = new File(c.getFilesDir(), "iptv.txt");
        load();
    }

    static boolean configured() {
        return !Config.IPTV_URL.trim().isEmpty();
    }

    static synchronized Iptv peek() { return instance; }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Everything the playlist has, in playlist order. */
    synchronized List<Item> items() { return new ArrayList<>(items); }

    synchronized List<Item> live() { return filter(true); }
    synchronized List<Item> movies() { return filter(false); }

    private List<Item> filter(boolean live) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) if (i.live == live) out.add(i);
        return out;
    }

    /** The groups of the playlist (category names), in the order they appear. */
    synchronized List<String> groups(boolean live) {
        List<String> out = new ArrayList<>();
        for (Item i : items) {
            if (i.live != live || i.group.isEmpty() || out.contains(i.group)) continue;
            out.add(i.group);
        }
        return out;
    }

    void refreshIfStale() {
        if (!configured() || loading) return;
        if (fetched == 0) fetched = file.lastModified();
        if (System.currentTimeMillis() - fetched < EVERY && !items.isEmpty()) return;
        refresh();
    }

    /** Downloads and parses the playlist in the background; the listeners are told when it lands. */
    void refresh() {
        if (!configured() || loading) return;
        loading = true;
        new Thread(() -> {
            List<Item> parsed = new ArrayList<>();
            try {
                String body = download(Config.IPTV_URL);
                parsed = parse(body);
                if (!parsed.isEmpty()) {
                    save(parsed);
                    synchronized (this) {
                        items.clear();
                        items.addAll(parsed);
                        fetched = System.currentTimeMillis();
                    }
                    AppLog.w("playlist: " + parsed.size() + " entrées");
                } else {
                    AppLog.w("playlist vide ou illisible");
                }
            } catch (IOException e) {
                AppLog.w("playlist: " + e.getMessage());
            } finally {
                loading = false;
                if (!parsed.isEmpty()) {
                    for (Listener l : new ArrayList<>(listeners)) l.onIptv();
                }
            }
        }, "iptv").start();
    }

    // MARK: - Parsing

    /**
     * M3U: `#EXTINF:-1 tvg-logo="…" group-title="…",Name` then the stream URL on the next line. Entries under a film
     * or series group are VOD; everything else is a channel.
     */
    static List<Item> parse(String body) {
        List<Item> out = new ArrayList<>();
        Item current = null;
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                current = new Item();
                current.logo = attr(line, "tvg-logo");
                current.group = attr(line, "group-title");
                String name = attr(line, "tvg-name");
                int comma = line.lastIndexOf(',');
                if (comma >= 0) name = line.substring(comma + 1).trim();
                current.name = name.isEmpty() ? attr(line, "tvg-id") : name;
                current.live = !vod(current.group);
            } else if (line.startsWith("#EXTVLCOPT") || line.startsWith("#EXTGRP") || line.startsWith("#KODIPROP")) {
                if (line.startsWith("#EXTGRP") && current != null) {
                    current.group = line.substring(7).trim();
                    current.live = !vod(current.group);
                }
            } else if (!line.startsWith("#")) {
                if (current != null && !current.name.isEmpty()) {
                    current.url = line;
                    out.add(current);
                }
                current = null;
            }
        }
        return out;
    }

    private static boolean vod(String group) {
        String g = group.toLowerCase(Locale.ROOT);
        return g.contains("vod") || g.contains("film") || g.contains("movie") || g.contains("serie") || g.contains("serie")
                || g.contains("série") || g.contains("cinema") || g.contains("cinéma");
    }

    private static String attr(String line, String key) {
        String needle = key + "=\"";
        int at = line.indexOf(needle);
        if (at < 0) return "";
        int start = at + needle.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : "";
    }

    // MARK: - Cache

    private void load() {
        if (!file.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 5) continue;
                Item i = new Item();
                i.live = "1".equals(f[0]);
                i.name = f[1];
                i.group = f[2];
                i.logo = f[3];
                i.url = f[4];
                items.add(i);
            }
            fetched = file.lastModified();
        } catch (IOException e) {
            AppLog.w("playlist en cache: " + e.getMessage());
        }
    }

    private void save(List<Item> parsed) {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (Item i : parsed) {
                w.write((i.live ? "1" : "0") + "\t" + clean(i.name) + "\t" + clean(i.group) + "\t"
                        + clean(i.logo) + "\t" + clean(i.url) + "\n");
            }
        } catch (IOException e) {
            AppLog.w("playlist: " + e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Plain HTTP GET, following one redirect; the playlist can be a few MB. */
    private static String download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        try {
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String to = c.getHeaderField("Location");
                if (to != null) return download(to);
            }
            if (code != 200) throw new IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder(1 << 20);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[16 * 1024];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
