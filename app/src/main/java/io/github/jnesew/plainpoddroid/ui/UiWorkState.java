package io.github.jnesew.plainpoddroid.ui;

/** Immutable UI snapshot. Controller operations still enforce their own boundaries. */
public record UiWorkState(
        boolean feedRefresh,
        boolean subscriptionWorkflow,
        boolean catalog,
        boolean download,
        boolean storage,
        boolean queue,
        boolean episodeAction,
        boolean chapters,
        boolean removal) {

    public boolean hasCatalogReadCompetingWork() {
        return removal || storage || queue || episodeAction || chapters;
    }

    public boolean hasQueueCompetingWork() {
        return feedRefresh || subscriptionWorkflow || catalog || download
                || storage || episodeAction || chapters;
    }

    /** Opaque-ID playback revalidates catalogue/file authority in the service. */
    public boolean hasPlaybackCompetingWork() {
        return removal || download || storage;
    }

    public boolean hasEpisodeActionCompetingWork() {
        return feedRefresh || subscriptionWorkflow || catalog || download
                || storage || queue || chapters;
    }

    public boolean hasDownloadCompetingWork(boolean allowDownloadWork) {
        return removal || feedRefresh || subscriptionWorkflow || catalog || storage
                || queue || episodeAction || chapters || (download && !allowDownloadWork);
    }

    public boolean isAnyMediaWorkBusy() {
        return removal || download || storage || queue || episodeAction || chapters;
    }
}
