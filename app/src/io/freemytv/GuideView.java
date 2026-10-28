package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Le guide, as a box draws it: the channels down the side, three hours across, a programme in each cell. The days
 * before today are there too (the panel keeps three days of replay), so « hier soir » is two presses away: what is
 * past plays from its first second through the channel's replay, what is on now or later opens the channel live.
 */
final class GuideView extends View {

    interface Host {
        /** The guide covers the home screen: it can rest. */
        void covered();
        /** The guide leaves: the home screen is shown and alive again. */
        void uncovered();
    }

    private static final int MARGIN = 64, HEAD_H = 140, GUTTER = 250, ROWS = 7, ROW_H = 118, COLS = 6;
    private static final int ZONE_GRID = 0, ZONE_DAYS = 1;
    /** -2, -1, today, tomorrow. */
    private static final String[] DAYS = {"Avant-hier", "Hier", "Aujourd'hui", "Demain"};
    private static final int[] DAY_OFFSET = {-2, -1, 0, 1};
    private static final long SLOT = 30 * 60_000L, WINDOW = COLS * SLOT, MINUTE = 60_000L;
    private static final long BACK_LIMIT = 71 * 3600_000L;   // the panel's three days of replay, minus an hour of slack

    private final Host host;
    private float k = 1, w, h;
    private boolean open;
    private int zone = ZONE_GRID, row, col, day = 2, dayFocus = 2;
    private long windowStart;
    private String toast = "";
    private long toastAt;
    private long frameAt;
    private final List<Channels.Channel> channels = new ArrayList<>();
    private final List<Epg.Show> shown = new ArrayList<>();
    private final RectF box = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.DITHER_FLAG);
    private final Paint titleP, dayP, numP, nameP, cellP, cellTimeP, headP, hintP, toastP;
    private final Runnable hideToast = this::clearToast;
    private final Runnable tick = this::redraw;
    private final Epg.Listener guideChanged = this::onGuide;
    private boolean settle;   // the day just changed: slide to the first hour the panel actually has

    GuideView(Context c, Host host) {
        super(c);
        this.host = host;
        Ui.init(c);
        setVisibility(INVISIBLE);
        setBackgroundColor(0xFF0B1014);
        titleP = Ui.text(Ui.displaySemibold, 40, 0xFFFFFFFF);
        dayP = Ui.text(Ui.semibold, 22, 0xFFE7EDEE);
        numP = Ui.numbers(Ui.semibold, 20, 0xFF8798A0);
        numP.setTextAlign(Paint.Align.RIGHT);
        nameP = Ui.text(Ui.medium, 21, 0xFFE7EDEE);
        cellP = Ui.text(Ui.medium, 20, 0xFFE7EDEE);
        cellTimeP = Ui.numbers(Ui.regular, 16, 0x8CFFFFFF);
        headP = Ui.text(Ui.semibold, 19, 0xCCFFFFFF);
        headP.setLetterSpacing(0.06f);
        hintP = Ui.text(Ui.medium, 21, 0xB3FFFFFF);
        toastP = Ui.text(Ui.medium, 22, 0xFFFFFFFF);
        toastP.setTextAlign(Paint.Align.CENTER);
        Epg.get(c).addListener(guideChanged);   // the programmes arrive from the panel: the grid fills in by itself
    }

    boolean isOpen() { return open; }

    // MARK: - Opening and closing

    void show() {
        long now = System.currentTimeMillis();
        channels.clear();
        for (Channels.Channel ch : Channels.get(getContext()).all) if (!ch.epg.isEmpty()) channels.add(ch);
        day = 2;
        dayFocus = 2;
        zone = ZONE_GRID;
        row = 0;
        col = 2;
        windowStart = align(now) - SLOT;   // a little of the past, then now
        clampWindow();
        open = true;
        settle = false;
        setVisibility(VISIBLE);
        host.covered();
        warm();
        toast("");
        schedule();
        invalidate();
    }

    void close() {
        if (!open) return;
        open = false;
        setVisibility(INVISIBLE);
        host.uncovered();
    }

    private void clearToast() {
        toast = "";
        toastAt = 0;
        invalidate();
    }

    private void toast(String text) {
        toast = text == null ? "" : text;
        toastAt = text == null || text.isEmpty() ? 0 : System.currentTimeMillis();
        removeCallbacks(hideToast);
        if (toastAt > 0) postDelayed(hideToast, 2600);
        invalidate();
    }

    private void redraw() { invalidate(); }

    /** The programmes arrive from the panel: the grid fills in, and a day that opened on an empty stretch slides. */
    private void onGuide() {
        if (!open) return;
        if (settle) {
            settle = false;
            settleWindow();
            invalidate();
        } else {
            invalidate();
        }
    }

    /**
     * The panel's guide does not reach as far back as its replay (TF1's file started at 22:15 the day before): when a
     * past day is picked, open on the first hour the panel actually has, instead of a blank evening.
     */
    private void settleWindow() {
        Epg guide = Epg.get(getContext());
        long earliest = Long.MAX_VALUE;
        int probe = Math.min(ROWS, channels.size());
        for (int i = 0; i < probe; i++) {
            long[] e = guide.extent(channels.get(i).epg);
            if (e != null && e[0] > 0) earliest = Math.min(earliest, e[0]);
        }
        if (earliest == Long.MAX_VALUE) return;
        long dayStart = windowStart - Math.floorMod(windowStart, 86_400_000L);
        if (earliest >= dayStart && earliest < dayStart + 86_400_000L && earliest > windowStart) {
            windowStart = align(earliest);
        }
        warm();
    }

    /** The guide keeps the clock (the « now » line and the banner) moving while it is up. */
    private void schedule() {
        removeCallbacks(tick);
        if (open) postDelayed(tick, 30_000);
    }

    private void warm() {
        List<String> ids = new ArrayList<>();
        int last = Math.min(channels.size(), row + ROWS + 2);
        for (int i = Math.max(0, row - 1); i < last; i++) ids.add(channels.get(i).epg);
        Epg.get(getContext()).warm(ids);
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (!open) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = e.getKeyCode(), repeat = e.getRepeatCount();
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_GUIDE:
                close();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (zone == ZONE_GRID && row > 0) {
                    row--;
                    warm();
                } else if (zone == ZONE_GRID) {
                    zone = ZONE_DAYS;
                    dayFocus = day;
                } else if (repeat == 0) {
                    zone = ZONE_GRID;
                }
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (zone == ZONE_DAYS) {
                    zone = ZONE_GRID;
                } else if (row < channels.size() - 1) {
                    row++;
                    warm();
                }
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (zone == ZONE_DAYS) {
                    if (dayFocus > 0) dayFocus--;
                } else if (windowStart > minWindow()) {
                    windowStart -= SLOT;   // the cell stays, the grid slides: the remote walks back through the day
                    warm();
                }
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (zone == ZONE_DAYS) {
                    if (dayFocus < DAYS.length - 1) dayFocus++;
                } else if (windowStart < maxWindow()) {
                    windowStart += SLOT;
                    warm();
                }
                invalidate();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
                row = Math.max(0, row - ROWS);
                warm();
                invalidate();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                row = Math.min(Math.max(0, channels.size() - 1), row + ROWS);
                warm();
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (repeat > 0) return true;
                if (zone == ZONE_DAYS) {
                    day = dayFocus;
                    zone = ZONE_GRID;
                    jumpToDay();
                } else {
                    playFocused();
                }
                invalidate();
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (!channels.isEmpty() && row < channels.size()) {
                    Channels.Channel ch = channels.get(row);
                    Prefs.toggleFavorite(ch.number);   // en tête de l'accueil, ou plus
                    toast(Prefs.favorite(ch.number) ? ch.name + " dans « Mes chaînes »"
                            : ch.name + " retirée de « Mes chaînes »");
                }
                return true;
            default:
                return true;
        }
    }

    private void jumpToDay() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis());
        long today = c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY, 19);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long evening = c.getTimeInMillis();
        int offset = DAY_OFFSET[day];
        long at;
        if (offset == 0) at = align(System.currentTimeMillis()) - SLOT;
        else at = evening + offset * 86_400_000L;
        windowStart = align(at);
        clampWindow();
        col = 2;
        settle = true;
        warm();
        settleWindow();   // the panel's guide starts later than its replay: open where there is something
        settle = false;
    }

    // MARK: - The window

    private static long align(long at) {
        return at - at % SLOT;
    }

    private long minWindow() { return align(System.currentTimeMillis() - BACK_LIMIT); }

    private long maxWindow() { return align(System.currentTimeMillis()) + 24 * 3600_000L; }

    private void clampWindow() {
        long lo = minWindow(), hi = maxWindow();
        if (windowStart < lo) windowStart = lo;
        if (windowStart > hi) windowStart = hi;
    }

    /** The programme the focus is on: the cell's start, plus a second so a boundary never lands in the gap. */
    private Epg.Show focused() {
        if (channels.isEmpty() || row >= channels.size()) return null;
        Channels.Channel ch = channels.get(row);
        return Epg.get(getContext()).now(ch.epg, windowStart + col * SLOT + 1000);
    }

    private void playFocused() {
        Epg.Show s = focused();
        if (s == null || channels.isEmpty()) return;
        Channels.Channel ch = channels.get(row);
        long now = System.currentTimeMillis();
        Intent it = new Intent(getContext(), Player.class);
        it.putExtra(Player.EXTRA_CHANNEL, ch.number);
        it.putExtra(Player.EXTRA_URL, ch.url());
        it.putExtra(Player.EXTRA_NAME, ch.name);
        it.putExtra(Player.EXTRA_LOGO, ch.logo);
        if (s.stop > now) {   // now or later: the channel, live
            getContext().startActivity(it);
            return;
        }
        if (ch.replay == null) {
            toast("Pas de replay sur " + ch.name);
            return;
        }
        if (s.start < now - ch.replayDays * 86_400_000L) {
            toast("Trop ancien : " + ch.name + " garde " + ch.replayDays + " jours");
            return;
        }
        it.putExtra(Player.EXTRA_REPLAY_START, s.start);
        it.putExtra(Player.EXTRA_REPLAY_STOP, s.stop);
        it.putExtra(Player.EXTRA_REPLAY_TITLE, s.title);
        getContext().startActivity(it);
    }

    private String hint() {
        Epg.Show s = focused();
        if (s == null) return "Guide en cours de chargement…";
        boolean past = s.stop <= System.currentTimeMillis();
        return past ? "OK : revoir ce programme depuis le début" : "OK : regarder la chaîne en direct";
    }

    // MARK: - Drawing

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw;
        h = nh;
        k = Math.min(w / 1920f, h / 1080f);
        bg.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF101720, 0xFF070A0D},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
    }

    private float rowTop(int i) { return HEAD_H * k + i * ROW_H * k; }

    private float gridLeft() { return GUTTER * k; }

    private float gridRight() { return w - MARGIN * k; }

    private float colW() { return (gridRight() - gridLeft()) / COLS; }

    @Override protected void onDraw(Canvas c) {
        c.drawPaint(bg);
        long now = System.currentTimeMillis();
        drawHead(c, now);
        drawGrid(c, now);
        drawFooter(c, now);
        if (!toast.isEmpty()) {
            float a = 1f;
            long t = System.currentTimeMillis() - toastAt;
            if (t > 2000) a = Math.max(0f, 1f - (t - 2000) / 600f);
            fill.setColor(Ui.alpha(0xE6151C22, a));
            float tw = toastP.measureText(toast) + 64 * k;
            box.set(w / 2 - tw / 2, h - 150 * k, w / 2 + tw / 2, h - 86 * k);
            c.drawRoundRect(box, 26 * k, 26 * k, fill);
            toastP.setColor(Ui.alpha(0xFFFFFFFF, a));
            c.drawText(toast, w / 2, h - 108 * k, toastP);
            if (a > 0 & t < 2600) postInvalidateDelayed(60);
        }
        schedule();
    }

    private void drawHead(Canvas c, long now) {
        titleP.setColor(0xFFFFFFFF);
        titleP.setTextAlign(Paint.Align.LEFT);
        c.drawText("Guide", MARGIN * k, 74 * k, titleP);

        float x = 330 * k, y = 34 * k, pw = 210 * k, ph = 52 * k;
        for (int i = 0; i < DAYS.length; i++) {
            box.set(x, y, x + pw, y + ph);
            boolean active = DAY_OFFSET[i] == DAY_OFFSET[day];
            boolean focusHere = zone == ZONE_DAYS && dayFocus == i;
            if (active) {
                fill.setColor(0x26FFFFFF);
                c.drawRoundRect(box, ph / 2, ph / 2, fill);
            }
            if (focusHere) {
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(2 * k);
                stroke.setColor(0xFFFFFFFF);
                c.drawRoundRect(box, ph / 2, ph / 2, stroke);
            }
            dayP.setColor(active ? 0xFFFFFFFF : 0xB3FFFFFF);
            dayP.setTextAlign(Paint.Align.CENTER);
            c.drawText(DAYS[i], box.centerX(), box.centerY() + Ui.halfCap(dayP), dayP);
            x += pw + 14 * k;
        }
        dayP.setTextAlign(Paint.Align.RIGHT);
        dayP.setColor(0xCCFFFFFF);
        c.drawText(Epg.clock(now) + "  ·  " + Epg.clock(windowStart) + " – " + Epg.clock(windowStart + WINDOW),
                w - MARGIN * k, 74 * k, dayP);
        dayP.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGrid(Canvas c, long now) {
        if (channels.isEmpty()) {
            hintP.setColor(0xCCFFFFFF);
            hintP.setTextAlign(Paint.Align.CENTER);
            c.drawText("Aucune chaîne : le guide attend le catalogue.", w / 2, h / 2, hintP);
            hintP.setTextAlign(Paint.Align.LEFT);
            return;
        }
        // the time ruler
        headP.setColor(0x99FFFFFF);
        headP.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < COLS; i++) {
            float cx = gridLeft() + i * colW();
            c.drawText(Epg.clock(windowStart + i * SLOT), cx + 8 * k, HEAD_H * k - 26 * k, headP);
        }

        int last = Math.min(channels.size(), row + ROWS);
        int first = Math.max(0, Math.min(row, channels.size() - ROWS));
        Epg guide = Epg.get(getContext());
        float pxPerMs = colW() / SLOT;
        for (int i = first; i < last; i++) {
            Channels.Channel ch = channels.get(i);
            float top = rowTop(i - first), bottom = top + ROW_H * k;
            if (i % 2 == 0) {
                fill.setColor(0x0AFFFFFF);
                c.drawRect(0, top, w, bottom, fill);
            }
            numP.setColor(0xFF8798A0);
            c.drawText(String.valueOf(ch.number), (GUTTER - 24) * k, top + ROW_H * k / 2 + Ui.halfCap(numP), numP);
            nameP.setColor(0xFFE7EDEE);
            c.drawText(TextUtils.ellipsize(ch.name, new TextPaint(nameP), (GUTTER - 108) * k,
                    TextUtils.TruncateAt.END).toString(), 24 * k, top + ROW_H * k / 2 + Ui.halfCap(nameP), nameP);

            List<Epg.Show> list = guide.between(ch.epg, windowStart - 2 * SLOT, windowStart + WINDOW + 2 * SLOT);
            for (Epg.Show s : list) {
                float x1 = gridLeft() + (s.start - windowStart) * pxPerMs;
                float x2 = gridLeft() + (s.stop - windowStart) * pxPerMs;
                float l = Math.max(gridLeft(), x1) + 3 * k, r = Math.min(gridRight(), x2) - 3 * k;
                if (r <= l) continue;
                boolean focused = i == row && s.start <= windowStart + col * SLOT + 1000 && s.stop > windowStart + col * SLOT + 1000;
                float ty = top + 10 * k, by = bottom - 10 * k;
                box.set(l, ty, r, by);
                fill.setColor(focused ? 0xF2FFFFFF : s.stop <= now ? 0x16FFFFFF : 0x0EFFFFFF);
                c.drawRoundRect(box, 12 * k, 12 * k, fill);
                int ink = focused ? 0xFF10171C : s.stop <= now ? 0xB3FFFFFF : 0xE6FFFFFF;
                cellP.setColor(ink);
                cellTimeP.setColor(focused ? 0xB310171C : 0x73FFFFFF);
                float room = box.width() - 20 * k;
                if (room < 40 * k) continue;
                String t = TextUtils.ellipsize(s.title, new TextPaint(cellP), room, TextUtils.TruncateAt.END).toString();
                c.drawText(t, l + 10 * k, ty + 34 * k, cellP);
                String span = Epg.clock(s.start) + " – " + Epg.clock(s.stop);
                if (room > cellTimeP.measureText(span) + 30 * k) {
                    c.drawText(span, l + 10 * k, by - 14 * k, cellTimeP);
                }
                if (focused) {
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(3 * k);
                    stroke.setColor(0xFFFFFFFF);
                    c.drawRoundRect(box, 12 * k, 12 * k, stroke);
                }
            }
        }
        // « now », when the window holds it
        if (now >= windowStart && now <= windowStart + WINDOW) {
            float x = gridLeft() + (now - windowStart) * pxPerMs;
            fill.setColor(0xFFFF8A00);
            c.drawRect(x - 1.5f * k, HEAD_H * k, x + 1.5f * k, rowTop(ROWS) * k, fill);
        }
    }

    private void drawFooter(Canvas c, long now) {
        Epg.Show s = focused();
        float y = rowTop(ROWS) + 44 * k;
        if (s == null) {
            hintP.setColor(0x99FFFFFF);
            hintP.setTextAlign(Paint.Align.LEFT);
            c.drawText("Aucun programme à cette heure-là  ·  ← → pour changer d'heure", MARGIN * k, y, hintP);
            return;
        }
        boolean past = s.stop <= now;
        Channels.Channel ch = row < channels.size() ? channels.get(row) : null;
        String head = ch == null ? "" : (Prefs.favorite(ch.number) ? "★  " : "")
                + ch.number + "  ·  " + ch.name + "  ·  " + Epg.span(s);
        headP.setColor(0x99FFFFFF);
        headP.setTextAlign(Paint.Align.LEFT);
        c.drawText(head, MARGIN * k, y - 30 * k, headP);
        hintP.setColor(0xFFFFFFFF);
        c.drawText(TextUtils.ellipsize(s.title, new TextPaint(hintP), w - 2 * MARGIN * k - 420 * k,
                TextUtils.TruncateAt.END).toString(), MARGIN * k, y + 6 * k, hintP);
        String right;
        if (past) right = ch != null && ch.replay != null ? "Revoir depuis le début" : "Pas de replay";
        else right = s.start > now ? "À venir  ·  " + Epg.clock(s.start) : "En direct";
        hintP.setColor(past && (ch == null || ch.replay == null) ? 0x66FFFFFF : 0xFFFF8A00);
        hintP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(right, w - MARGIN * k, y + 6 * k, hintP);
        hintP.setTextAlign(Paint.Align.LEFT);
        headP.setColor(0x73FFFFFF);
        c.drawText("↑ ↓ chaînes   ← → heures   OK regarder   INFO ★ mes chaînes   Retour fermer",
                MARGIN * k, h - 26 * k, headP);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (open) schedule();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        removeCallbacks(hideToast);
        super.onDetachedFromWindow();
    }

    /** Not used yet: kept so Color stays imported for the palette work above. */
    @SuppressWarnings("unused")
    private static final int INK = Color.WHITE;
}
