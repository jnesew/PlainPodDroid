package io.github.jnesew.plainpoddroid;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.jnesew.plainpoddroid.core.download.DownloadQuota;

import java.util.Objects;

final class DownloadStorageSettings {
    private static final String PREFERENCES = "download_storage";
    private static final String KEY_QUOTA_BYTES = "quota_bytes";

    private final SharedPreferences preferences;

    DownloadStorageSettings(Context context) {
        Objects.requireNonNull(context, "context");
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    DownloadQuota getQuota() {
        if (!preferences.contains(KEY_QUOTA_BYTES)) {
            return DownloadQuota.defaultQuota();
        }
        final long bytes;
        try {
            bytes = preferences.getLong(
                    KEY_QUOTA_BYTES,
                    DownloadQuota.defaultQuota().getBytes());
        } catch (ClassCastException exception) {
            return DownloadQuota.minimumQuota();
        }
        try {
            return DownloadQuota.fromBytes(bytes);
        } catch (IllegalArgumentException exception) {
            return DownloadQuota.minimumQuota();
        }
    }

    long getQuotaBytes() {
        return getQuota().getBytes();
    }

    boolean setQuota(DownloadQuota quota) {
        Objects.requireNonNull(quota, "quota");
        return preferences.edit()
                .putLong(KEY_QUOTA_BYTES, quota.getBytes())
                .commit();
    }
}
