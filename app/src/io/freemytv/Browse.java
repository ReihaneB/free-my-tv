package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the account has, the way the TV is meant to be used: rows of posters — continue watching, what just
 * came in, the best rated — then a row per category, for channels, films and series.
 *
 * Apple's glass: dark background, blurred-looking translucent panels, 18 dp corners, and a card that grows and lifts
 * when the remote lands on it.
 */
public final class Browse extends Activity implements Iptv.Listener {

    private static final int CARD_W = 132, CARD_H = 198;      // dp, 2:3 poster
    private static final int ROW_TITLE = 17, TAB = 15;

    private String type = "home";           // home | live | vod | series
    private String onlyCategory = "";       // "" = every category
    private boolean episodesShown;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout tabs, chips, content;
    private android.widget.EditText search;
    private String query = "";
    private final Runnable runSearch = this::showSearchResults;
    private int searchSeq;
    private ScrollView scroll;
    private FrameLayout holder;
    private LinearLayout header;
    private TextView empty;
    private View firstFocus, firstCard, heroButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(build());
        Iptv.get(this).addListener(this);
        show();
        if (getCurrentFocus() == null) tabs.getChildAt(0).requestFocus();
    }

    @Override protected void onDestroy() {
        Iptv.get(this).removeListener(this);
        super.onDestroy();
    }

    @Override public void onIptv() { ui.post(this::show); }

    /** Coming back from the player: the "Continue watching" row is new. */
    @Override protected void onResume() {
        super.onResume();
        if (content != null) ui.post(this::show);
    }

    // MARK: - Chrome

    private View build() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A0A0D, 0xFF14141A, 0xFF0B0B0F});
        root.setBackground(bg);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(56), dp(28), dp(56), 0);
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Title + tabs
        LinearLayout head = new LinearLayout(this);
        head.setId(View.generateViewId());
        header = head;
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Free TV");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        search = new android.widget.EditText(this);
        search.setId(1003);
        search.setHint("Rechercher");
        search.setHintTextColor(0x66FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setFocusable(false);
        search.setFocusableInTouchMode(false);
        search.setOnFocusChangeListener((v, has) -> {
            if (!has) { search.setFocusable(false); search.setFocusableInTouchMode(false); }
        });
        search.setBackground(glass(0x14FFFFFF, 0x1FFFFFFF));
        search.setPadding(dp(14), dp(7), dp(14), dp(8));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(18);
        head.addView(search, slp);
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (firstCard != null) firstCard.requestFocus(); else tabs.getChildAt(0).requestFocus();
                return true;
            }
            return false;
        });
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim();
                ui.removeCallbacks(runSearch);
                ui.postDelayed(runSearch, 220);
            }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });

        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setId(1001);
        head.addView(tabs);
        tab("home", "Accueil");
        tab("live", "Chaînes");
        tab("vod", "Films");
        tab("series", "Séries");
        page.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Categories
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setId(1002);
        chips.setPadding(0, 0, dp(24), 0);
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(16);
        page.addView(chipScroll, cp);

        // Content
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        holder = new FrameLayout(this);          // ScrollView accepts one child: the holder takes the rest
        holder.addView(content);
        scroll.addView(holder);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = dp(8);
        page.addView(scroll, sp);

        empty = new TextView(this);
        empty.setTextColor(0x99FFFFFF);
        empty.setTextSize(18);
        empty.setPadding(0, dp(28), 0, 0);
        page.addView(empty);

        return root;
    }

    private void tab(String id, String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TAB);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(9), dp(20), dp(10));
        t.setFocusable(true);
        t.setOnClickListener(v -> { type = id; onlyCategory = ""; show(); });
        tabViews.add(t);
        t.setOnFocusChangeListener((v, has) -> { if (has) { type = id; onlyCategory = ""; show(); } paintTabs(); });
        tabs.addView(t, margin(0, 0, dp(8), 0));
    }

    private void paintTabs() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            String want = i == 0 ? "home" : i == 1 ? "live" : i == 2 ? "vod" : "series";
            boolean on = want.equals(type);
            t.setTextColor(on ? 0xFF0B0B0F : 0xCCFFFFFF);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(16));
            pill.setColor(on ? 0xFFFFFFFF : 0x1AFFFFFF);
            t.setBackground(pill);
        }
    }

    private final List<String> chipIds = new ArrayList<>();
    private final List<View> chipViews = new ArrayList<>();
    private final List<View> tabViews = new ArrayList<>();
    private final List<List<View>> gridRows = new ArrayList<>();

    /** First chip: hands the focus to the search field, which opens the keyboard. */
    private void searchChip() {
        chipIds.add("\u0000search");
        TextView t = new TextView(this);
        t.setText("Rechercher");
        t.setTextSize(13);
        t.setTextColor(0xCCFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(7), dp(14), dp(8));
        t.setFocusable(true);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(14));
        pill.setColor(0x14FFFFFF);
        t.setBackground(pill);
        t.setOnClickListener(v -> {
            search.setFocusableInTouchMode(true);
            search.setFocusable(true);
            search.requestFocus();
        });
        chips.addView(t, margin(0, 0, dp(8), 0));
        chipViews.add(t);
    }

    private void paintChips() {
        for (int i = 0; i < chips.getChildCount() && i < chipIds.size(); i++) {
            TextView t = (TextView) chips.getChildAt(i);
            if (chipIds.get(i).startsWith("\u0000")) continue;
            boolean on = chipIds.get(i).equals(onlyCategory);
            t.setTextColor(on ? 0xFF0B0B0F : 0xCCFFFFFF);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(14));
            pill.setColor(on ? 0xFFFFFFFF : 0x14FFFFFF);
            t.setBackground(pill);
        }
    }

    private void chip(String id, String label) {
        chipIds.add(id);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(id.equals(onlyCategory) ? 0xFF0B0B0F : 0xCCFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(7), dp(14), dp(8));
        t.setFocusable(true);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(14));
        pill.setColor(id.equals(onlyCategory) ? 0xFFFFFFFF : 0x14FFFFFF);
        t.setBackground(pill);
        t.setOnClickListener(v -> { onlyCategory = id; refreshContent(); });
        t.setNextFocusUpId(1001);
        t.setOnFocusChangeListener((v, has) -> {
            if (has && !id.equals(onlyCategory)) { onlyCategory = id; refreshContent(); }
        });
        t.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && firstCard != null) { firstCard.requestFocus(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { tabs.getChildAt(0).requestFocus(); return true; }
            return false;
        });
        chips.addView(t, margin(0, 0, dp(8), 0));
    }

    // MARK: - Content

    private void show() {
        Iptv iptv = Iptv.get(this);
        paintTabs();

        chips.removeAllViews();
        chipIds.clear();
        chipViews.clear();
        List<Iptv.Category> cats = categoriesOf(iptv);
        if (!"home".equals(type)) {
            searchChip();
            chip("", "Tout");
            for (Iptv.Category c : cats) chip(c.id, clean(c.name));
        }

        refreshContent();
    }

    /** Apple TV's home: one title in the big frame, then the rows; it scrolls away with them. */
    private void dump(String tag, LinearLayout box) {
        StringBuilder order = new StringBuilder(tag).append(": ").append(box.getChildCount()).append(" enfants");
        for (int i = 0; i < Math.min(box.getChildCount(), 10); i++) {
            View ch = box.getChildAt(i);
            order.append(" | ").append(i).append(':').append(ch.getClass().getSimpleName());
            if (ch instanceof TextView) order.append('[').append(((TextView) ch).getText()).append(']');
        }
        AppLog.w(order.toString());
    }

    private void hero(Iptv iptv) {
        List<Iptv.Item> feature = new ArrayList<>();
        feature.addAll(enrich(Prefs.resume("vod"), iptv));
        feature.addAll(enrich(Prefs.resume("series"), iptv));
        boolean resuming = !feature.isEmpty();
        if (feature.isEmpty()) feature.addAll(iptv.fresh("vod", 21, 1));
        if (feature.isEmpty()) feature.addAll(iptv.best("vod", 1));
        if (feature.isEmpty()) return;
        final Iptv.Item item = feature.get(0);

        FrameLayout box = new FrameLayout(this);
        box.setClipToOutline(true);
        GradientDrawable bg = glass(0x18FFFFFF, 0x1FFFFFFF);
        bg.setCornerRadius(dp(22));
        box.setBackground(bg);

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        android.graphics.Bitmap cached = Posters.get(this).get(item.logo, (u, b) -> ui.post(() -> art.setImageBitmap(b)));
        if (cached != null) art.setImageBitmap(cached);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6000000, 0x99101010, 0x00101010}));
        box.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.BOTTOM);
        words.setPadding(dp(34), dp(24), dp(34), dp(26));

        TextView meta = new TextView(this);
        meta.setText(resuming ? "À REPRENDRE" : "À LA UNE");
        meta.setTextSize(11);
        meta.setTextColor(0xCCFFFFFF);
        meta.setLetterSpacing(0.12f);
        words.addView(meta);

        TextView title = new TextView(this);
        title.setText(item.name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(30);
        title.setMaxLines(2);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(6);
        words.addView(title, tp);

        float progress = Prefs.progressOf(item.url);
        if (progress > 0.01f) {
            android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress(Math.round(progress * 1000));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(260), dp(4));
            bp.topMargin = dp(12);
            words.addView(bar, bp);
        }

        TextView facts = new TextView(this);
        StringBuilder line = new StringBuilder();
        if (!item.year.isEmpty()) line.append(item.year);
        if (item.rating > 0) {
            if (line.length() > 0) line.append("  ·  ");
            line.append(String.format(java.util.Locale.ROOT, "%.1f", item.rating)).append("/10");
        }
        String catName = nameOf(categoriesOf(iptv), item.group);
        if (!catName.isEmpty()) {
            if (line.length() > 0) line.append("  ·  ");
            line.append(catName);
        }
        if (line.length() > 0) {
            facts.setText(line.toString());
            facts.setTextColor(0xCCFFFFFF);
            facts.setTextSize(13);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            fp.topMargin = dp(6);
            words.addView(facts, fp);
        }

        TextView watch = new TextView(this);
        watch.setText(progress > 0.01f ? "Reprendre" : "Regarder");
        watch.setTextSize(15);
        watch.setTextColor(0xFF0B0B0F);
        watch.setGravity(Gravity.CENTER);
        watch.setPadding(dp(26), dp(10), dp(26), dp(11));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(20));
        pill.setColor(0xFFFFFFFF);
        watch.setBackground(pill);
        watch.setFocusable(true);
        watch.setOnClickListener(v -> play(item.url, item.name, item.logo));
        heroButton = watch;
        watch.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                .setDuration(110).start());
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = dp(16);
        words.addView(watch, wp);
        if (firstFocus == null) firstFocus = watch;

        box.addView(words, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        hp.bottomMargin = dp(18);
        content.addView(box, hp);
        gridRows.add(new ArrayList<>());
    }

    /** Only the rows: called when a chip takes the focus, so the chip that has it is not rebuilt under it. */
    private void refreshContent() {
        Iptv iptv = Iptv.get(this);
        paintChips();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        holder.removeAllViews();
        holder.addView(column);
        content = column;
        firstFocus = null;
        firstCard = null;
        gridRows.clear();

        if (!Iptv.configured()) {
            empty.setText("Aucun compte IPTV configuré (iptv_host, iptv_user, iptv_pass).");
            return;
        }
        empty.setText("");

        if (!query.isEmpty()) {
            showSearchResults();
            return;
        }

        if ("home".equals(type)) {
            hero(iptv);

            List<Iptv.Item> res = new ArrayList<>();
            res.addAll(enrich(Prefs.resume("vod"), iptv));
            res.addAll(enrich(Prefs.resume("series"), iptv));
            if (!res.isEmpty()) row("Reprendre la lecture", res);
            row("Nouveautés", iptv.fresh("vod", 21, 20));
            row("Chaînes françaises", france(iptv));
            hdmiRow();
            List<Iptv.Item> hype = new ArrayList<>(iptv.best("vod", 14));
            hype.addAll(iptv.best("series", 8));
            hype.sort((a, b) -> Float.compare(b.rating, a.rating));
            row("Hype", hype);
            row("Séries les mieux notées", iptv.best("series", 20));
            List<Iptv.Item> films = iptv.movies();
            for (Iptv.Category c : iptv.movieCategories()) {
                if (gridRows.size() > 9) break;
                List<Iptv.Item> r = new ArrayList<>();
                for (Iptv.Item i : films) if (c.id.equals(i.group) && !i.url.isEmpty()) r.add(i);
                if (!r.isEmpty()) row(clean(c.name), r);
            }
            scroll.scrollTo(0, 0);
        dump("accueil", content);
            if (firstFocus != null && !search.hasFocus()) firstFocus.requestFocus();
            return;
        }

        List<Iptv.Item> all = items(iptv);
        if (all.isEmpty()) {
            empty.setText("Rien encore : le catalogue se charge en arrière-plan.");
            Iptv.get(this).refreshIfStale();
            return;
        }

        if ("vod".equals(type) || "series".equals(type)) {
            List<Iptv.Item> resume = Prefs.resume(type);
            if (!resume.isEmpty()) row("Reprendre la lecture", resume);
        }

        List<Iptv.Item> pool = filter(all, onlyCategory);
        if (onlyCategory.isEmpty()) {
            if ("vod".equals(type)) {
                List<Iptv.Item> fresh = iptv.fresh("vod", 21, 24);
                if (!fresh.isEmpty()) row("Nouveautés", fresh);
                List<Iptv.Item> best = iptv.best("vod", 24);
                if (!best.isEmpty()) row("Les mieux notés", best);
            } else if ("series".equals(type)) {
                List<Iptv.Item> best = iptv.best("series", 24);
                if (!best.isEmpty()) row("Les mieux notées", best);
            }
            // One pass to group, then five rows: building a row per category for 8 000 channels froze the TV.
            java.util.Map<String, List<Iptv.Item>> byCategory = new java.util.HashMap<>();
            for (Iptv.Item i : pool) {
                List<Iptv.Item> list = byCategory.get(i.group);
                if (list == null) { list = new ArrayList<>(); byCategory.put(i.group, list); }
                if (list.size() < 40) list.add(i);
            }
            int rows = 0;
            for (Iptv.Category c : categoriesOf(iptv)) {
                List<Iptv.Item> row = byCategory.get(c.id);
                if (row == null || row.isEmpty()) continue;
                this.row(clean(c.name), row);
                if (++rows >= 5) break;
            }
        } else {
            row(nameOf(categoriesOf(iptv), onlyCategory), pool);
        }

        StringBuilder order = new StringBuilder("content:");
        for (int i = 0; i < Math.min(content.getChildCount(), 8); i++) {
            View ch = content.getChildAt(i);
            order.append(' ').append(i).append(':').append(ch.getClass().getSimpleName());
            if (ch instanceof TextView) order.append('[').append(((TextView) ch).getText()).append(']');
        }
        AppLog.w(order.toString());
        View focused = getCurrentFocus();
        boolean gone = focused == null || focused.getParent() == null;   // the last build's views are detached
        if (firstFocus != null && (gone || focused == content
                || gridRows.get(gridRows.size() - 1).contains(focused))) {
            firstFocus.requestFocus();
        } else if (focused == null && tabs.getChildCount() > 0) {
            tabs.getChildAt(0).requestFocus();
        }
    }

    /** Matches on the name, in the current tab, off the UI thread (12 000 films, and the remote types fast). */
    private void showSearchResults() {
        final String q = query.toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return;
        final String kind = type;
        final int seq = ++searchSeq;
        content.removeAllViews();
        empty.setText("Recherche…");
        final List<Iptv.Item> all = items(Iptv.get(this));
        new Thread(() -> {
            List<Iptv.Item> hits = new ArrayList<>();
            for (Iptv.Item i : all) {
                if (hits.size() >= 60) break;
                if (i.name.toLowerCase(java.util.Locale.ROOT).contains(q)) hits.add(i);
            }
            ui.post(() -> {
                if (seq != searchSeq) return;
                content.removeAllViews();
                firstFocus = null;
                if (hits.isEmpty()) {
                    empty.setText("Rien pour « " + query + " ».");
                    return;
                }
                empty.setText("");
                List<Iptv.Item> row = new ArrayList<>(hits.subList(0, Math.min(20, hits.size())));
                this.row(hits.size() + " résultat" + (hits.size() > 1 ? "s" : ""), row);
                if (hits.size() > 20) {
                    for (int from = 20; from < hits.size(); from += 20) {
                        this.row("", new ArrayList<>(hits.subList(from, Math.min(from + 20, hits.size()))));
                    }
                }
                // Never steal the focus while the field has it: every letter would close the keyboard.
                if (firstFocus != null && !search.hasFocus()) firstFocus.requestFocus();
            });
        }, "search").start();
    }

    /** The HDMI sockets, and nothing else from the tuner: the antenna is not part of this TV any more. */
    private void hdmiRow() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return;
        List<TvInputInfo> inputs = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_HDMI) inputs.add(info);
        }
        inputs.sort((a, b) -> a.getId().compareTo(b.getId()));
        if (inputs.isEmpty()) return;

        TextView t = new TextView(this);
        t.setText("Entrées HDMI");
        t.setTextColor(0xE6FFFFFF);
        t.setTextSize(ROW_TITLE);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(14);
        tp.bottomMargin = dp(6);
        content.addView(t, tp);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(line);
        content.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        List<View> registered = new ArrayList<>();
        gridRows.add(registered);

        for (TvInputInfo info : inputs) {
            CharSequence label = info.loadLabel(this);
            TextView tile = new TextView(this);
            tile.setText(label == null ? "HDMI" : label.toString());
            tile.setTextColor(0xFFFFFFFF);
            tile.setTextSize(16);
            tile.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(glass(0x14FFFFFF, 0x26FFFFFF));
            tile.setFocusable(true);
            tile.setContentDescription(label == null ? "HDMI" : label.toString());
            tile.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            TvContract.buildChannelUriForPassthroughInput(info.getId())));
                } catch (Exception e) {
                    AppLog.w("hdmi: " + e.getMessage());
                }
            });
            tile.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(110).start();
                v.setBackground(has ? glass(0x2BFFFFFF, 0xFFFFFFFF) : glass(0x14FFFFFF, 0x26FFFFFF));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(CARD_H));
            lp.rightMargin = dp(10);
            line.addView(tile, lp);
            registered.add(tile);
            if (firstFocus == null) firstFocus = tile;
            if (firstCard == null) firstCard = tile;
        }
    }

    private static boolean progress0(Iptv.Item item) {
        return item != null && !item.url.isEmpty() && Prefs.progressOf(item.url) > 0.01f;
    }

    /** A resume entry only keeps the stream URL: the poster is looked up again by the stream id. */
    private List<Iptv.Item> enrich(List<Iptv.Item> items, Iptv iptv) {
        if (items.isEmpty()) return items;
        List<Iptv.Item> all = new ArrayList<>();
        all.addAll(iptv.movies());
        all.addAll(iptv.series());
        for (Iptv.Item it : items) {
            if (!it.logo.isEmpty()) continue;
            String id = it.url.substring(it.url.lastIndexOf('/') + 1);
            int dot = id.indexOf('.');
            if (dot > 0) id = id.substring(0, dot);
            for (Iptv.Item c : all) {
                if (c.id.equals(id) && !c.logo.isEmpty()) { it.logo = c.logo; it.year = c.year; it.rating = c.rating; break; }
            }
        }
        return items;
    }

    private List<Iptv.Item> france(Iptv iptv) {
        List<Iptv.Item> out = new ArrayList<>();
        for (Iptv.Category c : iptv.liveCategories()) {
            if (!c.name.toUpperCase(java.util.Locale.ROOT).contains("FRANCE")) continue;
            for (Iptv.Item i : iptv.live()) {
                if (i.group.equals(c.id) && !i.url.isEmpty() && !i.name.startsWith("─")) out.add(i);
                if (out.size() >= 20) return out;
            }
        }
        return out;
    }

    private List<Iptv.Item> items(Iptv iptv) {
        return "live".equals(type) ? iptv.live() : "vod".equals(type) ? iptv.movies() : iptv.series();
    }

    private List<Iptv.Category> categoriesOf(Iptv iptv) {
        return "live".equals(type) || "home".equals(type) ? iptv.liveCategories()
                : "vod".equals(type) ? iptv.movieCategories() : iptv.seriesCategories();
    }

    private static List<Iptv.Item> filter(List<Iptv.Item> all, String category) {
        List<Iptv.Item> out = new ArrayList<>();
        for (Iptv.Item i : all) {
            if (!category.isEmpty() && !category.equals(i.group)) continue;
            if (i.name.startsWith("─") || i.name.startsWith("=") || i.name.contains("✧")) continue;
            out.add(i);
        }
        return out;
    }

    private static String nameOf(List<Iptv.Category> cats, String id) {
        for (Iptv.Category c : cats) if (c.id.equals(id)) return clean(c.name);
        return "";
    }

    /** The panel decorates its category names ("─ ✧･ﾟ|| SPORT"): keep the word, drop the ornament. */
    private static String clean(String name) {
        String out = strip(name);
        return out.isEmpty() ? name.trim() : out;
    }

    private static String strip(String name) {
        String s = name.replace("✧", "").replace("･", "").replace("ﾟ", "").replace("─", "").replace("|||", "");
        s = s.replace("||", "").trim();
        while (s.startsWith(":") || s.startsWith("*") || s.startsWith("-") || s.startsWith("◇") || s.startsWith("·")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    private void row(String title, List<Iptv.Item> items) {
        if (items.isEmpty()) return;
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xE6FFFFFF);
        t.setTextSize(ROW_TITLE);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(14);
        tp.bottomMargin = dp(6);
        content.addView(t, tp);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(line);
        content.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        List<View> line2 = new ArrayList<>();
        gridRows.add(line2);
        int shown = 0;
        for (Iptv.Item it : items) {
            if (shown++ >= 20) break;
            View card = card(it);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H));
            clp.rightMargin = dp(10);
            line.addView(card, clp);
            line2.add(card);
        }
    }

    private View card(Iptv.Item item) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(glass(0x0FFFFFFF, 0x1FFFFFFF));
        card.setClipToOutline(true);
        card.setElevation(dp(2));
        card.setFocusable(true);
        card.setLayoutParams(new ViewGroup.LayoutParams(dp(CARD_W), dp(CARD_H)));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setAlpha(0.92f);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = new TextView(this);
        label.setText(item.name);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(12);
        label.setMaxLines(2);
        label.setPadding(dp(8), dp(6), dp(8), dp(8));
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000});
        label.setBackground(scrim);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        card.addView(label, lp);

        android.graphics.Bitmap b = Posters.get(this).get(item.logo, (url, bitmap) -> ui.post(() -> img.setImageBitmap(bitmap)));
        if (b != null) img.setImageBitmap(b);
        else if (item.logo == null || item.logo.isEmpty()) {
            TextView initial = new TextView(this);
            initial.setText(item.name.isEmpty() ? "?" : item.name.substring(0, 1).toUpperCase());
            initial.setTextColor(0x66FFFFFF);
            initial.setTextSize(40);
            initial.setGravity(Gravity.CENTER);
            card.addView(initial, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        card.setNextFocusUpId(1002);
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(110).start();
            v.setBackground(has ? glass(0x2BFFFFFF, 0xFFFFFFFF) : glass(0x0FFFFFFF, 0x1FFFFFFF));
            v.setElevation(dp(has ? 18 : 2));
        });
        card.setContentDescription(item.name);
        card.setOnClickListener(v -> open(item));
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (chips.getChildCount() > 0) { chips.getChildAt(0).requestFocus(); return true; }
            }
            return false;
        });
        if (firstFocus == null) firstFocus = card;
        if (firstCard == null) firstCard = card;
        return card;
    }

    private void open(Iptv.Item item) {
        if (!item.url.isEmpty()) { play(item.url, item.name, item.logo); return; }
        if ("series".equals(type)) showEpisodes(item);
    }

    private void showEpisodes(Iptv.Item series) {
        episodesShown = true;
        content.removeAllViews();
        chips.removeAllViews();
        TextView title = new TextView(this);
        title.setText(series.name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(title, margin(0, dp(8), 0, dp(12)));

        TextView wait = new TextView(this);
        wait.setText("Chargement des épisodes…");
        wait.setTextColor(0x99FFFFFF);
        content.addView(wait);

        new Thread(() -> {
            List<Iptv.Item> eps = Iptv.get(this).episodes(series.id);
            ui.post(() -> {
                content.removeAllViews();
                content.addView(title);
                if (eps.isEmpty()) {
                    TextView none = new TextView(this);
                    none.setText("Aucun épisode pour cette série.");
                    none.setTextColor(0x99FFFFFF);
                    content.addView(none);
                    return;
                }
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                content.addView(list);
                View first = null;
                for (Iptv.Item e : eps) {
                    TextView row = new TextView(this);
                    row.setText(e.name);
                    row.setTextColor(0xE6FFFFFF);
                    row.setTextSize(15);
                    row.setPadding(dp(16), dp(11), dp(16), dp(12));
                    row.setFocusable(true);
                    GradientDrawable pill = new GradientDrawable();
                    pill.setCornerRadius(dp(10));
                    pill.setColor(0x12FFFFFF);
                    row.setBackground(pill);
                    row.setOnFocusChangeListener((v, has) -> {
                        GradientDrawable d = new GradientDrawable();
                        d.setCornerRadius(dp(10));
                        d.setColor(has ? 0xFFFFFFFF : 0x12FFFFFF);
                        v.setBackground(d);
                        ((TextView) v).setTextColor(has ? 0xFF0B0B0F : 0xE6FFFFFF);
                    });
                    row.setOnClickListener(v -> play(e.url, e.name));
                    list.addView(row, margin(0, 0, 0, dp(6)));
                    if (first == null) first = row;
                }
                if (first != null) first.requestFocus();
            });
        }).start();
    }

    private void play(String url, String name) { play(url, name, ""); }

    private void play(String url, String name, String logo) {
        Intent it = new Intent(this, Player.class);
        it.putExtra(Player.EXTRA_URL, url);
        it.putExtra(Player.EXTRA_NAME, name);
        it.putExtra(Player.EXTRA_LOGO, logo);
        startActivity(it);
    }

    /**
     * Navigation by sections instead of Android's guesswork: the field, the tabs, the category chips, the rows of
     * cards. Up always goes to the section above, down to the one below, and the column is kept.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (navigate(k)) return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean navigate(int key) {
        View f = getCurrentFocus();
        if (f == null) return false;

        if (f == heroButton) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) { tabViews.get(0).requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusGrid(0, 0);
            return false;
        }
        if (f == search) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusGrid(0, 0) || focusChip(0);
            return false;                        // left/right/up stay in the field
        }
        int t = tabViews.indexOf(f);
        if (t >= 0) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && t > 0) { tabViews.get(t - 1).requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && t < tabViews.size() - 1) { tabViews.get(t + 1).requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusChip(0);
            if (key == KeyEvent.KEYCODE_DPAD_UP) { search.requestFocus(); return true; }
            return true;
        }
        int c = chipViews.indexOf(f);
        if (c >= 0) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && c > 0) { chipViews.get(c - 1).requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && c < chipViews.size() - 1) { chipViews.get(c + 1).requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusGrid(0, c);
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                int t2 = Math.min(tabViews.size() - 1, Math.max(0, "live".equals(type) ? 0 : "vod".equals(type) ? 1 : 2));
                tabViews.get(t2).requestFocus();
                return true;
            }
            return true;
        }
        for (int r = 0; r < gridRows.size(); r++) {
            int within = gridRows.get(r).indexOf(f);
            if (within < 0) continue;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (within > 0) { gridRows.get(r).get(within - 1).requestFocus(); return true; } return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (within < gridRows.get(r).size() - 1) { gridRows.get(r).get(within + 1).requestFocus(); return true; }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return r + 1 < gridRows.size() && focusGrid(r + 1, within);
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (r > 0) return focusGrid(r - 1, within);
                return focusChip(Math.max(0, Math.min(chipViews.size() - 1, within)));
            }
            return true;
        }
        // Anything else (the hero button, a detached view): up always means the tabs.
        if (key == KeyEvent.KEYCODE_DPAD_UP && !tabViews.isEmpty()) {
            int idx = "home".equals(type) ? 0 : "live".equals(type) ? 1 : "vod".equals(type) ? 2 : 3;
            tabViews.get(Math.min(idx, tabViews.size() - 1)).requestFocus();
            return true;
        }
        return false;
    }

    private boolean focusGrid(int row, int column) {
        if (row < 0 || row >= gridRows.size()) return false;
        List<View> line = gridRows.get(row);
        if (line.isEmpty()) return false;
        line.get(Math.max(0, Math.min(line.size() - 1, column))).requestFocus();
        return true;
    }

    private boolean focusChip(int index) {
        if (chipViews.isEmpty()) return false;
        chipViews.get(Math.max(0, Math.min(chipViews.size() - 1, index))).requestFocus();
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && episodesShown) { episodesShown = false; show(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    // MARK: - dp

    /** What Apple's glass looks like without a GPU blur: a thin white wash, a hairline, and a shadow to lift it. */
    private GradientDrawable glass(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(14));
        d.setColor(fill);
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }
}
