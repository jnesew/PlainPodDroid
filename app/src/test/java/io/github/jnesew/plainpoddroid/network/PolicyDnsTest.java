package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dns;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public final class PolicyDnsTest {
    @Test
    public void returnsTheExactValidatedPublicAnswerSet() throws Exception {
        List<InetAddress> answers = List.of(address(1, 1, 1, 1), address(8, 8, 8, 8));
        AtomicInteger calls = new AtomicInteger();
        Dns delegate = hostname -> {
            calls.incrementAndGet();
            assertEquals("feeds.example", hostname);
            return answers;
        };

        List<InetAddress> result = new PolicyDns(delegate).lookup("feeds.example");

        assertEquals(1, calls.get());
        assertEquals(answers, result);
        assertSame(answers.get(0), result.get(0));
        assertSame(answers.get(1), result.get(1));
    }

    @Test
    public void rejectsMixedAnswersWithoutLeakingTheHost() throws Exception {
        PolicyDns dns = new PolicyDns(hostname -> List.of(
                address(1, 1, 1, 1),
                address(192, 168, 1, 10)));

        UnknownHostException exception = assertThrows(
                UnknownHostException.class,
                () -> dns.lookup("private-token.example"));

        assertEquals(
                "DNS answer was rejected by network policy.",
                exception.getMessage());
        assertFalse(exception.getMessage().contains("private-token"));
        assertEquals(
                NetworkPolicyException.Reason.NON_PUBLIC_ADDRESS,
                ((NetworkPolicyException) exception.getCause()).getReason());
    }

    @Test
    public void rejectsLiteralAndNonCanonicalHostsBeforeResolution() {
        AtomicInteger calls = new AtomicInteger();
        PolicyDns dns = new PolicyDns(hostname -> {
            calls.incrementAndGet();
            return List.of(address(8, 8, 8, 8));
        });

        assertThrows(UnknownHostException.class, () -> dns.lookup("8.8.8.8"));
        assertThrows(UnknownHostException.class, () -> dns.lookup("FEEDS.EXAMPLE"));
        assertEquals(0, calls.get());
    }

    private static InetAddress address(int first, int second, int third, int fourth)
            throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] {
                (byte) first,
                (byte) second,
                (byte) third,
                (byte) fourth
        });
    }
}
