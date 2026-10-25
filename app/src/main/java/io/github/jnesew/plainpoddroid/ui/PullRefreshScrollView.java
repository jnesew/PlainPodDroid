package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.ui.PullRefreshGesture;
import java.util.function.BooleanSupplier;

/** Framework-only pull gesture; the existing refresh command still owns all work. */
public final class PullRefreshScrollView extends ScrollView {
    private final PullRefreshGesture gesture;
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BooleanSupplier enabled = () -> false;
    private Runnable refresh = () -> { };
    private int pointer = -1;
    private boolean ownsTouch;

    public PullRefreshScrollView(Context context) { this(context, null); }
    public PullRefreshScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gesture = new PullRefreshGesture(ViewConfiguration.get(context).getScaledTouchSlop(),
                UiWidgets.dp(context, 56), UiWidgets.dp(context, 96));
        setWillNotDraw(false);
        hint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                getResources().getDisplayMetrics()));
        hint.setColor(context.getColor(R.color.accent)); hint.setTextAlign(Paint.Align.CENTER);
    }
    public void setRefreshAction(BooleanSupplier enabled, Runnable refresh) {
        this.enabled = enabled; this.refresh = refresh;
    }
    private boolean atTop() { return getScrollY() == 0 && !canScrollVertically(-1); }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            resetPull(); pointer = event.getPointerId(0);
            gesture.begin(event.getX(), event.getY(), atTop(), enabled.getAsBoolean());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            gesture.cancel(); updatePull();
        } else if (action == MotionEvent.ACTION_MOVE && !ownsTouch) {
            claimPull(event);
        }
        if (ownsTouch) return true;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) resetPull();
        return super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        // Non-clickable space has no child touch target, so moves reach us directly.
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            gesture.cancel(); updatePull();
        }
        if (!ownsTouch && event.getActionMasked() == MotionEvent.ACTION_MOVE) claimPull(event);
        if (!ownsTouch) {
            boolean handled = super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) resetPull();
            return handled;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE -> {
                int index = event.findPointerIndex(pointer);
                if (index < 0) gesture.cancel();
                else gesture.move(event.getX(index), event.getY(index), atTop(), enabled.getAsBoolean());
                updatePull();
            }
            case MotionEvent.ACTION_UP -> {
                boolean requested = gesture.release(atTop(), enabled.getAsBoolean());
                resetPull();
                if (requested) { refresh.run(); performClick(); }
            }
            case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                gesture.cancel(); updatePull();
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) resetPull();
            }
            default -> { }
        }
        return true;
    }
    private void claimPull(MotionEvent event) {
        int index = event.findPointerIndex(pointer);
        if (index < 0 || !gesture.move(event.getX(index), event.getY(index), atTop(), enabled.getAsBoolean())) return;
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel); cancel.recycle();
        ownsTouch = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        updatePull();
    }
    @Override public boolean performClick() { return super.performClick(); }
    private void updatePull() {
        if (getChildCount() > 0) getChildAt(0).setTranslationY(gesture.distance());
        invalidate();
    }
    private void resetPull() { ownsTouch = false; pointer = -1; gesture.cancel(); updatePull(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float distance = gesture.distance();
        if (distance > hint.getTextSize()) canvas.drawText(getContext().getString(gesture.ready()
                ? R.string.ui_release_refresh : R.string.ui_pull_refresh), getWidth() / 2f,
                distance / 2f - (hint.ascent() + hint.descent()) / 2f, hint);
    }
    @Override protected void onVisibilityChanged(android.view.View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (gesture != null && visibility != VISIBLE) resetPull();
    }
    @Override protected void onDetachedFromWindow() { resetPull(); super.onDetachedFromWindow(); }
}
