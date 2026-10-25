package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.artwork.ArtworkTransport;
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

final class OkHttpArtworkTransport implements ArtworkTransport {
    private final OkHttpClient client;
    private final NetworkCallGate callGate;
    private final String userAgent;

    OkHttpArtworkTransport(OkHttpClient client, NetworkCallGate callGate, String versionName) {
        this.client = Objects.requireNonNull(client, "client");
        this.callGate = Objects.requireNonNull(callGate, "callGate");
        if (versionName == null || !versionName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid application version for User-Agent.");
        }
        userAgent = "PlainPodDroid/" + versionName;
    }

    @Override public Exchange execute(CanonicalHttpsUrl url) throws IOException {
        Objects.requireNonNull(url, "url");
        NetworkCallGate.Lease lease = callGate.acquireMetadata();
        Response response = null;
        try {
            Request request = new Request.Builder().url(url.toString())
                    .header("Accept", "image/jpeg, image/png, image/webp")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", userAgent).get().build();
            response = client.newCall(request).execute();
            ResponseBody body = response.body();
            if (body != null) {
                body.source().timeout().timeout(SecurityLimits.FEED_READ_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
            }
            return new ExchangeImpl(response, body, lease);
        } catch (IOException | RuntimeException exception) {
            try { if (response != null) response.close(); }
            finally { lease.close(); }
            throw new NetworkTransportException(NetworkTransportException.Reason.REQUEST_FAILED,
                    "Artwork network request failed.");
        }
    }

    private static final class ExchangeImpl implements Exchange {
        private final Response response;
        private final ResponseBody body;
        private final NetworkCallGate.Lease lease;

        ExchangeImpl(Response response, ResponseBody body, NetworkCallGate.Lease lease) {
            this.response = response;
            this.body = body;
            this.lease = lease;
        }
        @Override public int getStatusCode() { return response.code(); }
        @Override public String getHeader(String name) {
            List<String> values = response.headers(name);
            return values.isEmpty() ? null : String.join(",", values);
        }
        @Override public InputStream getBody() { return body == null ? null : body.byteStream(); }
        @Override public void close() {
            try { response.close(); } finally { lease.close(); }
        }
    }
}
