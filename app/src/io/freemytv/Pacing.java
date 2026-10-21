package io.freemytv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * One more display buffer for every window of the app, so animations keep a steady 60 fps over a video.
 *
 * <p>When the picture goes straight to the display (the player: the video plane plus the controls, both composed by
 * the TV's display hardware), this TV hands a buffer back a frame late. With the usual three buffers the render
 * thread then waits about 15 ms for one every frame, and the controls' animations fall to 30-50 fps whatever they
 * draw. A fourth buffer removes the wait (measured: buffer wait 15 ms → 0.3 ms, frame interval p90 34 → 17 ms).
 *
 * <p>Android takes it from the `debug.hwui.render_ahead` property, which only the shell may set, and which a
 * reboot clears. So at each start the app sets it through its own ADB link — only when that link is already
 * allowed (Free My TV mode), never making the TV ask — then tells every process to reload its properties: the
 * windows created from then on (the player's) get the extra buffer.
 */
final class Pacing {
    private Pacing() {}

    static final String PROP = "debug.hwui.render_ahead";
    private static volatile boolean started;

    static void ensure(Context context) {
        if (started) return;
        started = true;
        Context app = context.getApplicationContext();
        Thread t = new Thread(() -> run(app), "pacing");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    private static void run(Context app) {
        // early in a boot the TV's ADB may not listen yet: a few tries, spaced out
        for (int attempt = 0; attempt < 4; attempt++) {
            if ("1".equals(get())) return;
            if (!HomeMode.FREE.equals(HomeMode.state(app).optString("mode"))) return;   // ADB not set up: nothing
            try (Adb adb = Adb.connect(app, 0, null)) {
                adb.shell("setprop " + PROP + " 1; service call activity 1599295570 >/dev/null");
                AppLog.w("pacing: one more display buffer (" + PROP + "=" + get() + ")");
                return;
            } catch (IOException e) {
                AppLog.w("pacing: " + e.getMessage());
            }
            try {
                Thread.sleep(15_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String get() {
        try {
            Process p = new ProcessBuilder("getprop", PROP).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line == null ? "" : line.trim();
            } finally {
                p.destroy();
            }
        } catch (IOException e) {
            return "";
        }
    }
}
