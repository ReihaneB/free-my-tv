package io.freemytv;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the panel's lists already say about every film and series — story, genres, year, rating, length, a series'
 * backdrop — kept on disk while the catalogue refreshes. The home screen reads it the instant a card gets the focus,
 * so the spotlight has its text without asking the panel anything. One SQLite table: nothing of it is held in memory.
 */
final class Facts extends SQLiteOpenHelper {
    private static Facts instance;

    static synchronized Facts get(Context c) {
        if (instance == null) instance = new Facts(c.getApplicationContext());
        return instance;
    }

    private Facts(Context c) {
        super(c, "facts.db", null, 1);
        setWriteAheadLoggingEnabled(true);   // the home reads while the refresh writes
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE facts (k TEXT PRIMARY KEY, title TEXT, year TEXT, genre TEXT, plot TEXT, "
                + "backdrop TEXT, poster TEXT, rating REAL, minutes INTEGER, director TEXT, actors TEXT, trailer TEXT)"
                + " WITHOUT ROWID");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        db.execSQL("DROP TABLE IF EXISTS facts");
        onCreate(db);
    }

    /** One answer of the panel (get_vod_streams or get_series for a category), in one transaction. */
    void write(boolean series, JSONArray list) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            SQLiteStatement st = db.compileStatement("INSERT OR REPLACE INTO facts VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String id = Iptv.text(o, series ? "series_id" : "stream_id");
                if (id.isEmpty()) continue;
                String title = Iptv.text(o, "title");
                if (title.isEmpty()) title = Iptv.text(o, "name");
                String backdrop = "";
                Object b = o.opt("backdrop_path");
                if (b instanceof JSONArray && ((JSONArray) b).length() > 0) backdrop = ((JSONArray) b).optString(0, "");
                else if (b instanceof String) backdrop = (String) b;
                if ("null".equalsIgnoreCase(backdrop)) backdrop = "";
                long minutes = 0;
                try { minutes = Math.round(Double.parseDouble(Iptv.text(o, "episode_run_time"))); } catch (Exception ignored) { }
                st.clearBindings();
                st.bindString(1, (series ? "series:" : "vod:") + id);
                st.bindString(2, title);
                st.bindString(3, Iptv.year(o));
                st.bindString(4, Iptv.text(o, "genre"));
                st.bindString(5, Iptv.text(o, "plot"));
                st.bindString(6, backdrop);
                st.bindString(7, Iptv.text(o, series ? "cover" : "stream_icon"));
                st.bindDouble(8, o.optDouble("rating", 0));
                st.bindLong(9, Math.max(0, minutes));
                st.bindString(10, Iptv.text(o, "director"));
                st.bindString(11, Iptv.text(o, "cast"));
                st.bindString(12, Iptv.text(o, "youtube_trailer"));
                st.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** A film's or a series' facts as the lists gave them (no seasons, no episodes, no age), or null. ~1 ms. */
    Iptv.Detail read(boolean series, String id) {
        if (id == null || id.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, year, genre, plot, backdrop, poster, rating, minutes, director, actors, trailer"
                        + " FROM facts WHERE k = ?", new String[]{(series ? "series:" : "vod:") + id})) {
            if (!c.moveToFirst()) return null;
            Iptv.Detail d = new Iptv.Detail();
            d.lite = true;
            d.title = c.getString(0);
            d.year = c.getString(1);
            d.genre = c.getString(2);
            d.plot = c.getString(3);
            d.backdrop = c.getString(4);
            d.poster = c.getString(5);
            d.rating = c.getFloat(6);
            d.durationMs = c.getLong(7) * 60_000L;
            d.director = c.getString(8);
            d.cast = c.getString(9);
            d.trailer = c.getString(10);
            return d;
        } catch (Exception e) {
            AppLog.w("facts: " + e.getMessage());
            return null;
        }
    }
}
