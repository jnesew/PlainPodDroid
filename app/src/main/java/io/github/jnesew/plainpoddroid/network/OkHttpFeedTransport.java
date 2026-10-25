package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedRequestHeaders;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;




final class OkHttpFeedTransport implements FeedTransport {
    private static final String ACCEPT = "application/rss+xml, application/atom+xml, "
            + "application/xml;q=0.9, text/xml;q=0.9";

    private final OkHttpClient client;
    private final FeedLoginProvider logins;
    private final NetworkCallGate callGate;
    private final String userAgent;

    OkHttpFeedTransport(
            OkHttpClient client,
            NetworkCallGate callGate,
            String versionName) {
        this(client, callGate, versionName, FeedLoginProvider.NONE);
    }

    OkHttpFeedTransport(OkHttpClient client, NetworkCallGate callGate, String versionName,
            FeedLoginProvider logins) {
        this.logins = Objects.requireNonNull(logins);
        this.client = Objects.requireNonNull(client, "client");
        this.callGate = Objects.requireNonNull(callGate, "callGate");
        this.userAgent = staticUserAgent(versionName);
    }

    @Override
    public Exchange execute(CanonicalHttpsUrl url, FeedRequestHeaders headers) throws IOException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(headers, "headers");
        NetworkCallGate.Lease lease = callGate.acquireFeed();
        Response response = null;
        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url.toString())
                    .header("Accept", ACCEPT)
                    // Supplying this explicitly prevents OkHttp's transparent decompression.
                    .header("Accept-Encoding", "gzip")
                    .header("User-Agent", userAgent)
                    .get();
            if (!headers.getIfNoneMatch().isEmpty()) {
                requestBuilder.header("If-None-Match", headers.getIfNoneMatch());
            }
            if (!headers.getIfModifiedSince().isEmpty()) {
                requestBuilder.header("If-Modified-Since", headers.getIfModifiedSince());
            }
            Request request = requestBuilder.build();
            CanonicalHttpsUrl original = headers.getOriginal() == null ? url : headers.getOriginal();
            var login = headers.getLogin() == null ? logins.findFeedLogin(original) : headers.getLogin();
            response = client.newCall(request).execute();
            if (response.code() == 401 && headers.getLogin() == null) login = logins.findFeedLogin(original);
            Request retry = BasicHttpAuthentication.retry(response, login, false);
            if (retry != null) {
                response.close(); response = null;
                response = client.newCall(retry).execute();
                if (response.code() == 401 || response.code() == 403)
                    logins.rejectLogin(original, false, login);
            }
            if(BasicHttpAuthentication.basicChallenge(response)!=null)logins.noteBasicChallenge(original,false);
            ResponseBody body = response.body();
            if (body != null) {
                body.source().timeout().timeout(
                        SecurityLimits.FEED_READ_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
            }
            return new ResponseExchange(response, body, lease);
        } catch (IOException | RuntimeException exception) {
            if (response != null) {
                response.close();
            }
            lease.close();
            if (exception instanceof FeedLoginException loginFailure) throw loginFailure;
            throw new NetworkTransportException(
                    NetworkTransportException.Reason.REQUEST_FAILED,
                    "Feed network request failed.");
        }
    }

    private static String staticUserAgent(String versionName) {
        Objects.requireNonNull(versionName, "versionName");
        if (!versionName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid application version for User-Agent.");
        }
        return "PlainPodDroid/" + versionName;
    }

    private static final class ResponseExchange implements Exchange {
        private final Response response;
        private final ResponseBody body;
        private final NetworkCallGate.Lease lease;

        private ResponseExchange(
                Response response,
                ResponseBody body,
                NetworkCallGate.Lease lease) {
            this.response = response;
            this.body = body;
            this.lease = lease;
        }

        @Override
        public int getStatusCode() {
            return response.code();
        }

        @Override
        public String getHeader(String name) {
            List<String> values = response.headers(name);
            if (values.isEmpty()) {
                return null;
            }
            return String.join(",", values);
        }

        @Override
        public InputStream getBody() {
            return body == null ? null : body.byteStream();
        }

        @Override
        public void close() {
            try {
                response.close();
            } finally {
                lease.close();
            }
        }
    }
}
