package io.github.jnesew.plainpoddroid.core.network;

import java.io.IOException;
import java.io.InputStream;

public interface FeedTransport {
    Exchange execute(CanonicalHttpsUrl url, FeedRequestHeaders headers) throws IOException;

    interface Exchange extends AutoCloseable {
        int getStatusCode();

        String getHeader(String name);

        InputStream getBody();

        @Override
        void close() throws IOException;
    }
}
