package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One stream, full screen, with tvOS-like controls ({@link PlayerHud}). Started by the home screen with `url` (and
 * `name`, `logo`). OK pauses, left/right skip 10 s (held: scrub), up reaches the subtitle, audio and format buttons,
 * Back closes what is open and then leaves. A film or an episode comes back where it was left.
 *
 * <p>The engine is Media3 ExoPlayer: hardware decoders, and tunneled playback where the TV has it (the decoder
 * hands frames straight to the TV's video plane, synced to the audio in hardware); seeks use the file's index (one
 * request, not the platform player's back-and-forth between audio and video), and a bounded buffer suits a TV's
 * memory. The picture is a SurfaceView, so it stays on its own hardware plane.
 */
public final class Player extends Activity implements androidx.media3.common.Player.Listener {

    static final String EXTRA_URL = "url", EXTRA_NAME = "name", EXTRA_LOGO = "logo";
    /** From the film's page: "Reprendre au début" (the saved place is ignored), and an episode's series. */
    static final String EXTRA_FROM_START = "fromStart", EXTRA_SERIES = "series";

    private FrameLayout root;
    private SurfaceView surface;
    private PlayerHud hud;
    private ExoPlayer player;
    private String url = "", name = "", logo = "";
    private boolean ready, fill, failed;
    private int videoW, videoH;
    private long resumeMs = -1;
    private String series;        // an episode: its series' id, saved with the position
    private boolean fromStart;   // where to start when the player is built again (back from standby or Home)

    /** A track behind a popover row. */
    private static final class Ref {
        final Tracks.Group group;
        final int index;

        Ref(Tracks.Group group, int index) {
            this.group = group;
            this.index = index;
        }
    }

    // the rows of the three popovers, rebuilt when the tracks change (never while a popover opens)
    private final List<Ref> audioRefs = new ArrayList<>(), textRefs = new ArrayList<>();
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
        series = intent.getStringExtra(EXTRA_SERIES);
        fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false);
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
    }

    /** Another film or channel while this one is open (the activity is single): switch to it. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String next = intent.getStringExtra(EXTRA_URL);
        if (next == null || next.isEmpty() || next.equals(url)) return;
        release();
        setIntent(intent);
        url = next;
        name = intent.getStringExtra(EXTRA_NAME);
        if (name == null) name = "";
        logo = intent.getStringExtra(EXTRA_LOGO);
        if (logo == null) logo = "";
        series = intent.getStringExtra(EXTRA_SERIES);
        fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false);
        resumeMs = -1;
        videoW = videoH = 0;
        java.util.Arrays.fill(rowsOf, null);
        hud.subtitle(null);
        hud.subtitleImages(new Bitmap[0], new RectF[0]);
        if (started) build(0, "");   // still on screen: start it now (otherwise onStart does)
    }

    private boolean started;

    private boolean isLive() {
        return url.contains("/live/");
    }

    private String kind() {
        return url.contains("/movie/") ? "Film" : url.contains("/series/") ? "Série" : isLive() ? "En direct" : "";
    }

    // MARK: - Playback

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (url == null || url.isEmpty() || player != null) return;
        long at = resumeMs >= 0 ? resumeMs : isLive() || fromStart ? 0 : Prefs.positionOf(url);
        fromStart = false;   // once: back from standby, it goes on from where it is
        String note = at > 0 && resumeMs < 0 ? "Reprise à " + PlayerHud.clock(at / 1000) : "";
        build(at, note);
    }

    private String startNote = "";

    private void build(long startMs, String note) {
        ready = false;
        failed = false;
        startNote = note;
        hud.loading(name, kind());

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("FreeMyTV/1.0")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        DefaultTrackSelector selector = new DefaultTrackSelector(this);
        // tunneled playback when the decoder and the audio allow it; the TV's language first; subtitles off
        selector.setParameters(selector.buildUponParameters()
                .setTunnelingEnabled(true)
                .setPreferredAudioLanguages(Locale.getDefault().getLanguage())
                .build());
        // a bounded buffer: 15-50 s ahead, never more than 48 MB (the default allows 128 MB, too much for a TV);
        // playback starts with half a second in hand
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 500, 2_000)
                .setTargetBufferBytes(48 << 20)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(20_000, true)   // the last 20 s stay: a step back plays at once
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, renderers)
                .setTrackSelector(selector)
                .setLoadControl(load)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http, extractors))
                .build();
        player.addListener(this);
        player.setVideoSurfaceView(surface);
        // a jump lands on the nearest keyframe: nothing to decode up to an exact frame, the picture is back at once
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        if (isLive()) player.setMediaItem(MediaItem.fromUri(url));   // a channel starts at its live edge
        else player.setMediaItem(MediaItem.fromUri(url), Math.max(0, startMs));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (player == null) return;
        if (state == androidx.media3.common.Player.STATE_BUFFERING) {
            hud.buffering(true);
        } else if (state == androidx.media3.common.Player.STATE_READY) {
            hud.buffering(false);
            if (!ready) {
                ready = true;
                onReady();
            }
        } else if (state == androidx.media3.common.Player.STATE_ENDED) {
            remember();
            finish();
        }
    }

    private void onReady() {
        long duration = player.getDuration();
        boolean live = isLive() || player.isCurrentMediaItemLive() || duration == C.TIME_UNSET || duration <= 0;
        buildRows();
        String meta = live ? "" : kind();   // a channel: the EN DIRECT badge says it
        if (!live) {
            String length = length(duration);
            meta = meta.isEmpty() ? length : meta + "  ·  " + length;
        }
        hud.prepared(live, live ? 0 : duration, meta, live ? "" : startNote);
    }

    @Override public void onRenderedFirstFrame() { hud.buffering(false); }

    @Override public void onPlayerError(PlaybackException e) {
        if (player != null && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();   // a live stream that fell behind: back to the edge
            player.prepare();
            return;
        }
        failed = true;
        AppLog.w("player: " + e.getErrorCodeName() + " " + e.getMessage());
        hud.fail(e.getErrorCodeName().replace("ERROR_CODE_", "").toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    @Override public void onVideoSizeChanged(VideoSize size) {
        if (size.width <= 0 || size.height <= 0) return;
        videoW = Math.round(size.width * size.pixelWidthHeightRatio);
        videoH = size.height;
        applyAspect();
    }

    @Override public void onTracksChanged(Tracks tracks) { buildRows(); }

    @Override public void onPositionDiscontinuity(androidx.media3.common.Player.PositionInfo oldPosition,
                                                  androidx.media3.common.Player.PositionInfo newPosition,
                                                  int reason) {
        if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) hud.seekDone();
    }

    /** Text cues become lines; picture cues (PGS, DVB) are placed where they belong on the picture. */
    @Override public void onCues(CueGroup group) {
        StringBuilder text = new StringBuilder();
        List<Bitmap> images = new ArrayList<>();
        List<RectF> places = new ArrayList<>();
        float vl = surface.getLeft(), vt = surface.getTop(), vw = surface.getWidth(), vh = surface.getHeight();
        for (Cue cue : group.cues) {
            if (cue.text != null && cue.text.length() > 0) {
                if (text.length() > 0) text.append('\n');
                text.append(cue.text);
            } else if (cue.bitmap != null && vw > 0 && vh > 0) {
                float w = (cue.size == Cue.DIMEN_UNSET ? 0.5f : cue.size) * vw;
                float h = cue.bitmapHeight != Cue.DIMEN_UNSET ? cue.bitmapHeight * vh
                        : w * cue.bitmap.getHeight() / Math.max(1, cue.bitmap.getWidth());
                float x = (cue.position == Cue.DIMEN_UNSET ? 0.5f : cue.position) * vw - anchor(cue.positionAnchor, w);
                float y = cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION
                        ? cue.line * vh - anchor(cue.lineAnchor, h) : vh * 0.92f - h;
                images.add(cue.bitmap);
                places.add(new RectF(vl + x, vt + y, vl + x + w, vt + y + h));
            }
        }
        hud.subtitle(text.length() == 0 ? null : text.toString());
        hud.subtitleImages(images.toArray(new Bitmap[0]), places.toArray(new RectF[0]));
    }

    private static float anchor(int type, float size) {
        return type == Cue.ANCHOR_TYPE_MIDDLE ? size / 2 : type == Cue.ANCHOR_TYPE_END ? size : 0;
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

    // MARK: - For the controls (all on the main thread, where the player lives)

    long currentPosition() {
        return player == null ? 0 : Math.max(0, player.getCurrentPosition());
    }

    long bufferedPosition() {
        return player == null ? 0 : Math.max(0, player.getBufferedPosition());
    }

    boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    /** Returns whether it plays afterwards. */
    boolean togglePause() {
        if (player == null) return false;
        boolean play = !player.getPlayWhenReady();
        player.setPlayWhenReady(play);
        if (!play) remember();
        return play;
    }

    void seek(long ms) {
        if (player != null) player.seekTo(Math.max(0, ms));
    }

    String[] menuRows(int menu) {
        if (menu != PlayerHud.MENU_FORMAT && !ready) return null;
        if (rowsOf[menu] == null) buildRows();
        return rowsOf[menu];
    }

    private void buildRows() {
        audioRefs.clear();
        textRefs.clear();
        List<String> audio = new ArrayList<>(), text = new ArrayList<>();
        if (player != null) {
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                int type = g.getType();
                if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < g.length; i++) {
                    if (!g.isTrackSupported(i)) continue;
                    Format f = g.getTrackFormat(i);
                    if (type == C.TRACK_TYPE_AUDIO) {
                        audio.add(describe(f, audio.size(), true));
                        audioRefs.add(new Ref(g, i));
                    } else {
                        text.add(describe(f, text.size(), false));
                        textRefs.add(new Ref(g, i));
                    }
                }
            }
        }
        rowsOf[PlayerHud.MENU_AUDIO] = distinct(audio.toArray(new String[0]));
        if (text.isEmpty()) {
            rowsOf[PlayerHud.MENU_SUBS] = new String[0];
        } else {
            text.add(0, "Désactivés");
            rowsOf[PlayerHud.MENU_SUBS] = distinct(text.toArray(new String[0]));
        }
        rowsOf[PlayerHud.MENU_FORMAT] = new String[]{"Adapter à l'écran", "Remplir l'écran"};
    }

    int menuChecked(int menu) {
        if (menu == PlayerHud.MENU_FORMAT) return fill ? 1 : 0;
        if (menu == PlayerHud.MENU_AUDIO) {
            for (int i = 0; i < audioRefs.size(); i++) {
                Ref r = audioRefs.get(i);
                if (r.group.isTrackSelected(r.index)) return i;
            }
            return -1;
        }
        if (player == null || player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            return 0;
        }
        for (int i = 0; i < textRefs.size(); i++) {
            Ref r = textRefs.get(i);
            if (r.group.isTrackSelected(r.index)) return i + 1;
        }
        return 0;
    }

    void choose(int menu, int row) {
        if (menu == PlayerHud.MENU_FORMAT) {
            fill = row == 1;
            applyAspect();
            return;
        }
        if (player == null) return;
        TrackSelectionParameters.Builder p = player.getTrackSelectionParameters().buildUpon();
        if (menu == PlayerHud.MENU_AUDIO) {
            if (row < 0 || row >= audioRefs.size()) return;
            Ref r = audioRefs.get(row);
            p.setOverrideForType(new TrackSelectionOverride(r.group.getMediaTrackGroup(), r.index));
        } else if (row == 0) {
            p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            hud.subtitle(null);
            hud.subtitleImages(new Bitmap[0], new RectF[0]);
        } else if (row - 1 < textRefs.size()) {
            Ref r = textRefs.get(row - 1);
            p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(r.group.getMediaTrackGroup(), r.index));
        }
        player.setTrackSelectionParameters(p.build());
    }

    /** "Français · VFF · Dolby Digital Plus 5.1", "Anglais · forcés", "Piste 3". */
    private static String describe(Format f, int index, boolean audio) {
        String lang = language(f.language);
        StringBuilder b = new StringBuilder(lang.isEmpty() ? "Piste " + (index + 1) : lang);
        if (f.label != null && !f.label.trim().isEmpty() && !f.label.trim().equalsIgnoreCase(lang)) {
            b.append("  ·  ").append(f.label.trim());
        }
        if (audio) {
            String codec = codec(f.sampleMimeType);
            int ch = f.channelCount;
            String layout = ch >= 8 ? "7.1" : ch >= 6 ? "5.1" : ch == 2 ? "stéréo" : ch == 1 ? "mono" : "";
            if (!codec.isEmpty() || !layout.isEmpty()) {
                b.append("  ·  ").append(codec);
                if (!layout.isEmpty()) b.append(codec.isEmpty() ? "" : " ").append(layout);
            }
        } else if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0
                && (f.label == null || !f.label.toLowerCase(Locale.ROOT).contains("forc"))) {
            b.append("  ·  forcés");
        }
        return b.toString();
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
        String c = code.trim();
        if (c.isEmpty() || c.equalsIgnoreCase("und") || c.equalsIgnoreCase("unknown")) return "";
        String name = Locale.forLanguageTag(c).getDisplayLanguage(Locale.FRENCH);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(c)) return c.toUpperCase(Locale.ROOT);
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
        if (player == null || !ready || failed) return;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) return;
        Prefs.saveProgress(url, name, logo, player.getCurrentPosition(), duration, series);
    }

    private void release() {
        if (player == null) return;
        remember();
        if (ready && !failed) resumeMs = player.getCurrentPosition();
        player.removeListener(this);
        player.release();
        player = null;
        ready = false;
    }

    @Override protected void onPause() {
        super.onPause();
        remember();
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        release();
    }

    @Override protected void onDestroy() {
        if (hud != null) hud.stopTimers();
        release();
        super.onDestroy();
    }
}
