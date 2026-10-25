package io.github.jnesew.plainpoddroid.core.network;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PublicAddressPolicyTest {
    @Test
    public void acceptsOnlyEntirelyPublicResultSets() throws Exception {
        List<InetAddress> addresses = Arrays.asList(
                address("1.1.1.1"),
                address("2606:4700:4700::1111"));

        assertEquals(addresses, PublicAddressPolicy.requireAllPublic(addresses));
        assertThrows(UnsupportedOperationException.class,
                () -> PublicAddressPolicy.requireAllPublic(addresses)
                        .add(address("8.8.8.8")));
    }

    @Test
    public void rejectsMixedDnsAnswersAndEmptyAnswers() throws Exception {
        NetworkPolicyException mixed = assertThrows(
                NetworkPolicyException.class,
                () -> PublicAddressPolicy.requireAllPublic(Arrays.asList(
                        address("1.1.1.1"),
                        address("192.168.1.10"))));
        assertEquals(NetworkPolicyException.Reason.NON_PUBLIC_ADDRESS, mixed.getReason());

        NetworkPolicyException empty = assertThrows(
                NetworkPolicyException.class,
                () -> PublicAddressPolicy.requireAllPublic(Collections.emptyList()));
        assertEquals(NetworkPolicyException.Reason.DNS_EMPTY, empty.getReason());
    }

    @Test
    public void rejectsNonPublicIpv4Ranges() throws Exception {
        for (String value : new String[] {
                "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1",
                "169.254.1.1", "172.16.0.1", "192.0.0.1", "192.0.2.1",
                "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.51.100.1",
                "203.0.113.1", "224.0.0.1", "255.255.255.255"}) {
            assertFalse(value, PublicAddressPolicy.isPublic(address(value)));
        }
        assertTrue(PublicAddressPolicy.isPublic(address("8.8.8.8")));
    }

    @Test
    public void rejectsNonPublicAndTransitionIpv6Ranges() throws Exception {
        for (String value : new String[] {
                "::", "::1", "fe80::1", "fc00::1", "ff02::1",
                "2001:db8::1", "2001:2::1", "2001:10::1", "2001:20::1",
                "2001::1", "2002:0808:0808::1", "::ffff:127.0.0.1"}) {
            assertFalse(value, PublicAddressPolicy.isPublic(address(value)));
        }
        assertTrue(PublicAddressPolicy.isPublic(address("2606:4700:4700::1111")));
    }

    @Test
    public void rejectsDocumentationDnsAnswersIncludingMixedPublicResults() throws Exception {
        for (String value : new String[] {
                "3fff::", "3fff::1", "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff"}) {
            assertFalse(value, PublicAddressPolicy.isPublic(address(value)));
            NetworkPolicyException rejected = assertThrows(NetworkPolicyException.class,
                    () -> PublicAddressPolicy.requireAllPublic(Arrays.asList(
                            address("2606:4700:4700::1111"), address(value))));
            assertEquals(NetworkPolicyException.Reason.NON_PUBLIC_ADDRESS, rejected.getReason());
        }
    }

    @Test
    public void rejectsReservedIetfSpaceWhileRetainingPublicSpecialAssignments() throws Exception {
        for (String value : new String[] {
                "2001:1::", "2001:1::4", "2001:1::ffff", "2001:2:1::1",
                "2001:4:111::1", "2001:100::1", "2001:1ff:ffff::1"}) {
            assertFalse(value, PublicAddressPolicy.isPublic(address(value)));
        }
        for (String value : new String[] {
                "2001:1::1", "2001:1::2", "2001:1::3", "2001:3::1",
                "2001:4:112::1", "2001:30::1", "2001:3f:ffff::1",
                "2606:4700:4700::1111"}) {
            assertTrue(value, PublicAddressPolicy.isPublic(address(value)));
        }
    }

    private static InetAddress address(String value) throws Exception {
        return InetAddress.getByName(value);
    }
}
