package io.freemytv;

import java.util.Arrays;

/**
 * Frame timing for the home screen, off by default. When ON, it logs (adb logcat -s FreeMyTV) the gap between two
 * Choreographer callbacks while an animation is running — the only honest fluidity figure on this TV, gfxinfo also
 * counts loading frames — and the time onDraw spends on the UI thread.
 */
final class Perf {
    private Perf() {}

    static final boolean ON = false;

    /** Ablation switches for measuring (adb shell settings put global freemytv_skip <bits>), only read when ON. */
    static int skip;
    static final int NO_BACKDROP = 1, NO_CLIP = 2, NO_ART = 4, NO_TEXT = 8, NO_BAR = 16, NO_SCRIM = 32, NO_RIM = 64;
    /** The home presses its own arrows (4 a second): `input keyevent` starts a JVM per key, which itself janks. */
    static final int AUTO = 128;
    /** Draws a flat colour and nothing else: the platform's own cost per frame. */
    static final int NOTHING = 256;
    static final int[] DRIVE = {22, 22, 22, 22, 22, 22, 20, 20, 20, 21, 21, 21, 21, 22, 22, 22, 19, 19, 19, 19,
            22, 22, 22, 22, 22, 22, 22, 21, 21, 21, 21, 21, 21, 21, 20};

    static void readSkip(android.content.Context c) {
        if (!ON) return;
        skip = android.provider.Settings.Global.getInt(c.getContentResolver(), "freemytv_skip", 0);
        AppLog.w("perf skip=" + skip);
    }

    static boolean off(int bit) {
        return ON && (skip & bit) != 0;
    }

    private static final int N = 120;
    private static final long[] gaps = new long[N];
    private static final long[] draws = new long[N];
    private static int gi, di;
    private static long last;

    /** A Choreographer callback; `animating` = the previous frame asked for this one. */
    static void frame(long nanos, boolean animating) {
        if (animating && last != 0) {
            gaps[gi++] = nanos - last;
            if (gi == N) {
                report();
                gi = 0;
            }
        }
        last = nanos;
    }

    static void draw(long nanos) {
        draws[di] = nanos;
        di = (di + 1) % N;
    }

    private static void report() {
        long[] g = gaps.clone();
        Arrays.sort(g);
        long[] d = draws.clone();
        Arrays.sort(d);
        long sum = 0;
        for (long v : d) sum += v;
        AppLog.w(String.format(java.util.Locale.ROOT,
                "perf gap p50 %.1f p90 %.1f p99 %.1f max %.1f ms | onDraw avg %.2f p90 %.2f max %.2f ms",
                g[N / 2] / 1e6, g[N * 9 / 10] / 1e6, g[N * 99 / 100] / 1e6, g[N - 1] / 1e6,
                sum / (double) N / 1e6, d[N * 9 / 10] / 1e6, d[N - 1] / 1e6));
    }
}
