package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.widget.Button;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.R;
import java.util.function.Consumer;

/** Inert row navigation bindings; playback and download controls use separate views. */
public final class EpisodeRowViews {
    private final Context context;

    public EpisodeRowViews(Context context) { this.context = context; }

    public TextView title(String text, String description, boolean enabled,
            String mediaId, Consumer<String> openEpisode) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        title.setTextColor(context.getColor(R.color.text_primary));
        title.setMinHeight(dp(48));
        title.setFocusable(true);
        title.setClickable(true);
        title.setEnabled(enabled);
        title.setContentDescription(description);
        title.setOnClickListener(ignored -> openEpisode.accept(mediaId));
        return title;
    }

    public TextView caption(String text) {
        TextView details = new TextView(context);
        details.setText(text);
        details.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        details.setTextColor(context.getColor(R.color.text_secondary));
        return details;
    }

    public Button detailsButton(String description, boolean enabled,
            String mediaId, Consumer<String> openEpisode) {
        Button details = new Button(context);
        details.setMinHeight(dp(48));
        details.setText(R.string.episode_details_open_action);
        details.setContentDescription(description);
        details.setEnabled(enabled);
        details.setOnClickListener(ignored -> openEpisode.accept(mediaId));
        return details;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
