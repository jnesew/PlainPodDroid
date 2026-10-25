package io.github.jnesew.plainpoddroid.core.ui;

/** One downward gesture at the top; release never queues work after cancellation. */
public final class PullRefreshGesture {
    private final float slop, threshold, limit;
    private float startX, startY, distance;
    private boolean eligible, pulling;

    public PullRefreshGesture(float slop, float threshold, float limit) {
        if (!Float.isFinite(slop) || !Float.isFinite(threshold) || !Float.isFinite(limit)
                || slop < 0 || threshold <= 0 || limit < threshold) throw new IllegalArgumentException();
        this.slop = slop; this.threshold = threshold; this.limit = limit;
    }
    public void begin(float x, float y, boolean atTop, boolean enabled) {
        cancel(); startX = x; startY = y; eligible = atTop && enabled;
    }
    public boolean move(float x, float y, boolean atTop, boolean enabled) {
        if (!eligible) return false;
        float dx = Math.abs(x - startX), dy = y - startY;
        if (!atTop || !enabled || !Float.isFinite(dx) || !Float.isFinite(dy)
                || dy < -slop || (!pulling && dx > slop && dx > Math.abs(dy))) {
            cancel(); return false;
        }
        if (dy > slop && dy > dx) pulling = true;
        if (pulling) distance = Math.min(limit, Math.max(0, (dy - slop) * .5f));
        return pulling;
    }
    public boolean release(boolean atTop, boolean enabled) {
        boolean refresh = eligible && pulling && ready() && atTop && enabled;
        cancel(); return refresh;
    }
    public boolean ready() { return distance >= threshold; }
    public float distance() { return distance; }
    public void cancel() { eligible = false; pulling = false; distance = 0; }
}
