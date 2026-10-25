package io.github.jnesew.plainpoddroid.core.auth;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import javax.crypto.KeyGenerator;
import org.junit.Test;
import static org.junit.Assert.*;

public final class FeedLoginCipherTest {
    @Test public void randomizesCiphertextAndBindsItToOwnerWithAuthenticatedIntegrity() throws Exception {
        var key = KeyGenerator.getInstance("AES").generateKey();
        var codec = new FeedLoginCipher((owner, create) -> key);
        var owner = CanonicalHttpsUrl.parse("https://feed.example/rss?private=one");
        var login = new BasicFeedLogin(owner, "user", "private-password", CanonicalHttpsUrl.parse("https://cdn.example/members/"));
        byte[] first = codec.encrypt(owner, login), second = codec.encrypt(owner, login);
        assertFalse(java.util.Arrays.equals(first, second));
        assertFalse(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1).contains("private-password"));
        assertEquals(login.authorization(java.nio.charset.StandardCharsets.UTF_8),
                codec.decrypt(owner, first).authorization(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(login.audioScope(), codec.decrypt(owner, first).audioScope());
        var other = CanonicalHttpsUrl.parse("https://feed.example/rss?private=two");
        assertThrows(FeedLoginException.class, () -> codec.decrypt(other, first));
        assertThrows(FeedLoginException.class, () -> codec.encrypt(other, login));
        first[first.length - 1] ^= 1;
        FeedLoginException failure = assertThrows(FeedLoginException.class, () -> codec.decrypt(owner, first));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private"));
    }
    @Test public void onlyExplicitEncryptionCanReplaceAnInvalidKey() throws Exception {
        var owner = CanonicalHttpsUrl.parse("https://feed.example/rss");
        var good = KeyGenerator.getInstance("AES").generateKey();
        int[] repairs = {0};
        var keys = new FeedLoginCipher.Keys() {
            public javax.crypto.SecretKey get(CanonicalHttpsUrl ignored, boolean create) {
                return repairs[0] == 0 ? new javax.crypto.spec.SecretKeySpec(new byte[16], "DES") : good;
            }
            public javax.crypto.SecretKey replace(CanonicalHttpsUrl ignored) { repairs[0]++; return good; }
        };
        var cipher = new FeedLoginCipher(keys);
        var login = new BasicFeedLogin(owner, "user", "pass", null);
        byte[] stored = cipher.encrypt(owner, login); assertEquals(1, repairs[0]);
        assertTrue(login.sameLogin(cipher.decrypt(owner, stored)));
        repairs[0] = 0;
        assertThrows(FeedLoginException.class, () -> cipher.decrypt(owner, stored)); assertEquals(0, repairs[0]);
    }

    @Test public void unavailableKeysAndInvalidBlobsFailClosedWithoutCreatingAKeyOnRead() throws Exception {
        var owner = CanonicalHttpsUrl.parse("https://feed.example/rss");
        int[] reads = {0};
        var codec = new FeedLoginCipher((url, create) -> { reads[0]++; assertFalse(create); throw new Exception("private-password"); });
        for (byte[] data : new byte[][] {new byte[0], new byte[28], new byte[FeedLoginCipher.MAX_BYTES + 1]})
            assertThrows(FeedLoginException.class, () -> codec.decrypt(owner, data));
        assertEquals(0, reads[0]);
        byte[] validShape = new byte[29]; validShape[0] = 1;
        assertThrows(FeedLoginException.class, () -> codec.decrypt(owner, validShape));
        assertEquals(1, reads[0]);
    }
}
