package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the TV is for: the live channels, the films and the series of one IPTV account.
 *
 * Two ways in, because providers offer both:
 *  - Xtream Codes (iptv_host + iptv_user + iptv_pass): typed categories from player_api.php, which is what the home
 *    screen uses. 8 000 channels as one 60 MB M3U is not something to hold in memory.
 *  - A plain M3U (iptv_url) for providers that only give the playlist.
 *
 * Everything is written under filesDir/iptv, so a reboot opens the home screen instantly and offline.
 */
final class Iptv {
    static final class Item {
        String name = "", logo = "", group = "", url = "";
        boolean live;
    }

    static final class Category {
        String id = "", name = "";
        Category(String id, String name) { this.id = id; this.name = name; }
    }

    interface Listener { void onIptv(); }

    private static final long EVERY = 12 * 3600_000L;       // a catalogue changes slowly
    private static Iptv instance;

    private final File dir;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, List<Item>> items = new HashMap<>();
    private final Map<String, List<Category>> categories = new HashMap<>();
    private volatile boolean loading;
    private volatile long fetched;
    private volatile String error = "";

    static synchronized Iptv get(Context c) {
        if (instance == null) instance = new Iptv(c.getApplicationContext());
        return instance;
    }

    private Iptv(Context c) {
        dir = new File(c.getFilesDir(), "iptv");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        fetched = new File(dir, "stamp").lastModified();
    }

    /** An Xtream account, or failing that a playlist URL. */
    static boolean configured() { return xtream() || !Config.IPTV_URL.trim().isEmpty(); }

    /** Host + login: the Xtream Codes API, category by category. */
    static boolean xtream() { return !Config.IPTV_HOST.trim().isEmpty() && !Config.IPTV_USER.trim().isEmpty(); }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    String error() { return error; }

    // MARK: - What the home screen asks for

    List<Item> live() { return load("live"); }

    List<Item> movies() { return load("vod"); }

    List<Item> series() { return load("series"); }

    List<Category> liveCategories() { return cats("live"); }

    List<Category> movieCategories() { return cats("vod"); }

    List<Category> seriesCategories() { return cats("series"); }

    private synchronized List<Item> load(String type) {
        List<Item> have = items.get(type);
        if (have != null) return have;
        have = read(type);
        items.put(type, have);
        return have;
    }

    private synchronized List<Category> cats(String type) {
        List<Category> have = categories.get(type);
        if (have != null) return have;
        have = readCats(type);
        categories.put(type, have);
        return have;
    }

    /** The channels of one category (the home screen shows the first ones). */
    List<Item> inCategory(String type, String categoryId) {
        List<Item> out = new ArrayList<>();
        for (Item i : load(type)) if (categoryId.equals(i.group)) out.add(i);
        return out;
    }

    void refreshIfStale() {
        if (!configured() || loading) return;
        if (System.currentTimeMillis() - fetched < EVERY && !load("live").isEmpty()) return;
        refresh();
    }

    /** Fetches the catalogue in the background (categories, then the streams) and tells the listeners when it lands. */
    void refresh() {
        if (!configured() || loading) return;
        loading = true;
        new Thread(() -> {
            boolean got = false;
            try {
                got = xtream() ? refreshXtream() : refreshM3u();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("iptv: " + error);
            } finally {
                loading = false;
                if (got) {
                    fetched = System.currentTimeMillis();
                    try { new FileOutputStream(new File(dir, "stamp")).close(); } catch (IOException ignored) { }
                    synchronized (this) {
                        items.clear();
                        categories.clear();
                    }
                    List<Item> l = load("live"), m = load("vod"), s = load("series");
                    AppLog.w("iptv: " + l.size() + " chaînes, " + m.size() + " films, " + s.size() + " séries");
                    for (Listener x : new ArrayList<>(listeners)) x.onIptv();
                }
            }
        }, "iptv").start();
    }

    // MARK: - Xtream Codes

    private boolean refreshXtream() throws Exception {
        JSONObject who = new JSONObject(download(api(""))).optJSONObject("user_info");
        if (who == null) { error = "identifiants refusés"; return false; }
        String status = who.optString("status");
        if (!status.isEmpty() && !"Active".equalsIgnoreCase(status)) error = "compte " + status.toLowerCase(Locale.ROOT);

        for (String type : new String[]{"live", "vod", "series"}) {
            List<Category> list = new ArrayList<>();
            JSONArray cat = new JSONArray(download(api("&action=get_" + type + "_categories")));
            for (int i = 0; i < cat.length(); i++) {
                JSONObject o = cat.getJSONObject(i);
                list.add(new Category(o.optString("category_id"), o.optString("category_name").trim()));
            }
            writeCats(type, list);
        }

        writeItems("live", parseStreams(new JSONArray(download(api("&action=get_live_streams"))), "live"));
        // Films and series: category by category, so a 14 MB answer never has to be held at once on a TV.
        delete(itemsFile("vod"));
        for (Category c : cats("vod")) {
            appendItems("vod", parseStreams(new JSONArray(download(
                    api("&action=get_vod_streams&category_id=" + enc(c.id)))), "vod"));
        }
        delete(itemsFile("series"));
        for (Category c : cats("series")) {
            appendItems("series", parseSeries(new JSONArray(download(
                    api("&action=get_series&category_id=" + enc(c.id))))));
        }
        return true;
    }

    private String api(String action) {
        return Config.IPTV_HOST.trim() + "/player_api.php?username=" + enc(Config.IPTV_USER.trim())
                + "&password=" + enc(Config.IPTV_PASS.trim()) + action;
    }

    /** The playable URL Xtream answers for one id: /live|movie|series/user/pass/id.ext */
    private static String streamUrl(String type, String id, String ext) {
        String host = Config.IPTV_HOST.trim();
        String path = "live".equals(type) ? "live" : "vod".equals(type) ? "movie" : "series";
        String tail = ext == null || ext.isEmpty() ? ("live".equals(type) ? "ts" : "mp4") : ext;
        return host + "/" + path + "/" + enc(Config.IPTV_USER.trim()) + "/" + enc(Config.IPTV_PASS.trim())
                + "/" + id + "." + tail;
    }

    private static List<Item> parseStreams(JSONArray array, String type) {
        List<Item> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.name = o.optString("name").trim();
            it.logo = o.optString("stream_icon", "");
            it.group = o.optString("category_id");
            it.live = "live".equals(type);
            String ext = "live".equals(type) ? "ts" : o.optString("container_extension", "mp4");
            it.url = streamUrl(type, o.optString("stream_id"), ext);
            if (!it.name.isEmpty()) out.add(it);
        }
        return out;
    }

    /** A series has no single stream: the season/episode screen picks one (get_series_info). */
    private static List<Item> parseSeries(JSONArray array) {
        List<Item> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.name = o.optString("name").trim();
            it.logo = o.optString("cover", "");
            it.group = o.optString("category_id");
            it.url = "";
            if (!it.name.isEmpty()) out.add(it);
        }
        return out;
    }

    // MARK: - M3U

    private boolean refreshM3u() throws IOException {
        List<Item> parsed = M3u.parse(download(Config.IPTV_URL));
        if (parsed.isEmpty()) return false;
        List<Item> live = new ArrayList<>(), vod = new ArrayList<>();
        for (Item i : parsed) (i.live ? live : vod).add(i);
        writeItems("live", live);
        writeItems("vod", vod);
        writeItems("series", new ArrayList<>());
        return true;
    }

    // MARK: - Files

    private File itemsFile(String type) { return new File(dir, "items-" + type + ".tsv"); }

    private File catsFile(String type) { return new File(dir, "cats-" + type + ".tsv"); }

    private List<Item> read(String type) {
        List<Item> out = new ArrayList<>();
        File f = itemsFile(type);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.split("\t", -1);
                if (c.length < 5) continue;
                Item i = new Item();
                i.live = "1".equals(c[0]);
                i.group = c[1];
                i.name = c[2];
                i.logo = c[3];
                i.url = c[4];
                out.add(i);
            }
        } catch (IOException e) {
            AppLog.w("iptv cache: " + e.getMessage());
        }
        return out;
    }

    private void writeItems(String type, List<Item> list) {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(itemsFile(type)), StandardCharsets.UTF_8)) {
            for (Item i : list) {
                w.write((i.live ? "1" : "0") + "\t" + clean(i.group) + "\t" + clean(i.name) + "\t"
                        + clean(i.logo) + "\t" + clean(i.url) + "\n");
            }
        } catch (IOException e) {
            AppLog.w("iptv cache: " + e.getMessage());
        }
    }

    private void appendItems(String type, List<Item> list) {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(itemsFile(type), true), StandardCharsets.UTF_8)) {
            for (Item i : list) {
                w.write((i.live ? "1" : "0") + "\t" + clean(i.group) + "\t" + clean(i.name) + "\t"
                        + clean(i.logo) + "\t" + clean(i.url) + "\n");
            }
        } catch (IOException e) {
            AppLog.w("iptv cache: " + e.getMessage());
        }
    }

    private static void delete(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private List<Category> readCats(String type) {
        List<Category> out = new ArrayList<>();
        File f = catsFile(type);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.split("\t", -1);
                if (c.length >= 2) out.add(new Category(c[0], c[1]));
            }
        } catch (IOException e) {
            AppLog.w("iptv cache: " + e.getMessage());
        }
        return out;
    }

    private void writeCats(String type, List<Category> list) {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(catsFile(type)), StandardCharsets.UTF_8)) {
            for (Category c : list) w.write(clean(c.id) + "\t" + clean(c.name) + "\n");
        } catch (IOException e) {
            AppLog.w("iptv cache: " + e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(120_000);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        try {
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String to = c.getHeaderField("Location");
                if (to != null) return download(to);
            }
            if (code != 200) throw new IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder(1 << 20);
            try (InputStream in = c.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
                char[] buf = new char[16 * 1024];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** M3U: `#EXTINF:-1 tvg-logo="…" group-title="…",Name` then the stream URL on the next line. */
    static final class M3u {
        private M3u() { }

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
                    int comma = line.lastIndexOf(',');
                    String name = comma >= 0 ? line.substring(comma + 1).trim() : attr(line, "tvg-name");
                    current.name = name;
                    current.live = !vod(current.group);
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
            return g.contains("vod") || g.contains("film") || g.contains("movie") || g.contains("serie")
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
    }
}
