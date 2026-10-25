package io.github.jnesew.plainpoddroid.core.network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PublicAddressPolicy {
    private PublicAddressPolicy() {
        throw new AssertionError("No instances");
    }

    public static List<InetAddress> requireAllPublic(List<InetAddress> addresses)
            throws NetworkPolicyException {
        if (addresses == null || addresses.isEmpty()) {
            throw new NetworkPolicyException(
                    NetworkPolicyException.Reason.DNS_EMPTY,
                    "DNS returned no addresses.");
        }
        List<InetAddress> checked = new ArrayList<>(addresses.size());
        for (InetAddress address : addresses) {
            if (address == null || !isPublic(address)) {
                throw new NetworkPolicyException(
                        NetworkPolicyException.Reason.NON_PUBLIC_ADDRESS,
                        "DNS returned a non-public address.");
            }
            checked.add(address);
        }
        return Collections.unmodifiableList(checked);
    }

    public static boolean isPublic(InetAddress address) {
        Objects.requireNonNull(address, "address");
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length == 4) {
            return isPublicIpv4(bytes, 0);
        }
        if (bytes.length != 16) {
            return false;
        }
        if (isIpv4Mapped(bytes)) {
            return isPublicIpv4(bytes, 12);
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        // Require global-unicast space, excluding non-public special assignments.
        if ((unsigned(bytes[0]) & 0xe0) != 0x20) {
            return false;
        }
        if (hasPrefix(bytes, new int[] {0x20, 0x01, 0x00}, 23)) {
            // IANA marks 2001::/23 non-global unless a more specific assignment
            // says otherwise. Retain its existing public exceptions; ORCHID and
            // transition/benchmark addresses remain excluded.
            return isPublicIetfAssignment(bytes);
        }
        return !hasPrefix(bytes, new int[] {0x20, 0x01, 0x0d, 0xb8}, 32)
                && !hasPrefix(bytes, new int[] {0x3f, 0xff, 0x00}, 20) // RFC 9637 documentation.
                && !hasPrefix(bytes, new int[] {0x20, 0x02}, 16); // 6to4 transition.
    }

    private static boolean isPublicIetfAssignment(byte[] bytes) {
        // https://www.iana.org/assignments/iana-ipv6-special-registry/
        boolean publicAnycast = hasPrefix(bytes,
                new int[] {0x20, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 120)
                && unsigned(bytes[15]) >= 1 && unsigned(bytes[15]) <= 3;
        return publicAnycast
                || hasPrefix(bytes, new int[] {0x20, 0x01, 0x00, 0x03}, 32) // AMT.
                || hasPrefix(bytes, new int[] {0x20, 0x01, 0x00, 0x04, 0x01, 0x12}, 48) // AS112.
                || hasPrefix(bytes, new int[] {0x20, 0x01, 0x00, 0x30}, 28); // DETs.
    }

    private static boolean isPublicIpv4(byte[] bytes, int offset) {
        int first = unsigned(bytes[offset]);
        int second = unsigned(bytes[offset + 1]);
        int third = unsigned(bytes[offset + 2]);

        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192) {
            if (second == 0 && (third == 0 || third == 2)) {
                return false;
            }
            if (second == 88 && third == 99) {
                return false;
            }
            if (second == 168) {
                return false;
            }
        }
        if (first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)) {
            return false;
        }
        return first != 203 || second != 0 || third != 113;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return unsigned(bytes[10]) == 0xff && unsigned(bytes[11]) == 0xff;
    }

    private static boolean hasPrefix(byte[] address, int[] prefix, int bits) {
        int wholeBytes = bits / 8;
        int remaining = bits % 8;
        for (int index = 0; index < wholeBytes; index++) {
            if (unsigned(address[index]) != prefix[index]) {
                return false;
            }
        }
        if (remaining == 0) {
            return true;
        }
        int mask = 0xff << (8 - remaining);
        return (unsigned(address[wholeBytes]) & mask) == (prefix[wholeBytes] & mask);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
