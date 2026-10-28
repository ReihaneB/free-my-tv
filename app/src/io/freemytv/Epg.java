package io.freemytv;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV guide of the account: what each channel shows now, next, and since yesterday. The panel gives it as one
 * XMLTV file (~11 MB, ~25 000 programmes, a week ahead); it is read as it downloads into a small SQLite base twice a
 * day, keeping only the lineup's channels. The screens read it from memory: each channel's programmes around now are
 * loaded once on a worker, so a card or the player's banner never waits for the disk.
 */
final class Epg {

    static final class Show {
        final long start, stop;
        final String title, info;

        Show(long start, long stop, String title, String info) {
            this.start = start;
            this.stop = stop;
            this.title = title == null ? "" : title;
            this.info = info == null ? "" : info;
        }

        float progress(long now) {
            return stop <= start ? 0 : Math.max(0f, Math.min(1f, (float) (now - start) / (stop - start)));
        }
    }

    interface Listener { void onGuide(); }

    private static final long EVERY = 12 * 3600_000L;
    /** What is kept in memory for a channel: the panel's three days of replay, and a day and a half ahead. */
    private static final long BACK = 74 * 3600_000L, AHEAD = 36 * 3600_000L;

    private static Epg instance;

    static synchronized Epg get(Context c) {
        if (instance == null) instance = new Epg(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File dir, stamp;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "epg");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Show[]> mem = new HashMap<>();
    private final Map<String, Long> memAt = new HashMap<>();
    private final Set<String> asked = new HashSet<>();
    private SQLiteDatabase db;
    private volatile boolean fetching;
    private boolean notifyPosted;

    private Epg(Context c) {
        app = c;
        dir = new File(c.getFilesDir(), "iptv");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        stamp = new File(dir, "guide.stamp");
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    /** The guide is fetched again every 12 h (the screens ask when they appear). */
    void refreshIfStale() {
        if (!Iptv.xtream() || fetching) return;
        long age = System.currentTimeMillis() - stamp.lastModified();
        if (stamp.isFile() && age >= 0 && age < EVERY) return;
        fetching = true;
        io.execute(this::fetch);
    }

    /** What `channel` (its guide id) shows at `at`; null if unknown or not loaded yet (it is then asked for). */
    Show now(String channel, long at) {
        Show[] a = shows(channel, at);
        if (a == null) return null;
        int i = find(a, at);
        return i >= 0 ? a[i] : null;
    }

    /** What comes after the programme on at `at`. */
    Show next(String channel, long at) {
        Show[] a = shows(channel, at);
        if (a == null) return null;
        for (Show s : a) if (s.start > at) return s;
        return null;
    }

    /** The channel's programmes between two instants, in order (what memory holds: a day back, a day and a half on). */
    List<Show> between(String channel, long from, long to) {
        List<Show> out = new ArrayList<>();
        Show[] a = shows(channel, System.currentTimeMillis());
        if (a == null) return out;
        for (Show s : a) if (s.stop > from && s.start < to) out.add(s);
        return out;
    }

    /** When this channel's guide starts and ends (ms), from memory; null when nothing is loaded. */
    long[] extent(String channel) {
        Show[] a = shows(channel, System.currentTimeMillis());
        if (a == null || a.length == 0) return null;
        return new long[]{a[0].start, a[a.length - 1].stop};
    }

    /** Loads these channels' programmes into memory on the worker (one indexed read each), then tells the screens. */
    void warm(Collection<String> channels) {
        final List<String> want = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (mem) {
            for (String ch : channels) {
                if (ch == null || ch.isEmpty()) continue;
                Long at = memAt.get(ch);
                if (at != null && Math.abs(now - at) < 6 * 3600_000L) continue;
                if (asked.add(ch)) want.add(ch);
            }
        }
        if (want.isEmpty()) return;
        io.execute(() -> {
            for (String ch : want) load(ch);
            changed();
        });
    }

    private Show[] shows(String channel, long at) {
        if (channel == null || channel.isEmpty()) return null;
        Show[] a;
        Long loaded;
        synchronized (mem) {
            a = mem.get(channel);
            loaded = memAt.get(channel);
        }
        if (a == null || loaded == null || Math.abs(at - loaded) > 6 * 3600_000L) {
            List<String> one = new ArrayList<>();
            one.add(channel);
            warm(one);
        }
        return a;
    }

    private static int find(Show[] a, long at) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Show s = a[mid];
            if (at < s.start) hi = mid - 1;
            else if (at >= s.stop) lo = mid + 1;
            else return mid;
        }
        return -1;
    }

    // MARK: - On the worker

    private SQLiteDatabase db() {
        if (db == null) {
            db = SQLiteDatabase.openOrCreateDatabase(new File(dir, "guide.db"), null);
            db.execSQL("CREATE TABLE IF NOT EXISTS p (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL,"
                    + " title TEXT, info TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS p_ch ON p (ch, start)");
            // one row per programme, so a new fetch replaces it instead of piling up: what the panel forgets stays
            try {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS p_key ON p (ch, start)");
            } catch (RuntimeException e) {
                db.execSQL("DELETE FROM p WHERE rowid NOT IN (SELECT MIN(rowid) FROM p GROUP BY ch, start)");
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS p_key ON p (ch, start)");
            }
        }
        return db;
    }

    private void load(String ch) {
        long now = System.currentTimeMillis();
        List<Show> list = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT start, stop, title, info FROM p WHERE ch = ? AND stop > ? AND start < ?"
                + " ORDER BY start", new String[]{ch, String.valueOf(now - BACK), String.valueOf(now + AHEAD)})) {
            while (c.moveToNext()) list.add(new Show(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3)));
        } catch (RuntimeException e) {
            AppLog.w("guide: " + e.getMessage());
        }
        synchronized (mem) {
            mem.put(ch, list.toArray(new Show[0]));
            memAt.put(ch, now);
            asked.remove(ch);
        }
    }

    /** Downloads the XMLTV file to disk, then reads it into the base (every channel, from three days back). */
    private void fetch() {
        File part = new File(dir, "guide.xml.part");
        try {
            long t0 = System.currentTimeMillis();
            download(Iptv.guideUrl(), part);
            int n = read(part);
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            if (n > 0) {
                new FileOutputStream(stamp).close();
                //noinspection ResultOfMethodCallIgnored
                stamp.setLastModified(System.currentTimeMillis());
                synchronized (mem) {
                    mem.clear();
                    memAt.clear();
                    asked.clear();
                }
                AppLog.w("guide: " + n + " programmes in " + (System.currentTimeMillis() - t0) / 1000 + " s");
                changed();
            }
        } catch (Exception e) {
            AppLog.w("guide: " + e);
        } finally {
            fetching = false;
        }
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        int code = c.getResponseCode();
        if (code / 100 == 3 && c.getHeaderField("Location") != null) {
            String next = c.getHeaderField("Location");
            c.disconnect();
            download(next, to);
            return;
        }
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private int read(File xml) throws Exception {
        SQLiteDatabase d = db();
        long now = System.currentTimeMillis();
        long from = now - 3 * 24 * 3600_000L;
        int n = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(xml), 1 << 16)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            d.beginTransaction();
            try {
                // what the panel no longer sends is kept (days of history), the rest of the base is replaced
                d.delete("p", "stop < ?", new String[]{String.valueOf(now - 4 * 24 * 3600_000L)});
                SQLiteStatement ins = d.compileStatement(
                        "INSERT OR REPLACE INTO p (ch, start, stop, title, info) VALUES (?,?,?,?,?)");
                String ch = null, title = null, info = null;
                long start = 0, stop = 0;
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String tag = p.getName();
                        if ("programme".equals(tag)) {
                            ch = p.getAttributeValue(null, "channel");
                            start = time(p.getAttributeValue(null, "start_timestamp"), p.getAttributeValue(null, "start"));
                            stop = time(p.getAttributeValue(null, "stop_timestamp"), p.getAttributeValue(null, "stop"));
                            title = null;
                            info = null;
                            if (ch == null || stop < from) ch = null;
                        } else if (ch != null && "title".equals(tag) && title == null) {
                            title = p.nextText();
                        } else if (ch != null && "desc".equals(tag) && info == null) {
                            info = p.nextText();
                        }
                    } else if (ev == XmlPullParser.END_TAG && "programme".equals(p.getName()) && ch != null) {
                        if (stop > start && title != null) {
                            ins.bindString(1, ch);
                            ins.bindLong(2, start);
                            ins.bindLong(3, stop);
                            ins.bindString(4, title.trim());
                            if (info == null) ins.bindNull(5);
                            else ins.bindString(5, info.trim());
                            ins.executeInsert();
                            ins.clearBindings();
                            n++;
                        }
                        ch = null;
                    }
                }
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        }
        return n;
    }

    /** XMLTV gives both the epoch seconds and "20261026213900 +0000": the first when it is there. */
    private static long time(String epoch, String xmltv) {
        try {
            if (epoch != null && !epoch.isEmpty()) return Long.parseLong(epoch.trim()) * 1000L;
        } catch (NumberFormatException ignored) {
        }
        if (xmltv == null || xmltv.length() < 14) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US);
            return f.parse(xmltv.length() >= 20 ? xmltv : xmltv.substring(0, 14) + " +0000").getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Tells the screens once per burst of loads. */
    private void changed() {
        synchronized (this) {
            if (notifyPosted) return;
            notifyPosted = true;
        }
        main.postDelayed(() -> {
            synchronized (this) { notifyPosted = false; }
            for (Listener l : new ArrayList<>(listeners)) l.onGuide();
        }, 60);
    }

    // MARK: - Words

    /** "21:10". */
    static String clock(long at) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(at);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY), m = c.get(java.util.Calendar.MINUTE);
        return h + ":" + (m < 10 ? "0" : "") + m;
    }

    /** "21:10 – 22:25". */
    static String span(Show s) {
        return clock(s.start) + " – " + clock(s.stop);
    }

    /** "il reste 35 min", "il reste 1 h 05". */
    static String left(Show s, long now) {
        long min = Math.max(1, (s.stop - now + 59_999) / 60_000);
        if (min < 60) return "il reste " + min + " min";
        long m = min % 60;
        return "il reste " + (min / 60) + " h" + (m == 0 ? "" : " " + (m < 10 ? "0" : "") + m);
    }
}
