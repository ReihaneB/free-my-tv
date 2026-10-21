package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Liquid Glass for a Canvas UI on Android 11, which has neither RenderEffect (31) nor RuntimeShader (33).
 *
 * <p>Every glass surface is the same stack, lit from the top-left: a soft shadow, a fill, the inner glow of a thick
 * edge, a sheen on top and a caustic at the bottom (a glass rod is bright on both edges and quiet in the middle), and
 * a thin specular rim, brightest on the top-left curve and again, weaker, on the bottom-right one. All of it is baked
 * once per shape into bitmaps; a frame only blits them. Capsules are baked one pixel wider than a circle and drawn
 * in three slices, so one bake serves every width — the moving focus lens included.
 *
 * <p>Materials: PLATE (the bar; its fill is the live backdrop, drawn by the caller), CLEAR (a quiet glass control),
 * LIT (the focused control: bright glass, dark ink — the tvOS focus), WELL (a recessed field), DARK (over artwork).
 */
final class Liquid {
    private Liquid() {}

    static final int PLATE = 0, CLEAR = 1, LIT = 2, WELL = 3, DARK = 4;

    /** Ink for each material. */
    static final int INK_ON_GLASS = 0xFFFFFFFF, INK_ON_LIT = 0xFF0B1116;

    /** A baked capsule of one height, drawable at any width ≥ its height. */
    static final class Kit {
        final Bitmap face, shadow;
        final int h, pad;
        final float shadowScale;

        Kit(Bitmap face, Bitmap shadow, int h, int pad, float shadowScale) {
            this.face = face;
            this.shadow = shadow;
            this.h = h;
            this.pad = pad;
            this.shadowScale = shadowScale;
        }
    }

    private static final Rect SRC = new Rect();
    private static final RectF DST = new RectF(), SLICE = new RectF();

    // MARK: - Baking

    static Kit capsule(int material, int h, float k, boolean withShadow) {
        int w = h + 2;
        Bitmap face = face(material, w, h, h / 2f, k);
        Bitmap shadow = null;
        int pad = 0;
        float s = 0.5f;
        if (withShadow) {
            pad = Math.round(material == LIT ? 16 * k : 12 * k);
            shadow = shadow(w, h, h / 2f, pad, material == LIT ? 10 * k : 8 * k, material == LIT ? 0.55f : 0.40f, s);
        }
        return new Kit(face, shadow, h, pad, s);
    }

    /** One surface of w×h with corner radius r, everything but the shadow. */
    static Bitmap face(int material, int w, int h, float r, float k) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        RectF box = new RectF(0, 0, w, h);
        Path shape = new Path();
        shape.addRoundRect(box, r, r, Path.Direction.CW);
        c.clipPath(shape);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        // fill
        switch (material) {
            case PLATE: p.setColor(0x10FFFFFF); break;
            case CLEAR: p.setColor(0x24FFFFFF); break;
            case LIT: p.setShader(new LinearGradient(0, 0, 0, h, 0xF5FFFFFF, 0xE6E4EAEE, Shader.TileMode.CLAMP)); break;
            case WELL: p.setColor(0x38000000); break;
            default: p.setColor(0xE00B1116); break;
        }
        c.drawRect(box, p);
        p.setShader(null);

        if (material == WELL) {
            // recessed: the top lip throws a shadow into the field, the bottom lip catches the light
            p.setShader(new LinearGradient(0, 0, 0, h * 0.5f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(box, p);
            p.setShader(null);
            rim(c, w, h, r, k, 1.3f * k, 0f, 0.04f, 0f, 0.12f, 0.55f, false);
            return b;
        }
        if (material == LIT) {
            // bright glass: a whiter crest on top, a soft shade in the bottom third gives it volume
            p.setShader(new LinearGradient(0, h * 0.62f, 0, h, 0x00000000, 0x1F0B1116, Shader.TileMode.CLAMP));
            c.drawRect(box, p);
            p.setShader(new LinearGradient(0, 0, 0, h * 0.18f, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(box, p);
            p.setShader(null);
            rim(c, w, h, r, k, 1.4f * k, 0f, 0.55f, 0.35f, 0.9f, 0.2f, false);
            return b;
        }
        float sheen = material == PLATE ? 0.10f : material == CLEAR ? 0.12f : 0.07f;
        p.setShader(new LinearGradient(0, 0, 0, h * 0.5f, Ui.alpha(0xFFFFFFFF, sheen), 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(box, p);
        p.setShader(new LinearGradient(0, h * 0.6f, 0, h, 0x00FFFFFF, Ui.alpha(0xFFFFFFFF, sheen * 0.6f),
                Shader.TileMode.CLAMP));
        c.drawRect(box, p);
        p.setShader(null);

        float glow = material == DARK ? 0.18f : 0.30f;
        rim(c, w, h, r, k, 12 * k, 7 * k, glow * 0.08f, glow * 0.30f, glow, glow * 0.65f, true);   // inner glow
        float spec = material == DARK ? 0.7f : 0.95f;
        rim(c, w, h, r, k, 1.5f * k, 0f, spec * 0.07f, spec * 0.20f, spec, spec * 0.62f, false);  // specular rim
        return b;
    }

    /**
     * A rim on the edge of the shape (inside half only — the caller has clipped to it): a uniform base, a brighter
     * top edge, a highlight on the top-left curve and a weaker one on the bottom-right curve.
     */
    private static void rim(Canvas c, int w, int h, float r, float k, float width, float blur,
                            float base, float top, float topLeft, float bottomRight, boolean glow) {
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(width);
        if (blur > 0) s.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        float inset = glow ? 0 : width / 2f;
        RectF e = new RectF(inset, inset, w - inset, h - inset);
        float er = Math.max(0, r - inset);
        float d = Math.min(w, h) * 1.6f;

        s.setColor(Ui.alpha(0xFFFFFFFF, base));
        c.drawRoundRect(e, er, er, s);
        s.setColor(0xFFFFFFFF);
        s.setShader(new LinearGradient(0, 0, 0, h * 0.5f, Ui.alpha(0xFFFFFFFF, top), 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(e, er, er, s);
        s.setShader(new LinearGradient(0, 0, d, d, Ui.alpha(0xFFFFFFFF, topLeft), 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(e, er, er, s);
        s.setShader(new LinearGradient(w, h, w - d, h - d, Ui.alpha(0xFFFFFFFF, bottomRight), 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(e, er, er, s);
    }

    /** A soft shadow for a w×h shape, baked at `scale` with `pad` around it (both in full-size pixels). */
    static Bitmap shadow(int w, int h, float r, int pad, float blur, float alpha, float scale) {
        int bw = Math.max(1, Math.round((w + 2 * pad) * scale)), bh = Math.max(1, Math.round((h + 2 * pad) * scale));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Ui.alpha(0xFF000000, alpha));
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, blur * scale), BlurMaskFilter.Blur.NORMAL));
        RectF box = new RectF(pad * scale, pad * scale, (pad + w) * scale, (pad + h) * scale);
        c.drawRoundRect(box, r * scale, r * scale, p);
        return b;
    }

    // MARK: - Drawing

    /** Draws a capsule kit over `box` (any width): shadow first if asked, then the face. */
    static void draw(Canvas c, Kit kit, RectF box, Paint paint, float alpha, boolean shadow, float dy) {
        if (alpha <= 0.003f) return;
        paint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
        if (shadow && kit.shadow != null) {
            float pad = kit.pad;
            DST.set(box.left - pad, box.top - pad + dy, box.right + pad, box.bottom + pad + dy);
            slices(c, kit.shadow, (pad + kit.h / 2f) * kit.shadowScale, DST, pad + box.height() / 2f, paint);
        }
        DST.set(box);
        slices(c, kit.face, kit.h / 2f, DST, box.height() / 2f, paint);
        paint.setAlpha(255);
    }

    /** Three-slice blit: the two caps at their size, the middle column stretched. */
    private static void slices(Canvas c, Bitmap b, float srcCap, RectF dst, float dstCap, Paint paint) {
        int bw = b.getWidth(), bh = b.getHeight();
        int cap = Math.max(1, Math.min((bw - 1) / 2, Math.round(srcCap)));
        float dc = Math.min(dstCap, dst.width() / 2f);
        float l = dst.left, r = dst.right;
        SRC.set(0, 0, cap, bh);
        RectF d = SLICE;
        d.set(l, dst.top, l + dc, dst.bottom);
        c.drawBitmap(b, SRC, d, paint);
        if (r - dc > l + dc) {
            SRC.set(cap, 0, bw - cap, bh);
            d.set(l + dc, dst.top, r - dc, dst.bottom);
            c.drawBitmap(b, SRC, d, paint);
        }
        SRC.set(bw - cap, 0, bw, bh);
        d.set(r - dc, dst.top, r, dst.bottom);
        c.drawBitmap(b, SRC, d, paint);
    }
}
