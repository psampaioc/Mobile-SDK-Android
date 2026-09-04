package com.dji.sdk.sample.transport.diagnostics;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Bounded callback interval statistics with fixed maximum memory usage. */
public final class CallbackRateTracker {
    private final String sourceKey;
    private final int capacity;
    private final ArrayDeque<Long> intervals;
    private long callbackCount;
    private long firstNanos = -1;
    private long lastNanos = -1;
    private long nonMonotonicCount;

    public CallbackRateTracker(String sourceKey, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.sourceKey = sourceKey;
        this.capacity = capacity;
        this.intervals = new ArrayDeque<>(capacity);
    }

    public synchronized void record(long callbackEntryNanos) {
        callbackCount++;
        if (firstNanos < 0) firstNanos = callbackEntryNanos;
        if (lastNanos >= 0) {
            long interval = callbackEntryNanos - lastNanos;
            if (interval > 0) {
                if (intervals.size() == capacity) intervals.removeFirst();
                intervals.addLast(interval);
            } else {
                nonMonotonicCount++;
            }
        }
        if (callbackEntryNanos > lastNanos) lastNanos = callbackEntryNanos;
    }

    public synchronized CallbackRateSnapshot snapshot() {
        long[] sorted = new long[intervals.size()];
        int i = 0;
        for (Long interval : intervals) sorted[i++] = interval;
        Arrays.sort(sorted);
        double hz = callbackCount > 1 && lastNanos > firstNanos
                ? (callbackCount - 1) * 1_000_000_000.0 / (lastNanos - firstNanos) : 0.0;
        return new CallbackRateSnapshot(sourceKey, callbackCount, sorted.length, hz,
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                sorted.length == 0 ? 0 : sorted[sorted.length - 1], nonMonotonicCount);
    }

    private static long percentile(long[] sorted, double quantile) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
