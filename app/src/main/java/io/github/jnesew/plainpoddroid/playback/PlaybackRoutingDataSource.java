package io.github.jnesew.plainpoddroid.playback;

import android.net.Uri;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Routes only verified app-private files or policy-bound HTTPS sources. */
@UnstableApi
final class PlaybackRoutingDataSource implements DataSource {
    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory localFactory;
        private final DataSource.Factory remoteFactory;
        private final DataSource.Factory downloadFactory;

        Factory(DataSource.Factory localFactory, DataSource.Factory remoteFactory) {
            this(localFactory, remoteFactory, null);
        }

        Factory(DataSource.Factory localFactory, DataSource.Factory remoteFactory,
                DataSource.Factory downloadFactory) {
            this.localFactory = Objects.requireNonNull(localFactory, "localFactory");
            this.remoteFactory = Objects.requireNonNull(remoteFactory, "remoteFactory");
            this.downloadFactory = downloadFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new PlaybackRoutingDataSource(localFactory, remoteFactory, downloadFactory);
        }
    }

    private final DataSource.Factory localFactory;
    private final DataSource.Factory remoteFactory;
    private final DataSource.Factory downloadFactory;
    private final List<TransferListener> transferListeners = new ArrayList<>();
    private DataSource delegate;

    private PlaybackRoutingDataSource(
            DataSource.Factory localFactory,
            DataSource.Factory remoteFactory, DataSource.Factory downloadFactory) {
        this.localFactory = localFactory;
        this.remoteFactory = remoteFactory;
        this.downloadFactory = downloadFactory;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        if (delegate != null) {
            delegate.addTransferListener(transferListener);
        } else {
            transferListeners.add(Objects.requireNonNull(transferListener, "transferListener"));
        }
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        if (delegate != null) {
            throw new IOException("Data source is already open");
        }
        String scheme = dataSpec.uri.getScheme();
        if (scheme == null) {
            throw new IOException("Playback source scheme is unavailable");
        }
        DataSource.Factory selected;
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case io.github.jnesew.plainpoddroid.core.playback.DownloadPlaybackUri.SCHEME:
                if (downloadFactory == null) throw new IOException("Download playback is unavailable.");
                selected = downloadFactory;
                break;
            case "file":
                selected = localFactory;
                break;
            case "https":
                selected = remoteFactory;
                break;
            default:
                throw new IOException("Playback source scheme is unsupported");
        }
        DataSource source = selected.createDataSource();
        for (TransferListener listener : transferListeners) {
            source.addTransferListener(listener);
        }
        delegate = source;
        try {
            return source.open(dataSpec);
        } catch (IOException | RuntimeException exception) {
            delegate = null;
            try {
                source.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (delegate == null) {
            throw new IOException("Data source is not open");
        }
        return delegate.read(buffer, offset, length);
    }

    @Override
    public Uri getUri() {
        return delegate == null ? null : delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return delegate == null ? DataSource.super.getResponseHeaders()
                : delegate.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        if (delegate == null) {
            return;
        }
        DataSource source = delegate;
        delegate = null;
        source.close();
    }
}
