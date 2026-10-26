package com.dji.sdk.sample.transport;

/** Pure monotonic-stage coverage: cumulative counters cannot mask a later interruption. */
public final class PrimaryTransportMonitorTest {
    private PrimaryTransportMonitorTest() { }

    public static void run() {
        testCallbackStallAfterHealthySend();
        testParserWaitDoesNotClaimEdgeFailure();
        testSocketFailureIsLocal();
        testObservedCadenceRaisesOutageThreshold();
        testRecoveryIsOneTransition();
    }

    private static void testCallbackStallAfterHealthySend() {
        PrimaryTransportMonitor monitor = new PrimaryTransportMonitor();
        monitor.observe(100L, evidence(100L, 100L, 100L, 100L, 0L, 0L));
        PrimaryTransportMonitor.Snapshot stalled = monitor.observe(2_000_000_101L,
                evidence(100L, 100L, 100L, 100L, 0L, 0L));
        require("OUTAGE".equals(stalled.state), "callback interruption is outage");
        require("DJI_CALLBACK_STALLED".equals(stalled.detail), "callback classification");
    }

    private static void testParserWaitDoesNotClaimEdgeFailure() {
        PrimaryTransportMonitor monitor = new PrimaryTransportMonitor();
        PrimaryTransportMonitor.Snapshot parsing = monitor.observe(3_000_000_000L,
                evidence(3_000_000_000L, 3_000_000_000L, 1L, 1L, 0L, 0L));
        require("PARSING".equals(parsing.state), "AU starvation is parser state");
        require(parsing.detail.contains("H264"), "parser detail");
    }

    private static void testSocketFailureIsLocal() {
        PrimaryTransportMonitor monitor = new PrimaryTransportMonitor();
        PrimaryTransportMonitor.Snapshot failed = monitor.observe(3_000_000_000L,
                evidence(3_000_000_000L, 3_000_000_000L, 3_000_000_000L, 1L,
                        2_999_999_000L, 0L));
        require("OUTAGE".equals(failed.state), "socket error is outage");
        require("ANDROID_SOCKET_SEND_FAILED".equals(failed.detail), "socket classification");
    }

    private static void testObservedCadenceRaisesOutageThreshold() {
        PrimaryTransportMonitor monitor = new PrimaryTransportMonitor();
        PrimaryTransportMonitor.Evidence slow = new PrimaryTransportMonitor.Evidence(
                1_000_000_000L, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L,
                0L, 0L, 500_000_000L);
        PrimaryTransportMonitor.Snapshot waiting = monitor.observe(5_000_000_000L, slow);
        require("SENDING".equals(waiting.state), "five seconds remain healthy for 500 ms median AU cadence");
        PrimaryTransportMonitor.Snapshot outage = monitor.observe(6_000_000_001L, slow);
        require("OUTAGE".equals(outage.state), "threshold follows observed cadence");
    }

    private static void testRecoveryIsOneTransition() {
        PrimaryTransportMonitor monitor = new PrimaryTransportMonitor();
        monitor.observe(100L, evidence(100L, 100L, 100L, 100L, 0L, 0L));
        monitor.observe(2_000_000_101L, evidence(100L, 100L, 100L, 100L, 0L, 0L));
        PrimaryTransportMonitor.Snapshot recovered = monitor.observe(2_000_000_200L,
                evidence(2_000_000_200L, 2_000_000_200L, 2_000_000_200L, 2_000_000_200L, 0L, 0L));
        require(recovered.recovered && recovered.changed, "recovery transition");
        PrimaryTransportMonitor.Snapshot stable = monitor.observe(2_000_000_300L,
                evidence(2_000_000_300L, 2_000_000_300L, 2_000_000_300L, 2_000_000_300L, 0L, 0L));
        require(!stable.changed, "stable sending does not repeat evidence");
    }

    private static PrimaryTransportMonitor.Evidence evidence(long callback, long accepted, long au,
            long rtp, long rtpFailure, long workerFailure) {
        return new PrimaryTransportMonitor.Evidence(callback, accepted, au, rtp, rtpFailure, workerFailure);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
