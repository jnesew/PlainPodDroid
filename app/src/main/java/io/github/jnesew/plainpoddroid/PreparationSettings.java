package io.github.jnesew.plainpoddroid;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import io.github.jnesew.plainpoddroid.core.refresh.WeeklyPreparationSchedule;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Atomic configuration and source-free, attempt-owned progress. No playback intent is stored. */
@SuppressLint("ApplySharedPref")
final class PreparationSettings {
    enum Outcome { NEVER, RUNNING, COMPLETE, PARTIAL, PAUSED, FAILED, MISSED }
    record Config(boolean enabled, WeeklyPreparationSchedule schedule, boolean unmetered,
            boolean charging, long revision) { }
    record Status(Outcome outcome, long finishedAt, int refreshed, int downloaded,
            boolean problems, long due, long revision, int transfers, boolean refreshComplete) { }
    record Attempt(Config config, long due, long id) { }

    private final SharedPreferences preferences;
    private final LongSupplier clock;
    private boolean storageHealthy = true;

    PreparationSettings(Context context) {
        this(context.getApplicationContext().getSharedPreferences("scheduled_preparation", Context.MODE_PRIVATE),
                System::currentTimeMillis);
    }

    PreparationSettings(SharedPreferences preferences, LongSupplier clock) {
        this.preferences = Objects.requireNonNull(preferences);
        this.clock = Objects.requireNonNull(clock);
    }

    synchronized Config get() {
        if (!storageHealthy) return defaults();
        try {
            long revision = preferences.getLong("revision", 0L);
            if (revision < 0L) return defaults();
            return new Config(preferences.getBoolean("enabled", false),
                    new WeeklyPreparationSchedule(preferences.getInt("minute", 360), preferences.getInt("days", 31)),
                    preferences.getBoolean("unmetered", true), preferences.getBoolean("charging", false), revision);
        } catch (IllegalArgumentException | ClassCastException invalid) { return defaults(); }
    }

    private static Config defaults() {
        return new Config(false, new WeeklyPreparationSchedule(360, 31), true, false, 0L);
    }

    synchronized boolean save(boolean enabled, int minute, int days, boolean unmetered, boolean charging) {
        new WeeklyPreparationSchedule(minute, days);
        Config old = get();
        if (storageHealthy && old.enabled() == enabled && old.schedule().minuteOfDay() == minute
                && old.schedule().days() == days && old.unmetered() == unmetered && old.charging() == charging) return true;
        if (old.revision() == Long.MAX_VALUE) return false;
        if (!commit(preferences.edit().putBoolean("enabled", enabled).putInt("minute", minute).putInt("days", days)
                .putBoolean("unmetered", unmetered).putBoolean("charging", charging)
                .putLong("revision", old.revision() + 1L).putLong("active_attempt", 0L))) return false;
        storageHealthy = true;
        return true;
    }

    synchronized boolean finished(Config config, long due) {
        try {
            // A wall-clock rollback must not replay an earlier completed occurrence.
            return preferences.getLong("finished_revision", -1L) == config.revision()
                    && preferences.getLong("finished_due", 0L) >= due;
        } catch (ClassCastException invalid) { return true; }
    }

    synchronized boolean rememberDue(Config config, long due, java.time.ZoneId zone) {
        if (due < 1L || !config.enabled() || !get().equals(config)) return false;
        if (rememberedDue(config, zone) == due) return true;
        return commit(preferences.edit().putLong("scheduled_due", due)
                .putLong("scheduled_revision", config.revision()).putString("scheduled_zone", zone.getId()));
    }

    synchronized long rememberedDue(Config config, java.time.ZoneId zone) {
        if (!config.enabled() || !get().equals(config)) return 0L;
        try {
            return preferences.getLong("scheduled_revision", -1L) == config.revision()
                    && zone.getId().equals(preferences.getString("scheduled_zone", ""))
                    ? Math.max(0L, preferences.getLong("scheduled_due", 0L)) : 0L;
        } catch (ClassCastException invalid) { return 0L; }
    }

    synchronized Attempt begin(Config config, long due) {
        if (due < 1L || !config.enabled() || !get().equals(config) || finished(config, due)) return null;
        final long sequence;
        try { sequence = preferences.getLong("sequence", 0L); }
        catch (ClassCastException invalid) { return null; }
        if (sequence < 0L || sequence == Long.MAX_VALUE) return null;
        Status old = status();
        boolean resume = old.due() == due && old.revision() == config.revision();
        Attempt attempt = new Attempt(config, due, sequence + 1L);
        var edit = preferences.edit().putLong("sequence", attempt.id()).putLong("active_attempt", attempt.id());
        write(edit, config, due, Outcome.RUNNING, resume ? old.refreshed() : 0, resume ? old.downloaded() : 0,
                resume && old.problems(), resume ? old.transfers() : 0, resume && old.refreshComplete());
        return commit(edit) ? attempt : null;
    }

    synchronized boolean owns(Attempt attempt) {
        if (attempt == null || !attempt.config().enabled() || !attempt.config().equals(get())
                || finished(attempt.config(), attempt.due())) return false;
        try { return preferences.getLong("active_attempt", 0L) == attempt.id(); }
        catch (ClassCastException invalid) { return false; }
    }

    /** Reserve durably before transfer, so restarts and failed transfers cannot reset the work cap. */
    synchronized boolean reserveDownload(Attempt attempt) {
        if (!owns(attempt)) return false;
        Status status = status();
        return status.transfers() < SecurityLimits.MAX_PREPARATION_DOWNLOADS
                && commit(preferences.edit().putInt("transfers", status.transfers() + 1));
    }

    synchronized boolean record(Attempt attempt, Outcome outcome, int refreshed, int downloaded,
            boolean problems, boolean refreshComplete) {
        if (!owns(attempt) || outcome == Outcome.NEVER) return false;
        Status old = status();
        if (refreshed < old.refreshed() || refreshed > SecurityLimits.MAX_SUBSCRIPTIONS
                || downloaded < old.downloaded() || downloaded > old.transfers()) return false;
        var edit = preferences.edit();
        write(edit, attempt.config(), attempt.due(), outcome, refreshed, downloaded,
                problems || old.problems(), old.transfers(), refreshComplete || old.refreshComplete());
        if (outcome != Outcome.RUNNING) edit.putLong("active_attempt", 0L);
        if (outcome != Outcome.RUNNING && outcome != Outcome.PAUSED)
            edit.putLong("finished_revision", attempt.config().revision()).putLong("finished_due", attempt.due());
        return commit(edit);
    }

    synchronized void missed(Config config, long due) {
        if (due < 1L || !config.enabled() || !get().equals(config) || finished(config, due)) return;
        Status old = status();
        if (old.revision() == config.revision() && old.due() > due) return;
        boolean same = old.revision() == config.revision() && old.due() == due;
        var edit = preferences.edit().putLong("active_attempt", 0L)
                .putLong("finished_revision", config.revision()).putLong("finished_due", due);
        write(edit, config, due, Outcome.MISSED, same ? old.refreshed() : 0, same ? old.downloaded() : 0,
                same && old.problems(), same ? old.transfers() : 0, same && old.refreshComplete());
        commit(edit);
    }

    private void write(SharedPreferences.Editor edit, Config config, long due, Outcome outcome,
            int refreshed, int downloaded, boolean problems, int transfers, boolean refreshComplete) {
        edit.putString("outcome", outcome.name()).putLong("at", clock.getAsLong())
                .putInt("refreshed", refreshed).putInt("downloaded", downloaded).putBoolean("problems", problems)
                .putLong("status_due", due).putLong("status_revision", config.revision())
                .putInt("transfers", transfers).putBoolean("refresh_complete", refreshComplete);
    }

    private boolean commit(SharedPreferences.Editor edit) {
        if (edit.commit()) return true;
        storageHealthy = false;
        preferences.edit().putBoolean("enabled", false).apply();
        return false;
    }

    synchronized Status status() {
        try {
            long at = preferences.getLong("at", 0L);
            int refreshed = preferences.getInt("refreshed", 0), downloaded = preferences.getInt("downloaded", 0);
            int transfers = preferences.getInt("transfers", 0);
            if (at < 0L || refreshed < 0 || refreshed > SecurityLimits.MAX_SUBSCRIPTIONS
                    || downloaded < 0 || downloaded > transfers || transfers < 0
                    || transfers > SecurityLimits.MAX_PREPARATION_DOWNLOADS) return failedStatus();
            return new Status(Outcome.valueOf(preferences.getString("outcome", "NEVER")), at, refreshed, downloaded,
                    preferences.getBoolean("problems", false), preferences.getLong("status_due", 0L),
                    preferences.getLong("status_revision", -1L), transfers,
                    preferences.getBoolean("refresh_complete", false));
        } catch (ClassCastException | IllegalArgumentException invalid) { return failedStatus(); }
    }

    private static Status failedStatus() { return new Status(Outcome.FAILED, 0L, 0, 0, true, 0L, -1L, 0, false); }
    void register(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }
    void unregister(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
