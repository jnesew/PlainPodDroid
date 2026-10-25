package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.download.DownloadLookup;
import io.github.jnesew.plainpoddroid.core.download.ProgressiveAudioPolicy;
import io.github.jnesew.plainpoddroid.core.download.VerifiedDownloadStore;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PlaybackRequestResolver {
    private final PlaybackPrivacyPolicy privacyPolicy;
    private final VerifiedDownloadStore downloadStore;
    private final RemoteEpisodeCatalog remoteCatalog;

    public PlaybackRequestResolver(
            PlaybackPrivacyPolicy privacyPolicy,
            VerifiedDownloadStore downloadStore,
            RemoteEpisodeCatalog remoteCatalog) {
        this.privacyPolicy = Objects.requireNonNull(privacyPolicy, "privacyPolicy");
        this.downloadStore = Objects.requireNonNull(downloadStore, "downloadStore");
        this.remoteCatalog = Objects.requireNonNull(remoteCatalog, "remoteCatalog");
    }

    public PlaybackResolution resolve(String mediaId) {
        if (mediaId == null
                || mediaId.trim().isEmpty()
                || mediaId.length() > SecurityLimits.MAX_IDENTIFIER_CHARS) {
            return PlaybackResolution.blocked(
                    PlaybackResolution.BlockReason.INVALID_MEDIA_ID);
        }

        final DownloadLookup download;
        try {
            download = downloadStore.lookup(mediaId);
        } catch (IOException | RuntimeException exception) {
            return PlaybackResolution.blocked(PlaybackResolution.BlockReason.STORAGE_ERROR);
        }

        if (download.getState() == DownloadLookup.State.COMPLETE) {
            final RemoteEpisode episode;
            try {
                episode = findEpisode(mediaId);
            } catch (RuntimeException exception) {
                return PlaybackResolution.blocked(
                        PlaybackResolution.BlockReason.STORAGE_ERROR);
            }
            if (episode == null) {
                return PlaybackResolution.blocked(
                        PlaybackResolution.BlockReason.UNKNOWN_EPISODE);
            }
            return PlaybackResolution.allowed(source(
                    episode,
                    download.getCompleteFile().toURI(),
                    PlaybackSource.Kind.VERIFIED_LOCAL));
        }

        if (privacyPolicy.isNeverStreamAudioEnabled()) {
            switch (download.getState()) {
                case PARTIAL:
                    return PlaybackResolution.blocked(
                            PlaybackResolution.BlockReason.PARTIAL_DOWNLOAD);
                case INVALID:
                    return PlaybackResolution.blocked(
                            PlaybackResolution.BlockReason.INVALID_DOWNLOAD);
                case ABSENT:
                default:
                    return PlaybackResolution.blocked(
                            PlaybackResolution.BlockReason.DOWNLOAD_REQUIRED);
            }
        }

        final RemoteEpisode episode;
        try {
            episode = findEpisode(mediaId);
        } catch (RuntimeException exception) {
            return PlaybackResolution.blocked(PlaybackResolution.BlockReason.STORAGE_ERROR);
        }
        if (privacyPolicy.isNeverStreamAudioEnabled()) {
            return PlaybackResolution.blocked(
                    PlaybackResolution.BlockReason.DOWNLOAD_REQUIRED);
        }
        if (episode == null) {
            if (download.getState() == DownloadLookup.State.PARTIAL) {
                return PlaybackResolution.blocked(
                        PlaybackResolution.BlockReason.PARTIAL_DOWNLOAD);
            }
            if (download.getState() == DownloadLookup.State.INVALID) {
                return PlaybackResolution.blocked(
                        PlaybackResolution.BlockReason.INVALID_DOWNLOAD);
            }
            return PlaybackResolution.blocked(
                    PlaybackResolution.BlockReason.UNKNOWN_EPISODE);
        }
        if (!episode.isRemoteAllowed()) {
            return PlaybackResolution.blocked(
                    PlaybackResolution.BlockReason.DOWNLOAD_REQUIRED);
        }
        CanonicalHttpsUrl remoteUrl;
        try {
            if (!isEligibleRemoteUri(episode.getUri())) {
                return PlaybackResolution.blocked(
                        PlaybackResolution.BlockReason.INVALID_REMOTE_SOURCE);
            }
            remoteUrl = CanonicalHttpsUrl.parse(episode.getUri().toString());
        } catch (NetworkPolicyException | RuntimeException exception) {
            return PlaybackResolution.blocked(
                    PlaybackResolution.BlockReason.INVALID_REMOTE_SOURCE);
        }
        if (!ProgressiveAudioPolicy.isEligible(remoteUrl, episode.getMimeType())) {
            return PlaybackResolution.blocked(
                    PlaybackResolution.BlockReason.INVALID_REMOTE_SOURCE);
        }
        if (privacyPolicy.isDownloadBeforePlayingEnabled()) {
            try {
                return PlaybackResolution.allowed(source(episode,
                        DownloadPlaybackUri.create(mediaId), PlaybackSource.Kind.DOWNLOAD_THEN_LOCAL));
            } catch (IllegalArgumentException exception) {
                return PlaybackResolution.blocked(PlaybackResolution.BlockReason.INVALID_MEDIA_ID);
            }
        }
        return PlaybackResolution.allowed(source(
                episode,
                remoteUrl.toUri(),
                PlaybackSource.Kind.REMOTE_HTTPS));
    }

    public PlaybackBatchResolution resolveAll(List<String> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return PlaybackBatchResolution.blocked(
                    0,
                    PlaybackResolution.BlockReason.INVALID_MEDIA_ID);
        }

        List<PlaybackSource> resolved = new ArrayList<>(mediaIds.size());
        for (int index = 0; index < mediaIds.size(); index++) {
            PlaybackResolution item = resolve(mediaIds.get(index));
            if (!item.isAllowed()) {
                return PlaybackBatchResolution.blocked(index, item.getBlockReason());
            }
            resolved.add(item.getSource());
        }
        return PlaybackBatchResolution.allowed(resolved);
    }

    private static boolean isEligibleRemoteUri(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null
                && "https".equals(scheme.toLowerCase(Locale.ROOT))
                && uri.getHost() != null
                && !uri.getHost().trim().isEmpty()
                && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private RemoteEpisode findEpisode(String mediaId) {
        RemoteEpisode episode = remoteCatalog.findRemoteEpisode(mediaId);
        if (episode != null && !mediaId.equals(episode.getMediaId())) {
            throw new IllegalStateException("Catalog returned a mismatched episode.");
        }
        return episode;
    }

    private static PlaybackSource source(
            RemoteEpisode episode,
            URI uri,
            PlaybackSource.Kind kind) {
        return new PlaybackSource(
                episode.getMediaId(),
                uri,
                kind,
                episode.getMimeType(),
                episode.getTitle(),
                episode.getAuthor(),
                episode.getPlaybackPositionMillis());
    }
}
