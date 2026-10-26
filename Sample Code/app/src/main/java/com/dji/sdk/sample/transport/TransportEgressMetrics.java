package com.dji.sdk.sample.transport;

import com.dji.sdk.sample.transport.metrics.ByteRateMeter;

import java.util.concurrent.atomic.AtomicLong;

/** Process-wide Android socket-accepted egress accounting; it is not an Edge receipt signal. */
public final class TransportEgressMetrics {
    private final AtomicLong primaryRtpAcceptedBytes = new AtomicLong();
    private final AtomicLong metadataAcceptedBytes = new AtomicLong();
    private final AtomicLong telemetryAcceptedBytes = new AtomicLong();
    private final AtomicLong clockAcceptedBytes = new AtomicLong();
    private final ByteRateMeter primaryRtpRate = new ByteRateMeter();
    private final ByteRateMeter totalRate = new ByteRateMeter();

    public void recordPrimaryRtp(long bytes) { record(primaryRtpAcceptedBytes, bytes); }
    public void recordMetadata(long bytes) { record(metadataAcceptedBytes, bytes); }
    public void recordTelemetry(long bytes) { record(telemetryAcceptedBytes, bytes); }
    public void recordClock(long bytes) { record(clockAcceptedBytes, bytes); }

    private static void record(AtomicLong target, long bytes) {
        if (bytes > 0L) target.addAndGet(bytes);
    }

    /** Called by the runtime's periodic writer, never by a view or a DJI callback. */
    public synchronized Snapshot snapshot(long nowNanos) {
        long primaryRtp = primaryRtpAcceptedBytes.get();
        long metadata = metadataAcceptedBytes.get();
        long telemetry = telemetryAcceptedBytes.get();
        long clock = clockAcceptedBytes.get();
        long total = primaryRtp + metadata + telemetry + clock;
        return new Snapshot(primaryRtp, metadata, telemetry, clock, total,
                primaryRtpRate.sample(primaryRtp, nowNanos), totalRate.sample(total, nowNanos));
    }

    public static final class Snapshot {
        public final long primaryRtpAcceptedBytes;
        public final long metadataAcceptedBytes;
        public final long telemetryAcceptedBytes;
        public final long clockAcceptedBytes;
        public final long totalAcceptedBytes;
        public final ByteRateMeter.Snapshot primaryRtpRate;
        public final ByteRateMeter.Snapshot totalRate;

        Snapshot(long primaryRtpAcceptedBytes, long metadataAcceptedBytes,
                long telemetryAcceptedBytes, long clockAcceptedBytes, long totalAcceptedBytes,
                ByteRateMeter.Snapshot primaryRtpRate, ByteRateMeter.Snapshot totalRate) {
            this.primaryRtpAcceptedBytes = primaryRtpAcceptedBytes;
            this.metadataAcceptedBytes = metadataAcceptedBytes;
            this.telemetryAcceptedBytes = telemetryAcceptedBytes;
            this.clockAcceptedBytes = clockAcceptedBytes;
            this.totalAcceptedBytes = totalAcceptedBytes;
            this.primaryRtpRate = primaryRtpRate;
            this.totalRate = totalRate;
        }
    }
}
