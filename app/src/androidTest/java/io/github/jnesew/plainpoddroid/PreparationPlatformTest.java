package io.github.jnesew.plainpoddroid;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.NetworkCapabilities;
import android.test.AndroidTestCase;
import io.github.jnesew.plainpoddroid.core.refresh.WeeklyPreparationSchedule;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.time.ZoneId;
import java.util.Set;

@SuppressWarnings("deprecation")
public final class PreparationPlatformTest extends AndroidTestCase {
    public void testPersistedJobKeepsConstraintsAndOnlySchedulingExtras() {
        long due = System.currentTimeMillis() + 60_000;
        var config = new PreparationSettings.Config(true, new WeeklyPreparationSchedule(360, 31), true, true, 9);
        ComponentName service = new ComponentName(getContext(), PreparationJobService.class);
        var job = PreparationScheduler.buildJob(config, due, 0, service, due - 60_000, ZoneId.of("Europe/Helsinki"), true);
        assertTrue(job.isPersisted()); assertTrue(job.isRequireCharging());
        assertFalse(job.isPeriodic()); assertFalse(job.isPrefetch());
        assertEquals(0L, job.getMaxExecutionDelayMillis()); assertEquals(60_000L, job.getMinLatencyMillis());
        assertEquals(service, job.getService());
        assertTrue(job.getRequiredNetwork().hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        assertEquals(Set.of("due", "revision", "zone", "unmetered"), job.getExtras().keySet());
        assertEquals(due, job.getExtras().getLong("due")); assertEquals(9L, job.getExtras().getLong("revision"));
        assertEquals("Europe/Helsinki", job.getExtras().getString("zone"));
        var retry = PreparationScheduler.buildJob(config, due, SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS,
                service, due + 1, ZoneId.of("Europe/Helsinki"), false);
        assertEquals(SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS, retry.getMinLatencyMillis());
        assertFalse(retry.getRequiredNetwork().hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }

    public void testRealPreferencesRetainProgressAndRejectRetiredAttempts() {
        String prefix = "preparation-test-" + System.nanoTime();
        Context isolated = new ContextWrapper(getContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
        try {
            var first = new PreparationSettings(isolated);
            assertFalse(first.get().enabled()); assertTrue(first.save(true, 360, 31, true, false));
            long due = System.currentTimeMillis(); var config = first.get();
            var attempt = first.begin(config, due); assertNotNull(attempt);
            assertTrue(first.reserveDownload(attempt));
            assertTrue(first.record(attempt, PreparationSettings.Outcome.PAUSED, 2, 1, false, true));
            var recreated = new PreparationSettings(isolated);
            var continuation = recreated.begin(config, due); assertNotNull(continuation);
            assertEquals(1, recreated.status().transfers()); assertEquals(1, recreated.status().downloaded());
            assertTrue(recreated.status().refreshComplete());
            assertFalse(first.reserveDownload(attempt));
            assertTrue(recreated.record(continuation, PreparationSettings.Outcome.COMPLETE, 2, 1, false, true));
            assertNull(first.begin(config, due));
        } finally { isolated.getSharedPreferences("scheduled_preparation", 0).edit().clear().commit(); }
    }
}
