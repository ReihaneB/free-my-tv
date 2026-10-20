package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The home screen, built like Infuse on the Apple TV: a bar of library tabs, then shelves of artwork.
 *
 * <p>Everything on screen is the account: a row of three large cards ("À la une"), then the shelves — continue
 * watching, new films, new series, channels — then the TV's own things (apps, HDMI inputs) as shelves of the same
 * shape. Left/Right walks a shelf (the row slides under the focus), Up/Down goes from shelf to shelf, and the bar on
 * top switches between the home and each library. The account is on disk, so the screen opens with no network.
 */
final class HomeView extends View implements Choreographer.FrameCallback, Iptv.Listener {

    // MARK: - Model

    /** One card: a film, an episode, a channel, an app or an input. */
    private static final class Tile {
        String title = "", meta = "", image = "", url = "", seriesId = "";
        Bitmap art;                 // apps: the ready 16:9 card
        HomeApps.Item app;          // apps and inputs open through HomeApps
        String symbol = "";         // inputs: the icon
        float progress;             // continue watching: 0..1
        boolean series, live;
    }

    private static final class Row {
        String title = "";
        int kind, seeTab = -1;
        final List<Tile> items = new ArrayList<>();
        float y, h;                 // in the content column, from 0
        int col, win;               // focused card, first card shown
        float slide, slideTo;       // horizontal offset of the shelf (animated)
        float[] focus = new float[0];   // per card, 0..1
    }

    private static final int R_HERO = 0, R_POSTER = 1, R_LOGO = 2, R_APP = 3;
    private static final int Z_ROW = 0, Z_BAR = 1;
    /** col value of the "Tout voir" label at the end of a shelf. */
    private static final int SEE_ALL = -2;

    private static final String[] TABS = {"Accueil", "Films", "Séries", "Live", "Apps"};
    private static final int TAB_COUNT = TABS.length;
    private static final int GEAR = TAB_COUNT;          // last stop in the bar

    // MARK: - Geometry (designed at 1920×1080, scaled by k)

    private static final float BAR_H = 106, MARGIN = 96;
    private static final float HEAD_H = 54, ROW_GAP = 30, CAP_H = 56;
    private static final float HERO_W = 548, HERO_H = 308, HERO_GAP = 30;
    private static final float POSTER_W = 190, POSTER_H = 285, POSTER_GAP = 26;
    private static final float LOGO_W = 240, LOGO_H = 135, LOGO_GAP = 24;
    private static final float APP_W = 240, APP_H = 135, APP_GAP = 24;

    private static final int BG = 0xFF0B0B0D;
    private static final int INK = 0xFFFFFFFF, DIM = 0xB3FFFFFF, FAINT = 0x80FFFFFF, ACCENT = 0xFFFF8A00;

    private float k = 1f, w, h;

    // MARK: - State

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Float> progress = new HashMap<>();   // continue watching, read once per rebuild
    private int tab, zone = Z_ROW, barIndex, rowIndex, colIndex;
    private float offset, offsetTo;
    private boolean resumed, framePosted;
    private long lastFrame;
    private int prefsVersion = Prefs.version;
    private String dataKey = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            invalidate();
            if (resumed) main.postDelayed(this, 20_000);
        }
    };

    // MARK: - Paints (built after Ui.init, or they miss the font)

    private Paint tabInk, rowTitle, cardTitle, cardMeta, seeAll, clock, toast, empty;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint barShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint heroShade = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path clip = new Path();
    private final RectF r = new RectF(), r2 = new RectF(), dst = new RectF(), artBox = new RectF();
    private final android.graphics.Rect srcRect = new android.graphics.Rect();
    private final RectF[] barRects = new RectF[GEAR + 1];
    private final Map<String, Bitmap> softs = new HashMap<>();   // url → tiny bitmap stretched as a blur
    private String toastText = "";
    private long toastAt;

    // MARK: - Lifecycle

    HomeView(Context context) {
        super(context);
        setBackgroundColor(BG);
        setFocusable(true);
        setFocusableInTouchMode(true);
        Ui.init(context);
        paints();
        for (int i = 0; i <= GEAR; i++) barRects[i] = new RectF();
        rebuild();
    }

    private void paints() {
        tabInk = Ui.text(Ui.semibold, 26, INK);
        rowTitle = Ui.text(Ui.displaySemibold, 34, INK);
        cardTitle = Ui.text(Ui.medium, 24, 0xF2FFFFFF);
        cardMeta = Ui.text(Ui.regular, 20, FAINT);
        seeAll = Ui.text(Ui.medium, 22, ACCENT);
        clock = Ui.numbers(Ui.medium, 22, DIM);
        toast = Ui.text(Ui.medium, 24, INK);
        empty = Ui.text(Ui.medium, 26, FAINT);
    }

    void resume() {
        resumed = true;
        Iptv.get(getContext()).addListener(this);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            rebuild();
        }
        requestFrame();
        main.removeCallbacks(clockTick);
        main.postDelayed(clockTick, 20_000);
        invalidate();
    }

    void pause() {
        resumed = false;
        Iptv.get(getContext()).removeListener(this);
        main.removeCallbacks(clockTick);
    }

    void destroy() {
        pause();
    }

    /** Home pressed on the home screen: back to the top of the home tab. */
    void reset(boolean animated) {
        tab = 0;
        barIndex = 0;
        rowIndex = 0;
        colIndex = 0;
        zone = Z_ROW;
        offset = offsetTo = 0;
        rebuild();
        requestFrame();
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
        requestFrame();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(this);
        framePosted = false;
    }

    void showToast(String text) {
        toastText = text;
        toastAt = System.currentTimeMillis();
        invalidate();
    }

    // MARK: - The account, as shelves

    @Override public void onIptv() {
        main.post(() -> {
            if (!resumed) return;
            if (!key().equals(dataKey)) rebuild();
        });
    }

    private String key() {
        Iptv i = Iptv.get(getContext());
        return tab + "|" + i.live().size() + "|" + i.movies().size() + "|" + i.series().size()
                + "|" + i.movieCategories().size() + "|" + i.seriesCategories().size()
                + "|" + Prefs.version;
    }

    private void rebuild() {
        dataKey = key();
        progress.clear();
        progress.putAll(Prefs.progressAll());
        String keep = rowIndex < rows.size() ? rows.get(rowIndex).title : null;
        rows.clear();
        if (Iptv.configured()) {
            switch (tab) {
                case 0: homeRows(); break;
                case 1: filmRows(); break;
                case 2: seriesRows(); break;
                case 3: liveRows(); break;
                default: appRows(); break;
            }
        } else {
            Row row = new Row();
            row.title = "Applications";
            row.kind = R_APP;
            addApps(row);
            if (!row.items.isEmpty()) rows.add(row);
        }
        // Keep the shelf the user was on when the data lands (the account is read in the background).
        int restored = -1;
        if (keep != null) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).title.equals(keep)) { restored = i; break; }
        }
        rowIndex = restored >= 0 ? restored : Math.min(rowIndex, Math.max(0, rows.size() - 1));
        for (Row row : rows) {
            if (row.col < 0) row.col = 0;
            row.col = Math.min(row.col, Math.max(0, row.items.size() - 1));
            row.focus = new float[row.items.size()];
            row.win = Math.max(0, Math.min(row.win, Math.max(0, row.items.size() - 1)));
            row.slide = row.slideTo = row.win * step(row.kind);
        }
        layout();
        invalidate();
    }

    private void homeRows() {
        Iptv iptv = Iptv.get(getContext());
        Row hero = new Row();
        hero.title = "À la une";
        hero.kind = R_HERO;
        List<Iptv.Item> resumed = new ArrayList<>(Prefs.resume("vod"));
        resumed.addAll(Prefs.resume("series"));
        if (!resumed.isEmpty()) hero.items.add(resumeTile(resumed.get(0)));
        if (hero.items.isEmpty()) {
            for (Iptv.Item i : iptv.fresh("vod", 30, 40)) { hero.items.add(tile(i, false)); break; }
        }
        for (Iptv.Item i : iptv.fresh("series", 60, 40)) {
            if (hero.items.size() >= 2) break;
            hero.items.add(tile(i, true));
        }
        for (Iptv.Item i : frenchChannels(iptv, 4)) {
            if (hero.items.size() >= 3) break;
            hero.items.add(tile(i, false));
        }
        if (!hero.items.isEmpty()) rows.add(hero);

        Row resume = new Row();
        resume.title = "Reprendre la lecture";
        resume.kind = R_POSTER;
        for (Iptv.Item i : Prefs.resume("vod")) resume.items.add(resumeTile(i));
        for (Iptv.Item i : Prefs.resume("series")) resume.items.add(resumeTile(i));
        if (!resume.items.isEmpty()) rows.add(resume);

        shelf("Nouveautés films", R_POSTER, iptv.fresh("vod", 30, 30), false, 1);
        shelf("Nouvelles séries", R_POSTER, iptv.fresh("series", 60, 30), true, 2);
        shelf("Chaînes", R_LOGO, frenchChannels(iptv, 30), false, 3);
        shelf("Les mieux notés", R_POSTER, iptv.best("vod", 30), false, 1);
        shelf("Séries", R_POSTER, firstOf(iptv.series(), 30), true, 2);

        Row apps = new Row();
        apps.title = "Applications";
        apps.kind = R_APP;
        apps.seeTab = 4;
        addApps(apps);
        if (!apps.items.isEmpty()) rows.add(apps);

        Row inputs = new Row();
        inputs.title = "Entrées";
        inputs.kind = R_LOGO;
        inputs.seeTab = 4;
        addInputs(inputs);
        if (!inputs.items.isEmpty()) rows.add(inputs);
    }

    private void filmRows() {
        Iptv iptv = Iptv.get(getContext());
        shelf("Nouveautés", R_POSTER, iptv.fresh("vod", 30, 30), false, -1);
        shelf("Les mieux notés", R_POSTER, iptv.best("vod", 30), false, -1);
        List<Iptv.Item> all = iptv.movies();
        int built = 0;
        for (Iptv.Category c : iptv.movieCategories()) {
            if (built >= 12) break;
            List<Iptv.Item> items = new ArrayList<>();
            for (Iptv.Item i : all) {
                if (!c.id.equals(i.group) || i.name.startsWith("─") || i.name.contains("✧")) continue;
                items.add(i);
                if (items.size() >= 30) break;
            }
            if (items.size() < 4) continue;
            shelf(clean(c.name), R_POSTER, items, false, -1);
            built++;
        }
    }

    private void seriesRows() {
        Iptv iptv = Iptv.get(getContext());
        shelf("Nouvelles séries", R_POSTER, iptv.fresh("series", 60, 30), true, -1);
        shelf("Les mieux notées", R_POSTER, iptv.best("series", 30), true, -1);
        List<Iptv.Item> all = iptv.series();
        int built = 0;
        for (Iptv.Category c : iptv.seriesCategories()) {
            if (built >= 12) break;
            List<Iptv.Item> items = new ArrayList<>();
            for (Iptv.Item i : all) {
                if (!c.id.equals(i.group)) continue;
                items.add(i);
                if (items.size() >= 30) break;
            }
            if (items.size() < 4) continue;
            shelf(clean(c.name), R_POSTER, items, true, -1);
            built++;
        }
    }

    private void liveRows() {
        Iptv iptv = Iptv.get(getContext());
        shelf("Chaînes françaises", R_LOGO, frenchChannels(iptv, 30), false, -1);
        List<Iptv.Item> all = iptv.live();
        int built = 0;
        for (Iptv.Category c : iptv.liveCategories()) {
            if (built >= 14) break;
            if (c.name.toUpperCase(Locale.ROOT).contains("FRANCE")) continue;
            List<Iptv.Item> items = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Iptv.Item i : all) {
                if (!c.id.equals(i.group) || i.url.isEmpty()) continue;
                if (i.name.startsWith("─") || i.name.startsWith("=") || i.name.contains("✧")) continue;
                if (!seen.add(norm(i.name))) continue;
                items.add(i);
                if (items.size() >= 30) break;
            }
            if (items.size() < 4) continue;
            shelf(clean(c.name), R_LOGO, items, false, -1);
            built++;
        }
    }

    private void appRows() {
        Row apps = new Row();
        apps.title = "Applications";
        apps.kind = R_APP;
        addApps(apps);
        if (!apps.items.isEmpty()) rows.add(apps);
        Row inputs = new Row();
        inputs.title = "Entrées";
        inputs.kind = R_LOGO;
        addInputs(inputs);
        if (!inputs.items.isEmpty()) rows.add(inputs);
    }

    private void shelf(String title, int kind, List<Iptv.Item> items, boolean series, int seeTab) {
        if (items.isEmpty()) return;
        Row row = new Row();
        row.title = title;
        row.kind = kind;
        row.seeTab = seeTab;
        Set<String> seen = new HashSet<>();
        for (Iptv.Item i : items) {
            // The panel lists a channel in HD, FHD and backup, and a film twice under two ids.
            if (!seen.add(norm(pretty(i.name)))) continue;
            row.items.add(tile(i, series));
        }
        if (!row.items.isEmpty()) rows.add(row);
    }

    private void addApps(Row row) {
        for (HomeApps.Item it : HomeApps.shelf(getContext(), false)) row.items.add(appTile(it));
        for (HomeApps.Item it : HomeApps.others(getContext())) row.items.add(appTile(it));
    }

    private void addInputs(Row row) {
        for (HomeApps.Item it : HomeApps.allInputs(getContext())) {
            Tile t = new Tile();
            t.title = it.label;
            t.app = it;
            t.symbol = it.symbol == null || it.symbol.isEmpty() ? "hdmi" : it.symbol;
            row.items.add(t);
        }
    }

    private static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile("\\((\\d{4})\\)\\s*$");

    private Tile tile(Iptv.Item i, boolean series) {
        Tile t = new Tile();
        String name = i.name == null ? "" : i.name;
        java.util.regex.Matcher m = YEAR.matcher(name);
        if (m.find()) {
            name = name.substring(0, m.start());
            t.meta = m.group(1);
        }
        t.title = pretty(name);
        t.image = i.logo == null ? "" : i.logo;
        if (i.year != null && !i.year.isEmpty()) t.meta = i.year;
        t.series = series;
        t.seriesId = i.id == null ? "" : i.id;
        t.url = t.series ? "" : (i.url == null ? "" : i.url);
        t.live = t.url.contains("/live/");
        Float saved = t.live || t.url.isEmpty() ? null : progress.get(t.url);
        t.progress = saved == null ? 0 : saved;
        return t;
    }

    /** A saved position has the title and the stream but no artwork: take the poster from the catalogue. */
    private Tile resumeTile(Iptv.Item i) {
        Tile t = tile(i, false);
        if (!t.image.isEmpty() || t.title.isEmpty()) return t;
        String n = norm(t.title);
        Iptv ip = Iptv.get(getContext());
        for (Iptv.Item m : ip.movies()) {
            if (n.equals(norm(pretty(m.name)))) { t.image = m.logo == null ? "" : m.logo; t.meta = m.year == null ? "" : m.year; break; }
        }
        if (t.image.isEmpty()) {
            for (Iptv.Item m : ip.series()) {
                if (n.equals(norm(pretty(m.name)))) { t.image = m.logo == null ? "" : m.logo; t.meta = m.year == null ? "" : m.year; break; }
            }
        }
        return t;
    }

    private static Tile appTile(HomeApps.Item it) {
        Tile t = new Tile();
        t.title = it.label;
        t.app = it;
        t.art = it.tile != null ? it.tile : it.logo;
        return t;
    }

    // MARK: - Layout

    @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        w = width;
        h = height;
        k = Math.min(width / 1920f, height / 1080f);
        layout();
        offset = offsetTo;
        for (Row row : rows) row.slide = row.slideTo;
    }

    private float tw(int kind) {
        return (kind == R_HERO ? HERO_W : kind == R_POSTER ? POSTER_W : kind == R_APP ? APP_W : LOGO_W) * k;
    }

    private float th(int kind) {
        return (kind == R_HERO ? HERO_H : kind == R_POSTER ? POSTER_H : kind == R_APP ? APP_H : LOGO_H) * k;
    }

    private float gap(int kind) {
        return (kind == R_HERO ? HERO_GAP : kind == R_POSTER ? POSTER_GAP : kind == R_APP ? APP_GAP : LOGO_GAP) * k;
    }

    private float step(int kind) {
        return tw(kind) + gap(kind);
    }

    private int cols(int kind) {
        float usable = w - 2 * MARGIN * k;
        return Math.max(1, (int) ((usable + gap(kind)) / step(kind)));
    }

    private float capH(int kind) {
        return (kind == R_HERO ? 22 : kind == R_POSTER ? CAP_H : 34) * k;
    }

    private void layout() {
        float y = 0;
        for (Row row : rows) {
            row.y = y;
            row.h = (HEAD_H + th(row.kind) + capH(row.kind)) * k;
            y += row.h + ROW_GAP * k;
            if (row.col < 0) row.col = 0;
            row.col = Math.min(row.col, Math.max(0, row.items.size() - 1));
            int n = cols(row.kind);
            if (row.win > row.col) row.win = row.col;
            if (row.col >= row.win + n) row.win = row.col - n + 1;
            row.win = Math.max(0, Math.min(row.win, Math.max(0, row.items.size() - n)));
            row.slideTo = row.win * step(row.kind);
            if (row.focus.length != row.items.size()) {
                float[] a = new float[row.items.size()];
                for (int i = 0; i < Math.min(a.length, row.focus.length); i++) a[i] = row.focus[i];
                row.focus = a;
            }
        }
        layoutBar();
        clampOffset();
    }

    private void layoutBar() {
        float x = MARGIN * k;
        tabInk.setTextSize(26 * k);
        for (int i = 0; i < TAB_COUNT; i++) {
            float wdt = tabInk.measureText(TABS[i]) + 34 * k;
            barRects[i].set(x, 8 * k, x + wdt, BAR_H * k - 8 * k);
            x += wdt + 6 * k;
        }
        float g = 38 * k;
        barRects[GEAR].set(w - MARGIN * k - g, (BAR_H * k - g) / 2, w - MARGIN * k, (BAR_H * k + g) / 2);
    }

    private float contentH() {
        if (rows.isEmpty()) return 0;
        Row last = rows.get(rows.size() - 1);
        return last.y + last.h;
    }

    private float maxOffset() {
        return Math.max(0, contentH() - (h - BAR_H * k - 24 * k));
    }

    private void clampOffset() {
        offsetTo = Math.max(0, Math.min(maxOffset(), offsetTo));
    }

    /** Scrolls so the focused shelf is entirely on screen. */
    private void reveal() {
        if (rows.isEmpty()) return;
        // The focused shelf sits right under the bar, so the shelf below always peeks at the bottom: no half row
        // cut at the top without its title.
        Row row = rows.get(rowIndex);
        offsetTo = row.y - 4 * k;
        clampOffset();
        requestFrame();
    }

    // MARK: - Motion

    private void requestFrame() {
        if (framePosted) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long nanos) {
        framePosted = false;
        long now = nanos / 1_000_000L;
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean moving = false;
        if (Math.abs(offsetTo - offset) > 0.35f) {
            offset += (offsetTo - offset) * Math.min(1f, dt * 13f);
            moving = true;
        } else {
            offset = offsetTo;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (Math.abs(row.slideTo - row.slide) > 0.35f) {
                row.slide += (row.slideTo - row.slide) * Math.min(1f, dt * 16f);
                moving = true;
            } else {
                row.slide = row.slideTo;
            }
            if (row.focus.length != row.items.size()) continue;
            for (int j = 0; j < row.items.size(); j++) {
                float target = i == rowIndex && zone == Z_ROW && j == row.col ? 1f : 0f;
                float f = row.focus[j];
                if (Math.abs(target - f) < 0.003f) {
                    row.focus[j] = target;
                    continue;
                }
                row.focus[j] = f + (target - f) * Math.min(1f, dt * 15f);
                moving = true;
            }
        }
        invalidate();
        if (moving) requestFrame();
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        float barH = BAR_H * k;
        drawBackdrop(c);
        c.save();
        c.clipRect(0, barH, w, h);
        float top = barH + 16 * k - offset;
        if (rows.isEmpty()) {
            empty.setTextSize(26 * k);
            c.drawText("Aucun compte IPTV : renseignez-le dans les réglages.", MARGIN * k, top + 60 * k, empty);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            float y = top + row.y;
            if (y > h || y + row.h < barH) continue;
            drawRow(c, row, i, y);
        }
        c.restore();
        drawBar(c);
        drawToast(c);
    }

    /** A soft wash of the focused artwork behind the top of the screen (Infuse blurs its backdrop the same way). */
    private void drawBackdrop(Canvas c) {
        fill.setShader(null);
        fill.setColor(BG);
        c.drawRect(0, 0, w, h, fill);
        if (rows.isEmpty() || zone != Z_ROW) return;
        Row row = rows.get(rowIndex);
        if (row.kind != R_HERO || row.col < 0 || row.col >= row.items.size()) return;
        Bitmap soft = soft(row.items.get(row.col));
        if (soft == null) return;
        r2.set(0, 0, w, h * 0.78f);
        art.setAlpha(44);
        c.drawBitmap(soft, null, r2, art);
        art.setAlpha(255);
        heroShade.setShader(new LinearGradient(0, 0, 0, h * 0.88f, 0x00000000, BG, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.88f, heroShade);
        heroShade.setShader(null);
    }

    private void drawRow(Canvas c, Row row, int index, float y) {
        float m = MARGIN * k;
        rowTitle.setTextSize(34 * k);
        rowTitle.setTextAlign(Paint.Align.LEFT);
        float base = y + HEAD_H * k - 12 * k;
        c.drawText(row.title, m, base, rowTitle);

        boolean focusedRow = index == rowIndex && zone == Z_ROW;
        if (row.seeTab >= 0) {
            seeAll.setTextSize(24 * k);
            seeAll.setTextAlign(Paint.Align.RIGHT);
            boolean on = focusedRow && row.col == SEE_ALL;
            float tw2 = seeAll.measureText("Tout voir");
            if (on) {
                r.set(w - m - tw2 - 22 * k, base - Ui.cap(seeAll) - 10 * k, w - m + 12 * k, base + 10 * k);
                fill.setShader(null);
                fill.setColor(0xF2FFFFFF);
                c.drawRoundRect(r, 20 * k, 20 * k, fill);
                seeAll.setColor(0xFF000000);
            } else {
                seeAll.setColor(ACCENT);
            }
            c.drawText("Tout voir", w - m, base, seeAll);
            seeAll.setColor(ACCENT);
        }

        int n = cols(row.kind);
        float s = step(row.kind), tw = tw(row.kind), th = th(row.kind);
        float tilesTop = y + HEAD_H * k;
        for (int j = row.win; j < n + row.win && j < row.items.size(); j++) {
            Tile t = row.items.get(j);
            float x = m + j * s - row.slide;
            r.set(x, tilesTop, x + tw, tilesTop + th);
            if (r.right < m - 8 || r.left > w - m + 8) continue;
            float f = j < row.focus.length ? row.focus[j] : 0;
            drawTile(c, row.kind, r, t, f);
            if (row.kind != R_HERO) drawCaption(c, row.kind, t, x, tilesTop + th + 8 * k, tw);
        }
    }

    private void drawTile(Canvas c, int kind, RectF box, Tile t, float f) {
        float radius = (kind == R_HERO ? 20 : 14) * k;
        float grow = 1 + 0.05f * f;
        r2.set(box);
        r2.inset(-box.width() * (grow - 1) / 2, -box.height() * (grow - 1) / 2);
        float rad = radius * grow;

        if (f > 0.02f) {
            dst.set(r2);
            dst.offset(0, 12 * k * f);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            c.drawRoundRect(dst, rad, rad, fill);
        }

        clip.reset();
        clip.addRoundRect(r2, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        fill.setShader(null);
        fill.setColor(kind == R_APP || kind == R_LOGO ? 0xFF17171B : 0xFF101014);
        c.drawRect(r2, fill);

        Bitmap b = artOf(t);
        if (kind == R_HERO) {
            drawHeroCard(c, r2, t);
        } else if (b != null) {
            if (kind == R_POSTER || t.art != null) drawCover(c, b, r2);
            else drawLogo(c, b, r2);
        } else if (!t.symbol.isEmpty()) {
            Symbols.draw(getContext(), c, t.symbol, r2.centerX(), r2.centerY() - 6 * k, 64 * k, DIM);
        } else {
            cardTitle.setTextSize((kind == R_POSTER ? 22 : 20) * k);
            cardTitle.setTextAlign(Paint.Align.CENTER);
            List<String> lines = wrap(t.title, cardTitle, r2.width() - 24 * k, kind == R_POSTER ? 3 : 2);
            float ly = r2.centerY() - (lines.size() - 1) * 14 * k;
            for (String line : lines) {
                c.drawText(line, r2.centerX(), ly + Ui.halfCap(cardTitle), cardTitle);
                ly += 28 * k;
            }
            cardTitle.setTextAlign(Paint.Align.LEFT);
        }

        if (t.progress > 0.005f) {
            float barY = r2.bottom - 3.5f * k;
            fill.setColor(0x99000000);
            c.drawRect(r2.left, barY - 3.5f * k, r2.right, barY, fill);
            fill.setColor(ACCENT);
            c.drawRect(r2.left, barY - 3.5f * k, r2.left + Math.max(10 * k, r2.width() * t.progress), barY, fill);
        }
        if (f > 0.02f) {
            fill.setShader(new LinearGradient(0, r2.top, 0, r2.top + r2.height() * 0.45f,
                    Ui.alpha(0xFFFFFFFF, 0.07f * f), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(r2, fill);
            fill.setShader(null);
        }
        c.restore();

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1 * k);
        stroke.setColor(0x1AFFFFFF);
        c.drawRoundRect(r2, rad, rad, stroke);
        if (f > 0.02f) {
            stroke.setStrokeWidth(2 * k);
            stroke.setColor(Ui.alpha(0xFFFFFFFF, 0.62f * f));
            c.drawRoundRect(r2, rad, rad, stroke);
        }
    }

    private Bitmap artOf(Tile t) {
        if (t.art != null) return t.art;
        if (t.image == null || t.image.isEmpty()) return null;
        return Posters.get(getContext()).get(t.image, (url, bitmap) -> postInvalidateOnAnimation());
    }

    private void drawCover(Canvas c, Bitmap b, RectF box) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(box.width() / bw, box.height() / bh);
        float sw = box.width() / scale, sh = box.height() / scale;
        srcRect.set(Math.round((bw - sw) / 2), Math.round((bh - sh) / 2),
                Math.round((bw + sw) / 2), Math.round((bh + sh) / 2));
        c.drawBitmap(b, srcRect, box, art);
    }

    /**
     * A hero card: the artwork on the left, its title, year and button on the right, over a blurred wash of the same
     * artwork. Infuse shows fanart here; the panel only has posters, so the poster is shown whole instead.
     */
    private void drawHeroCard(Canvas c, RectF box, Tile t) {
        Bitmap b = artOf(t);
        Bitmap low = soft(t);
        if (low != null) {
            art.setAlpha(120);
            c.drawBitmap(low, null, box, art);
            art.setAlpha(255);
        }
        heroShade.setShader(new LinearGradient(box.left, box.top, box.right, box.top,
                0xE6000000, 0x59000000, Shader.TileMode.CLAMP));
        c.drawRect(box, heroShade);
        heroShade.setShader(null);

        float pad = 22 * k;
        float artH = box.height() - 2 * pad;
        float artW = artH * 2f / 3f;   // one text column for every card, whatever the artwork's shape
        artBox.set(box.left + pad, box.top + pad, box.left + pad + artW, box.top + pad + artH);
        if (b != null) {
            drawContain(c, b, artBox);
        } else if (!t.symbol.isEmpty()) {
            Symbols.draw(getContext(), c, t.symbol, artBox.centerX(), artBox.centerY(), 70 * k, DIM);
        }

        float tx = artBox.right + 26 * k;
        float maxW = box.right - pad - tx;
        cardTitle.setTextSize(30 * k);
        cardTitle.setTextAlign(Paint.Align.LEFT);
        List<String> lines = wrap(t.title, cardTitle, maxW, 2);
        float ty = box.top + pad + 8 * k;
        for (String line : lines) {
            c.drawText(line, tx, ty + Ui.cap(cardTitle), cardTitle);
            ty += 38 * k;
        }
        cardMeta.setTextSize(20 * k);
        cardMeta.setTextAlign(Paint.Align.LEFT);
        String meta = t.meta == null ? "" : t.meta;
        if (!meta.isEmpty()) c.drawText(fit(cardMeta, meta, maxW), tx, ty + Ui.cap(cardMeta) + 4 * k, cardMeta);

        String label = t.series ? "Épisodes" : t.url.isEmpty() ? "" : t.live ? "En direct" : "Regarder";
        if (!label.isEmpty()) {
            cardTitle.setTextSize(20 * k);
            float bw = cardTitle.measureText(label) + 58 * k;
            float by = Math.min(box.bottom - pad - 44 * k, ty + Ui.cap(cardMeta) + 26 * k);
            r.set(tx, by, tx + bw, by + 44 * k);
            fill.setShader(null);
            fill.setColor(0xF2FFFFFF);
            c.drawRoundRect(r, 22 * k, 22 * k, fill);
            Symbols.draw(getContext(), c, "play", r.left + 25 * k, r.centerY(), 22 * k, 0xFF000000);
            cardTitle.setColor(0xFF000000);
            c.drawText(label, r.left + 44 * k, r.centerY() + Ui.halfCap(cardTitle), cardTitle);
            cardTitle.setColor(0xF2FFFFFF);
        }
    }

    private void drawContain(Canvas c, Bitmap b, RectF box) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.min(box.width() / bw, box.height() / bh);
        float dw = bw * scale, dh = bh * scale;
        dst.set(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2);
        c.drawBitmap(b, null, dst, art);
    }

    private Bitmap soft(Tile t) {
        if (t == null || t.image == null || t.image.isEmpty()) return null;
        Bitmap s = softs.get(t.image);
        if (s != null) return s;
        Bitmap src = artOf(t);
        if (src == null) return null;
        int hh = 26;
        int ww = Math.max(4, Math.round(hh * (float) src.getWidth() / Math.max(1, src.getHeight())));
        s = Bitmap.createScaledBitmap(src, ww, hh, true);
        if (softs.size() > 16) softs.clear();
        softs.put(t.image, s);
        return s;
    }

    private void drawLogo(Canvas c, Bitmap b, RectF box) {
        float pad = 18 * k;
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.min((box.width() - 2 * pad) / bw, (box.height() - 2 * pad) / bh);
        float dw = bw * scale, dh = bh * scale;
        dst.set(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2);
        c.drawBitmap(b, null, dst, art);
    }

    private void drawCaption(Canvas c, int kind, Tile t, float x, float y, float width) {
        cardTitle.setTextSize((kind == R_HERO ? 26 : kind == R_POSTER ? 22 : 21) * k);
        cardTitle.setTextAlign(Paint.Align.LEFT);
        cardMeta.setTextSize((kind == R_HERO ? 20 : 18) * k);
        cardMeta.setTextAlign(Paint.Align.LEFT);
        c.drawText(fit(cardTitle, t.title, width), x, y + Ui.cap(cardTitle), cardTitle);
        String meta = t.meta == null ? "" : t.meta;
        if (kind == R_LOGO && t.app != null && !t.app.connected) meta = "Débranché";
        if (!meta.isEmpty()) c.drawText(fit(cardMeta, meta, width), x, y + Ui.cap(cardTitle) + 26 * k, cardMeta);
    }

    private void drawBar(Canvas c) {
        float barH = BAR_H * k;
        barShade.setShader(new LinearGradient(0, 0, 0, barH + 34 * k, 0xF20B0B0D, 0x000B0B0D, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, barH + 34 * k, barShade);
        barShade.setShader(null);

        for (int i = 0; i <= GEAR; i++) {
            boolean on = i == tab;
            boolean focus = zone == Z_BAR && i == barIndex;
            RectF box = barRects[i];
            if (focus) {
                fill.setColor(0x26FFFFFF);
                c.drawRoundRect(box, 17 * k, 17 * k, fill);
            }
            if (i == GEAR) {
                Symbols.draw(getContext(), c, "settings", box.centerX(), box.centerY(), 30 * k, focus ? INK : DIM);
                continue;
            }
            tabInk.setTextSize(26 * k);
            tabInk.setTextAlign(Paint.Align.CENTER);
            tabInk.setColor(on ? INK : focus ? 0xE6FFFFFF : 0x73FFFFFF);
            c.drawText(TABS[i], box.centerX(), box.centerY() + Ui.halfCap(tabInk), tabInk);
            if (on) {
                fill.setColor(ACCENT);
                c.drawCircle(box.centerX(), box.bottom - 6 * k, 3.2f * k, fill);
            }
        }
        tabInk.setColor(INK);

        clock.setTextSize(22 * k);
        clock.setTextAlign(Paint.Align.RIGHT);
        String time = new java.text.SimpleDateFormat("HH:mm", Locale.FRANCE)
                .format(new java.util.Date(System.currentTimeMillis()));
        c.drawText(time, barRects[GEAR].left - 34 * k,
                BAR_H * k / 2 + Ui.halfCap(clock), clock);
    }

    private void drawToast(Canvas c) {
        if (toastText == null || toastText.isEmpty()) return;
        long age = System.currentTimeMillis() - toastAt;
        if (age > 2600) {
            toastText = "";
            return;
        }
        float a = age < 200 ? age / 200f : age > 2200 ? 1 - (age - 2200) / 400f : 1f;
        a = Ui.clamp(a);
        toast.setTextSize(24 * k);
        toast.setTextAlign(Paint.Align.CENTER);
        toast.setAlpha((int) (255 * a));
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, 0.72f * a));
        float tw = toast.measureText(toastText) + 60 * k;
        r.set(w / 2 - tw / 2, h - 120 * k, w / 2 + tw / 2, h - 60 * k);
        c.drawRoundRect(r, 30 * k, 30 * k, fill);
        c.drawText(toastText, w / 2, r.centerY() + Ui.halfCap(toast), toast);
        toast.setAlpha(255);
        main.postDelayed(this::invalidate, 240);
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) activate();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) back();
                return true;
            case KeyEvent.KEYCODE_HOME:
                // Already the home screen: the system does not re-deliver the intent, so take it here.
                if (down) reset(true);
                return true;
            default:
                return false;
        }
    }

    private void move(int dx) {
        if (zone == Z_BAR) {
            barIndex = Math.max(0, Math.min(GEAR, barIndex + dx));
            invalidate();
            return;
        }
        if (rows.isEmpty()) return;
        Row row = rows.get(rowIndex);
        if (row.items.isEmpty()) return;
        int next = colIndex;
        if (dx < 0) {
            next = colIndex == SEE_ALL ? row.items.size() - 1 : Math.max(0, colIndex - 1);
        } else {
            if (colIndex == SEE_ALL) return;
            next = colIndex >= row.items.size() - 1 ? (row.seeTab >= 0 ? SEE_ALL : colIndex) : colIndex + 1;
        }
        if (next != colIndex) {
            colIndex = next;
            row.col = next;
            ensureWindow(row);
            invalidate();
            requestFrame();
        }
    }

    private void move(int dx, int dy) {
        if (zone == Z_BAR) {
            if (dy > 0 && !rows.isEmpty()) {
                zone = Z_ROW;
                rowIndex = 0;
                enterRow();
                invalidate();
            }
            return;
        }
        if (rows.isEmpty()) return;
        if (dy < 0) {
            if (rowIndex == 0) {
                zone = Z_BAR;
                barIndex = tab;
                invalidate();
                return;
            }
            rowIndex--;
            enterRow();
            invalidate();
            return;
        }
        if (rowIndex < rows.size() - 1) {
            rowIndex++;
            enterRow();
            invalidate();
        }
    }

    /** The focus lands on the shelf: same column as the shelf above, inside the window. */
    private void enterRow() {
        Row row = rows.get(rowIndex);
        colIndex = Math.max(0, Math.min(colIndex, row.items.size() - 1));
        row.col = colIndex;
        ensureWindow(row);
        reveal();
    }

    private void ensureWindow(Row row) {
        int n = cols(row.kind);
        if (row.col < 0) return;
        if (row.col < row.win) row.win = row.col;
        if (row.col >= row.win + n) row.win = row.col - n + 1;
        row.win = Math.max(0, Math.min(row.win, Math.max(0, row.items.size() - n)));
        row.slideTo = row.win * step(row.kind);
        requestFrame();
    }

    private void activate() {
        if (zone == Z_BAR) {
            if (barIndex == GEAR) {
                RectF box = barRects[GEAR];
                android.app.Activity a = activity();
                if (a != null) HomeApps.open(a, HomeApps.settings(), this, Math.round(box.left), Math.round(box.top),
                        Math.round(box.width()), Math.round(box.height()));
                return;
            }
            selectTab(barIndex);
            return;
        }
        if (rows.isEmpty()) return;
        Row row = rows.get(rowIndex);
        if (colIndex == SEE_ALL) {
            if (row.seeTab >= 0) selectTab(row.seeTab);
            return;
        }
        if (colIndex < 0 || colIndex >= row.items.size()) return;
        open(row.items.get(colIndex), row);
    }

    private void open(Tile t, Row row) {
        android.app.Activity a = activity();
        if (t.app != null) {
            if (a == null) return;
            int i = Math.max(0, colIndex);
            float x = MARGIN * k + i * step(row.kind) - row.slide;
            float y = BAR_H * k + 16 * k + row.y + HEAD_H * k - offset;
            if (!HomeApps.open(a, t.app, this, Math.round(x), Math.round(y),
                    Math.round(tw(row.kind)), Math.round(th(row.kind)))) {
                showToast("Impossible d'ouvrir " + t.title + ".");
            }
            return;
        }
        if (t.series && !t.seriesId.isEmpty()) {
            Intent it = new Intent(getContext(), Browse.class);
            it.putExtra(Browse.EXTRA_SERIES, t.seriesId);
            getContext().startActivity(it);
            return;
        }
        if (t.url.isEmpty()) return;
        Intent it = new Intent(getContext(), Player.class);
        it.putExtra(Player.EXTRA_URL, t.url);
        it.putExtra(Player.EXTRA_NAME, t.title);
        it.putExtra(Player.EXTRA_LOGO, t.image);
        getContext().startActivity(it);
    }

    private void selectTab(int index) {
        tab = Math.max(0, Math.min(TAB_COUNT - 1, index));
        barIndex = tab;
        zone = Z_ROW;
        rowIndex = 0;
        colIndex = 0;
        offset = offsetTo = 0;
        rows.clear();
        rebuild();
        requestFrame();
        invalidate();
    }

    private void back() {
        if (zone == Z_BAR) return;
        if (offsetTo > 2) {
            offsetTo = 0;
            offset = 0;
            rowIndex = 0;
            colIndex = 0;
            if (!rows.isEmpty()) rows.get(0).col = 0;
            invalidate();
            return;
        }
        zone = Z_BAR;
        barIndex = tab;
        invalidate();
    }

    private android.app.Activity activity() {
        Context c = getContext();
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // MARK: - Text helpers

    /** The string, cut with an ellipsis to fit the width. */
    private static String fit(Paint p, String s, float maxWidth) {
        if (s == null) return "";
        if (p.measureText(s) <= maxWidth) return s;
        float dots = p.measureText("…");
        int n = p.breakText(s, true, Math.max(0, maxWidth - dots), null);
        return s.substring(0, Math.max(0, n)).trim() + "…";
    }

    private static List<String> wrap(String s, Paint p, float maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String rest = s.trim();
        while (!rest.isEmpty() && out.size() < maxLines) {
            int n = p.breakText(rest, true, maxWidth, null);
            if (n <= 0) break;
            if (n < rest.length()) {
                int space = rest.lastIndexOf(' ', n);
                if (space > 0) n = space;
            }
            out.add(rest.substring(0, n).trim());
            rest = rest.substring(n).trim();
        }
        if (!rest.isEmpty() && !out.isEmpty()) {
            int last = out.size() - 1;
            out.set(last, fit(p, out.get(last) + "…", maxWidth));
        }
        return out;
    }

    // MARK: - Panel names

    /** "FR || TF1 [HEVC]" is a data label, not a title for the sofa. */
    static String pretty(String name) {
        if (name == null) return "";
        String t = name.replace("[#]", "").replace("|FR|", "").replace("FR ||", "").replace("FR||", "").replace("FR:", "").trim();
        t = t.replaceAll("\\s*\\[(HEVC|FHD|UHD|4K|HD|SD|RAW|BACKUP)]", "");
        t = t.replaceAll("\\s*\\((FHD|UHD|4K|HD|SD|RAW|BACKUP)\\)", "");
        t = t.replaceAll("\\s{2,}", " ").trim();
        while (t.startsWith("#") || t.startsWith("-") || t.startsWith("·") || t.startsWith("[") || t.startsWith("]")) {
            t = t.substring(1).trim();
        }
        t = t.replaceAll("(?i)\\s+(FHD|UHD|4K|HD|SD|RAW|BACKUP|MULTI)\\s*$", "").trim();
        return t.isEmpty() ? name.trim() : t;
    }

    /** A channel name without its quality suffix, to spot the same channel twice. */
    static String norm(String name) {
        if (name == null) return "";
        String t = name.toUpperCase(Locale.ROOT)
                .replace("FULL HD", "").replace("FHD", "").replace("UHD", "").replace("4K", "")
                .replace("HD", "").replace("SD", "").replace("RAW", "").replace("BACKUP", "")
                .replace("FR:", "").replace("|FR|", "").replace("FR |", "");
        return t.replaceAll("[^A-Z0-9]", "");
    }

    /** The panel decorates its category names: keep the word. */
    static String clean(String name) {
        if (name == null) return "";
        String t = name.replace("✧", "").replace("･", "").replace("ﾟ", "").replace("─", "").replace("||", "");
        while (t.startsWith(":") || t.startsWith("*") || t.startsWith("-") || t.startsWith("◇") || t.startsWith("·")) {
            t = t.substring(1);
        }
        t = t.trim();
        return t.isEmpty() ? name.trim() : t;
    }

    /** The French channels first: the home screen should not open on a Belgian shopping channel. */
    private static List<Iptv.Item> frenchChannels(Iptv iptv, int max) {
        List<Iptv.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Iptv.Item> all = iptv.live();
        for (Iptv.Category c : iptv.liveCategories()) {
            if (!c.name.toUpperCase(Locale.ROOT).contains("FRANCE")) continue;
            for (Iptv.Item i : all) {
                if (!c.id.equals(i.group) || i.url.isEmpty()) continue;
                if (i.name.startsWith("─") || i.name.startsWith("=") || i.name.contains("✧")) continue;
                if (!seen.add(norm(i.name))) continue;
                out.add(i);
                if (out.size() >= max) return out;
            }
        }
        if (out.isEmpty()) {
            for (Iptv.Item i : all) {
                if (i.url.isEmpty()) continue;
                out.add(i);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    private static List<Iptv.Item> firstOf(List<Iptv.Item> items, int max) {
        List<Iptv.Item> out = new ArrayList<>();
        for (Iptv.Item i : items) {
            out.add(i);
            if (out.size() >= max) break;
        }
        return out;
    }
}
