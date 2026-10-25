package io.github.jnesew.plainpoddroid.core.network;

import java.io.IOException;

public final class NetworkPolicyException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        URL_TOO_LONG,
        INVALID_URL,
        HTTPS_REQUIRED,
        HOST_REQUIRED,
        IP_LITERAL,
        USER_INFO_FORBIDDEN,
        PORT_FORBIDDEN,
        DNS_EMPTY,
        NON_PUBLIC_ADDRESS
    }

    private final Reason reason;

    NetworkPolicyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    NetworkPolicyException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
