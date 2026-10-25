package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.subscription.ApplePodcastLookupTransport;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Does not use the feed credential provider, even when a server challenges the request. */
final class OkHttpApplePodcastLookupTransport implements ApplePodcastLookupTransport {
    private final OkHttpClient client;
    private final NetworkCallGate gate;
    private final String userAgent;

    OkHttpApplePodcastLookupTransport(OkHttpClient client, NetworkCallGate gate, String version) {
        this.client = Objects.requireNonNull(client);
        this.gate = Objects.requireNonNull(gate);
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid application version for User-Agent.");
        }
        userAgent = "PlainPodDroid/" + version;
    }

    @Override public FeedTransport.Exchange execute(CanonicalHttpsUrl url) throws IOException {
        if (!url.toString().matches("https://itunes\\.apple\\.com/lookup\\?id=[1-9][0-9]{0,18}&entity=podcast&country=[a-z]{2}")) {
            throw new IOException("Invalid Apple lookup target.");
        }
        NetworkCallGate.Lease lease = gate.acquireMetadata();
        Response response = null;
        try {
            Request request = new Request.Builder().url(url.toString())
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", userAgent).get().build();
            response = client.newCall(request).execute();
            ResponseBody body = response.body();
            if (body != null) body.source().timeout().timeout(
                    SecurityLimits.FEED_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new LookupExchange(response, body, lease);
        } catch (IOException | RuntimeException exception) {
            try { if (response != null) response.close(); }
            finally { lease.close(); }
            throw new NetworkTransportException(NetworkTransportException.Reason.REQUEST_FAILED,
                    "Apple Podcasts lookup failed.");
        }
    }

    private static final class LookupExchange implements FeedTransport.Exchange {
        private final Response response;
        private final ResponseBody body;
        private final NetworkCallGate.Lease lease;
        LookupExchange(Response response, ResponseBody body, NetworkCallGate.Lease lease) {
            this.response = response; this.body = body; this.lease = lease;
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
