package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The picture behind a screen, as the home screen and a film's page both show it: the backdrop cropped to the
 * screen (or, without one, a wash of the poster's colours, or plain slate), with the scrims already on it — the
 * left side darkest where the text sits, the bottom behind the shelves, a hint at the top behind the bar.
 *
 * Baked once, off the UI thread, into one bitmap kept in GPU memory (screen-sized for a film's page, 2/3 for the
 * home screen, drawn scaled): the frame draws it in a single pass, nothing is uploaded when it first shows. With it,
 * a 192×108 frosted copy (heavily blurred): what the glass of the bar shows of it.
 */
final class Scenery {
    static final int FW = 192, FH = 108;

    final String key;
    final Bitmap image;   // HARDWARE when the TV allows it
    final Bitmap frost;   // small, blurred, scrims included

    private Scenery(String key, Bitmap image, Bitmap frost) {
        this.key = key;
        this.image = image;
        this.frost = frost;
    }

    /** A film's page: its text sits at the bottom left; screen-sized, in GPU memory. */
    static Scenery bake(String key, Bitmap src, boolean wash, int w, int h) {
        return make(key, w, h, painter(src, wash, false));
    }

    /**
     * The home screen's picture: the shelves cover the lower half, so the bottom scrim starts higher and is darker.
     * Baked at 2/3 of the screen (1280×720, the size the panel's backdrops come in: nothing is lost) and drawn
     * scaled: 2.25 × fewer pixels for the CPU and for the upload. Never on the render thread: an off-screen
     * HardwareRenderer shares it with the UI and made every frame late while it baked. Call off the UI thread.
     */
    static Scenery home(String key, Bitmap src, int w, int h) {
        return make(key, w * 2 / 3, h * 2 / 3, painter(src, false, true));
    }

    private static Painter painter(Bitmap src, boolean wash, boolean home) {
        return (c, cw, ch) -> {
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            c.drawColor(0xFF0B1116);
            if (src != null && wash) {
                c.drawBitmap(src, null, new RectF(0, 0, cw, ch), p);
                c.drawColor(0x8C05080B);
            } else if (src != null) {
                float sw0 = src.getWidth(), sh0 = src.getHeight();
                float scale = Math.max(cw / sw0, ch / sh0);
                float sw = cw / scale, sh = ch / scale;
                // a backdrop keeps its top (faces are rarely at the very bottom)
                int sx = Math.round((sw0 - sw) / 2), sy = Math.round((sh0 - sh) * 0.3f);
                c.drawBitmap(src, new Rect(sx, sy, Math.round(sx + sw), Math.round(sy + sh)), new RectF(0, 0, cw, ch), p);
            } else {
                // slate: Infuse's dark background, a little lighter at the top
                Paint g = new Paint(Paint.DITHER_FLAG);
                g.setShader(new LinearGradient(0, 0, 0, ch, 0xFF121A20, 0xFF05080A, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, cw, ch, g);
            }
            scrims(c, cw, ch, home);
        };
    }

    /**
     * No picture to show (a channel, an app, a film without backdrop): a flat light of its colour, never a blurred
     * copy of its logo or poster (that reads as a smear). The colour glows from the top right, where a backdrop's
     * subject would be, and fades into slate. For the home screen (2/3 size, like `home`). Call off the UI thread.
     */
    static Scenery tinted(String key, int color, int w, int h) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(0.75f, Math.max(hsv[1], hsv[1] > 0.08f ? 0.45f : hsv[1]));   // greys stay grey
        hsv[2] = Math.min(0.62f, Math.max(0.36f, hsv[2]));
        int glow = android.graphics.Color.HSVToColor(hsv) & 0xFFFFFF;
        return make(key, w * 2 / 3, h * 2 / 3, (c, cw, ch) -> {
            Paint g = new Paint(Paint.DITHER_FLAG);
            g.setShader(new LinearGradient(0, 0, 0, ch, 0xFF121A20, 0xFF05080A, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, cw, ch, g);
            g.setShader(new android.graphics.RadialGradient(cw * 0.80f, ch * 0.10f, cw * 0.85f,
                    new int[]{0xF0000000 | glow, 0x80000000 | glow, glow}, new float[]{0, 0.45f, 1},
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, cw, ch, g);
            scrims(c, cw, ch, true);
        });
    }

    /** Paints a picture at any size: once at the picture's size, once small for the frost. */
    private interface Painter { void paint(Canvas c, int w, int h); }

    private static Scenery make(String key, int w, int h, Painter painter) {
        Bitmap out;
        try {
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
        painter.paint(new Canvas(out), w, h);
        out.setHasAlpha(false);   // opaque: the GPU draws it without blending
        // the frost the bar's glass shows: the same painting at 2 × 192×108, then blurred down
        Bitmap small = Bitmap.createBitmap(FW * 2, FH * 2, Bitmap.Config.ARGB_8888);
        painter.paint(new Canvas(small), FW * 2, FH * 2);
        Bitmap frost = Bitmap.createBitmap(Backdrop.reduce(small, FW, FH, 3, 3), FW, FH, Bitmap.Config.ARGB_8888);
        small.recycle();
        // HARDWARE: uploaded here, by the uploader's own thread — the frame that first shows it has nothing to upload
        return new Scenery(key, Backdrop.gpu(out), frost);
    }

    /** A picture's average colour (a logo's transparent parts don't count). */
    static int average(Bitmap b) {
        if (b == null) return 0xFF23323C;
        try {
            if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap small = Bitmap.createScaledBitmap(b, 24, 24, true);
            long r = 0, gg = 0, bl = 0, n = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = small.getPixel(x, y), a = p >>> 24;
                    if (a < 128) continue;
                    r += (p >> 16) & 255;
                    gg += (p >> 8) & 255;
                    bl += p & 255;
                    n++;
                }
            }
            if (n == 0) return 0xFF23323C;
            return 0xFF000000 | ((int) (r / n) << 16) | ((int) (gg / n) << 8) | (int) (bl / n);
        } catch (Exception e) {
            return 0xFF23323C;
        }
    }

    /** The scrims: the text's side darkest, the picture free at the top right. */
    private static void scrims(Canvas c, int w, int h, boolean home) {
        Paint g = new Paint(Paint.DITHER_FLAG);
        g.setShader(new LinearGradient(0, 0, w * 0.66f, 0, new int[]{0xC705080B, 0x6105080B, 0x0005080B},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w * 0.66f, h, g);
        float from = (home ? 0.28f : 0.34f) * h;
        g.setShader(home
                ? new LinearGradient(0, from, 0, h, new int[]{0x0005080B, 0xC705080B, 0xF505080B},
                        new float[]{0, 0.42f, 1}, Shader.TileMode.CLAMP)
                : new LinearGradient(0, from, 0, h, new int[]{0x0005080B, 0xB805080B, 0xF505080B},
                        new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, from, w, h, g);
        g.setShader(new LinearGradient(0, 0, 0, h * 0.2f, 0x5205080B, 0x0005080B, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.2f, g);
    }

    /** The middle of a poster (or a banner), 16:9, reduced to a few pixels and grown back: a wash of its colours. */
    static Bitmap softOf(Bitmap p) {
        if (p == null || p.getWidth() < 4 || p.getHeight() < 4) return null;
        try {
            if (p.getConfig() == Bitmap.Config.HARDWARE) p = p.copy(Bitmap.Config.ARGB_8888, false);
            int pw = p.getWidth(), ph = p.getHeight();
            int cw = pw, ch = Math.round(pw * 9f / 16f);
            if (ch > ph) {
                ch = ph;
                cw = Math.round(ph * 16f / 9f);
            }
            Bitmap cur = Bitmap.createBitmap(p, (pw - cw) / 2, (ph - ch) / 2, cw, ch);
            int tw = cur.getWidth(), tht = cur.getHeight();
            while (tw > 64) {   // halving steps: every pixel counts, no sparkle
                tw = Math.max(32, tw / 2);
                tht = Math.max(18, tht / 2);
                cur = Bitmap.createScaledBitmap(cur, tw, tht, true);
            }
            cur = Bitmap.createScaledBitmap(cur, 32, 18, true);
            return Bitmap.createScaledBitmap(cur, 160, 90, true);
        } catch (Exception e) {
            return null;
        }
    }
}
