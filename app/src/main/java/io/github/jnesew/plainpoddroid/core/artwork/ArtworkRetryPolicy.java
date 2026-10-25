package io.github.jnesew.plainpoddroid.core.artwork;

import java.util.LinkedHashMap;
import java.util.Map;

/** Monotonic deadlines; finite bookkeeping and no immediate retry loop. */
public final class ArtworkRetryPolicy {
    private record Failure(int attempts, long next) { }
    private final Map<String, Failure> failures = new LinkedHashMap<>(16, .75f, true);
    public boolean canAttempt(String key, long now) { return now >= nextAttempt(key); }
    public long nextAttempt(String key) { Failure failure = failures.get(key); return failure == null ? 0 : failure.next(); }
    public void failed(String key, long now, boolean rejected) {
        Failure old = failures.get(key); int attempts = old == null ? 1 : Math.min(8, old.attempts() + 1);
        long delay = rejected ? 24L * 60 * 60 * 1000 : Math.min(60L * 60 * 1000, 30_000L << (attempts - 1));
        failures.put(key, new Failure(attempts, now + delay));
        while (failures.size() > 2048) failures.remove(failures.keySet().iterator().next());
    }
    public void succeeded(String key) { failures.remove(key); }
    public void clear() { failures.clear(); }
}
