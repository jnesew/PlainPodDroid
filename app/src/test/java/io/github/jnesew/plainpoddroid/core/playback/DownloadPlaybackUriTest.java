package io.github.jnesew.plainpoddroid.core.playback;

import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DownloadPlaybackUriTest {
    @Test public void acceptsOnlyCanonicalOpaqueEpisodeIdentifiers() {
        String id = "episode-" + "a".repeat(64);
        assertEquals(id, DownloadPlaybackUri.mediaId(DownloadPlaybackUri.create(id)));
        for (String value : new String[]{"file:/private/audio", "https://host/audio",
                "plainpoddroid-download://" + id, "plainpoddroid-download:" + id + "#fragment",
                "plainpoddroid-download:" + id + "?query", "plainpoddroid-download:episode-123",
                "plainpoddroid-download:../audio", "plainpoddroid-download:" + id.toUpperCase()})
            assertThrows(value, IllegalArgumentException.class, () -> DownloadPlaybackUri.mediaId(URI.create(value)));
    }
}
