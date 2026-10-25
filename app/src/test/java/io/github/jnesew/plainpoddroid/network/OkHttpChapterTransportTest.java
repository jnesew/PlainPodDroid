package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.chapter.ChapterTransport;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OkHttpChapterTransportTest {
    @Test public void sendsStaticHeadersAndSharesMetadataLease() throws Exception {
        AtomicReference<Request> captured=new AtomicReference<>();
        OkHttpClient client=NetworkClientFactory.create().newBuilder().addInterceptor(chain->{
            captured.set(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").addHeader("Content-Type","application/json+chapters")
                    .addHeader("X-Duplicate","one").addHeader("X-Duplicate","two")
                    .body(ResponseBody.create(new byte[] {1},null)).build();
        }).build();
        NetworkCallGate gate=new NetworkCallGate(2,1,1);
        OkHttpChapterTransport transport=new OkHttpChapterTransport(client,gate,"0.1-dev");
        try(ChapterTransport.Exchange exchange=transport.execute(
                CanonicalHttpsUrl.parse("https://publisher.example/chapters?token=secret"))) {
            Request request=captured.get();
            assertEquals("GET",request.method());
            assertEquals("application/json+chapters",request.header("Accept"));
            assertEquals("gzip",request.header("Accept-Encoding"));
            assertEquals("PlainPodDroid/0.1-dev",request.header("User-Agent"));
            for(String name:new String[] {"Cookie","Authorization","Referer","If-None-Match"}) {
                assertNull(request.header(name));
            }
            assertEquals("one,two",exchange.getHeader("X-Duplicate"));
            assertEquals(1,exchange.getBody().read());
            assertThrows(NetworkTransportException.class,()->gate.acquireFeed(0,TimeUnit.MILLISECONDS));
            gate.acquireMedia(0,TimeUnit.MILLISECONDS).close();
        }
        gate.acquireFeed(0,TimeUnit.MILLISECONDS).close();
    }

    @Test public void redactsFailuresReleasesLeaseAndRejectsVersionInjection() throws Exception {
        OkHttpClient client=NetworkClientFactory.create().newBuilder().addInterceptor(chain->{
            throw new IOException("https://publisher.example/private?token=secret");
        }).build();
        NetworkCallGate gate=new NetworkCallGate(1,1,1);
        OkHttpChapterTransport transport=new OkHttpChapterTransport(client,gate,"test");
        NetworkTransportException failure=assertThrows(NetworkTransportException.class,
                ()->transport.execute(CanonicalHttpsUrl.parse("https://publisher.example/private")));
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.getMessage().contains("private")); assertNull(failure.getCause());
        gate.acquireFeed(0,TimeUnit.MILLISECONDS).close();
        assertThrows(IllegalArgumentException.class,()->new OkHttpChapterTransport(client,gate,"device/model"));
        assertThrows(IllegalArgumentException.class,()->new OkHttpChapterTransport(client,gate,"x\r\nCookie: x"));
    }
}
