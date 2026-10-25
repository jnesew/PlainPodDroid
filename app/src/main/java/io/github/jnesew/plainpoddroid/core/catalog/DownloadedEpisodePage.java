package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record DownloadedEpisodePage(int offset, List<DownloadedEpisode> episodes,
        boolean hasMore) {
    public DownloadedEpisodePage {
        Objects.requireNonNull(episodes, "episodes");
        if (offset < 0 || offset > SecurityLimits.MAX_GLOBAL_EPISODES
                || episodes.size() > SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE
                || offset + episodes.size() > SecurityLimits.MAX_GLOBAL_EPISODES
                || (hasMore && offset + episodes.size() >= SecurityLimits.MAX_GLOBAL_EPISODES)
                || episodes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid downloaded episode page.");
        }
        episodes = Collections.unmodifiableList(new ArrayList<>(episodes));
    }
}
