package io.github.jnesew.plainpoddroid.core.playback;

public final class ControllerAccessPolicy {
    private ControllerAccessPolicy() {
        throw new AssertionError("No instances");
    }

    public static boolean mayConnect(boolean frameworkTrusted) {
        return frameworkTrusted;
    }

    public static boolean maySetMediaItems(
            boolean frameworkTrusted,
            int controllerUid,
            int applicationUid) {
        return frameworkTrusted && controllerUid == applicationUid;
    }
}
