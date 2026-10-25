package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.R;

/** Small shared styling primitives for framework Views. */
public final class UiWidgets {
    private UiWidgets() { }
    private static final Object NO_ARTWORK=new Object();
    private record IconStyle(int drawable,boolean primary,boolean selected,int size) { }
    public static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
    public static void icon(Button button, int drawable, String description, boolean primary) {
        renderIcon(button, drawable, description, primary, false);
    }
    public static void toggleIcon(Button button, int drawable, String description, boolean selected) {
        button.setSelected(selected);
        renderIcon(button, drawable, description, false, selected);
    }
    private static Drawable enabledGlyph(Context context, int resource, int color) {
        // A cached VectorDrawable can report non-stateful after applying a tint
        // list. An explicit selector keeps disabled state propagation reliable.
        Drawable enabled = context.getDrawable(resource).mutate();
        enabled.setTint(color);
        Drawable disabled = context.getDrawable(resource).mutate();
        disabled.setTint((color & 0x00ffffff) | 0x73000000);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {android.R.attr.state_enabled}, enabled);
        states.addState(new int[] {}, disabled);
        return states;
    }
    public static void episodeFeedback(View progress, TextView status, boolean hasDetails, boolean busy, boolean error) {
        progress.setVisibility(!hasDetails && busy ? View.VISIBLE : View.GONE);
        status.setVisibility(!hasDetails && (busy || error) ? View.VISIBLE : View.GONE);
    }
    private static void renderIcon(Button button, int drawable, String description, boolean primary, boolean selected) {
        Context context = button.getContext();
        button.setContentDescription(description);
        int size = primary && button.getLayoutParams() != null && button.getLayoutParams().width >= dp(context, 64) ? 32 : 24;
        IconStyle style=new IconStyle(drawable,primary,selected,size);
        if(style.equals(button.getTag(R.id.ui_icon_style)))return;
        button.setTag(R.id.ui_icon_style,style);
        button.setText("");
        button.setCompoundDrawablesRelative(null, null, null, null);
        Drawable image = enabledGlyph(context, drawable, context.getColor(primary ? R.color.on_accent
                : selected ? R.color.accent : R.color.text_primary));
        LayerDrawable layers = new LayerDrawable(new Drawable[] {context.getDrawable(primary
                ? R.drawable.play_surface : selected ? R.drawable.button_surface : R.drawable.icon_surface), image});
        layers.setLayerGravity(1, Gravity.CENTER); layers.setLayerSize(1, dp(context, size), dp(context, size));
        button.setBackgroundTintList(null); button.setBackground(layers); button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setAlpha(1f);
    }
    public static Button iconButton(Context context, int drawable, String description, boolean primary) {
        Button button = new Button(context, null, 0, R.style.Widget_PlainPod_Icon);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        icon(button, drawable, description, primary);
        return button;
    }
    public static void skip(Button button, int seconds, boolean back, String description) {
        button.setTag(R.id.ui_icon_style,null);
        Context c = button.getContext();
        Drawable ring = enabledGlyph(c, back ? R.drawable.ic_replay : R.drawable.ic_forward,
                c.getColor(R.color.text_primary));
        LayerDrawable layers = new LayerDrawable(new Drawable[] {c.getDrawable(R.drawable.icon_surface), ring});
        layers.setLayerGravity(1, Gravity.CENTER);
        layers.setLayerSize(1, dp(c, 56), dp(c, 56));
        button.setBackground(layers);button.setText(String.format(java.util.Locale.getDefault(), "%d", seconds));button.setTextSize(18);
        button.setPadding(0, 0, 0, 0);button.setContentDescription(description);
    }
    public static TextView text(Context c, String value, int size, boolean bold) {
        TextView text = new TextView(c);
        text.setText(value); text.setTextSize(size);
        text.setTextColor(c.getColor(bold ? R.color.text_primary : R.color.text_secondary));
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return text;
    }
    public static ImageView artwork(Context c, int size) {
        ImageView image = new ImageView(c);
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(c, size), dp(c, size)));
        image.setBackgroundResource(R.drawable.artwork_surface);
        image.setClipToOutline(true);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setArtwork(image, null); return image;
    }
    public static void setArtwork(ImageView view, Bitmap image) {
        Object binding=image==null?NO_ARTWORK:image;
        if(view.getTag(R.id.ui_artwork_binding)==binding)return;
        view.setTag(R.id.ui_artwork_binding,binding);
        if (image == null) {
            Drawable fallback = view.getContext().getDrawable(R.drawable.ic_podcast).mutate();
            fallback.setTint(view.getContext().getColor(R.color.accent));
            view.setImageDrawable(fallback); view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        } else { view.setImageBitmap(image); view.setScaleType(ImageView.ScaleType.CENTER_CROP); }
        view.setClipToOutline(true);
    }
}
