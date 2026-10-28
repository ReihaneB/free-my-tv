package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the panel does not give: the episodes' pictures. The panel keeps a still for some episodes and none for others
 * (measured: 2 of 20 on one series), and its own list is the only place they could come from. TMDB — the open movie
 * database the home screen already uses — knows each episode's still; the series is found by its title once, then one
 * request per season gives every episode's picture, kept on disk so it is asked for once.
 */
final class Tmdb {
    private Tmdb() {}

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/w300";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("tmdbstills", Context.MODE_PRIVATE);
    }

    static boolean available() {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /**
     * Fills the still of every episode that has none. Off the UI thread (network); `done` runs on the caller's thread
     * when something was added.
     */
    static void fill(Context c, Iptv.Detail d, String title, String year, Runnable done) {
        if (d == null || d.episodes.isEmpty() || !available()) return;
        boolean any = false;
        for (Iptv.Episode e : d.episodes) if (e.still.isEmpty()) any = true;
        if (!any) return;
        boolean changed = false;
        try {
            int id = showId(c, title, year);
            if (id <= 0) return;
            Map<Integer, Map<Integer, String>> seasons = new LinkedHashMap<>();
            for (Iptv.Episode e : d.episodes) {
                if (!e.still.isEmpty()) continue;
                Map<Integer, String> stills = seasons.get(e.season);
                if (stills == null) {
                    stills = season(c, id, e.season);
                    seasons.put(e.season, stills);
                }
                String s = stills.get(e.number);
                if (s != null && !s.isEmpty()) {
                    e.still = s;
                    changed = true;
                }
            }
        } catch (Exception ex) {
            AppLog.w("tmdb: " + ex.getMessage());
        }
        if (changed && done != null) done.run();
    }

    /** The series' TMDB id, from its title (and year when the title is ambiguous), kept for good. */
    private static int showId(Context c, String title, String year) throws Exception {
        if (title == null || title.isEmpty()) return 0;
        String key = "id:" + norm(title) + (year == null || year.isEmpty() ? "" : ":" + year);
        String was = sp(c).getString(key, null);
        if (was != null) return parseInt(was);
        String path = "/search/tv?language=" + lang() + "&query=" + URLEncoder.encode(title, "UTF-8")
                + (year == null || year.isEmpty() ? "" : "&first_air_date_year=" + URLEncoder.encode(year, "UTF-8"));
        JSONArray results = get(path).optJSONArray("results");
        int id = 0;
        if (results != null) {
            String want = norm(title);
            for (int i = 0; i < results.length(); i++) {
                JSONObject o = results.getJSONObject(i);
                String name = norm(o.optString("name"));
                if (name.equals(want) || (want.length() > 6 && name.startsWith(want))) {
                    id = o.optInt("id");
                    break;
                }
            }
            if (id == 0 && results.length() > 0) id = results.getJSONObject(0).optInt("id");   // the closest
        }
        sp(c).edit().putString(key, String.valueOf(id)).apply();
        return id;
    }

    /** Every episode's still of one season, by episode number; one request, kept on disk. */
    private static Map<Integer, String> season(Context c, int showId, int season) throws Exception {
        String key = "s:" + showId + ":" + season;
        String was = sp(c).getString(key, null);
        if (was != null) return parseStills(was);
        Map<Integer, String> out = new LinkedHashMap<>();
        JSONArray eps = get("/tv/" + showId + "/season/" + season + "?language=" + lang()).optJSONArray("episodes");
        StringBuilder saved = new StringBuilder();
        if (eps != null) {
            for (int i = 0; i < eps.length(); i++) {
                JSONObject o = eps.getJSONObject(i);
                String still = o.optString("still_path");
                int n = o.optInt("episode_number");
                if (still == null || still.isEmpty() || n <= 0) continue;
                out.put(n, IMG + still);
                saved.append(saved.length() == 0 ? "" : ";").append(n).append("=").append(still);
            }
        }
        sp(c).edit().putString(key, saved.toString()).apply();
        return out;
    }

    private static Map<Integer, String> parseStills(String saved) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (saved.isEmpty()) return out;
        for (String part : saved.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            int n = parseInt(part.substring(0, eq));
            if (n > 0) out.put(n, IMG + part.substring(eq + 1));
        }
        return out;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String lang() {
        String l = Ui.language == null ? "" : Ui.language.toLowerCase(Locale.ROOT);
        return l.startsWith("fr") ? "fr-FR" : l.startsWith("pt") ? "pt-BR" : l.startsWith("es") ? "es-ES" : "fr-FR";
    }

    /** "Avatar : Les Sept Refuges" → "AVATARLESSEPTREFUGES" (the panel's own spelling varies). */
    private static String norm(String s) {
        if (s == null) return "";
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = Config.TMDB_KEY.trim();
        boolean bearer = key.startsWith("eyJ");
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key="
                + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("TMDB HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) b.append(buf, 0, n);
        }
        return b.toString();
    }
}
