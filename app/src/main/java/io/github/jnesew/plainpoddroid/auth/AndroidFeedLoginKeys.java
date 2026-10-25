package io.github.jnesew.plainpoddroid.auth;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginCipher;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Encryption keys are separate from APK signing and never leave Android Keystore. */
public final class AndroidFeedLoginKeys implements FeedLoginCipher.Keys {
    public synchronized SecretKey get(CanonicalHttpsUrl owner, boolean create) throws Exception {
        String alias = alias(owner);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(alias)) {
            try {
                SecretKey key = (SecretKey) store.getKey(alias, null);
                if (key != null) return key;
            } catch (java.security.UnrecoverableKeyException unavailable) {
                if (!create) throw unavailable;
            }
            if (!create) throw new java.io.IOException("Login key unavailable.");
            store.deleteEntry(alias);
        }
        if (!create) throw new java.io.IOException("Login key unavailable.");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    @Override public synchronized SecretKey replace(CanonicalHttpsUrl owner) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias(owner));
        return get(owner, true);
    }
    public synchronized void delete(CanonicalHttpsUrl owner) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias(owner));
    }
    private static String alias(CanonicalHttpsUrl owner) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(owner.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder alias = new StringBuilder("plainpod.feed-login.");
        for (byte b : hash) alias.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return alias.toString();
    }
}
