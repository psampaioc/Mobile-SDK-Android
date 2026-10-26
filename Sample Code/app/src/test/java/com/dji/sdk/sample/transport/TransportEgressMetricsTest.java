package com.dji.sdk.sample.transport;

/** Proves that Transport reports all Android-to-Edge sender categories, not video alone. */
public final class TransportEgressMetricsTest {
    private TransportEgressMetricsTest() { }

    public static void run() {
        TransportEgressMetrics metrics = new TransportEgressMetrics();
        metrics.recordPrimaryRtp(1_000_000L);
        metrics.recordMetadata(2_000L);
        metrics.recordTelemetry(3_000L);
        metrics.recordClock(500L);
        TransportEgressMetrics.Snapshot baseline = metrics.snapshot(1_000_000_000L);
        require(!baseline.primaryRtpRate.available, "first Primary sample establishes its baseline");
        require(!baseline.totalRate.available, "first total sample establishes its baseline");
        require(baseline.totalAcceptedBytes == 1_005_500L, "all egress categories are summed");

        metrics.recordPrimaryRtp(1_000_000L);
        metrics.recordMetadata(2_000L);
        metrics.recordTelemetry(3_000L);
        metrics.recordClock(500L);
        TransportEgressMetrics.Snapshot next = metrics.snapshot(2_000_000_000L);
        require(next.primaryRtpRate.available, "Primary rate becomes available after a second sample");
        require(next.totalRate.available, "total rate becomes available after a second sample");
        require(close(next.primaryRtpRate.bitsPerSecond, 8_000_000.0d), "Primary RTP rate");
        require(close(next.totalRate.bitsPerSecond, 8_044_000.0d), "total egress includes every sender");

        metrics.recordTelemetry(-1L);
        require(metrics.snapshot(3_000_000_000L).telemetryAcceptedBytes == 6_000L,
                "invalid accepted-byte records are ignored");
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 0.001d;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
