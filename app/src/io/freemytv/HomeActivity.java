package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 *
 * <p>The search field of the top bar is a real (transparent) EditText laid over the pill the home screen draws, so the
 * TV's keyboard can type into it; the home screen hides it and shows the account's results in its own shelves.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private EditText search;
    private FrameLayout root;
    private DetailView detail;      // a film's or a series' page, over the home screen
    private GuideView guide;        // le guide, over the home screen (GUIDE)
    private boolean resumed, searchHidden;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over
    private long lastKeyAt;                     // dernier appui sur la télécommande
    private int bootRecoveries;                 // l'accueil repris au tuner après le boot (2 au maximum)

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("O Início do Free My TV teve um problema.\nVoltando para o Início original da TV…",
                    "Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);      // this screen is the home, as it always was

        root = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFF9AA8AE);
        search.setHint("Rechercher");
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22));
        search.setPadding(px(54), 0, px(22), 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(300), px(58));
        lp.leftMargin = px(108);   // exactly over the search well: 96 (margin) + 12 (inside the plate)
        lp.topMargin = px(36);   // BAR_TOP
        root.addView(search, lp);
        home.setSearchField(search);
        detail = new DetailView(this, new DetailView.Host() {
            @Override public void covered() {
                home.pause();
                home.setVisibility(View.INVISIBLE);   // under the page: not drawn, not animated
                // the search box stays typed in (Back comes back to the results) but its caret must not blink under
                // the page: each blink redraws the whole screen
                searchHidden = search.getVisibility() == View.VISIBLE;
                if (searchHidden) search.setVisibility(View.INVISIBLE);
            }

            @Override public void uncovered() {
                home.setVisibility(View.VISIBLE);
                if (searchHidden) search.setVisibility(View.VISIBLE);
                searchHidden = false;
                if (resumed) home.resume();
            }

            @Override public void watchingChanged() {
                home.syncWatching();   // under the page: rebuilt now, nothing moves on screen
            }

            @Override public void listChanged() {
                home.syncList();
            }
        });
        root.addView(detail, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        guide = new GuideView(this, new GuideView.Host() {
            @Override public void covered() {
                home.pause();
                home.setVisibility(View.INVISIBLE);
                if (search.getVisibility() == View.VISIBLE) search.setVisibility(View.INVISIBLE);
            }

            @Override public void uncovered() {
                home.setVisibility(View.VISIBLE);
                if (resumed) home.resume();
            }
        });
        root.addView(guide, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        getWindow().setBackgroundDrawable(null);
    }

    private int px(float at1080) {
        return Math.round(at1080 * getResources().getDisplayMetrics().heightPixels / 1080f);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        NetGuard.check(this);       // le Wi-Fi s'accroche parfois à une cellule lointaine : le lien est vérifié ici
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (detailOpen()) {                     // back from the player: the page shows where it was left
            detail.resume();
            if (home != null) home.syncWatching();   // and "En cours" is rebuilt under it, not when it closes
        }
        else if (home != null) home.resume();
    }

    @Override protected void onPause() {
        resumed = false;
        if (home != null) home.pause();
        if (detail != null) detail.pause();
        super.onPause();
        recoverAfterBoot();
    }

    /**
     * Sur cette TV, le service MediaTek remet l'app du tuner (« Live TV ») au premier plan ~25 s après le démarrage,
     * par-dessus l'accueil. On reprend l'écran 1,5 s plus tard — mais seulement dans les quatre minutes qui suivent le
     * boot, sans touche pressée juste avant (sinon c'est l'utilisateur qui a ouvert une appli, on ne le dérange pas) et
     * écran allumé (sinon c'est la veille ou l'écran de veille qui prend la main).
     */
    private void recoverAfterBoot() {
        final long now = SystemClock.elapsedRealtime();
        if (bootRecoveries >= 2 || now > 240_000 || now - lastKeyAt <= 3_000) return;
        final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) return;
        bootRecoveries++;
        new Handler(getMainLooper()).postDelayed(() -> {
            if (!isFinishing() && !hasWindowFocus()) HomeMode.takeFrontNow(this);
        }, 1_500);
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (detailOpen()) {
            detail.closeNow();
            if (home != null) home.setVisibility(View.VISIBLE);   // onResume brings it back to life
            if (searchHidden && search != null) search.setVisibility(View.VISIBLE);
            searchHidden = false;
        }
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) lastKeyAt = SystemClock.elapsedRealtime();
        if (guideOpen() && guide.onKey(event)) return true;     // the guide owns the arrows, OK and Back
        if (detailOpen() && detail.onKey(event)) return true;   // the page owns the arrows, OK and Back
        // While the search field is up it owns the keyboard: the home screen must not steal the arrows. Back closes
        // the search in one press — otherwise the first Back only hides the keyboard and the screen stays on results.
        if (search != null && search.getVisibility() == View.VISIBLE && search.hasFocus()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (home != null) home.closeSearch();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean detailOpen() {
        return detail != null && detail.isOpen();
    }

    private boolean guideOpen() {
        return guide != null && guide.isOpen();
    }

    /** GUIDE was pressed on the home screen: the guide covers it. */
    void openGuide() {
        if (guide == null || guideOpen() || detailOpen()) return;
        guide.show();
    }

    /** A film's or a series' card was pressed: its page fades in over the home screen, which then rests. */
    void openDetail(DetailView.Request r) {
        if (detail == null) return;
        detail.show(r);
    }

    /** Called by the home screen when its pill is pressed. */
    void openSearch() {
        if (search == null) return;
        search.setVisibility(View.VISIBLE);
        search.setText("");
        search.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    void closeSearch() {
        if (search == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setVisibility(View.GONE);
    }
}
