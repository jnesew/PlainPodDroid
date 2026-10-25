package io.github.jnesew.plainpoddroid.core.playback;

import java.net.URI;

/** An internal catalog identifier, never a publisher address or filesystem path. */
public final class DownloadPlaybackUri {
    public static final String SCHEME = "plainpoddroid-download";
    private DownloadPlaybackUri() { }

    public static URI create(String mediaId) {
        if (mediaId == null || !mediaId.matches("episode-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid episode selection.");
        return URI.create(SCHEME + ":" + mediaId);
    }

    public static String mediaId(URI uri) {
        if (uri == null || !SCHEME.equals(uri.getScheme()))
            throw new IllegalArgumentException("Invalid download playback source.");
        String id = uri.getRawSchemeSpecificPart();
        if (!create(id).equals(uri) || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Invalid download playback source.");
        return id;
    }
}
