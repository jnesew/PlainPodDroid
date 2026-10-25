package io.github.jnesew.plainpoddroid.core.auth;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Explicit per-feed authority. Never include this object in public metadata or saved UI state. */
public final class BasicFeedLogin {
    public static final int MAX_FIELD_CHARS = 512;
    private static final Pattern UNSAFE_PATH = Pattern.compile("(?i)%(2f|5c|25)|(?:^|/)\\.\\.?(?:/|$)|\\\\");
    private final java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final String username, password;
    private final CanonicalHttpsUrl owner;
    private final CanonicalHttpsUrl feedScope, audioScope;

    public BasicFeedLogin(CanonicalHttpsUrl feedUrl, String username, String password,
            CanonicalHttpsUrl audioScope) {
        this.username = field(username, false);
        this.password = field(password, true);
        owner = Objects.requireNonNull(feedUrl, "feedUrl");
        String path = feedUrl.toUri().getRawPath();
        String parent = path.substring(0, path.lastIndexOf('/') + 1);
        try { feedScope = CanonicalHttpsUrl.parse(feedUrl.getOriginForDisplay() + parent); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid login scope."); }
        validateScope(feedScope);
        if (audioScope != null) validateScope(audioScope);
        this.audioScope = audioScope;
    }

    private static String field(String value, boolean password) {
        if (value == null || value.length() > MAX_FIELD_CHARS
                || (!password && value.indexOf(':') >= 0))
            throw new IllegalArgumentException("Invalid login input.");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException("Invalid login input.");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Invalid login input.");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid login input.");
        }
        return value;
    }

    private static void validateScope(CanonicalHttpsUrl scope) {
        String path = scope.toUri().getRawPath();
        if (scope.toUri().getRawQuery() != null || !path.endsWith("/")
                || !safePath(scope))
            throw new IllegalArgumentException("Use an HTTPS login address ending in /.");
    }

    public boolean permits(CanonicalHttpsUrl request, boolean audio) {
        return matches(feedScope, request) || (audio && audioScope != null && matches(audioScope, request));
    }
    private static boolean matches(CanonicalHttpsUrl scope, CanonicalHttpsUrl request) {
        String path = request.toUri().getRawPath();
        return scope.getOriginForDisplay().equals(request.getOriginForDisplay())
                && safePath(request) && path.startsWith(scope.toUri().getRawPath());
    }
    private static boolean safePath(CanonicalHttpsUrl url) {
        String raw = url.toUri().getRawPath(), decoded = url.toUri().getPath();
        if (UNSAFE_PATH.matcher(raw).find() || UNSAFE_PATH.matcher(decoded).find()) return false;
        for (int i = 0; i < decoded.length(); i++) if (Character.isISOControl(decoded.charAt(i))) return false;
        return true;
    }
    public boolean sameLogin(BasicFeedLogin other) {
        return other != null && owner.equals(other.owner) && username.equals(other.username)
                && password.equals(other.password) && Objects.equals(audioScope, other.audioScope);
    }
    public boolean isActive() { return active.get(); }
    public void revoke() { active.set(false); }
    public boolean belongsTo(CanonicalHttpsUrl feed) { return owner.equals(feed); }
    public String username() { return username; }
    String password() { return password; }
    public CanonicalHttpsUrl audioScope() { return audioScope; }
    public BasicFeedLogin updated(CanonicalHttpsUrl feed, String user, String replacement,
            CanonicalHttpsUrl audio) {
        return new BasicFeedLogin(feed, user, replacement.isEmpty() ? password : replacement, audio);
    }
    public String authorization(Charset charset) throws CharacterCodingException {
        String combined = username + ":" + password;
        if (charset.equals(java.nio.charset.StandardCharsets.UTF_8))
            combined = Normalizer.normalize(combined, Normalizer.Form.NFC);
        ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(combined));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        try { return "Basic " + Base64.getEncoder().encodeToString(bytes); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
    @Override public String toString() { return "[private feed login]"; }
}
