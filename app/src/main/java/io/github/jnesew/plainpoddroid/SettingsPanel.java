package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import io.github.jnesew.plainpoddroid.core.download.DownloadQuota;
import io.github.jnesew.plainpoddroid.core.download.DownloadStorageController;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadActions;
import io.github.jnesew.plainpoddroid.core.playback.CompletionMode;
import io.github.jnesew.plainpoddroid.core.refresh.RefreshInterval;
import io.github.jnesew.plainpoddroid.playback.PlaybackCompletionSettings;
import io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Settings view ownership; storage work remains activity-owned across navigation. */
final class SettingsPanel {
    private PlaybackCompletionSettings playbackCompletionSettings;
    private Spinner playbackCompletionModeSpinner;
    private boolean updatingCompletionModeUi;
    private DownloadRetentionSettings downloadRetentionSettings;
    private Switch autoDeleteCompletedSwitch;
    private boolean updatingAutoDeleteUi;
    private Switch neverStreamAudioSwitch;
    private Switch downloadBeforePlayingSwitch;
    private boolean updatingPrivacySwitch;

    private DownloadStorageSettings downloadStorageSettings;
    private DownloadStorageController downloadStorageController;
    private Spinner downloadStorageQuotaSpinner;
    private TextView downloadStorageSummary;
    private DownloadQuota[] downloadStorageQuotas;
    private boolean updatingDownloadStorageUi;
    private AutomaticDownloadSettings automaticDownloadSettings;
    private AutomaticDownloadScheduler automaticDownloadScheduler;
    private Switch automaticDownloadUnmeteredSwitch;
    private boolean updatingAutomaticDownloadNetworkUi;

    private AutomaticRefreshSettings automaticRefreshSettings;
    private AutomaticRefreshScheduler automaticRefreshScheduler;
    private Spinner automaticRefreshIntervalSpinner;
    private Switch automaticRefreshUnmeteredSwitch;
    private RefreshInterval[] automaticRefreshIntervals;
    private boolean updatingAutomaticRefreshUi;
    private DisplayThemeSettings displayThemeSettings;
    private Spinner displayThemeSpinner;
    private boolean updatingDisplayThemeUi;
    private PreparationSettingsPanel preparationPanel;

    private void configureDownloadStorageUi() {
        PlainPodDroidApplication application =
                (PlainPodDroidApplication) activity.getApplication();
        downloadStorageSettings = application.getDownloadStorageSettings();
        downloadStorageQuotaSpinner = activity.findViewById(R.id.download_storage_quota);
        downloadStorageSummary = activity.findViewById(R.id.download_storage_summary);
        downloadStorageQuotas = DownloadQuota.values();

        List<String> labels = new ArrayList<>(downloadStorageQuotas.length);
        for (DownloadQuota quota : downloadStorageQuotas) {
            labels.add(downloadQuotaLabel(quota));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        updatingDownloadStorageUi = true;
        downloadStorageQuotaSpinner.setAdapter(adapter);
        downloadStorageQuotaSpinner.setSelection(
                downloadStorageSettings.getQuota().ordinal(),
                false);
        downloadStorageQuotaSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long itemId) {
                        onDownloadStorageQuotaSelected(position);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // A missing choice never changes persisted policy.
                    }
                });
        updatingDownloadStorageUi = false;

        EpisodeDownloadActions actions = application.getEpisodeDownloadActions();
        if (actions != null) {
            downloadStorageController = new DownloadStorageController(
                    actions,
                    screenWorker,
                    command -> mainHandler.post(command),
                    this::onDownloadStorageStateChanged);
        }
        renderDownloadStorageState();
        if (downloadStorageController != null) {
            downloadStorageController.refresh();
        }
    }

    private void onDownloadStorageQuotaSelected(int position) {
        if (updatingDownloadStorageUi
                || position < 0
                || position >= downloadStorageQuotas.length) {
            return;
        }
        if (downloadBusy.getAsBoolean() || isStorageBusy()) {
            refreshDownloadStorageSettings();
            return;
        }
        DownloadQuota quota = downloadStorageQuotas[position];
        if (!downloadStorageSettings.setQuota(quota)) {
            Toast.makeText(
                    activity,
                    R.string.download_storage_save_failed,
                    Toast.LENGTH_LONG).show();
            refreshDownloadStorageSettings();
            return;
        }
        renderDownloadStorageState();
        if (downloadStorageController != null) {
            downloadStorageController.refresh();
        }
    }

    private void refreshDownloadStorageSettings() {
        DownloadQuota quota = downloadStorageSettings.getQuota();
        updatingDownloadStorageUi = true;
        downloadStorageQuotaSpinner.setSelection(quota.ordinal(), false);
        updatingDownloadStorageUi = false;
        renderDownloadStorageState();
    }

    private void onDownloadStorageStateChanged(DownloadStorageController.State state) {
        renderDownloadStorageState();
        storageChanged.run();
    }

    private void renderDownloadStorageState() {
        DownloadQuota quota = downloadStorageSettings.getQuota();
        downloadStorageQuotaSpinner.setEnabled(
                !downloadBusy.getAsBoolean() && !isStorageBusy());
        if (downloadStorageController == null) {
            downloadStorageSummary.setText(R.string.download_storage_error_unavailable);
            return;
        }
        DownloadStorageController.State state = downloadStorageController.getState();
        switch (state.getPhase()) {
            case IDLE:
            case LOADING:
                downloadStorageSummary.setText(R.string.download_storage_usage_loading);
                break;
            case READY:
                downloadStorageSummary.setText(activity.getString(
                        R.string.download_storage_usage_format,
                        formatDownloadBytes(state.getUsageBytes()),
                        downloadQuotaLabel(quota)));
                break;
            case ERROR:
                downloadStorageSummary.setText(state.getFailure()
                        == DownloadStorageController.Failure.BACKGROUND_WORK_UNAVAILABLE
                        ? R.string.download_storage_error_background
                        : R.string.download_storage_error_unavailable);
                break;
            default:
                throw new AssertionError("Unknown download storage phase.");
        }
    }

    private String downloadQuotaLabel(DownloadQuota quota) {
        switch (quota) {
            case MIB_512:
                return activity.getString(R.string.download_storage_quota_512_mib);
            case GIB_1:
                return activity.getString(R.string.download_storage_quota_1_gib);
            case GIB_2:
                return activity.getString(R.string.download_storage_quota_2_gib);
            case GIB_5:
                return activity.getString(R.string.download_storage_quota_5_gib);
            case GIB_10:
                return activity.getString(R.string.download_storage_quota_10_gib);
            case GIB_20:
                return activity.getString(R.string.download_storage_quota_20_gib);
            default:
                throw new AssertionError("Unknown download quota.");
        }
    }

    private static String formatDownloadBytes(long bytes) {
        long mebibyte = 1024L * 1024L;
        long gibibyte = 1024L * mebibyte;
        if (bytes < mebibyte) {
            return String.format(Locale.getDefault(), "%,d B", bytes);
        }
        if (bytes < gibibyte) {
            return String.format(
                    Locale.getDefault(),
                    "%.1f MiB",
                    bytes / (double) mebibyte);
        }
        return String.format(
                Locale.getDefault(),
                "%.1f GiB",
                bytes / (double) gibibyte);
    }

    private void configureAutomaticRefreshUi() {
        PlainPodDroidApplication application =
                (PlainPodDroidApplication) activity.getApplication();
        automaticRefreshSettings = application.getAutomaticRefreshSettings();
        automaticRefreshScheduler = application.getAutomaticRefreshScheduler();
        automaticRefreshIntervalSpinner = activity.findViewById(R.id.automatic_refresh_interval);
        automaticRefreshUnmeteredSwitch = activity.findViewById(R.id.automatic_refresh_unmetered);
        automaticRefreshIntervals = RefreshInterval.values();

        List<String> labels = new ArrayList<>(automaticRefreshIntervals.length);
        for (RefreshInterval interval : automaticRefreshIntervals) {
            labels.add(refreshIntervalLabel(interval));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        automaticRefreshIntervalSpinner.setAdapter(adapter);
        automaticRefreshIntervalSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long itemId) {
                        onAutomaticRefreshIntervalSelected(position);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // A missing choice never changes persisted policy.
                    }
                });
        automaticRefreshUnmeteredSwitch.setOnCheckedChangeListener(
                this::onAutomaticRefreshNetworkChanged);
        refreshAutomaticRefreshSettings();
    }

    private void configureAutomaticDownloadNetworkUi() {
        PlainPodDroidApplication application =
                (PlainPodDroidApplication) activity.getApplication();
        automaticDownloadSettings = application.getAutomaticDownloadSettings();
        automaticDownloadScheduler = application.getAutomaticDownloadScheduler();
        automaticDownloadUnmeteredSwitch = activity.findViewById(R.id.automatic_download_unmetered);
        updatingAutomaticDownloadNetworkUi = true;
        automaticDownloadUnmeteredSwitch.setChecked(
                automaticDownloadSettings.isUnmeteredOnly());
        updatingAutomaticDownloadNetworkUi = false;
        automaticDownloadUnmeteredSwitch.setOnCheckedChangeListener((button, value) -> {
            if (updatingAutomaticDownloadNetworkUi) return;
            if (!automaticDownloadSettings.setUnmeteredOnly(value)) {
                Toast.makeText(activity, R.string.automatic_download_network_save_failed,
                        Toast.LENGTH_LONG).show();
                updatingAutomaticDownloadNetworkUi = true;
                automaticDownloadUnmeteredSwitch.setChecked(
                        automaticDownloadSettings.isUnmeteredOnly());
                updatingAutomaticDownloadNetworkUi = false;
                return;
            }
            if (!automaticDownloadScheduler.reconcile()) {
                Toast.makeText(activity, R.string.automatic_download_schedule_failed,
                        Toast.LENGTH_LONG).show();
            }
            if (preparationPanel != null) preparationPanel.reconcile();
        });
    }

    private void configureDisplayThemeUi() {
        displayThemeSettings = new DisplayThemeSettings(activity);
        displayThemeSpinner = activity.findViewById(R.id.display_theme);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(activity,
                R.array.display_theme_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        displayThemeSpinner.setAdapter(adapter);
        updatingDisplayThemeUi = true;
        displayThemeSpinner.setSelection(displayThemeSettings.getTheme().ordinal(), false);
        updatingDisplayThemeUi = false;
        displayThemeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                    int position, long id) {
                if (updatingDisplayThemeUi || position < 0
                        || position >= DisplayTheme.values().length) return;
                DisplayTheme selected = DisplayTheme.values()[position];
                if (selected == displayThemeSettings.getTheme()) return;
                if (!displayThemeSettings.setTheme(selected)) {
                    Toast.makeText(activity, R.string.display_theme_save_failed,
                            Toast.LENGTH_LONG).show();
                    updatingDisplayThemeUi = true;
                    displayThemeSpinner.setSelection(
                            displayThemeSettings.getTheme().ordinal(), false);
                    updatingDisplayThemeUi = false;
                    return;
                }
                activity.recreate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No selection is not a persisted theme change.
            }
        });
    }

    private void configureCompletionModeUi() {
        playbackCompletionModeSpinner = activity.findViewById(R.id.playback_completion_mode);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(activity,
                R.array.playback_completion_modes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        playbackCompletionModeSpinner.setAdapter(adapter);
        updatingCompletionModeUi = true;
        playbackCompletionModeSpinner.setSelection(
                playbackCompletionSettings.getMode().ordinal(), false);
        updatingCompletionModeUi = false;
        playbackCompletionModeSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                            int position, long id) {
                        if (updatingCompletionModeUi || position < 0
                                || position >= CompletionMode.values().length) return;
                        CompletionMode selected = CompletionMode.values()[position];
                        if (selected == playbackCompletionSettings.getMode()) return;
                        if (!playbackCompletionSettings.setMode(selected)) {
                            Toast.makeText(activity,
                                    R.string.playback_completion_save_failed,
                                    Toast.LENGTH_LONG).show();
                            updatingCompletionModeUi = true;
                            playbackCompletionModeSpinner.setSelection(
                                    playbackCompletionSettings.getMode().ordinal(), false);
                            updatingCompletionModeUi = false;
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // No selection is not a persisted completion change.
                    }
                });
    }

    private void configureAutoDeleteUi() {
        autoDeleteCompletedSwitch = activity.findViewById(R.id.auto_delete_completed);
        autoDeleteCompletedSwitch.setChecked(downloadRetentionSettings.isAutoDeleteCompleted());
        autoDeleteCompletedSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (updatingAutoDeleteUi) return;
            if (!downloadRetentionSettings.setAutoDeleteCompleted(enabled)) {
                Toast.makeText(activity, downloadRetentionSettings.isAutoDeleteCompleted()
                        ? R.string.auto_delete_save_failed
                        : R.string.auto_delete_save_failed_off, Toast.LENGTH_LONG).show();
                updatingAutoDeleteUi = true;
                autoDeleteCompletedSwitch.setChecked(
                        downloadRetentionSettings.isAutoDeleteCompleted());
                updatingAutoDeleteUi = false;
            }
        });
    }

    private void onAutomaticRefreshIntervalSelected(int position) {
        if (updatingAutomaticRefreshUi
                || position < 0
                || position >= automaticRefreshIntervals.length) {
            return;
        }
        RefreshInterval interval = automaticRefreshIntervals[position];
        if (!automaticRefreshSettings.setInterval(interval)) {
            Toast.makeText(
                    activity,
                    R.string.automatic_refresh_save_failed,
                    Toast.LENGTH_LONG).show();
            refreshAutomaticRefreshSettings();
            return;
        }
        automaticRefreshUnmeteredSwitch.setEnabled(interval.isEnabled());
        reconcileAutomaticRefreshSchedule();
    }

    private void onAutomaticRefreshNetworkChanged(
            CompoundButton button,
            boolean unmeteredOnly) {
        if (updatingAutomaticRefreshUi) {
            return;
        }
        if (!automaticRefreshSettings.setUnmeteredOnly(unmeteredOnly)) {
            Toast.makeText(
                    activity,
                    R.string.automatic_refresh_save_failed,
                    Toast.LENGTH_LONG).show();
            refreshAutomaticRefreshSettings();
            return;
        }
        reconcileAutomaticRefreshSchedule();
    }

    private void reconcileAutomaticRefreshSchedule() {
        if (preparationPanel != null) preparationPanel.reconcile();
        if (!automaticRefreshScheduler.reconcile()) {
            Toast.makeText(
                    activity,
                    R.string.automatic_refresh_schedule_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAutomaticRefreshSettings() {
        RefreshInterval interval = automaticRefreshSettings.getInterval();
        boolean unmeteredOnly = automaticRefreshSettings.isUnmeteredOnly();
        updatingAutomaticRefreshUi = true;
        automaticRefreshIntervalSpinner.setSelection(interval.ordinal());
        automaticRefreshUnmeteredSwitch.setChecked(unmeteredOnly);
        automaticRefreshUnmeteredSwitch.setEnabled(interval.isEnabled());
        updatingAutomaticRefreshUi = false;
    }

    private String refreshIntervalLabel(RefreshInterval interval) {
        switch (interval) {
            case OFF:
                return activity.getString(R.string.automatic_refresh_interval_off);
            case ONE_HOUR:
                return activity.getString(R.string.automatic_refresh_interval_1_hour);
            case THREE_HOURS:
                return activity.getString(R.string.automatic_refresh_interval_3_hours);
            case SIX_HOURS:
                return activity.getString(R.string.automatic_refresh_interval_6_hours);
            case TWELVE_HOURS:
                return activity.getString(R.string.automatic_refresh_interval_12_hours);
            case TWENTY_FOUR_HOURS:
                return activity.getString(R.string.automatic_refresh_interval_24_hours);
            default:
                throw new AssertionError("Unknown refresh interval.");
        }
    }

    private void onPrivacySettingChanged(CompoundButton button, boolean enabled) {
        if (updatingPrivacySwitch) {
            return;
        }
        if (!playbackPrivacySettings.setNeverStreamAudioEnabled(enabled)) {
            Toast.makeText(
                    activity,
                    R.string.never_stream_audio_save_failed,
                    Toast.LENGTH_LONG).show();
            refreshPrivacySetting();
        }
        audioPolicyChanged.run();
        if (preparationPanel != null) preparationPanel.refresh();
    }

    private void refreshPrivacySetting() {
        boolean enabled = playbackPrivacySettings.isNeverStreamAudioEnabled();
        updatingPrivacySwitch = true;
        neverStreamAudioSwitch.setChecked(enabled);
        if (downloadBeforePlayingSwitch != null)
            downloadBeforePlayingSwitch.setChecked(playbackPrivacySettings.isDownloadBeforePlayingEnabled());
        updatingPrivacySwitch = false;
    }

    private final Activity activity;
    private final Executor screenWorker;
    private final Handler mainHandler;
    private final PlaybackPrivacySettings playbackPrivacySettings;
    private final BooleanSupplier downloadBusy;
    private final Runnable storageChanged;
    private final Runnable audioPolicyChanged;

    public SettingsPanel(Activity activity, Executor worker, Handler handler,
            PlaybackPrivacySettings privacy, BooleanSupplier downloadBusy,
            Runnable storageChanged, Runnable audioPolicyChanged) {
        this.activity = activity;
        this.screenWorker = worker;
        this.mainHandler = handler;
        this.playbackPrivacySettings = privacy;
        this.downloadBusy = downloadBusy;
        this.storageChanged = storageChanged;
        this.audioPolicyChanged = audioPolicyChanged;
        playbackCompletionSettings = new PlaybackCompletionSettings(activity);
        downloadRetentionSettings = ((PlainPodDroidApplication) activity.getApplication())
                .getDownloadRetentionSettings();
        neverStreamAudioSwitch = activity.findViewById(R.id.never_stream_audio);
        downloadBeforePlayingSwitch = activity.findViewById(R.id.download_before_playing);
        refreshPrivacySetting();
        neverStreamAudioSwitch.setOnCheckedChangeListener(this::onPrivacySettingChanged);
        downloadBeforePlayingSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (updatingPrivacySwitch) return;
            if (!playbackPrivacySettings.setDownloadBeforePlayingEnabled(enabled)) {
                Toast.makeText(activity, R.string.download_before_playing_save_failed, Toast.LENGTH_LONG).show();
                refreshPrivacySetting();
            }
            audioPolicyChanged.run();
        });
        activity.findViewById(R.id.playback_setting_details).setOnClickListener(v ->
                new android.app.AlertDialog.Builder(activity).setTitle(R.string.ui_playback_info)
                        .setMessage(R.string.ui_playback_info_text).setPositiveButton(android.R.string.ok, null).show());
        configureCompletionModeUi();
        configureAutoDeleteUi();
        configureDownloadStorageUi();
        configureAutomaticRefreshUi();
        configureAutomaticDownloadNetworkUi();
        configureDisplayThemeUi();
        preparationPanel = new PreparationSettingsPanel(activity);
    }

    public void resume() {
        preparationPanel.reconcile();
        refreshPrivacySetting();
        refreshAutomaticRefreshSettings();
        refreshDownloadStorageSettings();
        refreshStorage();
    }
    public boolean isStorageBusy() {
        return downloadStorageController != null && downloadStorageController.getState().isBusy();
    }
    public void refreshStorage() {
        if (downloadStorageController != null) downloadStorageController.refresh();
    }
    public void renderStorage() { renderDownloadStorageState(); }
    public void close() {
        if (preparationPanel != null) preparationPanel.close();
        if (downloadStorageController != null) downloadStorageController.close();
    }
}
