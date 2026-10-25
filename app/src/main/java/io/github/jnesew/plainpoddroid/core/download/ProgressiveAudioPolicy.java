package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Shared allowlist for feed enclosure selection and downloaded response validation. */
public final class ProgressiveAudioPolicy {
    private static final Set<String> EXCLUDED_MIME_TYPES = Set.of(
            "application/dash+xml",
            "application/mpegurl",
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/midi",
            "audio/mpegurl",
            "audio/sp-midi",
            "audio/vnd.apple.mpegurl",
            "audio/x-midi",
            "audio/x-mpegurl");
    private static final List<String> AUDIO_EXTENSIONS = List.of(
            ".mp3", ".m4a", ".aac", ".ogg",
            ".oga", ".opus", ".flac", ".wav");
    private static final List<String> EXCLUDED_EXTENSIONS = List.of(
            ".m3u", ".m3u8", ".pls", ".mpd",
            ".ism", ".isml", ".mid", ".midi");

    private ProgressiveAudioPolicy() {
        throw new AssertionError("No instances");
    }

    public static boolean isEligible(CanonicalHttpsUrl url, String mimeType) {
        Objects.requireNonNull(url, "url");
        String normalized = normalizeMimeType(mimeType);
        if (normalized == null || hasAnyExtension(url, EXCLUDED_EXTENSIONS)) {
            return false;
        }
        if (normalized.startsWith("audio/")) {
            return !EXCLUDED_MIME_TYPES.contains(normalized);
        }
        return "application/octet-stream".equals(normalized)
                && hasAnyExtension(url, AUDIO_EXTENSIONS);
    }

    public static String normalizeMimeType(String input) {
        if (input == null || input.length() > SecurityLimits.MAX_MIME_TYPE_CHARS) {
            return null;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        int parameter = value.indexOf(';');
        if (parameter >= 0) {
            value = value.substring(0, parameter).trim();
        }
        return value.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? value
                : null;
    }

    private static boolean hasAnyExtension(
            CanonicalHttpsUrl url,
            List<String> extensions) {
        String rawPath = url.toUri().getRawPath().toLowerCase(Locale.ROOT);
        String decodedPath = url.toUri().getPath().toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (rawPath.endsWith(extension) || decodedPath.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
