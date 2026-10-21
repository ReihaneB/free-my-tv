package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

/**
 * The home's backdrop: a very dark gradient with four huge soft lights tinted by the focused artwork, drifting on
 * slow Lissajous paths. It is painted 12× smaller on its own thread, 15 times a second, into one of three buffers,
 * and the view only blits the latest one — the UI thread never paints a gradient pixel. (Painted on the UI thread,
 * the same picture cost 6-10 ms every 90 ms: one frame in ten missed its vsync.)
 *
 * <p>Three buffers because the render thread may still be drawing the previous frame's buffer while the worker
 * fills the next: the one being written is never the one shown nor the one shown just before.
 */
final class Lights {
    static final int SCALE = 12;
    private static final long PERIOD_MS = 66;

    private final View view;
    private final int lw, lh;
    private final Bitmap[] buf = new Bitmap[3];
    private volatile int front = -1;
    private volatile int generation;
    private volatile int target = 0xFF23323C;
    private volatile boolean running;

    // Each light fades its own final colour (never the accent before it is re-saturated: half-way between two tones
    // the accent can be grey, its hue undefined, and the side lights jumped). Worker thread only.
    private static final float[] ALPHA = {0.74f, 0.58f, 0.52f, 0.50f};
    private final float[][] cur = new float[4][3], vel = new float[4][3];
    private final int[] tgt = new int[4];
    private int paletteOf;
    private boolean painted, fading;
    private long lastStep;
    private long fadeAt, prevStep, fadeGap;   // Perf only
    private int fadeSteps;
    private float fadeJump;
    // A critically damped spring per channel: it leaves at zero speed, arrives without overshoot, and a new target
    // mid-way keeps the current speed — no step, ever. ω = 10 rad/s settles a change in about half a second.
    private static final float OMEGA = 10f;
    // While a colour fades, the worker paints on vsync (its own Choreographer) so every frame gets a new tone;
    // at rest it goes back to 15 pictures a second for the slow drift.
    private Choreographer choreo;
    private boolean vsyncPosted;
    private final Choreographer.FrameCallback vsync = n -> {
        vsyncPosted = false;
        step();
    };

    private final Canvas canvas = new Canvas();
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint();
    private final Paint vignette = new Paint();
    private HandlerThread thread;
    private Handler worker;
    private final Runnable step = this::step;

    Lights(View view, int width, int height, int top, int bottom) {
        this.view = view;
        lw = Math.max(16, Math.round(width / (float) SCALE));
        lh = Math.max(9, Math.round(height / (float) SCALE));
        for (int i = 0; i < buf.length; i++) {
            buf[i] = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888);
            buf[i].setHasAlpha(false);   // opaque: the render thread can skip blending the whole screen
        }
        base.setShader(new LinearGradient(0, 0, 0, lh, top, bottom, Shader.TileMode.CLAMP));
        vignette.setShader(new LinearGradient(0, lh * 0.55f, 0, lh, 0x00000000, Ui.alpha(bottom, 0.62f),
                Shader.TileMode.CLAMP));
    }

    int width() { return lw; }

    int height() { return lh; }

    /** The latest finished picture, or null before the first one. */
    Bitmap front() {
        int f = front;
        return f < 0 ? null : buf[f];
    }

    /** Changes every time a new picture is published (shaders built on the old one must be rebuilt). */
    int generation() { return generation; }

    /** The colour the lights fade to (eased, ~0.4 s, at the display's rate). Starts on the very next frame. */
    void accent(int color) {
        if (color == target) return;
        target = color;
        Handler h = worker;
        if (h != null && running) {
            h.removeCallbacks(step);
            h.post(step);
        }
    }

    synchronized void start() {
        if (running) return;
        running = true;
        if (thread == null) {
            thread = new HandlerThread("lights", Process.THREAD_PRIORITY_DISPLAY + 4);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        worker.removeCallbacks(step);
        worker.post(step);
    }

    synchronized void stop() {
        running = false;
        if (worker != null) worker.removeCallbacks(step);
        lastStep = 0;
    }

    synchronized void release() {
        stop();
        if (thread != null) thread.quitSafely();
        thread = null;
        worker = null;
    }

    private void step() {
        if (!running) return;
        long started = SystemClock.uptimeMillis();
        int t = target;
        if (!painted || t != paletteOf) {
            paletteOf = t;
            int main = vivid(t);
            tgt[0] = main;
            tgt[1] = vivid(hue(t, 30f));
            tgt[2] = vivid(hue(t, -30f));
            tgt[3] = deep(main);
        }
        float dt = lastStep == 0 ? 0.016f : Math.min(0.1f, (started - lastStep) / 1000f);
        lastStep = started;
        boolean wasFading = fading;
        float jump = 0;
        fading = false;
        int sub = Math.max(1, (int) Math.ceil(dt / 0.017f));
        float h = dt / sub;
        for (int i = 0; i < 4; i++) {
            int c = tgt[i];
            float[] x = cur[i], v = vel[i];
            for (int ch = 0; ch < 3; ch++) {
                float to = (c >> (16 - 8 * ch)) & 255;
                if (!painted) { x[ch] = to; v[ch] = 0; continue; }
                float before = x[ch];
                for (int n = 0; n < sub; n++) {
                    v[ch] += (OMEGA * OMEGA * (to - x[ch]) - 2 * OMEGA * v[ch]) * h;
                    x[ch] += v[ch] * h;
                }
                jump = Math.max(jump, Math.abs(x[ch] - before));
                if (Math.abs(to - x[ch]) > 0.4f || Math.abs(v[ch]) > 3f) fading = true;
                else { x[ch] = to; v[ch] = 0; }
            }
        }
        painted = true;
        if (Perf.ON) {
            if (fading && !wasFading) { fadeAt = started; fadeSteps = 0; fadeJump = 0; fadeGap = 0; }
            if (fading || wasFading) {
                fadeSteps++;
                fadeJump = Math.max(fadeJump, jump);
                if (wasFading) fadeGap = Math.max(fadeGap, started - prevStep);
            }
            if (wasFading && !fading) {
                AppLog.w("lights fade: " + fadeSteps + " frames in " + (started - fadeAt) + " ms, biggest step "
                        + Math.round(fadeJump * 10) / 10f + "/255, longest gap " + fadeGap + " ms");
            }
            prevStep = started;
        }

        int f = front;
        int b = f < 0 ? 0 : (f + 1) % buf.length;
        canvas.setBitmap(buf[b]);
        canvas.drawRect(0, 0, lw, lh, base);
        float sec = started / 1000f;
        // an analogous palette only: two hues 30° either side of the accent and a deep version of it. Mixing a
        // colour with its opposite (the old fixed teal under an orange accent) is what turned corners olive.
        paint(sec * 0.085f + 0.6f, sec * 0.062f + 1.4f, 0.82f, color(0), ALPHA[0]);
        paint(sec * 0.061f + 2.4f, sec * 0.079f + 0.3f, 0.66f, color(1), ALPHA[1]);
        paint(sec * 0.047f + 4.1f, sec * 0.054f + 3.2f, 0.74f, color(2), ALPHA[2]);
        paint(sec * 0.038f + 5.4f, sec * 0.091f + 2.1f, 0.62f, color(3), ALPHA[3]);
        // and the foot of the screen stays dark: captions and posters read on it
        canvas.drawRect(0, 0, lw, lh, vignette);
        canvas.setBitmap(null);
        front = b;
        generation++;
        view.postInvalidateOnAnimation();

        if (!running || worker == null) return;
        worker.removeCallbacks(step);
        if (fading) {
            if (choreo == null) choreo = Choreographer.getInstance();   // this thread's own (it has a Looper)
            if (!vsyncPosted) {
                vsyncPosted = true;
                choreo.postFrameCallback(vsync);
            }
        } else {
            long spent = SystemClock.uptimeMillis() - started;
            worker.postDelayed(step, Math.max(16, PERIOD_MS - spent));
        }
    }

    private int color(int i) {
        float[] v = cur[i];
        return 0xFF000000 | (clamp8(v[0]) << 16) | (clamp8(v[1]) << 8) | clamp8(v[2]);
    }

    private static int clamp8(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private void paint(float px, float py, float radiusRatio, int color, float alpha) {
        float cx = lw * (0.5f + 0.38f * (float) Math.sin(px));
        float cy = lh * (0.5f + 0.36f * (float) Math.cos(py));
        float r = Math.max(lw, lh) * radiusRatio;
        blob.setShader(new RadialGradient(cx, cy, r, Ui.alpha(color, alpha), Ui.alpha(color, 0f),
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, blob);
        blob.setShader(null);
    }

    /** Push a colour towards the saturated side: an average is always muddy, and muddy glows read as grey. */
    static int vivid(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        // coloured enough to read as light, never as paint: a full-saturation artwork tone stains the whole screen
        hsv[1] = Math.min(0.72f, Math.max(0.42f, hsv[1] * 1.25f));
        hsv[2] = Math.min(0.78f, Math.max(0.42f, hsv[2] * 1.1f + 0.08f));
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static int deep(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[2] *= 0.55f;
        return android.graphics.Color.HSVToColor(hsv);
    }

    static int hue(int color, float deg) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + deg + 360f) % 360f;
        return android.graphics.Color.HSVToColor(hsv);
    }
}
