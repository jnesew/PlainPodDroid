package io.github.jnesew.plainpoddroid.core.catalog;

import java.io.IOException;

/** Bounded cross-podcast read projection, including retained archived downloads. */
public interface DownloadedEpisodeStore {
    DownloadedEpisodePage listDownloadedEpisodes(int offset, int limit) throws IOException;
}
