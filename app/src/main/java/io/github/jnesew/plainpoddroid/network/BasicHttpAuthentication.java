package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import java.nio.charset.StandardCharsets;
import java.util.List;
import okhttp3.Challenge;
import okhttp3.Request;
import okhttp3.Response;

/** No cached or ambient authentication. A caller may retry exactly once for this response. */
final class BasicHttpAuthentication {
    private BasicHttpAuthentication() { }
    static Request retry(Response response, BasicFeedLogin login, boolean audio) throws java.io.IOException {
        if (response.code() != 401 || login == null || !login.isActive() || response.request().header("Authorization") != null) return null;
        var url = io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl.parse(response.request().url().toString());
        if (!login.permits(url, audio)) return null;
        Challenge challenge = basicChallenge(response);
        if(challenge==null)return null;
        String charset=challenge.authParams().get("charset");
        String authorization;
        try { authorization = login.authorization(charset == null ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8); }
        catch (java.nio.charset.CharacterCodingException e) { throw new io.github.jnesew.plainpoddroid.core.auth.FeedLoginException(); }
        return response.request().newBuilder().header("Authorization", authorization).build();
    }
    static Challenge basicChallenge(Response response) {
        if(response.code()!=401)return null;
        List<String> headers = response.headers("WWW-Authenticate");
        int length = 0;
        for (String header : headers) length += header.length();
        if (headers.size() > 4 || length > 4096) return null;
        for (Challenge challenge : response.challenges()) {
            if (!"Basic".equalsIgnoreCase(challenge.scheme()) || challenge.realm() == null
                    || challenge.realm().length() > 256) continue;
            String charset = challenge.authParams().get("charset");
            if (charset != null && !"UTF-8".equalsIgnoreCase(charset)) continue;
            return challenge;
        }
        return null;
    }
}
