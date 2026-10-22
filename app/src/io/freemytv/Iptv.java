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
        String name = "", logo = "", group = "", url = "", id = "", year = "";
        long added;             // when the panel added it: "new this week"
        float rating;           // films and series only
        boolean live;
    }

    static final class Category {
        String id = "", name = "";
        Category(String id, String name) { this.id = id; this.name = name; }
    }

    interface Listener { void onIptv(); }

    private static final long EVERY = 12 * 3600_000L;       // a catalogue changes slowly
    private static Iptv instance;

    private final File dir, infoDir;
    private final Context app;
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
        app = c;
        dir = new File(c.getFilesDir(), "iptv");
        infoDir = new File(dir, "info");
        //noinspection ResultOfMethodCallIgnored
        infoDir.mkdirs();
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

    /** Films added in the last `days` (the panel's own "new" shelf), newest first. */
    List<Item> fresh(String type, int days, int max) {
        List<Item> out = new ArrayList<>();
        long since = System.currentTimeMillis() - days * 24 * 3600_000L;
        for (Item i : load(type)) if (i.added >= since) out.add(i);
        out.sort((a, b) -> Long.compare(b.added, a.added));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Best rated first (films and series carry a note). */
    List<Item> best(String type, int max) {
        List<Item> out = new ArrayList<>();
        for (Item i : load(type)) if (i.rating > 0) out.add(i);
        out.sort((a, b) -> Float.compare(b.rating, a.rating));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** One episode of a series, as its page shows it. */
    static final class Episode {
        String id = "", url = "", title = "", plot = "", still = "";
        int season, number;
        long durationMs;
    }

    /** What a film's or a series' page shows; read from the panel when the page opens, kept for the session. */
    static final class Detail {
        String title = "", plot = "", genre = "", director = "", cast = "", year = "", country = "", age = "";
        String trailer = "", backdrop = "", poster = "";
        float rating;
        long durationMs;
        boolean lite;           // only what the lists say (Facts): no seasons, no episodes, no age
        long at;                // when the panel gave it
        final List<Integer> seasons = new ArrayList<>();
        final List<Episode> episodes = new ArrayList<>();
    }

    private final Map<String, Detail> details = new java.util.LinkedHashMap<String, Detail>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Detail> e) { return size() > 120; }
    };

    /** What is already known of a film or a series (no network), or null: the home screen's spotlight asks first. */
    Detail peek(boolean series, String id) {
        synchronized (details) {
            return details.get((series ? "series:" : "vod:") + id);
        }
    }

    /** What the lists said of a film or a series (Facts, on disk, ~1 ms), or null. */
    Detail lite(boolean series, String id) {
        return Facts.get(app).read(series, id);
    }

    /** Facts are written while the catalogue refreshes: this file says the last refresh wrote them. */
    private File factsMark() { return new File(dir, "facts1.ok"); }

    /**
     * What the panel told before, from memory or from disk (any age), or null: never the network. Off the UI thread
     * (a series' answer is parsed). The home screen reads this for the cards around the focus.
     */
    Detail cached(boolean series, String id) {
        String key = (series ? "series:" : "vod:") + id;
        synchronized (details) {
            Detail d = details.get(key);
            if (d != null) return d;
        }
        File f = infoFile(key);
        if (!f.isFile()) return null;
        try {
            Detail d = series ? seriesFrom(readText(f)) : filmFrom(readText(f));
            if (d == null) return null;
            d.at = f.lastModified();
            synchronized (details) {
                Detail had = details.get(key);
                if (had != null) return had;
                details.put(key, d);
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** A film's details (`get_vod_info`), or null when the panel doesn't answer. Network: call off the UI thread. */
    Detail film(String vodId) {
        Detail known = cached(false, vodId);   // a film's facts don't change
        if (known != null) return known;
        String key = "vod:" + vodId;
        try {
            String json = download(api("&action=get_vod_info&vod_id=" + enc(vodId)));
            Detail d = filmFrom(json);
            if (d == null) return null;
            d.at = System.currentTimeMillis();
            keep(key, json);
            synchronized (details) { details.put(key, d); }
            return d;
        } catch (Exception e) {
            AppLog.w("film info: " + e.getMessage());
            return null;
        }
    }

    private static Detail filmFrom(String json) throws Exception {
        JSONObject info = new JSONObject(json).optJSONObject("info");
        if (info == null) return null;
        Detail d = new Detail();
        common(d, info);
        d.durationMs = clockMs(text(info, "duration"));
        if (d.durationMs <= 0) d.durationMs = minutes(text(info, "runtime")) * 60_000L;
        return d;
    }

    /**
     * A series' details, seasons and episodes (`get_series_info`), or null. A series grows: what is older than a few
     * minutes is asked again (and kept if the panel doesn't answer). Network: call off the UI thread.
     */
    Detail series(String seriesId) {
        String key = "series:" + seriesId;
        Detail known;
        synchronized (details) { known = details.get(key); }
        if (known != null && System.currentTimeMillis() - known.at < 20 * 60_000L) return known;
        try {
            String json = download(api("&action=get_series_info&series_id=" + enc(seriesId)));
            Detail d = seriesFrom(json);
            d.at = System.currentTimeMillis();
            keep(key, json);
            synchronized (details) { details.put(key, d); }
            return d;
        } catch (Exception e) {
            AppLog.w("series info: " + e.getMessage());
            return known != null ? known : cached(true, seriesId);
        }
    }

    private static Detail seriesFrom(String json) throws Exception {
        {
            JSONObject root = new JSONObject(json);
            Detail d = new Detail();
            JSONObject info = root.optJSONObject("info");
            if (info != null) {
                common(d, info);
                d.durationMs = minutes(text(info, "episode_run_time")) * 60_000L;
            }
            JSONObject seasons = root.optJSONObject("episodes");
            JSONArray names = seasons == null ? null : seasons.names();
            if (names != null) {
                List<Integer> order = new ArrayList<>();
                for (int s = 0; s < names.length(); s++) {
                    try { order.add(Integer.parseInt(names.getString(s).trim())); } catch (Exception ignored) { }
                }
                java.util.Collections.sort(order);
                for (int season : order) {
                    JSONArray list = seasons.optJSONArray(String.valueOf(season));
                    if (list == null) continue;
                    int before = d.episodes.size();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null) continue;
                        Episode ep = new Episode();
                        ep.id = o.optString("id");
                        if (ep.id.isEmpty()) continue;
                        ep.season = season;
                        try { ep.number = Integer.parseInt(o.optString("episode_num", "").trim()); }
                        catch (Exception e) { ep.number = i + 1; }
                        String title = o.optString("title", "").trim();
                        // Panels often send "Serie - S01E02 - Title": keep only the episode's own title.
                        title = title.replaceFirst("(?i)^.*S\\d+\\s*[Ee]\\d+\\s*[-–·:]?\\s*", "").trim();
                        if (title.matches("(?i)(é|e)pisode\\s*\\d+")) title = "";   // "Épisode 3" says nothing
                        ep.title = title;
                        ep.url = streamUrl("series", ep.id, o.optString("container_extension", "mp4"));
                        JSONObject ei = o.optJSONObject("info");
                        if (ei != null) {
                            ep.plot = text(ei, "plot");
                            ep.still = text(ei, "movie_image");
                            ep.durationMs = clockMs(text(ei, "duration"));
                            if (ep.durationMs <= 0) {
                                long secs = (long) ei.optDouble("duration_secs", 0);
                                ep.durationMs = secs * 1000L;
                            }
                        }
                        d.episodes.add(ep);
                    }
                    java.util.Collections.sort(d.episodes.subList(before, d.episodes.size()),
                            (a, b) -> Integer.compare(a.number, b.number));
                    if (d.episodes.size() > before) d.seasons.add(season);
                }
            }
            return d;
        }
    }

    // The panel's answers on disk: the next start (and the cards around the focus) find them without asking.
    private int kept;

    private File infoFile(String key) { return new File(infoDir, key.replace(':', '-') + ".json"); }

    private void keep(String key, String json) {
        File f = infoFile(key), tmp = new File(infoDir, key.replace(':', '-') + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(json);
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
        if (++kept % 100 == 0) trimInfo();
    }

    /** At most ~3000 answers (~20 MB): the oldest go first. */
    private void trimInfo() {
        File[] all = infoDir.listFiles();
        if (all == null || all.length <= 3000) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < all.length - 2000; i++) delete(all[i]);
    }

    private static String readText(File f) throws IOException {
        StringBuilder sb = new StringBuilder((int) Math.min(1 << 20, f.length() + 16));
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 1 << 14)) {
            char[] buf = new char[8 * 1024];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** The fields films and series share. */
    private static void common(Detail d, JSONObject info) {
        d.title = text(info, "name");
        d.plot = text(info, "plot");
        if (d.plot.isEmpty()) d.plot = text(info, "description");
        d.genre = text(info, "genre");
        d.director = text(info, "director");
        d.cast = text(info, "cast");
        if (d.cast.isEmpty()) d.cast = text(info, "actors");
        d.country = text(info, "country");
        d.age = text(info, "age");
        d.trailer = text(info, "youtube_trailer");
        d.year = year(info);
        d.rating = (float) info.optDouble("rating", 0);
        d.poster = text(info, "cover_big");
        if (d.poster.isEmpty()) d.poster = text(info, "cover");
        if (d.poster.isEmpty()) d.poster = text(info, "movie_image");
        Object b = info.opt("backdrop_path");
        if (b instanceof JSONArray && ((JSONArray) b).length() > 0) d.backdrop = ((JSONArray) b).optString(0, "");
        else if (b instanceof String) d.backdrop = (String) b;
    }

    /** The year of a film or a series, from whichever date field the panel filled. */
    static String year(JSONObject o) {
        String date = text(o, "releasedate");
        if (date.isEmpty()) date = text(o, "releaseDate");
        if (date.isEmpty()) date = text(o, "release_date");
        if (date.length() >= 4 && date.substring(0, 4).matches("\\d{4}")) return date.substring(0, 4);
        String y = text(o, "year");
        return y.matches("\\d{4}") ? y : "";
    }

    /** A JSON text field, with the panels' "null", "0" and "N/A" read as nothing. */
    static String text(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        String s = String.valueOf(v).trim();
        return s.equalsIgnoreCase("null") || s.equalsIgnoreCase("none") || s.equals("N/A") || s.equals("0") ? "" : s;
    }

    /** "01:35:18" → ms. */
    private static long clockMs(String v) {
        if (v == null || !v.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) return 0;
        String[] p = v.split(":");
        long h = p.length == 3 ? Long.parseLong(p[0]) : 0, m = Long.parseLong(p[p.length == 3 ? 1 : 0]);
        long sec = Long.parseLong(p[p.length - 1]);
        return ((h * 60 + m) * 60 + sec) * 1000L;
    }

    private static long minutes(String v) {
        try { return Math.max(0, Math.round(Double.parseDouble(v.trim()))); } catch (Exception e) { return 0; }
    }


    void refreshIfStale() {
        if (!configured() || loading) return;
        if (System.currentTimeMillis() - fetched < EVERY && !load("live").isEmpty() && factsMark().isFile()) return;
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
                    if (xtream()) try { new FileOutputStream(factsMark()).close(); } catch (IOException ignored) { }
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
        // What the lists say of each title (story, genres, a series' backdrop) goes to Facts on the way.
        Facts facts = Facts.get(app);
        delete(itemsFile("vod"));
        for (Category c : cats("vod")) {
            JSONArray list = new JSONArray(download(api("&action=get_vod_streams&category_id=" + enc(c.id))));
            appendItems("vod", parseStreams(list, "vod"));
            facts.write(false, list);
        }
        delete(itemsFile("series"));
        for (Category c : cats("series")) {
            JSONArray list = new JSONArray(download(api("&action=get_series&category_id=" + enc(c.id))));
            appendItems("series", parseSeries(list));
            facts.write(true, list);
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
            it.id = o.optString("stream_id");
            it.url = streamUrl(type, it.id, ext);
            it.added = epoch(o.optString("added"));
            it.year = o.optString("year", "");
            it.rating = (float) o.optDouble("rating", 0);
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
            it.id = o.optString("series_id");
            it.rating = (float) o.optDouble("rating", 0);
            it.added = epoch(o.optString("last_modified", o.optString("added")));
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

    private File itemsFile(String type) { return new File(dir, "items2-" + type + ".tsv"); }

    private File catsFile(String type) { return new File(dir, "cats-" + type + ".tsv"); }

    private List<Item> read(String type) {
        List<Item> out = new ArrayList<>();
        File f = itemsFile(type);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.split("\t", -1);
                if (c.length < 8) continue;
                Item i = new Item();
                i.live = "1".equals(c[0]);
                i.group = c[1];
                i.name = c[2];
                i.logo = c[3];
                i.url = c[4];
                i.id = c[5];
                i.added = epoch(c[6]);
                try { i.rating = Float.parseFloat(c[7]); } catch (Exception ignored) { }
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
                w.write(line(i));
            }
        } catch (IOException e) {
            AppLog.w("iptv cache: " + e.getMessage());
        }
    }

    private static String line(Item i) {
        return (i.live ? "1" : "0") + "\t" + clean(i.group) + "\t" + clean(i.name) + "\t" + clean(i.logo) + "\t"
                + clean(i.url) + "\t" + clean(i.id) + "\t" + i.added + "\t" + i.rating + "\n";
    }

    private void appendItems(String type, List<Item> list) {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(itemsFile(type), true), StandardCharsets.UTF_8)) {
            for (Item i : list) {
                w.write(line(i));
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

    /** Xtream dates come as epoch seconds, as text. */
    private static long epoch(String v) {
        try { return Long.parseLong(v.trim()) * 1000L; } catch (Exception e) { return 0; }
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
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder(1 << 14);
            try (InputStream in = c.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
                char[] buf = new char[16 * 1024];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            // no disconnect(): the socket goes back to the pool, so the next answer skips the TCP handshake
            // (on this TV's Wi-Fi a round trip is 5 to 250 ms)
            return sb.toString();
        } catch (IOException e) {
            c.disconnect();
            throw e;
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
