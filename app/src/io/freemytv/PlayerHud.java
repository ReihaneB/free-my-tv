package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

/**
 * The player's controls, after tvOS 26: a scrim at the foot of the picture, the title, three glass buttons
 * (subtitles, audio, format), a glass progress bar with the elapsed and remaining time under it, and the track
 * popover above its button. Drawn on canvases with the home screen's glass materials.
 *
 * <p>Built for a TV GPU that is already busy with the picture: every part is its own small layer, so a frame only
 * redraws the part that changed (the TV supports partial updates); every motion — the controls fading and rising,
 * the popover growing, the feedback glyph, the spinner turning — is a layer property (alpha, scale, translation,
 * rotation), so nothing is re-recorded while it moves. Hidden layers are not drawn at all; while the controls rest,
 * only the time strip is redrawn, once a second.
 */
final class PlayerHud extends ViewGroup implements Choreographer.FrameCallback {

    static final int MENU_SUBS = 0, MENU_AUDIO = 1, MENU_FORMAT = 2;
    /** The player's own menu (Up / Down on a channel), which is not a list of tracks but of things to do. */
    private static final int MENU_OPTIONS = 3;
    private static final String[] ICONS = {"subtitles", "audio", "aspect"};
    private static final String[] MENU_TITLES = {"Sous-titres", "Audio", "Format de l'image", "Options"};
    private static final int HIDE_MS = 5000, COMMIT_MS = 700;
    private static final int RED = 0xFFFF453A;

    private static final int CONTROLS = 0, BAR = 1, SUBS = 2, MENU = 3, GLYPH = 4, SPINNER = 5, ERROR = 6, IMAGES = 7,
            LIST = 8, DIGITS = 9;
    /** In `buttons`: "Depuis le début" (a channel with a replay), the others are the popovers' menus. */
    private static final int START_OVER = -1;
    private static final int[] VOD_BUTTONS = {MENU_SUBS, MENU_AUDIO, MENU_FORMAT};
    private static final int[] LIVE_BUTTONS = {START_OVER, MENU_SUBS, MENU_AUDIO, MENU_FORMAT};

    /** One part of the controls: draws in screen coordinates, through the HUD. */
    private final class Layer extends View {
        final int part;

        Layer(Context c, int part) {
            super(c);
            this.part = part;
            setVisibility(INVISIBLE);
        }

        @Override protected void onDraw(Canvas c) {
            c.save();
            c.translate(-getLeft(), -getTop());
            paint(part, c);
            c.restore();
        }

        // alpha is applied draw by draw (no offscreen buffer per frame)
        @Override public boolean hasOverlappingRendering() { return false; }
    }

    private final Player host;
    private final Layer controls, bar, subs, images, menuView, glyphView, spinner, errorView, listView, digitsView;
    private final Layer[] layers, fading;
    private float k = 1, w, h;

    // what is playing
    private String title = "", titleShown = "", meta = "", note = "";
    private boolean ready, live, buffering, failed;
    private String error = "";
    private long duration;

    // the position, read from the player at most twice a second and carried forward in between
    private long posBase, posAt;
    private boolean playing;
    private long pendingSeek = -1, pendingUntil;

    // scrubbing: the bar previews a position, the seek happens when the remote rests
    private boolean scrubbing;
    private long scrubTo;

    // focus and visibility
    private static final int ON_BAR = 0, ON_BUTTONS = 1;
    private int focus = ON_BAR, button;
    private float barOn = 1, hudA, hudTo, scrubA;
    private final float[] buttonOn = new float[4];
    private int[] buttons = VOD_BUTTONS;

    // live TV: the channel, its logo, its programme now and next (read from the guide when it ends)
    private Channels.Channel channel;
    private Epg.Show replay;
    private Bitmap logoBmp, logoFace;
    private String logoUrl = "";
    private Epg.Show liveShow, liveNext;
    private long liveUntil;
    private String liveMeta = "", liveLeft = "", liveNextText = "";
    private final Epg.Listener guideChanged = this::onGuide;

    // the channel list (OK on a channel): every channel, its number, its logo and what it shows
    private boolean listOpen, listClosing;
    private int listRow, listFirst, listVisible;
    private float listA, listLens, listRowH, listHead;
    private final RectF listPanel = new RectF();
    private Bitmap listFace, listShadow;
    private int listPad;
    private Paint listNumP, listNameP, listShowP, digitsP, digitsNameP;

    // the digits: "1", "12" — the channel comes after a short pause or at the third digit
    private final StringBuilder digits = new StringBuilder();
    private String digitsNote = "";
    private final Runnable digitsRun = this::commitDigits, noteRun = this::clearNote;

    // the popover
    private int menu = -1, row, checked = -1, first;
    private boolean menuClosing;
    private String[] rows = new String[0];
    /** The options menu: what each row does, and the value written on its right (a track's name, the picture's fit). */
    private static final int NOTHING = -2, ACTIONS = -3, A_START = 0, A_LIVE = 1, A_LIST = 2, A_SUBS = 3,
            A_AUDIO = 4, A_FORMAT = 5;
    private int[] acts = new int[0];
    private String[] values = new String[0];
    private float menuA, rowLens;
    private final RectF panel = new RectF();
    private Bitmap panelFace, panelShadow;
    private int panelPad, visibleRows;
    /** Popover glass baked ahead (off the UI thread) for each size the three popovers will take: key = w << 16 | h. */
    private final java.util.Map<Integer, Bitmap[]> panels = new java.util.concurrent.ConcurrentHashMap<>();

    // feedback in the middle (play, pause, ±10 s)
    private String glyph;
    private long glyphAt = -1;

    private String[] subLines = new String[0];
    private Bitmap[] subImages = new Bitmap[0];
    private RectF[] subPlaces = new RectF[0];
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private long lastFrame;
    private boolean framePosted;

    // layout
    private float margin, barCY, timesBase, titleBase, metaBase, buttonD, buttonGap, buttonCY, rowH, headH, rise;
    private Liquid.Kit kitButton, kitButtonOn, kitRow, kitBubble, kitBadge, kitGlyph, kitDigits, kitListRow;
    private final Runnable liveBarTick = this::barTick;

    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint(Paint.DITHER_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private Paint metaP, timeP, subP, headP, rowP, valueP, bubbleP, badgeP, errP, errSmallP;
    private final RectF box = new RectF(), track = new RectF();
    private final Runnable hideRun = this::autoHide, commitRun = this::commitScrub;

    // time labels are rebuilt only when the second changes
    private long elapsedSec = -1, remainSec = -1, bubbleSec = -1;
    private String elapsedText = "", remainText = "", bubbleText = "", errorDetail = "";

    PlayerHud(Context c, Player host) {
        super(c);
        this.host = host;
        Ui.init(c);
        setWillNotDraw(true);
        setClipChildren(false);
        spin.setStyle(Paint.Style.STROKE);
        spin.setStrokeCap(Paint.Cap.ROUND);
        controls = new Layer(c, CONTROLS);
        bar = new Layer(c, BAR);
        subs = new Layer(c, SUBS);
        images = new Layer(c, IMAGES);
        menuView = new Layer(c, MENU);
        glyphView = new Layer(c, GLYPH);
        spinner = new Layer(c, SPINNER);
        errorView = new Layer(c, ERROR);
        listView = new Layer(c, LIST);
        digitsView = new Layer(c, DIGITS);
        layers = new Layer[]{images, controls, bar, subs, menuView, glyphView, spinner, errorView, listView, digitsView};
        fading = new Layer[]{controls, bar};
        for (Layer l : layers) addView(l);
        Epg.get(c).addListener(guideChanged);
    }

    // MARK: - Live TV

    private void onGuide() {
        liveUntil = 0;
        if (channel != null) readShow(System.currentTimeMillis());
        if (listOpen) listView.invalidate();
    }

    private void clearNote() {
        digitsNote = "";
        digitsView.setVisibility(INVISIBLE);
    }

    private void barTick() {
        bar.invalidate();
    }

    /** The channel on screen (null: a film or an episode), and the programme replayed from its start if any. */
    void channel(Channels.Channel c, Epg.Show replayed) {
        channel = c;
        replay = replayed;
        buttons = c != null && replayed == null && c.replay != null ? LIVE_BUTTONS : VOD_BUTTONS;
        button = Math.min(button, buttons.length - 1);
        if (c == null) return;
        if (!c.logo.equals(logoUrl)) {
            logoUrl = c.logo;
            final String want = c.logo;
            logoBmp = Posters.get(getContext()).get(want, (u, b) -> post(() -> {
                if (!u.equals(logoUrl)) return;
                logoBmp = b;
                controls.invalidate();
            }));
        }
        liveUntil = 0;
        readShow(System.currentTimeMillis());
        controls.invalidate();
        bar.invalidate();
        hudTo = 1;
        scheduleHide();
        kick();
    }

    private boolean liveMode() {
        return channel != null && replay == null;
    }

    /** The programme now and next, and the lines that say them; true when it changed. */
    private boolean readShow(long now) {
        if (channel == null || now < liveUntil) return false;
        Epg guide = Epg.get(getContext());
        Epg.Show s = guide.now(channel.epg, now), n = guide.next(channel.epg, now);
        liveShow = s;
        liveNext = n;
        liveUntil = s != null ? Math.min(s.stop, now + 60_000 - now % 60_000) : now + 15_000;
        if (replay != null) return true;
        title = s != null ? s.title : channel.name;
        liveMeta = channel.number + "  ·  " + channel.name + (s != null ? "  ·  " + Epg.span(s) : "");
        liveLeft = s != null ? Epg.left(s, now) : "";
        liveNextText = n != null ? "À suivre  ·  " + Epg.clock(n.start) + "  " + n.title : "";
        ellipsize();
        controls.invalidate();
        return true;
    }

    private void activateButton() {
        int b = buttons[Math.max(0, Math.min(buttons.length - 1, button))];
        if (b == START_OVER) {
            if (host.startOver()) flash("restart");
            return;
        }
        openMenu(b);
    }

    private int indexOfMenu(int which) {
        for (int i = 0; i < buttons.length; i++) if (buttons[i] == which) return i;
        return 0;
    }

    private String iconOf(int i) {
        int b = buttons[i];
        return b == START_OVER ? "restart" : ICONS[b];
    }

    // MARK: - The channel list

    private void openList() {
        List<Channels.Channel> all = Channels.get(getContext()).all;
        if (all.isEmpty()) return;
        listOpen = true;
        listClosing = false;
        Channels.Channel at = channel;
        listRow = Math.max(0, at == null ? 0 : all.indexOf(at));
        listLens = listRow;
        listFirst = Math.max(0, listRow - 3);
        keepListRow(all.size());
        List<String> ids = new java.util.ArrayList<>();
        for (Channels.Channel c : all) if (!c.epg.isEmpty()) ids.add(c.epg);
        Epg.get(getContext()).warm(ids);
        hudTo = 0;
        removeCallbacks(hideRun);
        listA = Math.min(listA, 0.001f);
        listView.setAlpha(0);
        reveal(listView);
        listView.invalidate();
        kick();
    }

    private void closeList() {
        listClosing = true;
        kick();
    }

    private void keepListRow(int count) {
        if (listVisible <= 0) return;
        if (listRow < listFirst + 1) listFirst = listRow - 1;
        if (listRow > listFirst + listVisible - 2) listFirst = listRow - listVisible + 2;
        listFirst = Math.max(0, Math.min(listFirst, Math.max(0, count - listVisible)));
    }

    private boolean listKey(int code, KeyEvent e) {
        List<Channels.Channel> all = Channels.get(getContext()).all;
        int n = all.size();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN: {
                boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                        || code == KeyEvent.KEYCODE_PAGE_DOWN;
                boolean page = code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN;
                int step = page ? Math.max(1, listVisible - 2) : e.getRepeatCount() > 8 ? 2 : 1;
                listRow = Math.max(0, Math.min(n - 1, listRow + (down ? step : -step)));
                keepListRow(n);
                listView.invalidate();
                kick();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0 && listRow < n) {
                    host.zapTo(all.get(listRow));
                    closeList();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_GUIDE:
                closeList();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    // MARK: - The digits

    private void digit(int d) {
        removeCallbacks(noteRun);
        digitsNote = "";
        if (digits.length() == 0 && d == 0) {   // "0" alone: the channel before
            host.zapPrevious();
            return;
        }
        digits.append(d);
        reveal(digitsView);
        digitsView.invalidate();
        removeCallbacks(digitsRun);
        if (digits.length() >= 3) commitDigits();
        else postDelayed(digitsRun, 1600);
    }

    private void commitDigits() {
        removeCallbacks(digitsRun);
        if (digits.length() == 0) return;
        int n;
        try { n = Integer.parseInt(digits.toString()); } catch (NumberFormatException e) { n = -1; }
        digits.setLength(0);
        if (n > 0 && host.zapTo(n)) {
            if (listOpen) closeList();
            digitsView.setVisibility(INVISIBLE);
            return;
        }
        digitsNote = "Pas de chaîne " + n;
        digitsView.invalidate();
        postDelayed(noteRun, 1600);
    }

    // MARK: - From the player

    void loading(String title, String meta) {
        this.title = title == null ? "" : title;
        liveUntil = 0;   // a channel reads its programme again (the title becomes the programme's)
        this.meta = meta;
        ready = false;
        failed = false;
        // a new stream (or the same one built again): nothing of the last one stays
        live = false;
        duration = 0;
        note = "";
        scrubbing = false;
        pendingSeek = -1;
        elapsedSec = remainSec = -1;
        removeCallbacks(commitRun);
        if (menu >= 0) {
            menu = -1;
            menuView.setVisibility(INVISIBLE);
        }
        errorView.setVisibility(INVISIBLE);
        bar.invalidate();
        ellipsize();
        hudTo = 1;
        controls.invalidate();
        kick();
    }

    void prepared(boolean live, long duration, String meta, String note) {
        this.live = live;
        this.duration = Math.max(0, duration);
        this.meta = meta;
        this.note = note == null ? "" : note;
        ready = true;
        playing = true;
        posAt = 0;
        controls.invalidate();
        bar.invalidate();
        show();
        bakePanels();
    }

    /** Sizes the three popovers now and bakes their glass on a worker: opening one later costs no frame. */
    private void bakePanels() {
        if (w <= 0) return;
        final int[] sizes = new int[3];
        for (int m = 0; m < 3; m++) {
            String[] r = host.menuRows(m);
            if (r == null) continue;
            String[] shown = r.length == 0 ? new String[]{m == MENU_SUBS ? "Aucun sous-titre disponible" : "Aucune piste"} : r;
            float[] size = panelSize(m, shown);
            sizes[m] = (Math.round(size[0]) << 16) | Math.round(size[1]);
        }
        final float kk = k;
        Thread t = new Thread(() -> {
            for (int key : sizes) {
                if (key == 0 || panels.containsKey(key)) continue;
                int pw = key >>> 16, ph = key & 0xFFFF;
                float r = 30 * kk;
                int pad = Math.round(30 * kk);
                panels.put(key, new Bitmap[]{Liquid.face(Liquid.PANEL, pw, ph, r, kk),
                        Liquid.shadow(pw, ph, r, pad, 26 * kk, 0.45f, 0.5f, -8 * kk)});
            }
        }, "popover glass");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Sends the player to where the film was left; the bar shows that point straight away. */
    void resumeAt(long ms) {
        seek(ms, SystemClock.uptimeMillis());
    }

    void buffering(boolean on) {
        if (buffering == on) return;
        buffering = on;
        kick();
    }

    void seekDone() {
        pendingSeek = -1;
        posAt = 0;
        if (hudA > 0) bar.invalidate();
    }

    /** Picture subtitles (PGS, DVB), each with its place on the screen. */
    void subtitleImages(Bitmap[] bitmaps, RectF[] places) {
        if (subImages.length == 0 && bitmaps.length == 0) return;
        subImages = bitmaps;
        subPlaces = places;
        images.setVisibility(bitmaps.length > 0 ? VISIBLE : INVISIBLE);
        images.invalidate();
    }

    void subtitle(String text) {
        subLines = text == null || text.trim().isEmpty() ? new String[0] : text.trim().split("\n");
        subs.setVisibility(subLines.length > 0 ? VISIBLE : INVISIBLE);
        subs.invalidate();
    }

    void fail(String why) {
        failed = true;
        error = why == null ? "" : why;
        errorDetail = "";
        buffering = false;
        hudTo = 0;
        reveal(errorView);
        errorView.invalidate();
        kick();
    }

    void stopTimers() {
        removeCallbacks(hideRun);
        removeCallbacks(commitRun);
        removeCallbacks(digitsRun);
        removeCallbacks(noteRun);
        removeCallbacks(liveBarTick);
        Epg.get(getContext()).removeListener(guideChanged);
        Choreographer.getInstance().removeFrameCallback(this);
        framePosted = false;
    }

    // MARK: - Keys

    /** Back: closes what is open first; false when there is nothing left to close (the player leaves). */
    boolean back() {
        if (listOpen && !listClosing) {
            closeList();
            return true;
        }
        if (digits.length() > 0) {
            digits.setLength(0);
            removeCallbacks(digitsRun);
            digitsView.setVisibility(INVISIBLE);
            return true;
        }
        if (menu >= 0 && !menuClosing) {
            closeMenu();
            return true;
        }
        if (scrubbing) {
            scrubbing = false;
            removeCallbacks(commitRun);
            show();
            return true;
        }
        if (hudTo > 0 && ready && !failed) {
            hide();
            return true;
        }
        return false;
    }

    boolean key(int code, KeyEvent e) {
        if (failed && channel == null) return false;   // a channel that fails can still be zapped away from
        int repeat = e.getRepeatCount();
        if (menu >= 0 && !menuClosing) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (row > 0) row--;
                    keepRowVisible();
                    kick();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (row < rows.length - 1) row++;
                    keepRowVisible();
                    kick();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (repeat == 0 && checked == ACTIONS) {
                        int pick = row;
                        closeMenu();
                        runAction(pick);
                    } else {
                        if (repeat == 0 && checked != NOTHING) {
                            host.choose(menu, row);
                            checked = row;
                            menuView.invalidate();
                        }
                        closeMenu();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    closeMenu();
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    // zapping is never swallowed by the menu: it closes and the channel changes
                    closeMenu();
                    if (channel != null && repeat == 0) {
                        host.zap(code == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                    }
                    return true;
                case KeyEvent.KEYCODE_GUIDE:
                    closeMenu();
                    if (repeat == 0) openList();
                    return true;
                default:
                    return false;
            }
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && channel != null) {
            if (repeat == 0) digit(code - KeyEvent.KEYCODE_0);
            return true;
        }
        if (listOpen && !listClosing && listKey(code, e)) return true;
        if (channel != null) {
            switch (code) {
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    host.zap(1);
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    host.zap(-1);
                    return true;
                case KeyEvent.KEYCODE_LAST_CHANNEL:
                    if (repeat == 0) host.zapPrevious();
                    return true;
                case KeyEvent.KEYCODE_GUIDE:
                    if (repeat == 0) openList();
                    return true;
                case KeyEvent.KEYCODE_INFO:
                    show();
                    return true;
                default:
                    break;
            }
        }
        // Up / Down never change channel, on the live or in a replay: they open the player's own menu
        if (channel != null && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (repeat == 0) openOptions();
            return true;
        }
        if (liveMode() && liveKey(code, repeat)) return true;
        if (failed) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (repeat > 0 || !ready) return true;
                if (visible() && focus == ON_BUTTONS) activateButton();
                else if (scrubbing) commitScrub();
                else togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (repeat == 0 && ready) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (repeat == 0 && ready && !host.isPlaying()) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (repeat == 0 && ready && host.isPlaying()) togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                boolean forward = code == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (visible() && focus == ON_BUTTONS) {
                    button = Math.max(0, Math.min(buttons.length - 1, button + (forward ? 1 : -1)));
                    show();
                    return true;
                }
                if (live || !ready || duration <= 0) {
                    show();
                    return true;
                }
                if (!visible() && repeat == 0 && !scrubbing) skip(forward ? 10_000 : -10_000);
                else scrub(forward, repeat);
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (!live && ready && duration > 0) skip(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 30_000 : -30_000);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (visible() && focus == ON_BAR && !scrubbing) focus = ON_BUTTONS;
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focus == ON_BUTTONS) {
                    focus = ON_BAR;
                    show();
                } else if (visible() && ready && !scrubbing) {
                    hide();
                } else {
                    show();
                }
                return true;
            default:
                return false;
        }
    }

    /**
     * A channel, live, as a box does it: OK opens the channel list, right reaches the buttons, left and right only
     * show the banner otherwise. Up / Down are the options menu, handled before this (see `key`).
     */
    private boolean liveKey(int code, int repeat) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (visible() && focus == ON_BUTTONS) button = Math.min(buttons.length - 1, button + 1);
                else if (visible() && ready) {
                    focus = ON_BUTTONS;
                    button = 0;
                }
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (visible() && focus == ON_BUTTONS) {
                    if (button == 0) focus = ON_BAR;
                    else button--;
                }
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (repeat > 0) return true;
                if (visible() && focus == ON_BUTTONS && ready) activateButton();
                else openList();
                return true;
            default:
                return false;
        }
    }

    private boolean visible() {
        return hudTo > 0;
    }

    private void show() {
        hudTo = 1;
        scheduleHide();
        kick();
    }

    private void hide() {
        hudTo = 0;
        if (!note.isEmpty()) {
            note = "";
            controls.invalidate();
        }
        removeCallbacks(hideRun);
        kick();
    }

    private void scheduleHide() {
        removeCallbacks(hideRun);
        if (ready && menu < 0 && !scrubbing && playing) postDelayed(hideRun, HIDE_MS);
    }

    private void autoHide() {
        if (menu >= 0 || scrubbing || !playing || !ready) return;
        hide();
    }

    private void togglePause() {
        playing = host.togglePause();
        posAt = 0;
        flash(playing ? "play" : "pause");
        show();
        if (!playing) removeCallbacks(hideRun);   // paused: the controls stay
    }

    private void flash(String symbol) {
        if (!symbol.equals(glyph)) {
            glyph = symbol;
            glyphView.invalidate();
        }
        glyphAt = SystemClock.uptimeMillis();
        kick();
    }

    private void skip(long delta) {
        long now = SystemClock.uptimeMillis();
        seek(clampPos(position(now) + delta), now);
        flash(delta > 0 ? "forward_10" : "replay_10");
        show();
    }

    private void scrub(boolean forward, int repeat) {
        long now = SystemClock.uptimeMillis();
        if (!scrubbing) {
            scrubbing = true;
            scrubTo = position(now);
        }
        // a click moves 10 s; holding the key accelerates up to 90 s a step (about 15 min a second)
        long step = repeat == 0 ? 10_000 : Math.min(90_000, 4_000 + repeat * 1_500L);
        scrubTo = clampPos(scrubTo + (forward ? step : -step));
        focus = ON_BAR;
        removeCallbacks(commitRun);
        postDelayed(commitRun, COMMIT_MS);
        bar.invalidate();
        show();
    }

    private void commitScrub() {
        if (!scrubbing) return;
        scrubbing = false;
        removeCallbacks(commitRun);
        seek(scrubTo, SystemClock.uptimeMillis());
        bar.invalidate();
        scheduleHide();
        kick();
    }

    private void seek(long to, long now) {
        host.seek(to);
        pendingSeek = to;              // the bar stays where it was sent until the player gets there
        pendingUntil = now + 4000;
        bar.invalidate();
    }

    private long clampPos(long p) {
        return Math.max(0, Math.min(Math.max(0, duration - 2000), p));
    }

    private long position(long now) {
        if (pendingSeek >= 0) {
            if (now < pendingUntil) return pendingSeek;
            pendingSeek = -1;
        }
        if (posAt == 0 || now - posAt > 500) {
            posBase = host.currentPosition();
            posAt = now;
        }
        long p = playing && !buffering ? posBase + (now - posAt) : posBase;
        return duration > 0 ? Math.min(duration, p) : p;
    }

    // MARK: - Popover

    private void openMenu(int which) {
        openMenu(which, false);
    }

    /** `steady`: the panel already on screen only changes content (a track list opened from the options menu). */
    private void openMenu(int which, boolean steady) {
        String[] r = host.menuRows(which);
        if (r == null) return;
        menu = which;
        menuClosing = false;
        acts = new int[0];
        values = new String[0];
        int sel = host.menuChecked(which);
        if (r.length == 0) {
            rows = new String[]{which == MENU_SUBS ? "Aucun sous-titre disponible" : "Aucune piste"};
            checked = NOTHING;   // nothing to choose
            row = 0;
        } else {
            rows = r;
            checked = sel;
            row = Math.max(0, sel);
        }
        rowLens = row;
        first = 0;
        layoutMenu();
        keepRowVisible();
        present(steady);
    }

    /**
     * Up / Down on a channel: the menu a box puts there — the programme from its start (or back to the live), the
     * channel list, and the three tracks with the one playing written on their right. Up / Down walk the rows,
     * OK runs the row, Back closes. Zapping stays on P+ / P− and on the digits.
     */
    private void openOptions() {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>(), vals = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> codes = new java.util.ArrayList<>();
        if (channel != null && replay == null && channel.replay != null) {
            labels.add("Depuis le début"); vals.add(""); codes.add(A_START);
        }
        if (replay != null) {
            labels.add("Revenir au direct"); vals.add(""); codes.add(A_LIVE);
        }
        labels.add("Liste des chaînes"); vals.add(""); codes.add(A_LIST);

        String[] subs = host.menuRows(MENU_SUBS);
        int si = host.menuChecked(MENU_SUBS);
        labels.add("Sous-titres");
        vals.add(subs == null ? "—" : subs.length == 0 ? "Aucun" : short_(subs[Math.max(0, Math.min(si, subs.length - 1))]));
        codes.add(A_SUBS);

        String[] audio = host.menuRows(MENU_AUDIO);
        int ai = host.menuChecked(MENU_AUDIO);
        labels.add("Audio");
        vals.add(audio == null || ai < 0 || ai >= audio.length ? "—" : short_(audio[ai]));
        codes.add(A_AUDIO);

        String[] fmt = host.menuRows(MENU_FORMAT);
        int fi = host.menuChecked(MENU_FORMAT);
        labels.add("Format de l'image");
        vals.add(fmt == null || fi < 0 || fi >= fmt.length ? "—" : fmt[fi]);
        codes.add(A_FORMAT);

        rows = labels.toArray(new String[0]);
        values = vals.toArray(new String[0]);
        acts = new int[codes.size()];
        for (int i = 0; i < acts.length; i++) acts[i] = codes.get(i);
        menu = MENU_OPTIONS;
        checked = ACTIONS;
        menuClosing = false;
        row = 0;
        rowLens = 0;
        first = 0;
        layoutMenu();
        present(false);
    }

    /** A track's name only: "Français · VFF · Dolby Digital Plus 5.1" → "Français". */
    private static String short_(String s) {
        int bar = s.indexOf("  ·  ");
        return bar > 0 ? s.substring(0, bar) : s;
    }

    /** Puts the popover on screen; `steady` keeps it at full strength while it only changes content. */
    private void present(boolean steady) {
        removeCallbacks(hideRun);
        hudTo = 1;
        if (!steady) {
            menuA = 0;
            menuView.setAlpha(0);
        }
        reveal(menuView);
        menuView.invalidate();
        kick();
    }

    private void runAction(int i) {
        if (i < 0 || i >= acts.length) return;
        switch (acts[i]) {
            case A_START:
                if (host.startOver()) flash("restart");
                break;
            case A_LIVE:
                host.backToLive();
                break;
            case A_LIST:
                openList();
                break;
            case A_SUBS:
                openTrack(MENU_SUBS);
                break;
            case A_AUDIO:
                openTrack(MENU_AUDIO);
                break;
            case A_FORMAT:
                openTrack(MENU_FORMAT);
                break;
            default:
                break;
        }
    }

    /** A track list, under the icon it belongs to (the icon lights up while its list is open). */
    private void openTrack(int which) {
        button = indexOfMenu(which);
        focus = ON_BUTTONS;
        openMenu(which, true);
    }

    private void closeMenu() {
        menuClosing = true;
        scheduleHide();
        kick();
    }

    /** Width and height of a popover holding these rows (both rounded to whole pixels). */
    private float[] panelSize(int which, String[] list) {
        float textW = rowP.measureText(MENU_TITLES[which]);
        for (String s : list) textW = Math.max(textW, rowP.measureText(s));
        float valW = 0;
        for (String v : values) valW = Math.max(valW, valueP.measureText(v));
        float pw = Math.round(Math.max(420 * k, Math.min(760 * k, textW + valW + (valW > 0 ? 132 * k : 112 * k))));
        float bottom = buttonCY - buttonD / 2 - 22 * k;
        int fit = (int) Math.max(1, Math.floor((bottom - 140 * k - headH - 28 * k) / rowH));
        int n = Math.min(list.length, fit);
        return new float[]{pw, Math.round(headH + n * rowH + 14 * k), n};
    }

    private void layoutMenu() {
        float[] size = panelSize(menu, rows);
        float pw = size[0], ph = size[1];
        visibleRows = (int) size[2];
        if (checked == ACTIONS) {
            // the player's menu stands at the left margin, above the picture's title, like a box's own menu
            float left = Math.round(margin), top = Math.round(60 * k);
            panel.set(left, top, left + pw, top + ph);
        } else {
            // it ends just past its own button (the one that opened it), kept inside the margin
            float right = Math.round(Math.min(w - margin, buttonCX(indexOfMenu(menu)) + buttonD / 2 + 22 * k));
            float bottom = Math.round(buttonCY - buttonD / 2 - 22 * k);
            panel.set(right - pw, bottom - ph, right, bottom);
        }
        int iw = (int) pw, ih = (int) ph;
        panelPad = Math.round(30 * k);
        Bitmap[] baked = panels.get((iw << 16) | ih);
        if (baked != null) {
            panelFace = baked[0];
            panelShadow = baked[1];
        } else if (panelFace == null || panelFace.getWidth() != iw || panelFace.getHeight() != ih) {
            float r = 30 * k;   // not baked yet (opened in the first instants): bake it here, once
            panelFace = Liquid.face(Liquid.PANEL, iw, ih, r, k);
            panelShadow = Liquid.shadow(iw, ih, r, panelPad, 26 * k, 0.45f, 0.5f, -8 * k);
            panels.put((iw << 16) | ih, new Bitmap[]{panelFace, panelShadow});
        }
        int l = Math.round(panel.left) - panelPad, t = Math.round(panel.top) - panelPad;
        int rr = Math.round(panel.right) + panelPad, b = Math.round(panel.bottom + panelPad + 14 * k);
        place(menuView, l, t, rr, b);
        if (checked == ACTIONS) {
            menuView.setPivotX(panelPad);                 // it unfolds from its own corner
            menuView.setPivotY(panelPad + headH / 2);
        } else {
            menuView.setPivotX(buttonCX(indexOfMenu(menu)) - l);   // it grows out of its button
            menuView.setPivotY(b - t);
        }
    }

    private void keepRowVisible() {
        if (row < first) first = row;
        if (row >= first + visibleRows) first = row - visibleRows + 1;
        first = Math.max(0, Math.min(first, Math.max(0, rows.length - visibleRows)));
    }

    // MARK: - Motion

    private void kick() {
        if (framePosted) return;
        framePosted = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        framePosted = false;
        if (kitButton == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean more = false;

        // the controls fade and rise into place: properties only
        if (hudA != hudTo || (hudA > 0) != (controls.getVisibility() == VISIBLE)) {
            hudA = approach(hudA, hudTo, dt * (hudTo > hudA ? 11f : 7f));
            more |= hudA != hudTo;
            float lift = (1 - hudA) * 22 * k;
            int vis = hudA > 0 ? VISIBLE : INVISIBLE;
            for (Layer l : fading) {
                l.setAlpha(hudA);
                l.setTranslationY(lift);
                if (l.getVisibility() != vis) l.setVisibility(vis);
            }
            subs.setTranslationY(-hudA * rise);
            if (hudA == 0 && focus != ON_BAR) focus = ON_BAR;
        }

        // focus and scrub: the parts that change are redrawn
        boolean ctl = false, strip = false;
        float barTo = focus == ON_BAR && menu < 0 ? 1 : 0;
        if (barOn != barTo) {
            barOn = approach(barOn, barTo, dt * 14f);
            strip = true;
        }
        for (int i = 0; i < buttonOn.length; i++) {
            float to = focus == ON_BUTTONS && button == i ? 1 : 0;
            if (buttonOn[i] != to) {
                buttonOn[i] = approach(buttonOn[i], to, dt * 15f);
                ctl = true;
            }
        }
        float sTo = scrubbing ? 1 : 0;
        if (scrubA != sTo) {
            scrubA = approach(scrubA, sTo, dt * 12f);
            ctl = true;
        }
        if (ctl) controls.invalidate();
        if (strip) bar.invalidate();
        more |= ctl || strip;

        // the popover grows out of its button (properties); the lit row glides (a redraw of the popover only)
        if (menu >= 0) {
            float to = menuClosing ? 0 : 1;
            if (menuA != to) {
                menuA = approach(menuA, to, dt * (menuClosing ? 14f : 12f));
                float s = 0.94f + 0.06f * menuA;
                menuView.setAlpha(menuA);
                menuView.setScaleX(s);
                menuView.setScaleY(s);
                more |= menuA != to;
            }
            if (rowLens != row) {
                rowLens = approach(rowLens, row, dt * 18f);
                menuView.invalidate();
                more = true;
            }
            if (menuClosing && menuA == 0) {
                menu = -1;
                menuView.setVisibility(INVISIBLE);
                bar.invalidate();
            }
        }

        // the channel list slides in from the left and the lit row glides
        if (listOpen) {
            float to = listClosing ? 0 : 1;
            if (listA != to) {
                listA = approach(listA, to, dt * (listClosing ? 14f : 11f));
                listView.setAlpha(listA);
                listView.setTranslationX(-(1 - listA) * 36 * k);
                more |= listA != to;
            }
            if (listLens != listRow) {
                listLens = approach(listLens, listRow, dt * 20f);
                if (Math.abs(listLens - listRow) < 0.01f) listLens = listRow;
                listView.invalidate();
                more = true;
            }
            if (listClosing && listA == 0) {
                listOpen = false;
                listClosing = false;
                listView.setVisibility(INVISIBLE);
            }
        }

        // the spinner turns (rotation of a drawn arc)
        boolean spinning = !failed && (!ready || buffering);
        if (spinning) {
            if (spinner.getVisibility() != VISIBLE) reveal(spinner);
            spinner.setRotation((now % 1100) / 1100f * 360f);
            more = true;
        } else if (spinner.getVisibility() == VISIBLE) {
            spinner.setVisibility(INVISIBLE);
        }

        // the feedback glyph pops in, holds and fades (alpha and scale)
        if (glyphAt >= 0) {
            long t = now - glyphAt;
            if (t > 900) {
                glyphAt = -1;
                glyphView.setVisibility(INVISIBLE);
            } else {
                float in = t < 160 ? ease(t / 160f) : 1f;
                float out = t > 620 ? 1f - (t - 620) / 280f : 1f;
                float s = 0.82f + 0.18f * in;
                glyphView.setAlpha(Math.max(0, in * out));
                glyphView.setScaleX(s);
                glyphView.setScaleY(s);
                if (glyphView.getVisibility() != VISIBLE) reveal(glyphView);
                more = true;
            }
        }

        if (more) {
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // MARK: - Layout

    @Override protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs));
    }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw;
        h = nh;
        k = Math.min(w / 1920f, h / 1080f);
        margin = 96 * k;
        barCY = h - 128 * k;
        timesBase = barCY + 44 * k;
        titleBase = barCY - 50 * k;
        metaBase = titleBase - 58 * k;
        buttonD = Math.round(66 * k);
        buttonGap = 18 * k;
        rowH = 58 * k;
        headH = 66 * k;
        rise = (h - 84 * k) - (metaBase - 64 * k);

        titleP.setTypeface(Ui.displaySemibold);
        titleP.setTextSize(46 * k);
        titleP.setColor(0xFFFFFFFF);
        buttonCY = titleBase - Ui.cap(titleP) / 2;
        metaP = Ui.text(Ui.medium, 23 * k, 0xB3FFFFFF);
        timeP = Ui.numbers(Ui.semibold, 22 * k, 0xB3FFFFFF);
        bubbleP = Ui.numbers(Ui.semibold, 24 * k, 0xFFFFFFFF);
        bubbleP.setTextAlign(Paint.Align.CENTER);
        subP = Ui.text(Ui.semibold, 42 * k, 0xFFFFFFFF);
        subP.setTextAlign(Paint.Align.CENTER);
        subP.setShadowLayer(6 * k, 0, 2 * k, 0xE6000000);
        headP = Ui.text(Ui.semibold, 21 * k, 0xCCFFFFFF);
        headP.setLetterSpacing(0.02f);
        rowP = Ui.text(Ui.medium, 26 * k, 0xEBFFFFFF);
        valueP = Ui.text(Ui.medium, 22 * k, 0x99FFFFFF);
        valueP.setTextAlign(Paint.Align.RIGHT);
        badgeP = Ui.text(Ui.semibold, 19 * k, 0xFFFFFFFF);
        badgeP.setLetterSpacing(0.08f);
        errP = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
        errP.setTextAlign(Paint.Align.CENTER);
        errSmallP = Ui.text(Ui.medium, 22 * k, 0x99FFFFFF);
        errSmallP.setTextAlign(Paint.Align.CENTER);
        spin.setStrokeWidth(4.5f * k);
        spin.setColor(0xE6FFFFFF);

        float top = h - 470 * k;
        scrim.setShader(new LinearGradient(0, top, 0, h, new int[]{0x00000000, 0x4D000000, 0xBF000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

        kitButton = Liquid.capsule(Liquid.CLEAR, (int) buttonD, k, false);
        kitButtonOn = Liquid.capsule(Liquid.FOCUS, (int) buttonD, k, true);
        kitRow = Liquid.capsule(Liquid.LIT, Math.round(50 * k), k, false);
        kitBubble = Liquid.capsule(Liquid.PANEL, Math.round(46 * k), k, true);
        kitBadge = Liquid.capsule(Liquid.PANEL, Math.round(42 * k), k, false);
        kitGlyph = Liquid.capsule(Liquid.PANEL, Math.round(128 * k), k, false);

        // live TV: the logo's glass card, the channel list's panel and its lines, the digits
        kitDigits = Liquid.capsule(Liquid.PANEL, Math.round(104 * k), k, false);
        kitListRow = Liquid.capsule(Liquid.LIT, Math.round(86 * k - 12 * k), k, false);
        float cardH = Math.round(logoCardH()), cardW = Math.round(cardH * 16 / 9f);
        logoFace = Liquid.face(Liquid.CLEAR, (int) cardW, (int) cardH, 18 * k, k);
        listNumP = Ui.numbers(Ui.semibold, 24 * k, 0x99FFFFFF);
        listNumP.setTextAlign(Paint.Align.RIGHT);
        listNameP = Ui.text(Ui.medium, 25 * k, 0xEBFFFFFF);
        listShowP = Ui.text(Ui.regular, 20 * k, 0x99FFFFFF);
        digitsP = Ui.numbers(Ui.semibold, 58 * k, 0xFFFFFFFF);
        digitsP.setTextAlign(Paint.Align.CENTER);
        digitsNameP = Ui.text(Ui.medium, 22 * k, 0xCCFFFFFF);
        digitsNameP.setTextAlign(Paint.Align.CENTER);
        listRowH = 86 * k;
        listHead = 78 * k;
        float lw = Math.round(720 * k), lh = Math.round(h - 2 * 60 * k);
        listPanel.set(Math.round(64 * k), Math.round(60 * k), Math.round(64 * k) + lw, Math.round(60 * k) + lh);
        listVisible = (int) Math.max(3, Math.floor((lh - listHead - 12 * k) / listRowH));
        listPad = Math.round(30 * k);
        final int pw = (int) lw, ph = (int) lh;
        final float kk = k;
        Thread t = new Thread(() -> {   // ~3 MB of glass: baked off the UI thread, the list opens without a frame lost
            Bitmap face = Liquid.face(Liquid.PANEL, pw, ph, 34 * kk, kk);
            Bitmap shadow = Liquid.shadow(pw, ph, 34 * kk, Math.round(30 * kk), 26 * kk, 0.45f, 0.5f, -8 * kk);
            post(() -> {
                listFace = face;
                listShadow = shadow;
                if (listOpen) listView.invalidate();
            });
        }, "list glass");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        ellipsize();
        kick();
    }

    /** The logo card spans the channel's two lines (number and name, then the programme). */
    private float logoCardH() {
        return (titleBase - metaBase) + Ui.cap(metaP) + 26 * k;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int W = r - l, H = b - t;
        place(controls, 0, Math.round(H - 470 * k), W, H);
        // the strip that changes every second: the bar, the times, the scrub bubble above the bar
        place(bar, 0, Math.round(barCY - 110 * k), W, Math.round(timesBase + 16 * k));
        place(subs, 0, Math.round(H - 380 * k), W, H);
        place(images, 0, 0, W, H);   // drawn only when a picture subtitle changes
        float cx = W / 2f, cy = H / 2f;
        int g = Math.round(kitGlyph.h / 2f + 6 * k);
        place(glyphView, Math.round(cx - g), Math.round(cy - g), Math.round(cx + g), Math.round(cy + g));
        int s = Math.round(40 * k);
        place(spinner, Math.round(cx - s), Math.round(cy - s), Math.round(cx + s), Math.round(cy + s));
        place(errorView, Math.round(cx - 470 * k), Math.round(cy - 130 * k), Math.round(cx + 470 * k),
                Math.round(cy + 130 * k));
        place(listView, Math.round(listPanel.left) - listPad, Math.round(listPanel.top) - listPad,
                Math.round(listPanel.right) + listPad, Math.round(listPanel.bottom) + listPad + Math.round(14 * k));
        place(digitsView, Math.round(W - margin - 300 * k), Math.round(56 * k), Math.round(W - margin), Math.round(220 * k));
        if (menu >= 0) layoutMenu();
    }

    /**
     * Shows a layer that was hidden. The picture is a SurfaceView, which makes the window transparent wherever no
     * view was visible at the last layout: a layer that only turns VISIBLE is drawn but never composited over the
     * video (the digits stayed black). A layout pass is what tells the system the window is opaque there again.
     */
    private void reveal(Layer l) {
        if (l.getVisibility() == VISIBLE) return;
        l.setVisibility(VISIBLE);
        requestLayout();
    }

    private static void place(View v, int l, int t, int r, int b) {
        v.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY));
        v.layout(l, t, r, b);
    }

    private void ellipsize() {
        if (w <= 0) return;
        int nb = buttons.length;
        float max = w - 2 * margin - nb * buttonD - (nb - 1) * buttonGap - 48 * k;
        if (liveMode() && logoFace != null) max -= logoFace.getWidth() + 28 * k;
        titleShown = TextUtils.ellipsize(title, titleP, max, TextUtils.TruncateAt.END).toString();
    }

    // MARK: - Drawing (screen coordinates; each layer draws its own part)

    private void paint(int part, Canvas c) {
        if (kitButton == null) return;
        switch (part) {
            case CONTROLS: paintControls(c); break;
            case BAR: paintBar(c); break;
            case SUBS: paintSubtitles(c); break;
            case IMAGES:
                for (int i = 0; i < subImages.length; i++) c.drawBitmap(subImages[i], null, subPlaces[i], imagePaint);
                break;
            case MENU: if (menu >= 0) paintMenu(c); break;
            case GLYPH: paintGlyph(c); break;
            case SPINNER: Ui.spinner(c, w / 2f, h / 2f, 30 * k, 0, spin); break;
            case ERROR: if (failed) paintError(c); break;
            case LIST: if (listOpen) paintList(c); break;
            case DIGITS: paintDigits(c); break;
            default: break;
        }
    }

    private void paintControls(Canvas c) {
        c.drawRect(0, h - 470 * k, w, h, scrim);
        // scrubbing: the time and the bar are all that matter, the title and buttons step aside
        float a = 1 - scrubA;
        if (a <= 0) return;
        if (liveMode()) {
            paintLiveControls(c, a);
            return;
        }
        String m = note.isEmpty() ? meta : meta + "  ·  " + note;
        if (!m.isEmpty()) {
            metaP.setColor(Ui.alpha(0xB3FFFFFF, a));
            c.drawText(m, margin, metaBase, metaP);
        }
        titleP.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawText(titleShown, margin - Ui.leftBearing(titleP, titleShown), titleBase, titleP);
        paintButtons(c, a);
    }

    private void paintButtons(Canvas c, float a) {
        for (int i = 0; i < buttons.length; i++) {
            float on = buttonOn[i];
            float cx = buttonCX(i), r = buttonD / 2 * (1 + 0.1f * on);
            box.set(cx - r, buttonCY - r, cx + r, buttonCY + r);
            if (on < 1) Liquid.draw(c, kitButton, box, glass, a * (1 - on), false, 0);
            if (on > 0) Liquid.draw(c, kitButtonOn, box, glass, a * on, true, 7 * k);
            Symbols.draw(getContext(), c, iconOf(i), cx, buttonCY, 25 * k * (1 + 0.08f * on),
                    Ui.alpha(0xFFFFFFFF, a * (0.82f + 0.18f * on)));
        }
        // the focused button says what it does, under it (as on the film's page)
        if (focus == ON_BUTTONS && hudTo > 0 && button < buttons.length && buttons[button] == START_OVER) {
            String n = "Depuis le début";
            metaP.setColor(Ui.alpha(0xE6FFFFFF, a));
            float nw = metaP.measureText(n);
            c.drawText(n, Math.min(w - margin - nw, buttonCX(button) - nw / 2), buttonCY + buttonD / 2 + 36 * k, metaP);
        }
    }

    /** A channel: its logo on a glass card, its number and name with the programme's hours, then the programme. */
    private void paintLiveControls(Canvas c, float a) {
        readShow(System.currentTimeMillis());
        float x = margin;
        if (logoFace != null) {
            float ch = logoFace.getHeight(), cw = logoFace.getWidth();
            float top = titleBase + 12 * k - ch;
            glass.setAlpha(Math.round(255 * a));
            c.drawBitmap(logoFace, x, top, glass);
            glass.setAlpha(255);
            if (logoBmp != null) {
                box.set(x + 18 * k, top + 14 * k, x + cw - 18 * k, top + ch - 14 * k);
                float bw = logoBmp.getWidth(), bh = logoBmp.getHeight();
                float s = Math.min(box.width() / bw, box.height() / bh);
                float dw = bw * s, dh = bh * s;
                track.set(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2);
                imagePaint.setAlpha(Math.round(255 * a));
                c.drawBitmap(logoBmp, null, track, imagePaint);
                imagePaint.setAlpha(255);
            } else {
                titleP.setTextSize(30 * k);
                titleP.setColor(Ui.alpha(0xE6FFFFFF, a));
                titleP.setTextAlign(Paint.Align.CENTER);
                c.drawText(String.valueOf(channel.number), x + cw / 2, top + ch / 2 + Ui.halfCap(titleP), titleP);
                titleP.setTextAlign(Paint.Align.LEFT);
                titleP.setTextSize(46 * k);
            }
            x += cw + 28 * k;
        }
        metaP.setColor(Ui.alpha(0xCCFFFFFF, a));
        c.drawText(liveMeta, x, metaBase, metaP);
        titleP.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawText(titleShown, x - Ui.leftBearing(titleP, titleShown), titleBase, titleP);
        paintButtons(c, a);
    }

    private float buttonCX(int i) {
        return w - margin - buttonD / 2 - (buttons.length - 1 - i) * (buttonD + buttonGap);
    }

    private void paintBar(Canvas c) {
        if (liveMode()) {   // a channel of the lineup: its programme shows while the stream starts
            paintLive(c);
            return;
        }
        if (!ready) return;
        if (live) {
            paintLive(c);
            return;
        }
        if (duration <= 0) return;
        long now = SystemClock.uptimeMillis();
        long shown = scrubbing ? scrubTo : position(now);
        float bh = (8 + 4 * barOn) * k;
        track.set(margin, barCY - bh / 2, w - margin, barCY + bh / 2);
        float r = bh / 2;
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.30f + 0.12f * barOn));
        c.drawRoundRect(track, r, r, fill);
        float frac = Math.max(0, Math.min(1, (float) shown / duration));
        float x = track.left + frac * track.width();
        long buffered = host.bufferedPosition();
        if (buffered > shown && !scrubbing) {
            float bx = track.left + Math.min(1f, (float) buffered / duration) * track.width();
            if (bx > x) {
                box.set(track.left, track.top, bx, track.bottom);
                fill.setColor(0x29FFFFFF);
                c.drawRoundRect(box, r, r, fill);
            }
        }
        box.set(track.left, track.top, Math.max(track.left + bh, x), track.bottom);
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.78f + 0.22f * barOn));
        c.drawRoundRect(box, r, r, fill);

        long es = shown / 1000, rs = Math.max(0, (duration - shown) / 1000);
        if (es != elapsedSec) {
            elapsedSec = es;
            elapsedText = clock(es);
        }
        if (rs != remainSec) {
            remainSec = rs;
            remainText = "−" + clock(rs);
        }
        timeP.setTextAlign(Paint.Align.LEFT);
        c.drawText(elapsedText, margin, timesBase, timeP);
        timeP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(remainText, w - margin, timesBase, timeP);

        if (scrubbing) {
            if (es != bubbleSec) {
                bubbleSec = es;
                bubbleText = clock(es);
            }
            float bw = bubbleP.measureText(bubbleText) + 36 * k, bhh = kitBubble.h;
            float cx = Math.max(margin + bw / 2, Math.min(w - margin - bw / 2, x));
            float bottom = track.top - 16 * k;
            box.set(cx - bw / 2, bottom - bhh, cx + bw / 2, bottom);
            Liquid.draw(c, kitBubble, box, glass, 1, true, 6 * k);
            c.drawText(bubbleText, cx, box.centerY() + Ui.halfCap(bubbleP), bubbleP);
            // a hairline from the bubble to the bar marks the exact point
            fill.setColor(0xE6FFFFFF);
            box.set(x - 1.5f * k, bottom, x + 1.5f * k, track.bottom + 4 * k);
            c.drawRoundRect(box, 1.5f * k, 1.5f * k, fill);
        }

        // the next second, and nothing in between (the layer is a thin strip)
        if (playing && !scrubbing && hudTo > 0) bar.postInvalidateDelayed(1000 - (shown % 1000) + 8);
    }

    /**
     * Live: the programme's progress from its start to its end, "● EN DIRECT · il reste 35 min" under its left end
     * and what comes next under its right end. Without a guide, the badge alone.
     */
    private void paintLive(Canvas c) {
        long now = System.currentTimeMillis();
        if (readShow(now)) controls.invalidate();
        Epg.Show s = channel != null ? liveShow : null;
        if (s == null) {
            String label = "EN DIRECT";
            float bh = kitBadge.h, bw = badgeP.measureText(label) + 62 * k;
            box.set(margin, barCY - bh / 2, margin + bw, barCY + bh / 2);
            Liquid.draw(c, kitBadge, box, glass, 1, false, 0);
            fill.setColor(RED);
            c.drawCircle(box.left + 24 * k, barCY, 6 * k, fill);
            c.drawText(label, box.left + 40 * k, barCY + Ui.halfCap(badgeP), badgeP);
            return;
        }
        float bh = 8 * k;
        track.set(margin, barCY - bh / 2, w - margin, barCY + bh / 2);
        float r = bh / 2;
        fill.setColor(0x4DFFFFFF);
        c.drawRoundRect(track, r, r, fill);
        float x = track.left + s.progress(now) * track.width();
        box.set(track.left, track.top, Math.max(track.left + bh, x), track.bottom);
        fill.setColor(0xF2FFFFFF);
        c.drawRoundRect(box, r, r, fill);

        fill.setColor(RED);
        float dotY = timesBase - Ui.cap(timeP) / 2;
        c.drawCircle(margin + 6 * k, dotY, 6 * k, fill);
        timeP.setTextAlign(Paint.Align.LEFT);
        String left = "EN DIRECT" + (liveLeft.isEmpty() ? "" : "  ·  " + liveLeft);
        c.drawText(left, margin + 22 * k, timesBase, timeP);
        if (!liveNextText.isEmpty()) {
            float room = w - 2 * margin - timeP.measureText(left) - 80 * k;
            String next = TextUtils.ellipsize(liveNextText, new TextPaint(timeP), room, TextUtils.TruncateAt.END).toString();
            timeP.setTextAlign(Paint.Align.RIGHT);
            c.drawText(next, w - margin, timesBase, timeP);
        }
        // the programme moves on: twice a minute is enough for its bar
        removeCallbacks(liveBarTick);
        if (hudTo > 0) postDelayed(liveBarTick, 30_000);
    }

    /**
     * Every channel, as a box lists them: number, logo, name and what it shows with its progress. The lit row is the
     * one OK goes to; the channel on screen has a red dot.
     */
    private void paintList(Canvas c) {
        List<Channels.Channel> all = Channels.get(getContext()).all;
        if (listShadow != null) {
            box.set(listPanel.left - listPad, listPanel.top - listPad + 14 * k, listPanel.right + listPad,
                    listPanel.bottom + listPad + 14 * k);
            c.drawBitmap(listShadow, null, box, glass);
        }
        if (listFace != null) c.drawBitmap(listFace, null, listPanel, glass);
        else {
            fill.setColor(0xE05A6168);
            c.drawRoundRect(listPanel, 34 * k, 34 * k, fill);
        }
        headP.setTextAlign(Paint.Align.LEFT);
        c.drawText("Chaînes", listPanel.left + 34 * k, listPanel.top + 48 * k, headP);
        headP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Epg.clock(System.currentTimeMillis()), listPanel.right - 34 * k, listPanel.top + 48 * k, headP);
        headP.setTextAlign(Paint.Align.LEFT);

        c.save();
        c.clipRect(listPanel.left, listPanel.top + listHead - 6 * k, listPanel.right, listPanel.bottom - 8 * k);
        float top = listPanel.top + listHead;
        float ly = top + (listLens - listFirst) * listRowH;
        box.set(listPanel.left + 12 * k, ly + 6 * k, listPanel.right - 12 * k, ly + listRowH - 6 * k);
        Liquid.draw(c, kitListRow, box, glass, 1, false, 0);
        long now = System.currentTimeMillis();
        Epg guide = Epg.get(getContext());
        Posters posters = Posters.get(getContext());
        int last = Math.min(all.size(), listFirst + listVisible + 1);
        for (int i = listFirst; i < last; i++) {
            Channels.Channel ch = all.get(i);
            float y = top + (i - listFirst) * listRowH, cy = y + listRowH / 2;
            float on = Math.max(0, 1 - Math.abs(listLens - i));
            int ink = blend(0xEBFFFFFF, Liquid.INK_ON_LIT, on), dim = blend(0x99FFFFFF, 0xB30B1116, on);
            listNumP.setColor(dim);
            c.drawText(String.valueOf(ch.number), listPanel.left + 92 * k, cy + Ui.halfCap(listNumP), listNumP);
            Bitmap logo = posters.get(ch.logo, (u, b) -> post(listView::invalidate));
            float lx = listPanel.left + 112 * k, lw = 96 * k, lh = 54 * k;
            // logos are white more often than not: they sit on a dark chip, so the lit row doesn't swallow them
            track.set(lx - 8 * k, cy - lh / 2 - 6 * k, lx + lw + 8 * k, cy + lh / 2 + 6 * k);
            fill.setColor(blend(0x1F000000, 0xF21A2329, on));
            c.drawRoundRect(track, 12 * k, 12 * k, fill);
            if (logo != null) {
                float bw = logo.getWidth(), bh = logo.getHeight();
                float s = Math.min(lw / bw, lh / bh);
                track.set(lx + (lw - bw * s) / 2, cy - bh * s / 2, lx + (lw + bw * s) / 2, cy + bh * s / 2);
                c.drawBitmap(logo, null, track, imagePaint);
            }
            float tx = lx + lw + 22 * k, right = listPanel.right - 44 * k;
            Epg.Show s = guide.now(ch.epg, now);
            listNameP.setColor(ink);
            String name = TextUtils.ellipsize(ch.name, new TextPaint(listNameP), right - tx, TextUtils.TruncateAt.END).toString();
            if (s == null) {
                c.drawText(name, tx, cy + Ui.halfCap(listNameP), listNameP);
            } else {
                c.drawText(name, tx, cy - 6 * k, listNameP);
                listShowP.setColor(dim);
                String what = TextUtils.ellipsize(Epg.clock(s.start) + "  " + s.title, new TextPaint(listShowP),
                        right - tx, TextUtils.TruncateAt.END).toString();
                c.drawText(what, tx, cy + 24 * k, listShowP);
                float pw = right - tx, py = cy + 34 * k;
                track.set(tx, py, tx + pw, py + 3 * k);
                fill.setColor(on > 0.5f ? 0x330B1116 : 0x33FFFFFF);
                c.drawRoundRect(track, 1.5f * k, 1.5f * k, fill);
                track.right = tx + Math.max(4 * k, pw * s.progress(now));
                fill.setColor(on > 0.5f ? 0xCC0B1116 : 0xCCFFFFFF);
                c.drawRoundRect(track, 1.5f * k, 1.5f * k, fill);
            }
            if (ch == channel) {
                fill.setColor(RED);
                c.drawCircle(listPanel.right - 26 * k, cy, 5 * k, fill);
            }
        }
        c.restore();
    }

    /** "12–" in a glass capsule at the top right, the channel it will be under it. */
    private void paintDigits(Canvas c) {
        if (digits.length() == 0 && digitsNote.isEmpty()) return;
        float cx = w - margin - 150 * k, cy = 56 * k + 64 * k;
        box.set(cx - 130 * k, cy - 52 * k, cx + 130 * k, cy + 52 * k);
        Liquid.draw(c, kitDigits, box, glass, 1, false, 0);
        if (!digitsNote.isEmpty()) {
            c.drawText(digitsNote, cx, cy + Ui.halfCap(digitsNameP), digitsNameP);
            return;
        }
        StringBuilder shown = new StringBuilder(digits);
        while (shown.length() < 2) shown.append('–');
        c.drawText(shown.toString(), cx, cy + Ui.halfCap(digitsP) - 8 * k, digitsP);
        Channels.Channel to = null;
        try { to = Channels.get(getContext()).byNumber(Integer.parseInt(digits.toString())); } catch (Exception ignored) { }
        if (to != null) {
            String n = TextUtils.ellipsize(to.name, new TextPaint(digitsNameP), 230 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(n, cx, cy + 40 * k, digitsNameP);
        }
    }

    private void paintSubtitles(Canvas c) {
        float line = 54 * k;
        float base = h - 84 * k;   // low on the picture; the layer rises above the controls when they show
        for (int i = subLines.length - 1; i >= 0; i--) {
            c.drawText(subLines[i], w / 2f, base, subP);
            base -= line;
        }
    }

    private void paintMenu(Canvas c) {
        if (panelShadow != null) {
            box.set(panel.left - panelPad, panel.top - panelPad + 14 * k, panel.right + panelPad,
                    panel.bottom + panelPad + 14 * k);
            c.drawBitmap(panelShadow, null, box, glass);
        }
        c.drawBitmap(panelFace, null, panel, glass);
        c.drawText(MENU_TITLES[menu], panel.left + 30 * k, panel.top + 42 * k, headP);

        c.save();
        c.clipRect(panel.left, panel.top + headH - 6 * k, panel.right, panel.bottom - 6 * k);
        float top = panel.top + headH;
        if (checked != -2) {
            float ly = top + (rowLens - first) * rowH + rowH / 2;
            box.set(panel.left + 10 * k, ly - kitRow.h / 2f, panel.right - 10 * k, ly + kitRow.h / 2f);
            Liquid.draw(c, kitRow, box, glass, 1, false, 0);
        }
        int last = Math.min(rows.length, first + visibleRows + 1);
        float labelX = panel.left + (checked == ACTIONS ? 40 * k : 68 * k);   // no check column in the options
        for (int i = first; i < last; i++) {
            float cy = top + (i - first) * rowH + rowH / 2;
            float on = checked == -2 ? 0 : Math.max(0, 1 - Math.abs(rowLens - i));
            int ink = blend(checked == -2 ? 0x80FFFFFF : 0xEBFFFFFF, Liquid.INK_ON_LIT, on);
            if (i == checked) Symbols.draw(getContext(), c, "check", panel.left + 40 * k, cy, 20 * k, ink);
            rowP.setColor(ink);
            c.drawText(rows[i], labelX, cy + Ui.halfCap(rowP), rowP);
            // the value on the right (a track's name, the picture's fit): what is playing, without opening the list
            if (i < values.length && !values[i].isEmpty()) {
                valueP.setColor(blend(0x99FFFFFF, Liquid.INK_ON_LIT, on));
                c.drawText(values[i], panel.right - 34 * k, cy + Ui.halfCap(valueP), valueP);
            }
        }
        c.restore();
    }

    private void paintGlyph(Canvas c) {
        if (glyph == null) return;
        float r = kitGlyph.h / 2f;
        box.set(w / 2f - r, h / 2f - r, w / 2f + r, h / 2f + r);
        Liquid.draw(c, kitGlyph, box, glass, 1, false, 0);
        Symbols.draw(getContext(), c, glyph, w / 2f, h / 2f, 50 * k, 0xFFFFFFFF);
    }

    private void paintError(Canvas c) {
        float cw = 900 * k, ch = 220 * k;
        box.set(w / 2f - cw / 2, h / 2f - ch / 2, w / 2f + cw / 2, h / 2f + ch / 2);
        fill.setColor(0xD91A1F24);
        c.drawRoundRect(box, 34 * k, 34 * k, fill);
        c.drawText("Cette vidéo ne s'ouvre pas.", w / 2f, box.top + 82 * k, errP);
        if (errorDetail.isEmpty()) {
            errorDetail = TextUtils.ellipsize(error, new TextPaint(errSmallP), cw - 80 * k,
                    TextUtils.TruncateAt.END).toString();
        }
        c.drawText(errorDetail, w / 2f, box.top + 130 * k, errSmallP);
        c.drawText("Retour pour revenir", w / 2f, box.top + 178 * k, errSmallP);
    }

    // MARK: - Helpers

    private static float approach(float v, float to, float rate) {
        float n = v + (to - v) * Math.min(1f, rate);
        return Math.abs(to - n) < 0.004f ? to : n;
    }

    private static float ease(float t) {
        float u = 1 - Math.max(0, Math.min(1, t));
        return 1 - u * u * u;
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** 1:02:03 or 4:05. */
    static String clock(long seconds) {
        long hh = seconds / 3600, mm = (seconds / 60) % 60, ss = seconds % 60;
        StringBuilder b = new StringBuilder(9);
        if (hh > 0) {
            b.append(hh).append(':');
            if (mm < 10) b.append('0');
        }
        b.append(mm).append(':');
        if (ss < 10) b.append('0');
        return b.append(ss).toString();
    }
}
