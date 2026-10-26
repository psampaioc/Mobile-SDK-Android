package com.dji.sdk.sample.transport.metrics;

/** Estimates a monotonic byte-counter's output rate without retaining an unbounded history. */
public final class ByteRateMeter {
    private long lastBytes = -1L;
    private long lastNanos = -1L;

    public Snapshot sample(long totalBytes, long nowNanos) {
        if (totalBytes < 0 || nowNanos <= 0 || lastBytes < 0 || lastNanos < 0
                || totalBytes < lastBytes || nowNanos <= lastNanos) {
            lastBytes = totalBytes;
            lastNanos = nowNanos;
            return Snapshot.unavailable();
        }
        long elapsedNanos = nowNanos - lastNanos;
        long deltaBytes = totalBytes - lastBytes;
        lastBytes = totalBytes;
        lastNanos = nowNanos;
        return new Snapshot(deltaBytes * 8_000_000_000.0d / elapsedNanos, elapsedNanos, deltaBytes);
    }

    public static final class Snapshot {
        public final boolean available;
        public final double bitsPerSecond;
        public final long elapsedNanos;
        public final long deltaBytes;
        private Snapshot(double bitsPerSecond, long elapsedNanos, long deltaBytes) {
            this.available = true;
            this.bitsPerSecond = bitsPerSecond;
            this.elapsedNanos = elapsedNanos;
            this.deltaBytes = deltaBytes;
        }
        private Snapshot() { available = false; bitsPerSecond = 0; elapsedNanos = 0; deltaBytes = 0; }
        static Snapshot unavailable() { return new Snapshot(); }
    }
}
