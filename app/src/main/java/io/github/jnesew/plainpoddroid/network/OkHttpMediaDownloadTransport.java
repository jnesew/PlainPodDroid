package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.download.MediaDownloadTransport;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
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




final class OkHttpMediaDownloadTransport implements MediaDownloadTransport {
    private static final String ACCEPT = "audio/*, application/octet-stream;q=0.5";

    private final OkHttpClient client;
    private final FeedLoginProvider logins;
    private final NetworkCallGate callGate;
    private final String userAgent;

    OkHttpMediaDownloadTransport(
            OkHttpClient baseClient,
            NetworkCallGate callGate,
            String versionName) {
        this(baseClient, callGate, versionName, FeedLoginProvider.NONE);
    }

    OkHttpMediaDownloadTransport(OkHttpClient baseClient, NetworkCallGate callGate, String versionName,
            FeedLoginProvider logins) {
        this.logins = Objects.requireNonNull(logins);
        client = NetworkClientFactory.forMedia(
                Objects.requireNonNull(baseClient, "baseClient"));
        this.callGate = Objects.requireNonNull(callGate, "callGate");
        userAgent = staticUserAgent(versionName);
    }

    @Override
    public Exchange execute(CanonicalHttpsUrl url) throws IOException {
        return execute(url, url);
    }

    @Override
    public Exchange execute(CanonicalHttpsUrl url, CanonicalHttpsUrl original) throws IOException {
        Objects.requireNonNull(url, "url");
        NetworkCallGate.Lease lease = callGate.acquireMedia();
        Response response = null;
        try {
            Request request = new Request.Builder()
                    .url(url.toString())
                    .header("Accept", ACCEPT)
                    // Identity prevents transparent decoding and keeps byte limits exact.
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", userAgent)
                    .get()
                    .build();
            var login = logins.findAudioLogin(original);
            response = client.newCall(request).execute();
            if (response.code() == 401) login = logins.findAudioLogin(original);
            Request retry = BasicHttpAuthentication.retry(response, login, true);
            if (retry != null) {
                response.close(); response = null;
                response = client.newCall(retry).execute();
                if (response.code() == 401 || response.code() == 403)
                    logins.rejectLogin(original, true, login);
            }
            if(BasicHttpAuthentication.basicChallenge(response)!=null)logins.noteBasicChallenge(original,true);
            ResponseBody body = response.body();
            if (body != null) {
                body.source().timeout().timeout(
                        SecurityLimits.MEDIA_READ_TIMEOUT_SECONDS,
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
                    "Media network request failed.");
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
            return values.isEmpty() ? null : String.join(",", values);
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
