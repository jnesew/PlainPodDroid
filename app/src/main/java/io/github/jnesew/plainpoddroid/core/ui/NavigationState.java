package io.github.jnesew.plainpoddroid.core.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Navigation only. It has no playback, network or controller lifetime dependency. */
public final class NavigationState {
    public enum Destination {
        PODCASTS, PODCAST_EPISODES, DOWNLOADS, QUEUE, FAVORITES, HISTORY,
        ARCHIVES, SETTINGS, ADD_PODCAST, EPISODE, NEW_EPISODES, IN_PROGRESS
    }
    public record Route(Destination destination, long podcastId, String episodeId) {
        public Route {
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(episodeId, "episodeId");
            if ((destination == Destination.PODCAST_EPISODES ? podcastId < 1 : podcastId != 0)
                    || (destination == Destination.EPISODE
                        ? !episodeId.matches("episode-[0-9a-f]{64}") : !episodeId.isEmpty())) {
                throw new IllegalArgumentException("Invalid navigation route.");
            }
        }
        public static Route root(Destination destination) { return new Route(destination, 0, ""); }
    }
    public static final int MAX_DEPTH = 16;
    private final List<Route> stack = new ArrayList<>();
    private boolean drawerOpen;

    public NavigationState() { stack.add(Route.root(Destination.PODCASTS)); }
    public Route current() { return stack.get(stack.size() - 1); }
    public boolean isDrawerOpen() { return drawerOpen; }
    public void openDrawer() { drawerOpen = true; }
    public void closeDrawer() { drawerOpen = false; }
    public void select(Destination destination) {
        Route route = Route.root(destination);
        drawerOpen = false;
        stack.clear(); stack.add(route);
    }
    public void openPodcast(long id) { push(new Route(Destination.PODCAST_EPISODES, id, "")); }
    public void openEpisode(String id) { push(new Route(Destination.EPISODE, 0, id)); }
    public void openAddPodcast() { push(Route.root(Destination.ADD_PODCAST)); }
    private void push(Route route) {
        drawerOpen = false;
        if (current().equals(route)) return;
        // Reopening a route returns to it rather than creating a navigation cycle.
        int prior = stack.indexOf(route);
        if (prior >= 0) { stack.subList(prior + 1, stack.size()).clear(); return; }
        if (stack.size() == MAX_DEPTH) stack.remove(1);
        stack.add(route);
    }
    public boolean back() {
        if (drawerOpen) { drawerOpen = false; return true; }
        if (stack.size() == 1) return false;
        stack.remove(stack.size() - 1); return true;
    }
    public boolean hasParent() { return stack.size() > 1; }
    public List<Route> snapshot() { return Collections.unmodifiableList(new ArrayList<>(stack)); }
    public void restore(List<Route> routes) {
        if (routes == null || routes.isEmpty() || routes.size() > MAX_DEPTH
                || routes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid navigation snapshot.");
        }
        stack.clear(); stack.addAll(routes); drawerOpen = false;
    }
}
