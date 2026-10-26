package com.dji.sdk.sample.transport.network;

/** JVM checks for the bounded local RTP continuity window; it never touches a network socket. */
public final class RtpSequenceWindowMetricsTest {
    private RtpSequenceWindowMetricsTest() { }

    public static void run() {
        RtpSequenceWindowMetrics metrics = new RtpSequenceWindowMetrics();
        metrics.recordGenerated(65535);
        metrics.recordGenerated(0);
        metrics.recordAccepted(65535, 1200);
        metrics.recordAccepted(0, 700);
        RtpSequenceWindowMetrics.Snapshot first = metrics.drain();
        equal(2L, first.generatedPackets, "generated packet count");
        equal(0L, first.generatedGaps, "sequence wrap is continuous");
        equal(2L, first.acceptedPackets, "accepted packet count");
        equal(1900L, first.acceptedBytes, "accepted byte count");
        equal(0L, first.acceptedGaps, "accepted sequence wrap is continuous");

        // The next window preserves the continuity baseline and reveals a local send gap.
        metrics.recordGenerated(1);
        metrics.recordGenerated(2);
        metrics.recordAccepted(2, 100);
        metrics.recordSendFailure();
        RtpSequenceWindowMetrics.Snapshot second = metrics.drain();
        equal(0L, second.generatedGaps, "generated stream remains continuous");
        equal(1L, second.acceptedGaps, "accepted stream shows skipped local packet");
        equal(1L, second.sendFailures, "local send failure aggregate");
    }

    private static void equal(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": " + actual + " != " + expected);
    }
}
