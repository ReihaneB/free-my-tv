package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process, and keeps the playlist fresh from the main one. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Iptv.get(this).refreshIfStale();
    }
}
