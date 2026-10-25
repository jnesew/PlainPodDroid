package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.download.ProgressiveAudioPolicy;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;




/** Applies the feed/download network boundary to each progressive playback request. */
final class OkHttpMediaStreamingInterceptor implements Interceptor {
    private static final String ACCEPT = "audio/*, application/octet-stream;q=0.5";
    private static final String FAILURE_MESSAGE = "Media streaming request failed.";

    private final NetworkCallGate callGate;
    private final FeedLoginProvider logins;
    private final String userAgent;

    OkHttpMediaStreamingInterceptor(NetworkCallGate callGate, String versionName) {
        this(callGate, versionName, FeedLoginProvider.NONE);
    }

    OkHttpMediaStreamingInterceptor(NetworkCallGate callGate, String versionName,
            FeedLoginProvider logins) {
        this.logins = Objects.requireNonNull(logins);
        this.callGate = Objects.requireNonNull(callGate, "callGate");
        userAgent = staticUserAgent(versionName);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        NetworkCallGate.Lease lease = callGate.acquirePlayback();
        Response response = null;
        boolean leaseHandedOff = false;
        try {
            Request request = chain.request();
            if (!"GET".equals(request.method())) {
                throw new IOException(FAILURE_MESSAGE);
            }
            CanonicalHttpsUrl current = canonical(request.url().toString());
            CanonicalHttpsUrl original = current;
            Set<String> visited = new HashSet<>();
            visited.add(current.toString());
            request = safeRequest(request, current);

            for (int redirectCount = 0; ; redirectCount++) {
                var login = logins.findAudioLogin(original);
                response = chain.proceed(request);
                if (response.code() == 401) login = logins.findAudioLogin(original);
                Request retry = BasicHttpAuthentication.retry(response, login, true);
                if (retry != null) {
                    response.close(); response = null;
                    response = chain.proceed(retry);
                    if (response.code() == 401 || response.code() == 403) logins.rejectLogin(original, true, login);
                }
                if(BasicHttpAuthentication.basicChallenge(response)!=null)logins.noteBasicChallenge(original,true);
                if (!isRedirect(response.code())) {
                    validateFinalResponse(current, response);
                    Response leased = withLease(response, lease);
                    response = null;
                    leaseHandedOff = true;
                    return leased;
                }
                if (redirectCount >= SecurityLimits.MAX_MEDIA_REDIRECTS) {
                    throw new IOException(FAILURE_MESSAGE);
                }
                CanonicalHttpsUrl destination = redirectDestination(
                        current,
                        response.header("Location"));
                if (!visited.add(destination.toString())) {
                    throw new IOException(FAILURE_MESSAGE);
                }
                response.close();
                response = null;
                current = destination;
                request = safeRequest(request, current);
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof FeedLoginException loginFailure) throw loginFailure;
            throw new IOException(FAILURE_MESSAGE);
        } finally {
            if (response != null) {
                response.close();
            }
            if (!leaseHandedOff) {
                lease.close();
            }
        }
    }

    private Request safeRequest(Request request, CanonicalHttpsUrl url) {
        return request.newBuilder()
                .url(url.toString())
                .removeHeader("Authorization")
                .removeHeader("Cookie")
                .removeHeader("Proxy-Authorization")
                .removeHeader("Referer")
                .header("Accept", ACCEPT)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", userAgent)
                .get()
                .build();
    }

    private static void validateFinalResponse(
            CanonicalHttpsUrl finalUrl,
            Response response) throws IOException {
        if (response.code() != 200 && response.code() != 206) {
            return;
        }
        if (response.body() == null
                || !ProgressiveAudioPolicy.isEligible(finalUrl, "audio/mpeg")) {
            throw new IOException(FAILURE_MESSAGE);
        }
        String encoding = response.header("Content-Encoding");
        if (encoding != null
                && !encoding.trim().isEmpty()
                && !"identity".equals(encoding.trim().toLowerCase(Locale.ROOT))) {
            throw new IOException(FAILURE_MESSAGE);
        }
        String contentType = response.header("Content-Type");
        if (contentType != null
                && !contentType.trim().isEmpty()
                && !ProgressiveAudioPolicy.isEligible(finalUrl, contentType)) {
            throw new IOException(FAILURE_MESSAGE);
        }
        validateLength(response.header("Content-Length"));
        validateContentRange(response.header("Content-Range"));
    }

    private static void validateLength(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            long length = Long.parseLong(value);
            if (length < 0L || length > SecurityLimits.MAX_DOWNLOAD_BYTES) {
                throw new NumberFormatException("outside limit");
            }
        } catch (NumberFormatException exception) {
            throw new IOException(FAILURE_MESSAGE);
        }
    }

    private static void validateContentRange(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash == value.length() - 1) {
            throw new IOException(FAILURE_MESSAGE);
        }
        String total = value.substring(slash + 1);
        if ("*".equals(total)) {
            return;
        }
        validateLength(total);
    }

    private static Response withLease(
            Response response,
            NetworkCallGate.Lease lease) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException(FAILURE_MESSAGE);
        }
        MediaType contentType = body.contentType();
        long contentLength = body.contentLength();
        BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
            private boolean closed;
            private long bytesRead;

            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                if (closed) throw new IOException(FAILURE_MESSAGE);
                if (byteCount == 0) return 0;
                try {
                    if (bytesRead == SecurityLimits.MAX_DOWNLOAD_BYTES) {
                        // Probe EOF without exposing a byte beyond the resource ceiling.
                        if (super.read(new Buffer(), 1) == -1) return -1;
                        throw new IOException(FAILURE_MESSAGE);
                    }
                    long count = super.read(sink, Math.min(byteCount,
                            SecurityLimits.MAX_DOWNLOAD_BYTES - bytesRead));
                    if (count > 0) bytesRead += count;
                    return count;
                } catch (IOException | RuntimeException failure) {
                    try { close(); } catch (IOException ignored) { /* Preserve the redacted failure. */ }
                    throw new IOException(FAILURE_MESSAGE);
                }
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    super.close();
                } finally {
                    lease.close();
                }
            }
        });
        ResponseBody leasedBody = new ResponseBody() {
            @Override
            public MediaType contentType() {
                return contentType;
            }

            @Override
            public long contentLength() {
                return contentLength;
            }

            @Override
            public BufferedSource source() {
                return source;
            }
        };
        return response.newBuilder().body(leasedBody).build();
    }

    private static CanonicalHttpsUrl redirectDestination(
            CanonicalHttpsUrl source,
            String location) throws IOException {
        if (location == null || location.isEmpty()
                || location.length() > SecurityLimits.MAX_URL_CHARS) {
            throw new IOException(FAILURE_MESSAGE);
        }
        try {
            URI resolved = source.toUri().resolve(new URI(location));
            return canonical(resolved.toString());
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IOException(FAILURE_MESSAGE);
        }
    }

    private static CanonicalHttpsUrl canonical(String value) throws IOException {
        try {
            return CanonicalHttpsUrl.parse(value);
        } catch (NetworkPolicyException | RuntimeException exception) {
            throw new IOException(FAILURE_MESSAGE);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    private static String staticUserAgent(String versionName) {
        Objects.requireNonNull(versionName, "versionName");
        if (!versionName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid application version for User-Agent.");
        }
        return "PlainPodDroid/" + versionName;
    }
}
