package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process, and keeps the playlist fresh from the main one. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Pacing.ensure(this);   // one more display buffer: smooth animations over a video (see Pacing)
        Iptv.get(this).refreshIfStale();
    }
}
