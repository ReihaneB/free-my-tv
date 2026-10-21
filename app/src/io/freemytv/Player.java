package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.TimedText;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One stream, full screen, with tvOS-like controls ({@link PlayerHud}). Started by the home screen with `url` (and
 * `name`, `logo`). OK pauses, left/right skip 10 s (held: scrub), up reaches the subtitle, audio and format buttons,
 * Back closes what is open and then leaves. A film or an episode comes back where it was left.
 */
public final class Player extends Activity implements SurfaceHolder.Callback, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener, MediaPlayer.OnVideoSizeChangedListener,
        MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnTimedTextListener, MediaPlayer.OnSeekCompleteListener,
        MediaPlayer.OnCompletionListener {

    static final String EXTRA_URL = "url", EXTRA_NAME = "name", EXTRA_LOGO = "logo";

    private FrameLayout root;
    private SurfaceView surface;
    private PlayerHud hud;
    private MediaPlayer player;
    private String url = "", name = "", logo = "";
    private boolean prepared, fill;
    private int videoW, videoH;

    // track indices (in getTrackInfo()) behind each popover row, and the rows themselves: read once, when the
    // stream is ready, so opening a popover costs nothing
    private final List<Integer> audioTracks = new ArrayList<>(), textTracks = new ArrayList<>();
    private final String[][] rowsOf = new String[3][];

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

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        hud = new PlayerHud(this, this);
        root.addView(hud, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::applyAspect);
        });
        setContentView(root);
        hud.loading(name, kind());
        surface.getHolder().addCallback(this);
    }

    private boolean isLive() {
        return url.contains("/live/");
    }

    private String kind() {
        return url.contains("/movie/") ? "Film" : url.contains("/series/") ? "Série" : isLive() ? "En direct" : "";
    }

    // MARK: - Playback

    @Override public void surfaceCreated(SurfaceHolder holder) {
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        player.setOnPreparedListener(this);
        player.setOnErrorListener(this);
        player.setOnInfoListener(this);
        player.setOnVideoSizeChangedListener(this);
        player.setOnBufferingUpdateListener(this);
        player.setOnTimedTextListener(this);
        player.setOnSeekCompleteListener(this);
        player.setOnCompletionListener(this);
        player.setDisplay(holder);
        player.setScreenOnWhilePlaying(true);
        try {
            player.setDataSource(url);
            hud.loading(name, kind());
            player.prepareAsync();
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { release(); }

    @Override public void onPrepared(MediaPlayer mp) {
        prepared = true;
        long duration = mp.getDuration();
        boolean live = isLive() || duration <= 0;
        readTracks();
        buildRows();
        String meta = kind();
        String note = "";
        long at = 0;
        if (!live) {
            String length = length(duration);
            meta = meta.isEmpty() ? length : meta + "  ·  " + length;
            at = Prefs.positionOf(url);
            if (at > 0 && at < duration - 30_000) note = "Reprise à " + PlayerHud.clock(at / 1000);
            else at = 0;
        }
        // started first, then sent to where it was left: a seek before start() is dropped by this TV's player
        mp.start();
        hud.prepared(live, live ? 0 : duration, meta, note);
        if (at > 0) hud.resumeAt(at);
    }

    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
        fail("erreur " + what + " / " + extra);
        return true;
    }

    @Override public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) hud.buffering(true);
        else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
            hud.buffering(false);
        return false;
    }

    @Override public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        videoW = width;
        videoH = height;
        applyAspect();
    }

    @Override public void onBufferingUpdate(MediaPlayer mp, int percent) { hud.bufferedPct(percent); }

    @Override public void onTimedText(MediaPlayer mp, TimedText text) {
        hud.subtitle(text == null ? null : text.getText());
    }

    @Override public void onSeekComplete(MediaPlayer mp) {
        hud.seekDone();
    }

    @Override public void onCompletion(MediaPlayer mp) {
        remember();
        finish();
    }

    private void fail(String why) {
        AppLog.w("player: " + why);
        hud.fail(why);
    }

    /** The picture keeps its proportions: fitted inside the screen, or filling it (cropped) when asked. */
    private void applyAspect() {
        if (surface == null || root == null) return;
        int sw = root.getWidth(), sh = root.getHeight();
        FrameLayout.LayoutParams lp;
        if (videoW <= 0 || videoH <= 0 || sw <= 0 || sh <= 0) {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        } else {
            float s = fill ? Math.max((float) sw / videoW, (float) sh / videoH)
                    : Math.min((float) sw / videoW, (float) sh / videoH);
            lp = new FrameLayout.LayoutParams(Math.round(videoW * s), Math.round(videoH * s), Gravity.CENTER);
        }
        ViewGroup.LayoutParams old = surface.getLayoutParams();
        if (old != null && old.width == lp.width && old.height == lp.height) return;
        surface.setLayoutParams(lp);
    }

    // MARK: - For the controls

    long currentPosition() {
        if (player == null || !prepared) return 0;
        try {
            return player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Returns whether it plays afterwards. */
    boolean togglePause() {
        if (player == null || !prepared) return false;
        try {
            if (player.isPlaying()) {
                player.pause();
                remember();
                return false;
            }
            player.start();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    void seek(long ms) {
        if (player == null || !prepared) return;
        try {
            player.seekTo(ms, MediaPlayer.SEEK_CLOSEST_SYNC);
        } catch (IllegalStateException ignored) {
        }
    }

    String[] menuRows(int menu) {
        if (menu != PlayerHud.MENU_FORMAT && !prepared) return null;
        if (rowsOf[menu] == null) buildRows();
        return rowsOf[menu];
    }

    private void buildRows() {
        for (int m = 0; m < rowsOf.length; m++) rowsOf[m] = rows(m);
    }

    private String[] rows(int menu) {
        if (menu == PlayerHud.MENU_FORMAT) return new String[]{"Adapter à l'écran", "Remplir l'écran"};
        if (!prepared) return new String[0];
        MediaPlayer.TrackInfo[] info = tracks();
        if (menu == PlayerHud.MENU_AUDIO) {
            String[] out = new String[audioTracks.size()];
            for (int i = 0; i < out.length; i++) out[i] = describe(info[audioTracks.get(i)], i, true);
            return distinct(out);
        }
        if (textTracks.isEmpty()) return new String[0];
        String[] out = new String[textTracks.size() + 1];
        out[0] = "Désactivés";
        for (int i = 0; i < textTracks.size(); i++) out[i + 1] = describe(info[textTracks.get(i)], i, false);
        return distinct(out);
    }

    int menuChecked(int menu) {
        if (menu == PlayerHud.MENU_FORMAT) return fill ? 1 : 0;
        if (player == null || !prepared) return -1;
        try {
            if (menu == PlayerHud.MENU_AUDIO) {
                return audioTracks.indexOf(player.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO));
            }
            int sel = player.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            return sel < 0 ? 0 : textTracks.indexOf(sel) + 1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    void choose(int menu, int row) {
        if (menu == PlayerHud.MENU_FORMAT) {
            fill = row == 1;
            applyAspect();
            return;
        }
        if (player == null || !prepared) return;
        try {
            if (menu == PlayerHud.MENU_AUDIO) {
                if (row >= 0 && row < audioTracks.size()) player.selectTrack(audioTracks.get(row));
                return;
            }
            int sel = player.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            if (row == 0) {
                if (sel >= 0) player.deselectTrack(sel);
                hud.subtitle(null);
            } else if (row - 1 < textTracks.size()) {
                player.selectTrack(textTracks.get(row - 1));
            }
        } catch (RuntimeException e) {
            AppLog.w("player: track " + e);
        }
    }

    private MediaPlayer.TrackInfo[] tracks() {
        try {
            MediaPlayer.TrackInfo[] t = player == null ? null : player.getTrackInfo();
            return t == null ? new MediaPlayer.TrackInfo[0] : t;
        } catch (RuntimeException e) {
            return new MediaPlayer.TrackInfo[0];
        }
    }

    private void readTracks() {
        audioTracks.clear();
        textTracks.clear();
        MediaPlayer.TrackInfo[] info = tracks();
        for (int i = 0; i < info.length; i++) {
            int type = info[i].getTrackType();
            if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) audioTracks.add(i);
            else if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) textTracks.add(i);
        }
    }

    /** "Français · Dolby Digital Plus", "Anglais · AAC", "Piste 3". */
    private static String describe(MediaPlayer.TrackInfo t, int index, boolean audio) {
        String lang = language(t.getLanguage());
        String label = lang.isEmpty() ? "Piste " + (index + 1) : lang;
        if (audio) {
            try {
                MediaFormat f = t.getFormat();
                String codec = f == null ? "" : codec(f.getString(MediaFormat.KEY_MIME));
                if (!codec.isEmpty()) label += "  ·  " + codec;
                if (f != null && f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    int ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    String layout = ch >= 8 ? "7.1" : ch >= 6 ? "5.1" : ch == 2 ? "stéréo" : ch == 1 ? "mono" : "";
                    if (!layout.isEmpty()) label += " " + layout;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return label;
    }

    private static String codec(String mime) {
        if (mime == null) return "";
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "audio/eac3": case "audio/eac3-joc": return "Dolby Digital Plus";
            case "audio/ac3": return "Dolby Digital";
            case "audio/ac4": return "Dolby AC-4";
            case "audio/true-hd": return "Dolby TrueHD";
            case "audio/mp4a-latm": case "audio/aac": return "AAC";
            case "audio/vnd.dts": case "audio/dts": return "DTS";
            case "audio/vnd.dts.hd": return "DTS-HD";
            case "audio/mpeg": return "MP3";
            case "audio/opus": return "Opus";
            case "audio/vorbis": return "Vorbis";
            case "audio/flac": return "FLAC";
            default: return "";
        }
    }

    /** Two rows that read the same get their track number, so they can be told apart. */
    private static String[] distinct(String[] rows) {
        for (int i = 0; i < rows.length; i++) {
            int n = 0;
            for (String r : rows) if (r.equals(rows[i])) n++;
            if (n < 2) continue;
            String same = rows[i];
            int count = 0;
            for (int j = 0; j < rows.length; j++) {
                if (rows[j].equals(same)) rows[j] = same + "  ·  " + (++count);
            }
        }
        return rows;
    }

    private static String language(String code) {
        if (code == null) return "";
        String c = code.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty() || c.equals("und") || c.equals("unknown") || c.equals("mis") || c.equals("zxx")) return "";
        // ISO 639-2/B codes Locale does not know
        switch (c) {
            case "fre": c = "fra"; break;
            case "ger": c = "deu"; break;
            case "dut": c = "nld"; break;
            case "chi": c = "zho"; break;
            case "per": c = "fas"; break;
            case "cze": c = "ces"; break;
            case "gre": c = "ell"; break;
            case "rum": c = "ron"; break;
            case "slo": c = "slk"; break;
            case "arm": c = "hye"; break;
            case "geo": c = "kat"; break;
            case "ice": c = "isl"; break;
            case "mac": c = "mkd"; break;
            case "may": c = "msa"; break;
            case "alb": c = "sqi"; break;
            case "baq": c = "eus"; break;
            case "wel": c = "cym"; break;
            default: break;
        }
        String name = new Locale(c).getDisplayLanguage(Locale.FRENCH);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(c)) return code.toUpperCase(Locale.ROOT);
        return name.substring(0, 1).toUpperCase(Locale.FRENCH) + name.substring(1);
    }

    /** "1 h 52", "42 min". */
    private static String length(long ms) {
        long min = Math.round(ms / 60_000.0);
        if (min < 60) return min + " min";
        long m = min % 60;
        return (min / 60) + " h" + (m == 0 ? "" : " " + (m < 10 ? "0" : "") + m);
    }

    // MARK: - Keys and lifecycle

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK && hud != null && hud.key(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (hud != null && hud.back()) return;
        super.onBackPressed();
    }

    /** Remembers where this stream stopped, so the film or episode comes back where it was left. */
    private void remember() {
        if (player == null || !prepared) return;
        try {
            Prefs.saveProgress(url, name, logo, player.getCurrentPosition(), player.getDuration());
        } catch (IllegalStateException ignored) { }
    }

    private void release() {
        if (player != null) {
            remember();
            prepared = false;
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
        prepared = false;
    }

    /** The surface is gone by the time onStop runs: the position has to be taken here. */
    @Override protected void onPause() {
        super.onPause();
        remember();
    }

    @Override protected void onStop() {
        super.onStop();
        release();
    }

    @Override protected void onDestroy() {
        if (hud != null) hud.stopTimers();
        release();
        super.onDestroy();
    }
}
