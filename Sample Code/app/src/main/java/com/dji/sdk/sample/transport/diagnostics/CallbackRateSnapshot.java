package com.dji.sdk.sample.transport.diagnostics;

public final class CallbackRateSnapshot {
    private final String sourceKey;
    private final long callbackCount;
    private final int sampledIntervalCount;
    private final double meanHz;
    private final long medianIntervalNanos;
    private final long p95IntervalNanos;
    private final long p99IntervalNanos;
    private final long maxIntervalNanos;
    private final long nonMonotonicCount;

    CallbackRateSnapshot(String sourceKey, long callbackCount, int sampledIntervalCount,
                         double meanHz, long medianIntervalNanos, long p95IntervalNanos,
                         long p99IntervalNanos, long maxIntervalNanos, long nonMonotonicCount) {
        this.sourceKey = sourceKey;
        this.callbackCount = callbackCount;
        this.sampledIntervalCount = sampledIntervalCount;
        this.meanHz = meanHz;
        this.medianIntervalNanos = medianIntervalNanos;
        this.p95IntervalNanos = p95IntervalNanos;
        this.p99IntervalNanos = p99IntervalNanos;
        this.maxIntervalNanos = maxIntervalNanos;
        this.nonMonotonicCount = nonMonotonicCount;
    }

    public String getSourceKey() { return sourceKey; }
    public long getCallbackCount() { return callbackCount; }
    public int getSampledIntervalCount() { return sampledIntervalCount; }
    public double getMeanHz() { return meanHz; }
    public long getMedianIntervalNanos() { return medianIntervalNanos; }
    public long getP95IntervalNanos() { return p95IntervalNanos; }
    public long getP99IntervalNanos() { return p99IntervalNanos; }
    public long getMaxIntervalNanos() { return maxIntervalNanos; }
    public long getNonMonotonicCount() { return nonMonotonicCount; }
}
