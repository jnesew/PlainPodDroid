package io.github.jnesew.plainpoddroid.core.download;

import java.io.IOException;

/** Per-subscription opt-in, stored with the local catalog. */
public interface AutomaticDownloadSettingsStore {
    /** False means the subscription is absent or archived. */
    boolean setAutomaticDownload(long subscriptionId, boolean enabled) throws IOException;
}
