package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SubscriptionPreview {
    final Object owner;
    final String capability;
    final CatalogSnapshot snapshot;
    final FeedValidators validators;
    private final List<String> redirectOriginsForDisplay;
    private final ApplePodcastLink appleLink;

    SubscriptionPreview(
            Object owner,
            String capability,
            CatalogSnapshot snapshot,
            FeedValidators validators,
            List<String> redirectOriginsForDisplay) {
        this(owner, capability, snapshot, validators, redirectOriginsForDisplay, null);
    }

    SubscriptionPreview(Object owner, String capability, CatalogSnapshot snapshot,
            FeedValidators validators, List<String> redirectOriginsForDisplay,
            ApplePodcastLink appleLink) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.validators = Objects.requireNonNull(validators, "validators");
        this.appleLink = appleLink;
        this.redirectOriginsForDisplay = Collections.unmodifiableList(
                new ArrayList<>(redirectOriginsForDisplay));
    }

    public String getOriginalOriginForDisplay() {
        return snapshot.getSubscriptionUrl().getOriginForDisplay();
    }

    public boolean isFromAppleLink() { return appleLink != null; }
    public boolean isFromAppleEpisodeLink() { return appleLink != null && appleLink.isEpisode(); }
    public CanonicalHttpsUrl getFeedUrl() { return snapshot.getSubscriptionUrl(); }

    public String getFinalOriginForDisplay() {
        return snapshot.getFinalFeedUrl().getOriginForDisplay();
    }

    public List<String> getRedirectOriginsForDisplay() {
        return redirectOriginsForDisplay;
    }

    public String getTitle() {
        return snapshot.getTitle();
    }

    public String getAuthor() {
        return snapshot.getAuthor();
    }

    public String getNotes() {
        return snapshot.getNotes();
    }

    public CanonicalHttpsUrl getArtworkUrl() {
        return snapshot.getArtworkUrl();
    }

    public int getAcceptedEpisodeCount() {
        return snapshot.getEpisodes().size();
    }

    public int getRejectedEpisodeCount() {
        return snapshot.getRejectedEpisodeCount();
    }

    public int getDuplicateEpisodeCount() {
        return snapshot.getDuplicateEpisodeCount();
    }
}
