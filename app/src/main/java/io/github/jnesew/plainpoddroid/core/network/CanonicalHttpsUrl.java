package io.github.jnesew.plainpoddroid.core.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

public final class CanonicalHttpsUrl {
    private final URI uri;
    private final String host;

    private CanonicalHttpsUrl(URI uri, String host) {
        this.uri = uri;
        this.host = host;
    }

    public static CanonicalHttpsUrl parse(String input) throws NetworkPolicyException {
        if (input == null || input.isEmpty()) {
            throw failure(NetworkPolicyException.Reason.INVALID_URL, "URL is empty.");
        }
        if (input.length() > SecurityLimits.MAX_URL_CHARS) {
            throw failure(NetworkPolicyException.Reason.URL_TOO_LONG, "URL exceeds the limit.");
        }
        if (!input.equals(input.trim()) || input.indexOf('\\') >= 0) {
            throw failure(NetworkPolicyException.Reason.INVALID_URL, "URL syntax is invalid.");
        }

        final URI parsed;
        try {
            parsed = new URI(input);
        } catch (URISyntaxException exception) {
            throw new NetworkPolicyException(
                    NetworkPolicyException.Reason.INVALID_URL,
                    "URL syntax is invalid.",
                    exception);
        }
        if (parsed.isOpaque()
                || parsed.getScheme() == null
                || !"https".equals(parsed.getScheme().toLowerCase(Locale.ROOT))) {
            throw failure(
                    NetworkPolicyException.Reason.HTTPS_REQUIRED,
                    "Only HTTPS URLs are allowed.");
        }
        if (parsed.getRawUserInfo() != null
                || (parsed.getRawAuthority() != null
                && parsed.getRawAuthority().indexOf('@') >= 0)) {
            throw failure(
                    NetworkPolicyException.Reason.USER_INFO_FORBIDDEN,
                    "URL user information is not allowed.");
        }

        HostAndPort hostAndPort = parseAuthority(parsed.getRawAuthority());
        String asciiHost = canonicalHost(hostAndPort.host);
        if (isNumericHost(asciiHost)) {
            throw failure(
                    NetworkPolicyException.Reason.IP_LITERAL,
                    "IP address literals are not allowed.");
        }
        if (hostAndPort.port != -1 && hostAndPort.port != 443) {
            throw failure(
                    NetworkPolicyException.Reason.PORT_FORBIDDEN,
                    "Only the ordinary HTTPS port is allowed.");
        }

        String rawPath = parsed.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            rawPath = "/";
        }
        StringBuilder value = new StringBuilder("https://")
                .append(asciiHost)
                .append(rawPath);
        if (parsed.getRawQuery() != null) {
            value.append('?').append(parsed.getRawQuery());
        }

        final URI canonical;
        try {
            canonical = new URI(new URI(value.toString()).toASCIIString());
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new NetworkPolicyException(
                    NetworkPolicyException.Reason.INVALID_URL,
                    "URL could not be canonicalized.",
                    exception);
        }
        if (canonical.toASCIIString().length() > SecurityLimits.MAX_URL_CHARS) {
            throw failure(NetworkPolicyException.Reason.URL_TOO_LONG, "URL exceeds the limit.");
        }
        return new CanonicalHttpsUrl(canonical, asciiHost);
    }

    public URI toUri() {
        return uri;
    }

    public String getHost() {
        return host;
    }

    public String getOriginForDisplay() {
        return "https://" + host;
    }

    @Override
    public String toString() {
        return uri.toASCIIString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanonicalHttpsUrl that && uri.equals(that.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri);
    }

    private static HostAndPort parseAuthority(String rawAuthority)
            throws NetworkPolicyException {
        if (rawAuthority == null || rawAuthority.isEmpty()) {
            throw failure(NetworkPolicyException.Reason.HOST_REQUIRED, "URL host is required.");
        }
        if (rawAuthority.startsWith("[") || rawAuthority.indexOf('%') >= 0) {
            throw failure(
                    NetworkPolicyException.Reason.IP_LITERAL,
                    "IP address literals are not allowed.");
        }
        int firstColon = rawAuthority.indexOf(':');
        int lastColon = rawAuthority.lastIndexOf(':');
        if (firstColon != lastColon) {
            throw failure(
                    NetworkPolicyException.Reason.IP_LITERAL,
                    "IP address literals are not allowed.");
        }

        String host = rawAuthority;
        int port = -1;
        if (lastColon >= 0) {
            host = rawAuthority.substring(0, lastColon);
            String portText = rawAuthority.substring(lastColon + 1);
            if (portText.isEmpty()) {
                throw failure(
                        NetworkPolicyException.Reason.INVALID_URL,
                        "URL port is invalid.");
            }
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException exception) {
                throw new NetworkPolicyException(
                        NetworkPolicyException.Reason.INVALID_URL,
                        "URL port is invalid.",
                        exception);
            }
        }
        return new HostAndPort(host, port);
    }

    private static String canonicalHost(String input) throws NetworkPolicyException {
        if (input.isEmpty()) {
            throw failure(NetworkPolicyException.Reason.HOST_REQUIRED, "URL host is required.");
        }
        String host = input;
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        final String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new NetworkPolicyException(
                    NetworkPolicyException.Reason.HOST_REQUIRED,
                    "URL host is invalid.",
                    exception);
        }
        if (ascii.isEmpty() || ascii.length() > SecurityLimits.MAX_HOST_CHARS) {
            throw failure(NetworkPolicyException.Reason.HOST_REQUIRED, "URL host is invalid.");
        }
        String[] labels = ascii.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                throw failure(
                        NetworkPolicyException.Reason.HOST_REQUIRED,
                        "URL host is invalid.");
            }
        }
        return ascii;
    }

    private static boolean isNumericHost(String host) {
        boolean decimalAndDots = true;
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            if (character != '.' && (character < '0' || character > '9')) {
                decimalAndDots = false;
                break;
            }
        }
        if (decimalAndDots) {
            return true;
        }
        if (host.startsWith("0x") && host.length() > 2) {
            for (int index = 2; index < host.length(); index++) {
                if (Character.digit(host.charAt(index), 16) < 0) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static NetworkPolicyException failure(
            NetworkPolicyException.Reason reason,
            String message) {
        return new NetworkPolicyException(reason, message);
    }

    private static final class HostAndPort {
        private final String host;
        private final int port;

        private HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
