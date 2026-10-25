package io.github.jnesew.plainpoddroid.playback;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import io.github.jnesew.plainpoddroid.core.playback.DownloadPlaybackUri;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackDownloadPreparer;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Waits for the complete file; it never exposes network bytes to the player. */
@UnstableApi
final class DownloadBeforePlayingDataSource implements DataSource {
    static final class Factory implements DataSource.Factory {
        private final PlaybackDownloadPreparer preparer;
        private final DataSource.Factory local;
        Factory(PlaybackDownloadPreparer preparer, DataSource.Factory local) {
            this.preparer = Objects.requireNonNull(preparer);
            this.local = Objects.requireNonNull(local);
        }
        @Override public DataSource createDataSource() {
            return new DownloadBeforePlayingDataSource(preparer, local);
        }
    }
    private final PlaybackDownloadPreparer preparer;
    private final DataSource.Factory local;
    private final List<TransferListener> listeners = new ArrayList<>();
    private AtomicBoolean cancelled = new AtomicBoolean();
    private DataSource delegate;
    private DownloadBeforePlayingDataSource(PlaybackDownloadPreparer preparer, DataSource.Factory local) {
        this.preparer = preparer;
        this.local = local;
    }
    @Override public void addTransferListener(TransferListener listener) {
        listeners.add(Objects.requireNonNull(listener));
        if (delegate != null) delegate.addTransferListener(listener);
    }
    @Override public long open(DataSpec spec) throws IOException {
        if (delegate != null) throw new IOException("Playback source is already open.");
        cancelled = new AtomicBoolean();
        try {
            String id = DownloadPlaybackUri.mediaId(URI.create(spec.uri.toString()));
            var file = preparer.prepare(id, cancelled::get);
            if (cancelled.get() || Thread.currentThread().isInterrupted())
                throw new IOException("Playback download was cancelled.");
            delegate = local.createDataSource();
            for (TransferListener listener : listeners) delegate.addTransferListener(listener);
            return delegate.open(spec.buildUpon().setUri(Uri.fromFile(file)).build());
        } catch (IOException | RuntimeException exception) {
            try { close(); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            throw new IOException("Unable to prepare downloaded audio.", exception);
        }
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (delegate == null) throw new IOException("Downloaded audio is not open.");
        return delegate.read(bytes, offset, length);
    }
    @Override public Uri getUri() { return delegate == null ? null : delegate.getUri(); }
    @Override public void close() throws IOException {
        cancelled.set(true);
        if (delegate != null) {
            DataSource source = delegate;
            delegate = null;
            source.close();
        }
    }
}
