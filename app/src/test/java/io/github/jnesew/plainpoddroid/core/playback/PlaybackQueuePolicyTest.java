package io.github.jnesew.plainpoddroid.core.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class PlaybackQueuePolicyTest {
    @Test public void downloadPreferenceAllowsPendingLocalAcquisitionButNeverRemoteAudio() {
        assertFalse(PlaybackQueuePolicy.mustStopAndClear(false, true,
                Arrays.asList(PlaybackSource.Kind.VERIFIED_LOCAL, PlaybackSource.Kind.DOWNLOAD_THEN_LOCAL)));
        assertTrue(PlaybackQueuePolicy.mustStopAndClear(false, true,
                Arrays.asList(PlaybackSource.Kind.DOWNLOAD_THEN_LOCAL, PlaybackSource.Kind.REMOTE_HTTPS)));
        assertTrue(PlaybackQueuePolicy.mustStopAndClear(true, true,
                Collections.singletonList(PlaybackSource.Kind.DOWNLOAD_THEN_LOCAL)));
    }
    @Test
    public void enablingNeverStreamStopsQueueContainingRemoteItem() {
        assertTrue(PlaybackQueuePolicy.mustStopAndClear(
                true,
                Arrays.asList(
                        PlaybackSource.Kind.VERIFIED_LOCAL,
                        PlaybackSource.Kind.REMOTE_HTTPS)));
    }

    @Test
    public void verifiedLocalQueueContinuesWhenPolicyIsEnabled() {
        assertFalse(PlaybackQueuePolicy.mustStopAndClear(
                true,
                Arrays.asList(
                        PlaybackSource.Kind.VERIFIED_LOCAL,
                        PlaybackSource.Kind.VERIFIED_LOCAL)));
    }

    @Test
    public void disabledPolicyDoesNotClearRemoteQueue() {
        assertFalse(PlaybackQueuePolicy.mustStopAndClear(
                false,
                Collections.singletonList(PlaybackSource.Kind.REMOTE_HTTPS)));
    }

    @Test
    public void emptyQueueRequiresNoAction() {
        assertFalse(PlaybackQueuePolicy.mustStopAndClear(true, Collections.emptyList()));
    }
}
