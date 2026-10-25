package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;

import java.io.IOException;
import java.io.InputStream;

public interface MediaDownloadTransport {
    Exchange execute(CanonicalHttpsUrl url) throws IOException;

    default Exchange execute(CanonicalHttpsUrl url, CanonicalHttpsUrl original) throws IOException {
        return execute(url);
    }

    interface Exchange extends AutoCloseable {
        int getStatusCode();

        String getHeader(String name);

        InputStream getBody();

        @Override
        void close() throws IOException;
    }
}
