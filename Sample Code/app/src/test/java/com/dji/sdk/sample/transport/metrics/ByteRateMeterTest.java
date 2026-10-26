package com.dji.sdk.sample.transport.metrics;

/** Dependency-free unit test for runtime-owned monotonic egress rates. */
public final class ByteRateMeterTest {
    public static void main(String[] args) {
        ByteRateMeter meter = new ByteRateMeter();
        require(!meter.sample(1_000L, 1_000_000_000L).available, "first sample is a baseline");
        ByteRateMeter.Snapshot rate = meter.sample(126_000L, 2_000_000_000L);
        require(rate.available, "second monotonic sample produces a rate");
        require(Math.abs(rate.bitsPerSecond - 1_000_000.0d) < 0.001d, "one megabit per second");
        require(!meter.sample(10L, 3_000_000_000L).available, "counter reset re-baselines");
        System.out.println("ByteRateMeterTest: all tests passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
