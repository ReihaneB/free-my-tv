package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
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
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The home screen, in the clothes of the film's page and the player (tvOS 26 / Infuse 8): the picture of what has
 * the focus fills the screen (its backdrop, or its colours), its title, facts and story stand at the top left, and
 * the shelves sit below — posters for films and series, 16:9 cards for what is being watched, the channels and the
 * apps. The focused shelf always sits at the same height; the ones above it fade away as it rises.
 *
 * <p>The Liquid Glass bar is kept: search, the libraries (Accueil, Films, Séries, Direct, Apps), then on the right
 * the TV's state — memory in use, storage in use, the weather — and the catalogue's refresh capsule.
 * Everything is drawn on the canvas from the account cached on disk, so the screen opens with no network; the
 * pictures and the facts of the focused item come from the panel when the focus rests.
 */
final class HomeView extends View implements Choreographer.FrameCallback, Iptv.Listener {

    // MARK: - Model

    /** One card: a film, an episode, a channel, an app, an input, or one of the library tiles. */
    private static final class Tile {
        String title = "", meta = "", image = "", url = "", seriesId = "", symbol = "";
        String episode = "";        // a "continue watching" episode: "S1, É2"
        String wide = "";           // a "continue watching" card: its 16:9 picture (backdrop, episode still)
        Bitmap art;                 // apps: the ready 16:9 card
        HomeApps.Item app;          // apps and inputs open through HomeApps
        float progress;             // continue watching: 0..1
        long left;                  // continue watching: what remains, ms
        boolean series, live;
        volatile Iptv.Detail lite;  // what the panel's lists say of it (Facts), read once
        volatile boolean liteAsked, fullTried;
        int color;                  // library tile
        int action = -1;            // >= 0: open that library tab; TO_SETTINGS / TO_BROWSE
        Channels.Channel ch;        // a channel of the lineup: its number, its streams, its guide id
        Epg.Show show;              // what it shows now (read from the guide when `showUntil` passes)
        long showUntil;
        // what the frame would otherwise recompute (and allocate) for every card, every frame
        float capWidth = -1;
        String capTitle = "", capMeta = "";
    }

    private static final class Row {
        String title = "";
        int kind, seeTab = -1;
        boolean captions;           // "En cours": title and time left under each card
        String catId = "";          // a Direct category: INFO puts it away
        String catName = "";
        boolean restore;            // the row of the categories put away: OK brings one back
        final List<Tile> items = new ArrayList<>();
        float y, h;
        int col, win;
        float slide, slideTo;
        float alpha = 1;            // the shelves above the focused one fade out as it rises
        float[] focus = new float[0];
    }

    /** 16:9 cards (watching, channels, apps, inputs) and 2:3 posters (films, series). */
    private static final int R_CARD = 0, R_POSTER = 1;
    private static final int Z_ROW = 0, Z_BAR = 1;
    /** col value of the "Tout voir" label at the end of a shelf. */
    private static final int SEE_ALL = -2;

    private static final String[] LIBRARIES = {"Accueil", "Films", "Séries", "Direct", "Apps"};
    private static final int TAB_COUNT = LIBRARIES.length;

    // Chrome stops, in the order they are laid out.
    private static final int CH_SEARCH = 0, CH_SYNC = 1;
    private static final int CH_TAB = 100;   // + index of the library

    // MARK: - Geometry (designed at 1920×1080, scaled by k)

    private static final float BAR_H = 116, MARGIN = 96, BAR_TOP = 36;
    private static final float HEAD_H = 58, ROW_GAP = 44, CAP_H = 66;
    // 4 cards or 7 posters span exactly the plate's width (1728 = 4 × 402 + 3 × 40 = 7 × 216 + 6 × 36): every right
    // edge on screen lines up with the bar's. The widths are a hair under so the column count never rounds down.
    private static final float CARD_W = 401.9f, CARD_H = 226, CARD_GAP = 40, CARD_R = 24;
    private static final float POSTER_W = 215.9f, POSTER_H = 324, POSTER_GAP = 36, POSTER_R = 18;
    private static final float PILL_W = 300, PILL_H = 58, BUTTON_W = 78, BUTTON_H = 58, BUTTON_GAP = 14;
    /** Where the focused shelf's block starts (its title, then its cards): always here, the others move. */
    private static final float ROW_ANCHOR = 530;
    /**
     * The spotlight stands on the shelves, as a film's page stands on the bottom of the screen: its last line sits
     * SPOT_GAP above the focused shelf. Films and series keep room for three lines of story, so the title doesn't
     * jump when the story arrives from the panel; a channel or an app has none and sits lower.
     */
    private static final float SPOT_GAP = 72, SPOT_TITLE_W = 1300, SPOT_TEXT_W = 920;

    // Infuse 8's slate background, not black (until the first picture is ready).
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
    private int playSeen = -1;              // Prefs.playVersion the shelves were built with
    private int listSeen = -1;              // Prefs.listVersion the shelves were built with
    private String dataKey = "";
    private EditText field;                 // the activity's search box, laid over the pill
    private boolean searchOpen;
    private String query = "";

    private final RectF[] chromeRects = new RectF[2];
    private final RectF[] tabRects = new RectF[TAB_COUNT];
    private final int[] chromeOrder = new int[1 + TAB_COUNT + 1];
    private int chromeCount;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (spot != null && spot.ch != null && System.currentTimeMillis() >= spot.showUntil) {
                Epg.Show before = spot.show;
                if (showOf(spot, System.currentTimeMillis()) != before) buildSpot(true);
            }
            invalidate();
            if (resumed) main.postDelayed(this, 20_000);
        }
    };

    // MARK: - The TV's state, on the right of the bar: memory in use, storage in use, the weather

    private static final long STATUS_EVERY = 5000;
    private static final float STAT_PT = 24, STAT_GAP = 30;
    /** Read below the UI thread (a binder call to the system and a statfs): the frame never waits for them. */
    private final java.util.concurrent.ThreadPoolExecutor statusLane = lane("home-status", 1);
    private int ramPct = -1, diskPct = -1;
    private String statTemp = "", statSky = "";
    private final String[] statSymbol = new String[3], statValue = new String[3];
    private final float[] statIconX = new float[3], statTextX = new float[3];
    private final boolean[] statWarn = new boolean[3];
    private int statCount;
    private Paint statInk;
    private final HomeData.Listener weatherChanged = this::readStatus;
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long wait = busyUntil - android.os.SystemClock.uptimeMillis();
            if (wait > 0) {   // the cards are moving: read once they rest
                main.postDelayed(this, wait + 300);
                return;
            }
            readStatus();
            HomeData.get(getContext()).refreshIfStale();   // the weather every 30 min (it keeps its own clock)
            main.postDelayed(this, STATUS_EVERY);
        }
    };

    // MARK: - The spotlight (what has the focus) and the picture behind everything

    private Tile spot;                      // what the top of the screen is about
    private Iptv.Detail spotDetail;         // its facts, once the panel gave them
    private List<String> spotLines = new ArrayList<>(), spotStory = new ArrayList<>();
    private final List<Fact> spotFacts = new ArrayList<>();
    private float spotSize, spotA = 1, spotInfoA = 1;
    private int spotRoom;                   // lines kept for the story
    private boolean spotStoryLate;          // the story came after the rest: it fades in
    private long spotAt;                    // when the focus came to it (uptime)
    private int way = 1;                    // the way the focus last went along a shelf: 1 right, -1 left
    /** Until when the layout moves (uptime ms): the picture changes, and the work ahead runs, once it is still. */
    private volatile long busyUntil;
    /** Until when a cross-fade runs: the work ahead waits for it too (its frames are the GPU's heaviest). */
    private volatile long fadingUntil;
    private final java.util.Set<String> spotShown = new java.util.HashSet<>();
    /**
     * Below the UI thread, the cards around the focus get ready before the focus reaches them: two lanes ask the
     * panel (the focused card first), one decodes and bakes the pictures. Each focus move empties both queues and
     * fills them again in order of distance, so nothing waits behind a card that was left.
     */
    private final java.util.concurrent.ThreadPoolExecutor infoLane = lane("home-info", 2);
    private final java.util.concurrent.ThreadPoolExecutor bakeLane = lane("home-bake", 1);
    /** Baked pictures, ready to fade in (GPU memory: 8 MB each). */
    private final Map<String, Scenery> scenes = new java.util.LinkedHashMap<String, Scenery>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Scenery> e) { return size() > 6; }
    };
    private final java.util.Set<String> baking = new java.util.HashSet<>();
    /**
     * A picture changes once the focus has rested this long: holding a key doesn't flash a picture per card, and the
     * cross-fade (two full-screen layers, the GPU's heaviest frames) doesn't land on the cards' own movement.
     */
    private static final long SCENE_WAIT = 200;

    private static java.util.concurrent.ThreadPoolExecutor lane(String name, int threads) {
        return new java.util.concurrent.ThreadPoolExecutor(threads, threads, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** The "continue watching" cards ask on their own thread: the spotlight never waits behind them. */
    private final java.util.concurrent.ExecutorService watchPool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
            r.run();
        }, "home-watching");
        t.setDaemon(true);
        return t;
    });
    private Scenery scene, sceneNext;       // the picture shown, and the one fading in over it
    private volatile String sceneWant = "";
    private long sceneAt;
    private float sceneT;
    private Bitmap frostOf;                 // the frosted picture the bar's lens shaders were built on

    // MARK: - Paints

    private Paint rowTitle, cardTitle, cardMeta, seeAll, pillInk, tabInk, toast, empty, nameInk;
    private Paint spotTitle, spotMeta, spotStoryP, spotBadge, spotDot;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scenePaint = new Paint(Paint.FILTER_BITMAP_FLAG);   // the picture is 2/3 of the screen

    private long sheenAt;

    private final Matrix matrix = new Matrix();
    private final RectF screen = new RectF();
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // the materials, baked once per size (Liquid)
    private Liquid.Kit kitClear, kitLit, kitWell, kitSee, kitToast;
    private Bitmap plateFace, plateShadow, cardShadow, posterShadow;
    private int platePad, cardPad;
    private Shader cardRim, posterRim, cardLight, posterLight, sheenBand, sepShade;
    /**
     * The plate's backdrop is drawn in nested bands, each compressed more than the one inside it: a lens. Behind it
     * is now a photograph, not soft lights: the middle stays almost true (what is seen through the glass lines up
     * with what is around it), only the rim bends.
     */
    private static final float[] LENS_BANDS = {0.985f, 0.97f, 0.955f, LENS};
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
        nameInk = Ui.text(Ui.semibold, 30, 0xE6FFFFFF);
        nameInk.setTextAlign(Paint.Align.CENTER);
        spotTitle = Ui.text(Ui.displaySemibold, 66, INK);
        spotTitle.setLetterSpacing(-0.01f);
        spotMeta = Ui.text(Ui.medium, 24, 0xE0FFFFFF);
        spotStoryP = Ui.text(Ui.regular, 25, 0xD1FFFFFF);
        spotBadge = Ui.text(Ui.semibold, 18, 0xE0FFFFFF);
        spotDot = Ui.text(Ui.semibold, 24, 0x73FFFFFF);
        statInk = Ui.numbers(Ui.medium, 20, 0xEBFFFFFF);
        statInk.setTextAlign(Paint.Align.LEFT);
        for (Paint p : new Paint[]{spotTitle, spotMeta, spotStoryP, spotBadge, spotDot}) p.setTextAlign(Paint.Align.LEFT);
    }

    void resume() {
        resumed = true;
        Iptv.get(getContext()).addListener(this);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            rebuild();
        }
        syncWatching();
        requestFrame();
        main.removeCallbacks(clockTick);
        main.postDelayed(clockTick, 20_000);
        Epg guide = Epg.get(getContext());
        guide.addListener(guideChanged);
        guide.refreshIfStale();
        HomeData data = HomeData.get(getContext());
        data.addListener(weatherChanged);
        data.refreshIfStale();
        readStatus();
        main.removeCallbacks(statusTick);
        main.postDelayed(statusTick, STATUS_EVERY);
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
        HomeData.get(getContext()).removeListener(weatherChanged);
        Epg.get(getContext()).removeListener(guideChanged);
        main.removeCallbacks(clockTick);
        main.removeCallbacks(statusTick);
        main.removeCallbacks(autoDrive);
        main.removeCallbacks(sceneTick);
    }

    void destroy() {
        pause();
        infoLane.shutdownNow();
        watchPool.shutdownNow();
        bakeLane.shutdownNow();
        statusLane.shutdownNow();
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

    /**
     * A position was saved or forgotten (the player, "Retirer de « En cours »"): the shelves are built again, the
     * focus staying on the same shelf and as near as it can to the same card. Done while the page still covers the
     * home screen, so nothing jumps when it closes.
     */
    void syncWatching() {
        if (playSeen == Prefs.playVersion || searchOpen) return;
        reflow();
    }

    /** « Ma liste » changed (from a film's or a series' page): the shelves are built again under the page. */
    void syncList() {
        if (listSeen == Prefs.listVersion() || searchOpen) return;
        reflow();
    }

    private void reflow() {
        String at = rowIndex < rows.size() ? rows.get(rowIndex).title : "";
        int col = colIndex;
        rebuild();
        if (rows.isEmpty() || zone != Z_ROW) return;
        Row row = rows.get(rowIndex);
        if (row.title.equals(at) && col >= 0) row.col = Math.max(0, Math.min(col, row.items.size() - 1));
        colIndex = Math.max(0, row.col);
        ensureWindow(row);
        row.slide = row.slideTo;
        offsetTo = row.y;
        clampOffset();
        offset = offsetTo;
        invalidate();
    }

    // MARK: - The account, as shelves

    @Override public void onIptv() {
        main.post(() -> {
            if (!resumed) return;
            Epg.get(getContext()).refreshIfStale();
            if (!key().equals(dataKey)) rebuild();
        });
    }

    private String key() {
        Iptv i = Iptv.get(getContext());
        return tab + "|" + i.live().size() + "|" + i.movies().size() + "|" + i.series().size()
                + "|" + i.movieCategories().size() + "|" + i.seriesCategories().size()
                + "|" + Prefs.version + "|" + Prefs.listVersion();
    }

    private void rebuild() {
        dataKey = key();
        playSeen = Prefs.playVersion;
        listSeen = Prefs.listVersion();
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
        if (spot != null && w > 0) planAhead();
        warmGuide();
    }

    private void homeRows() {
        Iptv iptv = Iptv.get(getContext());
        Channels lineup = Channels.get(getContext());

        // En tête, les chaînes qu'il suit, avec leurs numéros (« 1 · TF1 ») : la TNT, puis ce qu'il a mis en favori
        List<Channels.Channel> favs = new ArrayList<>();
        for (Integer n : Prefs.favorites()) {
            Channels.Channel c = lineup.byNumber(n);
            if (c != null) favs.add(c);
            if (favs.size() >= 30) break;
        }
        if (!favs.isEmpty()) {
            Row row = new Row();
            row.title = "Mes chaînes";
            row.kind = R_CARD;
            row.seeTab = 3;
            row.captions = true;
            for (Channels.Channel c : favs) row.items.add(channelTile(c));   // the caption already carries the number
            rows.add(row);
        }

        // What was left unfinished, as Apple's "Up Next": 16:9 pictures, the title and the time left under them.
        Row watching = new Row();
        watching.title = "En cours";
        watching.kind = R_CARD;
        watching.captions = true;
        for (Iptv.Item i : Prefs.resume("vod")) watching.items.add(resumeTile(i));
        for (Iptv.Item i : Prefs.resume("series")) watching.items.add(resumeTile(i));
        if (!watching.items.isEmpty()) rows.add(watching);

        // What was put aside: Netflix's « Ma liste », a poster row right under what is being watched
        Row mine = new Row();
        mine.title = "Ma liste";
        mine.kind = R_POSTER;
        for (Iptv.Item i : Prefs.list()) mine.items.add(tile(i, i.url == null || i.url.isEmpty()));
        if (!mine.items.isEmpty()) rows.add(mine);

        // New episodes of the series followed (Ma liste, or left halfway): flagged when the catalogue moves on
        Row fresh = new Row();
        fresh.title = "Nouveaux épisodes";
        fresh.kind = R_POSTER;
        for (Iptv.Item i : newestEpisodes(iptv)) fresh.items.add(tile(i, true));
        if (!fresh.items.isEmpty()) rows.add(fresh);
        Follow.check(getContext(), () -> main.post(this::syncList));   // at most every six hours

        // Where Infuse shows its Favorites, the TV's apps: Netflix, SmartTube, Twitch, Prime, M6+, Steam Link…
        Row apps = new Row();
        apps.title = "Applications";
        apps.kind = R_CARD;
        apps.seeTab = 4;
        addApps(apps);
        if (!apps.items.isEmpty()) rows.add(apps);

        shelf("Nouveautés films", iptv.fresh("vod", 30, 24), false, 1, R_POSTER);
        shelf("Nouvelles séries", iptv.fresh("series", 60, 24), true, 2, R_POSTER);
        if (!lineup.sections.isEmpty()) channelShelf("Chaînes", lineup.sections.get(0).channels, 3);
        else shelf("Chaînes", frenchChannels(iptv, 24), false, 3, R_CARD);
        shelf("Les mieux notés", iptv.best("vod", 24), false, 1, R_POSTER);
        shelf("Séries", firstOf(iptv.series(), 24), true, 2, R_POSTER);
        suggestions(iptv);

        Row inputs = new Row();
        inputs.title = "Entrées";
        inputs.kind = R_CARD;
        inputs.seeTab = 4;
        addInputs(inputs);
        if (!inputs.items.isEmpty()) rows.add(inputs);
        fillWatching(watching);
    }

    /** The "continue watching" cards want a 16:9 picture and the time left: from the panel, one by one. */
    private void fillWatching(Row row) {
        final Iptv iptv = Iptv.get(getContext());
        for (Tile t : row.items) {
            final Tile tile = t;
            boolean episode = isEpisode(t);
            Iptv.Detail known = episode ? iptv.peek(true, t.seriesId) : iptv.peek(false, vodId(t.url));
            if (known != null) {
                watchingFrom(tile, known);
                continue;
            }
            watchPool.execute(() -> {
                Iptv.Detail d = episode ? iptv.series(tile.seriesId) : iptv.film(vodId(tile.url));
                if (d != null) main.post(() -> {
                    watchingFrom(tile, d);
                    invalidate();
                });
            });
        }
    }

    private void watchingFrom(Tile t, Iptv.Detail d) {
        String picture = d.backdrop;
        long duration = d.durationMs;
        if (isEpisode(t)) {
            for (Iptv.Episode e : d.episodes) {
                if (!e.url.equals(t.url)) continue;
                if (!e.still.isEmpty()) picture = e.still;
                if (e.durationMs > 0) duration = e.durationMs;
                break;
            }
            if (!d.title.isEmpty()) t.title = DetailView.seriesTitle(d.title);
        }
        if (picture.isEmpty() && !d.poster.isEmpty() && t.image.isEmpty()) t.image = d.poster;
        t.wide = picture;
        if (duration > 0 && t.progress > 0) t.left = (long) (duration * (1 - t.progress));
        t.capWidth = -1;
    }

    private void filmRows() {
        Iptv iptv = Iptv.get(getContext());
        shelf("Nouveautés", iptv.fresh("vod", 30, 24), false, -1, R_POSTER);
        shelf("Les mieux notés", iptv.best("vod", 24), false, -1, R_POSTER);
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
            shelf(clean(c.name), items, false, -1, R_POSTER);
            built++;
        }
    }

    private void seriesRows() {
        Iptv iptv = Iptv.get(getContext());
        shelf("Nouvelles séries", iptv.fresh("series", 60, 24), true, -1, R_POSTER);
        shelf("Les mieux notées", iptv.best("series", 24), true, -1, R_POSTER);
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
            shelf(clean(c.name), items, true, -1, R_POSTER);
            built++;
        }
    }

    /**
     * The channels as a box lists them: the TNT in its numbers, then the lineup's sections (cinema and series, sport,
     * youth, news, discovery, music…), each card showing what is on; then the panel's other countries.
     */
    private void liveRows() {
        Iptv iptv = Iptv.get(getContext());
        Channels lineup = Channels.get(getContext());
        for (Channels.Section s : lineup.sections) {
            String key = "section:" + s.title;
            if (Prefs.hiddenCategory(key)) continue;
            channelShelf(s.title.equals("TNT") ? "TNT" : s.title, s.channels, -1);
            Row made = rows.get(rows.size() - 1);
            made.catId = key;   // INFO puts a whole section away too
            made.catName = s.title;
        }
        if (lineup.sections.isEmpty()) shelf("Chaînes françaises", frenchChannels(iptv, 24), false, -1, R_CARD);
        List<Iptv.Item> all = iptv.live();
        int built = 0;
        for (Iptv.Category c : iptv.liveCategories()) {
            if (built >= 10) break;
            if (c.name.toUpperCase(Locale.ROOT).contains("FRANCE")) continue;
            if (Prefs.hiddenCategory(c.id)) continue;   // put away with INFO on its row
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
            shelf(clean(c.name), items, false, -1, R_CARD);
            Row made = rows.get(rows.size() - 1);
            made.catId = c.id;
            made.catName = clean(c.name);
            built++;
        }
        // the categories put away, so they can come back without a hidden setting somewhere
        List<String[]> away = Prefs.hiddenCategories();
        if (!away.isEmpty()) {
            Row row = new Row();
            row.title = "Catégories masquées";
            row.kind = R_CARD;
            row.restore = true;
            for (String[] f : away) {
                Tile t = new Tile();
                t.title = f[1];
                t.url = "hidden:" + f[0];
                t.meta = "Catégorie";   // the spotlight says what it is, not « Film »
                t.symbol = "chevron_right";
                row.items.add(t);
            }
            rows.add(row);
        }
    }

    /** A shelf of the lineup's channels: logo cards, and under each what it shows now and for how long still. */
    private void channelShelf(String title, List<Channels.Channel> channels, int seeTab) {
        if (channels.isEmpty()) return;
        Row row = new Row();
        row.title = title;
        row.kind = R_CARD;
        row.seeTab = seeTab;
        row.captions = true;
        for (Channels.Channel c : channels) row.items.add(channelTile(c));
        rows.add(row);
    }

    private static Tile channelTile(Channels.Channel c) {
        Tile t = new Tile();
        t.title = c.name;
        t.image = c.logo;
        t.url = c.url();
        t.live = true;
        t.ch = c;
        return t;
    }

    /** What a channel card shows now: read from the guide's memory when the programme ends (and every minute). */
    private Epg.Show showOf(Tile t, long now) {
        if (t.ch == null || t.ch.epg.isEmpty()) return null;
        if (now >= t.showUntil) {
            Epg.Show s = Epg.get(getContext()).now(t.ch.epg, now);
            if (s != t.show) t.capWidth = -1;
            t.show = s;
            // the time left in the caption moves every minute
            t.showUntil = s != null ? Math.min(s.stop, now + 60_000 - now % 60_000) : now + 30_000;
            t.capWidth = -1;
        }
        return t.show;
    }

    /** The guide landed (or more of it): every channel card reads its programme again, the spotlight too. */
    private final Epg.Listener guideChanged = () -> {
        for (Row row : rows) for (Tile t : row.items) if (t.ch != null) t.showUntil = 0;
        if (spot != null && spot.ch != null) {
            spot.showUntil = 0;
            buildSpot(true);
        }
        invalidate();
    };

    private void warmGuide() {
        List<String> ids = new ArrayList<>();
        for (Row row : rows) for (Tile t : row.items) if (t.ch != null && !t.ch.epg.isEmpty()) ids.add(t.ch.epg);
        if (!ids.isEmpty()) Epg.get(getContext()).warm(ids);
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

    private void shelf(String title, List<Iptv.Item> items, boolean series, int seeTab, int kind) {
        if (items.isEmpty()) return;
        Row row = new Row();
        row.title = title;
        row.kind = kind;
        row.seeTab = seeTab;
        Set<String> seen = new HashSet<>();
        for (Iptv.Item i : items) {
            if (!seen.add(norm(pretty(i.name)))) continue;
            row.items.add(tile(i, series));
        }
        if (!row.items.isEmpty()) rows.add(row);
    }

    /** The series followed that have episodes the panel added since the last look, newest first. */
    private List<Iptv.Item> newestEpisodes(Iptv iptv) {
        List<Iptv.Item> out = new ArrayList<>();
        for (Iptv.Item i : iptv.series()) {
            if (!Follow.series(getContext()).contains(i.id)) continue;
            if (Follow.newEpisodes(getContext(), i.id) <= 0) continue;
            out.add(i);
            if (out.size() >= 24) break;
        }
        return out;
    }

    /**
     * « Parce que tu as regardé X » — the same category as the last thing watched, best rated first — and the saga it
     * belongs to, when three titles or more share its name (« John Wick », « Le Seigneur des anneaux »…). Everything
     * comes from the account: nothing outside the subscription is ever proposed.
     */
    private void suggestions(Iptv iptv) {
        String[] lw = Prefs.lastWatched();
        if (lw == null) return;
        String name = lw[0], url = lw[1], seriesId = lw[2];
        int cut = name == null ? -1 : name.indexOf(" · S");
        if (cut > 0) name = name.substring(0, cut);   // an episode is saved as « Série · S1, É4 »
        boolean series = seriesId != null && !seriesId.isEmpty();   // an episode: its series id is set
        List<Iptv.Item> all = series ? iptv.series() : iptv.movies();
        Iptv.Item last = null;
        for (Iptv.Item it : all) {
            if (series ? seriesId.equals(it.id) : url.equals(it.url)) {
                last = it;
                break;
            }
        }
        if (last != null && !last.group.isEmpty()) {
            List<Iptv.Item> same = new ArrayList<>();
            for (Iptv.Item it : all) {
                if (it == last || !last.group.equals(it.group)) continue;
                if (it.name.startsWith("─") || it.name.contains("✧")) continue;
                same.add(it);
                if (same.size() >= 40) break;
            }
            if (same.size() >= 4) {
                same.sort((a, b) -> Float.compare(b.rating, a.rating));
                shelf("Parce que tu as regardé " + pretty(name), same, series, -1, R_POSTER);
            }
        }
        String stem = sagaTitle(name);
        if (!series && stem.length() >= 4) {   // a saga is a film's: same name, numbered chapters
            String key = norm(stem);
            List<Iptv.Item> saga = new ArrayList<>();
            int count = 0;
            for (Iptv.Item it : all) {
                if (!norm(sagaTitle(it.name)).equals(key)) continue;
                count++;
                if (saga.size() < 24) saga.add(it);
            }
            if (count >= 3) shelf("La saga " + stem, saga, series, -1, R_POSTER);
        }
    }

    /** « John Wick : Chapitre 4 » → « John Wick » : the title without its episode number, for the sagas. */
    private static String sagaTitle(String title) {
        String t = pretty(title);
        for (int i = 0; i < 3; i++) {
            java.util.regex.Matcher m = SAGA_TAIL.matcher(t);
            if (!m.find() || m.start() < 3) break;
            t = t.substring(0, m.start()).trim();
        }
        return t;
    }

    private static final java.util.regex.Pattern SAGA_TAIL = java.util.regex.Pattern.compile(
            "(?i)\\s*[-–:·,.]?\\s*(chapitre\\s*)?(\\d{1,2}|iv|vi{0,3}|ix|xi{0,3}|x|v|iii|ii|i|partie\\s*\\d)\\s*$");

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
        t.seriesId = i.id == null ? "" : i.id;   // an episode: its series (saved with the position)
        int cut = t.title.indexOf(" · S");
        if (cut > 0) {   // an episode is saved as "Série · S1, É2"
            t.episode = t.title.substring(cut + 3).trim();
            t.title = t.title.substring(0, cut).trim();
        }
        long pos = Prefs.positionOf(t.url);
        if (t.progress > 0.01f && pos > 0) t.left = (long) (pos * (1 - t.progress) / t.progress);
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
        bake();
        // the picture is baked for this size: again from scratch
        scene = sceneNext = null;
        frostOf = null;
        sceneWant = "";
        synchronized (scenes) { scenes.clear(); }
        if (spot != null) buildSpot(false);
        requestScenery();
        // the facts' table opens below the UI thread, before the first card asks it
        infoLane.execute(() -> Iptv.get(getContext()).lite(false, "0"));
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
        kitSee = Liquid.capsule(Liquid.FOCUS, Math.round(40 * k), k, true);
        kitToast = Liquid.capsule(Liquid.DARK, Math.round(56 * k), k, true);

        int pw = Math.round(barBox.width()), ph = Math.round(barBox.height());
        plateFace = Liquid.face(Liquid.PLATE, pw, ph, barRadius, k);
        platePad = Math.round(28 * k);
        plateShadow = Liquid.shadow(pw, ph, barRadius, platePad, 22 * k, 0.42f, 0.5f, -10 * k);   // under, not around

        cardPad = Math.round(34 * k);
        int cw = Math.round(CARD_W * k), chh = Math.round(CARD_H * k);
        int qw = Math.round(POSTER_W * k), qh = Math.round(POSTER_H * k);
        cardShadow = Liquid.shadow(cw, chh, CARD_R * k, cardPad, 24 * k, 0.62f, 0.5f, -6 * k);
        posterShadow = Liquid.shadow(qw, qh, POSTER_R * k, cardPad, 24 * k, 0.66f, 0.5f, -6 * k);
        cardRim = rimShader(cw, chh);
        posterRim = rimShader(qw, qh);
        cardLight = new LinearGradient(0, 0, 0, chh * 0.55f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        posterLight = new LinearGradient(0, 0, 0, qh * 0.45f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        float band = cw * 0.42f;
        sheenBand = new LinearGradient(-band, -band * 0.35f, band, band * 0.35f,
                new int[] {0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        float sepLen = barBox.height() - 44 * k;
        sepShade = new LinearGradient(0, 0, 0, sepLen, new int[] {0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

        // the glass shows the picture behind it, more vivid and a little brighter (Apple's vibrancy)
        ColorMatrix vibrancy = new ColorMatrix();
        vibrancy.setSaturation(0.82f);   // mostly clear: Liquid Glass takes a hint of what is behind it, not its colour
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
        return (kind == R_POSTER ? POSTER_W : CARD_W) * k;
    }

    private float th(int kind) {
        return (kind == R_POSTER ? POSTER_H : CARD_H) * k;
    }

    private float gap(int kind) {
        return (kind == R_POSTER ? POSTER_GAP : CARD_GAP) * k;
    }

    private float step(int kind) {
        return tw(kind) + gap(kind);
    }

    private int cols(int kind) {
        float usable = w - 2 * MARGIN * k;
        return Math.max(1, (int) ((usable + gap(kind)) / step(kind)));
    }

    private void layout() {
        float y = 0;
        for (Row row : rows) {
            row.y = y;
            row.h = HEAD_H * k + th(row.kind) + (row.captions ? CAP_H * k : 0);
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

        float bw = BUTTON_W * k;
        chromeRects[CH_SYNC].set(inR - bw, top, inR, top + hh);   // concentric with the plate's end, like the search
        sepA = chromeRects[CH_SEARCH].right + 22 * k;
        layoutStatus();

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
        chromeOrder[chromeCount++] = CH_SYNC;
        chromeIndex = Math.max(0, Math.min(chromeIndex, chromeCount - 1));
    }

    /**
     * The state sits between the libraries and the refresh capsule, set from the right: memory, storage, weather.
     * Each is a quiet symbol and a number in fixed-width digits; past 90 % a figure turns orange. Not a control: the
     * focus goes from the last library straight to the refresh capsule.
     */
    private void layoutStatus() {
        statCount = 0;
        addStat("memory", ramPct < 0 ? "" : ramPct + "\u202F%", ramPct >= 90);
        addStat("storage", diskPct < 0 ? "" : diskPct + "\u202F%", diskPct >= 90);
        addStat(statSky, statTemp, false);
        float x = chromeRects[CH_SYNC].left - 26 * k, pt = STAT_PT * k;
        for (int i = statCount - 1; i >= 0; i--) {
            float tw = statInk.measureText(statValue[i]);
            float iw = Symbols.width(getContext(), statSymbol[i], pt);
            statTextX[i] = x - tw;
            statIconX[i] = x - tw - 9 * k - iw / 2;
            x = x - tw - 9 * k - iw - STAT_GAP * k;
        }
        sepB = (statCount == 0 ? chromeRects[CH_SYNC].left : x + STAT_GAP * k) - 22 * k;
    }

    private void addStat(String symbol, String value, boolean warn) {
        if (symbol == null || symbol.isEmpty() || value == null || value.isEmpty()) return;
        statSymbol[statCount] = symbol;
        statValue[statCount] = value;
        statWarn[statCount] = warn;
        statCount++;
    }

    /** Memory and storage in use (rounded %), and the weather already fetched; repaints only when a figure moves. */
    private void readStatus() {
        final android.content.Context app = getContext().getApplicationContext();
        try {
            statusLane.execute(() -> {
                int ram = -1, disk = -1;
                try {
                    android.app.ActivityManager am =
                            (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    if (mi.totalMem > 0) ram = (int) Math.round(100.0 * (mi.totalMem - mi.availMem) / mi.totalMem);
                } catch (RuntimeException ignored) {
                }
                try {
                    android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                    long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
                    if (total > 0) disk = (int) Math.round(100.0 * (total - free) / total);
                } catch (RuntimeException ignored) {
                }
                final int r = ram, d = disk;
                main.post(() -> applyStatus(r, d));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void applyStatus(int ram, int disk) {
        HomeData.Weather wx = HomeData.get(getContext()).weather;
        boolean has = Prefs.weather() && !Double.isNaN(wx.temp) && wx.code >= 0;
        String temp = has ? HomeData.degrees(wx.temp) : "";
        String sky = has ? Symbols.weather(wx.code, wx.isDay) : "";
        if (ram == ramPct && disk == diskPct && temp.equals(statTemp) && sky.equals(statSky)) return;
        ramPct = ram;
        diskPct = disk;
        statTemp = temp;
        statSky = sky;
        if (w > 0) layoutStatus();
        invalidate();
    }

    private RectF chromeRect(int id) {
        return id >= CH_TAB ? tabRects[id - CH_TAB] : chromeRects[id];
    }

    private int focusedChrome() {
        return chromeOrder[Math.max(0, Math.min(chromeIndex, chromeCount - 1))];
    }

    private void clampOffset() {
        float last = rows.isEmpty() ? 0 : rows.get(rows.size() - 1).y;
        offsetTo = Math.max(0, Math.min(last, offsetTo));
    }

    /** The focused shelf goes to the anchor, under the spotlight: the shelf below always peeks at the bottom. */
    private void reveal() {
        if (rows.isEmpty()) return;
        offsetTo = rows.get(rowIndex).y;
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
        // how much the layout itself moves this frame (px, a card's focus counting 100): the work ahead and the
        // picture wait for it to be still, not for the glint or a fade
        float stir = moving && lensA > 0.04f ? 2 : 0;
        if (Math.abs(offsetTo - offset) > 0.35f) {
            float d = (offsetTo - offset) * Math.min(1f, dt * 12f);
            offset += d;
            stir = Math.max(stir, Math.abs(d));
            moving = true;
        } else {
            offset = offsetTo;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (Math.abs(row.slideTo - row.slide) > 0.35f) {
                float d = (row.slideTo - row.slide) * Math.min(1f, dt * 16f);
                row.slide += d;
                stir = Math.max(stir, Math.abs(d));
                moving = true;
            } else {
                row.slide = row.slideTo;
            }
            float show = i >= rowIndex ? 1f : 0f;   // the shelves above the focused one step aside
            if (row.alpha != show) {
                float was = row.alpha;
                row.alpha = Math.abs(show - row.alpha) < 0.01f ? show : row.alpha + (show - row.alpha) * Math.min(1f, dt * 14f);
                stir = Math.max(stir, Math.abs(row.alpha - was) * 100);
                moving = true;
            }
            if (row.focus.length != row.items.size()) continue;
            for (int j = 0; j < row.items.size(); j++) {
                float target = i == rowIndex && zone == Z_ROW && j == row.col ? 1f : 0f;
                float f = row.focus[j];
                if (Math.abs(target - f) < 0.003f) {
                    row.focus[j] = target;
                    continue;
                }
                float d = (target - f) * Math.min(1f, dt * 15f);
                row.focus[j] = f + d;
                stir = Math.max(stir, Math.abs(d) * 100);
                moving = true;
            }
        }
        if (spotA < 1) {
            spotA = Math.min(1f, spotA + dt / 0.2f);
            moving = true;
        }
        if (spotInfoA < 1) {
            spotInfoA = Math.min(1f, spotInfoA + dt / 0.28f);
            moving = true;
        }
        if (sceneNext != null) {
            if (sceneAt == 0) sceneAt = now;
            sceneT = Math.min(1f, (now - sceneAt) / 260f);
            if (sceneT >= 1) {
                scene = sceneNext;
                sceneNext = null;
            }
            moving = true;
        }
        invalidate();
        if (sheenAt != 0 && System.currentTimeMillis() - sheenAt < SHEEN_MS + 20) moving = true;
        long up = android.os.SystemClock.uptimeMillis();
        if (stir > 1.5f) busyUntil = up + 50;
        fadingUntil = sceneNext != null ? up + 50 : 0;
        if (moving) requestFrame();
        wasMoving = moving;
    }

    private boolean wasMoving;

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

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

    // MARK: - The spotlight

    /** One fact of the line under the spotlight's title. */
    private static final class Fact {
        static final int TEXT = 0, RATING = 1, BADGE = 2, PROGRESS = 3;
        final int kind;
        final String text;
        final float value;
        boolean late;         // known only once the panel answered: fades in

        Fact(int kind, String text, float value, boolean late) {
            this.kind = kind;
            this.text = text;
            this.value = value;
            this.late = late;
        }
    }

    private static boolean isEpisode(Tile t) {
        return t.url.contains("/series/") && !t.seriesId.isEmpty();
    }

    private static final java.util.regex.Pattern MOVIE_ID = java.util.regex.Pattern.compile("/movie/[^/]+/[^/]+/(\\d+)\\.");

    private static String vodId(String url) {
        java.util.regex.Matcher m = MOVIE_ID.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    /** What the panel already told about this card, in memory (nothing is read or asked here). */
    private Iptv.Detail known(Tile t) {
        if (!isContent(t)) return null;
        String id = idOf(t);
        return id.isEmpty() ? null : Iptv.get(getContext()).peek(seriesOf(t), id);
    }

    /** A film, a series or an episode: what the panel has facts about. */
    private static boolean isContent(Tile t) {
        return t != null && t.app == null && !t.live && (t.series || isEpisode(t) || t.url.contains("/movie/"));
    }

    private static boolean seriesOf(Tile t) {
        return t.series || isEpisode(t);
    }

    private static String idOf(Tile t) {
        return seriesOf(t) ? t.seriesId : vodId(t.url);
    }

    private static boolean sameTile(Tile a, Tile b) {
        return a != null && b != null && a.title.equals(b.title) && a.url.equals(b.url) && a.seriesId.equals(b.seriesId)
                && a.app == b.app;
    }

    /**
     * The focus moved to another card: the spotlight takes it at once — its title, and what the lists say of it
     * (Facts: story, genres, year, length), so the text is never waited for. Its picture follows once the focus
     * rests (SCENE_WAIT), usually already baked by the time it does.
     */
    private void syncSpot() {
        Tile t = focusedTile();
        if (t == null || t == spot) return;
        if (sameTile(t, spot)) {   // the shelves were rebuilt under the same card: nothing to show again
            spot = t;
            return;
        }
        spot = t;
        spotAt = android.os.SystemClock.uptimeMillis();
        busyUntil = Math.max(busyUntil, spotAt + 120);   // the cards start moving: the lanes wait from now
        spotA = 0;
        spotDetail = known(t);
        if (t.lite == null && !t.liteAsked && isContent(t)) {   // one indexed read, ~1 ms
            t.liteAsked = true;
            t.lite = Iptv.get(getContext()).lite(seriesOf(t), idOf(t));
        }
        buildSpot(false);
        spotInfoA = 1;
        planAhead();
        main.removeCallbacks(sceneTick);
        main.postDelayed(sceneTick, SCENE_WAIT);
        requestFrame();
    }

    /** The picture changes once the focus has rested SCENE_WAIT and the cards have stopped moving. */
    private final Runnable sceneTick = new Runnable() {
        @Override public void run() {
            long wait = busyUntil - android.os.SystemClock.uptimeMillis();
            if (wait > 0) {
                main.postDelayed(this, wait + 10);
                return;
            }
            requestScenery();
        }
    };

    /** The full facts of the focused card landed (or it was found that there are none). */
    private void spotGotFull(Tile t, Iptv.Detail d) {
        if (spot != t) return;
        if (d != null && d != spotDetail) {
            spotDetail = d;
            // the lists said the rest: lay the lines out again (5 ms) only if the answer adds something
            if (t.lite == null || !d.age.isEmpty() || isEpisode(t) || (t.series && !d.seasons.isEmpty())) buildSpot(true);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - spotAt >= SCENE_WAIT && now >= busyUntil) requestScenery();
        requestFrame();
    }

    /** Empties both lanes and fills them again: the focused card, its neighbours, the shelf below, then further. */
    private void planAhead() {
        infoLane.getQueue().clear();
        bakeLane.getQueue().clear();
        if (rows.isEmpty() || rowIndex < 0 || rowIndex >= rows.size()) return;
        List<Tile> near = new ArrayList<>(), far = new ArrayList<>();
        Row row = rows.get(rowIndex);
        int c = Math.max(0, row.col);
        pick(near, row, c);
        pick(near, row, c + way);   // the next card the way the focus goes
        pick(far, row, c - way);
        if (rowIndex + 1 < rows.size()) {   // baked too: the work ahead only runs while the screen is still
            Row below = rows.get(rowIndex + 1);
            pick(near, below, below.col);
            pick(far, below, below.col + 1);
        }
        for (int d = 2; d <= 6; d++) pick(far, row, c + d);
        pick(far, row, c - 2);
        if (rowIndex > 0) {
            Row above = rows.get(rowIndex - 1);
            pick(far, above, above.col);
        }
        for (Tile t : near) infoLane.execute(() -> prepare(t, true));
        for (Tile t : far) infoLane.execute(() -> prepare(t, false));
    }

    private static void pick(List<Tile> into, Row row, int col) {
        if (col < 0 || col >= row.items.size()) return;
        Tile t = row.items.get(col);
        if (!into.contains(t)) into.add(t);
    }

    /**
     * On the info lane: a card's full facts (memory, disk, else the panel — a film's backdrop is only there), then its
     * picture: baked when it is next to the focus, only fetched to disk when it is further.
     */
    private void prepare(Tile t, boolean near) {
        // what is next to the focus at background priority; further, only when nothing else wants the CPU
        android.os.Process.setThreadPriority(near ? android.os.Process.THREAD_PRIORITY_BACKGROUND
                : android.os.Process.THREAD_PRIORITY_LOWEST);
        if (!whenStill()) return;
        Iptv iptv = Iptv.get(getContext());
        Iptv.Detail full = null;
        if (isContent(t)) {
            boolean series = seriesOf(t);
            String id = idOf(t);
            if (t.lite == null && !t.liteAsked) {
                t.liteAsked = true;
                t.lite = iptv.lite(series, id);
            }
            boolean asked = id.isEmpty();
            if (!id.isEmpty()) {
                full = iptv.cached(series, id);
                // a film's picture is only in its full facts; a series' seasons only matter next to the focus
                if (full == null && (near || !series)) {
                    full = series ? iptv.series(id) : iptv.film(id);
                    asked = true;
                }
            }
            if (full != null || asked) t.fullTried = true;
            final Iptv.Detail d = full;
            main.post(() -> spotGotFull(t, d));
        }
        String key = sceneKey(t, full);
        if (key == null) return;
        if (near) bake(t, key);
        else if (key.startsWith("bd:")) Posters.get(getContext()).warm(key.substring(3));
    }

    /** The lists' facts first (they were shown first), the panel's full answer for what they don't say. */
    private static Iptv.Detail merged(Iptv.Detail full, Iptv.Detail lite) {
        if (lite == null) return full;
        if (full == null) return lite;
        Iptv.Detail m = new Iptv.Detail();
        m.title = lite.title.isEmpty() ? full.title : lite.title;
        m.plot = lite.plot.isEmpty() ? full.plot : lite.plot;
        m.genre = lite.genre.isEmpty() ? full.genre : lite.genre;
        m.year = lite.year.isEmpty() ? full.year : lite.year;
        m.rating = lite.rating > 0 ? lite.rating : full.rating;
        m.durationMs = lite.durationMs > 0 ? lite.durationMs : full.durationMs;
        m.backdrop = lite.backdrop.isEmpty() ? full.backdrop : lite.backdrop;
        m.age = full.age;
        m.seasons.addAll(full.seasons);
        m.episodes.addAll(full.episodes);
        return m;
    }

    /**
     * The spotlight's lines, worked out when its card or its facts change (never per frame). `more`: the same card,
     * with more facts: what was already on screen stays, what is new fades in.
     */
    private void buildSpot(boolean more) {
        Tile t = spot;
        if (t == null || w == 0) return;
        Iptv.Detail full = spotDetail, d = merged(full, t.lite);
        boolean hadStory = !spotStory.isEmpty();
        String title = t.title;
        if ((t.series || isEpisode(t)) && d != null && !d.title.isEmpty()) title = DetailView.seriesTitle(d.title);
        long now = System.currentTimeMillis();
        Epg.Show show = t.ch != null ? showOf(t, now) : null;
        if (show != null) title = show.title;
        float maxW = SPOT_TITLE_W * k;
        spotSize = 66 * k;
        spotTitle.setTextSize(spotSize);
        if (spotTitle.measureText(title) <= maxW) {
            spotLines = new ArrayList<>();
            spotLines.add(title);
        } else {
            spotSize = 56 * k;
            spotTitle.setTextSize(spotSize);
            spotLines = wrap(title, spotTitle, maxW, 2);
        }

        spotFacts.clear();
        if (t.app != null) {
            spotFacts.add(new Fact(Fact.TEXT, t.symbol.isEmpty() ? "Application" : "Entrée", 0, false));
        } else if (t.ch != null) {
            // a channel: its programme, as a box's banner says it
            if (show != null) {
                String left = Epg.left(show, now);
                spotFacts.add(new Fact(Fact.PROGRESS, Character.toUpperCase(left.charAt(0)) + left.substring(1),
                        show.progress(now), false));
                spotFacts.add(new Fact(Fact.TEXT, Epg.span(show), 0, false));
            }
            spotFacts.add(new Fact(Fact.TEXT, t.ch.number + " · " + t.title, 0, false));
            if (t.ch.replay != null) spotFacts.add(new Fact(Fact.BADGE, "REPLAY " + t.ch.replayDays + " J", 0, false));
        } else if (t.live) {
            spotFacts.add(new Fact(Fact.TEXT, "Chaîne en direct", 0, false));
        } else {
            boolean episode = isEpisode(t);
            if (t.progress > 0.01f && t.left > 0) {
                spotFacts.add(new Fact(Fact.PROGRESS, "Il reste " + span(t.left), t.progress, false));
            }
            if (episode && !t.episode.isEmpty()) spotFacts.add(new Fact(Fact.TEXT, t.episode, 0, false));
            if (!episode && d == null) spotFacts.add(new Fact(Fact.TEXT, t.series ? "Série" : "Film", 0, false));
            float rating = d == null ? 0 : d.rating > 10 ? d.rating / 10f : d.rating;
            if (rating >= 1 && rating < 9.95f) {
                spotFacts.add(new Fact(Fact.RATING, String.format(Locale.FRANCE, "%.1f", rating), 0, false));
            }
            String year = d != null && !d.year.isEmpty() ? d.year : t.meta;
            if (year != null && !year.isEmpty() && !episode) spotFacts.add(new Fact(Fact.TEXT, year, 0, false));
            // what the lists know first; what only the full answer knows (seasons, age) last, so it adds on the
            // right instead of pushing the rest
            if (d != null) {
                if (!t.series && !episode && d.durationMs > 0) spotFacts.add(new Fact(Fact.TEXT, span(d.durationMs), 0, false));
                String genres = DetailView.genres(d.genre, 3);
                if (!genres.isEmpty()) spotFacts.add(new Fact(Fact.TEXT, genres, 0, false));
                if (t.series && !d.seasons.isEmpty()) {
                    int n = d.seasons.size();
                    spotFacts.add(new Fact(Fact.TEXT, n > 1 ? n + " saisons" : d.episodes.size() + " épisodes", 0, false));
                }
                if (!d.age.isEmpty()) spotFacts.add(new Fact(Fact.BADGE, d.age, 0, false));
            }
        }

        spotStory = new ArrayList<>();
        boolean content = t.app == null && (!t.live || show != null);
        spotRoom = content ? Math.max(1, 3 - (spotLines.size() - 1)) : 0;
        if (show != null) {
            spotStoryP.setTextSize(26 * k);
            Epg.Show next = Epg.get(getContext()).next(t.ch.epg, now);
            int room = Math.max(1, spotRoom - (next != null ? 1 : 0));
            if (!show.info.isEmpty()) spotStory = wrap(show.info, spotStoryP, SPOT_TEXT_W * k, room);
            if (next != null) {
                spotStory = new ArrayList<>(spotStory);
                spotStory.add(fit(spotStoryP, "À suivre · " + Epg.clock(next.start) + " · " + next.title, SPOT_TEXT_W * k));
            }
        } else if (d != null) {
            String story = d.plot;
            if (isEpisode(t)) {   // an episode tells its own story (only the full answer has it)
                story = "";
                if (full != null) {
                    for (Iptv.Episode e : full.episodes) {
                        if (e.url.equals(t.url)) {
                            story = e.plot;
                            break;
                        }
                    }
                    if (story.isEmpty()) story = d.plot;
                }
            }
            spotStoryP.setTextSize(26 * k);
            spotStory = wrap(story, spotStoryP, SPOT_TEXT_W * k, Math.max(1, spotRoom));
        }

        boolean late = false;
        for (Fact f : spotFacts) {
            f.late = more && !spotShown.contains(f.kind + f.text);
            late |= f.late;
        }
        spotShown.clear();
        for (Fact f : spotFacts) spotShown.add(f.kind + f.text);
        spotStoryLate = more && !hadStory && !spotStory.isEmpty();
        if (!more) spotStoryLate = false;
        if (late || spotStoryLate) spotInfoA = 0;
    }

    /** "1 h 35 min", "52 min". */
    private static String span(long ms) {
        long min = Math.max(1, Math.round(ms / 60000.0));
        if (min < 60) return min + " min";
        long hh = min / 60, mm = min % 60;
        return mm == 0 ? hh + " h" : hh + " h " + mm + " min";
    }

    /** Title, facts and story of what has the focus, over its own picture, at the top left. */
    private void drawSpotlight(Canvas c) {
        if (spot == null || spotLines.isEmpty()) return;
        float a = ease(spotA), ia = a * ease(spotInfoA);
        float x = MARGIN * k;
        spotTitle.setTextSize(spotSize);
        spotTitle.setColor(Ui.alpha(INK, a));
        float line = spotSize * 1.06f;
        // from the bottom up: the story's room, the facts, the title's lines
        float storyBase = (ROW_ANCHOR - SPOT_GAP) * k - Math.max(0, spotRoom - 1) * 37 * k;
        float factsBase = spotRoom > 0 ? storyBase - 48 * k : (ROW_ANCHOR - SPOT_GAP) * k;
        float base = factsBase - 54 * k - (spotLines.size() - 1) * line;
        for (String l : spotLines) {
            c.drawText(l, x - Ui.leftBearing(spotTitle, l), base, spotTitle);
            base += line;
        }
        base = factsBase;

        // the facts, as on the film's page: a progress bar and the time left, a rating, the year, the length…
        spotMeta.setTextSize(24 * k);
        spotBadge.setTextSize(18 * k);
        spotDot.setTextSize(24 * k);
        float mid = base - Ui.halfCap(spotMeta), right = w - MARGIN * k, dot = spotDot.measureText("·");
        boolean first = true;
        for (Fact f : spotFacts) {
            float fa = f.late ? ia : a;
            if (fa <= 0.003f) continue;
            float fw = factWidth(f);
            if (x + fw > right) break;
            if (!first) {
                spotDot.setColor(Ui.alpha(0x73FFFFFF, fa));
                c.drawText("·", x + 15 * k - dot / 2, base, spotDot);
                x += 30 * k;
            }
            first = false;
            switch (f.kind) {
                case Fact.PROGRESS: {
                    float bw = 110 * k, bh = 6 * k;
                    r.set(x, mid - bh / 2, x + bw, mid + bh / 2);
                    fill.setColor(Ui.alpha(0x47FFFFFF, fa));
                    c.drawRoundRect(r, bh / 2, bh / 2, fill);
                    r.right = x + Math.max(14 * k, bw * f.value);
                    fill.setColor(Ui.alpha(0xF2FFFFFF, fa));
                    c.drawRoundRect(r, bh / 2, bh / 2, fill);
                    spotMeta.setColor(Ui.alpha(0xE0FFFFFF, fa));
                    c.drawText(f.text, x + bw + 14 * k, base, spotMeta);
                    break;
                }
                case Fact.RATING:
                    Symbols.draw(getContext(), c, "star", x + 11 * k, mid - 1 * k, 21 * k, Ui.alpha(0xE0FFFFFF, fa));
                    spotMeta.setColor(Ui.alpha(0xE0FFFFFF, fa));
                    c.drawText(f.text, x + 28 * k, base, spotMeta);
                    break;
                case Fact.BADGE: {
                    float bw = spotBadge.measureText(f.text) + 18 * k, bh = 30 * k;
                    r.set(x, mid - bh / 2, x + bw, mid + bh / 2);
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(1.5f * k);
                    stroke.setColor(Ui.alpha(0x8CFFFFFF, fa));
                    c.drawRoundRect(r, 7 * k, 7 * k, stroke);
                    spotBadge.setColor(Ui.alpha(0xE0FFFFFF, fa));
                    c.drawText(f.text, x + 9 * k, mid + Ui.halfCap(spotBadge), spotBadge);
                    break;
                }
                default:
                    spotMeta.setColor(Ui.alpha(0xE0FFFFFF, fa));
                    c.drawText(f.text, x, base, spotMeta);
                    break;
            }
            x += fw;
        }

        float sa = spotStoryLate ? ia : a;
        if (sa <= 0.003f || spotStory.isEmpty()) return;
        spotStoryP.setTextSize(26 * k);
        spotStoryP.setColor(Ui.alpha(0xD1FFFFFF, sa));
        base = storyBase;
        for (String l : spotStory) {
            c.drawText(l, MARGIN * k, base, spotStoryP);
            base += 37 * k;
        }
    }

    private float factWidth(Fact f) {
        switch (f.kind) {
            case Fact.RATING: return 28 * k + spotMeta.measureText(f.text);
            case Fact.BADGE: return spotBadge.measureText(f.text) + 18 * k;
            case Fact.PROGRESS: return 124 * k + spotMeta.measureText(f.text);
            default: return spotMeta.measureText(f.text);
        }
    }

    // MARK: - The picture behind everything

    /**
     * The picture a card wants behind the whole screen: its backdrop, else the colour of its poster, logo or app
     * card, else slate. Null while a film's full facts (where its backdrop is) are still on their way.
     */
    private static String sceneKey(Tile t, Iptv.Detail full) {
        if (t == null) return "slate";
        if (t.app != null) return t.art != null ? "app:" + t.title : "slate";
        if (isContent(t)) {
            Iptv.Detail lite = t.lite;
            String bd = lite != null && !lite.backdrop.isEmpty() ? lite.backdrop : full != null ? full.backdrop : "";
            if (!bd.isEmpty()) return "bd:" + bd;
            if (full == null && !t.fullTried) return null;
        }
        return t.image.isEmpty() ? "slate" : "wash:" + t.image;
    }

    private Scenery sceneCached(String key) {
        synchronized (scenes) { return scenes.get(key); }
    }

    /** The focused card's picture: shown at once when it is baked, otherwise baked first. */
    private void requestScenery() {
        if (w == 0) return;
        Tile t = spot;
        String key = sceneKey(t, spotDetail);
        if (key == null) return;   // prepare() calls back when the facts land
        sceneWant = key;
        Scenery front = sceneNext != null ? sceneNext : scene;
        if (front != null && key.equals(front.key)) return;
        Scenery ready = sceneCached(key);
        if (ready != null) {
            showScene(ready);
            return;
        }
        bakeLane.execute(() -> bakeNow(t, key));
    }

    private void bake(Tile t, String key) {
        if (sceneCached(key) != null) return;
        bakeLane.execute(() -> bakeNow(t, key));
    }

    /**
     * On the bake lane: decodes what the picture is made of and has Scenery render it (on the GPU, ~30 ms), then
     * shows it if it is still the one the focus wants.
     */
    /**
     * Holds a lane while the screen moves (the cards, a cross-fade): this TV's four cores are already busy with its
     * own services, and anything decoded, parsed or painted in the background made the moving frames late (frames
     * 30-38 ms long instead of 16-25). The work ahead is done while the focus rests on a card. False if interrupted.
     */
    private boolean whenStill() {
        try {
            for (int i = 0; i < 80; i++) {
                long up = android.os.SystemClock.uptimeMillis();
                if (up >= busyUntil && up >= fadingUntil) break;
                Thread.sleep(25);
            }
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void bakeNow(Tile t, String key) {
        if (!whenStill()) return;
        Scenery sc = sceneCached(key);
        if (sc == null) {
            synchronized (baking) {
                if (!baking.add(key)) return;   // under way: its end shows it
            }
            try {
                int bw = Math.round(w), bh = Math.round(h);
                Posters posters = Posters.get(getContext());
                if (key.startsWith("bd:")) {
                    Bitmap b = posters.largeNow(key.substring(3));
                    sc = b != null ? Scenery.home(key, b, bw, bh)
                            : Scenery.tinted(key, Scenery.average(t == null ? null : posters.posterNow(t.image)), bw, bh);
                } else if (key.startsWith("app:")) {
                    sc = Scenery.tinted(key, Scenery.average(t == null ? null : t.art), bw, bh);
                } else if (key.startsWith("wash:")) {
                    // a poster or a logo only lends its colour (never a blurred copy)
                    sc = Scenery.tinted(key, Scenery.average(posters.posterNow(key.substring(5))), bw, bh);
                } else {
                    sc = Scenery.home(key, null, bw, bh);
                }
                if (sc != null) synchronized (scenes) { scenes.put(key, sc); }
            } finally {
                synchronized (baking) { baking.remove(key); }
            }
        }
        if (sc == null) return;
        final Scenery ready = sc;
        main.post(() -> {
            if (ready.key.equals(sceneWant)) showScene(ready);
        });
    }

    private void showScene(Scenery sc) {
        if (!sc.key.equals(sceneWant)) return;
        Scenery front = sceneNext != null ? sceneNext : scene;
        if (front == sc) return;
        if (scene == null) {
            scene = sc;
        } else {
            if (sceneNext != null) scene = sceneNext;   // a fade under way ends at once
            sceneNext = sc;
            sceneAt = 0;
            sceneT = 0;
        }
        requestFrame();
        invalidate();
    }

    // MARK: - Drawing

    private static final long SHEEN_MS = 640;

    /** The card the spotlight is about: the focused one, or, from the bar, the one the focus left in the shelves. */
    private Tile focusedTile() {
        if (rows.isEmpty()) return null;
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
        syncSpot();
        drawScene(c);
        if (!Perf.off(Perf.NO_BAR)) drawBar(c);
        drawToast(c);
        if (Perf.ON) Perf.draw(System.nanoTime() - t0);
    }

    private void drawScene(Canvas c) {
        if (scene != null && !Perf.off(Perf.NO_BACKDROP)) {
            c.drawBitmap(scene.image, null, screen, scenePaint);
        } else {
            fill.setColor(0xFF000000);
            fill.setShader(bg);
            c.drawRect(screen, fill);
            fill.setShader(null);
        }
        if (sceneNext != null && !Perf.off(Perf.NO_BACKDROP)) {
            scenePaint.setAlpha(Math.round(255 * ease(sceneT)));
            c.drawBitmap(sceneNext.image, null, screen, scenePaint);
            scenePaint.setAlpha(255);
        }
        drawSpotlight(c);

        float top = ROW_ANCHOR * k - offset;
        if (rows.isEmpty()) {
            empty.setTextSize(24 * k);
            empty.setTextAlign(Paint.Align.LEFT);
            String msg = searchOpen
                    ? query.length() < 2 ? "Tapez au moins deux lettres." : "Aucun résultat pour « " + query + " »."
                    : "Aucun compte IPTV : renseignez-le dans les réglages.";
            c.drawText(msg, MARGIN * k, ROW_ANCHOR * k + 40 * k, empty);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            float y = top + row.y;
            if (y > h || y + row.h < 0 || row.alpha <= 0.004f) continue;
            drawRow(c, row, i, y, row.alpha);
        }
    }

    /** The plate's lens shaders read the frosted picture: rebuilt only when the picture changes. */
    private boolean refreshBackdropShaders() {
        Scenery front = sceneNext != null ? sceneNext : scene;
        if (front == null || front.frost == null) return false;
        if (front.frost == frostOf) return true;
        frostOf = front.frost;
        float sx = w / Scenery.FW, sy = h / Scenery.FH;
        for (int i = 0; i < LENS_BANDS.length; i++) {
            BitmapShader glass = new BitmapShader(frostOf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            matrix.setScale(sx, sy);
            matrix.postScale(LENS_BANDS[i], LENS_BANDS[i], barBox.centerX(), barBox.centerY());
            glass.setLocalMatrix(matrix);
            lensShaders[i] = glass;
        }
        return true;
    }

    /**
     * The bar: one Liquid Glass plate — its soft shadow, the picture seen through it (compressed like through a lens,
     * more vivid), then its baked rim and sheen. Inside, the search well, the library shown on a quiet glass capsule,
     * and the focus: one drop of lit glass that slides from control to control. Ink turns dark where the drop is.
     */
    private void drawBar(Canvas c) {
        shadowPaint.setAlpha(255);
        dst.set(barBox.left - platePad, barBox.top - platePad + 12 * k, barBox.right + platePad,
                barBox.bottom + platePad + 12 * k);
        c.drawBitmap(plateShadow, null, dst, shadowPaint);
        if (refreshBackdropShaders()) {
            // the picture through the glass, bent more and more towards the rim: the edge shows what lies just
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
        float pt = STAT_PT * k, sy = chromeRects[CH_SYNC].centerY(), base = sy + Ui.halfCap(statInk);
        for (int i = 0; i < statCount; i++) {
            Symbols.draw(getContext(), c, statSymbol[i], statIconX[i], sy, pt, DIM);
            statInk.setColor(statWarn[i] ? ACCENT : 0xEBFFFFFF);
            c.drawText(statValue[i], statTextX[i], base, statInk);
        }
        icon(c, CH_SYNC, "sync");
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

    private void drawRow(Canvas c, Row row, int index, float y, float a) {
        float m = MARGIN * k;
        rowTitle.setTextSize(28 * k);
        rowTitle.setTextAlign(Paint.Align.LEFT);
        rowTitle.setColor(Ui.alpha(0xFFE7EDEE, a));
        float base = y + 30 * k;   // 22 px above the cards: a focused card grows and still clears it
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
                Liquid.draw(c, kitSee, r, glassPaint, a, true, 4 * k);
            }
            seeAll.setColor(Ui.alpha(on ? INK : 0xFFB8C4C9, a));
            c.drawText("Tout voir", w - m, base, seeAll);
        }

        float s = step(row.kind), tw = tw(row.kind), th = th(row.kind);
        float tilesTop = y + HEAD_H * k;
        // what lies just outside the plate's width peeks at both edges: there is more this way
        int from = Math.max(0, (int) Math.floor((row.slide - m) / s));
        for (int j = from; j < row.items.size(); j++) {
            float x = m + j * s - row.slide;
            if (x > w) break;
            if (x + tw < 0) continue;
            Tile t = row.items.get(j);
            r.set(x, tilesTop, x + tw, tilesTop + th);
            float f = j < row.focus.length ? row.focus[j] : 0;
            drawCard(c, r, t, f, row.kind, a);
            if (row.captions && !Perf.off(Perf.NO_TEXT)) drawCaption(c, t, r, tilesTop + th + (14 + 8 * f) * k, f, a);
        }
    }

    /**
     * Every card is the same piece of glass: artwork clipped to its rounded shape, a specular rim lit from the
     * top-left (quiet at rest, bright when focused), and on focus a lift — scale, a soft baked shadow under it, light
     * on its top half and a glint that crosses it once.
     */
    private void drawCard(Canvas c, RectF box, Tile t, float f, int kind, float a) {
        boolean poster = kind == R_POSTER;
        float radius = (poster ? POSTER_R : CARD_R) * k;
        float grow = 1 + (poster ? 0.075f : 0.055f) * f;
        r2.set(box);
        r2.inset(-box.width() * (grow - 1) / 2, -box.height() * (grow - 1) / 2);
        float rad = radius * grow;

        if (f > 0.02f) {
            float pad = cardPad * grow, dy = 16 * k * f;
            dst.set(r2.left - pad, r2.top - pad + dy, r2.right + pad, r2.bottom + pad + dy);
            shadowPaint.setAlpha(Math.round(255 * Math.min(1f, f) * a));
            c.drawBitmap(poster ? posterShadow : cardShadow, null, dst, shadowPaint);
            shadowPaint.setAlpha(255);
        }

        clip.reset();
        clip.addRoundRect(r2, rad, rad, Path.Direction.CW);
        c.save();
        if (!Perf.off(Perf.NO_CLIP)) c.clipPath(clip);
        cardBody(c, t, poster, a);
        if (f > 0.02f) {
            c.save();
            c.translate(r2.left, r2.top);
            shaded.setShader(poster ? posterLight : cardLight);
            shaded.setAlpha(Math.round(255 * Math.min(1f, f) * a));
            c.drawRect(0, 0, r2.width(), r2.height(), shaded);
            float phase = sheen();
            if (phase >= 0 && f > 0.5f) {
                float gx = -r2.width() * 0.45f + r2.width() * 1.9f * phase, gy = r2.height() / 2;
                c.translate(gx, gy);
                shaded.setShader(sheenBand);
                shaded.setAlpha(Math.round(255 * a));
                c.drawRect(-gx, -gy, r2.width() - gx, r2.height() - gy, shaded);
            }
            c.restore();
            shaded.setShader(null);
            shaded.setAlpha(255);
        }
        c.restore();

        if (Perf.off(Perf.NO_RIM)) return;
        float sw = (1f + 1.6f * f) * k;
        rimPaint.setShader(poster ? posterRim : cardRim);
        rimPaint.setStrokeWidth(sw);
        rimPaint.setAlpha(Math.round(255 * Math.min(1f, 0.20f + 0.80f * f) * a));
        c.save();
        c.translate(r2.left, r2.top);
        c.scale(grow, grow);
        float bw = box.width(), bh = box.height(), s = sw / grow;
        dst.set(s / 2, s / 2, bw - s / 2, bh - s / 2);
        c.drawRoundRect(dst, radius - s / 2, radius - s / 2, rimPaint);
        c.restore();
    }

    private void cardBody(Canvas c, Tile t, boolean poster, float a) {
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF1E2A31, a));
        c.drawRect(r2, fill);
        Bitmap b = null;
        if (!t.wide.isEmpty() && !Perf.off(Perf.NO_ART)) b = Posters.get(getContext()).get(t.wide, posterDone);
        if (b == null) b = artOf(t);
        art.setAlpha(Math.round(255 * a));
        if (b != null) {
            if (t.live) {
                artBox.set(r2);
                artBox.inset(30 * k, 30 * k);
                drawContain(c, b, artBox);
            } else {
                drawCover(c, b, r2);
            }
        } else if (t.live || t.app == null) {
            // no picture: the name, set like a title card (a poster on two or three lines), not a placeholder icon
            nameInk.setTextSize((poster ? 25 : 30) * k);
            nameInk.setColor(Ui.alpha(0xE6FFFFFF, a));
            List<String> lines = wrap(t.title, nameInk, r2.width() - (poster ? 32 : 48) * k, poster ? 4 : 2);
            float lh = (poster ? 32 : 38) * k;
            float ty = r2.centerY() - (lines.size() - 1) * lh / 2 + Ui.halfCap(nameInk);
            for (String l : lines) {
                c.drawText(l, r2.centerX(), ty, nameInk);
                ty += lh;
            }
        } else {
            String glyph = !t.symbol.isEmpty() ? t.symbol : "play";
            Symbols.draw(getContext(), c, glyph, r2.centerX(), r2.centerY(), 54 * k, Ui.alpha(0x4DFFFFFF, a));
        }
        art.setAlpha(255);
        float progress = t.progress;
        if (t.ch != null) {   // a channel: how far its programme is
            long now = System.currentTimeMillis();
            Epg.Show s = showOf(t, now);
            progress = s == null ? 0 : Math.max(0.02f, s.progress(now));
        }
        if (progress > 0.005f) {
            // the progress is a capsule inset in the card's foot, like everything else that is a control here
            float ph = 6 * k, inset = (poster ? 14 : 22) * k, py = r2.bottom - (poster ? 12 : 16) * k;
            dst.set(r2.left + inset, py - ph, r2.right - inset, py);
            fill.setColor(Ui.alpha(0x8C000000, a));
            c.drawRoundRect(dst, ph / 2, ph / 2, fill);
            dst.right = dst.left + Math.max(24 * k, (r2.width() - 2 * inset) * Math.min(1f, progress));
            fill.setColor(Ui.alpha(ACCENT, a));
            c.drawRoundRect(dst, ph / 2, ph / 2, fill);
        }
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
        float top = (bh - sh) * (bh > bw && box.width() > box.height() ? 0.3f : 0.5f);   // a poster in a wide card: its upper part
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

    /** Under a "continue watching" card, as Apple's Up Next: the title, then the episode and the time left. */
    private void drawCaption(Canvas c, Tile t, RectF box, float y, float f, float a) {
        cardTitle.setTextSize(22 * k);
        cardTitle.setTextAlign(Paint.Align.LEFT);
        cardMeta.setTextSize(18 * k);
        cardMeta.setTextAlign(Paint.Align.LEFT);
        float width = box.width();
        if (t.ch != null) showOf(t, System.currentTimeMillis());
        if (t.capWidth != width && t.ch != null) {
            // a channel: what it shows, then its number, its name and the time left
            long now = System.currentTimeMillis();
            Epg.Show s = t.show;
            t.capTitle = fit(cardTitle, s != null ? s.title : t.title, width);
            String meta = t.ch.number + " · " + t.title + (s != null ? " · " + Epg.left(s, now) : "");
            t.capMeta = fit(cardMeta, meta, width);
            t.capWidth = width;
        } else if (t.capWidth != width) {
            t.capTitle = fit(cardTitle, t.title, width);
            String meta = t.episode;
            if (t.left > 0) meta = meta.isEmpty() ? "Il reste " + span(t.left) : meta + " · il reste " + span(t.left);
            if (meta.isEmpty()) meta = t.meta == null ? "" : t.meta;
            t.capMeta = fit(cardMeta, meta, width);
            t.capWidth = width;
        }
        cardTitle.setColor(Ui.alpha(blend(0xFFDDE4E7, INK, f), a));
        c.drawText(t.capTitle, box.left, y + Ui.cap(cardTitle), cardTitle);
        cardMeta.setColor(Ui.alpha(0xFF9AA8AE, a));
        if (!t.capMeta.isEmpty()) c.drawText(t.capMeta, box.left, y + Ui.cap(cardTitle) + 27 * k, cardMeta);
        cardTitle.setColor(INK);
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
                if (down) {
                    way = -1;
                    move(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    way = 1;
                    move(1);
                }
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
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                // as on a box: P+ / P− from the home screen goes back to the channel last watched
                if (down && e.getRepeatCount() == 0 && !openChannel(Prefs.lastChannel())) openChannel(1);
                return true;
            case KeyEvent.KEYCODE_GUIDE:
                // GUIDE opens the guide: the channels, three hours across, and the three days of replay behind
                if (down && e.getRepeatCount() == 0 && getContext() instanceof HomeActivity) {
                    ((HomeActivity) getContext()).openGuide();
                }
                return true;
            case KeyEvent.KEYCODE_INFO:
                // on the Direct tab, INFO puts the focused category away (it comes back from « Catégories masquées »)
                if (down && e.getRepeatCount() == 0) hideFocusedCategory();
                return true;
            default:
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && !searchOpen) {
                    if (down && e.getRepeatCount() == 0) typeDigit(code - KeyEvent.KEYCODE_0);
                    return true;
                }
                return false;
        }
    }

    // MARK: - Channel numbers on the home screen

    private final StringBuilder typed = new StringBuilder();
    private final Runnable typedRun = this::openTyped;

    /** "1", "15": the channel opens after a short pause or at the third digit; the toast says where it goes. */
    private void typeDigit(int d) {
        if (typed.length() == 0 && d == 0) return;
        typed.append(d);
        removeCallbacks(typedRun);
        Channels.Channel c = Channels.get(getContext()).byNumber(Integer.parseInt(typed.toString()));
        showToast(c != null ? typed + "  ·  " + c.name : typed + "–");
        if (typed.length() >= 3) openTyped();
        else postDelayed(typedRun, 1500);
    }

    private void openTyped() {
        removeCallbacks(typedRun);
        if (typed.length() == 0) return;
        int n = Integer.parseInt(typed.toString());
        typed.setLength(0);
        if (!openChannel(n)) showToast("Pas de chaîne " + n);
    }

    /** Opens channel `n` of the lineup on its best stream; false when there is no such channel. */
    private boolean openChannel(int n) {
        Channels.Channel c = n > 0 ? Channels.get(getContext()).byNumber(n) : null;
        if (c == null || c.streams.isEmpty()) return false;
        Intent it = new Intent(getContext(), Player.class);
        it.putExtra(Player.EXTRA_URL, c.streams.get(0).url);
        it.putExtra(Player.EXTRA_NAME, c.name);
        it.putExtra(Player.EXTRA_LOGO, c.logo);
        it.putExtra(Player.EXTRA_CHANNEL, c.number);
        getContext().startActivity(it);
        return true;
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
        if (row.restore) {   // « Catégories masquées »: OK brings the category back
            if (colIndex >= 0 && colIndex < row.items.size()) {
                String u = row.items.get(colIndex).url;
                if (u.startsWith("hidden:")) {
                    Prefs.showCategory(u.substring(7));
                    showToast("Catégorie rétablie");
                    rebuild();
                }
            }
            return;
        }
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

    /**
     * INFO on the Direct tab puts the focused category away: its row goes, and a « Catégories masquées » row at the end
     * of the tab lists what was put away (OK brings one back). Only the panel's categories: the TNT and the lineup's own
     * sections stay.
     */
    private void hideFocusedCategory() {
        if (zone != Z_ROW || rows.isEmpty() || rowIndex >= rows.size()) return;
        Row row = rows.get(rowIndex);
        if (row.title.equals("Ma liste")) {   // INFO on a card puts the title out of the list
            if (colIndex < 0 || colIndex >= row.items.size()) return;
            Tile t = row.items.get(colIndex);
            boolean series = t.url.isEmpty() && !t.seriesId.isEmpty();
            String id = series ? t.seriesId : vodId(t.url);
            Prefs.removeFromList(Prefs.listKey(series, id));
            showToast(t.title + " retiré de « Ma liste »");
            rebuild();
            return;
        }
        if (row.catId.isEmpty()) {
            showToast(row.restore ? "OK sur une catégorie pour la remettre" : "Seules les catégories se masquent");
            return;
        }
        Prefs.hideCategory(row.catId, row.catName);
        showToast("Masquée : " + row.catName + "  ·  en fin de liste pour la remettre");
        rebuild();
    }

    private void activateChrome() {
        int id = chromeOrder[Math.max(0, Math.min(chromeIndex, chromeCount - 1))];
        if (id >= CH_TAB) { selectTab(id - CH_TAB); return; }
        switch (id) {
            case CH_SEARCH:
                openSearch();
                break;
            case CH_SYNC:
                Iptv.get(getContext()).refresh();
                showToast("Mise à jour du catalogue…");
                break;
            default:
                break;
        }
    }

    private void open(Tile t, Row row) {
        android.app.Activity a = activity();
        if (t.app != null) {
            if (a == null) return;
            int i = Math.max(0, colIndex);
            float x = MARGIN * k + i * step(row.kind) - row.slide;
            float y = ROW_ANCHOR * k + row.y + HEAD_H * k - offset;
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
        if (t.ch != null) it.putExtra(Player.EXTRA_CHANNEL, t.ch.number);
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
        row.kind = "Chaînes".equals(title) ? R_CARD : R_POSTER;
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
