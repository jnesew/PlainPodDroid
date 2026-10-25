package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.network.PublicAddressPolicy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

import okhttp3.Dns;

final class PolicyDns implements Dns {
    private final Dns delegate;
    private final java.util.function.BooleanSupplier offline;

    PolicyDns(Dns delegate) {
        this(delegate, () -> false);
    }

    PolicyDns(Dns delegate, java.util.function.BooleanSupplier offline) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.offline = Objects.requireNonNull(offline);
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (offline.getAsBoolean()) throw rejected(null);
        try {
            CanonicalHttpsUrl canonical = CanonicalHttpsUrl.parse("https://" + hostname + "/");
            if (!canonical.getHost().equals(hostname)) {
                throw rejected(null);
            }
            List<InetAddress> addresses = delegate.lookup(hostname);
            if (offline.getAsBoolean()) throw rejected(null);
            return PublicAddressPolicy.requireAllPublic(addresses);
        } catch (NetworkPolicyException exception) {
            throw rejected(exception);
        }
    }

    private static UnknownHostException rejected(Throwable cause) {
        UnknownHostException rejected = new UnknownHostException(
                "DNS answer was rejected by network policy.");
        if (cause != null) {
            rejected.initCause(cause);
        }
        return rejected;
    }
}
