package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting;
import io.github.jnesew.plainpoddroid.core.playback.PodcastPlaybackSettingsStore;
import io.github.jnesew.plainpoddroid.playback.PlaybackControlSettings;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.concurrent.Executor;

/** Dialog-owned podcast override editor. Every asynchronous edit retains its original podcast ID. */
public final class PodcastSpeedPanel {
    private final Context context;
    private final View controls;
    private final TextView status;
    private final Spinner choice;
    private final PodcastPlaybackSettingsStore store;
    private final PlaybackControlSettings defaults;
    private final Executor worker;
    private final Handler main;
    private long podcastId;
    private int generation, displayedPosition;
    private boolean active, loaded, saving;
    private PlaybackSpeedSetting override;

    public PodcastSpeedPanel(View root, PodcastPlaybackSettingsStore store,
            PlaybackControlSettings defaults, Executor worker, Handler main) {
        this.context = root.getContext(); this.store = store; this.defaults = defaults;
        this.worker = worker; this.main = main;
        controls = root.findViewById(R.id.podcast_speed_controls);
        status = root.findViewById(R.id.podcast_speed_status);
        choice = root.findViewById(R.id.podcast_speed_choice);
        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (active && loaded && !saving && position != displayedPosition) save(position);
            }
        });
    }

    public void open(long id) {
        close();
        active = true; podcastId = id;
        controls.setVisibility(id > 0 ? View.VISIBLE : View.GONE);
        if (id <= 0) return;
        choice.setEnabled(false); status.setVisibility(View.VISIBLE); status.setText(R.string.ui_speed_loading);
        int ticket = generation;
        try {
            worker.execute(() -> {
                PlaybackSpeedSetting value = null;
                boolean success = false;
                try { value = store.getPodcastSpeed(id); success = true; }
                catch (IOException | RuntimeException ignored) { }
                var result = value; boolean read = success;
                main.post(() -> {
                    if (!active || ticket != generation) return;
                    if (!read) { status.setText(R.string.ui_preference_save_failed); return; }
                    override = result; loaded = true; render();
                });
            });
        } catch (RuntimeException exception) { status.setText(R.string.ui_preference_save_failed); }
    }

    public void close() {
        generation++; active = false; loaded = false; saving = false;
    }

    private void render() {
        displayedPosition = override == null ? 0 : override.ordinal() + 1;
        var labels = new ArrayList<String>();
        labels.add(context.getString(R.string.ui_use_default_speed));
        for (var speed : PlaybackSpeedSetting.values()) labels.add(label(speed));
        var adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        choice.setAdapter(adapter); choice.setSelection(displayedPosition, false); choice.setEnabled(true);
        status.setVisibility(View.GONE);
    }

    private void save(int position) {
        if (position < 0 || position > PlaybackSpeedSetting.values().length) return;
        var selected = position == 0 ? null : PlaybackSpeedSetting.values()[position - 1];
        long id = podcastId; int ticket = generation;
        saving = true; choice.setEnabled(false); status.setVisibility(View.VISIBLE); status.setText(R.string.ui_speed_saving);
        try {
            worker.execute(() -> {
                boolean success = false;
                try { store.setPodcastSpeed(id, selected); success = true; }
                catch (IOException | RuntimeException ignored) { }
                // The revision belongs to the saved edit, even if its dialog was closed meanwhile.
                if (success) defaults.notifyPodcastSettingsChanged();
                boolean saved = success;
                main.post(() -> {
                    if (!active || ticket != generation) return;
                    saving = false;
                    if (saved) override = selected;
                    else Toast.makeText(context, R.string.ui_preference_save_failed, Toast.LENGTH_SHORT).show();
                    render();
                });
            });
        } catch (RuntimeException exception) {
            saving = false; render();
            Toast.makeText(context, R.string.ui_preference_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static String label(PlaybackSpeedSetting speed) {
        return NumberFormat.getNumberInstance().format(speed.getPercent() / 100.0) + "×";
    }
}
