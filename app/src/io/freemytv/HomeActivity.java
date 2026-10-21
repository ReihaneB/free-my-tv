package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
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
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

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

        FrameLayout root = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFF9AA8AE);
        search.setHint("Rechercher");
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24));
        search.setPadding(px(56), 0, px(18), 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(300), px(58));
        lp.leftMargin = px(96);
        lp.topMargin = px(26);
        root.addView(search, lp);
        home.setSearchField(search);

        setContentView(root);
        getWindow().setBackgroundDrawable(null);
    }

    private int px(float at1080) {
        return Math.round(at1080 * getResources().getDisplayMetrics().heightPixels / 1080f);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // While the search field is up it owns the keyboard: the home screen must not steal the arrows. Back closes
        // the search in one press — otherwise the first Back only hides the keyboard and the screen stays on results.
        if (search != null && search.getVisibility() == View.VISIBLE) {
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
