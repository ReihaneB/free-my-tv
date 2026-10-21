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
    private static final String[] ICONS = {"subtitles", "audio", "aspect"};
    private static final String[] MENU_TITLES = {"Sous-titres", "Audio", "Format de l'image"};
    private static final int HIDE_MS = 5000, COMMIT_MS = 700;
    private static final int RED = 0xFFFF453A;

    private static final int CONTROLS = 0, BAR = 1, SUBS = 2, MENU = 3, GLYPH = 4, SPINNER = 5, ERROR = 6;

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
    private final Layer controls, bar, subs, menuView, glyphView, spinner, errorView;
    private final Layer[] layers, fading;
    private float k = 1, w, h;

    // what is playing
    private String title = "", titleShown = "", meta = "", note = "";
    private boolean ready, live, buffering, failed;
    private String error = "";
    private long duration;
    private int bufferedPct;

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
    private final float[] buttonOn = new float[3];

    // the popover
    private int menu = -1, row, checked = -1, first;
    private boolean menuClosing;
    private String[] rows = new String[0];
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
    private long lastFrame;
    private boolean framePosted;

    // layout
    private float margin, barCY, timesBase, titleBase, metaBase, buttonD, buttonGap, buttonCY, rowH, headH, rise;
    private Liquid.Kit kitButton, kitButtonOn, kitRow, kitBubble, kitBadge, kitGlyph;

    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint(Paint.DITHER_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private Paint metaP, timeP, subP, headP, rowP, bubbleP, badgeP, errP, errSmallP;
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
        menuView = new Layer(c, MENU);
        glyphView = new Layer(c, GLYPH);
        spinner = new Layer(c, SPINNER);
        errorView = new Layer(c, ERROR);
        layers = new Layer[]{controls, bar, subs, menuView, glyphView, spinner, errorView};
        fading = new Layer[]{controls, bar};
        for (Layer l : layers) addView(l);
    }

    // MARK: - From the player

    void loading(String title, String meta) {
        this.title = title == null ? "" : title;
        this.meta = meta;
        ready = false;
        failed = false;
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

    void bufferedPct(int pct) {
        if (pct == bufferedPct) return;
        bufferedPct = pct;
        if (hudA > 0) bar.invalidate();
    }

    void seekDone() {
        pendingSeek = -1;
        posAt = 0;
        if (hudA > 0) bar.invalidate();
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
        errorView.setVisibility(VISIBLE);
        errorView.invalidate();
        kick();
    }

    void stopTimers() {
        removeCallbacks(hideRun);
        removeCallbacks(commitRun);
        Choreographer.getInstance().removeFrameCallback(this);
        framePosted = false;
    }

    // MARK: - Keys

    /** Back: closes what is open first; false when there is nothing left to close (the player leaves). */
    boolean back() {
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
        if (failed) return false;
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
                    if (repeat == 0 && checked != -2) {
                        host.choose(menu, row);
                        checked = row;
                        menuView.invalidate();
                    }
                    closeMenu();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    closeMenu();
                    return true;
                default:
                    return false;
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (repeat > 0 || !ready) return true;
                if (visible() && focus == ON_BUTTONS) openMenu(button);
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
                    button = Math.max(0, Math.min(ICONS.length - 1, button + (forward ? 1 : -1)));
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
        String[] r = host.menuRows(which);
        if (r == null) return;
        menu = which;
        menuClosing = false;
        int sel = host.menuChecked(which);
        if (r.length == 0) {
            rows = new String[]{which == MENU_SUBS ? "Aucun sous-titre disponible" : "Aucune piste"};
            checked = -2;   // nothing to choose
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
        removeCallbacks(hideRun);
        hudTo = 1;
        menuA = 0;
        menuView.setAlpha(0);
        menuView.setVisibility(VISIBLE);
        menuView.invalidate();
        kick();
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
        float pw = Math.round(Math.max(420 * k, Math.min(680 * k, textW + 112 * k)));
        float bottom = buttonCY - buttonD / 2 - 22 * k;
        int fit = (int) Math.max(1, Math.floor((bottom - 140 * k - headH - 28 * k) / rowH));
        int n = Math.min(list.length, fit);
        return new float[]{pw, Math.round(headH + n * rowH + 14 * k), n};
    }

    private void layoutMenu() {
        float[] size = panelSize(menu, rows);
        float pw = size[0], ph = size[1];
        visibleRows = (int) size[2];
        // it ends just past its own button (the one that opened it), kept inside the margin
        float right = Math.round(Math.min(w - margin, buttonCX(menu) + buttonD / 2 + 22 * k));
        float bottom = Math.round(buttonCY - buttonD / 2 - 22 * k);
        panel.set(right - pw, bottom - ph, right, bottom);
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
        menuView.setPivotX(buttonCX(menu) - l);   // it grows out of its button
        menuView.setPivotY(b - t);
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

        // the spinner turns (rotation of a drawn arc)
        boolean spinning = !failed && (!ready || buffering);
        if (spinning) {
            if (spinner.getVisibility() != VISIBLE) spinner.setVisibility(VISIBLE);
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
                if (glyphView.getVisibility() != VISIBLE) glyphView.setVisibility(VISIBLE);
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
        ellipsize();
        kick();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int W = r - l, H = b - t;
        place(controls, 0, Math.round(H - 470 * k), W, H);
        // the strip that changes every second: the bar, the times, the scrub bubble above the bar
        place(bar, 0, Math.round(barCY - 110 * k), W, Math.round(timesBase + 16 * k));
        place(subs, 0, Math.round(H - 380 * k), W, H);
        float cx = W / 2f, cy = H / 2f;
        int g = Math.round(kitGlyph.h / 2f + 6 * k);
        place(glyphView, Math.round(cx - g), Math.round(cy - g), Math.round(cx + g), Math.round(cy + g));
        int s = Math.round(40 * k);
        place(spinner, Math.round(cx - s), Math.round(cy - s), Math.round(cx + s), Math.round(cy + s));
        place(errorView, Math.round(cx - 470 * k), Math.round(cy - 130 * k), Math.round(cx + 470 * k),
                Math.round(cy + 130 * k));
        if (menu >= 0) layoutMenu();
    }

    private static void place(View v, int l, int t, int r, int b) {
        v.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY));
        v.layout(l, t, r, b);
    }

    private void ellipsize() {
        if (w <= 0) return;
        float max = w - 2 * margin - 3 * buttonD - 2 * buttonGap - 48 * k;
        titleShown = TextUtils.ellipsize(title, titleP, max, TextUtils.TruncateAt.END).toString();
    }

    // MARK: - Drawing (screen coordinates; each layer draws its own part)

    private void paint(int part, Canvas c) {
        if (kitButton == null) return;
        switch (part) {
            case CONTROLS: paintControls(c); break;
            case BAR: paintBar(c); break;
            case SUBS: paintSubtitles(c); break;
            case MENU: if (menu >= 0) paintMenu(c); break;
            case GLYPH: paintGlyph(c); break;
            case SPINNER: Ui.spinner(c, w / 2f, h / 2f, 30 * k, 0, spin); break;
            case ERROR: if (failed) paintError(c); break;
            default: break;
        }
    }

    private void paintControls(Canvas c) {
        c.drawRect(0, h - 470 * k, w, h, scrim);
        // scrubbing: the time and the bar are all that matter, the title and buttons step aside
        float a = 1 - scrubA;
        if (a <= 0) return;
        String m = note.isEmpty() ? meta : meta + "  ·  " + note;
        if (!m.isEmpty()) {
            metaP.setColor(Ui.alpha(0xB3FFFFFF, a));
            c.drawText(m, margin, metaBase, metaP);
        }
        titleP.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawText(titleShown, margin - Ui.leftBearing(titleP, titleShown), titleBase, titleP);
        for (int i = 0; i < ICONS.length; i++) {
            float on = buttonOn[i];
            float cx = buttonCX(i), r = buttonD / 2 * (1 + 0.1f * on);
            box.set(cx - r, buttonCY - r, cx + r, buttonCY + r);
            if (on < 1) Liquid.draw(c, kitButton, box, glass, a * (1 - on), false, 0);
            if (on > 0) Liquid.draw(c, kitButtonOn, box, glass, a * on, true, 7 * k);
            Symbols.draw(getContext(), c, ICONS[i], cx, buttonCY, 25 * k * (1 + 0.08f * on),
                    Ui.alpha(0xFFFFFFFF, a * (0.82f + 0.18f * on)));
        }
    }

    private float buttonCX(int i) {
        return w - margin - buttonD / 2 - (ICONS.length - 1 - i) * (buttonD + buttonGap);
    }

    private void paintBar(Canvas c) {
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
        if (bufferedPct > 0 && bufferedPct < 100 && !scrubbing) {
            float bx = track.left + bufferedPct / 100f * track.width();
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

    private void paintLive(Canvas c) {
        String label = "EN DIRECT";
        float bh = kitBadge.h, bw = badgeP.measureText(label) + 62 * k;
        box.set(margin, barCY - bh / 2, margin + bw, barCY + bh / 2);
        Liquid.draw(c, kitBadge, box, glass, 1, false, 0);
        fill.setColor(RED);
        c.drawCircle(box.left + 24 * k, barCY, 6 * k, fill);
        c.drawText(label, box.left + 40 * k, barCY + Ui.halfCap(badgeP), badgeP);
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
        for (int i = first; i < last; i++) {
            float cy = top + (i - first) * rowH + rowH / 2;
            float on = checked == -2 ? 0 : Math.max(0, 1 - Math.abs(rowLens - i));
            int ink = blend(checked == -2 ? 0x80FFFFFF : 0xEBFFFFFF, Liquid.INK_ON_LIT, on);
            if (i == checked) Symbols.draw(getContext(), c, "check", panel.left + 40 * k, cy, 20 * k, ink);
            rowP.setColor(ink);
            c.drawText(rows[i], panel.left + 68 * k, cy + Ui.halfCap(rowP), rowP);
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
