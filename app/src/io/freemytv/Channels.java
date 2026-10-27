package io.freemytv;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The French channels as a box shows them: one channel per guide id (the panel lists TF1 seven times — FHD, 4K, HD,
 * HEVC, SD… — under the same tf1.fr), numbered like the TNT (Arcom, since 6 June 2025: 1 TF1 … 26 Paris Première),
 * then every other French channel from 27 in the panel's order, grouped in the panel's own sections (Généralistes,
 * Divertissement, Jeunesse, Info, Découverte, Musique).
 *
 * <p>Each channel keeps all its streams: the first one plays (the panel's best quality), the others are there if it
 * fails, and the one with a replay (tv_archive) gives the last days' programmes.
 */
final class Channels {

    static final class Channel {
        int number;
        String key = "", name = "", logo = "", epg = "", section = "";
        final List<Iptv.Item> streams = new ArrayList<>();
        Iptv.Item replay;          // the stream the panel keeps the last days of (null: no replay)
        int replayDays;

        Iptv.Item main() { return streams.get(0); }

        String url() { return main().url; }

        boolean owns(String url) {
            for (Iptv.Item i : streams) if (i.url.equals(url)) return true;
            return false;
        }
    }

    static final class Section {
        final String title;
        final List<Channel> channels = new ArrayList<>();

        Section(String title) { this.title = title; }
    }

    /** Arcom's numbers for the national TNT channels, by guide id. */
    private static final String[] TNT = {
            "tf1.fr", "france2.fr", "france3.fr", "france4.fr", "france5.fr", "m6.fr", "arte.fr", "lcp.fr", "w9.fr",
            "tmc.fr", "tfx.fr", "gulli.fr", "bfmtv.fr", "cnews.fr", "lci.fr", "franceinfo.fr", "cstar.fr", "t18.fr",
            "novo19.fr", "tf1seriesfilms.fr", "lequipe21.fr", "6ter.fr", "rmcstory.fr", "rmcdecouverte.fr",
            "cherie25.fr", "parispremiere.fr"};

    private static Channels instance;
    private static List<Iptv.Item> builtFrom;

    final List<Channel> all = new ArrayList<>();
    final List<Section> sections = new ArrayList<>();
    private final Map<String, Channel> byUrl = new HashMap<>();
    private final Map<Integer, Channel> byNumber = new HashMap<>();

    /** The lineup of what Iptv has now (built once per catalogue: ~10 ms for 8 000 streams). */
    static synchronized Channels get(Context c) {
        Iptv iptv = Iptv.get(c);
        List<Iptv.Item> live = iptv.live();
        if (instance == null || builtFrom != live) {
            instance = new Channels(iptv, live);
            builtFrom = live;
        }
        return instance;
    }

    Channel byUrl(String url) { return url == null ? null : byUrl.get(url); }

    Channel byNumber(int n) { return byNumber.get(n); }

    /** The channel `step` places away (wrapping round the lineup), as P+ / P− do. */
    Channel step(Channel from, int step) {
        if (all.isEmpty()) return null;
        int i = from == null ? -1 : all.indexOf(from);
        if (i < 0) return all.get(0);
        int n = all.size();
        return all.get(((i + step) % n + n) % n);
    }

    private Channels(Iptv iptv, List<Iptv.Item> live) {
        Map<String, String> names = new HashMap<>();
        for (Iptv.Category c : iptv.liveCategories()) names.put(c.id, c.name);
        // the French categories, in the panel's order (FHD/4K first: its stream plays), regional ones aside
        List<String> order = new ArrayList<>();
        for (Iptv.Category c : iptv.liveCategories()) {
            String n = c.name.toUpperCase(Locale.ROOT);
            if (n.contains("FRANCE") && !n.contains("REGION")) order.add(c.id);
        }
        Map<String, List<Iptv.Item>> byCat = new HashMap<>();
        for (Iptv.Item i : live) {
            if (!order.contains(i.group)) continue;
            List<Iptv.Item> l = byCat.get(i.group);
            if (l == null) byCat.put(i.group, l = new ArrayList<>());
            l.add(i);
        }
        LinkedHashMap<String, Channel> groups = new LinkedHashMap<>();
        // a stream without a guide id joins the channel of the same name (the SD category has none)
        Map<String, Channel> byName = new HashMap<>();
        for (String cat : order) {
            List<Iptv.Item> items = byCat.get(cat);
            if (items == null) continue;
            String section = "";
            for (Iptv.Item i : items) {
                if (header(i.name)) {
                    section = sectionOf(i.name);
                    continue;
                }
                if (i.url.isEmpty()) continue;
                String byWords = nameKey(i.name);
                String key = !i.epg.isEmpty() ? i.epg.toLowerCase(Locale.ROOT) : "name:" + byWords;
                Channel ch = !i.epg.isEmpty() ? groups.get(key) : byName.get(byWords);
                if (ch == null) {
                    ch = new Channel();
                    ch.key = key;
                    ch.epg = i.epg;
                    String shown = SHOWN.get(key);
                    ch.name = shown != null ? shown : nice(HomeView.pretty(i.name));
                    ch.section = section;
                    groups.put(key, ch);
                }
                if (!byName.containsKey(byWords)) byName.put(byWords, ch);
                // a 4K stream is rarely the one that plays well: it goes after the others
                if (i.name.toUpperCase(Locale.ROOT).matches(".*\\b(4K|UHD)\\b.*")) ch.streams.add(i);
                else ch.streams.add(firstNon4k(ch), i);
                if (ch.logo.isEmpty() || (ch.logo.contains("tonicarep") && !i.logo.isEmpty() && !i.logo.contains("tonicarep"))) {
                    ch.logo = i.logo;   // the panel's own logo set (tv-logos) before the placeholder one
                }
                if (ch.replay == null && i.archive > 0) {
                    ch.replay = i;
                    ch.replayDays = i.archive;
                }
                if (ch.section.isEmpty()) ch.section = section;
            }
        }
        // numbers: the TNT first, in Arcom's order, then the others as the panel lists them
        int n = 1;
        for (String id : TNT) {
            Channel ch = groups.remove(id);
            if (ch != null) {
                ch.number = n;
                ch.section = "TNT";
                add(ch);
            }
            n++;
        }
        n = 27;
        for (Channel ch : groups.values()) {
            ch.number = n++;
            add(ch);
        }
        Map<String, Section> bySection = new LinkedHashMap<>();
        for (String s : new String[]{"TNT", "Divertissement", "Cinéma et séries", "Sport", "Jeunesse", "Info", "Découverte",
                "Musique", "Autres chaînes"}) bySection.put(s, new Section(s));
        for (Channel ch : all) {
            String s = ch.section.equals("TNT") ? "TNT" : refine(ch);
            Section sec = bySection.get(s);
            if (sec == null) sec = bySection.get("Autres chaînes");
            sec.channels.add(ch);
        }
        for (Section s : bySection.values()) if (!s.channels.isEmpty()) sections.add(s);
    }

    private static int firstNon4k(Channel ch) {
        int at = 0;
        for (Iptv.Item i : ch.streams) {
            if (i.name.toUpperCase(Locale.ROOT).matches(".*\\b(4K|UHD)\\b.*")) break;
            at++;
        }
        return at;
    }

    private void add(Channel ch) {
        all.add(ch);
        byNumber.put(ch.number, ch);
        for (Iptv.Item i : ch.streams) byUrl.put(i.url, ch);
    }

    /** The panel's section headers are entries like "── ✧･ﾟ: *✧･ﾟ:*  GENERAL  *:･ﾟ✧*:･ﾟ✧ ──" or "•●★--- Info ---★●•". */
    static boolean header(String name) {
        return name.startsWith("─") || name.startsWith("=") || name.startsWith("•") || name.contains("✧")
                || name.contains("★") || name.contains("---");
    }

    /** "FR || Planète + Crime [SD]" and "[#] PLANETE+ CRIME" are one channel: no accents, quality or filler words. */
    static String nameKey(String name) {
        String t = java.text.Normalizer.normalize(HomeView.pretty(name), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        t = t.replaceAll("\\b(FULL HD|FHD|UHD|4K|HD|SD|HEVC|RAW|BACKUP|TV|CHANNEL|FRANCE)\\b", " ");
        return t.replaceAll("[^A-Z0-9]", "");
    }

    /** The TNT's names as the channels write them. */
    private static final Map<String, String> SHOWN = new HashMap<>();
    static {
        String[][] s = {{"tf1seriesfilms.fr", "TF1 Séries Films"}, {"lequipe21.fr", "L'Équipe"}, {"lcp.fr", "LCP"},
                {"franceinfo.fr", "franceinfo"}, {"cherie25.fr", "RMC Life"}, {"novo19.fr", "Novo19"},
                {"bfmtv.fr", "BFMTV"}, {"parispremiere.fr", "Paris Première"}, {"6ter.fr", "6ter"},
                {"rmcstory.fr", "RMC Story"}, {"rmcdecouverte.fr", "RMC Découverte"}, {"cnews.fr", "CNews"},
                {"cstar.fr", "CStar"}, {"france24.fr", "France 24"}, {"i24news.fr", "i24NEWS"}};
        for (String[] p : s) SHOWN.put(p[0], p[1]);
    }

    private static String sectionOf(String header) {
        String n = header.replaceAll("[^A-Za-zÀ-ÿ]", "").toUpperCase(Locale.ROOT);
        if (n.startsWith("GENERAL")) return "Généralistes";
        if (n.startsWith("DIVERTISSEMENT")) return "Divertissement";
        if (n.startsWith("ENFANT")) return "Jeunesse";
        if (n.startsWith("NEWS") || n.startsWith("INFO")) return "Info";
        if (n.startsWith("DECOUVERTE") || n.startsWith("DÉCOUVERTE")) return "Découverte";
        if (n.startsWith("MUSI")) return "Musique";
        if (n.startsWith("SPORT")) return "Sport";
        return "";
    }

    /** The panel files cinema, series and sport under "Divertissement": a box would not. */
    private static String refine(Channel ch) {
        String n = ch.name.toUpperCase(Locale.ROOT);
        if (n.matches(".*\\b(SPORT|SPORTS|FOOT|EUROSPORT|BEIN|DAZN|EQUIPE|GOLF|AUTOMOTO|INFOSPORT|LEAGUE|EQUIDIA)\\b.*")
                || n.contains("SPORT") || n.startsWith("GOLF")) return "Sport";
        if (n.contains("DOCS") || n.contains("DISCOVERY") || n.contains("GEO") || n.contains("PLANETE")
                || n.contains("PLANÈTE")) return "Découverte";
        if (n.matches(".*\\b(CINE|CINÉ|CINEMA|CINÉMA|SERIE|SÉRIE|SERIES|SÉRIES|POLAR|ACTION|TCM|PARAMOUNT|WARNER|SYFY|NOVELAS|FRISSON|13E|OCS|CLUB|EMOTION|ÉMOTION|COMEDIE|COMÉDIE)\\b.*")
                || n.startsWith("CINÉ+") || n.startsWith("CINE+") || n.contains("CANAL+ CIN") || n.contains("CANAL+ SÉR")
                || n.contains("CANAL+ GRAND") || n.contains("BOX OFFICE")) return "Cinéma et séries";
        String s = ch.section;
        if (s.equals("Généralistes")) return "Divertissement";
        return s.isEmpty() ? "Autres chaînes" : s;
    }

    // MARK: - Names as they are written

    private static final Map<String, String> WORDS = new HashMap<>();
    static {
        String[][] w = {
                {"TF1", "TF1"}, {"M6", "M6"}, {"W9", "W9"}, {"TMC", "TMC"}, {"TFX", "TFX"}, {"LCI", "LCI"}, {"LCP", "LCP"},
                {"BFM", "BFM"}, {"BFMTV", "BFMTV"}, {"TV", "TV"}, {"RMC", "RMC"}, {"MTV", "MTV"}, {"NRJ", "NRJ"},
                {"CNEWS", "CNews"}, {"CSTAR", "CStar"}, {"AB1", "AB1"}, {"AB3", "AB3"}, {"TV5", "TV5"}, {"BET", "BET"},
                {"TCM", "TCM"}, {"ID", "ID"}, {"OCS", "OCS"}, {"RTL9", "RTL9"}, {"C8", "C8"}, {"T18", "T18"}, {"HD", "HD"},
                {"6TER", "6ter"}, {"TEVA", "Téva"}, {"CINEMA", "Cinéma"}, {"SERIES", "Séries"}, {"SERIE", "Série"},
                {"DECOUVERTE", "Découverte"}, {"PREMIERE", "Première"}, {"PLANETE", "Planète"}, {"CHAINE", "Chaîne"},
                {"CHAÎNE", "Chaîne"}, {"METEO", "Météo"}, {"EQUIPE", "Équipe"}, {"ECRAN", "Écran"}, {"COMEDIE", "Comédie"},
                {"PECHE", "Pêche"}, {"13EME", "13e"}, {"FRANCEINFO", "franceinfo"}, {"INFO", "Info"}, {"MONDE", "Monde"},
                {"CINE", "Ciné"}, {"EMOTION", "Émotion"}, {"ENTERTAINMENT", "Entertainment"}, {"E!", "E!"},
                {"DISNEY", "Disney"}, {"NATIONAL", "National"}, {"GEO", "Geo"}, {"USHUAIA", "Ushuaïa"}, {"J-ONE", "J-One"},
                {"TIJI", "Tiji"}, {"PIWI+", "Piwi+"}, {"MCM", "MCM"}, {"DJAZZ", "Djazz"}, {"RFM", "RFM"}, {"CANAL+", "Canal+"},
                {"A+", "A+"}, {"NOVO", "Novo"}, {"GULLI", "Gulli"}, {"ARTE", "Arte"}, {"PARLEMENTAIRE", "parlementaire"},
                {"LA", "La"}, {"DE", "de"}, {"DES", "des"}, {"ET", "et"}, {"DU", "du"}, {"L'EQUIPE", "L'Équipe"},
                {"I24", "i24"}, {"BEIN", "beIN"}, {"DAZN", "DAZN"}, {"MULTI", "Multi"}, {"XXL", "XXL"}, {"KTO", "KTO"},
                {"CINE+", "Ciné+"}, {"PLANETE+", "Planète+"}, {"SANTE", "Santé"}, {"SENAT", "Sénat"}, {"TELE", "Télé"},
        };
        for (String[] p : w) WORDS.put(p[0], p[1]);
    }

    /** "FRANCE 2" → "France 2", "CANAL+ CINEMA" → "Canal+ Cinéma", "RMC LIFE (Ex CHERIE 25)" → "RMC Life". */
    static String nice(String raw) {
        String s = raw.replace("\u200e", "").replace("\u200f", "").replaceAll("\\s*\\((?i:ex)[^)]*\\)", "")
                .replace('*', ' ').replaceAll("\\s{2,}", " ").trim();
        if (s.isEmpty()) return raw.trim();
        // already written in lower case by the panel: leave it
        if (!s.equals(s.toUpperCase(Locale.ROOT))) return s;
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            String w = WORDS.get(word);
            if (w == null) {
                w = word.length() <= 1 || word.matches("\\d+\\w*") ? word
                        : word.charAt(0) + word.substring(1).toLowerCase(Locale.FRENCH);
            } else if (first && Character.isLowerCase(w.charAt(0)) && !w.equals("franceinfo") && !w.equals("beIN")
                    && !w.equals("i24")) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            b.append(w);
            first = false;
        }
        return b.toString();
    }
}
