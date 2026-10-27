package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The series Reihane follows — the ones in « Ma liste » and the ones with an episode left halfway — watched for new
 * episodes: a snapshot of each one's episode list is kept, and whatever appears afterwards is flagged, so the home
 * screen can say "3 nouveaux épisodes". The panel is asked at most once every six hours, off the UI thread.
 */
final class Follow {
    private Follow() {}

    private static final long EVERY = 6 * 3600_000L;
    private static volatile long lastCheck;
    private static volatile boolean running;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("followed", Context.MODE_PRIVATE);
    }

    /** The series followed, by id: put aside, or left halfway. */
    static Set<String> series(Context c) {
        Set<String> ids = new LinkedHashSet<>();
        for (Iptv.Item i : Prefs.list()) if ((i.url == null || i.url.isEmpty()) && !i.id.isEmpty()) ids.add(i.id);
        for (Iptv.Item i : Prefs.resume("series")) if (i.id != null && !i.id.isEmpty()) ids.add(i.id);
        return ids;
    }

    /** Episodes that appeared since the last look (0: nothing new, or never looked). */
    static int newEpisodes(Context c, String seriesId) {
        try {
            return Integer.parseInt(sp(c).getString("new:" + seriesId, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The series' page was opened: what was new has been seen. */
    static void clear(Context c, String seriesId) {
        if (seriesId != null && !seriesId.isEmpty() && sp(c).contains("new:" + seriesId)) {
            sp(c).edit().remove("new:" + seriesId).apply();
        }
    }

    /**
     * Looks for new episodes of the followed series, at most every six hours. `done` is called (on the thread that
     * asks) when at least one series has something new.
     */
    static void check(Context c, Runnable done) {
        if (running || System.currentTimeMillis() - lastCheck < EVERY) return;
        final Set<String> ids = series(c);
        if (ids.isEmpty()) return;
        running = true;
        lastCheck = System.currentTimeMillis();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean changed = false;
            try {
                int n = 0;
                for (String id : ids) {
                    if (n++ >= 12) break;                  // a dozen series: the panel is not free
                    Iptv.Detail d = Iptv.get(app).series(id);
                    if (d == null || d.episodes.isEmpty()) continue;
                    Set<String> now = new LinkedHashSet<>();
                    for (Iptv.Episode e : d.episodes) now.add(e.id.isEmpty() ? e.url : e.id);
                    String was = sp(app).getString("seen:" + id, null);
                    if (was != null && !was.isEmpty()) {
                        Set<String> before = new LinkedHashSet<>();
                        for (String s : was.split(",")) if (!s.isEmpty()) before.add(s);
                        int fresh = 0;
                        for (String s : now) if (!before.contains(s)) fresh++;
                        if (fresh > 0) {
                            sp(app).edit().putString("new:" + id, String.valueOf(fresh)).apply();
                            changed = true;
                        }
                    }
                    StringBuilder b = new StringBuilder();
                    for (String s : now) b.append(b.length() == 0 ? "" : ",").append(s);
                    sp(app).edit().putString("seen:" + id, b.toString()).apply();
                }
            } catch (Exception e) {
                AppLog.w("follow: " + e.getMessage());
            }
            running = false;
            if (changed && done != null) done.run();
        }, "follow").start();
    }
}
