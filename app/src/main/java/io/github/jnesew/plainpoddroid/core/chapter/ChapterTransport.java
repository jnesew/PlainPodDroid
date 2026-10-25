package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.IOException;
import java.io.InputStream;

/** A single policy-validated request; redirects are handled by the bounded acquirer. */
public interface ChapterTransport {
    Exchange execute(CanonicalHttpsUrl url) throws IOException;

    interface Exchange extends AutoCloseable {
        int getStatusCode();
        String getHeader(String name);
        InputStream getBody();
        @Override void close();
    }
}
