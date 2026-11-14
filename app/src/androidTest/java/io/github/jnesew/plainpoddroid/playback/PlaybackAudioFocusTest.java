package io.github.jnesew.plainpoddroid.playback;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import io.github.jnesew.plainpoddroid.MainActivity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Real platform focus requests against the production player, with synthetic local audio. */
@UnstableApi
@SuppressWarnings("deprecation")
public final class PlaybackAudioFocusTest extends InstrumentationTestCase {
    private Activity activity;
    private ExoPlayer player;
    private AudioManager audio;
    private AudioFocusRequest interruption;
    private Path wav;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context context = getInstrumentation().getTargetContext();
        // Android 15+ grants focus only to a foreground app or playback service.
        activity = getInstrumentation().startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        wav = Files.createTempFile(context.getCacheDir().toPath(), "focus-fixture-", ".wav");
        int bytes = 8000 * 2 * 60;
        ByteBuffer data = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[]{'R','I','F','F'}).putInt(data.capacity() - 8)
                .put(new byte[]{'W','A','V','E','f','m','t',' '});
        data.putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000)
                .putInt(16000).putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d','a','t','a'}).putInt(bytes);
        Files.write(wav, data.array());
        onMain(() -> {
            player = PlaybackService.createPlayer(context, new DefaultMediaSourceFactory(context));
            player.setMediaItem(MediaItem.fromUri(wav.toUri().toString()));
            player.prepare();
            player.play();
        });
        await("Playback did not acquire focus", player::isPlaying);
    }

    public void testPermanentFocusLossPausesWithoutAutomaticResume() {
        interrupt(AudioManager.AUDIOFOCUS_GAIN);
        await("Permanent focus loss did not pause", () -> !player.getPlayWhenReady());
        abandon();
        assertStaysPaused();
        onMain(player::play);
        await("Explicit Play did not restart", player::isPlaying);
    }

    public void testTemporaryFocusLossPausesThenResumes() {
        interrupt(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        assertTemporarilySuppressed();
        abandon();
        await("Temporary interruption did not resume", player::isPlaying);
    }

    public void testSpeechPausesInsteadOfDucking() {
        interrupt(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        assertTemporarilySuppressed();
        abandon();
        await("Speech did not resume after interruption", player::isPlaying);
    }

    public void testManualPauseDuringInterruptionPreventsResume() {
        interrupt(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        assertTemporarilySuppressed();
        onMain(player::pause);
        abandon();
        assertStaysPaused();
    }

    private void assertTemporarilySuppressed() {
        await("Speech was not paused for temporary focus loss", () -> !player.isPlaying()
                && player.getPlayWhenReady()
                && player.getPlaybackSuppressionReason()
                == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    }

    private void interrupt(int gain) {
        onMain(() -> {
            interruption = new AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(change -> { }, new Handler(Looper.getMainLooper()))
                    .build();
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(interruption));
        });
    }

    private void abandon() {
        onMain(() -> {
            audio.abandonAudioFocusRequest(interruption);
            interruption = null;
        });
    }

    private void assertStaysPaused() {
        long deadline = SystemClock.elapsedRealtime() + 400;
        do {
            onMain(() -> {
                assertFalse(player.getPlayWhenReady());
                assertFalse(player.isPlaying());
            });
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
    }

    private void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        boolean[] met = {false};
        do {
            onMain(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }

    private void onMain(Runnable action) { getInstrumentation().runOnMainSync(action); }

    @Override protected void tearDown() throws Exception {
        try {
            onMain(() -> {
                if (interruption != null) audio.abandonAudioFocusRequest(interruption);
                if (player != null) player.release();
                if (activity != null) activity.finish();
            });
            if (wav != null) Files.deleteIfExists(wav);
        } finally { super.tearDown(); }
    }
}
