package io.github.jnesew.plainpoddroid.core.auth;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android-independent codec; production keys come exclusively from Android Keystore. */
public final class FeedLoginCipher {
    public static final int MAX_BYTES = 16_384;
    public interface Keys {
        SecretKey get(CanonicalHttpsUrl owner, boolean create) throws Exception;
        default SecretKey replace(CanonicalHttpsUrl owner) throws Exception { throw new IOException(); }
    }
    private final Keys keys;
    public FeedLoginCipher(Keys keys) { this.keys = java.util.Objects.requireNonNull(keys); }

    public byte[] encrypt(CanonicalHttpsUrl owner, BasicFeedLogin login) throws IOException {
        byte[] plain = null;
        try {
            if (!login.belongsTo(owner) || !login.isActive()) throw new IOException();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeUTF(login.username()); out.writeUTF(login.password());
                out.writeUTF(login.audioScope() == null ? "" : login.audioScope().toString());
            }
            plain = buffer.toByteArray();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            try { cipher.init(Cipher.ENCRYPT_MODE, keys.get(owner, true)); }
            catch (java.security.InvalidKeyException invalid) {
                // Encrypt is called only for explicit confirmation/login editing; reads never repair keys.
                cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, keys.replace(owner));
            }
            cipher.updateAAD(owner.toString().getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain);
            if (iv.length != 12 || encrypted.length + 13 > MAX_BYTES) throw new IOException();
            byte[] result = new byte[13 + encrypted.length]; result[0] = 1;
            System.arraycopy(iv, 0, result, 1, 12);
            System.arraycopy(encrypted, 0, result, 13, encrypted.length);
            return result;
        } catch (Exception e) { throw new FeedLoginException(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    public BasicFeedLogin decrypt(CanonicalHttpsUrl owner, byte[] stored) throws IOException {
        byte[] plain = null;
        try {
            if (stored == null || stored.length < 29 || stored.length > MAX_BYTES || stored[0] != 1)
                throw new IOException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(owner, false), new GCMParameterSpec(128, stored, 1, 12));
            cipher.updateAAD(owner.toString().getBytes(StandardCharsets.UTF_8));
            plain = cipher.doFinal(stored, 13, stored.length - 13);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
                String user = in.readUTF(), password = in.readUTF(), audio = in.readUTF();
                if (in.available() != 0) throw new IOException();
                return new BasicFeedLogin(owner, user, password,
                        audio.isEmpty() ? null : CanonicalHttpsUrl.parse(audio));
            }
        } catch (Exception e) { throw new FeedLoginException(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
}
