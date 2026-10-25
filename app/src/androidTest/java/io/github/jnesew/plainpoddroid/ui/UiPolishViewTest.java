package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.MainActivity;

@SuppressWarnings("deprecation")
public final class UiPolishViewTest extends InstrumentationTestCase {
    private int refreshes, clicks;
    private boolean allowed = true;
    private long downTime;
    private Context context(float font, boolean dark) {
        Context base = getInstrumentation().getTargetContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = font;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_PlainPodDroid);
    }
    private ViewGroup episode(Context c) {
        ViewGroup root = (ViewGroup) LayoutInflater.from(c).inflate(R.layout.screen_episode, null);
        root.setVisibility(View.VISIBLE); root.findViewById(R.id.episode_details_panel).setVisibility(View.VISIBLE);
        return root;
    }
    private void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(UiWidgets.dp(view.getContext(), 320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(UiWidgets.dp(view.getContext(), 640), View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    private void draw(View view) {
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image)); image.recycle();
    }
    public void testPlayerCircleAndGlyphFitAtBothFontSizesAndThemes() {
        getInstrumentation().runOnMainSync(() -> {
            for (float font : new float[] {1f, 2f}) for (boolean dark : new boolean[] {false, true}) {
                ViewGroup root = episode(context(font, dark));
                Button play = root.findViewById(R.id.episode_playback_action);
                UiWidgets.icon(play, R.drawable.ic_pause, "Pause", true); layout(root); draw(play);
                LinearLayout controls = root.findViewById(R.id.episode_playback_controls);
                assertTrue(play.getTop() >= 0); assertTrue(play.getBottom() <= controls.getHeight());
                Rect icon = ((LayerDrawable) play.getBackground()).getDrawable(1).getBounds();
                assertEquals(play.getWidth() / 2, icon.centerX()); assertEquals(play.getHeight() / 2, icon.centerY());
                assertEquals(play.getHeight(), play.getBackground().getBounds().height());
            }
        });
    }
    public void testUnchangedIconReusesDrawableAndUpdatesAccessibleLabel(){
        getInstrumentation().runOnMainSync(()->{
            Context c=context(1f,false);Button button=UiWidgets.iconButton(c,R.drawable.ic_play,"Play first",true);
            var background=button.getBackground();UiWidgets.icon(button,R.drawable.ic_play,"Play second",true);
            assertSame(background,button.getBackground());assertEquals("Play second",button.getContentDescription());
            button.setEnabled(false);UiWidgets.icon(button,R.drawable.ic_play,"Play second",true);assertSame(background,button.getBackground());
            UiWidgets.icon(button,R.drawable.ic_pause,"Pause",true);assertNotSame(background,button.getBackground());
        });
    }

    public void testUnchangedArtworkRetainsDrawableAndPlaceholderBinding(){
        getInstrumentation().runOnMainSync(()->{
            var image=UiWidgets.artwork(context(1f,false),56);var placeholder=image.getDrawable();
            UiWidgets.setArtwork(image,null);assertSame(placeholder,image.getDrawable());
            Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);UiWidgets.setArtwork(image,bitmap);
            var drawable=image.getDrawable();UiWidgets.setArtwork(image,bitmap);assertSame(drawable,image.getDrawable());
            UiWidgets.setArtwork(image,null);assertNotSame(drawable,image.getDrawable());bitmap.recycle();
        });
    }

    private int iconPixel(Button button) {
        Bitmap image = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
        ((LayerDrawable) button.getBackground()).getDrawable(1).draw(new Canvas(image));
        int color = image.getPixel(button.getWidth() / 2, button.getHeight() / 2); image.recycle(); return color;
    }
    public void testSelectedHeartReturnsToFullAccentAfterWorkEnds() {
        int[] pixels = new int[3];
        boolean[] selected = new boolean[1];
        String[] states = new String[1];
        getInstrumentation().runOnMainSync(() -> {
            Context c = context(1f, true); Button heart = UiWidgets.iconButton(c, R.drawable.ic_heart, "Favorite", false);
            heart.setEnabled(false); UiWidgets.toggleIcon(heart, R.drawable.ic_heart_filled, "Remove favorite", true);
            heart.measure(View.MeasureSpec.makeMeasureSpec(UiWidgets.dp(c,48),View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(UiWidgets.dp(c,48),View.MeasureSpec.EXACTLY));
            heart.layout(0,0,heart.getMeasuredWidth(),heart.getMeasuredHeight());
            draw(heart);
            pixels[0] = iconPixel(heart);
            states[0] = "enabled=" + heart.isEnabled() + " view=" + java.util.Arrays.toString(heart.getDrawableState())
                    + " background=" + java.util.Arrays.toString(heart.getBackground().getState())
                    + " image=" + java.util.Arrays.toString(((LayerDrawable) heart.getBackground()).getDrawable(1).getState());
            heart.setEnabled(true);
            pixels[1] = iconPixel(heart); pixels[2] = c.getColor(R.color.accent);
            selected[0] = heart.isSelected();
        });
        assertTrue("Disabled glyph pixel: " + Integer.toHexString(pixels[0]) + " " + states[0],
                android.graphics.Color.alpha(pixels[0]) < 255);
        assertEquals(pixels[2], pixels[1]); assertTrue(selected[0]);
    }
    public void testFavoriteFeedbackKeepsTheCoverAtTheSamePosition() {
        getInstrumentation().runOnMainSync(() -> {
            ViewGroup root = episode(context(2f, true)); View progress = root.findViewById(R.id.episode_action_progress);
            TextView status = root.findViewById(R.id.episode_action_status);
            UiWidgets.episodeFeedback(progress,status,true,false,false); layout(root);
            Rect before = new Rect(); root.findViewById(R.id.episode_cover).getDrawingRect(before);
            root.offsetDescendantRectToMyCoords(root.findViewById(R.id.episode_cover),before);
            for (boolean busy : new boolean[] {true, false}) {
                status.setText("Episode added to favorites."); UiWidgets.episodeFeedback(progress,status,true,busy,false); layout(root);
                Rect after = new Rect(); root.findViewById(R.id.episode_cover).getDrawingRect(after);
                root.offsetDescendantRectToMyCoords(root.findViewById(R.id.episode_cover),after); assertEquals(before,after);
            }
        });
    }
    private PullRefreshScrollView pullView() {
        Context c = context(1f,false); FrameLayout parent = new FrameLayout(c);
        PullRefreshScrollView scroll = new PullRefreshScrollView(c); parent.addView(scroll);
        LinearLayout rows = new LinearLayout(c); rows.setOrientation(LinearLayout.VERTICAL);
        Button body = new Button(c); body.setText("Podcast"); body.setOnClickListener(v -> clicks++);
        rows.addView(body,new LinearLayout.LayoutParams(-1,UiWidgets.dp(c,80)));
        View space = new View(c); rows.addView(space,new LinearLayout.LayoutParams(-1,UiWidgets.dp(c,1000)));
        scroll.addView(rows); scroll.setRefreshAction(() -> allowed, () -> refreshes++); layout(parent); return scroll;
    }
    private void touch(PullRefreshScrollView view, int action, int y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent event = MotionEvent.obtain(downTime,now,action,
                UiWidgets.dp(view.getContext(),40),UiWidgets.dp(view.getContext(),y),0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    public void testPullRefreshCancelsTheRowClickAndNormalTapStillNavigates() {
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                new Intent(getInstrumentation().getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PullRefreshScrollView[] fixture = new PullRefreshScrollView[1];
        try {
            getInstrumentation().runOnMainSync(() -> {
                fixture[0] = pullView();
                activity.addContentView((View) fixture[0].getParent(),
                        new ViewGroup.LayoutParams(-1, -1));
            });
            getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(() -> {
                touch(fixture[0],MotionEvent.ACTION_DOWN,24);
                touch(fixture[0],MotionEvent.ACTION_MOVE,200);
                touch(fixture[0],MotionEvent.ACTION_UP,200);
            });
            assertEquals(1,refreshes); assertEquals(0,clicks);
            assertEquals(0f,fixture[0].getChildAt(0).getTranslationY(),0f);
            getInstrumentation().runOnMainSync(() -> {
                touch(fixture[0],MotionEvent.ACTION_DOWN,24);
                touch(fixture[0],MotionEvent.ACTION_UP,24);
            });
            // Framework clicks are posted; a detached view or an immediate
            // assertion on the main thread cannot exercise that delivery.
            getInstrumentation().waitForIdleSync();
            assertEquals(1,refreshes); assertEquals(1,clicks);
        } finally {
            getInstrumentation().runOnMainSync(activity::finish);
            getInstrumentation().waitForIdleSync();
        }
    }
    public void testBusyAndCancelledPullsDoNotStartRefresh() {
        getInstrumentation().runOnMainSync(() -> {
            PullRefreshScrollView scroll = pullView();
            touch(scroll,MotionEvent.ACTION_DOWN,24); touch(scroll,MotionEvent.ACTION_MOVE,200); touch(scroll,MotionEvent.ACTION_CANCEL,200);
            assertEquals(0,refreshes);
            allowed=false; touch(scroll,MotionEvent.ACTION_DOWN,24); touch(scroll,MotionEvent.ACTION_MOVE,200); touch(scroll,MotionEvent.ACTION_UP,200);
            assertEquals(0,refreshes);
        });
    }
    public void testPullFromNonClickableSpaceWorksOnlyWhenStartingAtTheTop() {
        getInstrumentation().runOnMainSync(() -> {
            PullRefreshScrollView scroll = pullView();
            touch(scroll,MotionEvent.ACTION_DOWN,120); touch(scroll,MotionEvent.ACTION_MOVE,300); touch(scroll,MotionEvent.ACTION_UP,300);
            assertEquals(1,refreshes); assertEquals(0,clicks);
            scroll.scrollTo(0,UiWidgets.dp(scroll.getContext(),100));
            touch(scroll,MotionEvent.ACTION_DOWN,120); touch(scroll,MotionEvent.ACTION_MOVE,300); touch(scroll,MotionEvent.ACTION_UP,300);
            assertEquals(1,refreshes);
        });
    }
}
