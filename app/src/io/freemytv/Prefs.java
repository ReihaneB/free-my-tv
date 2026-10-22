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
        if (duration > 0 && position > duration * 0.96) seen().edit().putLong(key(url), System.currentTimeMillis()).apply();
        if (duration <= 0 || position < 45_000 || position > duration * 0.96) {
            play().edit().remove(key(url)).apply();
            return;
        }
        play().edit().putString(key(url), name + "\t" + position + "\t" + duration + "\t" + url + "\t"
                + (logo == null ? "" : logo) + "\t" + (series == null ? "" : series)).apply();
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

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) { return sp().getString("key" + keyCode, defaultApp(keyCode)); }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }
}
