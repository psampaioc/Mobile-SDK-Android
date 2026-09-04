package com.dji.sdk.sample.transport.diagnostics;

public final class CallbackRateTrackerTest {
    public static void run() {
        CallbackRateTracker tracker = new CallbackRateTracker("rtk:0", 3);
        tracker.record(1_000_000_000L);
        tracker.record(1_100_000_000L);
        tracker.record(1_200_000_000L);
        tracker.record(1_400_000_000L);
        tracker.record(1_500_000_000L);
        tracker.record(1_500_000_000L);

        CallbackRateSnapshot snapshot = tracker.snapshot();
        check(snapshot.getCallbackCount() == 6, "callback count");
        check(snapshot.getSampledIntervalCount() == 3, "bounded interval count");
        check(snapshot.getMedianIntervalNanos() == 100_000_000L, "median");
        check(snapshot.getP95IntervalNanos() == 200_000_000L, "p95");
        check(snapshot.getMaxIntervalNanos() == 200_000_000L, "max");
        check(snapshot.getNonMonotonicCount() == 1, "non-monotonic count");
        check(Math.abs(snapshot.getMeanHz() - 10.0) < 0.0001, "mean Hz");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
