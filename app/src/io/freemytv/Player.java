package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * One stream, full screen. Started by the home screen with `url` (and `name`): the player keeps the remote's own keys —
 * OK pauses, Back leaves — and nothing else gets in the way.
 */
public final class Player extends Activity implements SurfaceHolder.Callback, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener {

    static final String EXTRA_URL = "url", EXTRA_NAME = "name", EXTRA_LOGO = "logo";

    private SurfaceView surface;
    private TextView title, status;
    private MediaPlayer player;
    private String url = "", name = "", logo = "";
    private boolean prepared, paused;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent intent = getIntent();
        url = intent.getStringExtra(EXTRA_URL);
        name = intent.getStringExtra(EXTRA_NAME);
        if (name == null) name = "";
        logo = intent.getStringExtra(EXTRA_LOGO);
        if (logo == null) logo = "";
        if (url == null || url.isEmpty()) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        title = new TextView(this);
        title.setText(name == null ? "" : name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setPadding(dp(28), dp(20), dp(28), dp(20));
        title.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        root.addView(title, tl);

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(18);
        status.setPadding(dp(28), dp(20), dp(28), dp(24));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        root.addView(status, bl);

        setContentView(root);
        surface.getHolder().addCallback(this);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        player.setOnPreparedListener(this);
        player.setOnErrorListener(this);
        player.setOnInfoListener(this);
        player.setDisplay(holder);
        try {
            player.setDataSource(url);
            status.setText(Ui.tr("Abrindo…", "Opening…"));
            player.prepareAsync();
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { release(); }

    @Override public void onPrepared(MediaPlayer mp) {
        prepared = true;
        status.setText("");
        mp.start();
    }

    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
        fail("what=" + what + " extra=" + extra);
        return true;
    }

    @Override public boolean onInfo(MediaPlayer mp, int what, int extra) { return false; }

    private void fail(String why) {
        AppLog.w("player: " + why);
        status.setText(Ui.tr("Ce flux ne s'ouvre pas.", "This stream won't open.") + "  " + why);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (player != null && prepared) {
                if (player.isPlaying()) { player.pause(); paused = true; remember(); }
                else { player.start(); paused = false; }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Remembers where this stream stopped, so the film or episode comes back where it was left. */
    private void remember() {
        if (player == null || !prepared) return;
        try {
            Prefs.saveProgress(url, name, logo, player.getCurrentPosition(), player.getDuration());
        } catch (IllegalStateException ignored) { }
    }

    private void release() {
        prepared = false;
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
    }

    /** The surface is gone by the time onStop runs: the position has to be taken here. */
    @Override protected void onPause() {
        super.onPause();
        remember();
    }

    @Override protected void onStop() {
        super.onStop();
        remember();
        if (!paused) release();
        if (isFinishing()) finish();
    }

    @Override protected void onDestroy() {
        release();
        super.onDestroy();
    }
}
