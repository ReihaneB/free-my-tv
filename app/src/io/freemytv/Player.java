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
    /** A channel of the lineup (its number): the player zaps from it with P+ / P−, the arrows and the digits. */
    static final String EXTRA_CHANNEL = "channel";
    /** From the film's page: "Reprendre au début" (the saved place is ignored), and an episode's series. */
    static final String EXTRA_FROM_START = "fromStart", EXTRA_SERIES = "series";
    /** From the guide: a past programme, played through the channel's replay (its own hours and title). */
    static final String EXTRA_REPLAY_START = "replayStart", EXTRA_REPLAY_STOP = "replayStop",
            EXTRA_REPLAY_TITLE = "replayTitle";

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

    // live TV: the channel on screen, the one before it ("0" / Last), which of its streams plays, and a replay
    private Channels.Channel channel, previous;
    private int stream;
    private Epg.Show replaying;          // "Depuis le début": the programme played from the panel's replay
    private long replayStart;            // from the guide: the past programme to open, in ms (0: none)
    private long segmentAt;              // where the replay's current piece starts (the panel cuts at the minute)
    private long seekAfterReady = -1;
    private Channels.Channel pendingZap;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable zapRun = this::commitZap;

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
        channel = channelOf(intent, url);
        // from the guide, a programme of yesterday evening: the channel's replay, from its first second
        long replayAt = intent.getLongExtra(EXTRA_REPLAY_START, 0);
        if (replayAt > 0 && channel != null && channel.replay != null) {
            long stop = intent.getLongExtra(EXTRA_REPLAY_STOP, replayAt + 3 * 3600_000L);
            String title = intent.getStringExtra(EXTRA_REPLAY_TITLE);
            replaying = new Epg.Show(replayAt, stop, title == null ? name : title, "");
            replayStart = replayAt;
        }

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
        hud.channel(channel, null);
    }

    private Channels.Channel channelOf(Intent intent, String u) {
        Channels lineup = Channels.get(this);
        int number = intent.getIntExtra(EXTRA_CHANNEL, 0);
        Channels.Channel c = number > 0 ? lineup.byNumber(number) : null;
        if (c == null) c = lineup.byUrl(u);
        if (c != null) {
            Iptv.Item s = findStream(c, u);
            stream = s == null ? 0 : c.streams.indexOf(s);
            Prefs.setLastChannel(c.number);
        }
        return c;
    }

    private static Iptv.Item findStream(Channels.Channel c, String u) {
        for (Iptv.Item i : c.streams) if (i.url.equals(u)) return i;
        return null;
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
        replaying = null;
        replayStart = 0;
        channel = channelOf(intent, url);
        java.util.Arrays.fill(rowsOf, null);
        hud.subtitle(null);
        hud.subtitleImages(new Bitmap[0], new RectF[0]);
        if (started) {   // still on screen: start it now (otherwise onStart does)
            build(0, "");
            hud.channel(channel, null);
        }
    }

    private Iptv.Episode nextEpisode;   // the episode after this one, once the panel has told
    private boolean nextAsked;
    private final Runnable episodeTick = this::episodeTickRun;

    // MARK: - Live TV: zapping, the previous channel, another stream when one fails, a programme from its start

    Channels.Channel channel() { return channel; }

    Epg.Show replaying() { return replaying; }

    /** P+ / P− and the arrows: the banner follows each press at once, the stream once the remote rests. */
    void zap(int step) {
        Channels.Channel from = pendingZap != null ? pendingZap : channel;
        if (from == null) return;
        Channels.Channel to = Channels.get(this).step(from, step);
        if (to != null) zapLater(to);
    }

    /** The digits, "0" and the list: straight to that channel. */
    boolean zapTo(int number) {
        Channels.Channel to = Channels.get(this).byNumber(number);
        if (to == null) return false;
        zapNow(to);
        return true;
    }

    void zapTo(Channels.Channel to) { zapNow(to); }

    void zapPrevious() {
        if (previous != null) zapNow(previous);
    }

    private void zapLater(Channels.Channel to) {
        pendingZap = to;
        hud.channel(to, null);
        main.removeCallbacks(zapRun);
        main.postDelayed(zapRun, 420);
    }

    private void zapNow(Channels.Channel to) {
        pendingZap = to;
        main.removeCallbacks(zapRun);
        commitZap();
    }

    private void commitZap() {
        Channels.Channel to = pendingZap;
        pendingZap = null;
        if (to == null) return;
        if (to == channel && replaying == null) {
            hud.channel(channel, null);
            return;
        }
        if (to != channel) previous = channel;
        channel = to;
        stream = 0;
        replaying = null;
        Prefs.setLastChannel(to.number);
        play(to.url(), to.name, to.logo);
    }

    /** The programme on now, from its start: the panel's replay of this channel, seekable like a film. */
    boolean startOver() {
        Channels.Channel c = channel;
        if (c == null || c.replay == null) return false;
        long now = System.currentTimeMillis();
        Epg.Show s = replaying != null ? replaying : Epg.get(this).now(c.epg, now);
        if (s == null) return false;
        replaying = s;
        replayFrom(s.start);
        return true;
    }

    /**
     * The replay from `at`, in pieces. Asked for past what is already recorded, the panel sends at the recording's
     * pace (0.9x measured: the picture stalls); asked for whole minutes already broadcast, it sends 5x faster and
     * closes. So each piece stops a little before now, and the next one is asked for when it ends: the programme
     * goes on a minute or two behind the live, without a stall.
     */
    private void replayFrom(long at) {
        Channels.Channel c = channel;
        if (c == null || c.replay == null || replaying == null) return;
        long start = at - at % 60_000;   // the address is written to the minute
        long recorded = Math.min(replaying.stop, System.currentTimeMillis() - 30_000) - start;
        long minutes = Math.max(1, Math.min((replaying.stop - start + 59_999) / 60_000, recorded / 60_000));
        segmentAt = start;
        seekAfterReady = at - start > 1500 ? at - start : -1;
        String u = Iptv.get(this).replayUrl(c.replay, start, minutes * 60_000);
        play(u, replaying.title, c.logo);
    }

    /** The end of a stream: a replay goes on with its next piece (or back to the live); a channel reconnects. */
    private boolean endedOn() {
        if (replaying != null && player != null) {
            long reached = segmentAt + player.getCurrentPosition();
            long now = System.currentTimeMillis();
            if (reached < replaying.stop - 5_000 && now - reached > 45_000) replayFrom(reached);
            else backToLive();
            return true;
        }
        if (channel != null && player != null && isLive()) {   // a live stream that stopped: take it again
            player.seekToDefaultPosition();
            player.prepare();
            return true;
        }
        return false;
    }

    /** Back from a replay: the channel live again. */
    private boolean backToLive() {
        if (replaying == null || channel == null) return false;
        replaying = null;
        seekAfterReady = -1;
        play(channel.url(), channel.name, channel.logo);
        return true;
    }

    /** Another stream on the same player (no new decoder pipeline to build: faster, and the picture stays put). */
    private void play(String u, String n, String l) {
        url = u;
        name = n == null ? "" : n;
        logo = l == null ? "" : l;
        series = null;
        resumeMs = -1;
        java.util.Arrays.fill(rowsOf, null);
        hud.subtitle(null);
        hud.subtitleImages(new Bitmap[0], new RectF[0]);
        hud.loading(name, kind());
        hud.channel(channel, replaying);
        if (player == null) {
            if (started) build(0, "");
            return;
        }
        ready = false;
        failed = false;
        startNote = "";
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /** A channel's stream that fails: its next one (the panel lists 3-7 per channel), before saying it fails. */
    private boolean nextStream() {
        Channels.Channel c = channel;
        if (c == null || replaying != null || stream + 1 >= c.streams.size()) return false;
        stream++;
        Iptv.Item s = c.streams.get(stream);
        AppLog.w("player: " + c.name + " stream " + stream + "/" + c.streams.size());
        url = s.url;
        ready = false;
        failed = false;
        if (player != null) {
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.setPlayWhenReady(true);
        }
        return true;
    }

    private boolean started;

    private boolean isLive() {
        return url.contains("/live/");
    }

    private String kind() {
        return url.contains("/movie/") ? "Film" : url.contains("/series/") ? "Série" : isLive() ? "En direct"
                : url.contains("/timeshift/") ? "Replay" : "";
    }

    // MARK: - Playback

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (url == null || url.isEmpty() || player != null) return;
        if (replayStart > 0) {   // a programme picked in the guide: its replay, not the live stream
            long at = replayStart;
            replayStart = 0;
            replayFrom(at);
            return;
        }
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

        androidx.media3.datasource.DefaultHttpDataSource.Factory http =
                new androidx.media3.datasource.DefaultHttpDataSource.Factory()
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
                if (seekAfterReady > 0) {   // a replay's next piece: from the second it was left
                    player.seekTo(seekAfterReady);
                    seekAfterReady = -1;
                }
            }
        } else if (state == androidx.media3.common.Player.STATE_ENDED) {
            if (nextEpisode != null) {   // an episode of a series: the next one goes on by itself
                playNext();
                return;
            }
            if (endedOn()) return;
            remember();
            finish();
        }
    }

    private void onReady() {
        if (replaying != null) {   // a replay: the bar is the programme's, whatever piece plays
            buildRows();
            hud.prepared(false, replaying.stop - replaying.start, "Depuis le début  ·  " + Epg.span(replaying), "");
            return;
        }
        long duration = player.getDuration();
        boolean live = isLive() || player.isCurrentMediaItemLive() || duration == C.TIME_UNSET || duration <= 0;
        buildRows();
        startEpisodeTicker(live);
        String meta = live ? "" : kind();   // a channel: the EN DIRECT badge says it
        if (!live) {
            String length = length(duration);
            meta = meta.isEmpty() ? length : meta + "  ·  " + length;
        }
        hud.prepared(live, live ? 0 : duration, meta, live ? "" : startNote);
    }

    @Override public void onRenderedFirstFrame() { hud.buffering(false); }

    // MARK: - The next episode

    /** Every second or so: near the end, the next episode is asked for, counted down, then played. */
    private void startEpisodeTicker(boolean live) {
        main.removeCallbacks(episodeTick);
        nextEpisode = null;
        nextAsked = false;
        if (live || series == null || series.isEmpty()) return;
        main.postDelayed(episodeTick, 1000);
    }

    private void episodeTickRun() {
        if (player == null || series == null || series.isEmpty() || !ready || isLive()) return;
        long dur = player.getDuration();
        if (dur <= 0) {
            main.postDelayed(episodeTick, 1000);
            return;
        }
        long left = dur - player.getCurrentPosition();
        if (left <= 30_000) {
            if (!nextAsked) {
                nextAsked = true;
                askNextEpisode();
            }
            if (nextEpisode != null) hud.nextCountdown((int) ((Math.max(0, left) + 999) / 1000));
        }
        if (left <= 700 && nextEpisode != null) {   // the end: it goes on by itself
            playNext();
            return;
        }
        if (left > 0) main.postDelayed(episodeTick, 700);
    }

    /**
     * The episode after this one, from the panel's episode list (sorted by season then number, so an episode that
     * ends a season is followed by the first of the next one). Off the UI thread: the list may need a request.
     */
    private void askNextEpisode() {
        final String id = series, here = url;
        new Thread(() -> {
            Iptv.Detail d = Iptv.get(this).series(id);
            Iptv.Episode found = null;
            if (d != null && !d.episodes.isEmpty()) {
                List<Iptv.Episode> all = new ArrayList<>(d.episodes);
                all.sort((a, b) -> a.season != b.season ? Integer.compare(a.season, b.season)
                        : Integer.compare(a.number, b.number));
                for (int i = 0; i < all.size(); i++) {
                    if (!all.get(i).url.equals(here)) continue;
                    if (i + 1 < all.size()) found = all.get(i + 1);
                    break;
                }
            }
            final Iptv.Episode n = found;
            main.post(() -> {
                nextEpisode = n;
                if (n != null && nextAsked && player != null && !isLive()) {
                    String t = n.title == null || n.title.isEmpty() ? "Épisode " + n.number : n.title;
                    hud.next(t, seriesName() + "  ·  S" + n.season + ", É" + n.number);
                }
            });
        }, "next-episode").start();
    }

    /** « Série · S1, É2 » → « Série ». */
    private String seriesName() {
        int cut = name == null ? -1 : name.indexOf(" · S");
        return cut > 0 ? name.substring(0, cut) : (name == null ? "" : name);
    }

    /** « Passer le générique », or the countdown reaching zero: the next episode, now. */
    void playNext() {
        Iptv.Episode ep = nextEpisode;
        if (ep == null) return;
        nextEpisode = null;
        nextAsked = true;
        hud.clearNext();
        remember();                                  // this one is over: it is marked as watched
        Intent it = new Intent(this, Player.class);
        it.putExtra(EXTRA_URL, ep.url);
        it.putExtra(EXTRA_NAME, seriesName() + " · S" + ep.season + ", É" + ep.number);
        it.putExtra(EXTRA_LOGO, logo);
        it.putExtra(EXTRA_SERIES, series);
        it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
    }

    /** Back on the next-episode card: it is put away and this episode simply ends. */
    void cancelNext() {
        nextEpisode = null;
        nextAsked = true;
    }

    @Override public void onPlayerError(PlaybackException e) {
        if (player != null && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();   // a live stream that fell behind: back to the edge
            player.prepare();
            return;
        }
        AppLog.w("player: " + e.getErrorCodeName() + " " + e.getMessage());
        // the panel refuses a stream past the account's two connections with an empty text/html page: ask it plainly
        final String why = e.getErrorCodeName().replace("ERROR_CODE_", "").toLowerCase(Locale.ROOT).replace('_', ' ');
        final String probed = url;
        new Thread(() -> {
            boolean refused = looksRefused(probed);
            main.post(() -> {
                if (refused) {
                    failed = true;
                    hud.refused();
                    return;
                }
                if (nextStream()) return;
                failed = true;
                hud.fail(why);
            });
        }, "panel-probe").start();
        return;
    }

    /**
     * The panel's answer when the account's two connections are already in use: HTTP 200 with a text/html page and no
     * body, where a stream is video/mp2t (measured 28/10). One short request tells them apart from a real failure.
     */
    private static boolean looksRefused(String u) {
        if (u == null || u.isEmpty()) return false;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
            c.setRequestProperty("Range", "bytes=0-1");
            int code = c.getResponseCode();
            String type = c.getContentType();
            return code == 200 && type != null && type.toLowerCase(Locale.ROOT).contains("text/html");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
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
        if (player == null) return 0;
        long p = seekAfterReady > 0 ? seekAfterReady : Math.max(0, player.getCurrentPosition());
        return replaying != null ? segmentAt - replaying.start + p : p;
    }

    long bufferedPosition() {
        if (player == null) return 0;
        long b = Math.max(0, player.getBufferedPosition());
        return replaying != null ? segmentAt - replaying.start + b : b;
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
        if (player == null) return;
        if (replaying == null) {
            player.seekTo(Math.max(0, ms));
            return;
        }
        // a replay: in the piece that plays, or a new piece from there (never past what is recorded)
        long at = Math.min(replaying.start + Math.max(0, ms), System.currentTimeMillis() - 90_000);
        at = Math.max(replaying.start, at);
        long piece = player.getDuration();
        if (at >= segmentAt && piece != C.TIME_UNSET && at < segmentAt + piece - 2_000) player.seekTo(at - segmentAt);
        else replayFrom(at);
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
        if (backToLive()) return;
        super.onBackPressed();
    }

    /** OK on the refusal screen: ask the panel again for the same stream, without leaving the player. */
    void retry() {
        if (player == null) return;
        failed = false;
        ready = false;
        hud.loading(name, kind());
        hud.channel(channel, replaying);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /** Remembers where this stream stopped, so the film or episode comes back where it was left. */
    private void remember() {
        if (player == null || !ready || failed || url.contains("/timeshift/")) return;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET) return;
        Prefs.saveProgress(url, name, logo, player.getCurrentPosition(), duration, series);
    }

    private void release() {
        if (player == null) return;
        main.removeCallbacks(episodeTick);
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
        main.removeCallbacks(zapRun);
        if (hud != null) hud.stopTimers();
        release();
        super.onDestroy();
    }
}
