package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.catalog.EpisodeCatalogMapper;
import io.github.jnesew.plainpoddroid.core.feed.FeedParseException;
import io.github.jnesew.plainpoddroid.core.network.BoundedFeedAcquirer;
import io.github.jnesew.plainpoddroid.core.network.FeedAcquisition;
import io.github.jnesew.plainpoddroid.core.network.FeedAcquisitionException;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;



public final class SubscriptionCoordinator implements SubscriptionRefresher {
    private static final int CAPABILITY_BYTES = 32;

    private final BoundedFeedAcquirer acquirer;
    private final ApplePodcastResolver appleLinks;
    private final EpisodeCatalogMapper mapper;
    private final SubscriptionStore store;
    private final SecureRandom random;
    private final LongSupplier nanoClock;
    private final int pendingLimit;
    private final long previewTtlNanos;
    private final Runnable onRefreshCompleted;
    private final Object owner = new Object();
    private final Map<String, PendingPreview> pending = new LinkedHashMap<>();
    private final Set<Long> refreshesInFlight = new HashSet<>();

    public SubscriptionCoordinator(FeedTransport transport, SubscriptionStore store) {
        this(transport, store, () -> {});
    }

    public SubscriptionCoordinator(
            FeedTransport transport, SubscriptionStore store, Runnable onRefreshCompleted) {
        this(transport, store, onRefreshCompleted, null);
    }

    public SubscriptionCoordinator(FeedTransport transport, SubscriptionStore store,
            Runnable onRefreshCompleted, ApplePodcastResolver appleLinks) {
        this(
                new BoundedFeedAcquirer(transport),
                new EpisodeCatalogMapper(),
                store,
                new SecureRandom(),
                System::nanoTime,
                SecurityLimits.MAX_PENDING_SUBSCRIPTION_PREVIEWS,
                SecurityLimits.SUBSCRIPTION_PREVIEW_TTL_MILLIS * 1_000_000L,
                onRefreshCompleted,
                appleLinks);
    }

    SubscriptionCoordinator(
            BoundedFeedAcquirer acquirer,
            EpisodeCatalogMapper mapper,
            SubscriptionStore store,
            SecureRandom random,
            LongSupplier nanoClock,
            int pendingLimit,
            long previewTtlNanos) {
        this(acquirer, mapper, store, random, nanoClock, pendingLimit,
                previewTtlNanos, () -> {});
    }

    SubscriptionCoordinator(
            BoundedFeedAcquirer acquirer,
            EpisodeCatalogMapper mapper,
            SubscriptionStore store,
            SecureRandom random,
            LongSupplier nanoClock,
            int pendingLimit,
            long previewTtlNanos,
            Runnable onRefreshCompleted) {
        this(acquirer, mapper, store, random, nanoClock, pendingLimit, previewTtlNanos,
                onRefreshCompleted, null);
    }

    private SubscriptionCoordinator(BoundedFeedAcquirer acquirer, EpisodeCatalogMapper mapper,
            SubscriptionStore store, SecureRandom random, LongSupplier nanoClock,
            int pendingLimit, long previewTtlNanos, Runnable onRefreshCompleted,
            ApplePodcastResolver appleLinks) {
        this.acquirer = Objects.requireNonNull(acquirer, "acquirer");
        this.appleLinks = appleLinks;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.store = Objects.requireNonNull(store, "store");
        this.random = Objects.requireNonNull(random, "random");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.onRefreshCompleted = Objects.requireNonNull(
                onRefreshCompleted, "onRefreshCompleted");
        if (pendingLimit <= 0 || previewTtlNanos <= 0L) {
            throw new IllegalArgumentException("Invalid preview policy.");
        }
        this.pendingLimit = pendingLimit;
        this.previewTtlNanos = previewTtlNanos;
    }

    public SubscriptionPreview preview(String input)
            throws IOException, FeedParseException {
        return preview(input, null);
    }

    public SubscriptionPreview preview(String input, BasicFeedLogin login)
            throws IOException, FeedParseException {
        return preview(input, login, () -> true);
    }

    SubscriptionPreview preview(String input, BasicFeedLogin login,
            java.util.function.BooleanSupplier active) throws IOException, FeedParseException {
        var source = io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl.parse(input);
        ApplePodcastLink appleLink = ApplePodcastLink.from(source);
        if (appleLink != null) {
            if (login != null) {
                login.revoke();
                throw new ApplePodcastLookupException(ApplePodcastLookupException.Reason.LOGIN_UNSUPPORTED);
            }
            if (appleLinks == null) throw new ApplePodcastLookupException(
                    ApplePodcastLookupException.Reason.UNAVAILABLE);
            input = appleLinks.resolve(appleLink).toString();
        }
        if (!active.getAsBoolean()) throw new IOException("Preview cancelled.");
        FeedAcquisition acquisition = acquirer.acquire(input, io.github.jnesew.plainpoddroid.core.network.FeedValidators.none(), login);
        CatalogSnapshot snapshot = mapper.map(acquisition);
        if (!active.getAsBoolean()) throw new IOException("Preview cancelled.");
        synchronized (this) {
            long now = nanoClock.getAsLong();
            pruneExpired(now);
            if (pending.size() >= pendingLimit) {
                throw failure(
                        SubscriptionWorkflowException.Reason.PENDING_PREVIEW_LIMIT,
                        "Too many subscription previews are awaiting a decision.");
            }
            String capability = newCapability();
            SubscriptionPreview preview = new SubscriptionPreview(
                    owner,
                    capability,
                    snapshot,
                    acquisition.getValidators(),
                    acquisition.getRedirectOriginsForDisplay(), appleLink);
            pending.put(capability, new PendingPreview(preview, deadline(now), login));
            return preview;
        }
    }

    public long confirm(SubscriptionPreview preview) throws IOException {
        Objects.requireNonNull(preview, "preview");
        final CatalogSnapshot snapshot;
        final BasicFeedLogin login;
        synchronized (this) {
            long now = nanoClock.getAsLong();
            pruneExpired(now);
            PendingPreview pendingPreview = preview.owner == owner
                    ? pending.remove(preview.capability)
                    : null;
            if (pendingPreview == null || pendingPreview.preview != preview) {
                throw failure(
                        SubscriptionWorkflowException.Reason.INVALID_OR_EXPIRED_CONFIRMATION,
                        "Subscription confirmation is invalid or expired.");
            }
            snapshot = preview.snapshot;
            login = pendingPreview.login;
        }
        if (login != null && !login.isActive()) throw new FeedLoginException();
        return store.addConfirmedSubscription(snapshot, preview.validators, login);
    }

    public synchronized void discard(SubscriptionPreview preview) {
        if (preview == null || preview.owner != owner) {
            return;
        }
        PendingPreview pendingPreview = pending.get(preview.capability);
        if (pendingPreview != null && pendingPreview.preview == preview) {
            pending.remove(preview.capability);
            if (pendingPreview.login != null) pendingPreview.login.revoke();
        }
    }

    @Override
    public void refresh(long subscriptionId) throws SubscriptionRefreshException {
        refreshUsing(subscriptionId, acquirer, onRefreshCompleted);
    }

    /** A system-bound job can use its network while retaining the shared refresh ownership. */
    public SubscriptionRefresher refresherFor(FeedTransport transport, Runnable completed) {
        BoundedFeedAcquirer scoped = new BoundedFeedAcquirer(Objects.requireNonNull(transport));
        Objects.requireNonNull(completed);
        return id -> refreshUsing(id, scoped, completed);
    }

    private void refreshUsing(long subscriptionId, BoundedFeedAcquirer source, Runnable completed)
            throws SubscriptionRefreshException {
        if (subscriptionId < 1L) {
            throw refreshFailure(
                    SubscriptionRefreshException.Reason.SUBSCRIPTION_NOT_FOUND,
                    "Subscription does not exist.");
        }
        synchronized (this) {
            if (!refreshesInFlight.add(subscriptionId)) {
                throw refreshFailure(
                        SubscriptionRefreshException.Reason.ALREADY_RUNNING,
                        "Subscription refresh is already running.");
            }
        }
        try {
            refreshExisting(subscriptionId, source, completed);
        } finally {
            synchronized (this) {
                refreshesInFlight.remove(subscriptionId);
            }
        }
    }

    private void refreshExisting(long subscriptionId, BoundedFeedAcquirer source, Runnable completed)
            throws SubscriptionRefreshException {
        final SubscriptionRefreshTarget target;
        try {
            target = store.findSubscriptionRefreshTarget(subscriptionId);
        } catch (IOException exception) {
            throw refreshFailure(
                    SubscriptionRefreshException.Reason.STORAGE_FAILED,
                    "Subscription lookup failed.");
        }
        if (target == null) {
            throw refreshFailure(
                    SubscriptionRefreshException.Reason.SUBSCRIPTION_NOT_FOUND,
                    "Subscription does not exist.");
        }

        final FeedAcquisition acquisition;
        try {
            acquisition = source.acquire(
                    target.getSubscriptionUrl().toString(),
                    target.getValidators());
        } catch (FeedParseException exception) {
            throw refreshFailure(
                    SubscriptionRefreshException.Reason.INVALID_FEED,
                    "Subscription feed is invalid.");
        } catch (FeedAcquisitionException exception) {
            throw refreshFailure(
                    exception.getReason() == FeedAcquisitionException.Reason.LOGIN_REQUIRED ? SubscriptionRefreshException.Reason.LOGIN_REQUIRED
                            : exception.getReason() == FeedAcquisitionException.Reason.ACCESS_DENIED ? SubscriptionRefreshException.Reason.ACCESS_DENIED
                            : SubscriptionRefreshException.Reason.FETCH_FAILED,
                    "Check the podcast login or private feed link.");
        } catch (FeedLoginException exception) {
            throw refreshFailure(SubscriptionRefreshException.Reason.LOGIN_REQUIRED, "Update the podcast login.");
        } catch (IOException exception) {
            throw refreshFailure(
                    SubscriptionRefreshException.Reason.FETCH_FAILED,
                    "Subscription feed could not be acquired.");
        }

        try {
            if (acquisition.isNotModified()) {
                store.recordUnmodifiedSubscription(target, acquisition.getValidators());
                return;
            }
            CatalogSnapshot snapshot = mapper.map(acquisition);
            if (!snapshot.getSubscriptionUrl().equals(target.getSubscriptionUrl())) {
                throw new AssertionError("Feed acquisition changed the stored subscription URL.");
            }
            store.refreshExistingSubscription(target, snapshot, acquisition.getValidators());
            completed.run();
        } catch (IOException exception) {
            throw refreshFailure(
                    SubscriptionRefreshException.Reason.STORAGE_FAILED,
                    "Subscription refresh could not be saved.");
        }
    }

    private synchronized String newCapability() {
        byte[] bytes = new byte[CAPABILITY_BYTES];
        String value;
        do {
            random.nextBytes(bytes);
            value = hexadecimal(bytes);
        } while (pending.containsKey(value));
        return value;
    }

    private long deadline(long now) {
        // nanoTime comparisons intentionally use signed subtraction so wraparound is safe.
        return now + previewTtlNanos;
    }

    private void pruneExpired(long now) {
        Iterator<PendingPreview> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            PendingPreview expired = iterator.next();
            if (now - expired.expiresAtNanos >= 0L) {
                if (expired.login != null) expired.login.revoke();
                iterator.remove();
            }
        }
    }

    private static String hexadecimal(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static SubscriptionWorkflowException failure(
            SubscriptionWorkflowException.Reason reason,
            String message) {
        return new SubscriptionWorkflowException(reason, message);
    }

    private static SubscriptionRefreshException refreshFailure(
            SubscriptionRefreshException.Reason reason,
            String message) {
        return new SubscriptionRefreshException(reason, message);
    }

    private static final class PendingPreview {
        private final SubscriptionPreview preview;
        private final long expiresAtNanos;
        private final BasicFeedLogin login;

        private PendingPreview(SubscriptionPreview preview, long expiresAtNanos,
                BasicFeedLogin login) {
            this.preview = preview;
            this.login = login;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
