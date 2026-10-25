package io.github.jnesew.plainpoddroid.ui;

import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogEpisodeSummary;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogPublishTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.function.Supplier;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared localized metadata, with the existing distinctions between views intact. */
public final class EpisodeMetadataFormatter {
    @FunctionalInterface
    public interface Strings {
        String get(int resource, Object... arguments);
    }

    private final Strings strings;
    private final Clock clock;
    private final Supplier<Locale> locale;

    public EpisodeMetadataFormatter(Strings strings) {
        this(strings, Clock.systemDefaultZone(), Locale::getDefault);
    }

    public EpisodeMetadataFormatter(Strings strings, Clock clock, Supplier<Locale> locale) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    public String queue(EpisodeQueueEntry episode) {
        return listeningTime(episode.getDurationMillis(), episode.getPlaybackPositionMillis(),
                episode.isPlayed());
    }

    public String catalog(CatalogEpisodeSummary episode) {
        List<String> parts = new ArrayList<>();
        String date = publicationDate(episode.getPublished());
        if (!date.isEmpty()) parts.add(date);
        String timing = listeningTime(episode.getDurationMillis(),
                episode.getPlaybackPositionMillis(), episode.isPlayed());
        if (!timing.isEmpty()) parts.add(timing);
        return String.join(" · ", parts);
    }

    public String publicationDate(String published) {
        long millis = CatalogPublishTime.epochMillis(published);
        if (millis == 0L) return "";
        LocalDate date = Instant.ofEpochMilli(millis).atZone(clock.getZone()).toLocalDate();
        DateTimeFormatter format = date.getYear() == LocalDate.now(clock).getYear()
                ? DateTimeFormatter.ofPattern("d MMM", locale.get())
                : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale.get());
        return date.format(format);
    }

    public String listeningTime(long duration, long position, boolean played) {
        if (position > 0L && !played && (duration < 0L || position < duration)) {
            return duration > 0L
                    ? strings.get(R.string.ui_time_left, compactDuration(duration - position))
                    : strings.get(R.string.ui_time_listened, compactDuration(position));
        }
        return duration > 0L ? compactDuration(duration) : "";
    }

    private String compactDuration(long millis) {
        if (millis < 60_000L) return strings.get(R.string.ui_time_less_minute);
        long minutes = millis / 60_000L + (millis % 60_000L == 0L ? 0L : 1L);
        if (minutes < 60L) return strings.get(R.string.ui_time_minutes, minutes);
        if (minutes % 60L == 0L) return strings.get(R.string.ui_time_hours, minutes / 60L);
        return strings.get(R.string.ui_time_hours_minutes, minutes / 60L, minutes % 60L);
    }

    public String details(EpisodeDetails episode) {
        List<String> parts = new ArrayList<>();
        if (!episode.getPublished().isEmpty()) {
            parts.add(strings.get(R.string.episode_details_published_format,
                    episode.getPublished()));
        }
        timing(parts, episode.getDurationMillis(), episode.getPlaybackPositionMillis());
        flags(parts, episode.isPlayed(), episode.isFavorite(), episode.isQueued(),
                episode.isDownloaded(), true, episode.isStale());
        return String.join(" · ", parts);
    }

    public String duration(long millis) {
        long seconds = millis / 1_000L;
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        return hours > 0L
                ? strings.get(R.string.catalog_duration_hours_format,
                        hours, minutes, seconds % 60L)
                : strings.get(R.string.catalog_duration_minutes_format,
                        minutes, seconds % 60L);
    }

    private void timing(List<String> parts, long duration, long position) {
        parts.add(duration < 0L
                ? strings.get(R.string.catalog_episode_duration_unknown)
                : strings.get(R.string.catalog_episode_duration_format, duration(duration)));
        if (position > 0L) {
            parts.add(strings.get(R.string.catalog_episode_progress_format, duration(position)));
        }
    }

    private void flags(List<String> parts, boolean played, boolean favorite,
            boolean queued, boolean downloaded, boolean showMissing, boolean stale) {
        if (played) parts.add(strings.get(R.string.catalog_episode_played));
        if (favorite) parts.add(strings.get(R.string.catalog_episode_favorite));
        if (queued) parts.add(strings.get(R.string.catalog_episode_queued));
        if (downloaded || showMissing) {
            parts.add(strings.get(downloaded ? R.string.catalog_episode_downloaded
                    : R.string.catalog_episode_not_downloaded));
        }
        if (stale) parts.add(strings.get(R.string.catalog_episode_stale));
    }
}
