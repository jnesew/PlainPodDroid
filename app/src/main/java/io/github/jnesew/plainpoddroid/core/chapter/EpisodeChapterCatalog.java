package io.github.jnesew.plainpoddroid.core.chapter;

import java.io.IOException;

/** The UI supplies only an opaque catalog ID, never a publisher URL. */
public interface EpisodeChapterCatalog {
    EpisodeChapterTarget findChapterTarget(String mediaId) throws IOException;
}
