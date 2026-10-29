package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, or this app was updated. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            // L'app du tuner reprend le dessus ~25 s après le boot : on reprend l'écran deux fois, après elle.
            ModeJob.schedule(context, ModeJob.FRONT, null, 30_000);
            ModeJob.schedule(context, ModeJob.FRONT2, null, 50_000);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Watchdog.clear(context);    // a new build starts with a clean slate (a fixed crash must not keep us out)
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
        }
    }
}
