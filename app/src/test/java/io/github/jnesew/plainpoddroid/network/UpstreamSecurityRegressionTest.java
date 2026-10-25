package io.github.jnesew.plainpoddroid.network;

import java.io.IOException;
import java.util.Arrays;

import okhttp3.internal.http2.Hpack;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Exercises the pinned client's hostile-input fix without a live server. */
public final class UpstreamSecurityRegressionTest {
    @Test public void http2HeaderBudgetAcceptsOrdinaryHeadersAndRejectsOversizedLists() throws Exception {
        Hpack.Reader ordinary = new Hpack.Reader(headers(2), 4096);
        ordinary.readHeaders();
        assertEquals(2, ordinary.getAndResetHeaderList().size());

        // 320 KiB of values, plus names/overhead, exceeds the client's 256 KiB budget.
        Hpack.Reader oversized = new Hpack.Reader(headers(40), 4096);
        assertThrows(IOException.class, oversized::readHeaders);
    }

    private static Buffer headers(int count) {
        Buffer out = new Buffer();
        byte[] value = new byte[8192];
        Arrays.fill(value, (byte) 'a');
        for (int i = 0; i < count; i++) {
            out.writeByte(0); // Literal header, no indexing.
            out.writeByte(1).writeUtf8("x");
            int remaining = value.length - 127;
            out.writeByte(127);
            while (remaining >= 128) {
                out.writeByte((remaining & 127) | 128);
                remaining >>>= 7;
            }
            out.writeByte(remaining).write(value);
        }
        return out;
    }
}
