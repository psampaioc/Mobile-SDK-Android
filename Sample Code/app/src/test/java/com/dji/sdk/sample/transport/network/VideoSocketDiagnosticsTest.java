package com.dji.sdk.sample.transport.network;

/** Dependency-free coverage for bounded, payload-free video socket evidence. */
public final class VideoSocketDiagnosticsTest {
    private VideoSocketDiagnosticsTest() { }

    public static void run() {
        VideoSocketDiagnostics diagnostics = new VideoSocketDiagnostics();
        diagnostics.recordFailure(true, 11L, new java.net.SocketException("Network is unreachable"));
        diagnostics.recordFailure(false, 12L, new IllegalStateException("socket closed"));
        diagnostics.recordFailure(false, 13L, new IllegalStateException("socket closed again"));
        diagnostics.recordAccepted(false, 1200, 42L, 3L);
        VideoSocketDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        require(snapshot.metadataErrors == 1L, "metadata count");
        require(snapshot.rtpErrors == 2L, "rtp count");
        require(snapshot.rtpAttempts == 3L, "rtp attempts include success and failures");
        require(snapshot.rtpAccepted == 1L, "only accepted RTP is counted as sent");
        require(snapshot.rtpAcceptedBytes == 1200L, "accepted RTP bytes");
        require(snapshot.lastRtpAcceptedNanos == 42L, "accepted RTP timestamp");
        require(snapshot.lastRtpFailureNanos == 13L, "latest RTP failure timestamp");
        require(snapshot.lastMetadataError.contains("SocketException"), "metadata class");
        require(snapshot.lastMetadataError.contains("Network is unreachable"), "metadata message");
        require(snapshot.lastRtpError.contains("socket closed again"), "latest RTP error");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
