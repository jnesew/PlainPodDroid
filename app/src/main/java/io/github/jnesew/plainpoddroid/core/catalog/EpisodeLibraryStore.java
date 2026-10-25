package io.github.jnesew.plainpoddroid.core.catalog;

import java.io.IOException;

/** Bounded local-only views across podcasts, including retained archives. */
public interface EpisodeLibraryStore {
    enum Filter { FAVORITES, HISTORY, NEW_EPISODES, IN_PROGRESS }
    LibraryEpisodePage listLibraryEpisodes(Filter filter, int offset, int limit) throws IOException;
}
