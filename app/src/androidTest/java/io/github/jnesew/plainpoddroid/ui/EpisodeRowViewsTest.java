package io.github.jnesew.plainpoddroid.ui;

import android.test.InstrumentationTestCase;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Exercise actual Android click bindings rather than Java source call counts. */
@SuppressWarnings("deprecation")
public final class EpisodeRowViewsTest extends InstrumentationTestCase {
    public void testTitleTapOpensOnlyTheBoundEpisode() {
        List<String> opened = new ArrayList<>();
        getInstrumentation().runOnMainSync(() -> {
            EpisodeRowViews rows = new EpisodeRowViews(getInstrumentation().getTargetContext());
            TextView title = rows.title("Episode", "Open episode", true, "bound-id", opened::add);
            assertEquals("Open episode", title.getContentDescription());
            assertTrue(title.performClick());
        });
        assertEquals(java.util.Collections.singletonList("bound-id"), opened);
    }

    public void testDetailsButtonUsesTheSameNavigationBoundary() {
        List<String> opened = new ArrayList<>();
        getInstrumentation().runOnMainSync(() -> {
            EpisodeRowViews rows = new EpisodeRowViews(getInstrumentation().getTargetContext());
            assertTrue(rows.detailsButton("Open episode", true, "details-id", opened::add).performClick());
        });
        assertEquals(java.util.Collections.singletonList("details-id"), opened);
    }
}
