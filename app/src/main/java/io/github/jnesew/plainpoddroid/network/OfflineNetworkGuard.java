package io.github.jnesew.plainpoddroid.network;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** One shared boundary for every client and response body, including media clones. */
final class OfflineNetworkGuard implements Interceptor {
    private final BooleanSupplier offline;
    private final Set<Call> active = new HashSet<>();
    private long generation;

    OfflineNetworkGuard(BooleanSupplier offline) { this.offline = Objects.requireNonNull(offline); }
    boolean isOffline() { return offline.getAsBoolean(); }

    void policyChanged() {
        final Call[] cancelled;
        synchronized (this) {
            generation++;
            cancelled = active.toArray(new Call[0]);
            active.clear();
        }
        for (Call call : cancelled) call.cancel();
    }

    private synchronized void requireOnline(long started) throws IOException {
        if (isOffline() || generation != started) throw new IOException("Offline mode blocks network access.");
    }
    private synchronized void finished(Call call) { active.remove(call); }

    @Override public Response intercept(Chain chain) throws IOException {
        final long started;
        final Call call = chain.call();
        synchronized (this) {
            started = generation;
            requireOnline(started);
            active.add(call);
        }
        Response response = null;
        try {
            response = chain.proceed(chain.request());
            requireOnline(started);
            ResponseBody body = response.body();
            if (body == null) { finished(call); return response; }
            BufferedSource guarded = Okio.buffer(new ForwardingSource(body.source()) {
                @Override public long read(Buffer sink, long byteCount) throws IOException {
                    requireOnline(started);
                    long read = super.read(sink, byteCount);
                    requireOnline(started);
                    if (read == -1) finished(call);
                    return read;
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { finished(call); }
                }
            });
            return response.newBuilder().body(new ResponseBody() {
                @Override public MediaType contentType() { return body.contentType(); }
                @Override public long contentLength() { return body.contentLength(); }
                @Override public BufferedSource source() { return guarded; }
            }).build();
        } catch (IOException | RuntimeException exception) {
            if (response != null) response.close();
            finished(call);
            throw exception;
        }
    }
}
