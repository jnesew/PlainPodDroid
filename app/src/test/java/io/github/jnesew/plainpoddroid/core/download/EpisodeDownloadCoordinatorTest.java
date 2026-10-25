package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class EpisodeDownloadCoordinatorTest {
    private static final byte[] AUDIO = "audio".getBytes(StandardCharsets.UTF_8);

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void successfulDownloadCommitsFileBeforeCatalogFlag() throws Exception {
        Fixture fixture = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", AUDIO.length, false);
        fixture.catalog.target = target;
        fixture.transport.add(target.getAudioUrl().toString(), response(200, AUDIO)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", Integer.toString(AUDIO.length)));

        EpisodeDownloadResult result = fixture.coordinator.download(
                target.getMediaId(),
                () -> false);

        assertEquals(target.getMediaId(), result.getMediaId());
        assertEquals(AUDIO.length, result.getDownloadedBytes());
        assertEquals(AUDIO.length, fixture.catalog.completedBytes);
        assertEquals(List.of(target.getMediaId()), fixture.catalog.completedMediaIds);
        DownloadLookup lookup = fixture.store.lookup(target.getMediaId());
        assertEquals(DownloadLookup.State.COMPLETE, lookup.getState());
        assertArrayEquals(AUDIO, Files.readAllBytes(lookup.getCompleteFile().toPath()));
        assertEquals(AUDIO.length + AppPrivateDownloadStore.COMPLETION_MARKER_BYTES,
                fixture.coordinator.getStorageUsageBytes());
    }

    @Test
    public void followsBoundedRelativeRedirectsWithoutLeakingUrlsInResults()
            throws Exception {
        Fixture fixture = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/start.mp3?token=private", -1L, false);
        fixture.catalog.target = target;
        fixture.transport.add(target.getAudioUrl().toString(), response(302, (InputStream) null)
                .header("Location", "/final.mp3?other=secret"));
        fixture.transport.add("https://media.example/final.mp3?other=secret",
                response(200, AUDIO)
                        .header("Content-Type", "audio/mpeg")
                        .header("Content-Length", Integer.toString(AUDIO.length)));

        EpisodeDownloadResult result = fixture.coordinator.download(
                target.getMediaId(),
                () -> false);

        assertEquals(AUDIO.length, result.getDownloadedBytes());
        assertEquals(AUDIO.length, fixture.catalog.completedBytes);
        assertEquals(List.of(
                target.getAudioUrl().toString(),
                "https://media.example/final.mp3?other=secret"),
                fixture.transport.requested);
        assertFalse(result.toString().contains("secret"));
    }

    @Test
    public void rejectsLoopDowngradeAndRedirectLimit() throws Exception {
        Fixture loop = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/loop.mp3", -1L, false);
        loop.catalog.target = target;
        loop.transport.add(target.getAudioUrl().toString(), response(302, (InputStream) null)
                .header("Location", target.getAudioUrl().toString()));
        assertReason(loop, target, EpisodeDownloadException.Reason.REDIRECT_LOOP);

        Fixture downgrade = fixture(100L);
        downgrade.catalog.target = target;
        downgrade.transport.add(target.getAudioUrl().toString(),
                response(302, (InputStream) null)
                .header("Location", "http://127.0.0.1/private.mp3?token=secret"));
        EpisodeDownloadException rejected = assertReason(
                downgrade,
                target,
                EpisodeDownloadException.Reason.INVALID_REDIRECT);
        assertFalse(rejected.getMessage().contains("secret"));

        Fixture many = fixture(100L);
        EpisodeDownloadTarget manyTarget = target(2, "/r0.mp3", -1L, false);
        many.catalog.target = manyTarget;
        for (int index = 0; index <= SecurityLimits.MAX_MEDIA_REDIRECTS; index++) {
            many.transport.add(
                    "https://media.example/r" + index + ".mp3",
                    response(302, (InputStream) null).header(
                            "Location",
                            "/r" + (index + 1) + ".mp3"));
        }
        assertReason(many, manyTarget,
                EpisodeDownloadException.Reason.TOO_MANY_REDIRECTS);
        assertEquals(SecurityLimits.MAX_MEDIA_REDIRECTS + 1, many.transport.requested.size());
        assertTrue(many.transport.responses.values().stream().allMatch(exchange -> exchange.closed));
        assertEquals(DownloadLookup.State.ABSENT,
                many.store.lookup(manyTarget.getMediaId()).getState());
        assertTrue(many.catalog.completedMediaIds.isEmpty());
    }

    @Test
    public void acceptsCompleteAudioAfterMaximumTrackingChain() throws Exception {
        Fixture fixture = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/r0.mp3", 0L, false);
        fixture.catalog.target = target;
        for (int index = 0; index < SecurityLimits.MAX_MEDIA_REDIRECTS; index++) {
            fixture.transport.add("https://media.example/r" + index + ".mp3",
                    response(302, (InputStream) null)
                            .header("Location", "/r" + (index + 1) + ".mp3"));
        }
        fixture.transport.add("https://media.example/r" + SecurityLimits.MAX_MEDIA_REDIRECTS
                + ".mp3", response(200, AUDIO).header("Content-Length", "5"));

        assertEquals(AUDIO.length,
                fixture.coordinator.download(target.getMediaId(), () -> false).getDownloadedBytes());
        assertEquals(DownloadLookup.State.COMPLETE,
                fixture.store.lookup(target.getMediaId()).getState());
        assertEquals(SecurityLimits.MAX_MEDIA_REDIRECTS + 1, fixture.transport.requested.size());
        assertTrue(fixture.transport.responses.values().stream().allMatch(exchange -> exchange.closed));
    }

    @Test
    public void longTrackingChainStillRejectsDowngradePrivateHostAndLoop() throws Exception {
        String[] destinations = {"http://cdn.example/private.mp3?token=secret",
                "https://127.0.0.1/private.mp3?token=secret", "https://media.example/r0.mp3"};
        for (int invalid = 0; invalid < destinations.length; invalid++) {
            Fixture fixture = fixture(100L);
            EpisodeDownloadTarget target = target(1, "/r0.mp3", 0L, false);
            fixture.catalog.target = target;
            for (int index = 0; index < 8; index++) {
                fixture.transport.add("https://media.example/r" + index + ".mp3",
                        response(302, (InputStream) null).header("Location",
                                index == 7 ? destinations[invalid] : "/r" + (index + 1) + ".mp3"));
            }
            EpisodeDownloadException failure = assertReason(fixture, target, invalid == 2
                    ? EpisodeDownloadException.Reason.REDIRECT_LOOP
                    : EpisodeDownloadException.Reason.INVALID_REDIRECT);
            assertFalse(failure.getMessage().contains("secret"));
            assertEquals(8, fixture.transport.requested.size());
            assertTrue(fixture.transport.responses.values().stream().allMatch(exchange -> exchange.closed));
            assertEquals(DownloadLookup.State.ABSENT,
                    fixture.store.lookup(target.getMediaId()).getState());
            assertTrue(fixture.catalog.completedMediaIds.isEmpty());
        }
    }

    @Test
    public void rejectsEncodedVideoPlaylistAndMissingBodies() throws Exception {
        assertUnsupported(response(200, AUDIO)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Encoding", "gzip"));
        assertUnsupported(response(200, AUDIO).header("Content-Type", "video/mp4"));
        assertUnsupported(response(200, (InputStream) null)
                .header("Content-Type", "audio/mpeg"));

        Fixture playlist = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/start.mp3", -1L, false);
        playlist.catalog.target = target;
        playlist.transport.add(target.getAudioUrl().toString(),
                response(302, (InputStream) null)
                .header("Location", "/playlist.m3u8"));
        playlist.transport.add("https://media.example/playlist.m3u8",
                response(200, AUDIO).header("Content-Type", "audio/mpeg"));
        assertReason(playlist, target,
                EpisodeDownloadException.Reason.UNSUPPORTED_RESPONSE);
    }

    @Test
    public void validatesHttpAndActualLengthsAndCleansPartials()
            throws Exception {
        assertLengthFailure("abc", -1L, AUDIO,
                EpisodeDownloadException.Reason.INVALID_LENGTH);
        assertLengthFailure("0", -1L, AUDIO,
                EpisodeDownloadException.Reason.INVALID_LENGTH);
        assertLengthFailure("0", 0L, AUDIO,
                EpisodeDownloadException.Reason.INVALID_LENGTH);
        assertLengthFailure("5,5", AUDIO.length, AUDIO,
                EpisodeDownloadException.Reason.INVALID_LENGTH);
        assertLengthFailure("9223372036854775808", AUDIO.length, AUDIO,
                EpisodeDownloadException.Reason.INVALID_LENGTH);
        assertLengthFailure("2147483649", AUDIO.length, AUDIO,
                EpisodeDownloadException.Reason.INVALID_LENGTH);
        assertLengthFailure("6", AUDIO.length, AUDIO,
                EpisodeDownloadException.Reason.INVALID_LENGTH);
        assertLengthFailure("4", AUDIO.length, AUDIO,
                EpisodeDownloadException.Reason.QUOTA_EXCEEDED);
        assertLengthFailure(null, AUDIO.length, new byte[0],
                EpisodeDownloadException.Reason.INVALID_LENGTH);
    }

    @Test
    public void redirectedDownloadUsesServedLengthWhenRssLengthDiffers() throws Exception {
        for (long declaredLength : new long[] {0L, 2L, 50L}) {
            assertChangedEnclosureDownloads(declaredLength, Integer.toString(AUDIO.length));
        }
    }

    @Test
    public void unknownResponseLengthUsesBoundedEofWhenRssLengthDiffers() throws Exception {
        for (long declaredLength : new long[] {0L, 2L, 50L}) {
            assertChangedEnclosureDownloads(declaredLength, null);
        }
    }

    private void assertChangedEnclosureDownloads(long declaredLength, String responseLength)
            throws Exception {
        // The RSS hint can even exceed the remaining quota; only actual transfer bytes count.
        Fixture fixture = fixture(AUDIO.length + AppPrivateDownloadStore.COMPLETION_MARKER_BYTES);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", declaredLength, false);
        fixture.catalog.target = target;
        fixture.transport.add(target.getAudioUrl().toString(), response(302, (InputStream) null)
                .header("Location", "https://cdn.example/served.mp3"));
        fixture.transport.add("https://cdn.example/served.mp3", response(200, AUDIO)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", responseLength));

        EpisodeDownloadResult result = fixture.coordinator.download(target.getMediaId(), () -> false);

        assertEquals(AUDIO.length, result.getDownloadedBytes());
        assertEquals(AUDIO.length, fixture.catalog.completedBytes);
        assertEquals(List.of(target.getMediaId()), fixture.catalog.completedMediaIds);
        DownloadLookup lookup = fixture.store.lookup(target.getMediaId());
        assertEquals(DownloadLookup.State.COMPLETE, lookup.getState());
        assertArrayEquals(AUDIO, Files.readAllBytes(lookup.getCompleteFile().toPath()));
        assertEquals(List.of(target.getAudioUrl().toString(), "https://cdn.example/served.mp3"),
                fixture.transport.requested);
    }

    @Test
    public void enforcesQuotaForKnownAndChunkedBodies() throws Exception {
        Fixture known = fixture(20L);
        EpisodeDownloadTarget knownTarget = target(1, "/known.mp3", 9L, false);
        known.catalog.target = knownTarget;
        known.transport.add(knownTarget.getAudioUrl().toString(), response(200, new byte[9])
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", "9"));
        assertReason(known, knownTarget, EpisodeDownloadException.Reason.QUOTA_EXCEEDED);
        assertEquals(DownloadLookup.State.ABSENT,
                known.store.lookup(knownTarget.getMediaId()).getState());

        Fixture chunked = fixture(20L);
        EpisodeDownloadTarget chunkedTarget = target(2, "/chunked.mp3", 2L, false);
        chunked.catalog.target = chunkedTarget;
        chunked.transport.add(chunkedTarget.getAudioUrl().toString(),
                response(200, new byte[9]).header("Content-Type", "audio/mpeg"));
        assertReason(chunked, chunkedTarget,
                EpisodeDownloadException.Reason.QUOTA_EXCEEDED);
        assertEquals(DownloadLookup.State.ABSENT,
                chunked.store.lookup(chunkedTarget.getMediaId()).getState());
        assertTrue(chunked.catalog.completedMediaIds.isEmpty());
    }

    @Test
    public void readsTheCurrentQuotaBeforeEachExplicitDownload() throws Exception {
        AtomicLong quotaBytes = new AtomicLong(100L);
        Fixture fixture = fixture(quotaBytes, 13L);
        EpisodeDownloadTarget first = target(1, "/first.mp3", AUDIO.length, false);
        fixture.catalog.target = first;
        fixture.transport.add(first.getAudioUrl().toString(), response(200, AUDIO)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", Integer.toString(AUDIO.length)));
        fixture.coordinator.download(first.getMediaId(), () -> false);

        quotaBytes.set(20L);
        EpisodeDownloadTarget second = target(2, "/second.mp3", AUDIO.length, false);
        fixture.catalog.target = second;
        fixture.transport.add(second.getAudioUrl().toString(), response(200, AUDIO)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", Integer.toString(AUDIO.length)));

        assertReason(fixture, second, EpisodeDownloadException.Reason.QUOTA_EXCEEDED);
        assertEquals(DownloadLookup.State.ABSENT,
                fixture.store.lookup(second.getMediaId()).getState());
    }

    @Test
    public void invalidDynamicQuotaFailsClosedBeforeNetworkUse() throws Exception {
        AtomicLong quotaBytes = new AtomicLong(12L);
        Fixture fixture = fixture(quotaBytes, 13L);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", AUDIO.length, false);
        fixture.catalog.target = target;

        assertReason(fixture, target, EpisodeDownloadException.Reason.STORAGE_FAILED);
        assertTrue(fixture.transport.requested.isEmpty());
    }

    @Test
    public void cancellationBeforeAndDuringTransferNeverCompletes() throws Exception {
        Fixture before = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", -1L, false);
        before.catalog.target = target;
        EpisodeDownloadException cancelled = assertThrows(
                EpisodeDownloadException.class,
                () -> before.coordinator.download(target.getMediaId(), () -> true));
        assertEquals(EpisodeDownloadException.Reason.CANCELLED, cancelled.getReason());
        assertTrue(before.transport.requested.isEmpty());

        Fixture during = fixture(100L);
        AtomicBoolean stop = new AtomicBoolean();
        during.catalog.target = target;
        InputStream body = new InputStream() {
            private boolean delivered;

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (delivered) {
                    return -1;
                }
                delivered = true;
                buffer[offset] = 1;
                stop.set(true);
                return 1;
            }

            @Override
            public int read() {
                return -1;
            }
        };
        during.transport.add(target.getAudioUrl().toString(),
                response(200, body).header("Content-Type", "audio/mpeg"));
        EpisodeDownloadException stopped = assertThrows(
                EpisodeDownloadException.class,
                () -> during.coordinator.download(target.getMediaId(), stop::get));
        assertEquals(EpisodeDownloadException.Reason.CANCELLED, stopped.getReason());
        assertEquals(DownloadLookup.State.ABSENT,
                during.store.lookup(target.getMediaId()).getState());
    }

    @Test
    public void activeTimeWatchdogStopsAContinuouslyReadableBody() throws Exception {
        AtomicLong clock = new AtomicLong();
        Fixture fixture = fixture(100L, 1L, clock::get);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", -1L, false);
        fixture.catalog.target = target;
        InputStream body = new InputStream() {
            private int count;

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (count++ == 0) {
                    buffer[offset] = 1;
                    clock.set(1_000_000L);
                    return 1;
                }
                return -1;
            }

            @Override
            public int read() {
                return -1;
            }
        };
        fixture.transport.add(target.getAudioUrl().toString(),
                response(200, body).header("Content-Type", "audio/mpeg"));

        EpisodeDownloadException timedOut = assertThrows(
                EpisodeDownloadException.class,
                () -> fixture.coordinator.download(target.getMediaId(), () -> false));

        assertEquals(EpisodeDownloadException.Reason.FETCH_FAILED, timedOut.getReason());
        assertEquals(DownloadLookup.State.ABSENT,
                fixture.store.lookup(target.getMediaId()).getState());
    }

    @Test
    public void reconcilesMissingAndOrphanedMaterialBeforeDownloading() throws Exception {
        Fixture missing = fixture(100L);
        EpisodeDownloadTarget markedButMissing = target(1, "/episode.mp3", 5L, true);
        missing.catalog.target = markedButMissing;
        missing.transport.add(markedButMissing.getAudioUrl().toString(), response(200, AUDIO)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", "5"));
        missing.coordinator.download(markedButMissing.getMediaId(), () -> false);
        assertEquals(List.of(markedButMissing.getMediaId()), missing.catalog.absentMediaIds);

        Fixture orphan = fixture(100L);
        EpisodeDownloadTarget notMarked = target(2, "/replacement.mp3", 5L, false);
        orphan.catalog.target = notMarked;
        complete(orphan.store, notMarked.getMediaId(), new byte[] {9});
        orphan.transport.add(notMarked.getAudioUrl().toString(), response(200, AUDIO)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", "5"));
        orphan.coordinator.download(notMarked.getMediaId(), () -> false);
        assertArrayEquals(AUDIO, Files.readAllBytes(
                orphan.store.lookup(notMarked.getMediaId()).getCompleteFile().toPath()));
    }

    @Test
    public void alreadyDownloadedAndChangedTargetsNeverStartOrRetainWrongTransfer()
            throws Exception {
        Fixture existing = fixture(100L);
        EpisodeDownloadTarget downloaded = target(1, "/episode.mp3", 1L, true);
        existing.catalog.target = downloaded;
        complete(existing.store, downloaded.getMediaId(), new byte[] {1});
        assertReason(existing, downloaded,
                EpisodeDownloadException.Reason.ALREADY_DOWNLOADED);
        assertTrue(existing.transport.requested.isEmpty());

        Fixture changed = fixture(100L);
        EpisodeDownloadTarget target = target(2, "/episode.mp3", 5L, false);
        changed.catalog.target = target;
        changed.catalog.acceptCompletion = false;
        changed.transport.add(target.getAudioUrl().toString(), response(200, AUDIO)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", "5"));
        assertReason(changed, target, EpisodeDownloadException.Reason.TARGET_CHANGED);
        assertEquals(DownloadLookup.State.ABSENT,
                changed.store.lookup(target.getMediaId()).getState());
    }

    @Test
    public void deleteWhilePlayingRetainsFileAndCatalogUntilReaderCloses() throws Exception {
        Fixture fixture = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", 1L, true);
        fixture.catalog.target = target;
        complete(fixture.store, target.getMediaId(), new byte[] {1});
        String uri = fixture.store.lookup(target.getMediaId()).getCompleteFile()
                .toURI().toString();
        try (var reader = fixture.store.acquireReadLease(uri)) {
            EpisodeDownloadException error = assertThrows(EpisodeDownloadException.class,
                    () -> fixture.coordinator.delete(target.getMediaId()));
            assertEquals(EpisodeDownloadException.Reason.PLAYBACK_IN_USE, error.getReason());
            assertEquals(DownloadLookup.State.COMPLETE,
                    fixture.store.lookup(target.getMediaId()).getState());
            assertTrue(fixture.catalog.absentMediaIds.isEmpty());
        }
        fixture.coordinator.delete(target.getMediaId());
        assertEquals(DownloadLookup.State.ABSENT,
                fixture.store.lookup(target.getMediaId()).getState());
        assertEquals(List.of(target.getMediaId()), fixture.catalog.absentMediaIds);
    }

    @Test
    public void deleteRemovesVerifiedFileBeforeClearingCatalogFlag() throws Exception {
        Fixture fixture = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", 1L, true);
        fixture.catalog.target = target;
        complete(fixture.store, target.getMediaId(), new byte[] {1});

        fixture.coordinator.delete(target.getMediaId());

        assertEquals(DownloadLookup.State.ABSENT,
                fixture.store.lookup(target.getMediaId()).getState());
        assertEquals(List.of(target.getMediaId()), fixture.catalog.absentMediaIds);
    }

    @Test
    public void archivedDownloadMayBeDeletedWithoutMakingItDownloadable() throws Exception {
        Fixture fixture = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", 1L, true);
        fixture.catalog.target = target;
        fixture.catalog.archived = true;
        complete(fixture.store, target.getMediaId(), new byte[] {1});

        assertEquals(null, fixture.catalog.findEpisodeDownloadTarget(target.getMediaId()));
        fixture.coordinator.delete(target.getMediaId());

        assertEquals(DownloadLookup.State.ABSENT,
                fixture.store.lookup(target.getMediaId()).getState());
    }

    @Test
    public void catalogReadFailureIsTypedAndRedacted() throws Exception {
        Fixture fixture = fixture(100L);
        fixture.catalog.findFailure = new IOException(
                "https://media.example/private.mp3?token=secret");

        EpisodeDownloadException failure = assertThrows(
                EpisodeDownloadException.class,
                () -> fixture.coordinator.download(mediaId(1), () -> false));

        assertEquals(EpisodeDownloadException.Reason.STORAGE_FAILED, failure.getReason());
        assertFalse(failure.getMessage().contains("secret"));
        assertTrue(fixture.transport.requested.isEmpty());
    }

    private void assertUnsupported(FakeExchange exchange) throws Exception {
        Fixture fixture = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", -1L, false);
        fixture.catalog.target = target;
        fixture.transport.add(target.getAudioUrl().toString(), exchange);
        assertReason(fixture, target,
                EpisodeDownloadException.Reason.UNSUPPORTED_RESPONSE);
    }

    private void assertLengthFailure(
            String responseLength,
            long declaredLength,
            byte[] body,
            EpisodeDownloadException.Reason reason) throws Exception {
        Fixture fixture = fixture(100L);
        EpisodeDownloadTarget target = target(1, "/episode.mp3", declaredLength, false);
        fixture.catalog.target = target;
        fixture.transport.add(target.getAudioUrl().toString(), response(200, body)
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", responseLength));

        assertReason(fixture, target, reason);
        assertEquals(DownloadLookup.State.ABSENT,
                fixture.store.lookup(target.getMediaId()).getState());
        assertTrue(fixture.catalog.completedMediaIds.isEmpty());
    }

    private static EpisodeDownloadException assertReason(
            Fixture fixture,
            EpisodeDownloadTarget target,
            EpisodeDownloadException.Reason expected) {
        EpisodeDownloadException failure = assertThrows(
                EpisodeDownloadException.class,
                () -> fixture.coordinator.download(target.getMediaId(), () -> false));
        assertEquals(expected, failure.getReason());
        return failure;
    }

    @Test
    public void offlineModeDeniesNetworkAndAbortsPartialTransferButAllowsDeletion() throws Exception {
        for (boolean initiallyOffline : new boolean[] {true, false}) {
            Fixture fixture = fixture(100L);
            AtomicBoolean offline = new AtomicBoolean(initiallyOffline);
            EpisodeDownloadTarget target = target(1, "/episode.mp3", -1L, false);
            fixture.catalog.target = target;
            EpisodeDownloadCoordinator coordinator = new EpisodeDownloadCoordinator(
                    fixture.catalog, fixture.store, fixture.transport, () -> 100L,
                    AppPrivateDownloadStore.COMPLETION_MARKER_BYTES + 1L,
                    1000L, System::nanoTime, offline::get);
            InputStream body = new ByteArrayInputStream(AUDIO) {
                @Override public synchronized int read(byte[] buffer, int offset, int length) {
                    int count = super.read(buffer, offset, length);
                    offline.set(true);
                    return count;
                }
            };
            fixture.transport.add(target.getAudioUrl().toString(), response(200, body)
                    .header("Content-Type", "audio/mpeg"));
            EpisodeDownloadException error = assertThrows(EpisodeDownloadException.class,
                    () -> coordinator.download(target.getMediaId(), () -> false));
            assertEquals(EpisodeDownloadException.Reason.OFFLINE_MODE, error.getReason());
            assertEquals(initiallyOffline ? 0 : 1, fixture.transport.requested.size());
            assertEquals(DownloadLookup.State.ABSENT, fixture.store.lookup(target.getMediaId()).getState());
            assertTrue(fixture.catalog.completedMediaIds.isEmpty());
            complete(fixture.store, target.getMediaId(), AUDIO);
            coordinator.delete(target.getMediaId());
            assertEquals(DownloadLookup.State.ABSENT, fixture.store.lookup(target.getMediaId()).getState());
        }
    }

    private Fixture fixture(long quotaBytes) throws Exception {
        return fixture(quotaBytes, 1_000L, System::nanoTime);
    }

    private Fixture fixture(
            long quotaBytes,
            long activeMillis,
            java.util.function.LongSupplier clock) throws Exception {
        File directory = temporaryFolder.newFolder();
        AppPrivateDownloadStore store = new AppPrivateDownloadStore(directory, 128L);
        FakeCatalog catalog = new FakeCatalog();
        FakeTransport transport = new FakeTransport();
        EpisodeDownloadCoordinator coordinator = new EpisodeDownloadCoordinator(
                catalog,
                store,
                transport,
                quotaBytes,
                activeMillis,
                clock);
        return new Fixture(store, catalog, transport, coordinator);
    }

    private Fixture fixture(AtomicLong quotaBytes, long minimumQuotaBytes)
            throws Exception {
        File directory = temporaryFolder.newFolder();
        AppPrivateDownloadStore store = new AppPrivateDownloadStore(directory, 128L);
        FakeCatalog catalog = new FakeCatalog();
        FakeTransport transport = new FakeTransport();
        EpisodeDownloadCoordinator coordinator = new EpisodeDownloadCoordinator(
                catalog,
                store,
                transport,
                quotaBytes::get,
                minimumQuotaBytes,
                1_000L,
                System::nanoTime);
        return new Fixture(store, catalog, transport, coordinator);
    }

    private static EpisodeDownloadTarget target(
            int id,
            String path,
            long declaredLength,
            boolean downloaded) throws Exception {
        return new EpisodeDownloadTarget(
                mediaId(id),
                CanonicalHttpsUrl.parse("https://media.example" + path),
                "audio/mpeg",
                declaredLength,
                downloaded);
    }

    private static String mediaId(int value) {
        return "episode-" + Integer.toHexString(value).repeat(64).substring(0, 64);
    }

    private static FakeExchange response(int status, byte[] body) {
        return response(status, body == null ? null : new ByteArrayInputStream(body));
    }

    private static FakeExchange response(int status, InputStream body) {
        return new FakeExchange(status, body);
    }

    private static void complete(
            AppPrivateDownloadStore store,
            String mediaId,
            byte[] bytes) throws Exception {
        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin(mediaId, bytes.length)) {
            writer.write(bytes);
            writer.finish();
        }
    }

    private record Fixture(
            AppPrivateDownloadStore store,
            FakeCatalog catalog,
            FakeTransport transport,
            EpisodeDownloadCoordinator coordinator) {
    }

    private static final class FakeCatalog implements EpisodeDownloadCatalog {
        long completedBytes;
        private EpisodeDownloadTarget target;
        private IOException findFailure;
        private boolean acceptCompletion = true;
        private boolean archived;
        private final List<String> completedMediaIds = new ArrayList<>();
        private final List<String> absentMediaIds = new ArrayList<>();

        @Override
        public EpisodeDownloadTarget findEpisodeDownloadTarget(String mediaId)
                throws IOException {
            if (findFailure != null) {
                throw findFailure;
            }
            return !archived && target != null && target.getMediaId().equals(mediaId)
                    ? target : null;
        }

        @Override
        public EpisodeDownloadTarget findEpisodeDeletionTarget(String mediaId) {
            return target != null && target.getMediaId().equals(mediaId) ? target : null;
        }

        @Override
        public boolean markEpisodeDownloadComplete(EpisodeDownloadTarget completed, long downloadedBytes) {
            completedBytes = downloadedBytes;
            completedMediaIds.add(completed.getMediaId());
            return acceptCompletion;
        }

        @Override
        public void markEpisodeDownloadAbsent(String mediaId) {
            absentMediaIds.add(mediaId);
        }
    }

    private static final class FakeTransport implements MediaDownloadTransport {
        private final Map<String, FakeExchange> responses = new HashMap<>();
        private final List<String> requested = new ArrayList<>();

        private void add(String url, FakeExchange response) {
            responses.put(url, response);
        }

        @Override
        public Exchange execute(CanonicalHttpsUrl url) throws IOException {
            requested.add(url.toString());
            FakeExchange response = responses.get(url.toString());
            if (response == null) {
                throw new IOException("unexpected private URL");
            }
            return response;
        }
    }

    private static final class FakeExchange implements MediaDownloadTransport.Exchange {
        private final int status;
        private final InputStream body;
        private final Map<String, String> headers = new HashMap<>();
        private boolean closed;

        private FakeExchange(int status, InputStream body) {
            this.status = status;
            this.body = body;
        }

        private FakeExchange header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        @Override
        public int getStatusCode() {
            return status;
        }

        @Override
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        public InputStream getBody() {
            return body;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (body != null) {
                body.close();
            }
        }
    }
}
