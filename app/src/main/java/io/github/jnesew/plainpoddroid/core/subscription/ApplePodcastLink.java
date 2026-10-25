package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes modern HTTPS show/episode links without fetching the supplied page. */
public final class ApplePodcastLink {
    private static final Pattern PATH = Pattern.compile(
            "^/(?:([A-Za-z]{2})/)?podcast/(?:[^/]+/)?id([1-9][0-9]{0,18})/?$");
    private final String id;
    private final String country;
    private final boolean episode;

    private ApplePodcastLink(String id, String country, boolean episode) {
        this.id = id;
        this.country = country;
        this.episode = episode;
    }

    public static ApplePodcastLink from(CanonicalHttpsUrl url)
            throws ApplePodcastLookupException {
        if (!url.getHost().equals("podcasts.apple.com")) return null;
        Matcher match = PATH.matcher(url.toUri().getRawPath());
        if (!match.matches()) throw new ApplePodcastLookupException(
                ApplePodcastLookupException.Reason.INVALID_LINK);
        boolean episode = false;
        String query = url.toUri().getRawQuery();
        if (query != null) {
            for (String field : query.split("&")) {
                if (field.startsWith("i=")) {
                    if (episode || !field.substring(2).matches("[1-9][0-9]{0,18}")) {
                        throw new ApplePodcastLookupException(
                                ApplePodcastLookupException.Reason.INVALID_LINK);
                    }
                    episode = true;
                }
            }
        }
        return new ApplePodcastLink(match.group(2), match.group(1) == null ? "us"
                : match.group(1).toLowerCase(Locale.ROOT), episode);
    }

    public String getId() { return id; }
    public boolean isEpisode() { return episode; }

    public CanonicalHttpsUrl getLookupUrl()
            throws io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException {
        return CanonicalHttpsUrl.parse("https://itunes.apple.com/lookup?id=" + id
                + "&entity=podcast&country=" + country);
    }
}
