package io.github.jnesew.plainpoddroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Enabled only while the user has opted into a preparation schedule. */
public final class PreparationScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
            ((PlainPodDroidApplication) context.getApplicationContext()).getPreparationScheduler().reconcile(
                    Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action));
    }
}
