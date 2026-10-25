package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.net.InetAddress;
import java.net.Proxy;
import java.util.List;

import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

public final class NetworkClientFactoryTest {
    @Test public void newDnsCallApiRetainsPublicAddressAndOfflineGuards() throws Exception {
        var offline = new java.util.concurrent.atomic.AtomicBoolean();
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        OkHttpClient client = NetworkClientFactory.create(host -> {
            lookups.incrementAndGet();
            return host.equals("public.example")
                    ? List.of(InetAddress.getByAddress(new byte[]{8,8,8,8}))
                    : List.of(InetAddress.getByAddress(new byte[]{8,8,8,8}),
                            InetAddress.getByAddress(new byte[]{127,0,0,1}));
        }, new OfflineNetworkGuard(offline::get));
        for (int step = 0; step < 3; step++) {
            if (step == 2) offline.set(true);
            var complete = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<java.io.IOException>();
            var records = new java.util.concurrent.atomic.AtomicInteger();
            Dns.Call call = client.dns().newCall(new Dns.Request(
                    step == 1 ? "mixed.example" : "public.example", 443));
            call.enqueue(new Dns.Callback() {
                @Override public void onRecords(Dns.Call ignored, boolean cached,
                        List<? extends Dns.Record> answers) {
                    records.addAndGet(answers.size()); complete.countDown();
                }
                @Override public void onFailure(Dns.Call ignored, java.io.IOException error) {
                    failure.set(error); complete.countDown();
                }
            });
            try {
                assertTrue(complete.await(5, java.util.concurrent.TimeUnit.SECONDS));
                if (step == 0) {
                    assertEquals(1, records.get()); assertEquals(null, failure.get());
                } else {
                    assertEquals(0, records.get());
                    assertTrue(failure.get() instanceof java.net.UnknownHostException);
                }
            } finally { call.cancel(); }
        }
        assertEquals(2, lookups.get()); // Offline never reaches the underlying resolver.
    }

    @Test public void jobNetworkOwnsBothDnsAndSocketsAndCannotFallBackToTheDefaultRoute() throws Exception {
        var defaultDns = new java.util.concurrent.atomic.AtomicInteger();
        var selectedDns = new java.util.concurrent.atomic.AtomicInteger();
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        OkHttpClient base = NetworkClientFactory.create(host -> { defaultDns.incrementAndGet(); throw new java.net.UnknownHostException(); });
        javax.net.SocketFactory sockets = new RefusingSockets();
        OkHttpClient job = NetworkClientFactory.forNetwork(base, sockets, host -> {
            selectedDns.incrementAndGet();
            return List.of(InetAddress.getByAddress(new byte[]{8,8,8,8}));
        }, new OfflineNetworkGuard(stopped::get));
        assertSame(sockets, job.socketFactory());
        assertNotSame(base.connectionPool(), job.connectionPool());
        assertNotSame(base.dispatcher(), job.dispatcher());
        assertSame(base.dispatcher().executorService(), job.dispatcher().executorService());
        assertSame(base.sslSocketFactory(), job.sslSocketFactory());
        assertSame(base.hostnameVerifier(), job.hostnameVerifier());
        assertSame(Proxy.NO_PROXY, job.proxy()); assertSame(CookieJar.NO_COOKIES, job.cookieJar());
        assertSame(Authenticator.NONE, job.authenticator()); assertFalse(job.followRedirects());
        assertEquals(2, job.interceptors().size());
        assertEquals(1, job.dns().lookup("feeds.example").size());
        assertEquals(1, selectedDns.get()); assertEquals(0, defaultDns.get());
        stopped.set(true);
        assertThrows(java.net.UnknownHostException.class, () -> job.dns().lookup("feeds.example"));
        assertEquals(1, selectedDns.get());
        OkHttpClient privateAnswer = NetworkClientFactory.forNetwork(base, sockets,
                host -> List.of(InetAddress.getByAddress(new byte[]{127,0,0,1})), new OfflineNetworkGuard(() -> false));
        assertThrows(java.net.UnknownHostException.class, () -> privateAnswer.dns().lookup("feeds.example"));
        assertEquals(0, defaultDns.get());
    }

    @Test public void stoppingAJobCancelsItsBodiesWhileFullOfflineStillCancelsEveryClient() throws Exception {
        var offline = new java.util.concurrent.atomic.AtomicBoolean();
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        OfflineNetworkGuard global = new OfflineNetworkGuard(offline::get);
        OfflineNetworkGuard local = new OfflineNetworkGuard(() -> offline.get() || stop.get());
        OkHttpClient base = NetworkClientFactory.create(host -> { throw new java.net.UnknownHostException(); }, global);
        OkHttpClient job = NetworkClientFactory.forNetwork(base, new RefusingSockets(),
                host -> { throw new java.net.UnknownHostException(); }, local);
        var request = new okhttp3.Request.Builder().url("https://feeds.example/feed").build();
        var ordinary = withResponse(base).newCall(request);
        var preparing = withResponse(job).newCall(request);
        try (var first = ordinary.execute(); var second = preparing.execute()) {
            stop.set(true); local.policyChanged();
            assertTrue(preparing.isCanceled()); assertFalse(ordinary.isCanceled());
            assertEquals("cover", first.body().string());
            assertThrows(java.io.IOException.class, () -> second.body().string());
            assertThrows(java.io.IOException.class, () -> withResponse(job).newCall(request).execute());
        }
        stop.set(false);
        ordinary = withResponse(base).newCall(request); preparing = withResponse(job).newCall(request);
        try (var first = ordinary.execute(); var second = preparing.execute()) {
            offline.set(true); global.policyChanged(); offline.set(false);
            assertTrue(ordinary.isCanceled()); assertTrue(preparing.isCanceled());
            assertThrows(java.io.IOException.class, () -> first.body().string());
            assertThrows(java.io.IOException.class, () -> second.body().string());
        }
    }

    private static OkHttpClient withResponse(OkHttpClient client) {
        return client.newBuilder().addInterceptor(chain -> new okhttp3.Response.Builder().request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create("cover", okhttp3.MediaType.get("text/plain"))).build()).build();
    }

    private static final class RefusingSockets extends javax.net.SocketFactory {
        private java.net.Socket refused() throws java.io.IOException { throw new java.io.IOException("Selected network is unavailable."); }
        @Override public java.net.Socket createSocket() throws java.io.IOException { return refused(); }
        @Override public java.net.Socket createSocket(String h, int p) throws java.io.IOException { return refused(); }
        @Override public java.net.Socket createSocket(String h, int p, InetAddress l, int lp) throws java.io.IOException { return refused(); }
        @Override public java.net.Socket createSocket(InetAddress h, int p) throws java.io.IOException { return refused(); }
        @Override public java.net.Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws java.io.IOException { return refused(); }
    }

    @Test
    public void createsTheDirectFailClosedClient() throws Exception {
        Dns delegate = hostname -> List.of(InetAddress.getByAddress(
                new byte[] {1, 1, 1, 1}));

        OkHttpClient client = NetworkClientFactory.create(delegate);

        assertTrue(client.dns() instanceof PolicyDns);
        assertSame(Proxy.NO_PROXY, client.proxy());
        assertSame(CookieJar.NO_COOKIES, client.cookieJar());
        assertSame(Authenticator.NONE, client.authenticator());
        assertSame(Authenticator.NONE, client.proxyAuthenticator());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
        assertFalse(client.retryOnConnectionFailure());
        assertFalse(client.fastFallback());
        assertEquals(1, client.interceptors().size());
        assertTrue(client.interceptors().get(0) instanceof OfflineNetworkGuard);
        assertEquals(0, client.networkInterceptors().size());
        assertEquals(
                SecurityLimits.FEED_CONNECT_TIMEOUT_SECONDS * 1_000,
                client.connectTimeoutMillis());
        assertEquals(
                SecurityLimits.FEED_RESPONSE_HEADER_TIMEOUT_SECONDS * 1_000,
                client.readTimeoutMillis());
        assertEquals(
                SecurityLimits.FEED_CALL_TIMEOUT_SECONDS * 1_000,
                client.callTimeoutMillis());
    }

    @Test
    public void fixesDispatcherCeilings() {
        OkHttpClient client = NetworkClientFactory.create(
                hostname -> List.of(InetAddress.getLoopbackAddress()));

        assertEquals(
                SecurityLimits.MAX_CONCURRENT_NETWORK_CALLS,
                client.dispatcher().getMaxRequests());
        assertEquals(
                SecurityLimits.MAX_CONCURRENT_FEED_CALLS,
                client.dispatcher().getMaxRequestsPerHost());
    }

    @Test
    public void derivesMediaTimeoutsWithoutWeakeningBasePolicy() throws Exception {
        Dns delegate = hostname -> List.of(InetAddress.getByAddress(
                new byte[] {1, 1, 1, 1}));
        OkHttpClient base = NetworkClientFactory.create(delegate);

        OkHttpClient media = NetworkClientFactory.forMedia(base);

        assertSame(base.dns(), media.dns());
        assertSame(base.dispatcher(), media.dispatcher());
        assertSame(Proxy.NO_PROXY, media.proxy());
        assertSame(CookieJar.NO_COOKIES, media.cookieJar());
        assertSame(Authenticator.NONE, media.authenticator());
        assertSame(Authenticator.NONE, media.proxyAuthenticator());
        assertFalse(media.followRedirects());
        assertFalse(media.followSslRedirects());
        assertFalse(media.retryOnConnectionFailure());
        assertEquals(
                SecurityLimits.MEDIA_RESPONSE_HEADER_TIMEOUT_SECONDS * 1_000,
                media.readTimeoutMillis());
        assertEquals(0, media.callTimeoutMillis());
        assertEquals(base.connectTimeoutMillis(), media.connectTimeoutMillis());
    }
}
