package io.github.jnesew.plainpoddroid.core.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jnesew.plainpoddroid.core.download.DownloadLookup;
import io.github.jnesew.plainpoddroid.core.download.VerifiedDownloadStore;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class PlaybackRequestResolverTest {
    @Test
    public void neverStreamBlocksAbsentEpisodeWithoutConsultingRemoteCatalog() {
        MutablePrivacyPolicy privacy = new MutablePrivacyPolicy(true);
        FakeDownloadStore downloads = new FakeDownloadStore();
        CountingCatalog catalog = new CountingCatalog();
        catalog.put("episode", URI.create("https://media.example/episode.mp3"));
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                privacy,
                downloads,
                catalog);

        PlaybackResolution result = resolver.resolve("episode");

        assertFalse(result.isAllowed());
        assertEquals(PlaybackResolution.BlockReason.DOWNLOAD_REQUIRED, result.getBlockReason());
        assertEquals(0, catalog.lookupCount);
    }

    @Test
    public void neverStreamDistinguishesPartialAndInvalidDownloads() {
        MutablePrivacyPolicy privacy = new MutablePrivacyPolicy(true);
        FakeDownloadStore downloads = new FakeDownloadStore();
        downloads.put("partial", DownloadLookup.withoutFile(DownloadLookup.State.PARTIAL));
        downloads.put("invalid", DownloadLookup.withoutFile(DownloadLookup.State.INVALID));
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                privacy,
                downloads,
                mediaId -> episode(
                        mediaId,
                        URI.create("https://media.example/" + mediaId)));

        assertEquals(
                PlaybackResolution.BlockReason.PARTIAL_DOWNLOAD,
                resolver.resolve("partial").getBlockReason());
        assertEquals(
                PlaybackResolution.BlockReason.INVALID_DOWNLOAD,
                resolver.resolve("invalid").getBlockReason());
    }

    @Test
    public void verifiedCompleteDownloadWinsInEitherMode() {
        MutablePrivacyPolicy privacy = new MutablePrivacyPolicy(true);
        FakeDownloadStore downloads = new FakeDownloadStore();
        File verified = new File("/private/downloads/episode.audio");
        downloads.put("episode", DownloadLookup.complete(verified));
        CountingCatalog catalog = new CountingCatalog();
        catalog.put("episode", URI.create("https://media.example/episode.mp3"));
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                privacy,
                downloads,
                catalog);

        PlaybackResolution result = resolver.resolve("episode");

        assertTrue(result.isAllowed());
        assertEquals(PlaybackSource.Kind.VERIFIED_LOCAL, result.getSource().getKind());
        assertEquals(verified.toURI(), result.getSource().getUri());
        assertEquals(1, catalog.lookupCount);
        assertEquals("audio/mpeg", result.getSource().getMimeType());
        assertEquals("Episode title", result.getSource().getTitle());
    }

    @Test
    public void streamingModeMayResolveStructurallyEligibleHttpsSource() {
        MutablePrivacyPolicy privacy = new MutablePrivacyPolicy(false);
        CountingCatalog catalog = new CountingCatalog();
        URI remote = URI.create("https://media.example/episode.mp3");
        catalog.put("episode", remote);
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                privacy,
                new FakeDownloadStore(),
                catalog);

        PlaybackResolution result = resolver.resolve("episode");

        assertTrue(result.isAllowed());
        assertEquals(PlaybackSource.Kind.REMOTE_HTTPS, result.getSource().getKind());
        assertEquals(remote, result.getSource().getUri());
    }

    @Test
    public void archivedEpisodePlaysVerifiedFileButCannotStreamWhenFileIsAbsent() {
        FakeDownloadStore downloads = new FakeDownloadStore();
        RemoteEpisode archived = new RemoteEpisode("episode",
                URI.create("https://media.example/episode.mp3"),
                "audio/mpeg", "Archived episode", "Publisher", 0L, false);
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                () -> false, downloads, mediaId -> archived);

        assertEquals(PlaybackResolution.BlockReason.DOWNLOAD_REQUIRED,
                resolver.resolve("episode").getBlockReason());
        downloads.put("episode", DownloadLookup.complete(new File("/private/archive.audio")));
        PlaybackResolution local = resolver.resolve("episode");
        assertTrue(local.isAllowed());
        assertEquals(PlaybackSource.Kind.VERIFIED_LOCAL, local.getSource().getKind());
    }

    @Test
    public void malformedOrPrivilegedRemoteUrisAreRejected() {
        String[] rejected = {
                "http://media.example/episode.mp3",
                "https://user:secret@media.example/episode.mp3",
                "https://media.example:8443/episode.mp3",
                "https://media.example/episode.mp3#fragment",
                "file:///private/episode.mp3"
        };
        for (String candidate : rejected) {
            PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                    () -> false,
                    new FakeDownloadStore(),
                    mediaId -> episode(mediaId, URI.create(candidate)));
            PlaybackResolution result = resolver.resolve("episode");
            assertFalse(candidate, result.isAllowed());
            assertEquals(
                    candidate,
                    PlaybackResolution.BlockReason.INVALID_REMOTE_SOURCE,
                    result.getBlockReason());
        }
    }

    @Test
    public void queueResolutionIsAtomicWhenAnyTailItemIsBlocked() {
        FakeDownloadStore downloads = new FakeDownloadStore();
        downloads.put("local", DownloadLookup.complete(new File("/private/local.audio")));
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                () -> true,
                downloads,
                mediaId -> episode(
                        mediaId,
                        URI.create("https://media.example/" + mediaId)));

        PlaybackBatchResolution result = resolver.resolveAll(
                Arrays.asList("local", "remote", "attacker-tail"));

        assertFalse(result.isAllowed());
        assertTrue(result.getSources().isEmpty());
        assertEquals(1, result.getBlockedIndex());
        assertEquals(PlaybackResolution.BlockReason.DOWNLOAD_REQUIRED, result.getBlockReason());
    }

    @Test
    public void enablingPolicyDuringCatalogLookupFailsClosed() {
        MutablePrivacyPolicy privacy = new MutablePrivacyPolicy(false);
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                privacy,
                new FakeDownloadStore(),
                mediaId -> {
                    privacy.enabled = true;
                    return episode(
                            mediaId,
                            URI.create("https://media.example/episode.mp3"));
                });

        PlaybackResolution result = resolver.resolve("episode");

        assertFalse(result.isAllowed());
        assertEquals(PlaybackResolution.BlockReason.DOWNLOAD_REQUIRED, result.getBlockReason());
    }

    @Test
    public void storageFailureDoesNotFallBackToStreaming() {
        VerifiedDownloadStore failedStore = new VerifiedDownloadStore() {
            @Override
            public DownloadLookup lookup(String mediaId) throws IOException {
                throw new IOException("disk failure");
            }

            @Override
            public boolean isVerifiedCompleteUri(String uri) {
                return false;
            }
        };
        CountingCatalog catalog = new CountingCatalog();
        catalog.put("episode", URI.create("https://media.example/episode.mp3"));
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                () -> false,
                failedStore,
                catalog);

        PlaybackResolution result = resolver.resolve("episode");

        assertFalse(result.isAllowed());
        assertEquals(PlaybackResolution.BlockReason.STORAGE_ERROR, result.getBlockReason());
        assertEquals(0, catalog.lookupCount);
    }

    @Test
    public void catalogFailureDoesNotFallBackToUntrustedMediaData() {
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                () -> false,
                new FakeDownloadStore(),
                mediaId -> {
                    throw new IllegalStateException("corrupt catalog");
                });

        PlaybackResolution result = resolver.resolve("episode");

        assertFalse(result.isAllowed());
        assertEquals(PlaybackResolution.BlockReason.STORAGE_ERROR, result.getBlockReason());
    }

    @Test
    public void mismatchedCatalogIdentityIsRejectedAsStorageCorruption() {
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                () -> false,
                new FakeDownloadStore(),
                mediaId -> episode(
                        "different-episode",
                        URI.create("https://media.example/episode.mp3")));

        PlaybackResolution result = resolver.resolve("episode");

        assertFalse(result.isAllowed());
        assertEquals(PlaybackResolution.BlockReason.STORAGE_ERROR, result.getBlockReason());
    }

    @Test
    public void nonProgressiveCatalogMediaIsRejected() {
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                () -> false,
                new FakeDownloadStore(),
                mediaId -> new RemoteEpisode(
                        mediaId,
                        URI.create("https://media.example/episode.m3u8"),
                        "application/vnd.apple.mpegurl",
                        "Episode title",
                        "Episode author",
                        0L));

        PlaybackResolution result = resolver.resolve("episode");

        assertFalse(result.isAllowed());
        assertEquals(
                PlaybackResolution.BlockReason.INVALID_REMOTE_SOURCE,
                result.getBlockReason());
    }

    private static final class MutablePrivacyPolicy implements PlaybackPrivacyPolicy {
        private boolean enabled;
        private boolean downloadFirst;

        @Override public boolean isDownloadBeforePlayingEnabled() { return downloadFirst; }

        private MutablePrivacyPolicy(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isNeverStreamAudioEnabled() {
            return enabled;
        }
    }

    private static final class FakeDownloadStore implements VerifiedDownloadStore {
        private final Map<String, DownloadLookup> lookups = new HashMap<>();

        private void put(String mediaId, DownloadLookup lookup) {
            lookups.put(mediaId, lookup);
        }

        @Override
        public DownloadLookup lookup(String mediaId) {
            return lookups.getOrDefault(
                    mediaId,
                    DownloadLookup.withoutFile(DownloadLookup.State.ABSENT));
        }

        @Override
        public boolean isVerifiedCompleteUri(String uri) {
            return false;
        }
    }

    private static final class CountingCatalog implements RemoteEpisodeCatalog {
        private final Map<String, RemoteEpisode> entries = new HashMap<>();
        private int lookupCount;

        private void put(String mediaId, URI uri) {
            entries.put(mediaId, episode(mediaId, uri));
        }

        @Override
        public RemoteEpisode findRemoteEpisode(String mediaId) {
            lookupCount++;
            return entries.get(mediaId);
        }
    }

    private static RemoteEpisode episode(String mediaId, URI uri) {
        return new RemoteEpisode(
                mediaId,
                uri,
                "audio/mpeg",
                "Episode title",
                "Episode author",
                12_000L);
    }

    @Test public void downloadPreferenceResolvesAnOpaqueLocalPreparationSource() {
        String id = "episode-" + "a".repeat(64);
        MutablePrivacyPolicy policy = new MutablePrivacyPolicy(false);
        policy.downloadFirst = true;
        FakeDownloadStore store = new FakeDownloadStore();
        PlaybackRequestResolver resolver = new PlaybackRequestResolver(policy, store,
                key -> episode(key, URI.create("https://media.example/episode.mp3?private=secret")));
        PlaybackSource source = resolver.resolve(id).getSource();
        assertEquals(PlaybackSource.Kind.DOWNLOAD_THEN_LOCAL, source.getKind());
        assertEquals(id, DownloadPlaybackUri.mediaId(source.getUri()));
        assertFalse(source.getUri().toString().contains("secret"));
        store.put(id, DownloadLookup.complete(new File("/private/complete.audio")));
        assertEquals(PlaybackSource.Kind.VERIFIED_LOCAL, resolver.resolve(id).getSource().getKind());
        policy.enabled = true;
        store.put(id, DownloadLookup.withoutFile(DownloadLookup.State.ABSENT));
        assertEquals(PlaybackResolution.BlockReason.DOWNLOAD_REQUIRED, resolver.resolve(id).getBlockReason());
    }

    @Test public void downloadPreferencePreservesArchiveAndMediaEligibilityChecks() {
        String id = "episode-" + "b".repeat(64);
        MutablePrivacyPolicy policy = new MutablePrivacyPolicy(false);
        policy.downloadFirst = true;
        PlaybackRequestResolver archived = new PlaybackRequestResolver(policy, new FakeDownloadStore(),
                key -> new RemoteEpisode(key, URI.create("https://media.example/audio.mp3"),
                        "audio/mpeg", "Title", "Author", 0L, false));
        assertFalse(archived.resolve(id).isAllowed());
        PlaybackRequestResolver invalid = new PlaybackRequestResolver(policy, new FakeDownloadStore(),
                key -> episode(key, URI.create("http://media.example/audio.mp3")));
        assertEquals(PlaybackResolution.BlockReason.INVALID_REMOTE_SOURCE, invalid.resolve(id).getBlockReason());
    }
}
