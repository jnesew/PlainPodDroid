package io.github.jnesew.plainpoddroid.playback;

import android.net.Uri;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;

import io.github.jnesew.plainpoddroid.core.download.PlaybackDownloadStore;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@UnstableApi
public final class VerifiedDownloadDataSource implements DataSource {
    public static final class Factory implements DataSource.Factory {
        private final PlaybackDownloadStore downloadStore;

        public Factory(PlaybackDownloadStore downloadStore) {
            this.downloadStore = Objects.requireNonNull(downloadStore, "downloadStore");
        }

        @Override
        public DataSource createDataSource() {
            return new VerifiedDownloadDataSource(downloadStore);
        }
    }

    private final PlaybackDownloadStore downloadStore;
    private final List<TransferListener> transferListeners = new ArrayList<>();
    private DataSource delegate;
    private Closeable readLease;

    private VerifiedDownloadDataSource(PlaybackDownloadStore downloadStore) {
        this.downloadStore = downloadStore;
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
        DataSource fileSource = new FileDataSource();
        for (TransferListener listener : transferListeners) {
            fileSource.addTransferListener(listener);
        }
        readLease = downloadStore.acquireReadLease(dataSpec.uri.toString());
        delegate = fileSource;
        try {
            return fileSource.open(dataSpec);
        } catch (IOException | RuntimeException exception) {
            delegate = null;
            try {
                fileSource.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            } finally {
                try {
                    readLease.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
                readLease = null;
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
        try {
            source.close();
        } finally {
            try {
                readLease.close();
            } finally {
                readLease = null;
            }
        }
    }
}
