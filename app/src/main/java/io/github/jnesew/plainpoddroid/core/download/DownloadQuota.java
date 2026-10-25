package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

public enum DownloadQuota {
    MIB_512(SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES),
    GIB_1(1L * 1024L * 1024L * 1024L),
    GIB_2(SecurityLimits.DEFAULT_DOWNLOAD_QUOTA_BYTES),
    GIB_5(5L * 1024L * 1024L * 1024L),
    GIB_10(10L * 1024L * 1024L * 1024L),
    GIB_20(SecurityLimits.MAX_DOWNLOAD_QUOTA_BYTES);

    private final long bytes;

    DownloadQuota(long bytes) {
        if (bytes < SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES
                || bytes > SecurityLimits.MAX_DOWNLOAD_QUOTA_BYTES) {
            throw new IllegalArgumentException("Quota is outside the accepted range");
        }
        this.bytes = bytes;
    }

    public long getBytes() {
        return bytes;
    }

    public static DownloadQuota defaultQuota() {
        return GIB_2;
    }

    public static DownloadQuota minimumQuota() {
        return MIB_512;
    }

    public static DownloadQuota fromBytes(long bytes) {
        for (DownloadQuota quota : values()) {
            if (quota.bytes == bytes) {
                return quota;
            }
        }
        throw new IllegalArgumentException("Unknown download quota");
    }
}
