package com.dji.sdk.sample.transport.network;

import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.TransportEgressMetrics;
import com.dji.sdk.sample.transport.telemetry.TelemetrySink;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Sends one bounded record per native-cadence sample and never blocks DJI callbacks. */
public final class TelemetryUdpSink implements TelemetrySink, Closeable {
    private final BoundedUdpSender sender;
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final AtomicLong healthSequence = new AtomicLong();

    public TelemetryUdpSink(String host, int port) throws Exception {
        this(host, port, null);
    }

    public TelemetryUdpSink(String host, int port, TransportEgressMetrics egressMetrics) throws Exception {
        sender = new BoundedUdpSender(host, port, 512, "dji-telemetry-udp",
                egressMetrics == null ? null : (bytes, acceptedAtNanos) ->
                        egressMetrics.recordTelemetry(bytes));
    }

    @Override public void onTelemetry(TelemetryEvent event) {
        String key = event.getSource() + ":" + event.getComponentIndex();
        AtomicLong sequence = sequences.get(key);
        if (sequence == null) {
            AtomicLong candidate = new AtomicLong();
            AtomicLong existing = sequences.putIfAbsent(key, candidate);
            sequence = existing == null ? candidate : existing;
        }
        byte[] packet = JsonWireEncoder.telemetry(event, sequence.getAndIncrement());
        if (packet != null) sender.offer(packet);
    }

    public long getDroppedCount() { return sender.getDroppedCount(); }
    public long getErrorCount() { return sender.getErrorCount(); }
    public long getAcceptedDatagramCount() { return sender.getAcceptedDatagramCount(); }
    public long getAcceptedByteCount() { return sender.getAcceptedByteCount(); }
    public BoundedUdpSender.Snapshot diagnostics() { return sender.snapshot(); }
    public byte[] sendHealth(String session, long nowNanos, long primaryVideoDrops,
            long videoSocketErrors, long primaryVideoCallbacks, long primaryAccessUnits,
            long primaryRtpPackets, long primaryRtpBytes, long metadataDatagrams,
            long metadataBytes, long clockPongsSent) {
        byte[] health = JsonWireEncoder.health(session, healthSequence.getAndIncrement(), nowNanos,
                sender.getDroppedCount(), sender.getErrorCount(), primaryVideoDrops,
                videoSocketErrors, primaryVideoCallbacks, primaryAccessUnits, primaryRtpPackets,
                primaryRtpBytes, sender.getAcceptedDatagramCount(), sender.getAcceptedByteCount(),
                metadataDatagrams, metadataBytes, clockPongsSent);
        sender.offer(health);
        return health;
    }
    @Override public void close() { sender.close(); }
}
