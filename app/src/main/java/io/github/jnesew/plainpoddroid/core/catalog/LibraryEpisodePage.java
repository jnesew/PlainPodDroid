package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record LibraryEpisodePage(int offset, List<CatalogEpisodeSummary> episodes, boolean hasMore,
        List<Long> listenedAtMillis) {
    public LibraryEpisodePage(int offset, List<CatalogEpisodeSummary> episodes, boolean hasMore) {
        this(offset, episodes, hasMore, Collections.nCopies(episodes.size(), 0L));
    }
    public LibraryEpisodePage {
        Objects.requireNonNull(episodes, "episodes");
        Objects.requireNonNull(listenedAtMillis, "listenedAtMillis");
        if(offset<0||offset>SecurityLimits.MAX_GLOBAL_EPISODES
                ||episodes.size()>SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE
                ||offset+episodes.size()>SecurityLimits.MAX_GLOBAL_EPISODES
                ||(hasMore&&offset+episodes.size()>=SecurityLimits.MAX_GLOBAL_EPISODES)
                ||episodes.stream().anyMatch(e->e==null||e.getPodcast()==null)
                ||listenedAtMillis.size()!=episodes.size()
                ||listenedAtMillis.stream().anyMatch(time->time==null||time<0L)) {
            throw new IllegalArgumentException("Invalid library episode page.");
        }
        episodes=Collections.unmodifiableList(new ArrayList<>(episodes));
        listenedAtMillis=Collections.unmodifiableList(new ArrayList<>(listenedAtMillis));
    }
}
