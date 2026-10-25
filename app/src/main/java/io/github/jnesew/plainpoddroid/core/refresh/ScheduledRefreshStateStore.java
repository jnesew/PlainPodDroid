package io.github.jnesew.plainpoddroid.core.refresh;

import java.io.IOException;
import java.util.List;

public interface ScheduledRefreshStateStore {
    List<ScheduledRefreshCandidate> listScheduledRefreshCandidates() throws IOException;

    boolean recordScheduledRefreshFailure(
            ScheduledRefreshCandidate candidate,
            int newConsecutiveFailures,
            long attemptedAtMillis,
            ScheduledRefreshFailure failure) throws IOException;
}
