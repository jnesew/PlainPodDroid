package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.net.Proxy;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.OkHttpClient;

final class NetworkClientFactory {
    private NetworkClientFactory() {
        throw new AssertionError("No instances");
    }

    static OkHttpClient create() {
        return create(Dns.SYSTEM);
    }

    static OkHttpClient create(Dns resolver) {
        return create(resolver, new OfflineNetworkGuard(() -> false));
    }

    static OkHttpClient create(Dns resolver, OfflineNetworkGuard offline) {
        Objects.requireNonNull(resolver, "resolver");
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(SecurityLimits.MAX_CONCURRENT_NETWORK_CALLS);
        dispatcher.setMaxRequestsPerHost(SecurityLimits.MAX_CONCURRENT_FEED_CALLS);

        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .dns(new PolicyDns(resolver, offline::isOffline))
                .addInterceptor(offline)
                .proxy(Proxy.NO_PROXY)
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .fastFallback(false)
                .connectTimeout(
                        SecurityLimits.FEED_CONNECT_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)
                // This governs the initial response-header read. The feed exchange
                // switches its bounded response body to the documented 20-second idle limit.
                .readTimeout(
                        SecurityLimits.FEED_RESPONSE_HEADER_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)
                .writeTimeout(
                        SecurityLimits.FEED_READ_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)
                .callTimeout(
                        SecurityLimits.FEED_CALL_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)
                .build();
    }

    static OkHttpClient forMedia(OkHttpClient baseClient) {
        Objects.requireNonNull(baseClient, "baseClient");
        return baseClient.newBuilder()
                .readTimeout(
                        SecurityLimits.MEDIA_RESPONSE_HEADER_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)
                .callTimeout(0L, TimeUnit.MILLISECONDS)
                .build();
    }

    static OkHttpClient forNetwork(OkHttpClient base, android.net.Network network,
            OfflineNetworkGuard guard) {
        Objects.requireNonNull(network);
        return forNetwork(base, network.getSocketFactory(),
                host -> java.util.Arrays.asList(network.getAllByName(host)), guard);
    }

    static OkHttpClient forNetwork(OkHttpClient base, javax.net.SocketFactory sockets,
            Dns resolver, OfflineNetworkGuard guard) {
        Objects.requireNonNull(sockets);
        Objects.requireNonNull(resolver);
        Objects.requireNonNull(guard);
        Dispatcher dispatcher = new Dispatcher(base.dispatcher().executorService());
        dispatcher.setMaxRequests(SecurityLimits.MAX_CONCURRENT_NETWORK_CALLS);
        dispatcher.setMaxRequestsPerHost(SecurityLimits.MAX_CONCURRENT_FEED_CALLS);
        return base.newBuilder().dispatcher(dispatcher).connectionPool(new okhttp3.ConnectionPool())
                .socketFactory(sockets)
                .dns(new PolicyDns(resolver, guard::isOffline))
                .addInterceptor(guard).build();
    }
}
