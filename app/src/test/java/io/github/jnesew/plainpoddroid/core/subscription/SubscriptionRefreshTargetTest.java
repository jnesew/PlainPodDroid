package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SubscriptionRefreshTargetTest {
    @Test public void commitAuthorityRequiresSameIdSourceAndMembershipGeneration() throws Exception {
        var source=CanonicalHttpsUrl.parse("https://pod.example/rss");
        var target=new SubscriptionRefreshTarget(1,source,FeedValidators.none(),7);
        assertTrue(target.matches(1,7,source));
        assertFalse(target.matches(1,8,source)); // Same row reactivated after archival.
        assertFalse(target.matches(2,7,source)); // Same URL re-added as a different row.
        assertFalse(target.matches(1,7,CanonicalHttpsUrl.parse("https://pod.example/other")));
    }
    @Test public void absentMembershipGenerationCannotAuthorizeACommit() throws Exception {
        var source=CanonicalHttpsUrl.parse("https://pod.example/rss");
        for(long generation:new long[]{0,-1})assertThrows(IllegalArgumentException.class,
                ()->new SubscriptionRefreshTarget(1,source,FeedValidators.none(),generation));
    }
}
