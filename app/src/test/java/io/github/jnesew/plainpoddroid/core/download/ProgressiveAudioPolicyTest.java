package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ProgressiveAudioPolicyTest {
    @Test
    public void acceptsOrdinaryAudioAndNormalizesParameters() throws Exception {
        CanonicalHttpsUrl url = CanonicalHttpsUrl.parse("https://media.example/episode");

        assertTrue(ProgressiveAudioPolicy.isEligible(url, " Audio/MPEG; charset=binary "));
        assertEquals("audio/mpeg", ProgressiveAudioPolicy.normalizeMimeType(
                " Audio/MPEG; charset=binary "));
    }

    @Test
    public void genericBinaryRequiresRecognizedRawOrDecodedExtension() throws Exception {
        assertTrue(ProgressiveAudioPolicy.isEligible(
                CanonicalHttpsUrl.parse("https://media.example/show.MP3?token=one"),
                "application/octet-stream"));
        assertTrue(ProgressiveAudioPolicy.isEligible(
                CanonicalHttpsUrl.parse("https://media.example/show%2Emp3"),
                "application/octet-stream"));
        assertFalse(ProgressiveAudioPolicy.isEligible(
                CanonicalHttpsUrl.parse("https://media.example/download"),
                "application/octet-stream"));
    }

    @Test
    public void rejectsAdaptivePlaylistVideoAndMidiForms() throws Exception {
        for (String value : new String[] {
                "application/dash+xml",
                "application/vnd.apple.mpegurl",
                "audio/mpegurl",
                "audio/midi",
                "video/mp4",
                "text/plain"
        }) {
            assertFalse(ProgressiveAudioPolicy.isEligible(
                    CanonicalHttpsUrl.parse("https://media.example/episode.mp3"),
                    value));
        }
        assertFalse(ProgressiveAudioPolicy.isEligible(
                CanonicalHttpsUrl.parse("https://media.example/playlist.m3u8"),
                "audio/mpeg"));
    }

    @Test
    public void rejectsMalformedAndOversizedMimeValues() throws Exception {
        CanonicalHttpsUrl url = CanonicalHttpsUrl.parse("https://media.example/episode.mp3");
        assertNull(ProgressiveAudioPolicy.normalizeMimeType(null));
        assertNull(ProgressiveAudioPolicy.normalizeMimeType("audio"));
        assertNull(ProgressiveAudioPolicy.normalizeMimeType("audio/mpeg,video/mp4"));
        assertNull(ProgressiveAudioPolicy.normalizeMimeType(
                "a".repeat(SecurityLimits.MAX_MIME_TYPE_CHARS + 1)));
        assertFalse(ProgressiveAudioPolicy.isEligible(url, "audio"));
    }
}
