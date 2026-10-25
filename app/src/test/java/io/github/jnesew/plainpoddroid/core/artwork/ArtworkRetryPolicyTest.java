package io.github.jnesew.plainpoddroid.core.artwork;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ArtworkRetryPolicyTest {
    @Test public void temporaryFailuresBackOffAndSuccessfulRetryClearsDeadline() {
        ArtworkRetryPolicy policy=new ArtworkRetryPolicy();
        assertTrue(policy.canAttempt("a",100)); policy.failed("a",100,false);
        assertFalse(policy.canAttempt("a",30_099)); assertTrue(policy.canAttempt("a",30_100));
        policy.failed("a",30_100,false); assertEquals(90_100,policy.nextAttempt("a"));
        policy.succeeded("a"); assertTrue(policy.canAttempt("a",90_101));
    }
    @Test public void rejectedPayloadsRetrySlowlyAndTemporaryDelayIsCapped() {
        ArtworkRetryPolicy policy=new ArtworkRetryPolicy(); policy.failed("a",0,true);
        assertEquals(86_400_000,policy.nextAttempt("a"));
        for(int i=0;i<20;i++)policy.failed("b",0,false);
        assertEquals(3_600_000,policy.nextAttempt("b")); policy.clear(); assertEquals(0,policy.nextAttempt("b"));
    }
}
