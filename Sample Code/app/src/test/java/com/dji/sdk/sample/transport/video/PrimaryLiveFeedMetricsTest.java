package com.dji.sdk.sample.transport.video;

/** Dependency-free tests for completed-access-unit cadence only. */
public final class PrimaryLiveFeedMetricsTest {
    private PrimaryLiveFeedMetricsTest() { }

    public static void run() {
        reportsObservedCadence();
        waitsForTwoAccessUnits();
        expiresStaleObservation();
        discontinuityClearsObservation();
    }

    private static void reportsObservedCadence() {
        PrimaryLiveFeedMetrics metrics = new PrimaryLiveFeedMetrics();
        metrics.recordCompletedAccessUnit(1_000_000_000L);
        metrics.recordCompletedAccessUnit(1_033_370_000L);
        PrimaryLiveFeedMetrics.Snapshot sample = metrics.snapshot(1_050_000_000L);
        require(sample.observed, "two completed access units are observable");
        require(Math.abs(sample.framesPerSecond - 29.967d) < 0.01d, "33.37 ms is about 29.97 FPS");
        metrics.recordCompletedAccessUnit(1_073_370_000L);
        require(Math.abs(metrics.snapshot(1_090_000_000L).framesPerSecond - 25.0d) < 0.01d,
                "median complete-AU interval reports 25 FPS");
    }

    private static void waitsForTwoAccessUnits() {
        PrimaryLiveFeedMetrics metrics = new PrimaryLiveFeedMetrics();
        metrics.recordCompletedAccessUnit(1_000L);
        require(!metrics.snapshot(2_000L).observed, "one access unit cannot establish FPS");
    }

    private static void expiresStaleObservation() {
        PrimaryLiveFeedMetrics metrics = new PrimaryLiveFeedMetrics();
        metrics.recordCompletedAccessUnit(1_000_000_000L);
        metrics.recordCompletedAccessUnit(1_040_000_000L);
        PrimaryLiveFeedMetrics.Snapshot stale = metrics.snapshot(3_040_000_000L);
        require(!stale.observed && "STALE_OR_INACTIVE".equals(stale.state), "stale FPS is unobserved");
    }

    private static void discontinuityClearsObservation() {
        PrimaryLiveFeedMetrics metrics = new PrimaryLiveFeedMetrics();
        metrics.recordCompletedAccessUnit(1_000_000_000L);
        metrics.recordCompletedAccessUnit(1_040_000_000L);
        metrics.signalDiscontinuity();
        require(!metrics.snapshot(1_050_000_000L).observed, "discontinuity clears prior cadence");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
