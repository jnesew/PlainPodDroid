package io.github.jnesew.plainpoddroid.core.download;

import java.io.File;
import java.util.Objects;

public final class DownloadLookup {
    public enum State {
        ABSENT,
        PARTIAL,
        COMPLETE,
        INVALID
    }

    private final State state;
    private final File completeFile;

    private DownloadLookup(State state, File completeFile) {
        this.state = Objects.requireNonNull(state, "state");
        this.completeFile = completeFile;
    }

    public static DownloadLookup withoutFile(State state) {
        if (state == State.COMPLETE) {
            throw new IllegalArgumentException("A complete lookup requires a file");
        }
        return new DownloadLookup(state, null);
    }

    public static DownloadLookup complete(File file) {
        return new DownloadLookup(State.COMPLETE, Objects.requireNonNull(file, "file"));
    }

    public State getState() {
        return state;
    }

    public File getCompleteFile() {
        if (state != State.COMPLETE || completeFile == null) {
            throw new IllegalStateException("No verified complete file is available");
        }
        return completeFile;
    }
}
