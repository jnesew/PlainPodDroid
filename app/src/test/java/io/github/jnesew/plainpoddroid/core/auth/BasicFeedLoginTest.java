package io.github.jnesew.plainpoddroid.core.auth;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BasicFeedLoginTest {
    private static CanonicalHttpsUrl url(String value) throws Exception { return CanonicalHttpsUrl.parse(value); }
    @Test public void boundsAuthorityByOriginalOriginAndFolderAndExplicitAudioScope() throws Exception {
        BasicFeedLogin login = new BasicFeedLogin(url("https://feed.example/premium/rss?token=secret"), "user", "pass",
                url("https://audio.example/members/"));
        assertTrue(login.permits(url("https://feed.example/premium/rss?another=token"), false));
        assertFalse(login.permits(url("https://feed.example/public/rss"), false));
        assertFalse(login.permits(url("https://other.example/premium/rss"), false));
        assertTrue(login.permits(url("https://audio.example/members/one.mp3"), true));
        assertTrue(login.permits(url("https://audio.example/members/one%2Emp3"), true));
        assertFalse(login.permits(url("https://audio.example/members/%00one.mp3"), true));
        assertTrue(login.permits(url("https://feed.example/premium/one.mp3"), true));
        assertFalse(login.permits(url("https://audio.example/members/one.mp3"), false));
        assertFalse(login.permits(url("https://audio.example/other/one.mp3"), true));
        assertFalse(login.permits(url("https://audio.example/members/../other/one.mp3"), true));
        assertFalse(login.permits(url("https://audio.example/members/%2e%2e/one.mp3"), true));
        assertFalse(login.permits(url("https://audio.example/members/a%2F..%2Fone.mp3"), true));
        assertFalse(login.permits(url("https://audio.example/members/%252e/one.mp3"), true));
        assertFalse(login.toString().contains("pass"));
    }
    @Test public void validatesCredentialsAndNeverLossilyEncodesOrAllowsHeaderInjection() throws Exception {
        CanonicalHttpsUrl feed = url("https://feed.example/rss");
        for (String invalid : new String[] {"u:ser", "user\r\n", "a".repeat(513), "\ud800"})
            assertThrows(IllegalArgumentException.class, () -> new BasicFeedLogin(feed, invalid, "pass", null));
        assertThrows(IllegalArgumentException.class, () -> new BasicFeedLogin(feed, "user", "p\n", null));
        assertThrows(IllegalArgumentException.class, () -> new BasicFeedLogin(feed, "user", "pass", url("https://audio.example/path")));
        assertEquals("Basic OnBhc3M=", new BasicFeedLogin(feed, "", "pass", null).authorization(StandardCharsets.ISO_8859_1));
        BasicFeedLogin unicode = new BasicFeedLogin(feed, "用户", "pass", null);
        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> unicode.authorization(StandardCharsets.ISO_8859_1));
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("用户:pass".getBytes(StandardCharsets.UTF_8)),
                unicode.authorization(StandardCharsets.UTF_8));
        assertTrue(new BasicFeedLogin(feed, "user", "pass", null).permits(url("https://feed.example/audio.mp3"), true));
    }
}
