package io.github.jnesew.plainpoddroid.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.text.format.DateUtils;
import android.widget.Toast;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackActions;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackConnection;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackSkipInterval;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting;
import io.github.jnesew.plainpoddroid.core.playback.SleepTimerOption;
import io.github.jnesew.plainpoddroid.playback.PlaybackControlSettings;
import io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings;
import io.github.jnesew.plainpoddroid.playback.PlaybackService;
import io.github.jnesew.plainpoddroid.playback.PlaybackSleepTimerCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;


/** Activity-owned playback binding shared by browsing and episode controls. */
public final class PlaybackPanel {
    private TextView playbackStatus;
    private View miniPlayer;
    private TextView miniTitle;
    private ProgressBar miniProgress;
    private SeekBar episodeSeek;
    private ChapterPanel chapterPanel;
    private TextView episodePosition, episodeDuration;
    private EpisodeDetails selectedEpisode;
    private boolean episodeVisible, started, scrubbing;
    private View playbackOptions;
    private AlertDialog optionsDialog;
    private final Runnable positionRefresher = this::tickPositions;
    private Button playbackToggleButton;
    private Button playbackStopButton;
    private Button playbackSkipBackButton;
    private Button playbackSkipForwardButton;
    private Spinner playbackSkipBackSpinner;
    private Spinner playbackSkipForwardSpinner;
    private Spinner playbackSpeedSpinner;
    private Spinner playbackSleepTimerSpinner;
    private Button playbackSleepTimerSetButton;
    private Button playbackSleepTimerCancelButton;
    private TextView playbackSleepTimerStatus;
    private PlaybackSkipInterval[] playbackSkipIntervals;
    private PlaybackSpeedSetting[] playbackSpeeds;
    private SleepTimerOption[] playbackSleepTimerOptions;
    private PlaybackSleepTimerCommands.Status playbackSleepTimerSnapshot;
    private boolean playbackSleepTimerStatusKnown;
    private boolean updatingPlaybackSkipUi;
    private final Runnable sleepTimerStatusRefresher = this::requestSleepTimerStatus;
    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            renderPlaybackState();
            playbackChanged.run();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            renderPlaybackState();
            if (started) {
                int message = R.string.playback_status_error;
                Throwable cause = error;
                for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                    if (cause instanceof FeedLoginException) { message = R.string.login_needed; break; }
                    if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException http
                            && (http.responseCode == 401 || http.responseCode == 403)) {
                        message = http.responseCode == 401 ? R.string.login_needed : R.string.login_denied; break;
                    }
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        }
    };

    private void configurePlaybackUi() {
        miniPlayer = activity.findViewById(R.id.mini_player);
        miniTitle = activity.findViewById(R.id.mini_title);
        miniProgress = activity.findViewById(R.id.mini_progress);
        episodeSeek = activity.findViewById(R.id.episode_seek);
        episodePosition = activity.findViewById(R.id.episode_position);
        episodeDuration = activity.findViewById(R.id.episode_duration);
        playbackOptions = activity.findViewById(R.id.playback_options);
        episodeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser) renderPositions(); }
            public void onStartTrackingTouch(SeekBar bar) { scrubbing = true; renderPositions(); }
            public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                MediaController controller = connection.getConnected();
                if (controller != null && selectedEpisode != null) {
                    long duration = controller.getDuration();
                    if (duration > 0 && duration <= io.github.jnesew.plainpoddroid.core.security.SecurityLimits.MAX_MEDIA_DURATION_MILLIS) {
                        actions.seekCurrent(selectedEpisode.getMediaId(), duration * bar.getProgress() / 1000L);
                    }
                }
                renderPositions();
            }
        });
        playbackStatus = activity.findViewById(R.id.playback_status);
        playbackToggleButton = activity.findViewById(R.id.playback_toggle);
        playbackStopButton = activity.findViewById(R.id.playback_stop);
        playbackSkipBackButton = activity.findViewById(R.id.playback_skip_back);
        playbackSkipForwardButton = activity.findViewById(R.id.playback_skip_forward);
        playbackSleepTimerSpinner = activity.findViewById(R.id.playback_sleep_timer_option);
        playbackSleepTimerSetButton = activity.findViewById(R.id.playback_sleep_timer_set);
        playbackSleepTimerCancelButton = activity.findViewById(R.id.playback_sleep_timer_cancel);
        playbackSleepTimerStatus = activity.findViewById(R.id.playback_sleep_timer_status);
        playbackToggleButton.setOnClickListener(ignored -> togglePlayback());
        playbackStopButton.setOnClickListener(ignored -> stopPlayback());
        playbackStopButton.setVisibility(View.GONE); // Stop is directly available in the player overflow.
        playbackSkipBackButton.setOnClickListener(ignored -> skipPlaybackBack());
        playbackSkipForwardButton.setOnClickListener(ignored -> skipPlaybackForward());
        playbackSleepTimerSetButton.setOnClickListener(ignored -> armSleepTimer());
        playbackSleepTimerCancelButton.setOnClickListener(ignored -> cancelSleepTimer());
        playbackSleepTimerOptions = SleepTimerOption.values();
        List<String> sleepTimerLabels = new ArrayList<>(playbackSleepTimerOptions.length);
        int defaultIndex = 0;
        for (int index = 0; index < playbackSleepTimerOptions.length; index++) {
            SleepTimerOption option = playbackSleepTimerOptions[index];
            sleepTimerLabels.add(sleepTimerLabel(option));
            if (option == SleepTimerOption.MINUTES_15) {
                defaultIndex = index;
            }
        }
        ArrayAdapter<String> sleepTimerAdapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                sleepTimerLabels);
        sleepTimerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        playbackSleepTimerSpinner.setAdapter(sleepTimerAdapter);
        playbackSleepTimerSpinner.setSelection(defaultIndex);
        updatePlaybackSkipControlLabels();
        renderPlaybackState();
    }

    private void togglePlayback() {
        actions.toggle();
        renderPlaybackState();
    }

    private void skipPlaybackBack() {
        actions.skipBack();
        renderPlaybackState();
    }

    private void skipPlaybackForward() {
        actions.skipForward();
        renderPlaybackState();
    }

    private void renderPlaybackState() {
        renderChrome();
        if (playbackStatus == null
                || playbackToggleButton == null
                || playbackStopButton == null
                || playbackSkipBackButton == null
                || playbackSkipForwardButton == null) {
            return;
        }
        MediaController controller = connection.getConnected();
        renderSleepTimerControls();
        if (controller == null) {
            playbackStatus.setText(!connection.isConnecting()
                    ? R.string.playback_status_unavailable
                    : R.string.playback_status_connecting);
            UiWidgets.icon(playbackToggleButton, R.drawable.ic_play, activity.getString(R.string.ui_play), true);
            playbackToggleButton.setEnabled(false);
            playbackStopButton.setEnabled(false);
            playbackSkipBackButton.setEnabled(false);
            playbackSkipForwardButton.setEnabled(false);
            return;
        }
        MediaItem item = controller.getCurrentMediaItem();
        if (item == null) {
            playbackStatus.setText(R.string.playback_status_idle);
            UiWidgets.icon(playbackToggleButton, R.drawable.ic_play, activity.getString(R.string.ui_play), true);
            playbackToggleButton.setEnabled(false);
            playbackStopButton.setEnabled(false);
            playbackSkipBackButton.setEnabled(false);
            playbackSkipForwardButton.setEnabled(false);
            return;
        }
        String title = playbackTitle(item.mediaMetadata);
        if (controller.getPlayerError() != null) {
            playbackStatus.setText(R.string.playback_status_error);
        } else if (controller.getPlaybackState() == Player.STATE_IDLE) {
            playbackStatus.setText(activity.getString(
                    R.string.playback_status_stopped_format,
                    title));
        } else if (controller.getPlaybackState() == Player.STATE_BUFFERING) {
            playbackStatus.setText(activity.getString(
                    item.localConfiguration != null
                            && io.github.jnesew.plainpoddroid.core.playback.DownloadPlaybackUri.SCHEME.equals(
                                    item.localConfiguration.uri.getScheme())
                            ? R.string.playback_status_downloading_format : R.string.playback_status_buffering_format,
                    title));
        } else if (controller.isPlaying()) {
            playbackStatus.setText(activity.getString(
                    R.string.playback_status_playing_format,
                    title));
        } else if (controller.getPlaybackState() == Player.STATE_ENDED) {
            playbackStatus.setText(activity.getString(
                    R.string.playback_status_ended_format,
                    title));
        } else {
            playbackStatus.setText(activity.getString(
                    R.string.playback_status_paused_format,
                    title));
        }
        boolean playing = controller.getPlayWhenReady() && controller.getPlaybackState() != Player.STATE_IDLE
                && controller.getPlaybackState() != Player.STATE_ENDED;
        UiWidgets.icon(playbackToggleButton, playing ? R.drawable.ic_pause : R.drawable.ic_play,
                activity.getString(playing ? R.string.ui_pause : R.string.ui_play), true);
        playbackToggleButton.setContentDescription(activity.getString(
                playing
                        ? R.string.playback_pause_description
                        : R.string.playback_resume_description,
                title));
        playbackToggleButton.setEnabled(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));
        playbackStopButton.setContentDescription(activity.getString(
                R.string.playback_stop_description,
                title));
        playbackStopButton.setEnabled(true);
        playbackSkipBackButton.setEnabled(isSelectedCurrent() &&
                controller.isCommandAvailable(Player.COMMAND_SEEK_BACK));
        playbackSkipForwardButton.setEnabled(isSelectedCurrent() &&
                controller.isCommandAvailable(Player.COMMAND_SEEK_FORWARD));
        updatePlaybackSkipControlDescriptions(title);
    }

    private void armSleepTimer() {
        MediaController controller = connection.getConnected();
        int selection = playbackSleepTimerSpinner.getSelectedItemPosition();
        if (controller == null
                || selection < 0
                || selection >= playbackSleepTimerOptions.length
                || !controller.isSessionCommandAvailable(
                PlaybackSleepTimerCommands.armCommand())) {
            return;
        }
        SleepTimerOption option = playbackSleepTimerOptions[selection];
        sendSleepTimerCommand(
                controller,
                PlaybackSleepTimerCommands.armCommand(),
                PlaybackSleepTimerCommands.armArguments(option));
    }

    private void cancelSleepTimer() {
        MediaController controller = connection.getConnected();
        if (controller == null
                || !controller.isSessionCommandAvailable(
                PlaybackSleepTimerCommands.cancelCommand())) {
            return;
        }
        sendSleepTimerCommand(
                controller,
                PlaybackSleepTimerCommands.cancelCommand(),
                Bundle.EMPTY);
    }

    private void requestSleepTimerStatus() {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(sleepTimerStatusRefresher);
        }
        MediaController controller = connection.getConnected();
        if (controller == null
                || !controller.isSessionCommandAvailable(
                PlaybackSleepTimerCommands.getStatusCommand())) {
            renderSleepTimerControls();
            return;
        }
        sendSleepTimerCommand(
                controller,
                PlaybackSleepTimerCommands.getStatusCommand(),
                Bundle.EMPTY);
    }

    private void sendSleepTimerCommand(
            MediaController controller,
            SessionCommand command,
            Bundle args) {
        ListenableFuture<SessionResult> pending = controller.sendCustomCommand(command, args);
        pending.addListener(
                () -> finishSleepTimerCommand(controller, pending),
                activity.getMainExecutor());
    }

    private void finishSleepTimerCommand(
            MediaController controller,
            ListenableFuture<SessionResult> pending) {
        if (connection.getConnected() != controller) {
            return;
        }
        try {
            SessionResult result = pending.get();
            if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                applySleepTimerStatus(result.extras);
            } else {
                playbackSleepTimerStatus.setText(R.string.playback_sleep_timer_error);
            }
        } catch (CancellationException | ExecutionException exception) {
            playbackSleepTimerStatus.setText(R.string.playback_sleep_timer_error);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            playbackSleepTimerStatus.setText(R.string.playback_sleep_timer_error);
        }
    }

    private void applySleepTimerStatus(Bundle extras) {
        try {
            playbackSleepTimerSnapshot = PlaybackSleepTimerCommands.readStatus(extras);
            playbackSleepTimerStatusKnown = true;
        } catch (RuntimeException exception) {
            playbackSleepTimerSnapshot = null;
            playbackSleepTimerStatusKnown = false;
            renderSleepTimerControls();
            playbackSleepTimerStatus.setText(R.string.playback_sleep_timer_error);
            return;
        }
        renderSleepTimerControls();
        if (playbackSleepTimerSnapshot.isActive()
                && !playbackSleepTimerSnapshot.getOption().isEndOfEpisode()
                && mainHandler != null) {
            mainHandler.removeCallbacks(sleepTimerStatusRefresher);
            mainHandler.postDelayed(sleepTimerStatusRefresher, 60_000L);
        }
    }

    private void clearSleepTimerUiState() {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(sleepTimerStatusRefresher);
        }
        playbackSleepTimerSnapshot = null;
        playbackSleepTimerStatusKnown = false;
    }

    private void renderSleepTimerControls() {
        if (playbackSleepTimerSpinner == null
                || playbackSleepTimerSetButton == null
                || playbackSleepTimerCancelButton == null
                || playbackSleepTimerStatus == null) {
            return;
        }
        MediaController controller = connection.getConnected();
        boolean mayArm = controller != null
                && controller.getCurrentMediaItem() != null
                && controller.getPlaybackState() != Player.STATE_IDLE
                && controller.getPlaybackState() != Player.STATE_ENDED
                && controller.isSessionCommandAvailable(
                PlaybackSleepTimerCommands.armCommand());
        boolean mayCancel = controller != null
                && playbackSleepTimerStatusKnown
                && playbackSleepTimerSnapshot != null
                && playbackSleepTimerSnapshot.isActive()
                && controller.isSessionCommandAvailable(
                PlaybackSleepTimerCommands.cancelCommand());
        playbackSleepTimerSpinner.setEnabled(mayArm);
        playbackSleepTimerSetButton.setEnabled(mayArm);
        playbackSleepTimerCancelButton.setEnabled(mayCancel);

        if (controller == null) {
            playbackSleepTimerStatus.setText(R.string.playback_sleep_timer_unavailable);
        } else if (!playbackSleepTimerStatusKnown || playbackSleepTimerSnapshot == null) {
            playbackSleepTimerStatus.setText(R.string.playback_sleep_timer_checking);
        } else if (!playbackSleepTimerSnapshot.isActive()) {
            playbackSleepTimerStatus.setText(R.string.playback_sleep_timer_off);
        } else if (playbackSleepTimerSnapshot.getOption().isEndOfEpisode()) {
            playbackSleepTimerStatus.setText(R.string.playback_sleep_timer_end_active);
        } else {
            int remainingMinutes = (int) Math.max(
                    1L,
                    (playbackSleepTimerSnapshot.getRemainingMillis() + 59_999L) / 60_000L);
            playbackSleepTimerStatus.setText(activity.getResources().getQuantityString(
                    R.plurals.playback_sleep_timer_remaining,
                    remainingMinutes,
                    remainingMinutes));
        }
    }

    private String sleepTimerLabel(SleepTimerOption option) {
        if (option.isEndOfEpisode()) {
            return activity.getString(R.string.playback_sleep_timer_end_of_episode);
        }
        int minutes = option.getCommandValue();
        return activity.getResources().getQuantityString(
                R.plurals.playback_sleep_timer_minutes_option,
                minutes,
                minutes);
    }

    private void updatePlaybackSkipControlLabels() {
        PlaybackSkipInterval back = playbackControlSettings.getSkipBackInterval();
        PlaybackSkipInterval forward = playbackControlSettings.getSkipForwardInterval();
        UiWidgets.skip(playbackSkipBackButton, back.getSeconds(), true, activity.getResources().getQuantityString(
                R.plurals.playback_skip_back_action,
                back.getSeconds(),
                back.getSeconds()));
        UiWidgets.skip(playbackSkipForwardButton, forward.getSeconds(), false, activity.getResources().getQuantityString(
                R.plurals.playback_skip_forward_action,
                forward.getSeconds(),
                forward.getSeconds()));
    }

    private void updatePlaybackSkipControlDescriptions(String title) {
        PlaybackSkipInterval back = playbackControlSettings.getSkipBackInterval();
        PlaybackSkipInterval forward = playbackControlSettings.getSkipForwardInterval();
        playbackSkipBackButton.setContentDescription(activity.getResources().getQuantityString(
                R.plurals.playback_skip_back_description,
                back.getSeconds(),
                back.getSeconds(),
                title));
        playbackSkipForwardButton.setContentDescription(activity.getResources().getQuantityString(
                R.plurals.playback_skip_forward_description,
                forward.getSeconds(),
                forward.getSeconds(),
                title));
    }

    private String playbackTitle(MediaMetadata metadata) {
        CharSequence title = metadata.title;
        return title == null || title.length() == 0
                ? activity.getString(R.string.catalog_episode_untitled)
                : title.toString();
    }

    private void configurePlaybackSkipSettingsUi() {
        playbackSkipBackSpinner = activity.findViewById(R.id.playback_skip_back_interval);
        playbackSkipForwardSpinner = activity.findViewById(R.id.playback_skip_forward_interval);
        playbackSpeedSpinner = activity.findViewById(R.id.playback_speed);
        playbackSkipIntervals = PlaybackSkipInterval.values();
        playbackSpeeds = PlaybackSpeedSetting.values();

        List<String> labels = new ArrayList<>(playbackSkipIntervals.length);
        for (PlaybackSkipInterval interval : playbackSkipIntervals) {
            labels.add(playbackSkipIntervalLabel(interval));
        }
        ArrayAdapter<String> backAdapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                labels);
        backAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ArrayAdapter<String> forwardAdapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                new ArrayList<>(labels));
        forwardAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        List<String> speedLabels = new ArrayList<>(playbackSpeeds.length);
        for (PlaybackSpeedSetting speed : playbackSpeeds) {
            speedLabels.add(playbackSpeedLabel(speed));
        }
        ArrayAdapter<String> speedAdapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                speedLabels);
        speedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        updatingPlaybackSkipUi = true;
        playbackSkipBackSpinner.setAdapter(backAdapter);
        playbackSkipForwardSpinner.setAdapter(forwardAdapter);
        playbackSpeedSpinner.setAdapter(speedAdapter);
        playbackSkipBackSpinner.setSelection(
                playbackControlSettings.getSkipBackInterval().ordinal(),
                false);
        playbackSkipForwardSpinner.setSelection(
                playbackControlSettings.getSkipForwardInterval().ordinal(),
                false);
        playbackSpeedSpinner.setSelection(
                playbackControlSettings.getPlaybackSpeed().ordinal(),
                false);
        playbackSkipBackSpinner.setOnItemSelectedListener(
                playbackSkipSelectionListener(true));
        playbackSkipForwardSpinner.setOnItemSelectedListener(
                playbackSkipSelectionListener(false));
        playbackSpeedSpinner.setOnItemSelectedListener(
                playbackSpeedSelectionListener());
        updatingPlaybackSkipUi = false;
        refreshPlaybackSkipSettings();
    }

    private AdapterView.OnItemSelectedListener playbackSpeedSelectionListener() {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long itemId) {
                onPlaybackSpeedSelected(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // A missing choice never changes persisted playback behavior.
            }
        };
    }

    private AdapterView.OnItemSelectedListener playbackSkipSelectionListener(
            boolean skipBack) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long itemId) {
                onPlaybackSkipIntervalSelected(skipBack, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // A missing choice never changes persisted playback behavior.
            }
        };
    }

    private void onPlaybackSkipIntervalSelected(boolean skipBack, int position) {
        if (updatingPlaybackSkipUi
                || position < 0
                || position >= playbackSkipIntervals.length) {
            return;
        }
        PlaybackSkipInterval interval = playbackSkipIntervals[position];
        boolean saved = skipBack
                ? playbackControlSettings.setSkipBackInterval(interval)
                : playbackControlSettings.setSkipForwardInterval(interval);
        if (!saved) {
            Toast.makeText(
                    activity,
                    R.string.playback_skip_save_failed,
                    Toast.LENGTH_LONG).show();
            refreshPlaybackSkipSettings();
            return;
        }
        refreshPlaybackSkipSettings();
    }

    private void onPlaybackSpeedSelected(int position) {
        if (updatingPlaybackSkipUi
                || position < 0
                || position >= playbackSpeeds.length) {
            return;
        }
        if (!playbackControlSettings.setPlaybackSpeed(playbackSpeeds[position])) {
            Toast.makeText(
                    activity,
                    R.string.playback_speed_save_failed,
                    Toast.LENGTH_LONG).show();
            refreshPlaybackSkipSettings();
            return;
        }
        refreshPlaybackSkipSettings();
    }

    private void refreshPlaybackSkipSettings() {
        PlaybackSkipInterval back = playbackControlSettings.getSkipBackInterval();
        PlaybackSkipInterval forward = playbackControlSettings.getSkipForwardInterval();
        PlaybackSpeedSetting speed = playbackControlSettings.getPlaybackSpeed();
        updatingPlaybackSkipUi = true;
        playbackSkipBackSpinner.setSelection(back.ordinal(), false);
        playbackSkipForwardSpinner.setSelection(forward.ordinal(), false);
        playbackSpeedSpinner.setSelection(speed.ordinal(), false);
        updatingPlaybackSkipUi = false;
        updatePlaybackSkipControlLabels();
        renderPlaybackState();
    }

    private String playbackSkipIntervalLabel(PlaybackSkipInterval interval) {
        return activity.getResources().getQuantityString(
                R.plurals.playback_skip_interval,
                interval.getSeconds(),
                interval.getSeconds());
    }

    private String playbackSpeedLabel(PlaybackSpeedSetting speed) {
        int percent = speed.getPercent();
        int whole = percent / 100;
        int fraction = percent % 100;
        if (fraction == 0) {
            return activity.getString(R.string.playback_speed_whole_format, whole);
        }
        if (fraction % 10 == 0) {
            return activity.getString(
                    R.string.playback_speed_tenths_format,
                    whole,
                    fraction / 10);
        }
        return activity.getString(
                R.string.playback_speed_hundredths_format,
                whole,
                fraction);
    }

    private final Activity activity;
    private final Handler mainHandler;
    private final PlaybackPrivacySettings playbackPrivacySettings;
    private final PlaybackControlSettings playbackControlSettings;
    private final Runnable playbackChanged;
    private final PlaybackConnection<MediaController> connection;
    private final PlaybackActions actions;

    public PlaybackPanel(Activity activity, Handler handler,
            PlaybackPrivacySettings privacy, Runnable playbackChanged) {
        this.activity = activity;
        this.mainHandler = handler;
        this.playbackPrivacySettings = privacy;
        this.playbackControlSettings = new PlaybackControlSettings(activity);
        this.playbackChanged = playbackChanged;
        connection = new PlaybackConnection<>(this::openConnection,
                new PlaybackConnection.Listener<>() {
                    public void onConnected(MediaController controller) {
                        controller.addListener(playerListener);
                        requestSleepTimerStatus();
                    }
                    public void onDetached(MediaController controller) {
                        controller.removeListener(playerListener);
                    }
                    public void onStateChanged() {
                        if (connection.getConnected() == null) clearSleepTimerUiState();
                        renderPlaybackState();
                        PlaybackPanel.this.playbackChanged.run();
                    }
                });
        actions = new PlaybackActions(new ControllerPort());
        configurePlaybackUi();
        configurePlaybackSkipSettingsUi();
    }

    public void start() { started = true; connection.start(); tickPositions(); }
    public void stop() {
        started = false; mainHandler.removeCallbacks(positionRefresher);
        connection.stop();
        if (optionsDialog != null) optionsDialog.dismiss();
    }
    public boolean isConnected() { return connection.getConnected() != null; }
    public void resume() {
        refreshPlaybackSkipSettings();
        requestSleepTimerStatus();
    }
    public void stopPlayback() {
        actions.stop();
        renderPlaybackState();
    }
    public void playEpisode(EpisodeDetails episode, boolean competingWork) {
        if (episode != null && episode.getMediaId().equals(currentMediaId())) {
            togglePlayback(); return;
        }
        showStartResult(actions.playEpisode(episode, competingWork,
                playbackPrivacySettings.isNeverStreamAudioEnabled()));
    }
    public void setChapterPanel(ChapterPanel panel) { chapterPanel = panel; renderPositions(); }
    public void refreshChapterPosition() { renderPositions(); }
    public void seekChapterCurrent(EpisodeDetails episode, long startMillis) {
        if (episode != null) actions.seekCurrent(episode.getMediaId(), startMillis);
        renderPositions();
    }
    public void playChapter(EpisodeDetails episode, long startMillis) {
        showStartResult(actions.playChapter(episode, startMillis, false,
                playbackPrivacySettings.isNeverStreamAudioEnabled()));
    }
    private void showStartResult(PlaybackActions.StartResult result) {
        switch (result) {
            case STARTED -> renderPlaybackState();
            case DOWNLOAD_REQUIRED -> {
                playbackStatus.setText(R.string.playback_status_download_required);
                Toast.makeText(activity, R.string.playback_status_download_required, Toast.LENGTH_LONG).show();
            }
            case FAILED -> {
                playbackStatus.setText(R.string.playback_status_error);
                Toast.makeText(activity, R.string.playback_status_error, Toast.LENGTH_LONG).show();
            }
            case UNAVAILABLE -> { }
        }
    }

    public String currentMediaId() {
        MediaController controller = connection.getConnected();
        MediaItem item = controller == null ? null : controller.getCurrentMediaItem();
        return item == null ? "" : item.mediaId;
    }
    public boolean isPlaying(String mediaId) {
        MediaController controller = connection.getConnected();
        return controller != null && mediaId.equals(currentMediaId()) && controller.getPlayWhenReady()
                && controller.getPlaybackState() != Player.STATE_IDLE && controller.getPlaybackState() != Player.STATE_ENDED;
    }
    public void toggleCurrent() { togglePlayback(); }
    public void setEpisodeVisible(boolean visible) { episodeVisible = visible; renderChrome(); }
    public void bindEpisode(EpisodeDetails details) {
        if (selectedEpisode == null || details == null || !selectedEpisode.getMediaId().equals(details.getMediaId())) scrubbing = false;
        selectedEpisode = details; renderChrome();
    }
    public void showOptions() {
        if (optionsDialog != null && optionsDialog.isShowing()) return;
        ViewGroup owner = (ViewGroup) playbackOptions.getParent();
        owner.removeView(playbackOptions); playbackOptions.setVisibility(View.VISIBLE);
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity); scroll.addView(playbackOptions);
        optionsDialog = new AlertDialog.Builder(activity).setTitle(R.string.ui_playback_options)
                .setView(scroll).setPositiveButton(android.R.string.ok, null).create();
        optionsDialog.setOnDismissListener(dialog -> {
            ViewGroup parent = (ViewGroup) playbackOptions.getParent();
            if (parent != null) parent.removeView(playbackOptions);
            playbackOptions.setVisibility(View.GONE); owner.addView(playbackOptions);
            optionsDialog = null;
        });
        optionsDialog.show();
    }
    private boolean isSelectedCurrent() {
        return selectedEpisode != null && selectedEpisode.getMediaId().equals(currentMediaId());
    }
    private void renderChrome() {
        if (miniPlayer == null) return;
        MediaController controller = connection.getConnected();
        MediaItem item = controller == null ? null : controller.getCurrentMediaItem();
        if (playbackStatus != null) playbackStatus.setVisibility(episodeVisible && isSelectedCurrent()
                && controller != null && controller.getPlayerError() != null ? View.VISIBLE : View.GONE);
        miniPlayer.setVisibility(item != null && !episodeVisible ? View.VISIBLE : View.GONE);
        if (item != null) {
            miniTitle.setText(playbackTitle(item.mediaMetadata));
            activity.findViewById(R.id.mini_player_body).setContentDescription(activity.getString(
                    R.string.episode_details_open_description, playbackTitle(item.mediaMetadata)));
        }
        renderPositions();
    }
    private void tickPositions() {
        mainHandler.removeCallbacks(positionRefresher);
        if (!started) return;
        renderPositions(); mainHandler.postDelayed(positionRefresher, 500L);
    }
    private void renderPositions() {
        if (episodeSeek == null) return;
        MediaController controller = connection.getConnected();
        long currentDuration = controller == null ? -1L : controller.getDuration();
        if (currentDuration > io.github.jnesew.plainpoddroid.core.security.SecurityLimits.MAX_MEDIA_DURATION_MILLIS) currentDuration = -1L;
        long currentPosition = controller == null ? 0L : Math.max(0L, Math.min(controller.getCurrentPosition(),
                io.github.jnesew.plainpoddroid.core.security.SecurityLimits.MAX_MEDIA_DURATION_MILLIS));
        miniProgress.setProgress(fraction(currentPosition, currentDuration));
        boolean selectedCurrent = isSelectedCurrent();
        long duration = selectedCurrent && currentDuration > 0 ? currentDuration
                : selectedEpisode == null ? -1L : selectedEpisode.getDurationMillis();
        long position = selectedCurrent ? currentPosition
                : selectedEpisode == null ? 0L : selectedEpisode.getPlaybackPositionMillis();
        boolean canSeek = selectedCurrent && controller != null && currentDuration > 0
                && controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
        episodeSeek.setEnabled(canSeek);
        if (!scrubbing) episodeSeek.setProgress(fraction(position, duration));
        long preview = scrubbing && canSeek ? duration * episodeSeek.getProgress() / 1000L : position;
        episodePosition.setText(DateUtils.formatElapsedTime(Math.max(0L, preview) / 1000L));
        if (chapterPanel != null) chapterPanel.progress(preview, duration, scrubbing && canSeek, canSeek,
                !selectedCurrent && controller != null && selectedEpisode != null
                        && (selectedEpisode.getPodcast() == null || selectedEpisode.getPodcast().subscribed()
                        || selectedEpisode.isDownloaded()));
        episodeDuration.setText(duration < 0L ? activity.getString(R.string.catalog_episode_duration_unknown)
                : DateUtils.formatElapsedTime(duration / 1000L));
        if (playbackSkipBackButton != null) playbackSkipBackButton.setEnabled(selectedCurrent && controller != null
                && controller.isCommandAvailable(Player.COMMAND_SEEK_BACK));
        if (playbackSkipForwardButton != null) playbackSkipForwardButton.setEnabled(selectedCurrent && controller != null
                && controller.isCommandAvailable(Player.COMMAND_SEEK_FORWARD));
    }
    private static int fraction(long position, long duration) {
        if (duration <= 0 || duration > io.github.jnesew.plainpoddroid.core.security.SecurityLimits.MAX_MEDIA_DURATION_MILLIS) return 0;
        return (int) (Math.max(0L, Math.min(position, duration)) * 1000L / duration);
    }

    @OptIn(markerClass = UnstableApi.class)
    private PlaybackConnection.Pending<MediaController> openConnection() {
        SessionToken token = new SessionToken(activity,
                new ComponentName(activity, PlaybackService.class));
        ListenableFuture<MediaController> pending = new MediaController.Builder(activity, token)
                .setListener(new MediaController.Listener() {
                    public void onDisconnected(MediaController controller) {
                        connection.disconnected(controller);
                    }
                    public ListenableFuture<SessionResult> onCustomCommand(
                            MediaController controller, SessionCommand command, Bundle args) {
                        if (connection.getConnected() == controller
                                && PlaybackSleepTimerCommands.isStatusChangedCommand(command)) {
                            applySleepTimerStatus(args);
                            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                        }
                        return Futures.immediateFuture(new SessionResult(SessionError.ERROR_NOT_SUPPORTED));
                    }
                }).buildAsync();
        return new PlaybackConnection.Pending<>() {
            public void whenReady(Runnable completion) {
                pending.addListener(completion, activity.getMainExecutor());
            }
            public MediaController get() throws InterruptedException, ExecutionException {
                return pending.get();
            }
            public void release() { MediaController.releaseFuture(pending); }
        };
    }

    private final class ControllerPort implements PlaybackActions.Port {
        private MediaController controller() { return connection.getConnected(); }
        public boolean isConnected() { return controller() != null; }
        public boolean hasCurrentItem() { return controller().getCurrentMediaItem() != null; }
        public boolean isPlaying() { return PlaybackPanel.this.isPlaying(currentMediaId()); }
        public boolean isIdle() { return controller().getPlaybackState() == Player.STATE_IDLE; }
        public boolean canSkipBack() { return controller().isCommandAvailable(Player.COMMAND_SEEK_BACK); }
        public boolean canSkipForward() { return controller().isCommandAvailable(Player.COMMAND_SEEK_FORWARD); }
        public boolean canSeekCurrent() { return controller().isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM); }
        public String currentMediaId() { return PlaybackPanel.this.currentMediaId(); }
        public long durationMillis() { return controller().getDuration(); }
        public void setEpisode(String opaqueMediaId, long startPosition) {
            MediaItem request = new MediaItem.Builder().setMediaId(opaqueMediaId).build();
            controller().setMediaItem(request, startPosition);
        }
        public void prepare() { controller().prepare(); }
        public void play() { controller().play(); }
        public void pause() { controller().pause(); }
        public void stop() { controller().stop(); }
        public void skipBack() { controller().seekBack(); }
        public void skipForward() { controller().seekForward(); }
        public void seekTo(long position) { controller().seekTo(position); }
    }
}
