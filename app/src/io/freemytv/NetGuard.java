package io.freemytv;

import android.content.Context;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le Wi-Fi de la TV s'accroche parfois à une radio lointaine du même réseau et n'en bouge plus : mesuré ici 13 Mbps
 * et 40 % de paquets perdus alors qu'une cellule 5 GHz du même SSID était à -24 dBm (300 Mbps, 0 % de perte). Les
 * applis vidéo se mettent alors à bufferiser, à figer, et finissent en « ne répond pas ». Android ne revient pas
 * tout seul sur la bonne cellule : on vérifie donc le lien quand l'accueil revient au premier plan, et si une
 * cellule bien meilleure du même réseau est là, on relance le Wi-Fi une fois — c'est ce qui force la réassociation.
 *
 * <p>Au plus une vérification toutes les 10 minutes, en mode Free My TV seulement, et rien n'est touché tant que le
 * lien est bon.
 */
final class NetGuard {
    private NetGuard() {}

    /** Une cellule plus faible que ça (Mbps) mérite un coup d'œil. */
    private static final int SLOW = 60;
    /** Il faut au moins ce gain de signal (dBm) sur le même SSID pour valider un changement de cellule. */
    private static final int MARGIN = 12;
    private static final long EVERY = 10 * 60_000L;

    private static volatile long lastCheck;
    private static volatile boolean running;

    static void check(Context context) {
        if (running || System.currentTimeMillis() - lastCheck < EVERY) return;
        running = true;
        Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                run(app);
            } catch (Throwable e) {
                AppLog.w("wifi : " + e);
            } finally {
                lastCheck = System.currentTimeMillis();
                running = false;
            }
        }, "wifi-guard");
        t.setDaemon(true);
        t.start();
    }

    private static final Pattern SSID = Pattern.compile("SSID: ([^,]*),");
    private static final Pattern RSSI = Pattern.compile("RSSI: (-?\\d+)");
    private static final Pattern SPEED = Pattern.compile("Link speed: (\\d+)Mbps");
    /** Une ligne de `cmd wifi list-scan-results` : BSSID  fréquence  RSSI  âge  SSID  drapeaux. */
    private static final Pattern CELL = Pattern.compile("^\\s*([0-9a-f:]{17})\\s+(\\d+)\\s+(-?\\d+)\\s+(\\d+)\\s+(\\S.*?)\\s{2,}\\[");

    private static void run(Context app) throws IOException {
        if (!HomeMode.FREE.equals(HomeMode.state(app).optString("mode"))) return;
        String status, scan;
        try (Adb adb = Adb.connect(app, 8_000, null)) {
            status = adb.shell("cmd wifi status");
        }
        if (status == null || !status.contains("COMPLETED")) return;
        Matcher s = SSID.matcher(status), r = RSSI.matcher(status), v = SPEED.matcher(status);
        if (!s.find() || !r.find() || !v.find()) return;
        String ssid = s.group(1).trim();
        int rssi = Integer.parseInt(r.group(1));
        int speed = Integer.parseInt(v.group(1));
        if (speed >= SLOW) return;                       // lien correct : on ne touche à rien

        try (Adb adb = Adb.connect(app, 8_000, null)) {
            scan = adb.shell("cmd wifi list-scan-results");
        }
        if (scan == null) return;
        int best = rssi;
        String bestBssid = "";
        for (String line : scan.split("\n")) {
            Matcher m = CELL.matcher(line);
            if (!m.find()) continue;
            if (!m.group(5).trim().equals(ssid)) continue;    // même réseau seulement
            int cellRssi = Integer.parseInt(m.group(3));
            if (cellRssi > best) {
                best = cellRssi;
                bestBssid = m.group(1);
            }
        }
        AppLog.w("wifi : " + ssid + " à " + speed + " Mbps (" + rssi + " dBm), meilleure cellule "
                + (bestBssid.isEmpty() ? "aucune" : bestBssid + " à " + best + " dBm"));
        if (bestBssid.isEmpty() || best < rssi + MARGIN) return;

        // Le redémarrage du Wi-Fi coupe notre propre lien adb : la commande part détachée et rend la main de suite.
        try (Adb adb = Adb.connect(app, 8_000, null)) {
            adb.shell("nohup sh -c \"sleep 1; cmd wifi set-wifi-enabled disabled; sleep 6; cmd wifi set-wifi-enabled enabled\""
                    + " >/dev/null 2>&1 &");
        }
        AppLog.w("wifi : relancé pour rejoindre la cellule à " + best + " dBm");
    }
}
