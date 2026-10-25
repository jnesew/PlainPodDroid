package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.auth.*;
import io.github.jnesew.plainpoddroid.core.network.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BasicHttpAuthenticationTest {
    private static final String FEED = "https://feed.example/members/rss?token=secret";
    private static final String AUDIO = "https://audio.example/paid/episode.mp3?sig=secret";
    private static BasicFeedLogin login(String user, String password) throws Exception {
        return new BasicFeedLogin(CanonicalHttpsUrl.parse(FEED), user, password, CanonicalHttpsUrl.parse("https://audio.example/paid/"));
    }
    private static Response response(Request r, int code, String challenge) {
        return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                .header("WWW-Authenticate", challenge).body(ResponseBody.create(new byte[0], MediaType.get("audio/mpeg"))).build();
    }
    @Test public void negotiatesBasicEncodingAndNeverPreemptivelyOrTwiceRetries() throws Exception {
        Request request = new Request.Builder().url(FEED).build();
        BasicFeedLogin login = login("user", "password");
        Request retry = BasicHttpAuthentication.retry(response(request, 401, "Basic realm=\"members\""), login, false);
        assertEquals(login.authorization(StandardCharsets.ISO_8859_1), retry.header("Authorization"));
        assertNull(request.header("Authorization"));
        assertNull(BasicHttpAuthentication.retry(response(retry, 401, "Basic realm=\"members\""), login, false));
        assertNull(BasicHttpAuthentication.retry(response(request, 403, "Basic realm=\"members\""), login, false));
        assertNull(BasicHttpAuthentication.retry(response(request, 401, "Digest realm=\"members\""), login, false));
        assertNull(BasicHttpAuthentication.retry(response(request, 401, "Basic realm=\"members\", charset=\"unknown\""), login, false));
        assertNull(BasicHttpAuthentication.retry(response(request, 401, "Basic"), login, false));
        assertNull(BasicHttpAuthentication.retry(response(request, 401, "Basic realm=\"" + "a".repeat(4096) + "\""), login, false));
        BasicFeedLogin unicode = login("用户", "päss");
        Request utf = BasicHttpAuthentication.retry(response(request, 401, "Basic realm=\"members\", charset=\"UTF-8\""), unicode, false);
        assertEquals(unicode.authorization(StandardCharsets.UTF_8), utf.header("Authorization"));
        assertThrows(FeedLoginException.class, () -> BasicHttpAuthentication.retry(
                response(request, 401, "Basic realm=\"members\""), unicode, false));
    }
    @Test public void authenticatesFeedOnlyAfterChallengeAndSuspendsRejectedStoredLogin() throws Exception {
        List<Request> requests = new ArrayList<>();
        Provider provider = new Provider(login("user", "password"));
        OkHttpClient client = NetworkClientFactory.create().newBuilder().addInterceptor(chain -> {
            requests.add(chain.request());
            return response(chain.request(), 401, "Basic realm=\"members\"");
        }).build();
        OkHttpFeedTransport transport = new OkHttpFeedTransport(client, new NetworkCallGate(), "test", provider);
        var url = CanonicalHttpsUrl.parse(FEED);
        try (var exchange = transport.execute(url, FeedRequestHeaders.none().withLoginContext(url, null))) {
            assertEquals(401, exchange.getStatusCode());
        }
        assertEquals(2, requests.size()); assertNull(requests.get(0).header("Authorization"));
        assertNotNull(requests.get(1).header("Authorization")); assertTrue(provider.blocked);
        assertThrows(FeedLoginException.class, () -> transport.execute(url, FeedRequestHeaders.none().withLoginContext(url, null)));
        assertEquals(2, requests.size());
    }
    @Test public void authenticatedRangeRedirectRetainsOwnerAndDropsCredentialsOutsideApprovedScope() throws Exception {
        List<Request> requests = new ArrayList<>();
        Provider provider = new Provider(login("user", "password"));
        var streaming = NetworkClientFactory.create().newBuilder()
                .addInterceptor(new OkHttpMediaStreamingInterceptor(new NetworkCallGate(), "test", provider))
                .addInterceptor(chain -> {
                    Request r = chain.request(); requests.add(r);
                    if (r.url().host().equals("audio.example")) {
                        if (r.header("Authorization") == null) return response(r, 401, "Basic realm=\"members\"");
                        return response(r, 302, "Basic realm=\"members\"").newBuilder()
                                .header("Location", "https://unapproved.example/episode.mp3").build();
                    }
                    return response(r, 401, "Basic realm=\"malicious\"");
                }).build();
        try (Response response = streaming.newCall(new Request.Builder().url(AUDIO).header("Range", "bytes=2-").build()).execute()) {
            assertEquals(401, response.code());
        }
        assertEquals(3, requests.size());
        assertNotNull(requests.get(1).header("Authorization")); assertNull(requests.get(2).header("Authorization"));
        for (Request r : requests) { assertEquals("bytes=2-", r.header("Range")); assertNull(r.header("Cookie")); }
        assertFalse(provider.blocked); assertTrue(provider.sources.stream().allMatch(s -> s.equals(AUDIO)));
    }
    @Test public void removedOrCancelledLoginIsNotSentAfterAPendingChallenge() throws Exception {
        for (boolean explicit : new boolean[] {false, true}) {
            BasicFeedLogin login = login("user", "password"); Provider provider = new Provider(login);
            List<Request> requests = new ArrayList<>();
            var client = NetworkClientFactory.create().newBuilder().addInterceptor(chain -> {
                requests.add(chain.request());
                if (explicit) login.revoke(); else provider.login = null;
                return response(chain.request(), 401, "Basic realm=\"members\"");
            }).build();
            var source = CanonicalHttpsUrl.parse(FEED);
            var transport = new OkHttpFeedTransport(client, new NetworkCallGate(), "test", provider);
            try (var exchange = transport.execute(source, FeedRequestHeaders.none().withLoginContext(source, explicit ? login : null))) {
                assertEquals(401, exchange.getStatusCode());
            }
            assertEquals(1, requests.size()); assertNull(requests.get(0).header("Authorization"));
        }
    }

    @Test public void loginAffordanceRequiresSupportedFinalChallengeAcrossFeedDownloadAndStreaming() throws Exception {
        String[] challenges={"Basic realm=\"members\"","Digest realm=\"members\"","Basic","Basic realm=\"members\", charset=\"unsupported\""};
        for(int route=0;route<3;route++)for(int code:new int[]{200,401,403})for(String challenge:challenges){
            Provider provider=new Provider(null);
            OkHttpClient client=NetworkClientFactory.create().newBuilder().addInterceptor(chain->response(chain.request(),code,challenge)).build();
            var source=CanonicalHttpsUrl.parse(route==0?FEED:AUDIO);
            if(route==0)try(var exchange=new OkHttpFeedTransport(client,new NetworkCallGate(),"test",provider).execute(source,FeedRequestHeaders.none())){assertEquals(code,exchange.getStatusCode());}
            else if(route==1)try(var exchange=new OkHttpMediaDownloadTransport(client,new NetworkCallGate(),"test",provider).execute(source)){assertEquals(code,exchange.getStatusCode());}
            else try(var response=NetworkClientFactory.create().newBuilder()
                    .addInterceptor(new OkHttpMediaStreamingInterceptor(new NetworkCallGate(),"test",provider))
                    .addInterceptor(chain->response(chain.request(),code,challenge)).build().newCall(new Request.Builder().url(source.toString()).build()).execute()){
                assertEquals(code,response.code());
            }
            assertEquals(code==401&&challenge.equals(challenges[0])?1:0,provider.challenges.size());
            if(!provider.challenges.isEmpty())assertEquals(source.toString()+":"+(route!=0),provider.challenges.get(0));
        }
    }

    private static final class Provider implements FeedLoginProvider {
        final List<String> challenges=new ArrayList<>();
        public void noteBasicChallenge(CanonicalHttpsUrl source,boolean audio){challenges.add(source.toString()+":"+audio);}
        BasicFeedLogin login; boolean blocked; final List<String> sources = new ArrayList<>();
        Provider(BasicFeedLogin login) { this.login = login; }
        public BasicFeedLogin findFeedLogin(CanonicalHttpsUrl source) throws java.io.IOException {
            sources.add(source.toString()); if (blocked) throw new FeedLoginException(); return login;
        }
        public BasicFeedLogin findAudioLogin(CanonicalHttpsUrl source) throws java.io.IOException { return findFeedLogin(source); }
        public void rejectLogin(CanonicalHttpsUrl source, boolean audio, BasicFeedLogin attempted) { blocked = true; }
    }
}
