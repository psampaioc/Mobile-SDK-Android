package com.dji.sdk.sample.transport.network;

/** Bounded, payload-free evidence for one feed's UDP send path. */
public final class VideoSocketDiagnostics {
    private long metadataAttempts;
    private long rtpAttempts;
    private long metadataAccepted;
    private long rtpAccepted;
    private long metadataAcceptedBytes;
    private long rtpAcceptedBytes;
    private long metadataErrors;
    private long rtpErrors;
    private long lastMetadataAcceptedNanos;
    private long lastRtpAcceptedNanos;
    private long lastMetadataFailureNanos;
    private long lastRtpFailureNanos;
    private long lastSendDurationNanos;
    private long maxSendDurationNanos;
    private String lastMetadataError = "";
    private String lastRtpError = "";

    public synchronized void recordAccepted(boolean metadata, int bytes, long nowNanos, long durationNanos) {
        if (metadata) {
            metadataAttempts++;
            metadataAccepted++;
            metadataAcceptedBytes += Math.max(0, bytes);
            lastMetadataAcceptedNanos = nowNanos;
        } else {
            rtpAttempts++;
            rtpAccepted++;
            rtpAcceptedBytes += Math.max(0, bytes);
            lastRtpAcceptedNanos = nowNanos;
        }
        lastSendDurationNanos = Math.max(0L, durationNanos);
        maxSendDurationNanos = Math.max(maxSendDurationNanos, lastSendDurationNanos);
    }

    public synchronized void recordFailure(boolean metadata, long nowNanos, Exception error) {
        String value = error == null ? "unknown" : error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        if (metadata) {
            metadataAttempts++; metadataErrors++; lastMetadataError = value;
            lastMetadataFailureNanos = nowNanos;
        } else {
            rtpAttempts++; rtpErrors++; lastRtpError = value;
            lastRtpFailureNanos = nowNanos;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(metadataAttempts, rtpAttempts, metadataAccepted, rtpAccepted,
                metadataAcceptedBytes, rtpAcceptedBytes,
                metadataErrors, rtpErrors, lastMetadataAcceptedNanos, lastRtpAcceptedNanos,
                lastMetadataFailureNanos, lastRtpFailureNanos, lastSendDurationNanos,
                lastMetadataError, lastRtpError);
    }
    /** One-second consumer drains this rolling maximum without changing wire data. */
    public synchronized long drainMaxSendDurationNanos() {
        long value = maxSendDurationNanos;
        maxSendDurationNanos = 0L;
        return value;
    }

    public static final class Snapshot {
        public final long metadataAttempts;
        public final long rtpAttempts;
        public final long metadataAccepted;
        public final long rtpAccepted;
        public final long metadataAcceptedBytes;
        public final long rtpAcceptedBytes;
        public final long metadataErrors;
        public final long rtpErrors;
        public final long lastMetadataAcceptedNanos;
        public final long lastRtpAcceptedNanos;
        public final long lastMetadataFailureNanos;
        public final long lastRtpFailureNanos;
        public final long lastSendDurationNanos;
        public final String lastMetadataError;
        public final String lastRtpError;
        Snapshot(long metadataAttempts, long rtpAttempts, long metadataAccepted, long rtpAccepted,
                long metadataAcceptedBytes, long rtpAcceptedBytes,
                long metadataErrors, long rtpErrors, long lastMetadataAcceptedNanos,
                long lastRtpAcceptedNanos, long lastMetadataFailureNanos, long lastRtpFailureNanos,
                long lastSendDurationNanos,
                String lastMetadataError, String lastRtpError) {
            this.metadataAttempts = metadataAttempts; this.rtpAttempts = rtpAttempts;
            this.metadataAccepted = metadataAccepted; this.rtpAccepted = rtpAccepted;
            this.metadataAcceptedBytes = metadataAcceptedBytes; this.rtpAcceptedBytes = rtpAcceptedBytes;
            this.metadataErrors = metadataErrors; this.rtpErrors = rtpErrors;
            this.lastMetadataAcceptedNanos = lastMetadataAcceptedNanos;
            this.lastRtpAcceptedNanos = lastRtpAcceptedNanos;
            this.lastMetadataFailureNanos = lastMetadataFailureNanos;
            this.lastRtpFailureNanos = lastRtpFailureNanos;
            this.lastSendDurationNanos = lastSendDurationNanos;
            this.lastMetadataError = lastMetadataError; this.lastRtpError = lastRtpError;
        }
    }
}
