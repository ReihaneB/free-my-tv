package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** System notices for the main process: the TV booted, or this app was updated. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    static final String FRONT = "io.freemytv.action.FRONT";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            // Démarrage direct : le stockage chiffré (l'état de l'app) n'est pas encore lisible, on ne fait donc que
            // poser deux réveils. L'app du tuner prend l'écran ~26 s après le boot : ces deux passages ramènent
            // l'accueil juste après, sans attendre le BOOT_COMPLETED qui n'arrive qu'à ~70 s sur cette TV.
            AlarmManager am = context.getSystemService(AlarmManager.class);
            if (am == null) return;
            long now = System.currentTimeMillis();
            int i = 0;
            for (long at : new long[] {20_000, 30_000, 45_000}) {
                PendingIntent pi = PendingIntent.getBroadcast(context, ++i,
                        new Intent(context, HomeReceiver.class).setAction(FRONT),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setExact(AlarmManager.RTC, now + at, pi);
            }
        } else if (FRONT.equals(action)) {
            HomeMode.takeFrontNow(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            // Le service MediaTek a déjà mis l'app du tuner au premier plan (+26 s) et le système n'a jamais lancé
            // l'accueil : on le lance tout de suite, puis une fois de plus 20 s après au cas où le tuner revienne.
            HomeMode.takeFrontNow(context);
            ModeJob.schedule(context, ModeJob.FRONT, null, 20_000);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Watchdog.clear(context);    // a new build starts with a clean slate (a fixed crash must not keep us out)
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
        }
    }
}
