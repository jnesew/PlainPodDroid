package io.github.jnesew.plainpoddroid.core.chapter;

import java.io.IOException;
import java.util.Objects;

public final class EpisodeChapterCoordinator {
    private final EpisodeChapterCatalog catalog;
    private final BoundedChapterAcquirer acquirer;

    public EpisodeChapterCoordinator(EpisodeChapterCatalog catalog, BoundedChapterAcquirer acquirer) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.acquirer = Objects.requireNonNull(acquirer, "acquirer");
    }

    public PodcastChapterDocument load(String mediaId) throws IOException, ChapterParseException {
        if (mediaId == null || !mediaId.matches("episode-[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid chapter episode ID.");
        }
        final EpisodeChapterTarget target;
        try {
            target = catalog.findChapterTarget(mediaId);
        } catch (IOException | RuntimeException exception) {
            throw new ChapterLoadException(ChapterLoadException.Reason.CATALOG_FAILURE);
        }
        if (target == null) {
            throw new ChapterLoadException(ChapterLoadException.Reason.NO_REFERENCE);
        }
        if (!mediaId.equals(target.getMediaId())) {
            throw new ChapterLoadException(ChapterLoadException.Reason.CATALOG_FAILURE);
        }
        return acquirer.acquire(target.getUrl());
    }
}
