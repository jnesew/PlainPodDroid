package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Calendar;
import java.util.Locale;

/** One opt-in recurring preparation schedule, separate from ordinary interval updates. */
final class PreparationSettingsPanel implements AutoCloseable {
    private final Activity activity;
    private final PlainPodDroidApplication app;
    private final PreparationSettings settings;
    private final Switch enabled, unmetered, charging;
    private final Button time, days;
    private final TextView next, last;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable redraw = this::refresh;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) -> {
        main.removeCallbacks(redraw); main.post(redraw);
    };
    private boolean updating, closed;
    PreparationSettingsPanel(Activity activity) {
        this.activity = activity; app = (PlainPodDroidApplication) activity.getApplication();
        settings = app.getPreparationSettings();
        enabled = activity.findViewById(R.id.preparation_enabled);
        unmetered = activity.findViewById(R.id.preparation_unmetered);
        charging = activity.findViewById(R.id.preparation_charging);
        time = activity.findViewById(R.id.preparation_time); days = activity.findViewById(R.id.preparation_days);
        next = activity.findViewById(R.id.preparation_next); last = activity.findViewById(R.id.preparation_last);
        enabled.setOnCheckedChangeListener((button, value) -> saveSwitches());
        unmetered.setOnCheckedChangeListener((button, value) -> saveSwitches());
        charging.setOnCheckedChangeListener((button, value) -> saveSwitches());
        time.setOnClickListener(view -> chooseTime()); days.setOnClickListener(view -> chooseDays());
        settings.register(listener); refresh();
    }
    private void saveSwitches() {
        if (updating) return;
        var config = settings.get();
        save(enabled.isChecked(), config.schedule().minuteOfDay(), config.schedule().days(),
                unmetered.isChecked(), charging.isChecked());
    }
    private void save(boolean on, int minute, int mask, boolean wifi, boolean charge) {
        if (!settings.save(on, minute, mask, wifi, charge))
            Toast.makeText(activity, R.string.preparation_save_failed, Toast.LENGTH_LONG).show();
        reconcile();
    }
    void reconcile() {
        if (!app.getPreparationScheduler().reconcile())
            Toast.makeText(activity, R.string.preparation_schedule_failed, Toast.LENGTH_LONG).show();
        refresh();
    }
    private void chooseTime() {
        var config = settings.get(); int minute = config.schedule().minuteOfDay();
        new TimePickerDialog(activity, (picker, hour, min) -> {
            var current = settings.get();
            save(current.enabled(), hour * 60 + min, current.schedule().days(), current.unmetered(), current.charging());
        },
                minute / 60, minute % 60, DateFormat.is24HourFormat(activity)).show();
    }
    private String[] dayLabels() {
        String[] labels = new String[7];
        for (int i = 0; i < 7; i++) labels[i] = DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault());
        return labels;
    }
    private void chooseDays() {
        var config = settings.get(); boolean[] chosen = new boolean[7];
        for (int i = 0; i < 7; i++) chosen[i] = (config.schedule().days() & (1 << i)) != 0;
        new AlertDialog.Builder(activity).setTitle(R.string.preparation_days)
                .setMultiChoiceItems(dayLabels(), chosen, (dialog, which, checked) -> chosen[which] = checked)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int mask = 0; for (int i = 0; i < 7; i++) if (chosen[i]) mask |= 1 << i;
                    if (mask == 0) { Toast.makeText(activity, R.string.preparation_choose_day, Toast.LENGTH_SHORT).show(); return; }
                    var current = settings.get();
                    save(current.enabled(), current.schedule().minuteOfDay(), mask, current.unmetered(), current.charging());
                }).show();
    }
    void refresh() {
        if (closed) return;
        var config = settings.get(); updating = true;
        enabled.setChecked(config.enabled()); unmetered.setChecked(config.unmetered()); charging.setChecked(config.charging());
        updating = false;
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, config.schedule().minuteOfDay() / 60);
        calendar.set(Calendar.MINUTE, config.schedule().minuteOfDay() % 60);
        time.setText(activity.getString(R.string.preparation_time_format, DateFormat.getTimeFormat(activity).format(calendar.getTime())));
        StringBuilder names = new StringBuilder(); String[] labels = dayLabels();
        for (int i = 0; i < 7; i++) if ((config.schedule().days() & (1 << i)) != 0) {
            if (names.length() > 0) names.append(", "); names.append(labels[i]);
        }
        days.setText(activity.getString(R.string.preparation_days_format, names));
        if (!config.enabled()) next.setText(R.string.preparation_off);
        else if (app.isOfflineModeEnabled()) next.setText(R.string.preparation_offline);
        else {
            long due = app.getPreparationScheduler().nextDueMillis();
            next.setText(due == 0L ? activity.getString(R.string.preparation_schedule_failed)
                    : due <= System.currentTimeMillis() ? activity.getString(R.string.preparation_waiting)
                    : activity.getString(R.string.preparation_next_format, dateTime(due)));
        }
        var status = settings.status();
        if (status.outcome() == PreparationSettings.Outcome.NEVER) last.setText(R.string.preparation_never);
        else {
            int outcome = switch (status.outcome()) {
                case NEVER -> R.string.preparation_never;
                case RUNNING -> R.string.preparation_running;
                case COMPLETE -> R.string.preparation_complete;
                case PARTIAL -> R.string.preparation_partial;
                case PAUSED -> R.string.preparation_paused;
                case FAILED -> R.string.preparation_failed;
                case MISSED -> R.string.preparation_missed;
            };
            if (status.outcome() == PreparationSettings.Outcome.RUNNING || status.outcome() == PreparationSettings.Outcome.PAUSED) {
                if (System.currentTimeMillis() - status.due()
                        > io.github.jnesew.plainpoddroid.core.refresh.WeeklyPreparationSchedule.MAX_LATENESS_MILLIS)
                    outcome = R.string.preparation_missed;
                else if (!config.enabled() || status.revision() != config.revision() || app.isOfflineModeEnabled())
                    outcome = R.string.preparation_paused;
            }
            last.setText(activity.getString(R.string.preparation_last_format,
                    status.finishedAt() > 0 ? dateTime(status.finishedAt()) : activity.getString(R.string.preparation_unknown_time),
                    activity.getString(outcome),
                    activity.getResources().getQuantityString(R.plurals.preparation_refreshed_count, status.refreshed(), status.refreshed()),
                    activity.getResources().getQuantityString(R.plurals.preparation_downloaded_count, status.downloaded(), status.downloaded())));
        }
    }
    private String dateTime(long time) {
        return DateUtils.formatDateTime(activity, time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
    }
    @Override public void close() {
        closed = true; settings.unregister(listener); main.removeCallbacks(redraw);
    }
}
