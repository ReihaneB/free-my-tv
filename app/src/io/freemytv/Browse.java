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

    private String type = "live";           // live | vod | series
    private String onlyCategory = "";       // "" = every category
    private boolean episodesShown;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout tabs, chips, content;
    private android.widget.EditText search;
    private String query = "";
    private final Runnable runSearch = this::showSearchResults;
    private int searchSeq;
    private ScrollView scroll;
    private LinearLayout header;
    private TextView empty;
    private View firstFocus, firstCard;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(build());
        Iptv.get(this).addListener(this);
        show();
    }

    @Override protected void onDestroy() {
        Iptv.get(this).removeListener(this);
        super.onDestroy();
    }

    @Override public void onIptv() { ui.post(this::show); }

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
        scroll.addView(content);
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
        t.setOnFocusChangeListener((v, has) -> { if (has) { type = id; onlyCategory = ""; show(); } paintTabs(); });
        tabs.addView(t, margin(0, 0, dp(8), 0));
    }

    private void paintTabs() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            boolean on = ("live".equals(type) && i == 0) || ("vod".equals(type) && i == 1) || ("series".equals(type) && i == 2);
            t.setTextColor(on ? 0xFF0B0B0F : 0xCCFFFFFF);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(16));
            pill.setColor(on ? 0xFFFFFFFF : 0x1AFFFFFF);
            t.setBackground(pill);
        }
    }

    private final List<String> chipIds = new ArrayList<>();

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
        t.setOnClickListener(v -> search.requestFocus());
        chips.addView(t, margin(0, 0, dp(8), 0));
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
        List<Iptv.Category> cats = categories(iptv);
        searchChip();
        chip("", "Tout");
        for (Iptv.Category c : cats) chip(c.id, clean(c.name));

        refreshContent();
    }

    /** Only the rows: called when a chip takes the focus, so the chip that has it is not rebuilt under it. */
    private void refreshContent() {
        Iptv iptv = Iptv.get(this);
        paintChips();
        content.removeAllViews();
        firstFocus = null;
        firstCard = null;

        if (!Iptv.configured()) {
            empty.setText("Aucun compte IPTV configuré (iptv_host, iptv_user, iptv_pass).");
            return;
        }
        empty.setText("");

        if (!query.isEmpty()) {
            showSearchResults();
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
            for (Iptv.Category c : categories(iptv)) {
                List<Iptv.Item> row = byCategory.get(c.id);
                if (row == null || row.isEmpty()) continue;
                this.row(clean(c.name), row);
                if (++rows >= 5) break;
            }
        } else {
            row(nameOf(categories(iptv), onlyCategory), pool);
        }

        if (firstFocus != null) firstFocus.requestFocus();
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

    private List<Iptv.Category> categories(Iptv iptv) {
        return "live".equals(type) ? iptv.liveCategories() : "vod".equals(type) ? iptv.movieCategories() : iptv.seriesCategories();
    }

    private List<Iptv.Item> items(Iptv iptv) {
        return "live".equals(type) ? iptv.live() : "vod".equals(type) ? iptv.movies() : iptv.series();
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
        String s = name.replace("✧", "").replace("･", "").replace("ﾟ", "").replace("─", "").replace("|||", "");
        s = s.replace("||", "").trim();
        while (s.startsWith(":") || s.startsWith("*") || s.startsWith("-") || s.startsWith("◇") || s.startsWith("·")) {
            s = s.substring(1).trim();
        }
        return s.isEmpty() ? name.trim() : s;
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

        int shown = 0;
        for (Iptv.Item it : items) {
            if (shown++ >= 20) break;
            line.addView(card(it), margin(0, 0, dp(10), 0));
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
        if (!item.url.isEmpty()) { play(item.url, item.name); return; }
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

    private void play(String url, String name) {
        Intent it = new Intent(this, Player.class);
        it.putExtra(Player.EXTRA_URL, url);
        it.putExtra(Player.EXTRA_NAME, name);
        startActivity(it);
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
