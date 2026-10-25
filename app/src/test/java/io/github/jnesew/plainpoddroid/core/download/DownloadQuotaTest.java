package io.github.jnesew.plainpoddroid.core.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

public final class DownloadQuotaTest {
    @Test
    public void choicesAreOrderedAndStayInsideAcceptedRange() {
        DownloadQuota[] choices = DownloadQuota.values();

        assertEquals(6, choices.length);
        assertEquals(SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES,
                choices[0].getBytes());
        assertEquals(SecurityLimits.MAX_DOWNLOAD_QUOTA_BYTES,
                choices[choices.length - 1].getBytes());
        for (int index = 1; index < choices.length; index++) {
            assertTrue(choices[index].getBytes() > choices[index - 1].getBytes());
        }
    }

    @Test
    public void defaultAndMinimumChoicesMatchSecurityPolicy() {
        assertEquals(SecurityLimits.DEFAULT_DOWNLOAD_QUOTA_BYTES,
                DownloadQuota.defaultQuota().getBytes());
        assertEquals(SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES,
                DownloadQuota.minimumQuota().getBytes());
    }

    @Test
    public void onlyExactChoicesCanBeDecoded() {
        for (DownloadQuota quota : DownloadQuota.values()) {
            assertEquals(quota, DownloadQuota.fromBytes(quota.getBytes()));
        }
        assertThrows(IllegalArgumentException.class, () ->
                DownloadQuota.fromBytes(SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES + 1L));
    }
}
