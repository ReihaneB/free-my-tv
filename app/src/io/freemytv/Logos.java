package io.freemytv;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The channels' logos when the panel has none (or only its own placeholder): the tv-logos set, an open database of
 * French channel logos (github.com/tv-logo/tv-logos, per-country folders). One picture per channel name, fetched by
 * the same Posters cache as the panel's own images.
 */
final class Logos {
    private Logos() {}

    private static final String BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/";

    /** Our channels' names, without accents, quality tags or punctuation, to the file in the open set. */
    private static final Map<String, String> FR = new HashMap<>();

    static {
        FR.put("6TER", "6ter-fr.png");
        FR.put("AB1", "ab1-fr.png");
        FR.put("AB3", "ab3-fr.png");
        FR.put("ACTION", "action-fr.png");
        FR.put("ANIMAUX", "animaux-fr.png");
        FR.put("ARTE", "arte-fr.png");
        FR.put("AUTOMOTOLACHAINE", "automoto-la-chaine-fr.png");
        FR.put("BET", "bet-fr.png");
        FR.put("BFM", "bfm-tv-fr.png");
        FR.put("BFMBUSINESS", "bfm-business-fr.png");
        FR.put("BFMGRANDLITTORAL", "bfm-grand-littoral-fr.png");
        FR.put("BFMPARIS", "bfm-paris-fr.png");
        FR.put("BOING", "boing-fr.png");
        FR.put("BOOMERANG", "boomerang-fr.png");
        FR.put("BREIZH", "tv-breizh-fr.png");
        FR.put("C8", "c8-fr.png");
        FR.put("CANALJ", "canal-j-fr.png");
        FR.put("CANALPLUS", "canal-plus-fr.png");
        FR.put("CANALPLUSBOXOFFICE", "canal-plus-box-office-fr.png");
        FR.put("CANALPLUSDOCS", "canal-plus-docs-fr.png");
        FR.put("CANALPLUSFOOT", "canal-plus-foot-fr.png");
        FR.put("CANALPLUSGRANDECRAN", "canal-plus-grand-ecran-fr.png");
        FR.put("CANALPLUSKIDS", "canal-plus-kids-fr.png");
        FR.put("CANALPLUSPREMIERLEAGUE", "canal-plus-premier-league-fr.png");
        FR.put("CANALPLUSSERIES", "canal-plus-series-fr.png");
        FR.put("CANALPLUSSPORT", "canal-plus-sport-fr.png");
        FR.put("CARTOONNETWORK", "cartoon-network-fr.png");
        FR.put("CHASSEETPECHE", "chasse-et-peche-fr.png");
        FR.put("CINEPLUSCLASSIC", "cine-plus-classic-fr.png");
        FR.put("CINEPLUSEMOTION", "cine-plus-emotion-fr.png");
        FR.put("CINEPLUSFAMILY", "cine-plus-family-fr.png");
        FR.put("CINEPLUSFESTIVAL", "cine-plus-festival-fr.png");
        FR.put("CINEPLUSFRISSON", "cine-plus-frisson-fr.png");
        FR.put("CINEPLUSOCS", "cine-plus-ocs-fr.png");
        FR.put("CINEPLUSPREMIER", "cine-plus-premier-fr.png");
        FR.put("CNEWS", "c-news-fr.png");
        FR.put("COMEDIEPLUS", "comedie-plus-fr.png");
        FR.put("CRIMEDISTRICT", "crime-district-fr.png");
        FR.put("CSTAR", "c-star-fr.png");
        FR.put("DISNEY", "disney-channel-fr.png");
        FR.put("EQUIDIA", "equidia-fr.png");
        FR.put("EUROSPORT1", "eurosport-1-fr.png");
        FR.put("EUROSPORT2", "eurosport-2-fr.png");
        FR.put("FRANCE2", "france-2-fr.png");
        FR.put("FRANCE24", "france-24-fr.png");
        FR.put("FRANCE3", "france-3-fr.png");
        FR.put("FRANCE4", "france-4-fr.png");
        FR.put("FRANCE5", "france-5-fr.png");
        FR.put("FRANCEINFO", "franceinfo-fr.png");
        FR.put("GAMEONE", "game-one-fr.png");
        FR.put("GOLFPLUS", "golf-plus-fr.png");
        FR.put("GULLI", "gulli-fr.png");
        FR.put("HISTOIRE", "histoire-tv-fr.png");
        FR.put("INFOSPORTPLUS", "infosport-plus-fr.png");
        FR.put("JONE", "j-one-fr.png");
        FR.put("LACHAINEMETEO", "la-chaine-meteo-fr.png");
        FR.put("LCI", "lci-fr.png");
        FR.put("LCP", "lcp-fr.png");
        FR.put("LEQUIPE", "lequipe-fr.png");
        FR.put("M6", "m6-fr.png");
        FR.put("M6MUSIC", "m6-music-fr.png");
        FR.put("MANGAS", "mangas-fr.png");
        FR.put("MCM", "mcm-fr.png");
        FR.put("MCMPOP", "mcm-pop-fr.png");
        FR.put("MCMTOP", "mcm-top-fr.png");
        FR.put("MELODY", "melody-fr.png");
        FR.put("MEZZO", "mezzo-fr.png");
        FR.put("MEZZOLIVE", "mezzo-live-fr.png");
        FR.put("NICKELODEON", "nickelodeon-fr.png");
        FR.put("NICKELODEONJUNIOR", "nickelodeon-junior-fr.png");
        FR.put("NICKELODEONTEEN", "nickelodeon-teen-fr.png");
        FR.put("NOVELAS", "novelas-tv-fr.png");
        FR.put("NOVO19", "novo19-fr.png");
        FR.put("NRJ12", "nrj-12-fr.png");
        FR.put("NRJHITS", "nrj-hits-fr.png");
        FR.put("OLYMPIA", "olympia-tv-fr.png");
        FR.put("PARAMOUNT", "paramount-channel-fr.png");
        FR.put("PARAMOUNTDECALE", "paramount-channel-decale-fr.png");
        FR.put("PARISPREMIERE", "paris-premiere-fr.png");
        FR.put("PITCHOUN", "pitchoun-tv-fr.png");
        FR.put("PIWIPLUS", "piwi-plus-fr.png");
        FR.put("PLANETEPLUS", "planete-plus-fr.png");
        FR.put("PLANETEPLUSAVENTURE", "planete-plus-aventure-fr.png");
        FR.put("PLANETEPLUSCRIME", "planete-plus-crime-fr.png");
        FR.put("POLARPLUS", "polar-plus-fr.png");
        FR.put("PUBLICSENAT", "public-senat-fr.png");
        FR.put("RFM", "rfm-tv-fr.png");
        FR.put("RMCDECOUVERTE", "rmc-decouverte-fr.png");
        FR.put("RMCSPORT1", "rmc-sport-1-fr.png");
        FR.put("RMCSPORT2", "rmc-sport-2-fr.png");
        FR.put("RMCSTORY", "rmc-story-fr.png");
        FR.put("RTL9", "rtl9-fr.png");
        FR.put("SEASONS", "seasons-fr.png");
        FR.put("SERIECLUB", "serie-club-fr.png");
        FR.put("T18", "t18-fr.png");
        FR.put("TCMCINEMA", "tcm-cinema-fr.png");
        FR.put("TELETOONPLUS", "teletoon-plus-fr.png");
        FR.put("TELETOONPLUS1", "teletoon-plus-1-fr.png");
        FR.put("TEVA", "teva-fr.png");
        FR.put("TF1", "tf1-fr.png");
        FR.put("TF1SERIESFILMS", "tf1-series-films-fr.png");
        FR.put("TFX", "tfx-fr.png");
        FR.put("TIJI", "tiji-fr.png");
        FR.put("TLC", "tlc-fr.png");
        FR.put("TMC", "tmc-fr.png");
        FR.put("TOUTELHISTOIRE", "toute-lhistoire-fr.png");
        FR.put("TRACECARIBBEAN", "trace-caribbean-fr.png");
        FR.put("TRACELATINA", "trace-latina-fr.png");
        FR.put("TRACEURBAN", "trace-urban-fr.png");
        FR.put("TREK", "trek-fr.png");
        FR.put("TV5MONDE", "tv5-monde-fr.png");
        FR.put("USHUAIA", "ushuaia-tv-fr.png");
        FR.put("W9", "w9-fr.png");
        FR.put("WARNER", "warner-tv-fr.png");
        FR.put("WARNERNEXT", "warner-tv-next-fr.png");
    }

    /** The open logo for this channel name, or "" when the set has no picture for it. */
    static String url(String name) {
        String k = key(name);
        if (k.isEmpty()) return "";
        String file = FR.get(k);
        return file == null ? "" : BASE + file;
    }

    /** "CANAL+ SPORT [HEVC]" → "CANALPLUSSPORT": the same shape as the file names, minus "-fr.png". */
    private static String key(String name) {
        if (name == null || name.isEmpty()) return "";
        String t = java.text.Normalizer.normalize(HomeView.pretty(name), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        t = t.replace("+", " PLUS ");
        t = t.replaceAll("\\b(FULL HD|FHD|UHD|4K|HD|SD|HEVC|RAW|BACKUP|TV|CHANNEL)\\b", " ");
        return t.replaceAll("[^A-Z0-9]", "");
    }
}
