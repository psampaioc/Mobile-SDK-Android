package com.dji.sdk.sample.transport;

import android.content.Context;

import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.operate.FlightReadinessStore;
import com.dji.sdk.sample.transport.clock.AndroidMonotonicClock;
import com.dji.sdk.sample.transport.clock.MonotonicClock;
import com.dji.sdk.sample.transport.diagnostics.AsyncNdjsonDiagnosticWriter;
import com.dji.sdk.sample.transport.diagnostics.CallbackRateRegistry;
import com.dji.sdk.sample.transport.diagnostics.CallbackRateSnapshot;
import com.dji.sdk.sample.transport.network.AndroidClockResponder;
import com.dji.sdk.sample.transport.network.TelemetryUdpSink;
import com.dji.sdk.sample.transport.network.TransportConfig;
import com.dji.sdk.sample.transport.network.VideoStreamSender;
import com.dji.sdk.sample.transport.telemetry.CompositeTelemetrySink;
import com.dji.sdk.sample.transport.telemetry.DjiTelemetryCollector;

import java.io.Closeable;
import java.io.File;
import java.util.UUID;
import java.util.List;

import dji.sdk.products.Aircraft;

/** Passive, explicitly started transport lifecycle. Contains no aircraft command API. */
public final class TransportRuntime implements Closeable {
    private final String sessionId = UUID.randomUUID().toString();
    private final MonotonicClock clock = new AndroidMonotonicClock();
    private final CallbackRateRegistry rates = new CallbackRateRegistry(512);
    private TelemetryUdpSink telemetryNetwork;
    private AsyncNdjsonDiagnosticWriter diagnostics;
    private DjiTelemetryCollector collector;
    private VideoStreamSender primary;
    private VideoStreamSender secondary;
    private AndroidClockResponder clockResponder;
    private CallbackMulticaster.Subscription hubSubscription;
    private CallbackMulticaster.Subscription readinessAuditSubscription;
    private DjiDataHub hub;
    private Thread rateWriter;
    private Aircraft aircraft;
    private volatile boolean running = true;

    public TransportRuntime(Context context, Aircraft aircraft, TransportConfig config) throws Exception {
        this.aircraft = aircraft;
        try {
            telemetryNetwork = new TelemetryUdpSink(config.edgeHost, config.telemetryPort);
            File directory = context.getExternalFilesDir("transport");
            if (directory == null) directory = new File(context.getFilesDir(), "transport");
            File sessionDirectory = new File(directory, sessionId);
            File output = new File(sessionDirectory, "transport.ndjson");
            diagnostics = new AsyncNdjsonDiagnosticWriter(output, 1024);
            collector = new DjiTelemetryCollector(sessionId, clock,
                    new CompositeTelemetrySink(telemetryNetwork, diagnostics), rates);
            primary = new VideoStreamSender(sessionId, "primary", clock, config.edgeHost,
                    config.primaryRtpPort, config.metadataPort, randomSsrc());
            secondary = new VideoStreamSender(sessionId, "secondary", clock, config.edgeHost,
                    config.secondaryRtpPort, config.metadataPort, randomSsrc());
            clockResponder = new AndroidClockResponder(clock, config.clockPort);
            hub = DjiDataHub.getInstance();
            primary.setPhysicalSource(hub.getSource(DjiDataHub.Feed.PRIMARY));
            secondary.setPhysicalSource(hub.getSource(DjiDataHub.Feed.SECONDARY));
            hubSubscription = hub.addListener(new DjiDataHub.Listener() {
                @Override public void onVideo(DjiDataHub.Feed feed, byte[] data, int size,
                        String source) {
                    if (feed == DjiDataHub.Feed.PRIMARY) {
                        primary.offer(data, size);
                    } else {
                        secondary.offer(data, size);
                    }
                }
                @Override public void onPhysicalSource(DjiDataHub.Feed feed,
                        dji.common.airlink.PhysicalSource source) {
                    if (feed == DjiDataHub.Feed.PRIMARY) primary.setPhysicalSource(source.name());
                    else secondary.setPhysicalSource(source.name());
                }
            });
            FlightReadinessStore readinessStore = FlightReadinessStore.getInstance();
            readinessStore.start();
            readinessAuditSubscription = readinessStore.addAuditListener(event ->
                    diagnostics.writeReadinessEvent(sessionId, event));
            collector.start(hub);
            rateWriter = new Thread(this::writeRates, "dji-callback-rates");
            rateWriter.setDaemon(true);
            rateWriter.start();
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private void writeRates() {
        int localDiagnosticTicks = 0;
        while (running) {
            try { Thread.sleep(1_000); }
            catch (InterruptedException e) { if (!running) return; }
            byte[] health = telemetryNetwork.sendHealth(sessionId, clock.nowNanos(), getPrimaryVideoDrops(),
                    getSecondaryVideoDrops(), getVideoSocketErrors(),
                    primary.getCallbackCount(), secondary.getCallbackCount(),
                    primary.getAccessUnitCount(), secondary.getAccessUnitCount(),
                    primary.getRtpPacketCount(), secondary.getRtpPacketCount());
            diagnostics.writeJsonRecord(health);
            if (++localDiagnosticTicks >= 10) {
                localDiagnosticTicks = 0;
                List<CallbackRateSnapshot> snapshots = rates.snapshots();
                diagnostics.writeRateSnapshots(sessionId, snapshots, clock);
                diagnostics.writeSecondaryVideoDiagnostics(sessionId, secondary.diagnostics(), clock);
            }
        }
    }

    private static int randomSsrc() {
        int value = UUID.randomUUID().hashCode();
        return value == 0 ? 1 : value;
    }

    public String getSessionId() { return sessionId; }
    public CallbackRateRegistry getRates() { return rates; }
    public long getTelemetryDrops() { return telemetryNetwork.getDroppedCount(); }
    public long getPrimaryVideoDrops() {
        return primary.getPipeline().ingestionCounters().droppedChunks;
    }
    public long getSecondaryVideoDrops() {
        return secondary.getPipeline().ingestionCounters().droppedChunks;
    }
    public long getVideoSocketErrors() {
        return primary.getSocketErrorCount() + secondary.getSocketErrorCount();
    }

    @Override public void close() {
        if (!running) return;
        running = false;
        if (rateWriter != null) rateWriter.interrupt();
        if (collector != null) collector.stop();
        if (diagnostics != null) diagnostics.writeRateSnapshots(sessionId, rates.snapshots(), clock);
        if (hubSubscription != null) hubSubscription.close();
        if (readinessAuditSubscription != null) readinessAuditSubscription.close();
        if (clockResponder != null) clockResponder.close();
        if (primary != null) primary.close();
        if (secondary != null) secondary.close();
        if (telemetryNetwork != null) telemetryNetwork.close();
        if (diagnostics != null) try { diagnostics.close(); } catch (Exception ignored) { }
    }
}
