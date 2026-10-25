package io.github.jnesew.plainpoddroid.core.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class CanonicalHttpsUrlTest {
    @Test
    public void canonicalizesHostPortRootAndFragment() throws Exception {
        CanonicalHttpsUrl value = CanonicalHttpsUrl.parse(
                "HTTPS://BÜCHER.Example.:443?token=secret#ignored");

        assertEquals("https://xn--bcher-kva.example/?token=secret", value.toString());
        assertEquals("xn--bcher-kva.example", value.getHost());
        assertEquals("https://xn--bcher-kva.example", value.getOriginForDisplay());
    }

    @Test
    public void preservesEscapedPathAndQueryWithoutExposingThemInOrigin() throws Exception {
        CanonicalHttpsUrl value = CanonicalHttpsUrl.parse(
                "https://feeds.example/private%2Fid.xml?token=a%2Fb");

        assertEquals(
                "https://feeds.example/private%2Fid.xml?token=a%2Fb",
                value.toString());
        assertEquals("https://feeds.example", value.getOriginForDisplay());
    }

    @Test
    public void rejectsAmbiguousOrNonHttpsForms() {
        assertReason("http://example.com/feed", NetworkPolicyException.Reason.HTTPS_REQUIRED);
        assertReason("https://user:pass@example.com/feed",
                NetworkPolicyException.Reason.USER_INFO_FORBIDDEN);
        assertReason("https://example.com:8443/feed",
                NetworkPolicyException.Reason.PORT_FORBIDDEN);
        assertReason("https://127.0.0.1/feed", NetworkPolicyException.Reason.IP_LITERAL);
        assertReason("https://2130706433/feed", NetworkPolicyException.Reason.IP_LITERAL);
        assertReason("https://0x7f000001/feed", NetworkPolicyException.Reason.IP_LITERAL);
        assertReason("https://[::1]/feed", NetworkPolicyException.Reason.IP_LITERAL);
        assertReason(" https://example.com/feed", NetworkPolicyException.Reason.INVALID_URL);
        assertReason("https://example.com\\feed", NetworkPolicyException.Reason.INVALID_URL);
        assertReason("https:///feed", NetworkPolicyException.Reason.HOST_REQUIRED);
    }

    @Test
    public void enforcesInputAndCanonicalLength() {
        String oversized = "https://example.com/" + "a".repeat(SecurityLimits.MAX_URL_CHARS);
        assertReason(oversized, NetworkPolicyException.Reason.URL_TOO_LONG);

        String unicodeExpansion = "https://example.com/" + "ä".repeat(1_400);
        assertReason(unicodeExpansion, NetworkPolicyException.Reason.URL_TOO_LONG);
    }

    private static void assertReason(String value, NetworkPolicyException.Reason reason) {
        NetworkPolicyException exception = assertThrows(
                NetworkPolicyException.class,
                () -> CanonicalHttpsUrl.parse(value));
        assertEquals(reason, exception.getReason());
    }
}
