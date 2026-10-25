package io.github.jnesew.plainpoddroid.core.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PullRefreshGestureTest {
    private final PullRefreshGesture gesture = new PullRefreshGesture(8, 56, 96);
    @Test public void deliberateDownwardPullRefreshesOnlyOnceOnRelease() {
        gesture.begin(40, 20, true, true);
        assertTrue(gesture.move(42, 164, true, true));
        assertTrue(gesture.ready());
        assertTrue(gesture.release(true, true));
        assertFalse(gesture.release(true, true));
        assertEquals(0, gesture.distance(), 0);
    }
    @Test public void shortUpwardAndHorizontalGesturesRemainNonRefreshing() {
        gesture.begin(0, 0, true, true); gesture.move(0, 50, true, true);
        assertFalse(gesture.release(true, true));
        gesture.begin(0, 100, true, true); assertFalse(gesture.move(0, 70, true, true));
        assertFalse(gesture.release(true, true));
        gesture.begin(0, 0, true, true); assertFalse(gesture.move(50, 10, true, true));
        assertFalse(gesture.move(50, 300, true, true));
        assertFalse(gesture.release(true, true));
    }
    @Test public void cancellationOrRetreatBelowThresholdCannotRefresh() {
        gesture.begin(0, 0, true, true); gesture.move(0, 200, true, true); gesture.cancel();
        assertFalse(gesture.release(true, true));
        gesture.begin(0, 0, true, true); gesture.move(0, 200, true, true); gesture.move(0, 60, true, true);
        assertFalse(gesture.ready()); assertFalse(gesture.release(true, true));
    }
    @Test public void scrollingAndWorkRestrictionsMustHoldThroughoutTheGesture() {
        gesture.begin(0, 0, false, true); assertFalse(gesture.move(0, 200, true, true));
        assertFalse(gesture.release(true, true));
        gesture.begin(0, 0, true, false); assertFalse(gesture.move(0, 200, true, true));
        assertFalse(gesture.release(true, true));
        gesture.begin(0, 0, true, true); gesture.move(0, 200, true, true);
        assertFalse(gesture.release(true, false));
        gesture.begin(0, 0, true, true); gesture.move(0, 200, true, true);
        assertFalse(gesture.move(0, 220, false, true));
        assertFalse(gesture.release(true, true));
    }
    @Test public void visualTravelIsBoundedAndInvalidCoordinatesCancel() {
        gesture.begin(0, 0, true, true); gesture.move(0, 10000, true, true);
        assertEquals(96, gesture.distance(), 0);
        assertFalse(gesture.move(Float.NaN, 10000, true, true));
        assertFalse(gesture.release(true, true));
    }
    @Test public void invalidThresholdConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PullRefreshGesture(8, 56, 55));
        assertThrows(IllegalArgumentException.class, () -> new PullRefreshGesture(-1, 56, 96));
        assertThrows(IllegalArgumentException.class, () -> new PullRefreshGesture(8, 0, 96));
        assertThrows(IllegalArgumentException.class, () -> new PullRefreshGesture(8, Float.NaN, 96));
    }
}
