package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.artwork.ArtworkThumbnailLoader;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Podcast library rows and optional artwork; selecting a row only navigates. */
public final class PodcastLibraryView {
    public interface Menu { void show(CatalogSubscriptionSummary podcast, View anchor); }
    private record Art(ImageView image, CatalogSubscriptionSummary podcast, View row) { }
    private final Context context;
    private final LinearLayout rows;
    private final ArtworkThumbnailLoader artwork;
    private final Runnable artworkReady;
    private final Consumer<CatalogSubscriptionSummary> open;
    private final Menu menu;
    private final List<Art> images = new ArrayList<>();

    public PodcastLibraryView(Context context, LinearLayout rows, ArtworkThumbnailLoader artwork,
            Runnable artworkReady, Consumer<CatalogSubscriptionSummary> open, Menu menu) {
        this.context=context; this.rows=rows; this.artwork=artwork;
        this.artworkReady=artworkReady; this.open=open; this.menu=menu;
    }
    public void render(List<CatalogSubscriptionSummary> podcasts, boolean archives) { render(podcasts,archives,false); }
    public void render(List<CatalogSubscriptionSummary> podcasts, boolean archives, boolean searching) {
        rows.removeAllViews(); images.clear();
        for (CatalogSubscriptionSummary podcast : podcasts) {
            if (podcast.isSubscribed() == archives) continue;
            LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0,UiWidgets.dp(context,12),0,UiWidgets.dp(context,12));
            LinearLayout body = new LinearLayout(context); body.setGravity(Gravity.CENTER_VERTICAL);
            body.setMinimumHeight(UiWidgets.dp(context,76)); body.setFocusable(true);
            body.setBackgroundResource(android.R.drawable.list_selector_background);
            body.setOnClickListener(v->open.accept(podcast));
            body.setOnLongClickListener(v->{menu.show(podcast,v);return true;});
            ImageView image=UiWidgets.artwork(context,72); body.addView(image); images.add(new Art(image,podcast,row));
            LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(UiWidgets.dp(context,16),0,UiWidgets.dp(context,8),0);
            String title=podcast.getTitle().isEmpty()?context.getString(R.string.catalog_episode_untitled):podcast.getTitle();
            android.widget.TextView titleView = UiWidgets.text(context,title,18,true);
            titleView.setMaxLines(3); titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(titleView);
            String unplayed = context.getResources().getQuantityString(R.plurals.ui_unplayed_count,
                    podcast.getUnplayedCount(), podcast.getUnplayedCount());
            String newText = !archives && podcast.getNewEpisodeCount() > 0
                    ? context.getResources().getQuantityString(R.plurals.ui_new_count,
                            podcast.getNewEpisodeCount(), podcast.getNewEpisodeCount()) : "";
            var counts = UiWidgets.text(context, newText.isEmpty() ? unplayed : newText + " · " + unplayed, 15, false);
            if (!newText.isEmpty()) {
                var styled = new android.text.SpannableString(counts.getText());
                styled.setSpan(new android.text.style.ForegroundColorSpan(context.getColor(R.color.accent)),
                        0, newText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        0, newText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                counts.setText(styled);
            }
            labels.addView(counts);
            body.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
            Button options=UiWidgets.iconButton(context,R.drawable.ic_more,
                    context.getString(R.string.ui_row_options,title),false);
            options.setOnClickListener(v->menu.show(podcast,v));row.addView(options);rows.addView(row);
            divider();
        }
        if(images.isEmpty()) rows.addView(UiWidgets.text(context,context.getString(archives
                ?R.string.ui_empty_archives:searching?R.string.ui_no_matching_podcasts:R.string.ui_empty_podcasts),16,false));
        refreshArtwork();
    }
    private void divider() {
        View line=new View(context);line.setBackgroundColor(context.getColor(R.color.divider));
        rows.addView(line,new LinearLayout.LayoutParams(-1,UiWidgets.dp(context,1)));
    }
    public void refreshArtwork() {
        android.graphics.Rect visible=new android.graphics.Rect();
        if(rows.getWindowVisibility()!=View.VISIBLE||!rows.getLocalVisibleRect(visible))return;
        for(Art art:images)if(art.row().getHeight()>0&&art.row().getBottom()>visible.top&&art.row().getTop()<visible.bottom)
            UiWidgets.setArtwork(art.image(),artwork.getOrRequest(art.podcast().getArtworkUrl(),artworkReady));
    }
}
