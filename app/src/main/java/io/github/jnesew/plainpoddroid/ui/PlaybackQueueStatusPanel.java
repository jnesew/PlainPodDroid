package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackQueueStatusReader;
import io.github.jnesew.plainpoddroid.core.playback.UpNextVisibilityPolicy;
import java.io.IOException;
import java.util.concurrent.Executor;

/** Activity-owned, coalesced local status reads. Stale callbacks cannot update another episode. */
public final class PlaybackQueueStatusPanel {
    private record Request(String id, String title, boolean buffering, boolean ended, boolean error) { }
    private final Context context;
    private final View root;
    private final Executor worker;
    private final Handler main;
    private final PlaybackQueueStatusReader reader;
    private final View compact;
    private final TextView warning;
    private Request request = new Request("", "", false, false, false);
    private PlaybackQueueStatusReader.Snapshot snapshot;
    private boolean active, busy, dirty, failed, nearEnd;
    private long lastRead = -3_000L;
    private int generation;

    public PlaybackQueueStatusPanel(View root, Executor worker, Handler main,
            PlaybackQueueStatusReader reader) {
        this.root = root; this.context = root.getContext(); this.worker = worker; this.main = main; this.reader = reader;
        compact = root.findViewById(R.id.playback_up_next);
        warning = root.findViewById(R.id.queue_playback_warning);
        compact.setFocusable(true); compact.setMinimumHeight(UiWidgets.dp(context, 48));
    }
    public void start() { active = true; refresh(true); }
    public void stop() { active = false; generation++; dirty = false; }
    public void update(String id, String title, boolean buffering, boolean ended, boolean error, long positionMillis, long durationMillis) {
        nearEnd = UpNextVisibilityPolicy.isNearEnd(positionMillis, durationMillis);
        var next = new Request(id, title, buffering, ended, error);
        boolean changed = !next.equals(request);
        if (changed) { request = next; generation++; snapshot = null; failed = false; }
        render(); refresh(changed);
    }
    public void invalidate() {
        generation++; failed = false; snapshot = null;
        render(); refresh(true);
    }
    public void refresh(boolean force) {
        if (!active) return;
        if (request.id().isEmpty() && root.findViewById(R.id.screen_queue).getVisibility() != View.VISIBLE) return;
        if (busy) { dirty |= force; return; }
        if (!force && SystemClock.elapsedRealtime() - lastRead < 3_000L) return;
        busy = true; dirty = false; lastRead = SystemClock.elapsedRealtime();
        int ticket = generation; var input = request;
        try {
            worker.execute(() -> {
                PlaybackQueueStatusReader.Snapshot value = null;
                try { value = reader.read(input.id(), input.buffering()); }
                catch (IOException | RuntimeException ignored) { }
                var result = value;
                main.post(() -> {
                    busy = false;
                    if (active && ticket == generation) { snapshot = result; failed = result == null; render(); }
                    if (active && (dirty || ticket != generation)) refresh(true);
                });
            });
        } catch (RuntimeException exception) { busy = false; failed = true; render(); }
    }
    private void render() {
        boolean inQueue = root.findViewById(R.id.screen_queue).getVisibility() == View.VISIBLE;
        boolean hasNext = snapshot != null
                && snapshot.availability() != PlaybackQueueStatusReader.Availability.EMPTY;
        String action = actionableStatus();
        compact.setVisibility(!request.id().isEmpty() && !inQueue
                && (hasNext && (nearEnd || !action.isEmpty()) || failed)
                ? View.VISIBLE : View.GONE);
        TextView next = compact.findViewById(R.id.up_next_title);
        next.setVisibility(hasNext ? View.VISIBLE : View.GONE);
        text(next, hasNext ? context.getString(R.string.ui_up_next, title(snapshot.nextTitle())) : "");
        TextView status = compact.findViewById(R.id.up_next_status);
        text(status, action);
        status.setVisibility(action.isEmpty() ? View.GONE : View.VISIBLE);
        text(warning, action);
        warning.setVisibility(inQueue && !action.isEmpty() ? View.VISIBLE : View.GONE);
    }
    private String actionableStatus() {
        if (failed) return context.getString(R.string.ui_queue_read_failed);
        if (request.error()) return context.getString(R.string.ui_current_playback_failed);
        if (snapshot == null) return "";
        String status = switch (snapshot.availability()) {
            case OFFLINE_BLOCKED -> context.getString(R.string.ui_next_offline_blocked);
            case UNAVAILABLE -> context.getString(R.string.ui_next_unavailable);
            default -> "";
        };
        return request.ended() && !status.isEmpty()
                ? context.getString(R.string.ui_queue_playback_stopped, status) : status;
    }
    private String title(String value) {
        return value.isEmpty() ? context.getString(R.string.catalog_episode_untitled) : value;
    }
    private static void text(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }
}
