package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.content.Intent;
import android.view.ViewGroup;
import io.github.jnesew.plainpoddroid.MainActivity;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.download.*;
import io.github.jnesew.plainpoddroid.core.playback.*;
import io.github.jnesew.plainpoddroid.core.queue.*;
import io.github.jnesew.plainpoddroid.playback.PlaybackControlSettings;
import java.io.IOException;
import java.util.*;

/** Actual spinner binding/persistence and stale asynchronous view updates, with synthetic stores. */
@SuppressWarnings("deprecation")
public final class PlaybackOptionsStatusTest extends InstrumentationTestCase {
    private static final String A = "episode-" + "a".repeat(64), B = "episode-" + "b".repeat(64);
    private View root;
    private MainActivity fixtureActivity;
    private Context context;
    private PlaybackControlSettings defaults;
    private PlaybackSpeedSetting originalDefault;
    private final List<Runnable> work = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Long, PlaybackSpeedSetting> overrides = new HashMap<>();
    private PodcastSpeedPanel speed;
    private PlaybackQueueStatusPanel status;
    private int writes;
    private long lastWrittenId;
    private boolean failSave;
    private boolean offline = true;

    @Override protected void setUp() throws Exception {
        super.setUp();
        fixtureActivity = (MainActivity)getInstrumentation().startActivitySync(new Intent(
                getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> {
            context = new ContextThemeWrapper(fixtureActivity, R.style.Theme_PlainPodDroid);
            root = LayoutInflater.from(context).inflate(R.layout.activity_main, null);
            root.findViewById(R.id.playback_options).setVisibility(View.VISIBLE);
            fixtureActivity.addContentView(root,new ViewGroup.LayoutParams(-1,-1));
            defaults = new PlaybackControlSettings(context); originalDefault = defaults.getPlaybackSpeed();
            var store = new PodcastPlaybackSettingsStore() {
                public PlaybackSpeedSetting findSpeedForEpisode(String id) { throw new AssertionError(); }
                public PlaybackSpeedSetting getPodcastSpeed(long id) { return overrides.get(id); }
                public void setPodcastSpeed(long id, PlaybackSpeedSetting value) throws IOException {
                    if (failSave) throw new IOException("Fixture");
                    writes++; lastWrittenId = id;
                    if (value == null) overrides.remove(id); else overrides.put(id, value);
                }
            };
            speed = new PodcastSpeedPanel(root, store, defaults, work::add, main);
        });
    }
    @Override protected void tearDown() throws Exception {
        onMain(() -> {
            speed.close(); if (status != null) status.stop(); defaults.setPlaybackSpeed(originalDefault);
            fixtureActivity.finish();
        });
        getInstrumentation().waitForIdleSync();
        super.tearDown();
    }
    private void onMain(Runnable action) {
        Throwable[] failure = new Throwable[1];
        getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    private void read() {
        Runnable[] task = new Runnable[1]; onMain(() -> task[0] = work.isEmpty() ? null : work.remove(0));
        assertNotNull("Expected pending local work",task[0]);
        task[0].run(); getInstrumentation().waitForIdleSync();
    }
    private void choose(PlaybackSpeedSetting value) {
        onMain(() -> ((Spinner)root.findViewById(R.id.podcast_speed_choice))
                .setSelection(value == null ? 0 : value.ordinal() + 1));
        getInstrumentation().waitForIdleSync();
    }
    private int selectedSpeed() { return ((Spinner)root.findViewById(R.id.podcast_speed_choice)).getSelectedItemPosition(); }

    public void testOpeningAndDefaultRebindingDoNotWriteAnOverride() {
        overrides.put(1L, PlaybackSpeedSetting.ONE_AND_HALF);
        onMain(() -> speed.open(1)); read();
        assertEquals(0, writes); assertEquals(PlaybackSpeedSetting.ONE_AND_HALF.ordinal()+1,selectedSpeed());
        onMain(() -> { defaults.setPlaybackSpeed(PlaybackSpeedSetting.DOUBLE); });
        getInstrumentation().waitForIdleSync(); assertEquals(0,writes); assertEquals(0,work.size());
        choose(null); read(); assertEquals(1,writes); assertEquals(1L,lastWrittenId);
        assertFalse(overrides.containsKey(1L)); assertEquals(0,selectedSpeed());
    }
    public void testOldPodcastLoadCannotRebindTheNewTargetOrSaveAnything() {
        overrides.put(1L, PlaybackSpeedSetting.ONE_AND_HALF);
        onMain(() -> { speed.open(1); speed.open(2); });
        read(); read(); assertEquals(0,writes); assertEquals(0,selectedSpeed());
        choose(PlaybackSpeedSetting.DOUBLE); read(); assertEquals(2L,lastWrittenId);
        assertEquals(PlaybackSpeedSetting.ONE_AND_HALF,overrides.get(1L));
    }
    public void testPendingEditKeepsOriginalPodcastWhenAnotherPanelOpens() {
        onMain(() -> speed.open(1)); read();
        choose(PlaybackSpeedSetting.ONE_AND_HALF);
        onMain(() -> speed.open(2));
        read(); read(); assertEquals(1,writes); assertEquals(1L,lastWrittenId);
        assertFalse(overrides.containsKey(2L)); assertEquals(0,selectedSpeed());
    }
    public void testFailedSaveRestoresPreviousSelectionWithoutChangingStore() {
        overrides.put(1L,PlaybackSpeedSetting.ONE_AND_HALF); failSave = true;
        onMain(() -> speed.open(1)); read();
        choose(PlaybackSpeedSetting.DOUBLE); read();
        assertEquals(0,writes); assertEquals(PlaybackSpeedSetting.ONE_AND_HALF,overrides.get(1L));
        onMain(() -> assertEquals(PlaybackSpeedSetting.ONE_AND_HALF.ordinal()+1,
                ((Spinner)root.findViewById(R.id.podcast_speed_choice)).getSelectedItemPosition()));
    }
    private void statusPanel() {
        var queue = new EpisodeQueueStore() {
            public List<EpisodeQueueEntry> listQueue() {
                return List.of(new EpisodeQueueEntry(B,"Next fixture","Show",1000L,0L,false,false,false,0));
            }
            public EpisodeQueueMutation append(String id) { throw new AssertionError(); }
            public EpisodeQueueMutation remove(String id) { throw new AssertionError(); }
            public EpisodeQueueMutation playNext(String id) { throw new AssertionError(); }
            public EpisodeQueueMutation move(String id,int position) { throw new AssertionError(); }
        };
        VerifiedDownloadStore files = new VerifiedDownloadStore() {
            public DownloadLookup lookup(String id) { return DownloadLookup.withoutFile(DownloadLookup.State.ABSENT); }
            public boolean isVerifiedCompleteUri(String uri) { throw new AssertionError(); }
        };
        status = new PlaybackQueueStatusPanel(root, work::add, main,
                new PlaybackQueueStatusReader(queue,files,()->offline));
        status.start();
    }
    public void testOnlyActionableQueueWarningsAreVisible() {
        onMain(() -> { statusPanel(); status.update(A,"Current fixture",false,true,false,0L,3_600_000L); });
        read();
        onMain(() -> {
            View compact = root.findViewById(R.id.playback_up_next);
            assertEquals(View.VISIBLE,compact.getVisibility());
            assertEquals("Up next: Next fixture", ((TextView)compact.findViewById(R.id.up_next_title)).getText().toString());
            assertTrue(((TextView)compact.findViewById(R.id.up_next_status)).getText().toString().contains("Offline mode"));
            assertTrue(((TextView)compact.findViewById(R.id.up_next_status)).getText().toString().contains("Playback stopped"));
            root.findViewById(R.id.screen_queue).setVisibility(View.VISIBLE);
            status.update(A,"Current fixture",false,true,false,0L,3_600_000L);
            assertEquals(View.GONE,compact.getVisibility());
            assertEquals(View.VISIBLE,root.findViewById(R.id.queue_playback_warning).getVisibility());
            offline = false; status.invalidate();
        });
        read();
        onMain(() -> {
            assertEquals(View.GONE,root.findViewById(R.id.queue_playback_warning).getVisibility());
            root.findViewById(R.id.screen_queue).setVisibility(View.GONE);
            status.update(A,"Current fixture",false,false,false,0L,3_600_000L);
        });
        read();
        onMain(() -> {
            View compact = root.findViewById(R.id.playback_up_next);
            assertEquals(View.GONE,compact.getVisibility());
            status.update(A,"Current fixture",false,false,false,3_480_000L,3_600_000L);
            assertEquals(View.VISIBLE,compact.getVisibility());
            assertEquals(View.GONE,compact.findViewById(R.id.up_next_status).getVisibility());
            status.update(A,"Current fixture",false,false,false,0L,3_600_000L);
            assertEquals(View.GONE,compact.getVisibility());
        });
    }
    public void testPendingStatusReadsCoalesceAndCannotReplaceANewerEpisode() {
        onMain(() -> {
            statusPanel(); status.update(A,"Old fixture",false,false,false,0L,3_600_000L);
            for (int i=0;i<50;i++) status.update(B,"New fixture",false,false,false,0L,3_600_000L);
        });
        assertEquals(1,work.size()); read(); assertEquals(1,work.size()); read();
        onMain(() -> {
            View compact = root.findViewById(R.id.playback_up_next);
            assertEquals(View.GONE,compact.getVisibility());
            assertEquals(View.GONE,compact.findViewById(R.id.up_next_title).getVisibility());
        });
    }
    public void testStoppedPanelIgnoresPendingStatusDelivery() {
        onMain(() -> { statusPanel(); status.update(A,"Fixture",false,false,false,0L,3_600_000L); status.stop(); });
        read(); assertEquals(0,work.size());
        onMain(() -> assertEquals("",((TextView)root.findViewById(R.id.playback_up_next)
                .findViewById(R.id.up_next_title)).getText().toString()));
    }
}
