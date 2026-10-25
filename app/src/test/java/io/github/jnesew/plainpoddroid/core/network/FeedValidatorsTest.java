package io.github.jnesew.plainpoddroid.core.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class FeedValidatorsTest {
    @Test
    public void storedValidatorsAreBoundedAndSyntacticallySafe() throws Exception {
        CanonicalHttpsUrl url = CanonicalHttpsUrl.parse("https://feeds.example/feed");
        String boundaryEtag = "\"" + "a".repeat(SecurityLimits.MAX_ETAG_CHARS - 2) + "\"";
        String boundaryDate = "x".repeat(SecurityLimits.MAX_LAST_MODIFIED_CHARS);

        FeedValidators accepted = FeedValidators.of(url, boundaryEtag, boundaryDate);

        assertEquals(boundaryEtag, accepted.getEtag());
        assertEquals(boundaryDate, accepted.getLastModified());
        assertThrows(
                IllegalArgumentException.class,
                () -> FeedValidators.of(url, boundaryEtag + "x", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> FeedValidators.of(
                        url,
                        "",
                        boundaryDate + "x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FeedValidators.of(url, "unquoted", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> FeedValidators.of(url, "\"line\nfeed\"", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> FeedValidators.of(url, "", "date\r\ninjected"));
    }

    @Test
    public void requestHeadersAreEmittedOnlyForExactCanonicalResource() throws Exception {
        CanonicalHttpsUrl resource = CanonicalHttpsUrl.parse("https://feeds.example/feed");
        FeedValidators validators = FeedValidators.of(
                resource,
                "W/\"known\"",
                "Wed, 21 Oct 2015 07:28:00 GMT");

        FeedRequestHeaders matching = validators.headersFor(resource);
        FeedRequestHeaders other = validators.headersFor(
                CanonicalHttpsUrl.parse("https://feeds.example/other"));

        assertTrue(matching.isConditional());
        assertEquals("W/\"known\"", matching.getIfNoneMatch());
        assertFalse(other.isConditional());
        assertEquals("", other.getIfNoneMatch());
        assertEquals("", other.getIfModifiedSince());
    }

    @Test
    public void emptyValidatorsCarryNoResourceOrConditionalState() throws Exception {
        CanonicalHttpsUrl url = CanonicalHttpsUrl.parse("https://feeds.example/feed");

        FeedValidators empty = FeedValidators.of(url, "", "");

        assertTrue(empty.isEmpty());
        assertNull(empty.getResourceUrl());
        assertFalse(empty.headersFor(url).isConditional());
    }
}
