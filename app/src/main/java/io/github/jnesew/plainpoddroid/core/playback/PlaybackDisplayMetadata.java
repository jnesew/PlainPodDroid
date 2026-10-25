package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

public final class PlaybackDisplayMetadata {
    private PlaybackDisplayMetadata() {
        throw new AssertionError("No instances");
    }

    public static String notificationTitle(String title) {
        String value = title == null || title.isEmpty() ? "Untitled episode" : title;
        return truncate(value, SecurityLimits.MAX_NOTIFICATION_TITLE_CHARS);
    }

    public static String notificationSubtitle(String author) {
        return truncate(author == null ? "" : author,
                SecurityLimits.MAX_NOTIFICATION_SUBTITLE_CHARS);
    }

    private static String truncate(String value, int maximumChars) {
        if (value.length() <= maximumChars) {
            return value;
        }
        int end = maximumChars;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
