package io.github.jnesew.plainpoddroid.core.network;

public final class FeedRequestHeaders {
    private static final FeedRequestHeaders NONE = new FeedRequestHeaders("", "");

    private final String ifNoneMatch;
    private final String ifModifiedSince;
    private final CanonicalHttpsUrl original;
    private final io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin login;

    public static FeedRequestHeaders none() {
        return NONE;
    }

    static FeedRequestHeaders of(String ifNoneMatch, String ifModifiedSince) {
        if (ifNoneMatch.isEmpty() && ifModifiedSince.isEmpty()) {
            return NONE;
        }
        return new FeedRequestHeaders(ifNoneMatch, ifModifiedSince);
    }

    private FeedRequestHeaders(String ifNoneMatch, String ifModifiedSince) {
        this(ifNoneMatch, ifModifiedSince, null, null);
    }

    private FeedRequestHeaders(String etag, String modified, CanonicalHttpsUrl original,
            io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin login) {
        this.original = original; this.login = login;
        this.ifNoneMatch = etag;
        this.ifModifiedSince = modified;
    }

    public FeedRequestHeaders withLoginContext(CanonicalHttpsUrl source,
            io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin supplied) {
        return new FeedRequestHeaders(ifNoneMatch, ifModifiedSince, source, supplied);
    }
    public CanonicalHttpsUrl getOriginal() { return original; }
    public io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin getLogin() { return login; }

    public String getIfNoneMatch() {
        return ifNoneMatch;
    }

    public String getIfModifiedSince() {
        return ifModifiedSince;
    }

    public boolean isConditional() {
        return !ifNoneMatch.isEmpty() || !ifModifiedSince.isEmpty();
    }
}
