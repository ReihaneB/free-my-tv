package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The home screen, in the clothes of Infuse 8 on the Apple TV: a search field and three capsule buttons on top, then
 * shelves of large 16:9 cards with their title centred underneath and an orange progress bar where a film was left.
 *
 * <p>The first shelf is what is being watched, the second is the libraries (Fims, Séries, Live, Apps, Entrées — the
 * Favorites row of Infuse), then the account's shelves. Opening a library swaps the top bar for a back button and the
 * library's name, and shows that library's rows; Back comes home. Everything is drawn on the canvas from the account
 * cached on disk, so the screen opens with no network.
 */
final class HomeView extends View implements Choreographer.FrameCallback, Iptv.Listener {

    // MARK: - Model

    /** One card: a film, an episode, a channel, an app, an input, or one of the library tiles. */
    private static final class Tile {
        String title = "", meta = "", image = "", url = "", seriesId = "", symbol = "";
        Bitmap art;                 // apps: the ready 16:9 card
        HomeApps.Item app;          // apps and inputs open through HomeApps
        float progress;             // continue watching: 0..1
        boolean series, live;
        int color;                  // library tile
        int action = -1;            // >= 0: open that library tab; TO_SETTINGS / TO_BROWSE
    }

    private static final class Row {
        String title = "";
        int kind, seeTab = -1;
        final List<Tile> items = new ArrayList<>();
        float y, h;
        int col, win;
        float slide, slideTo;
        float[] focus = new float[0];
    }

    private static final int R_CARD = 0, R_HERO = 1;
    private static final int Z_ROW = 0, Z_BAR = 1;
    /** col value of the "Tout voir" label at the end of a shelf. */
    private static final int SEE_ALL = -2;

    private static final String[] LIBRARIES = {"Accueil", "Films", "Séries", "Direct", "Apps"};
    private static final int TAB_COUNT = LIBRARIES.length;

    // Chrome stops, in the order they are laid out.
    private static final int CH_SEARCH = 0, CH_APPS = 1, CH_SYNC = 2, CH_GEAR = 3;
    private static final int CH_TAB = 100;   // + index of the library

    // MARK: - Geometry (designed at 1920×1080, scaled by k)

    private static final float BAR_H = 104, MARGIN = 96;
    private static final float HEAD_H = 40, ROW_GAP = 46, CAP_H = 70;
    private static final float CARD_W = 400, CARD_H = 225, CARD_GAP = 30;
    private static final float HERO_W = 545, HERO_H = 306, HERO_GAP = 30;
    private static final float PILL_W = 300, PILL_H = 58, BUTTON_W = 78, BUTTON_H = 58, BUTTON_GAP = 14;
    private static final float LIB_TITLE_H = 96;      // the library's name above its shelves

    // Infuse 8's slate background, not black.
    private static final int BG_TOP = 0xFF2E3A41, BG_BOTTOM = 0xFF1A242C;
    private static final int INK = 0xFFFFFFFF, DIM = 0xFFB8C4C9, FAINT = 0xFF8798A0, ACCENT = 0xFFFF8A00;
    private static final int GLASS = 0x1FFFFFFF, GLASS_ON = 0x33FFFFFF, HAIR = 0x33FFFFFF;

    private float k = 1f, w, h;
    private LinearGradient bg;

    // MARK: - State

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Float> progress = new HashMap<>();
    private int tab, zone = Z_ROW, rowIndex, colIndex, chromeIndex;
    private float offset, offsetTo;
    private boolean resumed, framePosted;
    private long lastFrame;
    private int prefsVersion = Prefs.version;
    private String dataKey = "";
    private EditText field;                 // the activity's search box, laid over the pill
    private boolean searchOpen, seriesMode;
    private String seriesTitle = "", query = "";
    private List<Iptv.Item> episodes = new ArrayList<>();

    private final RectF[] chromeRects = new RectF[4];
    private final RectF[] tabRects = new RectF[TAB_COUNT];
    private final int[] chromeOrder = new int[1 + TAB_COUNT + 3];
    private int chromeCount;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            invalidate();
            if (resumed) main.postDelayed(this, 20_000);
        }
    };

    // MARK: - Paints

    private Paint rowTitle, cardTitle, cardMeta, seeAll, pillInk, tabInk, toast, empty;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private static final float BLUR = 2f;       // the band under the bar, drawn 2× smaller
    private static final float LENS_EXTRA = 26f; // rows below the edge the lens may look at

    private Bitmap blurBmp;
    private Canvas blurCanvas;
    private int[] stripPx, stripTmp;
    private float blurLag = -1;
    private int lastShapeSum = -1;
    private Glass glass;
    private Bitmap glassBmp;
    private boolean glassTried;
    private final float[] shapes = new float[Glass.MAX_SHAPES * 5];
    private int shapeCount;
    private int shapeSum;
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF blurDst = new RectF();
    private long sheenAt;
    private Bitmap backdrop;          // the slate background + the focused artwork's wash, at 1/4: cheap to blit
    private String backdropKey = "";
    private float blurOffset = -1;    // the strip is only redrawn when what it covers changes
    private String blurKey = "";
    private static final ColorMatrixColorFilter SATURATE = saturation(1.75f);

    private static ColorMatrixColorFilter saturation(float s) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(s);
        return new ColorMatrixColorFilter(m);
    }

    private final Path clip = new Path();
    private final RectF r = new RectF(), r2 = new RectF(), dst = new RectF(), artBox = new RectF();
    private final android.graphics.Rect srcRect = new android.graphics.Rect();
    private final Map<String, Bitmap> softs = new HashMap<>();
    private final Map<String, Bitmap> washes = new HashMap<>();   // url → tiny copy, used as a soft wash
    private String toastText = "";
    private long toastAt;

    // MARK: - Lifecycle

    HomeView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        Ui.init(context);
        paints();
        for (int i = 0; i < chromeRects.length; i++) chromeRects[i] = new RectF();
        for (int i = 0; i < tabRects.length; i++) tabRects[i] = new RectF();
        rebuild();
    }

    private void paints() {
        rowTitle = Ui.text(Ui.semibold, 26, 0xFFE7EDEE);
        cardTitle = Ui.text(Ui.medium, 21, 0xFFFFFFFF);
        cardMeta = Ui.text(Ui.regular, 17, 0xFF9AA8AE);
        seeAll = Ui.text(Ui.medium, 20, 0xFFB8C4C9);
        tabInk = Ui.text(Ui.medium, 20, 0xFFFFFFFF);
        pillInk = Ui.text(Ui.regular, 22, 0xFFB0BCC1);
        toast = Ui.text(Ui.medium, 22, INK);
        empty = Ui.text(Ui.medium, 24, FAINT);
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
        zone = Z_ROW;
        rowIndex = 0;
        colIndex = 0;
        chromeIndex = 0;
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
        if (seriesMode) {
            seasonRows();
        } else if (Iptv.configured()) {
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
            row.kind = R_CARD;
            addApps(row);
            if (!row.items.isEmpty()) rows.add(row);
        }
        int restored = -1;
        if (keep != null) for (int i = 0; i < rows.size(); i++) if (rows.get(i).title.equals(keep)) { restored = i; break; }
        rowIndex = restored >= 0 ? restored : Math.min(rowIndex, Math.max(0, rows.size() - 1));
        for (Row row : rows) {
            if (row.col < 0) row.col = 0;
            row.col = Math.min(row.col, Math.max(0, row.items.size() - 1));
            row.focus = new float[row.items.size()];
            row.win = Math.max(0, Math.min(row.win, Math.max(0, row.items.size() - 1)));
            row.slide = row.slideTo = row.win * step(row.kind);
        }
        layout();
        requestFrame();   // rebuild() zeroes the per-card focus: nothing else would start the animation again
        invalidate();
    }

    private void homeRows() {
        Iptv iptv = Iptv.get(getContext());

        // The hero shelf: what was left unfinished, then a new series, then a channel — four cards, all speaking.
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

        Row watching = new Row();
        watching.title = "En cours";
        watching.kind = R_CARD;
        for (Iptv.Item i : Prefs.resume("vod")) watching.items.add(resumeTile(i));
        for (Iptv.Item i : Prefs.resume("series")) watching.items.add(resumeTile(i));
        if (!watching.items.isEmpty()) rows.add(watching);

        // Where Infuse shows its Favorites, the TV's apps: Netflix, SmartTube, Twitch, Prime, M6+, Steam Link…
        Row apps = new Row();
        apps.title = "Applications";
        apps.kind = R_CARD;
        apps.seeTab = 4;
        addApps(apps);
        if (!apps.items.isEmpty()) rows.add(apps);

        Row inputs = new Row();
        inputs.title = "Entrées";
        inputs.kind = R_CARD;
        inputs.seeTab = 4;
        addInputs(inputs);
        if (!inputs.items.isEmpty()) rows.add(inputs);

        shelf("Nouveautés films", iptv.fresh("vod", 30, 24), false, 1);
        shelf("Nouvelles séries", iptv.fresh("series", 60, 24), true, 2);
        shelf("Chaînes", frenchChannels(iptv, 24), false, 3);
        shelf("Les mieux notés", iptv.best("vod", 24), false, 1);
        shelf("Séries", firstOf(iptv.series(), 24), true, 2);
    }

    private void filmRows() {
        Iptv iptv = Iptv.get(getContext());
        shelf("Nouveautés", iptv.fresh("vod", 30, 24), false, -1);
        shelf("Les mieux notés", iptv.best("vod", 24), false, -1);
        List<Iptv.Item> all = iptv.movies();
        int built = 0;
        for (Iptv.Category c : iptv.movieCategories()) {
            if (built >= 12) break;
            List<Iptv.Item> items = new ArrayList<>();
            for (Iptv.Item i : all) {
                if (!c.id.equals(i.group) || i.name.startsWith("─") || i.name.contains("✧")) continue;
                items.add(i);
                if (items.size() >= 24) break;
            }
            if (items.size() < 4) continue;
            shelf(clean(c.name), items, false, -1);
            built++;
        }
    }

    private void seriesRows() {
        Iptv iptv = Iptv.get(getContext());
        shelf("Nouvelles séries", iptv.fresh("series", 60, 24), true, -1);
        shelf("Les mieux notées", iptv.best("series", 24), true, -1);
        List<Iptv.Item> all = iptv.series();
        int built = 0;
        for (Iptv.Category c : iptv.seriesCategories()) {
            if (built >= 12) break;
            List<Iptv.Item> items = new ArrayList<>();
            for (Iptv.Item i : all) {
                if (!c.id.equals(i.group)) continue;
                items.add(i);
                if (items.size() >= 24) break;
            }
            if (items.size() < 4) continue;
            shelf(clean(c.name), items, true, -1);
            built++;
        }
    }

    private void liveRows() {
        Iptv iptv = Iptv.get(getContext());
        shelf("Chaînes françaises", frenchChannels(iptv, 24), false, -1);
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
                if (items.size() >= 24) break;
            }
            if (items.size() < 4) continue;
            shelf(clean(c.name), items, false, -1);
            built++;
        }
    }

    private void appRows() {
        Row apps = new Row();
        apps.title = "Applications";
        apps.kind = R_CARD;
        addApps(apps);
        if (!apps.items.isEmpty()) rows.add(apps);
        Row inputs = new Row();
        inputs.title = "Entrées";
        inputs.kind = R_CARD;
        addInputs(inputs);
        if (!inputs.items.isEmpty()) rows.add(inputs);
    }

    private void shelf(String title, List<Iptv.Item> items, boolean series, int seeTab) {
        if (items.isEmpty()) return;
        Row row = new Row();
        row.title = title;
        row.kind = R_CARD;
        row.seeTab = seeTab;
        Set<String> seen = new HashSet<>();
        for (Iptv.Item i : items) {
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

    /** A saved position has the title and the stream but no artwork and no year: take both from the catalogue. */
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
        bg = new LinearGradient(0, 0, 0, height, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP);
        layout();
        offset = offsetTo;
        for (Row row : rows) row.slide = row.slideTo;
    }

    private float tw(int kind) {
        return (kind == R_HERO ? HERO_W : CARD_W) * k;
    }

    private float th(int kind) {
        return (kind == R_HERO ? HERO_H : CARD_H) * k;
    }

    private float gap(int kind) {
        return (kind == R_HERO ? HERO_GAP : CARD_GAP) * k;
    }

    private float step(int kind) {
        return tw(kind) + gap(kind);
    }

    private int cols(int kind) {
        float usable = w - 2 * MARGIN * k;
        return Math.max(1, (int) ((usable + gap(kind)) / step(kind)));
    }

    private float capH(int kind) {
        return (kind == R_HERO ? 18 : CAP_H) * k;
    }

    private float contentTop() {
        return (tab == 0 && !seriesMode ? 0 : LIB_TITLE_H * k);
    }

    private void layout() {
        float y = contentTop();
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
        float top = 26 * k, hh = PILL_H * k, m = MARGIN * k;
        chromeCount = 0;
        chromeRects[CH_SEARCH].set(m, top, m + PILL_W * k, top + hh);
        chromeOrder[chromeCount++] = CH_SEARCH;

        tabInk.setTextSize(20 * k);
        float tx = m + PILL_W * k + 44 * k;
        for (int i = 0; i < TAB_COUNT; i++) {
            float twi = tabInk.measureText(LIBRARIES[i]) + 30 * k;
            tabRects[i].set(tx, top, tx + twi, top + hh);
            tx += twi + 6 * k;
            chromeOrder[chromeCount++] = CH_TAB + i;
        }

        float bw = BUTTON_W * k, right = w - m;
        chromeRects[CH_GEAR].set(right - bw, top, right, top + hh);
        chromeRects[CH_SYNC].set(right - 2 * bw - BUTTON_GAP * k, top, right - bw - BUTTON_GAP * k, top + hh);
        chromeRects[CH_APPS].set(right - 3 * bw - 2 * BUTTON_GAP * k, top, right - 2 * bw - 2 * BUTTON_GAP * k, top + hh);
        chromeOrder[chromeCount++] = CH_APPS;
        chromeOrder[chromeCount++] = CH_SYNC;
        chromeOrder[chromeCount++] = CH_GEAR;
        chromeIndex = Math.max(0, Math.min(chromeIndex, chromeCount - 1));
    }

    private float contentH() {
        if (rows.isEmpty()) return contentTop();
        Row last = rows.get(rows.size() - 1);
        return last.y + last.h;
    }

    private float maxOffset() {
        return Math.max(0, contentH() - (h - BAR_H * k - 24 * k));
    }

    private void clampOffset() {
        offsetTo = Math.max(0, Math.min(maxOffset(), offsetTo));
    }

    /** The focused shelf sits right under the bar, so the shelf below always peeks at the bottom. */
    private void reveal() {
        if (rows.isEmpty()) return;
        offsetTo = rows.get(rowIndex).y - 4 * k;
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
        if (blurLag < 0) blurLag = offset;
        if (Math.abs(offset - blurLag) > 0.3f) {
            blurLag += (offset - blurLag) * Math.min(1f, dt * 20f);   // the material follows a beat late
            moving = true;
        } else {
            blurLag = offset;
        }
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
        if (sheenAt != 0 && System.currentTimeMillis() - sheenAt < 640) moving = true;
        if (moving) requestFrame();
    }

    // MARK: - Drawing

    /**
     * The slate background, warmed by the focused artwork's wash. It only changes when the focus moves or a poster
     * lands, so it is built once into a quarter-size bitmap and blitted: stretching the whole wash every frame is
     * what made the shelves stutter.
     */
    private void ensureBackdrop() {
        String key = "";
        Bitmap low = null;
        if (!rows.isEmpty() && zone == Z_ROW) {
            Row row = rows.get(rowIndex);
            if (row.col >= 0 && row.col < row.items.size()) {
                Tile t = row.items.get(row.col);
                key = rowIndex + ":" + row.col + ":" + t.image;
                low = wash(t);
            }
        }
        int bw = Math.max(4, Math.round(w / 4)), bh = Math.max(4, Math.round(h / 4));
        if (backdrop == null || backdrop.getWidth() != bw || backdrop.getHeight() != bh) {
            if (backdrop != null) backdrop.recycle();
            backdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            backdropKey = "";
        }
        if (key.equals(backdropKey)) return;
        backdropKey = key;
        Canvas bc = new Canvas(backdrop);
        bc.scale(1f / 4, 1f / 4);
        fill.setAlpha(255);
        fill.setShader(bg);
        bc.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        if (low != null) {
            blurDst.set(0, 0, w, h * 0.72f);
            art.setAlpha(170);
            bc.drawBitmap(low, null, blurDst, art);
            art.setAlpha(255);
            fill.setAlpha(255);
            fill.setShader(new LinearGradient(0, 0, 0, h * 0.62f, 0x00000000, BG_BOTTOM, Shader.TileMode.CLAMP));
            bc.drawRect(0, 0, w, h * 0.62f, fill);
            fill.setShader(null);
        }
    }

    /**
     * The wash behind everything: the artwork, downscaled to 160x90 and blurred there, where it costs nothing.
     * Stretching it afterwards is smooth because there is no hard edge left in it — a 26-row thumbnail stretched
     * over 200 rows was the pixel soup this replaces.
     */
    private Bitmap wash(Tile t) {
        if (t == null || t.image == null || t.image.isEmpty()) return null;
        Bitmap c = washes.get(t.image);
        if (c != null) return c;
        Bitmap low = soft(t);
        if (low == null) return null;
        int bw = 160, bh = 90;
        c = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(c);
        float sc = Math.max(bw / (float) low.getWidth(), bh / (float) low.getHeight());
        float dw = low.getWidth() * sc, dh = low.getHeight() * sc;
        srcRect.set(0, 0, low.getWidth(), low.getHeight());
        cc.drawBitmap(low, srcRect, new RectF((bw - dw) / 2, (bh - dh) / 2, (bw + dw) / 2, (bh + dh) / 2), art);
        int[] p = new int[bw * bh], tmp = new int[bw * bh];
        c.getPixels(p, 0, bw, 0, 0, bw, bh);
        box(p, tmp, bw, bh, 3);
        box(p, tmp, bw, bh, 3);
        c.setPixels(p, 0, bw, 0, 0, bw, bh);
        if (washes.size() > 8) washes.clear();
        washes.put(t.image, c);
        return c;
    }

    private void drawScene(Canvas c) {
        ensureBackdrop();
        blurDst.set(0, 0, w, h);
        c.drawBitmap(backdrop, null, blurDst, art);

        float barH = BAR_H * k;
        float top = barH + 18 * k - offset;
        if (rows.isEmpty()) {
            empty.setTextSize(24 * k);
            String msg = searchOpen
                    ? query.length() < 2 ? "Tapez au moins deux lettres." : "Aucun résultat pour « " + query + " »."
                    : seriesMode ? "Chargement des épisodes…"
                    : "Aucun compte IPTV : renseignez-le dans les réglages.";
            c.drawText(msg, MARGIN * k, top + 60 * k, empty);
        } else {
            if (tab != 0 || seriesMode) {
                rowTitle.setTextSize(44 * k);
                rowTitle.setTextAlign(Paint.Align.LEFT);
                c.drawText(seriesMode ? seriesTitle : LIBRARIES[tab], MARGIN * k, top + 52 * k, rowTitle);
            }
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                float y = top + row.y;
                if (y > h || y + row.h < barH) continue;
                drawRow(c, row, i, y);
            }
        }
    }

    private long lastGlassAt;

    @Override protected void onDraw(Canvas c) {
        drawScene(c);
        drawBar(c);
        drawToast(c);
    }

    /**
     * Liquid Glass needs a real blur of what is behind the chrome. Android 11 has no RenderEffect, so the band the
     * bar covers is drawn once into a bitmap 12× smaller and stretched back: a cheap, honest gaussian-ish blur.
     */
    private void updateBlur() {
        boolean dirty = !backdropKey.equals(blurKey) || shapeSum != lastShapeSum;
        boolean moved = Math.abs(blurLag - blurOffset) >= 0.4f;
        boolean atRest = Math.abs(blurLag - offset) < 0.6f;
        long now = SystemClock.uptimeMillis();
        // While the shelves are moving the bar keeps the glass it already has: behind it the picture is a blur,
        // so a strip that is one frame (or ten) old is invisible — and this pass is the most expensive thing on
        // the frame. At most one refresh every 120 ms in motion, and one the moment it settles.
        if (blurBmp != null && !dirty && (!moved || !atRest)) return;
        lastShapeSum = shapeSum;
        blurOffset = blurLag;
        blurKey = backdropKey;
        lastGlassAt = now;
        float barH = BAR_H * k;
        float bandH = barH + LENS_EXTRA * k;   // the extra rows are what the lens bends up into the glass

        // the band, 4× smaller
        int bw = Math.max(8, Math.round(w / BLUR));
        int bh = Math.max(8, Math.round(bandH / BLUR));
        if (blurBmp == null || blurBmp.getWidth() != bw || blurBmp.getHeight() != bh) {
            if (blurBmp != null) blurBmp.recycle();
            blurBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            blurCanvas = new Canvas(blurBmp);
            stripPx = new int[bw * bh];
            stripTmp = new int[bw * bh];
        }
        blurCanvas.save();
        blurCanvas.scale(1f / BLUR, 1f / BLUR);
        blurCanvas.clipRect(0, 0, w, bandH);
        blurCanvas.translate(0, blurLag - offset);
        drawScene(blurCanvas);
        blurCanvas.restore();
        // the GPU does the rest: blur, the bend at the edge, the red/blue split and the saturation, one pass
        if (!glassTried) {
            glassTried = true;
            glass = new Glass();
            boolean ok = glass.init();
            AppLog.w("glass: shader " + (ok ? "ready" : "failed, falling back to the CPU blur"));
            if (!ok) glass = null;
        }
        glassBmp = null;
        if (glass != null) {
            glassBmp = glass.render(blurBmp, shapes, shapeCount, 18 * k / BLUR, 1.75f);
        }
        if (glassBmp == null) {
            blurBmp.getPixels(stripPx, 0, bw, 0, 0, bw, bh);
            box(stripPx, stripTmp, bw, bh, 2);
            box(stripPx, stripTmp, bw, bh, 2);
            blurBmp.setPixels(stripPx, 0, bw, 0, 0, bw, bh);
        }
    }

    /** Separable box blur: three passes of it is a gaussian to the eye, and it costs nothing at this size. */
    private static void box(int[] px, int[] tmp, int bw, int bh, int r) {
        for (int y = 0; y < bh; y++) {
            int row = y * bw;
            for (int x = 0; x < bw; x++) {
                int a = 0, rr = 0, gg = 0, bb = 0, n = 0;
                for (int d = -r; d <= r; d++) {
                    int xx = x + d;
                    if (xx < 0) xx = 0; else if (xx >= bw) xx = bw - 1;
                    int c = px[row + xx];
                    a += c >>> 24; rr += (c >> 16) & 255; gg += (c >> 8) & 255; bb += c & 255; n++;
                }
                tmp[row + x] = ((a / n) << 24) | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
        }
        for (int x = 0; x < bw; x++) {
            for (int y = 0; y < bh; y++) {
                int a = 0, rr = 0, gg = 0, bb = 0, n = 0;
                for (int d = -r; d <= r; d++) {
                    int yy = y + d;
                    if (yy < 0) yy = 0; else if (yy >= bh) yy = bh - 1;
                    int c = tmp[yy * bw + x];
                    a += c >>> 24; rr += (c >> 16) & 255; gg += (c >> 8) & 255; bb += c & 255; n++;
                }
                px[y * bw + x] = ((a / n) << 24) | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
        }
    }

    /** The bar: the blurred band, a frosted tint, a lit top edge, a hairline and a soft shadow under it. */
    private void drawBar(Canvas c) {
        float barH = BAR_H * k;
        float bandH = barH + LENS_EXTRA * k;
        // everything that is glass in the chrome: the bar, the search pill, the capsules, the focused tab
        shapeCount = 0;
        addShape(w / 2, (barH - 4000 * k) / 2, w, (barH + 4000 * k) / 2, 0);
        addShape(chromeRects[CH_SEARCH], chromeRects[CH_SEARCH].height() / 2);
        addShape(chromeRects[CH_APPS], 20 * k);
        addShape(chromeRects[CH_SYNC], 20 * k);
        addShape(chromeRects[CH_GEAR], 20 * k);
        for (int i = 0; i < TAB_COUNT; i++) if (focused(CH_TAB + i)) addShape(tabRects[i], 18 * k);
        shapeSum = 0;
        for (int i = 0; i < shapeCount * 5; i++) shapeSum = shapeSum * 31 + Math.round(shapes[i]);
        updateBlur();

        if (glassBmp != null) {
            // the shader already blurred, bent and saturated it: crop the band to the bar and draw
            int gh = glassBmp.getHeight();
            srcRect.set(0, 0, glassBmp.getWidth(), Math.max(1, Math.round(gh * (barH / bandH))));
            blurDst.set(0, 0, w, barH);
            c.drawBitmap(glassBmp, srcRect, blurDst, blurPaint);
        } else {
            blurDst.set(0, 0, w, bandH);
            blurPaint.setColorFilter(SATURATE);   // Liquid Glass keeps behind it alive
            c.drawBitmap(blurBmp, null, blurDst, blurPaint);
            blurPaint.setColorFilter(null);
        }

        fill.setAlpha(255);
        fill.setShader(null);
        // smoked glass: the colour of the blur stays alive under a slight dark tint (a white veil would wash it out)
        fill.setColor(0x40090F14);
        c.drawRect(0, 0, w, barH, fill);
        fill.setShader(new LinearGradient(0, 0, 0, 30 * k, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, 30 * k, fill);
        fill.setShader(null);
        // no hairlines: the two rules the bar used to draw read as a frame, not as glass
        // and the soft shadow it casts on the content below (a hard band would read as a separator)
        fill.setShader(new LinearGradient(0, barH, 0, barH + 26 * k,
                new int[] {0x33000000, 0x14000000, 0x00000000}, new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, barH, w, barH + 26 * k, fill);
        fill.setShader(null);

        drawPill(c, chromeRects[CH_SEARCH], focused(CH_SEARCH));
        for (int i = 0; i < TAB_COUNT; i++) drawTab(c, i, focused(CH_TAB + i));
        drawCapsule(c, chromeRects[CH_APPS], "apps", focused(CH_APPS));
        drawCapsule(c, chromeRects[CH_SYNC], "sync", focused(CH_SYNC));
        drawCapsule(c, chromeRects[CH_GEAR], "settings", focused(CH_GEAR));
    }

    private void addShape(RectF b, float rad) {
        addShape(b.centerX(), b.centerY(), b.width() / 2, b.height() / 2, rad);
    }

    private void addShape(float cx, float cy, float hw, float hh, float rad) {
        if (shapeCount >= Glass.MAX_SHAPES) return;
        int o = shapeCount * 5;
        shapes[o] = cx / BLUR;
        shapes[o + 1] = cy / BLUR;
        shapes[o + 2] = hw / BLUR;
        shapes[o + 3] = hh / BLUR;
        shapes[o + 4] = rad / BLUR;
        shapeCount++;
    }

    private boolean focused(int id) {
        if (zone != Z_BAR) return false;
        return chromeOrder[Math.max(0, Math.min(chromeIndex, chromeCount - 1))] == id;
    }

    /**
     * One Liquid Glass surface: a soft shadow, the backdrop showing through a light tint, the specular crescent of a
     * lit top edge, a moving highlight when it takes the focus, and a hairline rim.
     */
    private void drawGlass(Canvas c, RectF box, float radius, boolean on, int tint) {
        float rad = radius;
        if (on) {
            dst.set(box);
            dst.offset(0, 8 * k);
            fill.setShader(null);
            fill.setAlpha(255);
            fill.setColor(0x4D000000);
            c.drawRoundRect(dst, rad, rad, fill);
        }
        clip.reset();
        clip.addRoundRect(box, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        boolean real = glassBmp != null;   // the shader put the backdrop inside this shape, only brighten it
        fill.setShader(null);
        fill.setColor(real ? (on ? 0x3DFFFFFF : 0x14FFFFFF) : (on ? 0x38FFFFFF : tint));
        c.drawRect(box, fill);
        if (!real) {
            fill.setAlpha(255);
            fill.setShader(new LinearGradient(0, box.top, 0, box.top + box.height() * 0.55f,
                    0x73FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(box, fill);
            fill.setShader(null);
            fill.setColor(0x5CFFFFFF);
            c.drawRect(box.left, box.top, box.right, box.top + 1.2f * k, fill);
            fill.setShader(new LinearGradient(0, box.bottom - box.height() * 0.35f, 0, box.bottom,
                    0x00000000, 0x26000000, Shader.TileMode.CLAMP));
            c.drawRect(box, fill);
            fill.setShader(null);
        }
        if (on) {
            float phase = sheen();
            if (phase >= 0) {
                float cx = box.left - box.width() * 0.4f + box.width() * 1.8f * phase;
                fill.setAlpha(255);
                fill.setShader(new LinearGradient(cx - box.width() * 0.45f, box.top, cx + box.width() * 0.45f, box.bottom,
                        new int[] {0x00FFFFFF, 0x3DFFFFFF, 0x00FFFFFF}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(box, fill);
                fill.setShader(null);
            }
        }
        c.restore();

        stroke.setStyle(Paint.Style.STROKE);
        if (on) {
            // a halo so the focus reads from the sofa, not only from close up
            stroke.setStrokeWidth(9 * k);
            stroke.setColor(0x14FFFFFF);
            c.drawRoundRect(box, rad, rad, stroke);
            stroke.setStrokeWidth(4 * k);
            stroke.setColor(0x26FFFFFF);
            c.drawRoundRect(box, rad, rad, stroke);
        }
        stroke.setStrokeWidth((on ? 1.8f : 1f) * k);
        stroke.setColor(on ? 0xDBFFFFFF : 0x38FFFFFF);
        c.drawRoundRect(box, rad, rad, stroke);
    }

    /** 0..1 while a focus highlight sweeps across a surface, -1 when it is done (keeps the CPU quiet at rest). */
    private float sheen() {
        long age = System.currentTimeMillis() - sheenAt;
        if (sheenAt == 0 || age < 0 || age > 620) return -1;
        return age / 620f;
    }

    private void drawPill(Canvas c, RectF box, boolean on) {
        drawGlass(c, box, box.height() / 2, on, 0x1FFFFFFF);

        float cx = box.left + 30 * k, cy = box.centerY();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.2f * k);
        stroke.setColor(on ? INK : DIM);
        c.drawCircle(cx, cy - 1 * k, 9 * k, stroke);
        c.drawLine(cx + 6.5f * k, cy + 6 * k, cx + 13 * k, cy + 12.5f * k, stroke);

        if (!searchOpen) {   // the EditText laid over the pill draws the hint itself when it is up
            pillInk.setTextSize(22 * k);
            pillInk.setTextAlign(Paint.Align.LEFT);
            pillInk.setColor(on ? 0xFFFFFFFF : 0xFFB0BCC1);
            c.drawText("Rechercher", box.left + 54 * k, box.centerY() + Ui.halfCap(pillInk), pillInk);
        }
    }

    /** A library name in the top bar: the one shown is white with an orange dot, the focused one becomes glass. */
    private void drawTab(Canvas c, int i, boolean focus) {
        RectF box = tabRects[i];
        if (focus) drawGlass(c, box, 18 * k, true, 0x1FFFFFFF);
        tabInk.setTextSize(20 * k);
        tabInk.setTextAlign(Paint.Align.CENTER);
        tabInk.setColor(i == tab ? INK : focus ? 0xFFFFFFFF : 0x8CFFFFFF);
        c.drawText(LIBRARIES[i], box.centerX(), box.centerY() + Ui.halfCap(tabInk) - 2 * k, tabInk);
        if (i == tab) {
            fill.setShader(null);
            fill.setAlpha(255);
            fill.setColor(ACCENT);
            c.drawCircle(box.centerX(), box.bottom - 9 * k, 3 * k, fill);
        }
    }

    private void drawCapsule(Canvas c, RectF box, String symbol, boolean on) {
        drawGlass(c, box, 20 * k, on, 0x1FFFFFFF);
        if (!symbol.isEmpty()) Symbols.draw(getContext(), c, symbol, box.centerX(), box.centerY(), 28 * k, on ? INK : DIM);
    }

    private void drawRow(Canvas c, Row row, int index, float y) {
        float m = MARGIN * k;
        rowTitle.setTextSize(26 * k);
        rowTitle.setTextAlign(Paint.Align.LEFT);
        float base = y + HEAD_H * k - 12 * k;
        c.drawText(row.title, m, base, rowTitle);

        boolean focusedRow = index == rowIndex && zone == Z_ROW;
        if (row.seeTab >= 0) {
            seeAll.setTextSize(20 * k);
            seeAll.setTextAlign(Paint.Align.RIGHT);
            boolean on = focusedRow && row.col == SEE_ALL;
            float tw2 = seeAll.measureText("Tout voir");
            if (on) {
                r.set(w - m - tw2 - 20 * k, base - Ui.cap(seeAll) - 9 * k, w - m + 12 * k, base + 9 * k);
                fill.setShader(null);
                fill.setColor(0xF2FFFFFF);
                c.drawRoundRect(r, 18 * k, 18 * k, fill);
                seeAll.setColor(0xFF0B1013);
            } else {
                seeAll.setColor(0xFFB8C4C9);
            }
            c.drawText("Tout voir", w - m, base, seeAll);
            seeAll.setColor(0xFFB8C4C9);
        }

        int n = cols(row.kind);
        float s = step(row.kind), tw = tw(row.kind), th = th(row.kind);
        float tilesTop = y + HEAD_H * k;
        for (int j = row.win; j < n + row.win && j < row.items.size(); j++) {
            Tile t = row.items.get(j);
            float x = m + j * s - row.slide;
            r.set(x, tilesTop, x + tw, tilesTop + th);
            if (r.right < m - 12 || r.left > w - m + 12) continue;
            float f = j < row.focus.length ? row.focus[j] : 0;
            if (row.kind == R_HERO) drawHero(c, r, t, f);
            else drawCard(c, r, t, f);
            if (row.kind != R_HERO) drawCaption(c, t, r, tilesTop + th + 8 * k);
        }
    }

    private void drawCard(Canvas c, RectF box, Tile t, float f) {
        float radius = 14 * k;
        float grow = 1 + 0.055f * f;
        r2.set(box);
        r2.inset(-box.width() * (grow - 1) / 2, -box.height() * (grow - 1) / 2);
        float rad = radius * grow;

        if (f > 0.02f) {
            dst.set(r2);
            dst.offset(0, 14 * k * f);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.35f * f));
            c.drawRoundRect(dst, rad, rad, fill);
        }

        clip.reset();
        clip.addRoundRect(r2, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        fill.setShader(null);
        fill.setColor(0xFF243038);
        c.drawRect(r2, fill);

        Bitmap b = artOf(t);
        if (b != null) {
            if (t.live) {
                artBox.set(r2);
                artBox.inset(26 * k, 26 * k);
                drawContain(c, b, artBox);
            } else {
                drawCover(c, b, r2);
            }
        } else {
            // No artwork: a quiet glyph, never the title — the caption under the card already has it.
            String glyph = !t.symbol.isEmpty() ? t.symbol : t.live ? "tv" : "play";
            Symbols.draw(getContext(), c, glyph, r2.centerX(), r2.centerY(), 54 * k, 0x59FFFFFF);
        }

        if (t.progress > 0.005f) {
            // A track across the card so a sliver of progress reads as progress, not as a stray dot.
            float barH = 4 * k, barY = r2.bottom - 3 * k;
            fill.setColor(0x4D000000);
            c.drawRect(r2.left, barY - barH, r2.right, barY, fill);
            fill.setColor(0x26FFFFFF);
            c.drawRect(r2.left, barY - barH, r2.right, barY - barH + 1 * k, fill);
            dst.set(r2.left, barY - barH, r2.left + Math.max(16 * k, r2.width() * t.progress), barY);
            fill.setColor(ACCENT);
            c.drawRoundRect(dst, barH / 2, barH / 2, fill);
        }
        if (f > 0.02f) {
            fill.setAlpha(255);
            fill.setShader(new LinearGradient(0, r2.top, 0, r2.top + r2.height() * 0.5f,
                    Ui.alpha(0xFFFFFFFF, 0.06f * f), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(r2, fill);
            fill.setShader(null);
        }
        c.restore();

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1 * k);
        stroke.setColor(0x22FFFFFF);
        c.drawRoundRect(r2, rad, rad, stroke);
        if (f > 0.02f) {
            stroke.setStrokeWidth(2.5f * k);
            stroke.setColor(Ui.alpha(0xFFFFFFFF, 0.92f * f));
            c.drawRoundRect(r2, rad, rad, stroke);
        }
    }

    /**
     * The card that speaks: artwork on the left, title, year and the button on the right, over a wash of the same
     * artwork. It is the hero of the first shelf, kept from the earlier design.
     */
    private void drawHero(Canvas c, RectF box, Tile t, float f) {
        float radius = 16 * k;
        float grow = 1 + 0.045f * f;
        r2.set(box);
        r2.inset(-box.width() * (grow - 1) / 2, -box.height() * (grow - 1) / 2);
        float rad = radius * grow;

        if (f > 0.02f) {
            dst.set(r2);
            dst.offset(0, 14 * k * f);
            fill.setShader(null);
            fill.setAlpha(255);
            fill.setColor(Ui.alpha(0xFF000000, 0.35f * f));
            c.drawRoundRect(dst, rad, rad, fill);
        }

        clip.reset();
        clip.addRoundRect(r2, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        fill.setShader(null);
        fill.setColor(0xFF20303A);
        c.drawRect(r2, fill);

        Bitmap b = artOf(t);
        Bitmap low = soft(t);
        if (low != null) {
            art.setAlpha(140);
            c.drawBitmap(low, null, r2, art);
            art.setAlpha(255);
        }
        fill.setAlpha(255);
        fill.setShader(new LinearGradient(r2.left, r2.top, r2.right, r2.top,
                0xE60B1116, 0x4D0B1116, Shader.TileMode.CLAMP));
        c.drawRect(r2, fill);
        fill.setShader(null);

        float pad = 24 * k;
        float artH = r2.height() - 2 * pad;
        float artW = artH * 2f / 3f;
        artBox.set(r2.left + pad, r2.top + pad, r2.left + pad + artW, r2.top + pad + artH);
        if (b != null) {
            drawContain(c, b, artBox);
        } else {
            Symbols.draw(getContext(), c, !t.symbol.isEmpty() ? t.symbol : t.live ? "tv" : "play",
                    artBox.centerX(), artBox.centerY(), 56 * k, 0x59FFFFFF);
        }

        float tx = artBox.right + 26 * k;
        float maxW = r2.right - pad - tx;
        cardTitle.setTextSize(29 * k);
        cardTitle.setTextAlign(Paint.Align.LEFT);
        List<String> lines = wrap(t.title, cardTitle, maxW, 2);
        float ty = r2.top + pad + 4 * k;
        for (String line : lines) {
            c.drawText(line, tx, ty + Ui.cap(cardTitle), cardTitle);
            ty += 36 * k;
        }
        cardMeta.setTextSize(19 * k);
        cardMeta.setTextAlign(Paint.Align.LEFT);
        String meta = t.meta == null ? "" : t.meta;
        if (!meta.isEmpty()) c.drawText(fit(cardMeta, meta, maxW), tx, ty + Ui.cap(cardMeta) + 2 * k, cardMeta);

        String label = t.series ? "Épisodes" : t.url.isEmpty() ? "" : t.live ? "En direct" : "Regarder";
        if (!label.isEmpty()) {
            cardTitle.setTextSize(20 * k);
            float bw = cardTitle.measureText(label) + 58 * k;
            float by = Math.min(r2.bottom - pad - 44 * k, ty + Ui.cap(cardMeta) + 26 * k);
            r.set(tx, by, tx + bw, by + 44 * k);
            fill.setColor(0xF2FFFFFF);
            c.drawRoundRect(r, 22 * k, 22 * k, fill);
            Symbols.draw(getContext(), c, "play", r.left + 25 * k, r.centerY(), 22 * k, 0xFF0B1116);
            cardTitle.setColor(0xFF0B1116);
            c.drawText(label, r.left + 44 * k, r.centerY() + Ui.halfCap(cardTitle), cardTitle);
            cardTitle.setColor(0xFFFFFFFF);
        }
        c.restore();

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(f > 0.02f ? 2.5f * k : 1 * k);
        stroke.setColor(f > 0.02f ? Ui.alpha(0xFFFFFFFF, 0.62f * f) : 0x22FFFFFF);
        c.drawRoundRect(r2, rad, rad, stroke);
    }

    /** A tiny copy of the artwork, stretched as a soft wash behind the hero card. */
    private Bitmap soft(Tile t) {
        if (t == null || t.image == null || t.image.isEmpty()) return null;
        Bitmap s = softs.get(t.image);
        if (s != null) return s;
        Bitmap src = artOf(t);
        if (src == null) return null;
        int hh = 96;
        int ww = Math.max(4, Math.round(hh * (float) src.getWidth() / Math.max(1, src.getHeight())));
        s = Bitmap.createScaledBitmap(src, ww, hh, true);
        if (softs.size() > 12) softs.clear();
        softs.put(t.image, s);
        return s;
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

    private void drawContain(Canvas c, Bitmap b, RectF box) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.min(box.width() / bw, box.height() / bh);
        float dw = bw * scale, dh = bh * scale;
        dst.set(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2);
        c.drawBitmap(b, null, dst, art);
    }

    private static int lighten(int color, float f) {
        int r = Math.min(255, Math.round(((color >> 16) & 255) * f));
        int g = Math.min(255, Math.round(((color >> 8) & 255) * f));
        int b = Math.min(255, Math.round((color & 255) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawCaption(Canvas c, Tile t, RectF box, float y) {
        cardTitle.setTextSize(21 * k);
        cardTitle.setTextAlign(Paint.Align.CENTER);
        cardMeta.setTextSize(17 * k);
        cardMeta.setTextAlign(Paint.Align.CENTER);
        float width = box.width() + 24 * k;
        c.drawText(fit(cardTitle, t.title, width), box.centerX(), y + Ui.cap(cardTitle), cardTitle);
        String meta = t.meta == null ? "" : t.meta;
        if (!meta.isEmpty()) {
            c.drawText(fit(cardMeta, meta, width), box.centerX(), y + Ui.cap(cardTitle) + 26 * k, cardMeta);
        }
        cardTitle.setTextAlign(Paint.Align.LEFT);
    }

    // MARK: - Top bar

    private void drawToast(Canvas c) {
        if (toastText == null || toastText.isEmpty()) return;
        long age = System.currentTimeMillis() - toastAt;
        if (age > 2600) {
            toastText = "";
            return;
        }
        float a = Ui.clamp(age < 200 ? age / 200f : age > 2200 ? 1 - (age - 2200) / 400f : 1f);
        toast.setTextSize(22 * k);
        toast.setTextAlign(Paint.Align.CENTER);
        toast.setAlpha((int) (255 * a));
        fill.setColor(Ui.alpha(0xFF000000, 0.7f * a));
        float tw = toast.measureText(toastText) + 56 * k;
        r.set(w / 2 - tw / 2, h - 116 * k, w / 2 + tw / 2, h - 60 * k);
        c.drawRoundRect(r, 28 * k, 28 * k, fill);
        c.drawText(toastText, w / 2, r.centerY() + Ui.halfCap(toast), toast);
        toast.setAlpha(255);
        main.postDelayed(this::invalidate, 240);
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (searchOpen) {
            if (down && code == KeyEvent.KEYCODE_BACK) {
                closeSearch();
                return true;
            }
            return false;   // the field and the keyboard own the rest
        }
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
            int before = chromeIndex;
            chromeIndex = Math.max(0, Math.min(chromeCount - 1, chromeIndex + dx));
            if (chromeIndex != before) {
                sheenAt = System.currentTimeMillis();
                requestFrame();
            }
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
                chromeIndex = 0;
                sheenAt = System.currentTimeMillis();
                requestFrame();
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
            activateChrome();
            return;
        }
        if (rows.isEmpty()) return;
        Row row = rows.get(rowIndex);
        if (colIndex == SEE_ALL) {
            if (row.seeTab >= 0) selectTab(row.seeTab);
            return;
        }
        if (colIndex < 0 || colIndex >= row.items.size()) return;
        Tile t = row.items.get(colIndex);
        if (t.action >= 0) { selectTab(t.action); return; }
        if (searchOpen) closeSearch();   // picking a result leaves the search behind
        open(t, row);
    }

    private void activateChrome() {
        int id = chromeOrder[Math.max(0, Math.min(chromeIndex, chromeCount - 1))];
        if (id >= CH_TAB) { selectTab(id - CH_TAB); return; }
        switch (id) {
            case CH_SEARCH:
                openSearch();
                break;
            case CH_APPS:
                selectTab(4);
                break;
            case CH_SYNC:
                Iptv.get(getContext()).refresh();
                showToast("Mise à jour du catalogue…");
                break;
            case CH_GEAR:
                openSettings();
                break;
            default:
                break;
        }
    }

    private void openSettings() {
        android.app.Activity a = activity();
        if (a == null) return;
        RectF box = chromeRects[CH_GEAR];
        HomeApps.open(a, HomeApps.settings(), this, Math.round(box.left), Math.round(box.top),
                Math.round(box.width()), Math.round(box.height()));
    }

    private void open(Tile t, Row row) {
        android.app.Activity a = activity();
        if (t.app != null) {
            if (a == null) return;
            int i = Math.max(0, colIndex);
            float x = MARGIN * k + i * step(row.kind) - row.slide;
            float y = BAR_H * k + 18 * k + row.y + HEAD_H * k - offset;
            if (!HomeApps.open(a, t.app, this, Math.round(x), Math.round(y),
                    Math.round(tw(row.kind)), Math.round(th(row.kind)))) {
                showToast("Impossible d'ouvrir " + t.title + ".");
            }
            return;
        }
        if (t.series) {
            openSeries(t);
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
        zone = Z_ROW;
        rowIndex = 0;
        colIndex = 0;
        chromeIndex = 0;
        offset = offsetTo = 0;
        rows.clear();
        rebuild();
        requestFrame();
        invalidate();
    }

    private void back() {
        if (seriesMode) {
            seriesMode = false;
            episodes = new ArrayList<>();
            rowIndex = 0;
            colIndex = 0;
            offset = offsetTo = 0;
            rebuild();
            return;
        }
        if (tab != 0) {
            selectTab(0);
            return;
        }
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
        chromeIndex = 0;
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

    // MARK: - Search (typed in the activity's field, shown in our own shelves)

    /** The activity hands over its search box: it is laid over the pill, so typing looks like typing in the pill. */
    void setSearchField(EditText f) {
        field = f;
        f.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (searchOpen) showResults(e.toString());
            }
        });
        f.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                closeSearch();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                v.clearFocus();
                zone = Z_ROW;
                rowIndex = 0;
                colIndex = 0;
                invalidate();
                return true;
            }
            return false;
        });
    }

    private void openSearch() {
        if (field == null) return;
        searchOpen = true;
        seriesMode = false;
        tab = 0;
        android.app.Activity a = activity();
        if (a instanceof HomeActivity) ((HomeActivity) a).openSearch();
        else { field.setVisibility(VISIBLE); field.requestFocus(); }
        showResults("");
    }

    void closeSearch() {
        if (!searchOpen) return;
        searchOpen = false;
        android.app.Activity a = activity();
        if (a instanceof HomeActivity) ((HomeActivity) a).closeSearch();
        query = "";
        rebuild();
        requestFocus();
        invalidate();
    }

    /** Filters the account on the fly: films, series and channels, 24 of each, in the usual shelves. */
    private void showResults(String raw) {
        query = raw == null ? "" : raw.trim();
        String q = query.toLowerCase(Locale.ROOT);
        rows.clear();
        if (q.length() >= 2) {
            Iptv iptv = Iptv.get(getContext());
            results("Films", iptv.movies(), q, false);
            results("Séries", iptv.series(), q, true);
            results("Chaînes", iptv.live(), q, false);
        }
        rowIndex = 0;
        colIndex = 0;
        offset = offsetTo = 0;
        for (Row row : rows) {
            row.focus = new float[row.items.size()];
            row.slide = row.slideTo = 0;
            row.win = 0;
        }
        layout();
        invalidate();
    }

    private void results(String title, List<Iptv.Item> all, String q, boolean series) {
        Row row = new Row();
        row.title = title;
        row.kind = R_CARD;
        Set<String> seen = new HashSet<>();
        for (Iptv.Item i : all) {
            if (i.name == null || i.name.isEmpty()) continue;
            if (!i.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (!seen.add(norm(pretty(i.name)))) continue;
            row.items.add(tile(i, series));
            if (row.items.size() >= 24) break;
        }
        if (!row.items.isEmpty()) rows.add(row);
    }

    // MARK: - A series, in place (no other screen)

    /** Opens a series' episodes in the shelves: one row per season, the series' name above them. */
    private void openSeries(Tile t) {
        if (t.seriesId == null || t.seriesId.isEmpty()) {
            showToast("Cette série n'a pas d'épisodes.");
            return;
        }
        seriesMode = true;
        seriesTitle = t.title;
        episodes = new ArrayList<>();
        rowIndex = 0;
        colIndex = 0;
        offset = offsetTo = 0;
        rebuild();
        showToast("Chargement des épisodes…");
        final String id = t.seriesId;
        new Thread(() -> {
            List<Iptv.Item> got = Iptv.get(getContext()).episodes(id);
            post(() -> {
                if (!seriesMode) return;
                episodes = got;
                rebuild();
                if (got.isEmpty()) showToast("Aucun épisode pour cette série.");
            });
        }, "episodes").start();
    }

    private void seasonRows() {
        java.util.LinkedHashMap<String, Row> bySeason = new java.util.LinkedHashMap<>();
        for (Iptv.Item e : episodes) {
            Row row = bySeason.get(e.group);
            if (row == null) {
                row = new Row();
                row.kind = R_CARD;
                row.title = "Saison " + e.group;
                bySeason.put(e.group, row);
            }
            Tile t = new Tile();
            t.title = e.name;                 // "S01 E02 · Titre"
            t.url = e.url == null ? "" : e.url;
            t.image = e.logo == null ? "" : e.logo;
            row.items.add(t);
        }
        rows.addAll(bySeason.values());
    }

    // MARK: - Panel names

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

    static String norm(String name) {
        if (name == null) return "";
        String t = name.toUpperCase(Locale.ROOT)
                .replace("FULL HD", "").replace("FHD", "").replace("UHD", "").replace("4K", "")
                .replace("HD", "").replace("SD", "").replace("RAW", "").replace("BACKUP", "")
                .replace("FR:", "").replace("|FR|", "").replace("FR |", "");
        return t.replaceAll("[^A-Z0-9]", "");
    }

    static String clean(String name) {
        if (name == null) return "";
        String t = name.replace("✧", "").replace("･", "").replace("ﾟ", "").replace("─", "").replace("||", "");
        while (t.startsWith(":") || t.startsWith("*") || t.startsWith("-") || t.startsWith("◇") || t.startsWith("·")) {
            t = t.substring(1);
        }
        t = t.trim();
        return t.isEmpty() ? name.trim() : t;
    }

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
