package io.github.jnesew.plainpoddroid.core.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ControllerAccessPolicyTest {
    @Test
    public void untrustedControllerCannotConnectOrInjectMedia() {
        assertFalse(ControllerAccessPolicy.mayConnect(false));
        assertFalse(ControllerAccessPolicy.maySetMediaItems(false, 1000, 1000));
    }

    @Test
    public void trustedSystemControllerMayConnectButCannotInjectMedia() {
        assertTrue(ControllerAccessPolicy.mayConnect(true));
        assertFalse(ControllerAccessPolicy.maySetMediaItems(true, 1000, 2000));
    }

    @Test
    public void trustedSameUidControllerMayInjectOpaqueMediaIds() {
        assertTrue(ControllerAccessPolicy.maySetMediaItems(true, 2000, 2000));
    }
}
