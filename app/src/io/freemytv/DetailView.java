package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A film's or a series' page, between its card and the player (Infuse 8 / tvOS 26): the backdrop full screen; the
 * title, Lecture / Reprendre and round buttons on the left; the facts and the synopsis on the right. A series adds its
 * seasons and episodes below: their tabs peek at the bottom, Down scrolls the page up to them.
 *
 * Drawn in layers moved by RenderNode properties (alpha, translation): the scroll, the entrance and the backdrop's
 * cross-fade repaint nothing; a focus change repaints only the layer it lives in. The background (picture and scrims)
 * is baked once off the UI thread into one screen-sized bitmap: the GPU draws it in a single pass.
 */
final class DetailView extends FrameLayout implements Choreographer.FrameCallback {

    /** What the card knew when it was pressed: enough to draw the page at once, the rest comes from the panel. */
    static final class Request {
        String title = "", year = "", poster = "", url = "", seriesId = "";
        boolean series;
    }

    private static final int MARGIN = 96;
    private static final int BTN_W = 440, BTN_H = 68, ICON_D = 64, ICON_GAP = 20, COL_GAP = 64;
    private static final int CARD_W = 448, CARD_H = 252, CARD_GAP = 36, CARD_R = 22, TAB_H = 52;
    private static final int A_RESTART = 0, A_TRAILER = 1, A_FORGET = 2, A_LIST = 3, A_WATCHED = 4;
    private static final String[] ACTION_ICON = {"restart", "trailer", "remove", "add", "check"};
    private static final String[] ACTION_NAME = {"Recommencer", "Bande-annonce", "Retirer de « En cours »", "Ma liste", "Marquer comme vu"};
    private static final int Z_PRIMARY = 0, Z_ICONS = 1, Z_SEASONS = 2, Z_EPISODES = 3;
    private static final int BACK = 0, PHOTO = 1, DIM = 2, HERO = 3, SHELF = 4;
    private static final int ACCENT = 0xFFFF8A00;   // the progress, as on the home screen's cards
    private static final Pattern MOVIE_ID = Pattern.compile("/movie/[^/]+/[^/]+/(\\d+)\\.");
    private static final Pattern YEAR_TAIL = Pattern.compile("\\s*\\((\\d{4})\\)\\s*$");

    /**
     * The home screen under the page. The page fades over a still picture of it (a copy of the screen), never over the
     * live home screen: drawing both every frame cost 35-60 ms of GPU. So the home rests while the page is up.
     */
    interface Host {
        /** The page shows (over the home screen's picture): the home screen can rest, hidden. */
        void covered();
        /** The page is leaving (as a picture of itself): the home screen is shown and alive again. */
        void uncovered();

        /** A film or a series was taken out of "En cours". */
        void watchingChanged();

        /** A film or a series was put in « Ma liste », or taken out: the home screen's row follows. */
        void listChanged();
    }

    private final Host host;
    private final Layer back, photo, hero, shelf;
    private final Layer dim;
    private float k = 1;
    private int w, h;

    // MARK: - State

    private Request req;
    private Iptv.Detail detail;
    private boolean open, loading, failed;
    private int generation;
    private Bitmap poster, soft, backdrop;
    private Bitmap bgSoft, bgPhoto;   // the background with its scrims, baked: the poster's wash, then the backdrop
    private Bitmap snap;              // the screen under the page while it fades in (or the page while it leaves)
    private Bitmap shot;              // the buffer the screen is copied into
    private boolean leaving;
    private long fadeAt, photoAt;
    private float photoA;
    private boolean posterCard;     // no backdrop: the poster stands on the right, over its own colours

    // what is drawn, worked out when the data changes (never per frame)
    private String title = "", primary = "", primaryTime = "", credit1 = "", credit2 = "";
    private List<String> titleLines = new ArrayList<>(), synopsis = new ArrayList<>();
    private final List<Integer> actions = new ArrayList<>();
    private final List<Seg> meta = new ArrayList<>();
    private Map<String, Float> progress = new HashMap<>();
    private Set<String> watched = new java.util.HashSet<>();
    private String playUrl = "";
    private long playPos;
    private Iptv.Episode target;

    // a series: its seasons and the episodes of the one shown
    private final List<Iptv.Episode> episodes = new ArrayList<>();
    private String[] epTitle = new String[0], epMeta = new String[0];
    private String[][] epPlot = new String[0][];
    private float[] epProgress = new float[0];
    private boolean[] epWatched = new boolean[0];
    private float[] tabX = new float[0], tabW = new float[0];

    // focus
    private int zone = Z_PRIMARY, action, season, episode;

    // animation (all eased toward their target in doFrame)
    private float primaryOn, scroll, scrollTo, slide, slideTo, enter, infoA = 1, infoTo = 1;
    private float[] actionOn = new float[0], tabOn = new float[0], epOn = new float[0];
    private boolean framePosted;
    private long lastFrame;

    // MARK: - Geometry (computed in layoutPage)

    private float titleSize, titleLine, titleBase, primTop, iconsTop, colX, metaBase, synBase, synLine, creditBase;
    private float scrollBy, tabsTop, cardsTop;
    private final RectF posterBox = new RectF();

    // MARK: - Paints and baked glass

    private float dotW;
    private final Paint dotP, titleP, metaP, badgeP, synP, labelP, nameP, btnP, timeP, iconNameP, tabP, epTitleP, epMetaP,
            epPlotP, emptyP;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.FILTER_BITMAP_FLAG), image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint(), rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Liquid.Kit kitBtn, kitBtnOn, kitIcon, kitIconOn, kitTab, kitTabOn;
    private Bitmap cardShadow, posterShadow;
    private int cardPad, posterPad;
    private Shader topLight, cardRim;
    private final RectF box = new RectF(), dst = new RectF();
    private final Matrix matrix = new Matrix();
    private final Map<Bitmap, BitmapShader> shaders = new HashMap<>();

    DetailView(Context c, Host host) {
        super(c);
        this.host = host;
        setClipChildren(false);
        back = layer(c, BACK);
        photo = layer(c, PHOTO);
        dim = layer(c, DIM);
        dim.setAlpha(0);
        dim.setVisibility(INVISIBLE);
        shelf = layer(c, SHELF);
        hero = layer(c, HERO);
        photo.setAlpha(0);
        // laid out from the start (hidden, not gone): the glass is baked and on the GPU before the first card is pressed
        setVisibility(INVISIBLE);

        titleP = Ui.text(Ui.displaySemibold, 72, 0xFFFFFFFF);
        titleP.setLetterSpacing(-0.01f);
        metaP = Ui.text(Ui.medium, 24, 0xE0FFFFFF);
        dotP = Ui.text(Ui.semibold, 24, 0x73FFFFFF);
        badgeP = Ui.text(Ui.semibold, 18, 0xE0FFFFFF);
        synP = Ui.text(Ui.regular, 26, 0xD1FFFFFF);
        labelP = Ui.text(Ui.medium, 22, 0x99FFFFFF);
        nameP = Ui.text(Ui.medium, 22, 0xC7FFFFFF);
        btnP = Ui.text(Ui.semibold, 25, 0xFFFFFFFF);
        timeP = Ui.numbers(Ui.medium, 24, 0xFFFFFFFF);
        iconNameP = Ui.text(Ui.medium, 20, 0xE6FFFFFF);
        tabP = Ui.text(Ui.medium, 22, 0xFFFFFFFF);
        epTitleP = Ui.text(Ui.semibold, 23, 0xFFFFFFFF);
        epMetaP = Ui.text(Ui.medium, 19, 0x9EFFFFFF);
        epPlotP = Ui.text(Ui.regular, 19, 0x8CFFFFFF);
        emptyP = Ui.text(Ui.medium, 24, 0x99FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    private Layer layer(Context c, int part) {
        Layer l = new Layer(c, part);
        addView(l, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return l;
    }

    /** One drawing surface of the page; the page moves it by its properties. */
    private final class Layer extends View {
        final int part;

        Layer(Context c, int part) {
            super(c);
            this.part = part;
        }

        /** Faded by drawing each part translucent: no offscreen copy of the screen for the alpha. */
        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            paint(part, c);
        }
    }

    @Override public boolean hasOverlappingRendering() {
        return false;   // when it leaves, it is one picture: faded as is
    }

    // MARK: - Open, close

    boolean isOpen() {
        return open;
    }

    /** Opens the page for a card. */
    void show(Request r) {
        req = r;
        open = true;
        generation++;
        detail = null;
        loading = true;
        failed = false;
        backdrop = null;
        soft = null;
        bgSoft = bgPhoto = null;
        posterCard = false;
        back.setVisibility(VISIBLE);
        shaders.clear();
        zone = Z_PRIMARY;
        action = season = episode = 0;
        scroll = scrollTo = slide = slideTo = 0;
        java.util.Arrays.fill(actionOn, 0);
        episodes.clear();
        title = r.series ? seriesName(r.title) : r.title;
        photo.setAlpha(0);
        dim.setAlpha(0);
        dim.setVisibility(INVISIBLE);

        poster = r.poster.isEmpty() ? null : Posters.get(getContext()).get(r.poster, (url, b) -> post(() -> {
            if (open && req == r && poster == null) {
                poster = b;
                soft = Scenery.softOf(b);
                bakeSoft();
                hero.invalidate();
            }
        }));
        if (poster != null) soft = Scenery.softOf(poster);
        bakeSoft();

        refresh();
        layoutPage();
        primaryOn = 1;   // already lit: no focus animation under the opening fade
        infoA = infoTo = 0;
        photoA = 0;
        enter = 0;
        animate().cancel();
        setAlpha(1);
        load(r, generation);
        // a picture of the home screen as it is now: the page fades in over it, the home screen rests at once
        final int gen = generation;
        snapshot(b -> {
            if (!open || gen != generation) return;
            snap = b;
            setVisibility(VISIBLE);
            bringToFront();
            host.covered();
            if (b == null) enter = 1;   // no picture: the page simply appears
            fadeAt = 0;
            applyMotion();
            invalidateAll();
            kick();
        });
    }

    private interface Shot { void done(Bitmap b); }

    /** The window as it is on screen, copied into a bitmap (async, ~15 ms); null if the copy fails. */
    private void snapshot(Shot done) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        int sw = Math.max(1, getRootView().getWidth()), sh = Math.max(1, getRootView().getHeight());
        if (shot == null || shot.getWidth() != sw || shot.getHeight() != sh) {
            // one screen-sized buffer, kept: allocating 8 MB at each opening cost ~40 ms before the page could move
            try {
                shot = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                shot = null;
                main.post(() -> done.done(null));
                return;
            }
        }
        final Bitmap b = shot;
        try {
            android.view.PixelCopy.request(((android.app.Activity) getContext()).getWindow(), b,
                    result -> done.done(result == android.view.PixelCopy.SUCCESS ? b : null), main);
        } catch (Exception e) {
            main.post(() -> done.done(null));
        }
    }

    /** Back on the page's top: the page becomes a picture of itself, the home screen comes back under it. */
    private void close() {
        if (!open) return;
        open = false;
        Choreographer.getInstance().removeFrameCallback(this);
        framePosted = false;
        snapshot(b -> {
            if (open) return;   // opened again meanwhile
            host.uncovered();
            if (b == null) {
                clear();
                return;
            }
            snap = b;
            leaving = true;
            for (View v : new View[]{photo, dim, hero, shelf}) v.setVisibility(INVISIBLE);
            back.setVisibility(VISIBLE);
            back.invalidate();
            animate().cancel();
            animate().alpha(0).setDuration(200).withEndAction(this::clear).start();
        });
    }

    /** Home pressed: gone at once. */
    void closeNow() {
        open = false;
        animate().cancel();
        clear();
    }

    private void clear() {
        if (open) return;
        setVisibility(INVISIBLE);
        setAlpha(1);
        leaving = false;
        snap = null;
        for (View v : new View[]{photo, hero, shelf}) v.setVisibility(VISIBLE);
        Choreographer.getInstance().removeFrameCallback(this);
        framePosted = false;
        backdrop = soft = poster = bgSoft = bgPhoto = null;   // 8 MB each: nothing kept once the page is closed
        shaders.clear();
        detail = null;
        episodes.clear();
    }

    void pause() {
        Choreographer.getInstance().removeFrameCallback(this);
        framePosted = false;
    }

    /** Back from the player: where it was left, which episode is next. */
    void resume() {
        if (!open) return;
        refresh();
        kick();
    }

    private void load(Request r, int gen) {
        final Context ctx = getContext();
        new Thread(() -> {
            // below the UI and render threads: the page's opening fade never waits for the panel
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Iptv.Detail d = null;
            if (r.series) {
                Follow.clear(ctx, r.seriesId);   // its page is opening: what was new has been seen
                if (!r.seriesId.isEmpty()) d = Iptv.get(ctx).series(r.seriesId);
            } else {
                Matcher m = MOVIE_ID.matcher(r.url);
                if (m.find()) d = Iptv.get(ctx).film(m.group(1));
            }
            final Iptv.Detail got = d;
            post(() -> loaded(got, gen));
        }, "detail").start();
    }

    /**
     * The episodes' missing pictures, from TMDB (the series is found by its title once, then one request per season).
     * Off the UI thread; when they arrive the cards are rebuilt in place, nothing else moves.
     */
    private void askStills(final Iptv.Detail d) {
        if (!Tmdb.available()) return;
        final String name = d.title.isEmpty() ? title : seriesName(d.title);
        final String year = d.year;
        new Thread(() -> Tmdb.fill(getContext(), d, name, year, () -> post(() -> {
            if (!open || detail != d) return;
            buildSeason();
            shelf.invalidate();
        })), "tmdb-stills").start();
    }

    private void loaded(Iptv.Detail d, int gen) {
        if (!open || gen != generation) return;
        detail = d;
        loading = false;
        failed = d == null;
        if (d != null) {
            if (req.series && !d.title.isEmpty()) title = seriesName(d.title);
            if (!d.backdrop.isEmpty()) {
                final Request r = req;
                backdrop = Posters.get(getContext()).large(d.backdrop, (url, b) -> post(() -> {
                    if (open && req == r && detail != null && url.equals(detail.backdrop)) showBackdrop(b);
                }));
                if (backdrop != null) showBackdrop(backdrop);
            } else {
                posterCard = true;
            }
            if (req.series && poster == null && !d.poster.isEmpty()) {
                final Request r = req;
                poster = Posters.get(getContext()).get(d.poster, (url, b) -> post(() -> {
                    if (open && req == r) {
                        poster = b;
                        if (soft == null) {
                            soft = Scenery.softOf(b);
                            bakeSoft();
                        }
                        hero.invalidate();
                    }
                }));
                if (poster != null && soft == null) {
                    soft = Scenery.softOf(poster);
                    bakeSoft();
                }
            }
        }
        refresh();
        if (req.series && d != null) askStills(d);   // the panel gives some episodes no picture: TMDB has them
        if (req.series && target != null) {
            season = Math.max(0, seasonsOf().indexOf(target.season));
            buildSeason();
            episode = Math.max(0, episodes.indexOf(target));
            slide = slideTo = slideFor(episode);
        }
        layoutPage();
        infoA = 0;
        infoTo = 1;
        invalidateAll();
        kick();
    }

    private void showBackdrop(Bitmap b) {
        if (backdrop == b && bgPhoto != null) return;
        backdrop = b;
        shelf.invalidate();   // episodes without a picture of their own use it
        final Request r = req;
        bake(b, false, baked -> {
            if (!open || req != r || backdrop != b) return;
            bgPhoto = baked;
            photo.invalidate();
            photoA = 0;
            photoAt = 0;
            kick();
        });
    }

    private void bakeSoft() {
        if (soft == null || w == 0) return;
        final Request r = req;
        final Bitmap from = soft;
        bake(from, true, baked -> {
            if (!open || req != r || soft != from) return;
            bgSoft = baked;
            back.invalidate();
        });
    }

    private interface Baked { void done(Bitmap b); }

    /** The background as the screen shows it (Scenery: picture + scrims, in GPU memory), baked on a worker. */
    private void bake(Bitmap src, boolean wash, Baked done) {
        final int bw = w, bh = h;
        if (bw == 0 || bh == 0) return;
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Scenery sc = Scenery.bake("", src, wash, bw, bh);
            if (sc != null) post(() -> done.done(sc.image));
        }, "detail-bake").start();
    }

    // MARK: - What is shown

    /** Positions, the next episode, the buttons and the lines: after loading and when back from the player. */
    private void refresh() {
        progress = Prefs.progressAll();
        watched = Prefs.watched();
        if (req.series) {
            target = pickTarget();
            playUrl = target == null ? "" : target.url;
        } else {
            playUrl = req.url;
        }
        playPos = playUrl.isEmpty() ? 0 : Prefs.positionOf(playUrl);
        if (req.series) {
            if (target == null) primary = loading ? "Chargement…" : "Aucun épisode";
            else primary = (playPos > 0 ? "Reprendre " : "Lecture ") + "S" + target.season + ", É" + target.number;
            primaryTime = "";
        } else {
            primary = playPos > 0 ? "Reprendre" : "Lecture";
            primaryTime = playPos > 0 ? hms(playPos / 1000) : "";
        }
        actions.clear();
        if (playPos > 0) actions.add(A_RESTART);
        if (detail != null && !detail.trailer.isEmpty()) actions.add(A_TRAILER);
        if (!listKey().isEmpty()) actions.add(A_LIST);
        if (!req.series && !listKey().isEmpty()) actions.add(A_WATCHED);   // a film: seen, by hand
        if (watching()) actions.add(A_FORGET);   // last, like Apple's "Supprimer de À suivre"
        if (action >= actions.size()) action = Math.max(0, actions.size() - 1);
        // one slot per round button: a fixed table lit the last two at once once a fifth button appeared
        if (actionOn.length != actions.size()) actionOn = new float[Math.max(1, actions.size())];
        if (zone == Z_ICONS && actions.isEmpty()) zone = Z_PRIMARY;
        buildMeta();
        if (req.series) buildSeason();
        invalidateAll();
    }

    /** The episode Lecture / Reprendre plays: the last one left halfway, else the one after the last finished. */
    private Iptv.Episode pickTarget() {
        if (detail == null || detail.episodes.isEmpty()) return null;
        List<Iptv.Episode> all = detail.episodes;
        int resume = -1, done = -1;
        for (int i = 0; i < all.size(); i++) {
            String u = all.get(i).url;
            if (progress.containsKey(u)) resume = i;
            if (Prefs.watched(watched, u)) done = i;
        }
        if (resume >= 0 && resume >= done) return all.get(resume);
        if (done >= 0 && done + 1 < all.size()) return all.get(done + 1);
        if (resume >= 0) return all.get(resume);
        for (Iptv.Episode e : all) if (e.season > 0) return e;   // season 0 holds the specials
        return all.get(0);
    }

    private List<Integer> seasonsOf() {
        return detail == null ? new ArrayList<>() : detail.seasons;
    }

    private void buildMeta() {
        meta.clear();
        Iptv.Detail d = detail;
        if (!req.series && playPos > 0) {
            Float f = progress.get(playUrl);
            long dur = d != null && d.durationMs > 0 ? d.durationMs : f != null && f > 0 ? (long) (playPos / f) : 0;
            if (dur > playPos) meta.add(new Seg(Seg.PROGRESS, "Il reste " + span(dur - playPos), (float) playPos / dur));
        }
        float rating = d == null ? 0 : d.rating > 10 ? d.rating / 10f : d.rating;
        if (rating >= 1 && rating < 9.95f) meta.add(new Seg(Seg.RATING, String.format(Locale.FRANCE, "%.1f", rating), 0));
        String year = d != null && !d.year.isEmpty() ? d.year : req.year;
        if (year != null && !year.isEmpty()) meta.add(new Seg(Seg.TEXT, year, 0));
        if (req.series) {
            int n = seasonsOf().size();
            if (n > 1) meta.add(new Seg(Seg.TEXT, n + " saisons", 0));
            else if (d != null && !d.episodes.isEmpty()) {
                int e = d.episodes.size();
                meta.add(new Seg(Seg.TEXT, e + (e > 1 ? " épisodes" : " épisode"), 0));
            }
        } else if (d != null && d.durationMs > 0) {
            meta.add(new Seg(Seg.TEXT, span(d.durationMs), 0));
        }
        if (d != null && !d.age.isEmpty()) meta.add(new Seg(Seg.BADGE, d.age, 0));
        if (d != null && !d.genre.isEmpty()) {
            String[] g = d.genre.replace(" / ", ",").replace("/", ",").split("\\s*,\\s*");
            for (int i = 0; i < g.length; i++) g[i] = genre(g[i].trim());
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < g.length && i < 3; i++) {
                if (g[i].trim().isEmpty()) continue;
                if (b.length() > 0) b.append(", ");
                b.append(g[i].trim());
            }
            if (b.length() > 0) meta.add(new Seg(Seg.TEXT, b.toString(), 0));
        }
    }

    /** The episodes of the season shown, with their lines ready. */
    private void buildSeason() {
        episodes.clear();
        List<Integer> seasons = seasonsOf();
        if (detail != null && !seasons.isEmpty()) {
            season = Math.max(0, Math.min(seasons.size() - 1, season));
            int s = seasons.get(season);
            for (Iptv.Episode e : detail.episodes) if (e.season == s) episodes.add(e);
        }
        int n = episodes.size();
        epTitle = new String[n];
        epMeta = new String[n];
        epPlot = new String[n][];
        epProgress = new float[n];
        epWatched = new boolean[n];
        if (epOn.length != n) epOn = new float[n];
        float cw = CARD_W * k;
        for (int i = 0; i < n; i++) {
            Iptv.Episode e = episodes.get(i);
            String t = e.title.isEmpty() ? "Épisode " + e.number : e.number + ". " + e.title;
            epTitle[i] = fit(t, epTitleP, cw);
            Float f = progress.get(e.url);
            epProgress[i] = f == null ? 0 : f;
            epWatched[i] = Prefs.watched(watched, e.url);
            long left = f != null && f > 0 && e.durationMs > 0 ? (long) (e.durationMs * (1 - f)) : 0;
            epMeta[i] = left > 0 ? "Il reste " + span(left) : e.durationMs > 0 ? span(e.durationMs) : "";
            List<String> p = wrap(e.plot, epPlotP, cw, 2);
            epPlot[i] = p.toArray(new String[0]);
        }
        episode = Math.max(0, Math.min(Math.max(0, n - 1), episode));
        // the tabs: one per season
        int sn = seasons.size();
        tabX = new float[sn];
        tabW = new float[sn];
        if (tabOn.length != sn) tabOn = new float[sn];
        float x = MARGIN * k;
        for (int i = 0; i < sn; i++) {
            int s = seasons.get(i);
            float tw = tabP.measureText(s == 0 ? "Bonus" : "Saison " + s) + 2 * 26 * k;
            tabX[i] = x;
            tabW[i] = tw;
            x += tw + 12 * k;
        }
    }

    /** The page's lines and places, for its size and its title. */
    private void layoutPage() {
        if (w == 0 || req == null) return;
        float dy = req.series ? -90 * k : 0;
        float maxW = 1300 * k;
        titleSize = 72 * k;
        titleP.setTextSize(titleSize);
        titleLines = new ArrayList<>();
        if (titleP.measureText(title) <= maxW) {
            titleLines.add(title);
        } else {
            titleSize = 62 * k;
            titleP.setTextSize(titleSize);
            titleLines = wrap(title, titleP, maxW, 2);
            float widest = 0;
            for (String l : titleLines) widest = Math.max(widest, titleP.measureText(l));
            if (widest > maxW) {
                titleSize = Math.max(44 * k, titleSize * maxW / widest);
                titleP.setTextSize(titleSize);
                titleLines = wrap(title, titleP, maxW, 2);
            }
        }
        titleLine = titleSize * 1.06f;
        titleBase = 744 * k + dy;
        primTop = 782 * k + dy;
        iconsTop = (782 + BTN_H + 26) * k + dy;
        colX = (MARGIN + BTN_W + COL_GAP) * k;
        metaBase = primTop + BTN_H * k / 2 + Ui.halfCap(metaP);
        synLine = 38 * k;
        synBase = iconsTop + Ui.cap(synP) + 3 * k;
        float synW = Math.min(w - MARGIN * k - colX, 1120 * k);   // ~90 characters a line, no more
        synopsis = detail == null ? new ArrayList<>() : wrap(detail.plot, synP, synW, 3);
        if (detail == null && !loading) synopsis.add("Aucune information sur ce titre.");
        creditBase = synBase + Math.max(0, synopsis.size() - 1) * synLine + (synopsis.isEmpty() ? 0 : 46 * k);
        credit1 = credit2 = "";
        if (detail != null) {
            credit1 = names(detail.director, 2);
            credit2 = names(detail.cast, 4);
        }

        float firstTop = titleBase - (titleLines.size() - 1) * titleLine - Ui.cap(titleP);
        scrollBy = Math.max(0, firstTop - 80 * k);
        tabsTop = 1000 * k - scrollBy;
        cardsTop = tabsTop + (TAB_H + 40) * k;

        // no backdrop: the poster on the right, above the title's line
        float bottom = firstTop - 44 * k, top = 110 * k;
        float ph = Math.min(495 * k, bottom - top), pw = ph * 2 / 3f;
        posterBox.set(w - MARGIN * k - pw, top, w - MARGIN * k, top + ph);
        if (req.series) buildSeason();
        applyMotion();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (!open) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) back();   // on the release: the press must not reach the home screen after the page closes
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(code);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) activate();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && e.getRepeatCount() == 0) play(playUrl, false, target);
                return true;
            default:
                return false;
        }
    }

    private void move(int code) {
        boolean shelfOk = req.series && !episodes.isEmpty();
        switch (zone) {
            case Z_PRIMARY:
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!actions.isEmpty()) {
                        zone = Z_ICONS;
                        action = 0;
                    } else if (shelfOk) {
                        toShelf();
                    }
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && !actions.isEmpty()) {
                    zone = Z_ICONS;
                    action = 0;
                }
                break;
            case Z_ICONS:
                if (code == KeyEvent.KEYCODE_DPAD_UP) zone = Z_PRIMARY;
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (action > 0) action--;
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (action < actions.size() - 1) action++;
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && shelfOk) {
                    toShelf();
                }
                break;
            case Z_SEASONS:
                if (code == KeyEvent.KEYCODE_DPAD_UP) toHero();
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !episodes.isEmpty()) zone = Z_EPISODES;
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int to = season + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (to >= 0 && to < seasonsOf().size()) {
                        season = to;
                        buildSeason();
                        episode = target != null ? Math.max(0, episodes.indexOf(target)) : 0;
                        slideTo = slideFor(episode);
                        shelf.invalidate();
                    }
                }
                break;
            case Z_EPISODES:
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (seasonsOf().size() > 1) zone = Z_SEASONS;
                    else toHero();
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (episode > 0) episode--;
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (episode < episodes.size() - 1) episode++;
                }
                slideTo = slideFor(episode);
                break;
            default:
                break;
        }
        kick();
    }

    private void toShelf() {
        zone = Z_EPISODES;
        scrollTo = 1;
    }

    private void toHero() {
        zone = Z_PRIMARY;
        scrollTo = 0;
    }

    private void back() {
        if (zone == Z_EPISODES || zone == Z_SEASONS) {
            toHero();
            kick();
            return;
        }
        close();
    }

    private void activate() {
        switch (zone) {
            case Z_PRIMARY:
                play(playUrl, false, target);
                break;
            case Z_ICONS:
                if (action >= actions.size()) return;
                if (actions.get(action) == A_RESTART) play(playUrl, true, target);
                else if (actions.get(action) == A_FORGET) forget();
                else if (actions.get(action) == A_LIST) toggleList();
                else if (actions.get(action) == A_WATCHED) toggleWatched();
                else trailer();
                break;
            case Z_SEASONS:
                if (!episodes.isEmpty()) zone = Z_EPISODES;
                kick();
                break;
            case Z_EPISODES:
                if (episode < episodes.size()) play(episodes.get(episode).url, false, episodes.get(episode));
                break;
            default:
                break;
        }
    }

    private void play(String url, boolean fromStart, Iptv.Episode ep) {
        if (url == null || url.isEmpty()) return;
        Intent it = new Intent(getContext(), Player.class);
        it.putExtra(Player.EXTRA_URL, url);
        String logo = req.poster.isEmpty() && detail != null ? detail.poster : req.poster;
        if (req.series && ep != null) {
            it.putExtra(Player.EXTRA_NAME, title + " · S" + ep.season + ", É" + ep.number);
            it.putExtra(Player.EXTRA_SERIES, req.seriesId);
        } else {
            it.putExtra(Player.EXTRA_NAME, title);
        }
        it.putExtra(Player.EXTRA_LOGO, logo == null ? "" : logo);
        it.putExtra(Player.EXTRA_FROM_START, fromStart);
        getContext().startActivity(it);
    }

    /** "Depuis le début", "Bande-annonce", "Ma liste", "Retirer de « En cours »": Ma liste changes with its state. */
    private String iconOf(int a) {
        if (a == A_LIST) return inList() ? "check" : "add";
        if (a == A_WATCHED) return "check";
        return ACTION_ICON[a];
    }

    private String nameOf(int a) {
        if (a == A_LIST) return inList() ? "Dans ma liste" : "Ma liste";
        if (a == A_WATCHED) return Prefs.isWatched(playUrl) ? "Vu — décocher" : "Marquer comme vu";
        return ACTION_NAME[a];
    }

    /** « Vu » à la main : le film est marqué (ou plus), la fiche et l'accueil le disent. */
    private void toggleWatched() {
        if (playUrl == null || playUrl.isEmpty()) return;
        Prefs.setWatched(playUrl, !Prefs.isWatched(playUrl));
        refresh();
        kick();
        host.watchingChanged();
    }

    private String listKey() {
        if (req == null) return "";
        if (req.series) return Prefs.listKey(true, req.seriesId);
        Matcher m = MOVIE_ID.matcher(req.url == null ? "" : req.url);
        return m.find() ? Prefs.listKey(false, m.group(1)) : "";
    }

    private boolean inList() {
        return Prefs.inList(listKey());
    }

    /** Puts the film or the series in « Ma liste », or takes it out; the home screen's row follows at once. */
    private void toggleList() {
        String k = listKey();
        if (k.isEmpty()) return;
        String name = detail != null && !detail.title.isEmpty() ? detail.title : req.title;
        String logo = detail != null && !detail.poster.isEmpty() ? detail.poster : req.poster;
        String year = detail != null && !detail.year.isEmpty() ? detail.year : req.year;
        Prefs.toggleList(k, name, logo, req.series ? req.seriesId : "", year, req.series ? "" : playUrl);
        host.listChanged();
        invalidateAll();
        kick();
    }

    /** Whether this film, or an episode of this series, is left halfway: it shows in "En cours". */
    private boolean watching() {
        if (!req.series) return playPos > 0;
        if (detail != null) for (Iptv.Episode e : detail.episodes) if (progress.containsKey(e.url)) return true;
        return Prefs.resumingSeries(req.seriesId);
    }

    /**
     * Out of "En cours": the saved position is dropped (every episode left halfway, for a series). The page says so
     * at once — Reprendre becomes Lecture, the time left and the progress go — and the focus goes back to Lecture;
     * the home screen rebuilds its shelf while it is still under the page.
     */
    private void forget() {
        if (req.series) {
            List<String> urls = new ArrayList<>();
            if (detail != null) for (Iptv.Episode e : detail.episodes) urls.add(e.url);
            Prefs.forgetSeries(req.seriesId, urls);
        } else {
            Prefs.forget(playUrl);
        }
        zone = Z_PRIMARY;
        action = 0;
        refresh();
        kick();
        host.watchingChanged();
    }

    /** The trailer, in SmartTube (the TV's YouTube app is gone: SmartTube is the only player). */
    private void trailer() {
        if (detail == null || detail.trailer.isEmpty()) return;
        String t = detail.trailer.trim();
        Matcher m = Pattern.compile("(?:v=|youtu\\.be/|embed/)([\\w-]{6,})").matcher(t);
        String id = m.find() ? m.group(1) : t;
        Uri uri = Uri.parse("https://www.youtube.com/watch?v=" + id);
        Intent it = new Intent(Intent.ACTION_VIEW, uri);
        it.setPackage("org.smarttube.stable");
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(it);
        } catch (Exception e) {
            try {
                getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                AppLog.w("trailer: no app for " + uri);
            }
        }
    }

    private float slideFor(int i) {
        int n = episodes.size();
        float step = (CARD_W + CARD_GAP) * k;
        float total = n * step - CARD_GAP * k, room = w - 2 * MARGIN * k;
        return Math.max(0, Math.min(i * step, total - room));
    }

    // MARK: - Animation

    private void kick() {
        if (framePosted || !open && getVisibility() != VISIBLE) return;
        framePosted = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        framePosted = false;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean more = false, heroDirty = false, shelfDirty = false;

        if (enter < 1) {
            if (fadeAt == 0) fadeAt = now;
            enter = Math.min(1f, (now - fadeAt) / 280f);
            back.invalidate();   // the home screen's picture under the page's background
            more = true;
            if (enter >= 1) snap = null;
        }
        if (bgPhoto != null && photoA < 1) {
            if (photoAt == 0) photoAt = now;
            photoA = Math.min(1f, (now - photoAt) / 380f);
            more = true;
            if (photoA >= 1 && enter >= 1) {
                back.setVisibility(INVISIBLE);   // covered for good by the backdrop: not drawn any more
                bgSoft = null;
            }
        } else if (photoA >= 1 && enter >= 1 && back.getVisibility() == VISIBLE && bgPhoto != null) {
            back.setVisibility(INVISIBLE);
            bgSoft = null;
        }
        float ia = approach(infoA, infoTo, dt * 8f);
        if (ia != infoA) {
            infoA = ia;
            heroDirty = true;
            more = true;
        }
        float sc = approach(scroll, scrollTo, dt * 9f);
        if (sc != scroll) {
            scroll = sc;
            more = true;
        }
        float pt = zone == Z_PRIMARY ? 1 : 0;
        if (primaryOn != pt) {
            primaryOn = approach(primaryOn, pt, dt * 14f);
            heroDirty = true;
            more = true;
        }
        for (int i = 0; i < actionOn.length; i++) {
            float to = zone == Z_ICONS && action == i ? 1 : 0;
            if (actionOn[i] != to) {
                actionOn[i] = approach(actionOn[i], to, dt * 14f);
                heroDirty = true;
                more = true;
            }
        }
        for (int i = 0; i < tabOn.length; i++) {
            float to = zone == Z_SEASONS && season == i ? 1 : 0;
            if (tabOn[i] != to) {
                tabOn[i] = approach(tabOn[i], to, dt * 14f);
                shelfDirty = true;
                more = true;
            }
        }
        for (int i = 0; i < epOn.length; i++) {
            float to = zone == Z_EPISODES && episode == i ? 1 : 0;
            if (epOn[i] != to) {
                epOn[i] = approach(epOn[i], to, dt * 13f);
                shelfDirty = true;
                more = true;
            }
        }
        float sl = approach(slide, slideTo, dt * 10f);
        if (sl != slide) {
            slide = sl;
            shelfDirty = true;
            more = true;
        }
        applyMotion();
        if (heroDirty) hero.invalidate();
        if (shelfDirty) shelf.invalidate();
        if (more && open) {
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** The scroll and the entrance, as layer properties: nothing is repainted for them. */
    private void applyMotion() {
        if (leaving) return;
        float in = ease(enter);
        float rise = (1 - in) * 30 * k;
        hero.setTranslationY(-scrollBy * scroll + rise);
        shelf.setTranslationY(scrollBy * (1 - scroll) + rise * 1.6f);
        shelf.setVisibility(req != null && req.series ? VISIBLE : INVISIBLE);
        hero.setAlpha(in);
        shelf.setAlpha(in);
        photo.setAlpha(photoA * in);
        float d = 0.72f * scroll * in;   // down at the episodes, the picture steps back behind the text above
        int vis = d > 0.002f ? VISIBLE : INVISIBLE;
        if (dim.getVisibility() != vis) dim.setVisibility(vis);
    }

    private static float approach(float v, float to, float step) {
        float n = v + (to - v) * Math.min(1f, step);
        return Math.abs(to - n) < 0.002f ? to : n;
    }

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    // MARK: - Size and baked glass

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw;
        h = nh;
        k = Math.min(w / 1920f, h / 1080f);
        for (Paint p : new Paint[]{dotP, metaP, badgeP, synP, labelP, nameP, btnP, timeP, iconNameP, tabP, epTitleP, epMetaP,
                epPlotP, emptyP}) {
            p.setTextSize(p.getTextSize() / lastK * k);
        }
        lastK = k;
        dotW = dotP.measureText("·");
        kitBtn = Liquid.capsule(Liquid.CLEAR, Math.round(BTN_H * k), k, false);
        kitBtnOn = Liquid.capsule(Liquid.LIT, Math.round(BTN_H * k), k, true);
        kitIcon = Liquid.capsule(Liquid.CLEAR, Math.round(ICON_D * k), k, false);
        kitIconOn = Liquid.capsule(Liquid.LIT, Math.round(ICON_D * k), k, true);
        kitTab = Liquid.capsule(Liquid.CLEAR, Math.round(TAB_H * k), k, false);
        kitTabOn = Liquid.capsule(Liquid.FOCUS, Math.round(TAB_H * k), k, true);
        cardPad = Math.round(30 * k);
        cardShadow = Liquid.shadow(Math.round(CARD_W * k), Math.round(CARD_H * k), CARD_R * k, cardPad, 22 * k, 0.62f,
                0.5f, -6 * k);
        posterPad = Math.round(40 * k);
        posterShadow = Liquid.shadow(Math.round(330 * k), Math.round(495 * k), 26 * k, posterPad, 30 * k, 0.6f, 0.5f,
                -8 * k);
        topLight = new LinearGradient(0, 0, 0, CARD_H * k * 0.55f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        cardRim = new LinearGradient(0, 0, 0, CARD_H * k, 0x8CFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP);
        for (Liquid.Kit kit : new Liquid.Kit[]{kitBtn, kitBtnOn, kitIcon, kitIconOn, kitTab, kitTabOn}) {
            kit.face.prepareToDraw();   // uploaded now, while nothing moves: the page's first frame has nothing to upload
            if (kit.shadow != null) kit.shadow.prepareToDraw();
        }
        cardShadow.prepareToDraw();
        posterShadow.prepareToDraw();
        layoutPage();
        if (req != null && open) {
            bakeSoft();
            if (backdrop != null) {
                bgPhoto = null;
                Bitmap b = backdrop;
                backdrop = null;
                showBackdrop(b);
            }
        }
        invalidateAll();
    }

    private float lastK = 1;

    private void invalidateAll() {
        back.invalidate();
        photo.invalidate();
        hero.invalidate();
        shelf.invalidate();
    }

    // MARK: - Drawing

    private void paint(int part, Canvas c) {
        if (req == null || kitBtn == null) return;
        switch (part) {
            case BACK:
                if (leaving) {
                    if (snap != null) c.drawBitmap(snap, 0, 0, null);
                    break;
                }
                float a = ease(enter);
                if (snap != null && a < 1) c.drawBitmap(snap, 0, 0, null);
                if (bgSoft != null) {
                    image.setAlpha(Math.round(255 * a));
                    c.drawBitmap(bgSoft, 0, 0, image);
                    image.setAlpha(255);
                } else {
                    c.drawColor(Ui.alpha(0xFF0B1116, a));
                }
                break;
            case PHOTO:
                if (bgPhoto != null) c.drawBitmap(bgPhoto, 0, 0, null);
                break;
            case DIM:
                c.drawColor(0xFF05080B);
                break;
            case HERO: paintHero(c); break;
            case SHELF: paintShelf(c); break;
            default: break;
        }
    }

    private void paintHero(Canvas c) {
        // the poster, when there is no backdrop to show
        if (posterCard && poster != null && posterBox.height() > 120 * k) {
            float a = infoA;
            float pad = posterPad * posterBox.width() / (330 * k);
            dst.set(posterBox.left - pad, posterBox.top - pad + 18 * k, posterBox.right + pad, posterBox.bottom + pad + 18 * k);
            image.setAlpha(Math.round(255 * a));
            c.drawBitmap(posterShadow, null, dst, image);
            image.setAlpha(255);
            roundImage(c, poster, posterBox, 24 * k, a);
            rimPaint.setShader(null);
            rimPaint.setColor(Ui.alpha(0x47FFFFFF, a));
            rimPaint.setStrokeWidth(1.2f * k);
            box.set(posterBox);
            box.inset(0.6f * k, 0.6f * k);
            c.drawRoundRect(box, 24 * k, 24 * k, rimPaint);
        }

        // title
        float y = titleBase - (titleLines.size() - 1) * titleLine;
        for (String l : titleLines) {
            c.drawText(l, MARGIN * k - Ui.leftBearing(titleP, l), y, titleP);
            y += titleLine;
        }

        // Lecture / Reprendre: clear glass, white when it has the focus
        float on = primaryOn;
        boolean ready = !playUrl.isEmpty();
        float grow = 1 + 0.04f * on;
        float bw = BTN_W * k, bh = BTN_H * k, cx = MARGIN * k + bw / 2, cy = primTop + bh / 2;
        box.set(cx - bw / 2 * grow, cy - bh / 2 * grow, cx + bw / 2 * grow, cy + bh / 2 * grow);
        if (on < 1) Liquid.draw(c, kitBtn, box, glass, 1 - on, false, 0);
        if (on > 0) Liquid.draw(c, kitBtnOn, box, glass, on, true, 6 * k);
        int ink = blend(0xFFFFFFFF, Liquid.INK_ON_LIT, on);
        if (!ready) ink = Ui.alpha(ink, 0.55f);
        btnP.setColor(ink);
        float lw = btnP.measureText(primary), tw = primaryTime.isEmpty() ? 0 : timeP.measureText(primaryTime) + 14 * k;
        float glyph = ready ? 30 * k : 0;
        float x = cx - (glyph + lw + tw) / 2;
        float base = cy + Ui.halfCap(btnP);
        if (ready) Symbols.draw(getContext(), c, "play", x + 11 * k, cy, 27 * k * grow, ink);
        c.drawText(primary, x + glyph, base, btnP);
        if (tw > 0) {
            timeP.setColor(Ui.alpha(ink, 0.62f));
            c.drawText(primaryTime, x + glyph + lw + 14 * k, base, timeP);
        }

        // the round buttons, and the name of the one with the focus under it
        for (int i = 0; i < actions.size(); i++) {
            float a = i < actionOn.length ? actionOn[i] : 0f;
            float d = ICON_D * k * (1 + 0.08f * a), icx = MARGIN * k + ICON_D * k / 2 + i * (ICON_D + ICON_GAP) * k;
            float icy = iconsTop + ICON_D * k / 2;
            box.set(icx - d / 2, icy - d / 2, icx + d / 2, icy + d / 2);
            if (a < 1) Liquid.draw(c, kitIcon, box, glass, 1 - a, false, 0);
            if (a > 0) Liquid.draw(c, kitIconOn, box, glass, a, true, 6 * k);
            Symbols.draw(getContext(), c, iconOf(actions.get(i)), icx, icy, 27 * k * (1 + 0.06f * a),
                    blend(0xEBFFFFFF, Liquid.INK_ON_LIT, a));
            if (a > 0.01f) {
                String n = nameOf(actions.get(i));
                float nw = iconNameP.measureText(n);
                float nx = Math.max((MARGIN - 24) * k, icx - nw / 2);   // centred under it, as tvOS does
                iconNameP.setColor(Ui.alpha(0xE6FFFFFF, a));
                c.drawText(n, nx, iconsTop + ICON_D * k + 38 * k, iconNameP);
            }
        }

        // the facts, the synopsis, who made it: they fade in when the panel answers
        float ia = infoA;
        if (ia <= 0.003f) return;
        x = colX;
        float right = w - MARGIN * k;
        float mid = metaBase - Ui.halfCap(metaP);
        for (Seg s : meta) {
            float sw = s.width(this);
            if (x + sw > right) break;
            switch (s.kind) {
                case Seg.PROGRESS: {
                    float tw2 = 110 * k, th = 6 * k;
                    box.set(x, mid - th / 2, x + tw2, mid + th / 2);
                    fill.setColor(Ui.alpha(0x47FFFFFF, ia));
                    c.drawRoundRect(box, th / 2, th / 2, fill);
                    box.right = x + Math.max(14 * k, tw2 * s.value);
                    fill.setColor(Ui.alpha(0xF2FFFFFF, ia));
                    c.drawRoundRect(box, th / 2, th / 2, fill);
                    metaP.setColor(Ui.alpha(0xE0FFFFFF, ia));
                    c.drawText(s.text, x + tw2 + 14 * k, metaBase, metaP);
                    break;
                }
                case Seg.RATING:
                    Symbols.draw(getContext(), c, "star", x + 11 * k, mid - 1 * k, 21 * k, Ui.alpha(0xE0FFFFFF, ia));
                    metaP.setColor(Ui.alpha(0xE0FFFFFF, ia));
                    c.drawText(s.text, x + 28 * k, metaBase, metaP);
                    break;
                case Seg.BADGE: {
                    float bw2 = badgeP.measureText(s.text) + 18 * k, bh2 = 30 * k;
                    box.set(x, mid - bh2 / 2, x + bw2, mid + bh2 / 2);
                    stroke.setColor(Ui.alpha(0x8CFFFFFF, ia));
                    stroke.setStrokeWidth(1.5f * k);
                    c.drawRoundRect(box, 7 * k, 7 * k, stroke);
                    badgeP.setColor(Ui.alpha(0xE0FFFFFF, ia));
                    c.drawText(s.text, x + 9 * k, mid + Ui.halfCap(badgeP), badgeP);
                    break;
                }
                default:
                    metaP.setColor(Ui.alpha(0xE0FFFFFF, ia));
                    c.drawText(s.text, x, metaBase, metaP);
                    break;
            }
            x += sw;
            if (s != meta.get(meta.size() - 1)) {
                dotP.setColor(Ui.alpha(0x73FFFFFF, ia));
                c.drawText("·", x + 15 * k - dotW / 2, metaBase, dotP);
            }
            x += 30 * k;
        }

        y = synBase;
        synP.setColor(Ui.alpha(detail == null ? 0x99FFFFFF : 0xD1FFFFFF, ia));
        for (String l : synopsis) {
            c.drawText(l, colX, y, synP);
            y += synLine;
        }
        x = colX;
        if (!credit1.isEmpty()) x = credit(c, "Réalisation", credit1, x, right, ia) + 40 * k;
        if (!credit2.isEmpty() && x < right - 200 * k) credit(c, "Avec", credit2, x, right, ia);
    }

    private float credit(Canvas c, String label, String names, float x, float right, float a) {
        labelP.setColor(Ui.alpha(0x99FFFFFF, a));
        nameP.setColor(Ui.alpha(0xC7FFFFFF, a));
        c.drawText(label, x, creditBase, labelP);
        x += labelP.measureText(label) + 12 * k;
        String n = fit(names, nameP, right - x);
        c.drawText(n, x, creditBase, nameP);
        return x + nameP.measureText(n);
    }

    private void paintShelf(Canvas c) {
        if (!req.series) return;
        List<Integer> seasons = seasonsOf();
        if (detail == null) return;
        if (seasons.isEmpty()) {
            c.drawText("Aucun épisode disponible.", MARGIN * k, tabsTop + 36 * k, emptyP);
            return;
        }

        // seasons: the one shown on clear glass, lit glass when it has the focus (as the home screen's tabs)
        float th = TAB_H * k;
        for (int i = 0; i < seasons.size() && i < tabX.length; i++) {
            float on = tabOn[i];
            boolean sel = i == season;
            box.set(tabX[i], tabsTop, tabX[i] + tabW[i], tabsTop + th);
            if (sel && on < 1) Liquid.draw(c, kitTab, box, glass, 1 - on, false, 0);
            if (on > 0) Liquid.draw(c, kitTabOn, box, glass, on, true, 6 * k);
            int s = seasons.get(i);
            String l = s == 0 ? "Bonus" : "Saison " + s;
            float grow = 1 + 0.07f * on;
            tabP.setTextSize(22 * k * grow);
            tabP.setColor(sel ? 0xFFFFFFFF : 0x99FFFFFF);
            float lw = tabP.measureText(l);
            c.drawText(l, box.centerX() - lw / 2, box.centerY() + Ui.halfCap(tabP), tabP);
        }
        tabP.setTextSize(22 * k);

        // episodes: the picture, the progress, the number and title, how long, two lines of story
        float cw = CARD_W * k, ch = CARD_H * k, step = (CARD_W + CARD_GAP) * k;
        for (int i = 0; i < episodes.size(); i++) {
            float x = MARGIN * k + i * step - slide;
            if (x > w || x + cw < 0) continue;
            float on = i < epOn.length ? epOn[i] : 0;
            float grow = 1 + 0.06f * on;
            box.set(x, cardsTop, x + cw, cardsTop + ch);
            float gx = cw * (grow - 1) / 2, gy = ch * (grow - 1) / 2;
            box.inset(-gx, -gy);
            float r = CARD_R * k * grow;
            if (on > 0.02f) {
                float pad = cardPad * grow, dy = 14 * k * on;
                dst.set(box.left - pad, box.top - pad + dy, box.right + pad, box.bottom + pad + dy);
                image.setAlpha(Math.round(255 * Math.min(1f, on)));
                c.drawBitmap(cardShadow, null, dst, image);
                image.setAlpha(255);
            }
            Iptv.Episode e = episodes.get(i);
            Bitmap b = e.still.isEmpty() ? null
                    : Posters.get(getContext()).get(e.still, (u, bm) -> shelf.postInvalidateOnAnimation());
            if (b == null && backdrop != null) {
                // no still of its own: the series' picture, dimmed, says "an episode" without posing as one
                fill.setColor(0xFF10171C);
                c.drawRoundRect(box, r, r, fill);
                roundImage(c, backdrop, box, r, 0.42f);
                Symbols.draw(getContext(), c, "play", box.centerX(), box.centerY(), 44 * k, 0x8CFFFFFF);
            } else if (b != null) {
                roundImage(c, b, box, r, 1f);
            } else {
                fill.setColor(0xFF1E2A31);
                c.drawRoundRect(box, r, r, fill);
                Symbols.draw(getContext(), c, "play", box.centerX(), box.centerY(), 48 * k, 0x4DFFFFFF);
            }
            if (on > 0.02f) {
                c.save();
                c.translate(box.left, box.top);
                shade.setShader(topLight);
                shade.setAlpha(Math.round(255 * Math.min(1f, on)));
                c.drawRoundRect(0, 0, box.width(), box.height(), r, r, shade);
                c.restore();
                shade.setShader(null);
            }
            if (i < epProgress.length && epProgress[i] > 0.005f) {
                float ph = 6 * k, inset = 20 * k, py = box.bottom - 16 * k;
                dst.set(box.left + inset, py - ph, box.right - inset, py);
                fill.setColor(0x8C000000);
                c.drawRoundRect(dst, ph / 2, ph / 2, fill);
                dst.right = dst.left + Math.max(24 * k, (box.width() - 2 * inset) * Math.min(1f, epProgress[i]));
                fill.setColor(ACCENT);
                c.drawRoundRect(dst, ph / 2, ph / 2, fill);
            } else if (i < epWatched.length && epWatched[i]) {
                float rr = 16 * k, ccx = box.right - 26 * k, ccy = box.top + 26 * k;
                fill.setColor(0xD90B1116);
                c.drawCircle(ccx, ccy, rr, fill);
                Symbols.draw(getContext(), c, "check", ccx, ccy, 20 * k, 0xFFFFFFFF);
            }
            float sw = (1f + 1.2f * on) * k;
            rimPaint.setShader(cardRim);
            rimPaint.setStrokeWidth(sw);
            rimPaint.setAlpha(Math.round(255 * Math.min(1f, 0.25f + 0.75f * on)));
            c.save();
            c.translate(box.left, box.top);
            c.scale(grow, grow);
            dst.set(sw / 2, sw / 2, cw - sw / 2, ch - sw / 2);
            c.drawRoundRect(dst, CARD_R * k - sw / 2, CARD_R * k - sw / 2, rimPaint);
            c.restore();
            rimPaint.setShader(null);

            float ty = cardsTop + ch + 42 * k + gy;
            epTitleP.setColor(blend(0xD9FFFFFF, 0xFFFFFFFF, on));
            c.drawText(epTitle[i], x, ty, epTitleP);
            if (!epMeta[i].isEmpty()) {
                ty += 30 * k;
                c.drawText(epMeta[i], x, ty, epMetaP);
            }
            ty += 4 * k;
            for (String l : epPlot[i]) {
                ty += 27 * k;
                c.drawText(l, x, ty, epPlotP);
            }
        }
    }

    // MARK: - Pictures

    /** A picture cropped to fill `r` with rounded corners, in one draw (a shader, no clip). */
    private void roundImage(Canvas c, Bitmap b, RectF r, float radius, float alpha) {
        BitmapShader s = shaders.get(b);
        if (s == null) {
            s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaders.put(b, s);
        }
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(r.width() / bw, r.height() / bh);
        matrix.setScale(scale, scale);
        matrix.postTranslate(r.left + (r.width() - bw * scale) / 2, r.top + (r.height() - bh * scale) / 2);
        s.setLocalMatrix(matrix);
        cardPaint.setShader(s);
        cardPaint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
        c.drawRoundRect(r, radius, radius, cardPaint);
        cardPaint.setShader(null);
    }

    // MARK: - Text

    /** One fact of the line under the title. */
    private static final class Seg {
        static final int TEXT = 0, RATING = 1, BADGE = 2, PROGRESS = 3;
        final int kind;
        final String text;
        final float value;

        Seg(int kind, String text, float value) {
            this.kind = kind;
            this.text = text;
            this.value = value;
        }

        float width(DetailView v) {
            float k = v.k;
            switch (kind) {
                case RATING: return 28 * k + v.metaP.measureText(text);
                case BADGE: return v.badgeP.measureText(text) + 18 * k;
                case PROGRESS: return 124 * k + v.metaP.measureText(text);
                default: return v.metaP.measureText(text);
            }
        }
    }

    /** "0:02:36", "1:12:05": where the film was left. */
    private static String hms(long sec) {
        return String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    /** "1 h 35 min", "52 min". */
    private static String span(long ms) {
        long min = Math.max(1, Math.round(ms / 60000.0));
        if (min < 60) return min + " min";
        long hh = min / 60, mm = min % 60;
        return mm == 0 ? hh + " h" : hh + " h " + mm + " min";
    }

    /** For the home screen's spotlight: a series' name without its year and tags. */
    static String seriesTitle(String n) {
        return seriesName(n);
    }

    /** "Drame, Guerre & Politique": the first few genres, TMDB's English TV ones in French. */
    static String genres(String raw, int max) {
        if (raw == null || raw.isEmpty()) return "";
        String[] g = raw.replace(" / ", ",").replace("/", ",").split("\\s*,\\s*");
        StringBuilder b = new StringBuilder();
        for (int i = 0, n = 0; i < g.length && n < max; i++) {
            String one = genre(g[i].trim());
            if (one.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(one);
            n++;
        }
        return b.toString();
    }

    /** A series' name as the panel gives it, without its year and its tags. */
    private static String seriesName(String n) {
        if (n == null) return "";
        int cut = n.indexOf(" · S");
        if (cut > 0) n = n.substring(0, cut);   // a "continue watching" card: "Série · S1, É3"
        String t = HomeView.pretty(n);
        Matcher m = YEAR_TAIL.matcher(t);
        if (m.find()) t = t.substring(0, m.start());
        return t.trim();
    }

    /** TMDB's English-only TV genres, in French like the others. */
    private static String genre(String g) {
        switch (g) {
            case "Action & Adventure": return "Action & Aventure";
            case "Sci-Fi & Fantasy": return "Science-Fiction & Fantastique";
            case "War & Politics": return "Guerre & Politique";
            case "Kids": return "Enfants";
            case "Reality": return "Téléréalité";
            case "Soap": return "Feuilleton";
            case "Talk": return "Talk-show";
            case "News": return "Actualités";
            default: return g;
        }
    }

    /** The first few names of a comma-separated list. */
    private static String names(String list, int max) {
        if (list == null || list.isEmpty()) return "";
        String[] p = list.split("\\s*,\\s*");
        StringBuilder b = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0, n = 0; i < p.length && n < max; i++) {
            String name = p[i].trim();
            if (name.isEmpty()) continue;
            if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) continue;   // the panel repeats the director
            if (b.length() > 0) b.append(", ");
            b.append(name);
            n++;
        }
        return b.toString();
    }

    private static List<String> wrap(String text, Paint p, float width, int max) {
        List<String> lines = new ArrayList<>();
        if (text == null) return lines;
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return lines;
        String[] words = t.split(" ");
        StringBuilder line = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String next = line.length() == 0 ? words[i] : line + " " + words[i];
            if (line.length() == 0 || p.measureText(next) <= width) {
                line.setLength(0);
                line.append(next);
                continue;
            }
            lines.add(line.toString());
            line.setLength(0);
            line.append(words[i]);
            if (lines.size() == max) break;
        }
        if (lines.size() < max) {
            if (line.length() > 0) lines.add(fit(line.toString(), p, width));
            return lines;
        }
        // too long: the last line takes what is left and ends with an ellipsis
        StringBuilder rest = new StringBuilder(lines.get(max - 1));
        for (int j = i; j < words.length; j++) rest.append(' ').append(words[j]);
        lines.set(max - 1, fit(rest.toString(), p, width));
        if (!lines.get(max - 1).endsWith("…")) lines.set(max - 1, fit(lines.get(max - 1) + " …", p, width));
        return lines;
    }

    /** `s` as is if it fits, otherwise cut at a word (or a letter) with an ellipsis. */
    private static String fit(String s, Paint p, float width) {
        if (s == null) return "";
        if (p.measureText(s) <= width) return s;
        int n = p.breakText(s, true, Math.max(0, width - p.measureText("…")), null);
        String cut = s.substring(0, Math.max(0, n));
        int sp = cut.lastIndexOf(' ');
        if (sp > cut.length() * 0.6f) cut = cut.substring(0, sp);
        cut = cut.replaceAll("[\\s,;:.\\-–]+$", "");
        return cut + "…";
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
