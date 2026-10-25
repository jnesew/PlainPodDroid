package io.github.jnesew.plainpoddroid.core.artwork;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.IOException;
import java.io.InputStream;

public interface ArtworkTransport {
    Exchange execute(CanonicalHttpsUrl url) throws IOException;

    interface Exchange extends AutoCloseable {
        int getStatusCode();
        String getHeader(String name);
        InputStream getBody();
        @Override void close();
    }
}
