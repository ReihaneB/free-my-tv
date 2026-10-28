package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static SharedPreferences play() {
        return app.getSharedPreferences("playback", Context.MODE_PRIVATE);
    }

    /**
     * Where the player stopped, per stream. A film or an episode keeps its place for two weeks; a live channel is
     * never "resumed". Entries are stored as name \t position \t duration \t url \t logo \t series id (an episode:
     * its card opens the series' page). Played to the end, it is remembered as watched instead.
     */
    static void saveProgress(String url, String name, String logo, long position, long duration, String series) {
        if (url == null || url.isEmpty()) return;
        playVersion++;
        if (position >= 45_000) setLastWatched(name, url, series, logo);   // what the home screen's suggestions follow
        if (duration > 0 && position > duration * 0.96) seen().edit().putLong(key(url), System.currentTimeMillis()).apply();
        if (duration <= 0 || position < 45_000 || position > duration * 0.96) {
            play().edit().remove(key(url)).apply();
            return;
        }
        play().edit().putString(key(url), name + "\t" + position + "\t" + duration + "\t" + url + "\t"
                + (logo == null ? "" : logo) + "\t" + (series == null ? "" : series)).apply();
    }

    /** The channel last watched (its number): the Direct tab starts there. Not a setting: no version bump. */
    static int lastChannel() { return sp().getInt("lastChannel", 0); }

    static void setLastChannel(int number) {
        if (number > 0) sp().edit().putInt("lastChannel", number).apply();
    }

    /** Bumped whenever a position is saved or forgotten: the home screen's "En cours" knows it must be rebuilt. */
    static volatile int playVersion;

    /** Out of "En cours": the film's saved position is dropped (it starts from the beginning next time). */
    static void forget(String url) {
        if (url == null || url.isEmpty()) return;
        play().edit().remove(key(url)).apply();
        playVersion++;
    }

    /** Out of "En cours": every episode of the series left halfway (saved with its series id, or one of `urls`). */
    static void forgetSeries(String seriesId, java.util.Collection<String> urls) {
        SharedPreferences.Editor ed = play().edit();
        for (java.util.Map.Entry<String, ?> e : new ArrayList<>(play().getAll().entrySet())) {
            String[] f = String.valueOf(e.getValue()).split("\t", -1);
            boolean mine = f.length > 5 && seriesId != null && !seriesId.isEmpty() && seriesId.equals(f[5]);
            if (mine || (f.length > 3 && urls != null && urls.contains(f[3]))) ed.remove(e.getKey());
        }
        ed.apply();
        playVersion++;
    }

    /** Whether an episode of this series is left halfway (it shows in "En cours"). */
    static boolean resumingSeries(String seriesId) {
        if (seriesId == null || seriesId.isEmpty()) return false;
        for (Object v : play().getAll().values()) {
            String[] f = String.valueOf(v).split("\t", -1);
            if (f.length > 5 && seriesId.equals(f[5])) return true;
        }
        return false;
    }

    private static SharedPreferences seen() {
        return app.getSharedPreferences("watched", Context.MODE_PRIVATE);
    }

    /** Every stream played to the end (keys, see watched(set, url)). */
    static java.util.Set<String> watched() {
        return new java.util.HashSet<>(seen().getAll().keySet());
    }

    static boolean watched(java.util.Set<String> all, String url) {
        return url != null && all.contains(key(url));
    }

    static List<Iptv.Item> resume(String type) {
        List<Iptv.Item> out = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> e : new ArrayList<>(play().getAll().entrySet())) {
            String[] f = String.valueOf(e.getValue()).split("\t", -1);
            if (f.length < 4) continue;
            String url = f[3];
            boolean movie = url.contains("/movie/"), series = url.contains("/series/"), live = url.contains("/live/");
            if (("vod".equals(type) && !movie) || ("series".equals(type) && !series) || ("live".equals(type) && !live))
                continue;
            Iptv.Item it = new Iptv.Item();
            it.name = f[0];
            it.url = url;
            it.logo = f.length > 4 ? f[4] : "";
            it.id = f.length > 5 ? f[5] : "";   // an episode: its series
            out.add(it);
        }
        return out;
    }

    /** How much of this stream was watched (0 to 1), from the entry saved for it. */
    static float progressOf(String url) {
        Object v = play().getAll().get(key(url));
        if (v == null) return 0;
        String[] f = String.valueOf(v).split("\t", -1);
        if (f.length < 3) return 0;
        try {
            long pos = Long.parseLong(f[1]), dur = Long.parseLong(f[2]);
            return dur <= 0 ? 0 : Math.max(0, Math.min(1, (float) pos / dur));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Where this stream was left, in milliseconds (0 when it was never started or was finished). */
    static long positionOf(String url) {
        Object v = play().getAll().get(key(url));
        if (v == null) return 0;
        String[] f = String.valueOf(v).split("\t", -1);
        if (f.length < 3) return 0;
        try {
            return Math.max(0, Long.parseLong(f[1]));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Every saved position at once (one read of the store instead of one per card), url → fraction watched. */
    static java.util.Map<String, Float> progressAll() {
        java.util.Map<String, Float> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, ?> e : new java.util.ArrayList<>(play().getAll().entrySet())) {
            String[] f = String.valueOf(e.getValue()).split("\t", -1);
            if (f.length < 4) continue;
            try {
                long pos = Long.parseLong(f[1]), dur = Long.parseLong(f[2]);
                if (dur > 0) out.put(f[3], Math.max(0f, Math.min(1f, (float) pos / dur)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String key(String url) {
        return Integer.toHexString(url.hashCode());
    }

    // MARK: - Catégories du Direct masquées

    private static final String HIDDEN = "hiddenCats";

    /** The Direct categories put away, as {id, name} pairs. */
    static List<String[]> hiddenCategories() {
        List<String[]> out = new ArrayList<>();
        String v = sp().getString(HIDDEN, "");
        if (v.isEmpty()) return out;
        for (String line : v.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", 2);
            if (f.length == 2) out.add(f);
        }
        return out;
    }

    static boolean hiddenCategory(String id) {
        if (id == null || id.isEmpty()) return false;
        for (String[] f : hiddenCategories()) if (f[0].equals(id)) return true;
        return false;
    }

    static void hideCategory(String id, String name) {
        if (id == null || id.isEmpty() || hiddenCategory(id)) return;
        StringBuilder b = new StringBuilder(sp().getString(HIDDEN, ""));
        if (b.length() > 0) b.append('\n');
        b.append(id).append('\t').append(name == null ? "" : name.replace('\t', ' ').replace('\n', ' '));
        put(HIDDEN, b.toString());
    }

    static void showCategory(String id) {
        StringBuilder b = new StringBuilder();
        for (String[] f : hiddenCategories()) {
            if (f[0].equals(id)) continue;
            b.append(b.length() == 0 ? "" : "\n").append(f[0]).append('\t').append(f[1]);
        }
        put(HIDDEN, b.toString());
    }

    // MARK: - Chaînes favorites

    /** The channels that go first on the home screen, by number; the TNT until the user picks (null = never picked). */
    static List<Integer> favorites() {
        String v = sp().getString("favs", null);
        List<Integer> out = new ArrayList<>();
        if (v == null) {
            for (int i = 1; i <= 26; i++) out.add(i);   // the TNT, in the box's numbers
            return out;
        }
        for (String s : v.split(",")) {
            if (s.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static boolean favorite(int number) {
        if (number <= 0) return false;
        String v = sp().getString("favs", null);
        if (v == null) return number <= 26;   // the TNT, until the list is edited
        for (String s : v.split(",")) if (s.equals(String.valueOf(number))) return true;
        return false;
    }

    /** Adds the channel to the favourites, or takes it out (the first edit freezes the TNT default). */
    static void toggleFavorite(int number) {
        if (number <= 0) return;
        List<Integer> now = favorites();
        Integer n = number;
        if (!now.remove(n)) now.add(n);
        StringBuilder b = new StringBuilder();
        for (Integer i : now) b.append(b.length() == 0 ? "" : ",").append(i);
        put("favs", b.toString());
    }

    // MARK: - Ma liste (films et séries mis de côté)

    private static volatile int listVersion;

    private static SharedPreferences lastw() {
        return app.getSharedPreferences("lastwatched", Context.MODE_PRIVATE);
    }

    /** The last film or episode actually watched: name, stream, series id, poster. */
    static void setLastWatched(String name, String url, String seriesId, String logo) {
        lastw().edit().putString("last", (name == null ? "" : name) + "\t" + (url == null ? "" : url) + "\t"
                + (seriesId == null ? "" : seriesId) + "\t" + (logo == null ? "" : logo)).apply();
    }

    /** {name, stream, series id, poster}, or null. */
    static String[] lastWatched() {
        String v = lastw().getString("last", "");
        if (v.isEmpty()) return null;
        String[] f = v.split("\t", -1);
        return f.length >= 3 ? f : null;
    }

    /** Bumped on every change: the home screen's « Ma liste » knows it must be rebuilt. */
    static int listVersion() { return listVersion; }

    private static SharedPreferences mylist() {
        return app.getSharedPreferences("mylist", Context.MODE_PRIVATE);
    }

    /** "s:" + series id, or "m:" + the film's id: one key per title, whatever its stream. */
    static String listKey(boolean series, String id) {
        return id == null || id.isEmpty() ? "" : (series ? "s:" : "m:") + id;
    }

    static boolean inList(String key) {
        return key != null && !key.isEmpty() && mylist().contains(key);
    }

    /** Puts the title aside, or takes it out. Stored: name, logo, series id, year, stream, when. */
    static void toggleList(String key, String name, String logo, String seriesId, String year, String url) {
        if (key == null || key.isEmpty()) return;
        SharedPreferences p = mylist();
        if (p.contains(key)) {
            p.edit().remove(key).apply();
        } else {
            p.edit().putString(key, (name == null ? "" : name) + "\t" + (logo == null ? "" : logo) + "\t"
                    + (seriesId == null ? "" : seriesId) + "\t" + (year == null ? "" : year) + "\t"
                    + (url == null ? "" : url) + "\t" + System.currentTimeMillis()).apply();
        }
        listVersion++;
    }

    /** Everything put aside, the last one added first. */
    static List<Iptv.Item> list() {
        List<Object[]> sorted = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> e : new ArrayList<>(mylist().getAll().entrySet())) {
            String[] f = String.valueOf(e.getValue()).split("\t", -1);
            if (f.length < 5) continue;
            sorted.add(new Object[]{f, e.getKey()});
        }
        sorted.sort((a, b) -> {
            String[] fa = (String[]) a[0], fb = (String[]) b[0];
            long ta = fa.length > 5 ? parse(fa[5]) : 0, tb = fb.length > 5 ? parse(fb[5]) : 0;
            return Long.compare(tb, ta);
        });
        List<Iptv.Item> out = new ArrayList<>();
        for (Object[] o : sorted) {
            String[] f = (String[]) o[0];
            Iptv.Item it = new Iptv.Item();
            it.name = f[0];
            it.logo = f[1];
            it.id = f[2];      // a series: its id, its card opens the series' page
            it.year = f[3];
            it.url = f[4];     // a film: its stream
            out.add(it);
        }
        return out;
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City (from config.properties)

    static String city() { return Config.CITY.trim(); }
    static String latitude() { return Config.LATITUDE; }
    static String longitude() { return Config.LONGITUDE; }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Remote buttons

    static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "org.smarttube.stable";   // the TV's YouTube app is gone: SmartTube takes the key
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) { return sp().getString("key" + keyCode, defaultApp(keyCode)); }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }
}
