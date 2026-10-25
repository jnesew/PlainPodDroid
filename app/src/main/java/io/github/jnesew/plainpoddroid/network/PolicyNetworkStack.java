package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.artwork.ArtworkTransport;
import io.github.jnesew.plainpoddroid.core.chapter.ChapterTransport;
import io.github.jnesew.plainpoddroid.core.download.MediaDownloadTransport;

import okhttp3.OkHttpClient;
import okhttp3.Call;

public final class PolicyNetworkStack {
    private final OkHttpClient client;
    private final io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider logins;
    private final NetworkCallGate callGate;
    private final OfflineNetworkGuard offline;

    private PolicyNetworkStack(OkHttpClient client, NetworkCallGate callGate,
            io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider logins, OfflineNetworkGuard offline) {
        this.logins = java.util.Objects.requireNonNull(logins);
        this.client = client;
        this.callGate = callGate;
        this.offline = offline;
    }

    public static PolicyNetworkStack create() {
        return create(io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider.NONE);
    }

    public static PolicyNetworkStack create(io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider logins) {
        return create(logins, () -> false);
    }

    public static PolicyNetworkStack create(io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider logins,
            java.util.function.BooleanSupplier offlineMode) {
        OfflineNetworkGuard offline = new OfflineNetworkGuard(offlineMode);
        return new PolicyNetworkStack(NetworkClientFactory.create(okhttp3.Dns.SYSTEM, offline),
                new NetworkCallGate(), logins, offline);
    }

    public void onOfflineModeChanged() {
        offline.policyChanged();
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }

    /** Uses only the JobScheduler-selected network, retaining all URL/DNS/TLS/auth policies. */
    public PolicyNetworkStack forNetwork(android.net.Network network,
            java.util.function.BooleanSupplier cancelled) {
        java.util.Objects.requireNonNull(network);
        java.util.Objects.requireNonNull(cancelled);
        OfflineNetworkGuard jobGuard = new OfflineNetworkGuard(
                () -> offline.isOffline() || cancelled.getAsBoolean());
        return new PolicyNetworkStack(NetworkClientFactory.forNetwork(client, network, jobGuard),
                callGate, logins, jobGuard);
    }

    public FeedTransport newFeedTransport(String versionName) {
        return new OkHttpFeedTransport(client, callGate, versionName, logins);
    }

    public io.github.jnesew.plainpoddroid.core.subscription.ApplePodcastLookupTransport
            newApplePodcastLookupTransport(String versionName) {
        return new OkHttpApplePodcastLookupTransport(client, callGate, versionName);
    }

    public ChapterTransport newChapterTransport(String versionName) {
        return new OkHttpChapterTransport(client, callGate, versionName);
    }

    public ArtworkTransport newArtworkTransport(String versionName) {
        return new OkHttpArtworkTransport(client, callGate, versionName);
    }

    public MediaDownloadTransport newMediaDownloadTransport(String versionName) {
        return new OkHttpMediaDownloadTransport(client, callGate, versionName, logins);
    }

    public Call.Factory newMediaStreamingCallFactory(String versionName) {
        return newMediaStreamingCallFactory(versionName, () -> false);
    }

    public Call.Factory newMediaStreamingCallFactory(String versionName,
            java.util.function.BooleanSupplier streamingForbidden) {
        return NetworkClientFactory.forMedia(client)
                .newBuilder()
                .addInterceptor(new OfflineNetworkGuard(streamingForbidden))
                .addInterceptor(new OkHttpMediaStreamingInterceptor(callGate, versionName, logins))
                .build();
    }
}
