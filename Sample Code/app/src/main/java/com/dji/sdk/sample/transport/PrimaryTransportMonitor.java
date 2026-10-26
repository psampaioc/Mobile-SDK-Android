package com.dji.sdk.sample.transport;

import com.dji.sdk.sample.transport.network.VideoStreamSender;

/**
 * Classifies Android-local Primary progress. It never treats an accepted Android UDP datagram as
 * proof that Edge received it. State uses monotonic stage timestamps, never stale total counters.
 */
final class PrimaryTransportMonitor {
    private static final long MINIMUM_OUTAGE_NANOS = 2_000_000_000L;
    private boolean hasEverSent;
    private boolean outageOpen;
    private String previousState = "WAITING_FOR_DJI";

    Snapshot observe(long nowNanos, VideoStreamSender.Diagnostics diagnostics) {
        return observe(nowNanos, Evidence.from(diagnostics));
    }

    Snapshot observe(long nowNanos, Evidence value) {
        long threshold = Math.max(MINIMUM_OUTAGE_NANOS, value.medianAccessUnitIntervalNanos * 10L);
        if (value.lastCallbackNanos == 0L) {
            return transition("WAITING_FOR_DJI", "Waiting for Primary callbacks", false);
        }
        if (age(nowNanos, value.lastCallbackNanos) >= threshold) {
            return hasEverSent
                    ? transition("OUTAGE", "DJI_CALLBACK_STALLED", true)
                    : transition("WAITING_FOR_DJI", "Waiting for Primary callbacks", false);
        }
        if (age(nowNanos, value.lastAcceptedChunkNanos) >= threshold) {
            return transition("OUTAGE", "INGEST_DROPPING", true);
        }
        if (value.lastAccessUnitNanos == 0L || age(nowNanos, value.lastAccessUnitNanos) >= threshold) {
            if (value.lastWorkerFailureNanos > value.lastAccessUnitNanos) {
                return transition("OUTAGE", "ANDROID_WORKER_STALLED_OR_FAILED", true);
            }
            return hasEverSent
                    ? transition("OUTAGE", "PARSER_OR_AU_STALLED", true)
                    : transition("PARSING", "WAITING_FOR_H264_KEYFRAME_OR_AU", false);
        }
        if (age(nowNanos, value.lastRtpAcceptedNanos) >= threshold) {
            if (value.lastRtpFailureNanos >= value.lastRtpAcceptedNanos) {
                return transition("OUTAGE", "ANDROID_SOCKET_SEND_FAILED", true);
            }
            return transition("OUTAGE", "ANDROID_RTP_SEND_STALLED", true);
        }
        hasEverSent = true;
        return transition("SENDING", "Primary RTP accepted locally", false);
    }

    private static long age(long nowNanos, long stageNanos) {
        return stageNanos <= 0L ? Long.MAX_VALUE : Math.max(0L, nowNanos - stageNanos);
    }

    private Snapshot transition(String state, String detail, boolean outage) {
        boolean changed = !state.equals(previousState) || (outage && !outageOpen);
        boolean recovered = outageOpen && !outage;
        outageOpen = outage;
        previousState = state;
        return new Snapshot(state, detail, changed, recovered, outage);
    }

    static final class Evidence {
        final long lastCallbackNanos;
        final long lastAcceptedChunkNanos;
        final long lastAccessUnitNanos;
        final long lastRtpAcceptedNanos;
        final long lastRtpFailureNanos;
        final long lastWorkerFailureNanos;
        final long medianAccessUnitIntervalNanos;

        Evidence(long lastCallbackNanos, long lastAcceptedChunkNanos, long lastAccessUnitNanos,
                long lastRtpAcceptedNanos, long lastRtpFailureNanos, long lastWorkerFailureNanos) {
            this(lastCallbackNanos, lastAcceptedChunkNanos, lastAccessUnitNanos, lastRtpAcceptedNanos,
                    lastRtpFailureNanos, lastWorkerFailureNanos, 0L);
        }

        Evidence(long lastCallbackNanos, long lastAcceptedChunkNanos, long lastAccessUnitNanos,
                long lastRtpAcceptedNanos, long lastRtpFailureNanos, long lastWorkerFailureNanos,
                long medianAccessUnitIntervalNanos) {
            this.lastCallbackNanos = lastCallbackNanos;
            this.lastAcceptedChunkNanos = lastAcceptedChunkNanos;
            this.lastAccessUnitNanos = lastAccessUnitNanos;
            this.lastRtpAcceptedNanos = lastRtpAcceptedNanos;
            this.lastRtpFailureNanos = lastRtpFailureNanos;
            this.lastWorkerFailureNanos = lastWorkerFailureNanos;
            this.medianAccessUnitIntervalNanos = medianAccessUnitIntervalNanos;
        }

        static Evidence from(VideoStreamSender.Diagnostics value) {
            return new Evidence(value.lastCallbackNanos, value.lastAcceptedChunkNanos,
                    value.lastAccessUnitNanos, value.socket.lastRtpAcceptedNanos,
                    value.socket.lastRtpFailureNanos, value.lastWorkerFailureNanos,
                    value.medianAccessUnitIntervalNanos);
        }
    }

    static final class Snapshot {
        final String state;
        final String detail;
        final boolean changed;
        final boolean recovered;
        final boolean outage;

        Snapshot(String state, String detail, boolean changed, boolean recovered, boolean outage) {
            this.state = state;
            this.detail = detail;
            this.changed = changed;
            this.recovered = recovered;
            this.outage = outage;
        }
    }
}
