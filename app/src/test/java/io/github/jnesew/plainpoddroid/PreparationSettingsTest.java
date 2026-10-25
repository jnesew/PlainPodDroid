package io.github.jnesew.plainpoddroid;

import android.content.SharedPreferences;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PreparationSettingsTest {
    private static final long DUE = 1_792_474_200_000L;
    private final MemoryPreferences preferences = new MemoryPreferences();
    private final PreparationSettings settings = new PreparationSettings(preferences, () -> DUE + 1000);

    private PreparationSettings.Config enable() {
        assertTrue(settings.save(true, 360, 31, true, false));
        return settings.get();
    }

    @Test public void scheduleIsOptInAndPersistsConfigurationWithoutPlaybackState() {
        assertFalse(settings.get().enabled()); assertTrue(settings.get().unmetered());
        assertTrue(settings.save(true, 435, 65, false, true));
        var config = settings.get();
        assertEquals(config, new PreparationSettings(preferences, () -> DUE).get());
        assertEquals(435, config.schedule().minuteOfDay()); assertEquals(65, config.schedule().days());
        assertFalse(config.unmetered()); assertTrue(config.charging());
        assertEquals(Set.of("enabled", "minute", "days", "unmetered", "charging", "revision", "active_attempt"),
                preferences.getAll().keySet());
    }

    @Test public void retiredAttemptCannotOverwriteAContinuationOrTerminalResult() {
        var config = enable();
        var first = settings.begin(config, DUE);
        assertNotNull(first); assertTrue(settings.reserveDownload(first));
        assertTrue(settings.record(first, PreparationSettings.Outcome.PAUSED, 4, 1, false, true));
        assertFalse(settings.owns(first));
        var second = settings.begin(config, DUE);
        assertNotNull(second); assertNotEquals(first.id(), second.id());
        assertFalse(settings.record(first, PreparationSettings.Outcome.RUNNING, 9, 1, false, true));
        assertFalse(settings.reserveDownload(first));
        assertTrue(settings.record(second, PreparationSettings.Outcome.COMPLETE, 4, 1, false, true));
        assertFalse(settings.record(second, PreparationSettings.Outcome.PAUSED, 4, 1, false, true));
        assertEquals(PreparationSettings.Outcome.COMPLETE, settings.status().outcome());
        assertNull(settings.begin(config, DUE));
    }

    @Test public void transferBudgetAndCompletedRefreshSurviveProcessRestartAndFailures() {
        var config = enable(); var attempt = settings.begin(config, DUE);
        for (int i = 0; i < SecurityLimits.MAX_PREPARATION_DOWNLOADS - 1; i++) assertTrue(settings.reserveDownload(attempt));
        assertTrue(settings.record(attempt, PreparationSettings.Outcome.PAUSED, 3, 2, true, true));
        var recreated = new PreparationSettings(preferences, () -> DUE + 2000);
        var next = recreated.begin(config, DUE);
        assertNotNull(next); assertTrue(recreated.status().refreshComplete());
        assertEquals(3, recreated.status().refreshed()); assertEquals(2, recreated.status().downloaded());
        assertTrue(recreated.status().problems());
        assertTrue(recreated.reserveDownload(next)); assertFalse(recreated.reserveDownload(next));
        assertEquals(SecurityLimits.MAX_PREPARATION_DOWNLOADS, recreated.status().transfers());
        assertFalse(settings.record(attempt, PreparationSettings.Outcome.RUNNING, 3, 3, true, true));
    }

    @Test public void failedDurableReservationDisablesWorkEvenWhenPreferencesChangedInMemory() {
        var attempt = settings.begin(enable(), DUE);
        preferences.failCommit = true;
        assertFalse(settings.reserveDownload(attempt));
        assertFalse(settings.get().enabled()); assertFalse(settings.owns(attempt));
        assertFalse(settings.reserveDownload(attempt));
    }

    @Test public void failedOptInCannotStartWork() {
        preferences.failCommit = true;
        assertFalse(settings.save(true, 360, 31, true, false));
        assertFalse(settings.get().enabled()); assertFalse(preferences.getBoolean("enabled", true));
    }

    @Test public void completionAndMissedWatermarkRejectDuplicatesAndClockRollback() {
        var config = enable(); var attempt = settings.begin(config, DUE);
        assertTrue(settings.record(attempt, PreparationSettings.Outcome.COMPLETE, 0, 0, false, true));
        assertTrue(settings.finished(config, DUE)); assertTrue(settings.finished(config, DUE - 86_400_000));
        assertNull(settings.begin(config, DUE - 86_400_000));
        settings.missed(config, DUE + 86_400_000);
        assertEquals(PreparationSettings.Outcome.MISSED, settings.status().outcome());
        assertNull(settings.begin(config, DUE + 86_400_000));
        assertNotNull(settings.begin(config, DUE + 172_800_000));
    }

    @Test public void configurationChangeRevokesOldAuthorityAndStartsAFreshOccurrence() {
        var config = enable(); var attempt = settings.begin(config, DUE);
        assertTrue(settings.reserveDownload(attempt));
        assertTrue(settings.save(true, 420, 31, true, true));
        assertFalse(settings.owns(attempt)); assertFalse(settings.reserveDownload(attempt));
        assertFalse(settings.record(attempt, PreparationSettings.Outcome.COMPLETE, 0, 1, false, true));
        var replacement = settings.begin(settings.get(), DUE);
        assertNotNull(replacement); assertEquals(0, settings.status().transfers());
        assertTrue(settings.save(false, 420, 31, true, true));
        assertFalse(settings.owns(replacement)); assertNull(settings.begin(settings.get(), DUE));
    }

    @Test public void malformedConfigurationAndInvalidProgressFailClosed() {
        enable(); preferences.edit().putString("days", "corrupt").commit();
        assertFalse(settings.get().enabled());
        preferences.edit().putInt("days", 31).commit();
        var attempt = settings.begin(settings.get(), DUE);
        assertNotNull(attempt);
        assertFalse(settings.record(attempt, PreparationSettings.Outcome.RUNNING, -1, 0, false, true));
        assertFalse(settings.record(attempt, PreparationSettings.Outcome.RUNNING, 0, 1, false, true));
        assertFalse(settings.record(attempt, PreparationSettings.Outcome.RUNNING,
                SecurityLimits.MAX_SUBSCRIPTIONS + 1, 0, false, true));
        assertThrows(IllegalArgumentException.class, () -> settings.save(true, 1440, 31, true, false));
        assertThrows(IllegalArgumentException.class, () -> settings.save(true, 360, 0, true, false));
    }

    @Test public void cancellingThePlatformJobOfflineDoesNotLoseAnEligibleUnstartedOccurrence() {
        var config = enable(); var zone = java.time.ZoneId.of("Europe/Helsinki");
        assertTrue(settings.rememberDue(config, DUE, zone));
        assertEquals(PreparationSettings.Outcome.NEVER, settings.status().outcome());
        // Platform job cancellation does not remove this local scheduling stamp.
        var recreated = new PreparationSettings(preferences, () -> DUE + 60_000);
        assertEquals(DUE, recreated.rememberedDue(config, zone));
        assertEquals(0L, recreated.rememberedDue(config, java.time.ZoneId.of("Atlantic/Reykjavik")));
        assertTrue(settings.save(true, 420, 31, true, false));
        assertEquals(0L, recreated.rememberedDue(settings.get(), zone));
    }

    @Test public void offlineInterruptionPausesUntilTheOccurrenceIsActuallyTooLate() {
        var config = enable(); var attempt = settings.begin(config, DUE);
        var paused = PreparationJobService.interruptionOutcome(DUE, DUE + 60_000);
        assertEquals(PreparationSettings.Outcome.PAUSED, paused);
        assertTrue(settings.record(attempt, paused, 0, 0, false, false));
        assertFalse(settings.finished(config, DUE)); assertNotNull(settings.begin(config, DUE));
        assertEquals(PreparationSettings.Outcome.PAUSED, PreparationJobService.interruptionOutcome(DUE, DUE - 1000));
        assertEquals(PreparationSettings.Outcome.MISSED, PreparationJobService.interruptionOutcome(DUE,
                DUE + io.github.jnesew.plainpoddroid.core.refresh.WeeklyPreparationSchedule.MAX_LATENESS_MILLIS + 1));
    }

    /** commit(false) still mutates memory, matching Android's failure semantics. */
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();
        boolean failCommit;
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) { return values.containsKey(key) ? (String) values.get(key) : fallback; }
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            return values.containsKey(key) ? (Set<String>) values.get(key) : fallback;
        }
        @Override public int getInt(String key, int fallback) { return values.containsKey(key) ? (Integer) values.get(key) : fallback; }
        @Override public long getLong(String key, long fallback) { return values.containsKey(key) ? (Long) values.get(key) : fallback; }
        @Override public float getFloat(String key, float fallback) { return values.containsKey(key) ? (Float) values.get(key) : fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return values.containsKey(key) ? (Boolean) values.get(key) : fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.add(listener); }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.remove(listener); }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>(); boolean clear;
                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> value) { pending.put(key, value); return this; }
                @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                @Override public Editor remove(String key) { pending.put(key, null); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() { apply(); return !failCommit; }
                @Override public void apply() {
                    if (clear) values.clear();
                    pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                    for (var listener : Set.copyOf(listeners)) for (String key : pending.keySet()) listener.onSharedPreferenceChanged(MemoryPreferences.this, key);
                }
            };
        }
    }
}
