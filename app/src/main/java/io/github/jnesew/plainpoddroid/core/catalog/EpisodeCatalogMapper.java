package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.feed.FeedDocument;
import io.github.jnesew.plainpoddroid.core.download.ProgressiveAudioPolicy;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedAcquisition;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class EpisodeCatalogMapper {
    private static final long DURATION_OVER_LIMIT = -2L;
    public CatalogSnapshot map(FeedAcquisition acquisition) {
        Objects.requireNonNull(acquisition, "acquisition");
        return map(
                acquisition.getOriginalUrl(),
                acquisition.getFinalUrl(),
                acquisition.getDocument());
    }

    CatalogSnapshot map(
            CanonicalHttpsUrl subscriptionUrl,
            CanonicalHttpsUrl finalFeedUrl,
            FeedDocument document) {
        Objects.requireNonNull(subscriptionUrl, "subscriptionUrl");
        Objects.requireNonNull(finalFeedUrl, "finalFeedUrl");
        Objects.requireNonNull(document, "document");

        List<CatalogEpisode> accepted = new ArrayList<>();
        Set<String> mediaIds = new HashSet<>();
        int rejected = 0;
        int duplicates = 0;
        List<FeedDocument.Episode> sourceEpisodes = document.getEpisodes();
        for (int index = 0; index < sourceEpisodes.size(); index++) {
            FeedDocument.Episode source = sourceEpisodes.get(index);
            SelectedEnclosure selected = selectEnclosure(source.getEnclosures());
            long durationMillis = parseDurationMillis(source.getDuration());
            if (selected == null || durationMillis == DURATION_OVER_LIMIT) {
                rejected++;
                continue;
            }

            String identity = source.getIdentifier().isEmpty()
                    ? "url\0" + selected.url
                    : "guid\0" + source.getIdentifier();
            String mediaId = mediaId(subscriptionUrl, identity);
            if (!mediaIds.add(mediaId)) {
                duplicates++;
                continue;
            }

            String author = source.getAuthor().isEmpty()
                    ? document.getAuthor()
                    : source.getAuthor();
            accepted.add(new CatalogEpisode(
                    mediaId,
                    selected.url,
                    selected.mimeType,
                    selected.declaredLength,
                    source.getTitle(),
                    author,
                    source.getNotes(),
                    optionalHttpsUrl(source.getWebsiteUrl()),
                    chapterUrl(source),
                    source.getPublished(),
                    durationMillis,
                    source.getCategories(),
                    index));
        }

        return new CatalogSnapshot(
                subscriptionUrl,
                finalFeedUrl,
                document.getTitle(),
                document.getAuthor(),
                document.getNotes(),
                optionalHttpsUrl(document.getWebsiteUrl()),
                optionalHttpsUrl(document.getArtworkUrl()),
                document.getCategories(),
                accepted,
                rejected,
                duplicates);
    }

    private static SelectedEnclosure selectEnclosure(
            List<FeedDocument.Enclosure> enclosures) {
        for (FeedDocument.Enclosure enclosure : enclosures) {
            long length = enclosure.getDeclaredLength();
            if (length < CatalogEpisode.UNKNOWN_DECLARED_LENGTH
                    || length > SecurityLimits.MAX_DOWNLOAD_BYTES) {
                continue;
            }
            CanonicalHttpsUrl url;
            try {
                url = CanonicalHttpsUrl.parse(enclosure.getUrl());
            } catch (NetworkPolicyException exception) {
                continue;
            }
            String mimeType = ProgressiveAudioPolicy.normalizeMimeType(
                    enclosure.getMimeType());
            if (mimeType != null && ProgressiveAudioPolicy.isEligible(url, mimeType)) {
                return new SelectedEnclosure(url, mimeType, length);
            }
        }
        return null;
    }

    private static CanonicalHttpsUrl optionalHttpsUrl(String input) {
        if (input.isEmpty()) {
            return null;
        }
        try {
            return CanonicalHttpsUrl.parse(input);
        } catch (NetworkPolicyException exception) {
            return null;
        }
    }

    private static CanonicalHttpsUrl chapterUrl(FeedDocument.Episode episode) {
        if (!episode.getChapterMimeType().trim().equalsIgnoreCase(
                "application/json+chapters")) {
            return null;
        }
        return optionalHttpsUrl(episode.getChapterUrl());
    }

    private static long parseDurationMillis(String input) {
        if (input.isEmpty()) {
            return CatalogEpisode.UNKNOWN_DURATION_MILLIS;
        }
        String value = input.trim();
        try {
            long seconds;
            String[] parts = value.split(":", -1);
            if (parts.length == 1) {
                seconds = parseDecimal(parts[0]);
            } else if (parts.length == 2) {
                long minutes = parseDecimal(parts[0]);
                long finalSeconds = parseDecimal(parts[1]);
                if (finalSeconds >= 60L) {
                    return CatalogEpisode.UNKNOWN_DURATION_MILLIS;
                }
                seconds = Math.addExact(Math.multiplyExact(minutes, 60L), finalSeconds);
            } else if (parts.length == 3) {
                long hours = parseDecimal(parts[0]);
                long minutes = parseDecimal(parts[1]);
                long finalSeconds = parseDecimal(parts[2]);
                if (minutes >= 60L || finalSeconds >= 60L) {
                    return CatalogEpisode.UNKNOWN_DURATION_MILLIS;
                }
                seconds = Math.addExact(
                        Math.addExact(
                                Math.multiplyExact(hours, 3_600L),
                                Math.multiplyExact(minutes, 60L)),
                        finalSeconds);
            } else {
                return CatalogEpisode.UNKNOWN_DURATION_MILLIS;
            }
            long millis = Math.multiplyExact(seconds, 1_000L);
            return millis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                    ? DURATION_OVER_LIMIT
                    : millis;
        } catch (ArithmeticException | NumberFormatException exception) {
            return CatalogEpisode.UNKNOWN_DURATION_MILLIS;
        }
    }

    private static long parseDecimal(String value) {
        if (value.isEmpty() || !value.matches("[0-9]+")) {
            throw new NumberFormatException("Duration part is not decimal.");
        }
        return Long.parseLong(value);
    }

    private static String mediaId(CanonicalHttpsUrl subscriptionUrl, String identity) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable.", exception);
        }
        digest.update("PlainPodDroid episode identity v1\0".getBytes(StandardCharsets.UTF_8));
        digest.update(subscriptionUrl.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(identity.getBytes(StandardCharsets.UTF_8));
        byte[] hash = digest.digest();
        char[] hexadecimal = new char[hash.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < hash.length; index++) {
            int value = hash[index] & 0xff;
            hexadecimal[index * 2] = alphabet[value >>> 4];
            hexadecimal[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return "episode-" + new String(hexadecimal);
    }

    private static final class SelectedEnclosure {
        private final CanonicalHttpsUrl url;
        private final String mimeType;
        private final long declaredLength;

        private SelectedEnclosure(
                CanonicalHttpsUrl url,
                String mimeType,
                long declaredLength) {
            this.url = url;
            this.mimeType = mimeType;
            this.declaredLength = declaredLength;
        }
    }
}
