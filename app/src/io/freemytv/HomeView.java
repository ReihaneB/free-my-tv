package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.Matrix;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
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
        // what the frame would otherwise recompute (and allocate) for every card, every frame
        float capWidth = -1, heroWidth = -1;
        String capTitle = "", capMeta = "";
        List<String> heroLines;
        String heroMeta;
        Shader heroTint;
        int heroTintOf;
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

    private static final float BAR_H = 116, MARGIN = 96, BAR_TOP = 36;
    private static final float HEAD_H = 52, ROW_GAP = 46, CAP_H = 70;
    // 4 cards or 3 heroes span exactly the plate's width (1728 = 4 × 402 + 3 × 40 = 3 × 549 + 2 × 40): every right
    // edge on screen lines up with the bar's. The widths are a hair under so the column count never rounds down.
    private static final float CARD_W = 401.9f, CARD_H = 226, CARD_GAP = 40;
    private static final float HERO_W = 549.2f, HERO_H = 309, HERO_GAP = 40;
    private static final float PILL_W = 300, PILL_H = 58, BUTTON_W = 78, BUTTON_H = 58, BUTTON_GAP = 14;
    private static final float LIB_TITLE_H = 96;      // the library's name above its shelves

    // Infuse 8's slate background, not black.
    private static final int BG_TOP = 0xFF0E1418, BG_BOTTOM = 0xFF05080A;
    private static final int INK = 0xFFFFFFFF, DIM = 0xFFB8C4C9, FAINT = 0xFF8798A0, ACCENT = 0xFFFF8A00;
    /** How much the glass compresses what is behind it (a lens: the edge shows a little of the outside). */
    private static final float LENS = 0.94f;

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
    private boolean searchOpen;
    private String query = "";

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

    private Paint monogram;
    private Paint rowTitle, cardTitle, cardMeta, seeAll, pillInk, tabInk, toast, empty;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);


    private long sheenAt;

    // the backdrop, painted on its own thread (Lights); the glass and the scroll edge read it through shaders
    private Lights lights;
    private int shaderGen = -1;
    private final Matrix matrix = new Matrix();
    private final RectF screen = new RectF();
    private LinearGradient scrimMask;
    private final Paint scrimPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float scrimBottom;

    // the materials, baked once per size (Liquid)
    private Liquid.Kit kitClear, kitLit, kitWell, kitButton, kitSee, kitToast;
    private Bitmap plateFace, plateShadow, cardShadow, heroShadow;
    private int platePad, cardPad;
    private Shader cardRim, heroRim, topLight, sheenBand, sepShade;
    private Bitmap heroTile;
    /** The plate's backdrop is drawn in nested bands, each compressed more than the one inside it: a lens. */
    private static final float[] LENS_BANDS = {0.80f, 0.86f, 0.90f, LENS};
    private static final float[] LENS_INSETS = {0f, 3f, 6f, 10f};
    private final BitmapShader[] lensShaders = new BitmapShader[LENS_BANDS.length];
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaded = new Paint(Paint.ANTI_ALIAS_FLAG);

    // the focus lens of the bar: one drop of lit glass that slides from control to control
    private final RectF lens = new RectF(), lensTo = new RectF(), lensBox = new RectF();
    private float lensA;

    private final Posters.Done posterDone = (url, bitmap) -> postInvalidateOnAnimation();
    private final Runnable toastTick = this::invalidate;

    private final Path clip = new Path();
    private final RectF r = new RectF(), r2 = new RectF(), dst = new RectF(), artBox = new RectF();
    private final RectF barBox = new RectF();
    private float barRadius, sepA, sepB;
    private final android.graphics.Rect srcRect = new android.graphics.Rect();
    private final Map<String, Integer> accents = new HashMap<>();   // url → the artwork's average colour
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
        monogram = Ui.text(Ui.semibold, 112, 0x8CFFFFFF);
        monogram.setTextAlign(Paint.Align.CENTER);
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
        if (lights != null) lights.start();
        Perf.readSkip(getContext());
        main.removeCallbacks(autoDrive);
        if (Perf.off(Perf.AUTO)) main.postDelayed(autoDrive, 4000);
        invalidate();
    }

    private final Runnable autoDrive = new Runnable() {
        private int i;
        @Override public void run() {
            if (!resumed || !Perf.off(Perf.AUTO)) return;
            int code = Perf.DRIVE[i++ % Perf.DRIVE.length];
            onKey(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            onKey(new KeyEvent(KeyEvent.ACTION_UP, code));
            main.postDelayed(this, 250);
        }
    };

    void pause() {
        resumed = false;
        Iptv.get(getContext()).removeListener(this);
        main.removeCallbacks(clockTick);
        main.removeCallbacks(autoDrive);
        if (lights != null) lights.stop();
    }

    void destroy() {
        pause();
        if (lights != null) lights.release();
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
        screen.set(0, 0, width, height);
        layout();
        offset = offsetTo;
        for (Row row : rows) row.slide = row.slideTo;
        if (lights != null) lights.release();
        lights = new Lights(this, width, height, BG_TOP, BG_BOTTOM);
        shaderGen = -1;
        if (resumed) lights.start();
        bake();
    }

    /**
     * Bakes every glass surface for this screen size (Liquid): the bar's plate, the controls, the focus lens, the
     * soft shadows of the cards, and the few gradients the cards use — in their own coordinates, so a frame only
     * moves the canvas and never builds a shader.
     */
    private void bake() {
        int ch = Math.round(PILL_H * k);
        kitClear = Liquid.capsule(Liquid.CLEAR, ch, k, false);
        kitLit = Liquid.capsule(Liquid.FOCUS, ch, k, true);
        kitWell = Liquid.capsule(Liquid.WELL, ch, k, false);
        kitButton = Liquid.capsule(Liquid.CLEAR, Math.round(44 * k), k, false);
        kitSee = Liquid.capsule(Liquid.FOCUS, Math.round(40 * k), k, true);
        kitToast = Liquid.capsule(Liquid.DARK, Math.round(56 * k), k, true);

        int pw = Math.round(barBox.width()), ph = Math.round(barBox.height());
        plateFace = Liquid.face(Liquid.PLATE, pw, ph, barRadius, k);
        platePad = Math.round(28 * k);
        plateShadow = Liquid.shadow(pw, ph, barRadius, platePad, 22 * k, 0.42f, 0.5f, -10 * k);   // under, not around

        cardPad = Math.round(34 * k);
        int cw = Math.round(CARD_W * k), chh = Math.round(CARD_H * k);
        int hw = Math.round(HERO_W * k), hh = Math.round(HERO_H * k);
        cardShadow = Liquid.shadow(cw, chh, 30 * k, cardPad, 24 * k, 0.62f, 0.5f);
        heroShadow = Liquid.shadow(hw, hh, 34 * k, cardPad, 26 * k, 0.62f, 0.5f);

        cardRim = rimShader(cw, chh);
        heroRim = rimShader(hw, hh);
        topLight = new LinearGradient(0, 0, 0, chh * 0.55f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        float band = cw * 0.42f;
        sheenBand = new LinearGradient(-band, -band * 0.35f, band, band * 0.35f,
                new int[] {0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        float sepLen = barBox.height() - 44 * k;
        sepShade = new LinearGradient(0, 0, 0, sepLen, new int[] {0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        float tilePad = 24 * k, tileH = HERO_H * k - 2 * tilePad;
        heroTile = Liquid.face(Liquid.CLEAR, Math.round(tileH * 2f / 3f), Math.round(tileH), 12 * k, k);

        // the scroll edge: what scrolls up fades into the backdrop before it reaches the glass
        scrimBottom = BAR_H * k + 16 * k;
        scrimMask = new LinearGradient(0, barBox.centerY(), 0, scrimBottom, 0xFF000000, 0x00000000,
                Shader.TileMode.CLAMP);

        // the glass shows the lights behind it, more vivid and a little brighter (Apple's vibrancy)
        ColorMatrix vibrancy = new ColorMatrix();
        // mostly clear: Liquid Glass takes a hint of what is behind it, not its colour
        vibrancy.setSaturation(0.82f);
        ColorMatrix lift = new ColorMatrix(new float[] {
                1.04f, 0, 0, 0, 9,
                0, 1.04f, 0, 0, 9,
                0, 0, 1.04f, 0, 10,
                0, 0, 0, 1, 0});
        vibrancy.postConcat(lift);
        platePaint.setColorFilter(new ColorMatrixColorFilter(vibrancy));

        rimPaint.setStyle(Paint.Style.STROKE);
        lens.setEmpty();
        lensA = 0;
    }

    /** The specular rim of a card, in the card's own coordinates: lit on the top-left, again on the bottom-right. */
    private static Shader rimShader(float cw, float ch) {
        return new LinearGradient(0, 0, cw, ch,
                new int[] {0xFFFFFFFF, 0x6BFFFFFF, 0x4DFFFFFF, 0xD9FFFFFF},
                new float[] {0f, 0.32f, 0.68f, 1f}, Shader.TileMode.CLAMP);
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
        return (tab == 0 ? 0 : LIB_TITLE_H * k);
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

    /**
     * The bar is one glass plate inset from the edges, and everything in it is concentric with it: the plate's
     * radius is half its height, the controls sit 12 px inside and have half that radius. Search on the left, the
     * actions on the right, the libraries centred between the two separators.
     */
    private void layoutBar() {
        float top = BAR_TOP * k, hh = PILL_H * k, m = MARGIN * k;
        float pad = 12 * k;
        barBox.set(m, top - pad, w - m, top + hh + pad);   // 24 px of air above the plate
        barRadius = barBox.height() / 2;
        float inL = m + pad, inR = w - m - pad;
        chromeCount = 0;
        chromeRects[CH_SEARCH].set(inL, top, inL + PILL_W * k, top + hh);
        chromeOrder[chromeCount++] = CH_SEARCH;

        float bw = BUTTON_W * k, gb = 4 * k;
        chromeRects[CH_GEAR].set(inR - bw, top, inR, top + hh);
        chromeRects[CH_SYNC].set(inR - 2 * bw - gb, top, inR - bw - gb, top + hh);
        chromeRects[CH_APPS].set(inR - 3 * bw - 2 * gb, top, inR - 2 * bw - 2 * gb, top + hh);
        sepA = chromeRects[CH_SEARCH].right + 22 * k;
        sepB = chromeRects[CH_APPS].left - 22 * k;

        tabInk.setTextSize(21 * k);
        float gt = 10 * k, total = -gt;
        for (int i = 0; i < TAB_COUNT; i++) total += tabInk.measureText(LIBRARIES[i]) + 40 * k + gt;
        float tx = w / 2 - total / 2;   // on the screen's axis, not between the separators
        tx = Math.max(sepA + 16 * k, Math.min(tx, sepB - 16 * k - total));
        for (int i = 0; i < TAB_COUNT; i++) {
            float twi = tabInk.measureText(LIBRARIES[i]) + 40 * k;
            tabRects[i].set(tx, top, tx + twi, top + hh);
            tx += twi + gt;
            chromeOrder[chromeCount++] = CH_TAB + i;
        }
        chromeOrder[chromeCount++] = CH_APPS;
        chromeOrder[chromeCount++] = CH_SYNC;
        chromeOrder[chromeCount++] = CH_GEAR;
        chromeIndex = Math.max(0, Math.min(chromeIndex, chromeCount - 1));
    }

    private RectF chromeRect(int id) {
        return id >= CH_TAB ? tabRects[id - CH_TAB] : chromeRects[id];
    }

    private int focusedChrome() {
        return chromeOrder[Math.max(0, Math.min(chromeIndex, chromeCount - 1))];
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
        if (Perf.ON) Perf.frame(nanos, wasMoving);
        long now = nanos / 1_000_000L;
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean moving = stepLens(dt);
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
        if (sheenAt != 0 && System.currentTimeMillis() - sheenAt < SHEEN_MS + 20) moving = true;
        if (moving) requestFrame();
        wasMoving = moving;
    }

    private boolean wasMoving;

    /**
     * The lens follows the focus like a drop: the edge in front runs, the edge behind follows, so it stretches on
     * the way and settles round on arrival. It fades in where the focus is when the bar is entered.
     */
    private boolean stepLens(float dt) {
        boolean moving = false;
        float aTo = zone == Z_BAR && !searchOpen ? 1f : 0f;
        if (searchOpen) lensA = 0;   // the field is typed in white: no lit glass under it, not even fading
        if (Math.abs(aTo - lensA) > 0.004f) {
            lensA += (aTo - lensA) * Math.min(1f, dt * 14f);
            moving = true;
        } else {
            lensA = aTo;
        }
        lensTo.set(chromeRect(focusedChrome()));
        if (lens.isEmpty() || (lensA < 0.04f && aTo > 0)) {
            lens.set(lensTo);
            return moving;
        }
        float fast = Math.min(1f, dt * 18f), slow = Math.min(1f, dt * 13f);   // a calm drop, not a rubber band
        boolean right = lensTo.centerX() >= lens.centerX();
        float l = lens.left + (lensTo.left - lens.left) * (right ? slow : fast);
        float rr = lens.right + (lensTo.right - lens.right) * (right ? fast : slow);
        if (Math.abs(lensTo.left - l) < 0.3f) l = lensTo.left;
        if (Math.abs(lensTo.right - rr) < 0.3f) rr = lensTo.right;
        if (l != lensTo.left || rr != lensTo.right) moving = true;
        lens.set(l, lensTo.top, rr, lensTo.bottom);
        return moving;
    }

    // MARK: - Drawing

    private static final long SHEEN_MS = 640;

    /** The tile that has the focus, if any: the one whose artwork tints the lights and the hero card. */
    private Tile focusedTile() {
        if (rows.isEmpty() || zone != Z_ROW) return null;
        if (rowIndex < 0 || rowIndex >= rows.size()) return null;
        Row row = rows.get(rowIndex);
        if (row.col < 0 || row.col >= row.items.size()) return null;
        return row.items.get(row.col);
    }

    @Override protected void onDraw(Canvas c) {
        long t0 = Perf.ON ? System.nanoTime() : 0;
        if (Perf.off(Perf.NOTHING)) {
            c.drawColor(0xFF0E1418);
            return;
        }
        // the lights repaint themselves on their thread and invalidate us when a new picture is ready
        if (lights != null) lights.accent(accentOf(focusedTile()));
        drawScene(c);
        if (!Perf.off(Perf.NO_BAR)) drawBar(c);
        drawToast(c);
        if (Perf.ON) Perf.draw(System.nanoTime() - t0);
    }

    private void drawScene(Canvas c) {
        Bitmap back = lights == null || Perf.off(Perf.NO_BACKDROP) ? null : lights.front();
        if (back != null) {
            c.drawBitmap(back, null, screen, art);
        } else {
            fill.setColor(0xFF000000);
            fill.setShader(bg);
            c.drawRect(screen, fill);
            fill.setShader(null);
        }

        float barH = BAR_H * k;
        float top = barH + 18 * k - offset;
        if (rows.isEmpty()) {
            empty.setTextSize(24 * k);
            empty.setTextAlign(Paint.Align.LEFT);
            String msg = searchOpen
                    ? query.length() < 2 ? "Tapez au moins deux lettres." : "Aucun résultat pour « " + query + " »."
                    : "Aucun compte IPTV : renseignez-le dans les réglages.";
            c.drawText(msg, MARGIN * k, top + 60 * k, empty);
        } else {
            if (tab != 0) {
                rowTitle.setTextSize(44 * k);
                rowTitle.setTextAlign(Paint.Align.LEFT);
                c.drawText(LIBRARIES[tab], MARGIN * k, top + 52 * k, rowTitle);
            }
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                float y = top + row.y;
                if (y > h || y + row.h < barH) continue;
                drawRow(c, row, i, y);
            }
        }
        // the scroll edge: what goes up fades into the backdrop before it reaches the glass
        if (!Perf.off(Perf.NO_SCRIM) && refreshBackdropShaders()) c.drawRect(0, 0, w, scrimBottom, scrimPaint);
    }

    /**
     * The scroll edge and the plate read the lights through bitmap shaders, and a shader keeps the picture it was
     * built on: rebuild both whenever the lights publish a new one (15 times a second, never per frame).
     */
    private boolean refreshBackdropShaders() {
        if (lights == null || scrimMask == null) return false;
        int g = lights.generation();
        Bitmap b = lights.front();
        if (b == null) return false;
        if (g == shaderGen) return true;
        shaderGen = g;
        float sx = w / lights.width(), sy = h / lights.height();
        BitmapShader edge = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        matrix.setScale(sx, sy);
        edge.setLocalMatrix(matrix);
        scrimPaint.setShader(new ComposeShader(edge, scrimMask, PorterDuff.Mode.DST_IN));
        for (int i = 0; i < LENS_BANDS.length; i++) {
            BitmapShader glass = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            matrix.setScale(sx, sy);
            matrix.postScale(LENS_BANDS[i], LENS_BANDS[i], barBox.centerX(), barBox.centerY());
            glass.setLocalMatrix(matrix);
            lensShaders[i] = glass;
        }
        return true;
    }

    /**
     * The bar: one Liquid Glass plate — its soft shadow, the lights seen through it (compressed like through a lens,
     * more vivid), then its baked rim and sheen. Inside, the search well, the library shown on a quiet glass capsule,
     * and the focus: one drop of lit glass that slides from control to control. Ink turns dark where the drop is.
     */
    private void drawBar(Canvas c) {
        shadowPaint.setAlpha(255);
        dst.set(barBox.left - platePad, barBox.top - platePad + 12 * k, barBox.right + platePad,
                barBox.bottom + platePad + 12 * k);
        c.drawBitmap(plateShadow, null, dst, shadowPaint);
        if (refreshBackdropShaders()) {
            // the lights through the glass, bent more and more towards the rim: the edge shows what lies just
            // outside it, like the thick border of a drop
            for (int i = 0; i < LENS_BANDS.length; i++) {
                float in = LENS_INSETS[i] * k;
                dst.set(barBox);
                dst.inset(in, in);
                platePaint.setShader(lensShaders[i]);
                c.drawRoundRect(dst, barRadius - in, barRadius - in, platePaint);
            }
        }
        c.drawBitmap(plateFace, barBox.left, barBox.top, glassPaint);
        shaded.setAlpha(255);
        shaded.setShader(sepShade);
        float sepTop = barBox.top + 22 * k, sepLen = barBox.height() - 44 * k;
        c.save();
        c.translate(sepA, sepTop);
        c.drawRect(0, 0, 1.5f * k, sepLen, shaded);
        c.translate(sepB - sepA, 0);
        c.drawRect(0, 0, 1.5f * k, sepLen, shaded);
        c.restore();
        shaded.setShader(null);

        Liquid.draw(c, kitWell, chromeRects[CH_SEARCH], glassPaint, 1f, false, 0);
        Liquid.draw(c, kitClear, tabRects[tab], glassPaint, 1f, false, 0);
        if (lensA > 0.003f) {
            // a drop stretched by its speed gets a little thinner, like a liquid would
            float stretch = Math.max(0, lens.width() / Math.max(1, lensTo.width()) - 1);
            float squash = Math.min(0.08f, stretch * 0.08f) * lensTo.height();
            lensBox.set(lens.left, lens.top + squash / 2, lens.right, lens.bottom - squash / 2);
            lensBox.inset(-3 * k * lensA, -2 * k * lensA);   // the focused item is lifted a little, like on tvOS
            Liquid.draw(c, kitLit, lensBox, glassPaint, lensA, true, 7 * k);
        }

        RectF pill = chromeRects[CH_SEARCH];
        float on = cover(pill);
        float cx = pill.left + 30 * k, cy = pill.centerY();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.2f * k);
        stroke.setColor(blend(DIM, INK, on));
        c.drawCircle(cx, cy - 1 * k, 9 * k, stroke);
        c.drawLine(cx + 6.5f * k, cy + 6 * k, cx + 13 * k, cy + 12.5f * k, stroke);
        if (!searchOpen) {   // the EditText laid over the pill draws the hint itself when it is up
            pillInk.setTextSize(22 * k);
            pillInk.setTextAlign(Paint.Align.LEFT);
            pillInk.setColor(blend(0xFFB0BCC1, INK, on));
            c.drawText("Rechercher", pill.left + 54 * k, cy + Ui.halfCap(pillInk), pillInk);
        }

        tabInk.setTextSize(21 * k);
        tabInk.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < TAB_COUNT; i++) {
            RectF box = tabRects[i];
            float on2 = cover(box);
            // under the glass the label brightens and is magnified a little, as through a drop of water
            tabInk.setTextSize(21 * k * (1 + 0.07f * on2));
            tabInk.setColor(blend(i == tab ? INK : 0xB8FFFFFF, INK, on2));
            c.drawText(LIBRARIES[i], box.centerX(), box.centerY() + Ui.halfCap(tabInk), tabInk);
        }
        icon(c, CH_APPS, "apps");
        icon(c, CH_SYNC, "sync");
        icon(c, CH_GEAR, "settings");
    }

    private void icon(Canvas c, int id, String symbol) {
        RectF box = chromeRects[id];
        float on = cover(box);
        Symbols.draw(getContext(), c, symbol, box.centerX(), box.centerY(), 28 * k * (1 + 0.08f * on), blend(DIM, INK, on));
    }

    /** How much of `box` the focus drop covers right now (0..1, faded with it): the ink follows the glass. */
    private float cover(RectF box) {
        if (lensA <= 0.003f || box.width() <= 0) return 0;
        float o = Math.min(lensBox.right, box.right) - Math.max(lensBox.left, box.left);
        return Ui.clamp(o / box.width()) * lensA;
    }

    private static int blend(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24), br = (b >> 16) & 255, bg2 = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg2 - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** 0..1 while a glint crosses the focused surface, -1 when it is done (keeps the CPU quiet at rest). */
    private float sheen() {
        long age = System.currentTimeMillis() - sheenAt;
        if (sheenAt == 0 || age < 0 || age > SHEEN_MS) return -1;
        float t = age / (float) SHEEN_MS;
        return 1 - (1 - t) * (1 - t) * (1 - t);   // ease out
    }

    private void drawRow(Canvas c, Row row, int index, float y) {
        float m = MARGIN * k;
        rowTitle.setTextSize(28 * k);
        rowTitle.setTextAlign(Paint.Align.LEFT);
        float base = y + 30 * k;   // 22 px above the cards: a focused card grows 7 px and still clears it
        c.drawText(row.title, m, base, rowTitle);

        boolean focusedRow = index == rowIndex && zone == Z_ROW;
        if (row.seeTab >= 0) {
            // "Tout voir" is a control like the others: plain ink at rest, the lit glass drop when focused
            seeAll.setTextSize(20 * k);
            seeAll.setTextAlign(Paint.Align.RIGHT);
            boolean on = focusedRow && row.col == SEE_ALL;
            float tw2 = seeAll.measureText("Tout voir"), cy = base - Ui.cap(seeAll) / 2;
            if (on) {   // the text stays on the grid's right edge; the drop overhangs it like a focused card
                r.set(w - m - tw2 - 20 * k, cy - 20 * k, w - m + 20 * k, cy + 20 * k);
                Liquid.draw(c, kitSee, r, glassPaint, 1f, true, 4 * k);
            }
            seeAll.setColor(on ? INK : 0xFFB8C4C9);
            c.drawText("Tout voir", w - m, base, seeAll);
            seeAll.setColor(0xFFB8C4C9);
        }

        int n = cols(row.kind);
        float s = step(row.kind), tw = tw(row.kind), th = th(row.kind);
        float tilesTop = y + HEAD_H * k;
        boolean hero = row.kind == R_HERO;
        for (int j = row.win; j < n + row.win && j < row.items.size(); j++) {
            Tile t = row.items.get(j);
            float x = m + j * s - row.slide;
            r.set(x, tilesTop, x + tw, tilesTop + th);
            if (r.right < m - 12 || r.left > w - m + 12) continue;
            float f = j < row.focus.length ? row.focus[j] : 0;
            drawCard(c, r, t, f, hero);
            if (!hero && !Perf.off(Perf.NO_TEXT)) drawCaption(c, t, r, tilesTop + th + (8 + 10 * f) * k, f);
        }
    }

    /**
     * Every card is the same piece of glass: artwork clipped to its rounded shape, a specular rim lit from the
     * top-left (quiet at rest, bright when focused), and on focus a lift — scale, a soft baked shadow, light on its
     * top half and a glint that crosses it once.
     */
    private void drawCard(Canvas c, RectF box, Tile t, float f, boolean hero) {
        float radius = (hero ? 34 : 30) * k;
        float grow = 1 + (hero ? 0.045f : 0.055f) * f;
        r2.set(box);
        r2.inset(-box.width() * (grow - 1) / 2, -box.height() * (grow - 1) / 2);
        float rad = radius * grow;

        if (f > 0.02f) {
            float pad = cardPad * grow, dy = 16 * k * f;
            dst.set(r2.left - pad, r2.top - pad + dy, r2.right + pad, r2.bottom + pad + dy);
            shadowPaint.setAlpha(Math.round(255 * Math.min(1f, f)));
            c.drawBitmap(hero ? heroShadow : cardShadow, null, dst, shadowPaint);
            shadowPaint.setAlpha(255);
        }

        clip.reset();
        clip.addRoundRect(r2, rad, rad, Path.Direction.CW);
        c.save();
        if (!Perf.off(Perf.NO_CLIP)) c.clipPath(clip);
        if (hero) heroBody(c, t, f, grow); else cardBody(c, t);
        if (f > 0.02f) {
            c.save();
            c.translate(r2.left, r2.top);
            shaded.setShader(topLight);
            shaded.setAlpha(Math.round(255 * Math.min(1f, f)));
            c.drawRect(0, 0, r2.width(), r2.height(), shaded);
            float phase = sheen();
            if (phase >= 0 && f > 0.5f) {
                float gx = -r2.width() * 0.45f + r2.width() * 1.9f * phase, gy = r2.height() / 2;
                c.translate(gx, gy);
                shaded.setShader(sheenBand);
                shaded.setAlpha(255);
                c.drawRect(-gx, -gy, r2.width() - gx, r2.height() - gy, shaded);
            }
            c.restore();
            shaded.setShader(null);
            shaded.setAlpha(255);
        }
        c.restore();

        if (Perf.off(Perf.NO_RIM)) return;
        float sw = (1f + 1.6f * f) * k;
        rimPaint.setShader(hero ? heroRim : cardRim);
        rimPaint.setStrokeWidth(sw);
        rimPaint.setAlpha(Math.round(255 * Math.min(1f, 0.20f + 0.80f * f)));
        c.save();
        c.translate(r2.left, r2.top);
        dst.set(sw / 2, sw / 2, r2.width() - sw / 2, r2.height() - sw / 2);
        c.drawRoundRect(dst, rad - sw / 2, rad - sw / 2, rimPaint);
        c.restore();
    }

    private void cardBody(Canvas c, Tile t) {
        fill.setShader(null);
        fill.setColor(0xFF1E2A31);
        c.drawRect(r2, fill);
        Bitmap b = artOf(t);
        if (b != null) {
            if (t.live) {
                artBox.set(r2);
                artBox.inset(28 * k, 28 * k);
                drawContain(c, b, artBox);
            } else {
                drawCover(c, b, r2);
            }
        } else {
            // No artwork: a quiet glyph, never the title — the caption under the card already has it.
            String glyph = !t.symbol.isEmpty() ? t.symbol : t.live ? "tv" : "play";
            Symbols.draw(getContext(), c, glyph, r2.centerX(), r2.centerY(), 54 * k, 0x4DFFFFFF);
        }
        if (t.progress > 0.005f) {
            // the progress is a capsule inset in the card's foot, like everything else that is a control here
            float ph = 6 * k, inset = 22 * k, py = r2.bottom - 16 * k;
            dst.set(r2.left + inset, py - ph, r2.right - inset, py);
            fill.setColor(0x8C000000);
            c.drawRoundRect(dst, ph / 2, ph / 2, fill);
            dst.right = dst.left + Math.max(30 * k, (r2.width() - 2 * inset) * Math.min(1f, t.progress));
            fill.setColor(ACCENT);
            c.drawRoundRect(dst, ph / 2, ph / 2, fill);
        }
    }

    /**
     * The card that speaks: artwork on the left with its own concentric corners, title, year and the button on the
     * right, over a tone taken from the artwork (one flat colour, darkened so white text always reads on it).
     */
    private void heroBody(Canvas c, Tile t, float f, float grow) {
        // a pane of smoked glass over the lights (they are already soft, so what shows through is a true frost),
        // tinted by the artwork; a card with no artwork is the same glass, untinted
        fill.setShader(null);
        fill.setColor(0x9E0B1116);
        c.drawRect(r2, fill);
        int accent = accentOf(t);
        if (t.heroTint == null || t.heroTintOf != accent) {
            int tint = heroTint(accent);
            t.heroTint = new LinearGradient(0, 0, 0, HERO_H * k, Ui.alpha(tint, 0.72f), Ui.alpha(tint, 0.40f),
                    Shader.TileMode.CLAMP);
            t.heroTintOf = accent;
        }
        c.save();
        c.translate(r2.left, r2.top);
        shaded.setAlpha(255);
        shaded.setShader(t.heroTint);
        c.drawRect(0, 0, r2.width(), r2.height(), shaded);
        shaded.setShader(null);
        c.restore();

        float pad = 24 * k * grow;
        float artH = r2.height() - 2 * pad;
        float artW = artH * 2f / 3f;
        artBox.set(r2.left + pad, r2.top + pad, r2.left + pad + artW, r2.top + pad + artH);
        Bitmap b = artOf(t);
        float ir = Math.max(4 * k, 34 * k * grow - pad);   // concentric with the card
        if (b == null || t.live) {
            // a channel logo or no picture at all: it sits on a pane of glass the size of a poster
            c.drawBitmap(heroTile, null, artBox, glassPaint);
        }
        if (b != null && t.live) {
            artBox.inset(20 * k, 20 * k);
            drawContain(c, b, artBox);
            artBox.inset(-20 * k, -20 * k);
        } else if (b != null) {
            float bw = b.getWidth(), bh = b.getHeight();
            float scale = Math.min(artBox.width() / bw, artBox.height() / bh);
            float dw = bw * scale, dh = bh * scale;
            dst.set(artBox.centerX() - dw / 2, artBox.centerY() - dh / 2, artBox.centerX() + dw / 2, artBox.centerY() + dh / 2);
            clip.reset();
            clip.addRoundRect(dst, ir, ir, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(b, null, dst, art);
            c.restore();
        } else {
            // no artwork: the title's initial, large, on the glass pane — typography, not a placeholder icon
            String initial = t.title.isEmpty() ? "·" : t.title.substring(0, t.title.offsetByCodePoints(0, 1))
                    .toUpperCase(Locale.FRENCH);
            monogram.setTextSize(112 * k * grow);
            c.drawText(initial, artBox.centerX(), artBox.centerY() + Ui.halfCap(monogram), monogram);
        }

        float tx = artBox.right + 26 * k;
        float maxW = r2.right - pad - tx;
        cardTitle.setTextSize(29 * k);
        cardTitle.setTextAlign(Paint.Align.LEFT);
        cardTitle.setColor(INK);
        if (t.heroLines == null || Math.abs(t.heroWidth - maxW) > 6 * k) {
            t.heroLines = wrap(t.title, cardTitle, maxW, 2);
            t.heroWidth = maxW;
        }
        float ty = r2.top + pad + 4 * k;
        for (String line : t.heroLines) {
            c.drawText(line, tx, ty + Ui.cap(cardTitle), cardTitle);
            ty += 36 * k;
        }
        cardMeta.setTextSize(19 * k);
        cardMeta.setTextAlign(Paint.Align.LEFT);
        if (t.heroMeta == null) t.heroMeta = heroMeta(t);
        c.drawText(t.heroMeta, tx, ty + Ui.cap(cardMeta) + 2 * k, cardMeta);

        String label = t.series ? "Épisodes" : t.url.isEmpty() ? "" : t.live ? "En direct" : "Regarder";
        if (!label.isEmpty()) {
            cardTitle.setTextSize(20 * k);
            float bw = cardTitle.measureText(label) + 62 * k;
            float by = r2.bottom - pad - 44 * k;   // on the poster's foot: every hero's button on one line
            r.set(tx, by, tx + bw, by + 44 * k);
            // the button is glass at rest and lights up with its card, the same drop as in the bar
            Liquid.draw(c, kitButton, r, glassPaint, 1f, false, 0);
            Liquid.draw(c, kitButton, r, glassPaint, 0.9f * f, false, 0);   // brighter glass with its card
            int ink = INK;
            Symbols.draw(getContext(), c, "play", r.left + 26 * k, r.centerY(), 22 * k, ink);
            cardTitle.setColor(ink);
            c.drawText(label, r.left + 46 * k, r.centerY() + Ui.halfCap(cardTitle), cardTitle);
            cardTitle.setColor(INK);
        }
    }

    /** Every hero says the same things in the same order: what it is, its year, how far it was watched. */
    private static String heroMeta(Tile t) {
        StringBuilder b = new StringBuilder(t.live ? "Chaîne en direct" : t.series ? "Série" : "Film");
        if (t.meta != null && !t.meta.isEmpty() && !t.live) b.append(" · ").append(t.meta);
        if (t.progress > 0.01f) b.append(" · ").append(Math.round(t.progress * 100)).append(" % vu");
        return b.toString();
    }

    /** The artwork's tone made safe for white text: never brighter than a dark mid-tone, never garish. */
    private static int heroTint(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], 0.70f);
        hsv[2] = Math.min(hsv[2], 0.44f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /**
     * The artwork's average colour, cached per URL: one flat tone, computed by scaling the picture to a single
     * pixel. It tints a card without ever showing a stretched, blocky copy of the picture behind the text.
     */
    private int accentOf(Tile t) {
        if (t == null || t.image == null || t.image.isEmpty()) return 0xFF23323C;
        Integer a = accents.get(t.image);
        if (a != null) return a;
        Bitmap src = artOf(t);
        if (src == null) return 0xFF23323C;
        Bitmap one = Bitmap.createScaledBitmap(src, 1, 1, true);
        int c = one.getPixel(0, 0);
        one.recycle();
        int r = Math.min(255, Math.round(((c >> 16) & 255) * 1.25f) + 14);
        int g = Math.min(255, Math.round(((c >> 8) & 255) * 1.25f) + 14);
        int b = Math.min(255, Math.round((c & 255) * 1.25f) + 14);
        int out = 0xFF000000 | (r << 16) | (g << 8) | b;
        if (accents.size() > 160) accents.clear();
        accents.put(t.image, out);
        return out;
    }

    private Bitmap artOf(Tile t) {
        if (Perf.off(Perf.NO_ART)) return null;
        if (t.art != null) return t.art;
        if (t.image == null || t.image.isEmpty()) return null;
        return Posters.get(getContext()).get(t.image, posterDone);
    }

    private void drawCover(Canvas c, Bitmap b, RectF box) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(box.width() / bw, box.height() / bh);
        float sw = box.width() / scale, sh = box.height() / scale;
        float top = (bh - sh) * (bh > bw ? 0.3f : 0.5f);   // a portrait poster: its upper part, where faces are
        srcRect.set(Math.round((bw - sw) / 2), Math.round(top), Math.round((bw + sw) / 2), Math.round(top + sh));
        c.drawBitmap(b, srcRect, box, art);
    }

    private void drawContain(Canvas c, Bitmap b, RectF box) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.min(box.width() / bw, box.height() / bh);
        float dw = bw * scale, dh = bh * scale;
        dst.set(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2);
        c.drawBitmap(b, null, dst, art);
    }

    private void drawCaption(Canvas c, Tile t, RectF box, float y, float f) {
        cardTitle.setTextSize(21 * k);
        cardTitle.setTextAlign(Paint.Align.CENTER);
        cardMeta.setTextSize(17 * k);
        cardMeta.setTextAlign(Paint.Align.CENTER);
        float width = box.width() + 24 * k;
        if (t.capWidth != width) {
            t.capTitle = fit(cardTitle, t.title, width);
            t.capMeta = t.meta == null || t.meta.isEmpty() ? "" : fit(cardMeta, t.meta, width);
            t.capWidth = width;
        }
        cardTitle.setColor(blend(0xFFDDE4E7, INK, f));
        c.drawText(t.capTitle, box.centerX(), y + Ui.cap(cardTitle), cardTitle);
        if (!t.capMeta.isEmpty()) c.drawText(t.capMeta, box.centerX(), y + Ui.cap(cardTitle) + 26 * k, cardMeta);
        cardTitle.setColor(INK);
        cardTitle.setTextAlign(Paint.Align.LEFT);
    }

    // MARK: - Toast

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
        float tw = toast.measureText(toastText) + 64 * k;
        r.set(w / 2 - tw / 2, h - 116 * k, w / 2 + tw / 2, h - 60 * k);
        Liquid.draw(c, kitToast, r, glassPaint, a, true, 6 * k);
        toast.setAlpha(Math.round(255 * a));
        c.drawText(toastText, w / 2, r.centerY() + Ui.halfCap(toast), toast);
        toast.setAlpha(255);
        main.removeCallbacks(toastTick);
        if (age < 200 || age > 2200) postInvalidateOnAnimation();
        else main.postDelayed(toastTick, 2200 - age);
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
            if (field != null && field.hasFocus()) return false;   // the field and the keyboard own the rest
            // in the results (Down from the field): the arrows are ours; Up from the first row goes back to typing
            if (down && code == KeyEvent.KEYCODE_DPAD_UP && zone == Z_ROW && rowIndex == 0 && field != null) {
                field.requestFocus();
                invalidate();
                return true;
            }
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
            sheenAt = System.currentTimeMillis();
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
        sheenAt = System.currentTimeMillis();
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
        // picking a result leaves the search behind, except for a film's or a series' page: Back comes back to it
        boolean page = t.series || t.url.contains("/movie/") || (t.url.contains("/series/") && !t.seriesId.isEmpty());
        if (searchOpen && !page) closeSearch();
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
        boolean episode = t.url.contains("/series/") && !t.seriesId.isEmpty();
        if (a instanceof HomeActivity && (t.series || episode || t.url.contains("/movie/"))) {
            // a film or a series: its page first (synopsis, Lecture / Reprendre, the episodes)
            if (t.series && t.seriesId.isEmpty()) {
                showToast("Cette série n'a pas d'épisodes.");
                return;
            }
            DetailView.Request r = new DetailView.Request();
            r.title = t.title;
            r.year = t.meta;
            r.poster = t.image;
            r.url = t.url;
            r.series = t.series || episode;
            r.seriesId = t.seriesId;
            ((HomeActivity) a).openDetail(r);
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
        tab = 0;
        android.app.Activity a = activity();
        if (a instanceof HomeActivity) ((HomeActivity) a).openSearch();
        else { field.setVisibility(VISIBLE); field.requestFocus(); }
        lensA = 0;
        requestFrame();
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
        requestFrame();
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
