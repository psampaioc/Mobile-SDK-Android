package com.dji.sdk.sample.transport.video;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Bounded observation of completed Primary access units.
 *
 * <p>This is intentionally independent of DJI callback chunks: one callback is not necessarily
 * one frame. Callers record only a completed H.264 access unit from their existing worker.</p>
 */
public final class PrimaryLiveFeedMetrics {
    private static final int WINDOW_SIZE = 120;
    private static final long STALE_NANOS = 2_000_000_000L;
    private final ArrayDeque<Long> completedAtNanos = new ArrayDeque<>();

    public synchronized void recordCompletedAccessUnit(long completedNanos) {
        if (completedNanos <= 0L) return;
        Long previous = completedAtNanos.peekLast();
        if (previous != null && completedNanos <= previous) completedAtNanos.clear();
        completedAtNanos.addLast(completedNanos);
        while (completedAtNanos.size() > WINDOW_SIZE) completedAtNanos.removeFirst();
    }

    /** A parser or sender discontinuity makes any prior cadence observation untrustworthy. */
    public synchronized void signalDiscontinuity() {
        completedAtNanos.clear();
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        if (completedAtNanos.size() < 2) return Snapshot.unobserved("WAITING_FOR_ACCESS_UNITS", 0L, completedAtNanos.size());
        long latest = completedAtNanos.peekLast();
        if (nowNanos <= 0L || nowNanos < latest || nowNanos - latest >= STALE_NANOS) {
            return Snapshot.unobserved("STALE_OR_INACTIVE", latest, completedAtNanos.size());
        }
        ArrayList<Long> intervals = new ArrayList<>(completedAtNanos.size() - 1);
        Long previous = null;
        for (Long value : completedAtNanos) {
            if (previous != null && value > previous) intervals.add(value - previous);
            previous = value;
        }
        if (intervals.isEmpty()) return Snapshot.unobserved("WAITING_FOR_ACCESS_UNITS", latest, completedAtNanos.size());
        Collections.sort(intervals);
        long median = intervals.get(intervals.size() / 2);
        if (median <= 0L) return Snapshot.unobserved("WAITING_FOR_ACCESS_UNITS", latest, completedAtNanos.size());
        return new Snapshot(true, 1_000_000_000.0d / median, median, latest, completedAtNanos.size(), "OBSERVED");
    }

    public static final class Snapshot {
        public final boolean observed;
        public final double framesPerSecond;
        public final long medianIntervalNanos;
        public final long lastCompletedAccessUnitNanos;
        public final int sampleCount;
        public final String state;

        private Snapshot(boolean observed, double framesPerSecond, long medianIntervalNanos,
                long lastCompletedAccessUnitNanos, int sampleCount, String state) {
            this.observed = observed;
            this.framesPerSecond = framesPerSecond;
            this.medianIntervalNanos = medianIntervalNanos;
            this.lastCompletedAccessUnitNanos = lastCompletedAccessUnitNanos;
            this.sampleCount = sampleCount;
            this.state = state;
        }

        private static Snapshot unobserved(String state, long lastCompletedAccessUnitNanos, int sampleCount) {
            return new Snapshot(false, 0.0d, 0L, lastCompletedAccessUnitNanos, sampleCount, state);
        }
    }
}
