package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;



/** Synchronous download/delete boundary. Callers provide background execution. */
public final class EpisodeDownloadCoordinator implements EpisodeDownloadActions {
    private static final int BUFFER_BYTES = 32 * 1024;

    private final EpisodeDownloadCatalog catalog;
    private final AppPrivateDownloadStore store;
    private final MediaDownloadTransport transport;
    private final LongSupplier quotaBytesSupplier;
    private final long minimumQuotaBytes;
    private final long maximumActiveNanos;
    private final LongSupplier nanoClock;
    private final BooleanSupplier offlineMode;
    private final Set<String> activeMediaIds = new HashSet<>();

    public EpisodeDownloadCoordinator(
            EpisodeDownloadCatalog catalog,
            AppPrivateDownloadStore store,
            MediaDownloadTransport transport) {
        this(
                catalog,
                store,
                transport,
                () -> SecurityLimits.DEFAULT_DOWNLOAD_QUOTA_BYTES,
                SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES,
                SecurityLimits.MAX_DOWNLOAD_ACTIVE_MILLIS,
                System::nanoTime);
    }

    public EpisodeDownloadCoordinator(
            EpisodeDownloadCatalog catalog,
            AppPrivateDownloadStore store,
            MediaDownloadTransport transport,
            LongSupplier quotaBytesSupplier) {
        this(
                catalog,
                store,
                transport,
                quotaBytesSupplier,
                SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES,
                SecurityLimits.MAX_DOWNLOAD_ACTIVE_MILLIS,
                System::nanoTime);
    }

    EpisodeDownloadCoordinator(
            EpisodeDownloadCatalog catalog,
            AppPrivateDownloadStore store,
            MediaDownloadTransport transport,
            long quotaBytes,
            long maximumActiveMillis,
            LongSupplier nanoClock) {
        this(
                catalog,
                store,
                transport,
                fixedQuotaSupplier(quotaBytes),
                AppPrivateDownloadStore.COMPLETION_MARKER_BYTES + 1L,
                maximumActiveMillis,
                nanoClock);
    }

    EpisodeDownloadCoordinator(
            EpisodeDownloadCatalog catalog,
            AppPrivateDownloadStore store,
            MediaDownloadTransport transport,
            LongSupplier quotaBytesSupplier,
            long minimumQuotaBytes,
            long maximumActiveMillis,
            LongSupplier nanoClock) {
        this(catalog, store, transport, quotaBytesSupplier, minimumQuotaBytes,
                maximumActiveMillis, nanoClock, () -> false);
    }

    public EpisodeDownloadCoordinator(
            EpisodeDownloadCatalog catalog, AppPrivateDownloadStore store,
            MediaDownloadTransport transport, LongSupplier quotaBytesSupplier,
            BooleanSupplier offlineMode) {
        this(catalog, store, transport, quotaBytesSupplier, SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES,
                SecurityLimits.MAX_DOWNLOAD_ACTIVE_MILLIS, System::nanoTime, offlineMode);
    }

    EpisodeDownloadCoordinator(
            EpisodeDownloadCatalog catalog, AppPrivateDownloadStore store,
            MediaDownloadTransport transport, LongSupplier quotaBytesSupplier,
            long minimumQuotaBytes, long maximumActiveMillis, LongSupplier nanoClock,
            BooleanSupplier offlineMode) {
        this.offlineMode = Objects.requireNonNull(offlineMode, "offlineMode");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.quotaBytesSupplier = Objects.requireNonNull(
                quotaBytesSupplier,
                "quotaBytesSupplier");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (minimumQuotaBytes <= AppPrivateDownloadStore.COMPLETION_MARKER_BYTES
                || minimumQuotaBytes > SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES
                || maximumActiveMillis <= 0L
                || maximumActiveMillis > SecurityLimits.MAX_DOWNLOAD_ACTIVE_MILLIS) {
            throw new IllegalArgumentException("Invalid download limits.");
        }
        this.minimumQuotaBytes = minimumQuotaBytes;
        this.maximumActiveNanos = Math.multiplyExact(maximumActiveMillis, 1_000_000L);
    }

    @Override
    public EpisodeDownloadResult download(
            String mediaId,
            BooleanSupplier cancellationRequested) throws EpisodeDownloadException {
        validateMediaId(mediaId);
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        checkCancelled(cancellationRequested);
        acquire(mediaId);
        try {
            return downloadActive(mediaId, cancellationRequested);
        } finally {
            release(mediaId);
        }
    }

    @Override
    public void delete(String mediaId) throws EpisodeDownloadException {
        validateMediaId(mediaId);
        acquire(mediaId);
        try {
            EpisodeDownloadTarget target;
            try {
                target = catalog.findEpisodeDeletionTarget(mediaId);
            } catch (IOException | RuntimeException exception) {
                throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                        "Episode deletion target could not be read.");
            }
            if (target == null) {
                throw failure(EpisodeDownloadException.Reason.EPISODE_NOT_FOUND,
                        "Episode is no longer in the catalog.");
            }
            try {
                store.delete(mediaId);
                catalog.markEpisodeDownloadAbsent(mediaId);
            } catch (DownloadInUseException exception) {
                throw failure(exception.isPlayback() ? EpisodeDownloadException.Reason.PLAYBACK_IN_USE
                        : EpisodeDownloadException.Reason.ALREADY_ACTIVE, "Download is in use.");
            } catch (IOException | RuntimeException exception) {
                throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                        "Episode download could not be deleted.");
            }
        } finally {
            release(mediaId);
        }
    }

    @Override
    public long getStorageUsageBytes() throws EpisodeDownloadException {
        try {
            return store.storageUsageBytes();
        } catch (IOException | RuntimeException exception) {
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Download storage usage could not be read.");
        }
    }

    private EpisodeDownloadResult downloadActive(
            String mediaId,
            BooleanSupplier cancellationRequested) throws EpisodeDownloadException {
        EpisodeDownloadTarget target = findTarget(mediaId);
        if (target == null) {
            throw failure(EpisodeDownloadException.Reason.EPISODE_NOT_FOUND,
                    "Episode is no longer in the catalog.");
        }

        reconcileBeforeDownload(target);
        checkCancelled(cancellationRequested);
        long maximumWriteBytes = remainingWriteBytes();
        long downloadedBytes;
        try {
            downloadedBytes = transfer(target, maximumWriteBytes, cancellationRequested);
        } catch (EpisodeDownloadException exception) {
            discardTransfer(mediaId);
            throw exception;
        } catch (RuntimeException exception) {
            discardTransfer(mediaId);
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Episode download failed safely.");
        }

        try {
            checkCancelled(cancellationRequested);
            if (!catalog.markEpisodeDownloadComplete(target, downloadedBytes)) {
                throw failure(EpisodeDownloadException.Reason.TARGET_CHANGED,
                        "Episode changed while it was downloading.");
            }
        } catch (EpisodeDownloadException exception) {
            discardTransfer(mediaId);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            discardTransfer(mediaId);
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Completed download state could not be saved.");
        }
        return new EpisodeDownloadResult(mediaId, downloadedBytes);
    }

    private void reconcileBeforeDownload(EpisodeDownloadTarget target)
            throws EpisodeDownloadException {
        final DownloadLookup lookup;
        try {
            lookup = store.lookup(target.getMediaId());
            if (target.isDownloaded() && lookup.getState() == DownloadLookup.State.COMPLETE) {
                throw failure(EpisodeDownloadException.Reason.ALREADY_DOWNLOADED,
                        "Episode is already downloaded.");
            }
            if (target.isDownloaded()) {
                catalog.markEpisodeDownloadAbsent(target.getMediaId());
            }
            if (lookup.getState() != DownloadLookup.State.ABSENT) {
                store.delete(target.getMediaId());
            }
        } catch (EpisodeDownloadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Download state could not be reconciled.");
        }
    }

    private long remainingWriteBytes() throws EpisodeDownloadException {
        try {
            long usage = store.storageUsageBytes();
            long quotaBytes = quotaBytesSupplier.getAsLong();
            if (quotaBytes < minimumQuotaBytes
                    || quotaBytes > SecurityLimits.MAX_DOWNLOAD_QUOTA_BYTES) {
                throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                        "Download quota setting was invalid.");
            }
            long remaining = quotaBytes - usage
                    - AppPrivateDownloadStore.COMPLETION_MARKER_BYTES;
            if (remaining <= 0L) {
                throw failure(EpisodeDownloadException.Reason.QUOTA_EXCEEDED,
                        "Download quota is full.");
            }
            return Math.min(remaining, SecurityLimits.MAX_DOWNLOAD_BYTES);
        } catch (EpisodeDownloadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Download storage usage could not be read.");
        }
    }

    private long transfer(
            EpisodeDownloadTarget target,
            long maximumWriteBytes,
            BooleanSupplier cancellationRequested) throws EpisodeDownloadException {
        CanonicalHttpsUrl current = target.getAudioUrl();
        Set<String> visited = new HashSet<>();
        visited.add(current.toString());
        long deadline = nanoClock.getAsLong() + maximumActiveNanos;

        for (int redirectCount = 0; ; redirectCount++) {
            checkActive(cancellationRequested, deadline);
            try (MediaDownloadTransport.Exchange exchange = transport.execute(current, target.getAudioUrl())) {
                int status = exchange.getStatusCode();
                if (isRedirect(status)) {
                    if (redirectCount >= SecurityLimits.MAX_MEDIA_REDIRECTS) {
                        throw failure(EpisodeDownloadException.Reason.TOO_MANY_REDIRECTS,
                                "Media exceeded the redirect limit.");
                    }
                    CanonicalHttpsUrl destination = redirectDestination(
                            current,
                            exchange.getHeader("Location"));
                    if (!visited.add(destination.toString())) {
                        throw failure(EpisodeDownloadException.Reason.REDIRECT_LOOP,
                                "Media redirect loop was rejected.");
                    }
                    current = destination;
                    continue;
                }
                if (status == 401 || status == 403) throw failure(status == 401
                        ? EpisodeDownloadException.Reason.LOGIN_REQUIRED : EpisodeDownloadException.Reason.ACCESS_DENIED,
                        "Check the podcast login or private feed link.");
                if (status != 200) {
                    throw failure(EpisodeDownloadException.Reason.HTTP_STATUS,
                            "Media server returned an unsupported status.");
                }
                validateResponsePolicy(target, current, exchange);
                // RSS length is advisory: hosts can transcode or insert ads after publishing it.
                // Validate the representation actually served, or bound a clean EOF when unknown.
                long expectedLength = contentLength(exchange.getHeader("Content-Length"));
                if (expectedLength != AppPrivateDownloadStore.UNKNOWN_LENGTH
                        && expectedLength > maximumWriteBytes) {
                    throw failure(EpisodeDownloadException.Reason.QUOTA_EXCEEDED,
                            "Episode exceeds the available download quota.");
                }
                InputStream body = exchange.getBody();
                if (body == null) {
                    throw failure(EpisodeDownloadException.Reason.UNSUPPORTED_RESPONSE,
                            "Media response had no body.");
                }
                return copyBody(
                        target.getMediaId(),
                        body,
                        expectedLength,
                        maximumWriteBytes,
                        cancellationRequested,
                        deadline);
            } catch (EpisodeDownloadException exception) {
                throw exception;
            } catch (FeedLoginException exception) {
                throw failure(EpisodeDownloadException.Reason.LOGIN_REQUIRED, "Update the podcast login.");
            } catch (IOException exception) {
                throw failure(EpisodeDownloadException.Reason.FETCH_FAILED,
                        "Media network request failed.");
            }
        }
    }

    private long copyBody(
            String mediaId,
            InputStream body,
            long expectedLength,
            long maximumWriteBytes,
            BooleanSupplier cancellationRequested,
            long deadline) throws EpisodeDownloadException {
        AppPrivateDownloadStore.DownloadWriter writer;
        try {
            writer = store.begin(mediaId, expectedLength, maximumWriteBytes);
        } catch (IOException | RuntimeException exception) {
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Download storage could not be opened.");
        }

        long written = 0L;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (writer) {
            while (true) {
                checkActive(cancellationRequested, deadline);
                final int count;
                try {
                    count = body.read(buffer);
                } catch (IOException exception) {
                    throw failure(EpisodeDownloadException.Reason.FETCH_FAILED,
                            "Media response body failed.");
                }
                if (count == -1) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                try {
                    writer.write(buffer, 0, count);
                    written = Math.addExact(written, count);
                } catch (IOException | ArithmeticException exception) {
                    throw failure(EpisodeDownloadException.Reason.QUOTA_EXCEEDED,
                            "Episode exceeded its permitted byte count.");
                }
            }
            checkActive(cancellationRequested, deadline);
            if (written <= 0L
                    || (expectedLength != AppPrivateDownloadStore.UNKNOWN_LENGTH
                            && written != expectedLength)) {
                throw failure(EpisodeDownloadException.Reason.INVALID_LENGTH,
                        "Media response length was inconsistent.");
            }
            try {
                writer.finish();
            } catch (IOException exception) {
                throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                        "Completed media could not be committed.");
            }
            return written;
        } catch (EpisodeDownloadException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Download storage could not be closed.");
        }
    }

    private static void validateResponsePolicy(
            EpisodeDownloadTarget target,
            CanonicalHttpsUrl finalUrl,
            MediaDownloadTransport.Exchange exchange) throws EpisodeDownloadException {
        String encoding = exchange.getHeader("Content-Encoding");
        if (encoding != null
                && !encoding.trim().isEmpty()
                && !"identity".equals(encoding.trim().toLowerCase(Locale.ROOT))) {
            throw failure(EpisodeDownloadException.Reason.UNSUPPORTED_RESPONSE,
                    "Encoded media responses are unsupported.");
        }
        if (!ProgressiveAudioPolicy.isEligible(finalUrl, target.getMimeType())) {
            throw failure(EpisodeDownloadException.Reason.UNSUPPORTED_RESPONSE,
                    "Redirected media type is unsupported.");
        }
        String responseType = exchange.getHeader("Content-Type");
        if (responseType != null && !responseType.trim().isEmpty()
                && !ProgressiveAudioPolicy.isEligible(finalUrl, responseType)) {
            throw failure(EpisodeDownloadException.Reason.UNSUPPORTED_RESPONSE,
                    "Media response type is unsupported.");
        }
    }

    private static long contentLength(String value) throws EpisodeDownloadException {
        if (value == null || value.isEmpty()) {
            return AppPrivateDownloadStore.UNKNOWN_LENGTH;
        }
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException("not decimal");
            }
            long length = Long.parseLong(value);
            if (length <= 0L || length > SecurityLimits.MAX_DOWNLOAD_BYTES) {
                throw new NumberFormatException("outside limits");
            }
            return length;
        } catch (NumberFormatException exception) {
            throw failure(EpisodeDownloadException.Reason.INVALID_LENGTH,
                    "Media Content-Length was invalid.");
        }
    }

    private static CanonicalHttpsUrl redirectDestination(
            CanonicalHttpsUrl source,
            String location) throws EpisodeDownloadException {
        if (location == null || location.isEmpty()
                || location.length() > SecurityLimits.MAX_URL_CHARS) {
            throw failure(EpisodeDownloadException.Reason.INVALID_REDIRECT,
                    "Media redirect location was invalid.");
        }
        try {
            URI resolved = source.toUri().resolve(new URI(location));
            return CanonicalHttpsUrl.parse(resolved.toString());
        } catch (URISyntaxException | IllegalArgumentException | NetworkPolicyException exception) {
            throw failure(EpisodeDownloadException.Reason.INVALID_REDIRECT,
                    "Media redirect location was invalid.");
        }
    }

    private EpisodeDownloadTarget findTarget(String mediaId)
            throws EpisodeDownloadException {
        try {
            return catalog.findEpisodeDownloadTarget(mediaId);
        } catch (IOException | RuntimeException exception) {
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Episode download target could not be read.");
        }
    }

    private void discardTransfer(String mediaId) throws EpisodeDownloadException {
        try {
            store.delete(mediaId);
        } catch (IOException | RuntimeException exception) {
            throw failure(EpisodeDownloadException.Reason.STORAGE_FAILED,
                    "Failed download could not be discarded.");
        }
    }

    private void checkActive(BooleanSupplier cancellationRequested, long deadline)
            throws EpisodeDownloadException {
        checkCancelled(cancellationRequested);
        if (nanoClock.getAsLong() - deadline >= 0L) {
            throw failure(EpisodeDownloadException.Reason.FETCH_FAILED,
                    "Episode download exceeded its active-time limit.");
        }
    }

    private void checkCancelled(BooleanSupplier cancellationRequested)
            throws EpisodeDownloadException {
        if (offlineMode.getAsBoolean()) {
            throw failure(EpisodeDownloadException.Reason.OFFLINE_MODE,
                    "Audio downloads are disabled in offline mode.");
        }
        if (cancellationRequested.getAsBoolean()) {
            throw failure(EpisodeDownloadException.Reason.CANCELLED,
                    "Episode download was cancelled.");
        }
    }

    private synchronized void acquire(String mediaId) throws EpisodeDownloadException {
        if (activeMediaIds.contains(mediaId)
                || activeMediaIds.size() >= SecurityLimits.MAX_CONCURRENT_MEDIA_CALLS) {
            throw failure(EpisodeDownloadException.Reason.ALREADY_ACTIVE,
                    "Media transfer capacity is already in use.");
        }
        activeMediaIds.add(mediaId);
    }

    private synchronized void release(String mediaId) {
        activeMediaIds.remove(mediaId);
    }

    private static void validateMediaId(String mediaId) throws EpisodeDownloadException {
        if (mediaId == null
                || mediaId.isEmpty()
                || mediaId.length() > SecurityLimits.MAX_IDENTIFIER_CHARS) {
            throw failure(EpisodeDownloadException.Reason.INVALID_SELECTION,
                    "Episode selection is invalid.");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    private static EpisodeDownloadException failure(
            EpisodeDownloadException.Reason reason,
            String message) {
        return new EpisodeDownloadException(reason, message);
    }

    private static LongSupplier fixedQuotaSupplier(long quotaBytes) {
        if (quotaBytes <= AppPrivateDownloadStore.COMPLETION_MARKER_BYTES
                || quotaBytes > SecurityLimits.MAX_DOWNLOAD_QUOTA_BYTES) {
            throw new IllegalArgumentException("Invalid download quota.");
        }
        return () -> quotaBytes;
    }
}
