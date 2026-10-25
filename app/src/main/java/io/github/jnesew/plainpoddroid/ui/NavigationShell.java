package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.ui.NavigationState;
import io.github.jnesew.plainpoddroid.core.ui.NavigationState.Destination;
import java.util.ArrayList;
import java.util.EnumMap;

/** Drawer, destination visibility and navigation persistence; never closes shared work. */
public final class NavigationShell {
    private final Context context;
    private final View root;
    private final NavigationState state = new NavigationState();
    private final EnumMap<Destination, ScrollView> screens = new EnumMap<>(Destination.class);
    private final EnumMap<Destination, Button> drawerItems = new EnumMap<>(Destination.class);
    private final Runnable changed;
    private final View drawer, scrim, content;
    private boolean drawerWasOpen;
    private int podcastCount = -1;
    private final Button navigation;

    public NavigationShell(Context context, View root, Bundle saved, Runnable changed, Runnable about) {
        this.context = context; this.root = root; this.changed = changed;
        drawer = root.findViewById(R.id.navigation_drawer);
        scrim = root.findViewById(R.id.drawer_scrim);
        content = root.findViewById(R.id.app_content);
        navigation = root.findViewById(R.id.navigation_action);
        Destination[] destinations = Destination.values();
        int[] ids = {R.id.screen_podcasts, R.id.screen_podcast_episodes, R.id.screen_downloads,
                R.id.screen_queue, R.id.screen_favorites, R.id.screen_history, R.id.screen_archives,
                R.id.screen_settings, R.id.screen_add_podcast, R.id.screen_episode,
                R.id.screen_new_episodes, R.id.screen_in_progress};
        for (int i = 0; i < destinations.length; i++) screens.put(destinations[i], root.findViewById(ids[i]));
        bind(R.id.nav_podcasts, Destination.PODCASTS); bind(R.id.nav_downloads, Destination.DOWNLOADS);
        bind(R.id.nav_queue, Destination.QUEUE); bind(R.id.nav_favorites, Destination.FAVORITES);
        bind(R.id.nav_history, Destination.HISTORY); bind(R.id.nav_archives, Destination.ARCHIVES);
        bind(R.id.nav_new_episodes, Destination.NEW_EPISODES); bind(R.id.nav_in_progress, Destination.IN_PROGRESS);
        bind(R.id.nav_settings, Destination.SETTINGS); bind(R.id.nav_add_podcast, Destination.ADD_PODCAST);
        root.findViewById(R.id.nav_about).setOnClickListener(v -> {
            state.closeDrawer(); render(); about.run();
        });
        scrim.setOnClickListener(v -> { state.closeDrawer(); render(); });
        navigation.setOnClickListener(v -> {
            if (state.hasParent()) back(); else { state.openDrawer(); render(); }
        });
        restore(saved); render();
        drawer.getLayoutParams().width = Math.min(UiWidgets.dp(context, 320),
                Math.max(UiWidgets.dp(context, 240), context.getResources().getDisplayMetrics().widthPixels - UiWidgets.dp(context, 48)));
    }
    private void bind(int id, Destination destination) {
        Button item = root.findViewById(id); drawerItems.put(destination, item);
        item.setOnClickListener(v -> {
            if (destination == Destination.ADD_PODCAST) state.openAddPodcast();
            else state.select(destination);
            render(); changed.run();
        });
    }
    public NavigationState.Route current() { return state.current(); }
    public void openPodcast(long id) { state.openPodcast(id); navigate(); }
    public void openEpisode(String id) { state.openEpisode(id); navigate(); }
    public void addPodcast() { state.openAddPodcast(); navigate(); }
    public void select(Destination d) { state.select(d); navigate(); }
    private void navigate() { render(); changed.run(); }
    public boolean back() {
        boolean wasDrawer = state.isDrawerOpen();
        if (!state.back()) return false;
        render(); if (!wasDrawer) changed.run(); return true;
    }
    private void render() {
        Destination selected = state.current().destination();
        for (var entry : screens.entrySet()) entry.getValue().setVisibility(entry.getKey() == selected ? View.VISIBLE : View.GONE);
        boolean episode = selected == Destination.EPISODE;
        root.findViewById(R.id.episode_favorite_action).setVisibility(episode ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.episode_queue_header).setVisibility(episode ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.search_action).setVisibility(selected == Destination.PODCASTS ? View.VISIBLE : View.GONE);
        renderTitle(selected);
        UiWidgets.icon(navigation, state.hasParent() ? R.drawable.ic_back : R.drawable.ic_menu,
                context.getString(state.hasParent() ? R.string.ui_back : R.string.ui_navigation), false);
        boolean open = state.isDrawerOpen();
        drawer.setVisibility(open ? View.VISIBLE : View.GONE); scrim.setVisibility(open ? View.VISIBLE : View.GONE);
        content.setImportantForAccessibility(open ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        for (var entry : drawerItems.entrySet()) {
            boolean active = entry.getKey() == selected;
            entry.getValue().setSelected(active);
            entry.getValue().setBackgroundResource(active ? R.drawable.button_surface : R.drawable.icon_surface);
            entry.getValue().setTextColor(context.getColor(active ? R.color.accent : R.color.text_primary));
        }
        ((android.view.ViewGroup) content).setDescendantFocusability(open
                ? android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS : android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (open && !drawerWasOpen) drawerItems.get(Destination.PODCASTS).requestFocus();
        if (!open && drawerWasOpen) navigation.requestFocus();
        drawerWasOpen = open;
    }
    public void setPodcastCount(int count) {
        podcastCount = Math.max(0, count);
        renderTitle(state.current().destination());
    }
    private void renderTitle(Destination selected) {
        TextView view = root.findViewById(R.id.destination_title);
        String title = context.getString(title(selected));
        if (selected == Destination.PODCASTS && podcastCount >= 0) {
            android.text.SpannableString text = new android.text.SpannableString(
                    context.getString(R.string.ui_title_count, title, podcastCount));
            text.setSpan(new android.text.style.RelativeSizeSpan(.7f), title.length(), text.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.ForegroundColorSpan(context.getColor(R.color.text_secondary)),
                    title.length(), text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(text);
            view.setContentDescription(title + ", " + context.getResources().getQuantityString(
                    R.plurals.ui_subscription_count, podcastCount, podcastCount));
        } else { view.setText(title); view.setContentDescription(null); }
    }
    private static int title(Destination d) {
        return switch (d) {
            case PODCASTS -> R.string.ui_podcasts; case PODCAST_EPISODES -> R.string.ui_episodes;
            case DOWNLOADS -> R.string.ui_downloads; case QUEUE -> R.string.ui_queue;
            case FAVORITES -> R.string.ui_favorites; case HISTORY -> R.string.ui_history;
            case ARCHIVES -> R.string.ui_archives; case SETTINGS -> R.string.ui_settings;
            case ADD_PODCAST -> R.string.ui_add_podcast; case EPISODE -> R.string.ui_episode;
            case NEW_EPISODES -> R.string.ui_new_episodes; case IN_PROGRESS -> R.string.ui_in_progress;
        };
    }
    public void save(Bundle out) {
        ArrayList<String> routes = new ArrayList<>();
        for (var route : state.snapshot()) routes.add(route.destination().name()+":"+route.podcastId()+":"+route.episodeId());
        out.putStringArrayList("navigation.routes", routes);
        for (var e : screens.entrySet()) out.putInt("navigation.scroll."+e.getKey().name(), e.getValue().getScrollY());
    }
    private void restore(Bundle saved) {
        if (saved == null) return;
        try {
            ArrayList<String> values = saved.getStringArrayList("navigation.routes");
            if (values != null && values.size() <= NavigationState.MAX_DEPTH) {
                ArrayList<NavigationState.Route> routes = new ArrayList<>();
                for (String value : values) {
                    String[] parts = value.split(":", -1);
                    if (parts.length != 3) throw new IllegalArgumentException();
                    routes.add(new NavigationState.Route(Destination.valueOf(parts[0]), Long.parseLong(parts[1]), parts[2]));
                }
                state.restore(routes);
            }
            for (var e : screens.entrySet()) {
                int y = Math.max(0, saved.getInt("navigation.scroll."+e.getKey().name(), 0));
                e.getValue().post(() -> e.getValue().scrollTo(0, y));
            }
        } catch (IllegalArgumentException | NullPointerException ignored) { state.select(Destination.PODCASTS); }
    }
}
