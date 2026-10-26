package com.dji.sdk.sample.transport;

import android.content.Context;

import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.operate.FlightReadinessStore;
import com.dji.sdk.sample.operate.SessionTimelineStore;
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
import com.dji.sdk.sample.transport.video.PrimaryLiveFeedMetrics;

import java.io.Closeable;
import java.io.File;
import java.util.UUID;
import java.util.List;

import dji.sdk.products.Aircraft;

/** Passive, explicitly started transport lifecycle. Contains no aircraft command API. */
public final class TransportRuntime implements Closeable {
    private final String sessionId = UUID.randomUUID().toString();
    private final String endpoint;
    private final MonotonicClock clock = new AndroidMonotonicClock();
    private final CallbackRateRegistry rates = new CallbackRateRegistry(512);
    private TelemetryUdpSink telemetryNetwork;
    private AsyncNdjsonDiagnosticWriter diagnostics;
    private DjiTelemetryCollector collector;
    private VideoStreamSender primary;
    private AndroidClockResponder clockResponder;
    private CallbackMulticaster.Subscription hubSubscription;
    private DjiDataHub.PrimaryVideoSubscription primaryVideoSubscription;
    private CallbackMulticaster.Subscription readinessAuditSubscription;
    private CallbackMulticaster.Subscription timelineSubscription;
    private DjiDataHub hub;
    private Thread rateWriter;
    private Aircraft aircraft;
    private volatile boolean running = true;
    private Boolean lastTransportDegraded;
    private final PrimaryTransportMonitor primaryMonitor = new PrimaryTransportMonitor();
    private final TransportEgressMetrics egressMetrics = new TransportEgressMetrics();
    private volatile PrimaryTransportMonitor.Snapshot primaryStatus;
    private volatile TransportEgressMetrics.Snapshot egressSnapshot;
    private long lastHealthTickNanos;
    private boolean primaryCallbackStale;
    private boolean telemetrySenderStale;

    public TransportRuntime(Context context, Aircraft aircraft, TransportConfig config) throws Exception {
        this.aircraft = aircraft;
        this.endpoint = config.edgeHost;
        try {
            telemetryNetwork = new TelemetryUdpSink(config.edgeHost, config.telemetryPort, egressMetrics);
            File directory = context.getExternalFilesDir("transport");
            if (directory == null) directory = new File(context.getFilesDir(), "transport");
            File sessionDirectory = new File(directory, sessionId);
            File output = new File(sessionDirectory, "transport.ndjson");
            diagnostics = new AsyncNdjsonDiagnosticWriter(output, 1024);
            diagnostics.writeTransportTransition(sessionId, "RUNTIME_CREATED", endpoint,
                    clock.nowNanos());
            timelineSubscription = SessionTimelineStore.addListener(event ->
                    diagnostics.writeTimelineEvent(sessionId, event));
            SessionTimelineStore.android("TRANSPORT_STARTED", "Local transport diagnostics started");
            collector = new DjiTelemetryCollector(sessionId, clock,
                    new CompositeTelemetrySink(telemetryNetwork, diagnostics), rates);
            primary = new VideoStreamSender(sessionId, "primary", clock, config.edgeHost,
                    config.primaryRtpPort, config.metadataPort, randomSsrc(), egressMetrics);
            clockResponder = new AndroidClockResponder(clock, config.clockPort, egressMetrics);
            egressSnapshot = egressMetrics.snapshot(clock.nowNanos());
            hub = DjiDataHub.getInstance();
            primary.setPhysicalSource(hub.getSource(DjiDataHub.Feed.PRIMARY));
            hubSubscription = hub.addListener(new DjiDataHub.Listener() {
                @Override public void onPhysicalSource(DjiDataHub.Feed feed,
                        dji.common.airlink.PhysicalSource source) {
                    if (feed == DjiDataHub.Feed.PRIMARY) primary.setPhysicalSource(source.name());
                }
                @Override public void onVideoBinding(DjiDataHub.Feed feed, boolean attached) {
                    if (feed == DjiDataHub.Feed.PRIMARY) diagnostics.writeTransportTransition(sessionId,
                            attached ? "DJI_PRIMARY_BINDING_ATTACHED" : "DJI_PRIMARY_BINDING_DETACHED",
                            endpoint, clock.nowNanos());
                }
            });
            primaryVideoSubscription = hub.addPrimaryVideoListener(new DjiDataHub.PrimaryVideoListener() {
                @Override public void onVideo(byte[] data, int size, String source) {
                    primary.offer(data, size);
                }

                @Override public void onDiscontinuity() { primary.signalDiscontinuity(); }
            });
            diagnostics.writeTransportTransition(sessionId, "DJI_PRIMARY_BINDING_INITIAL_STATE",
                    "available=" + hub.isAvailable(DjiDataHub.Feed.PRIMARY) + ", source="
                            + hub.getSource(DjiDataHub.Feed.PRIMARY), clock.nowNanos());
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
            long now = clock.nowNanos();
            long tickInterval = lastHealthTickNanos == 0L ? -1L : Math.max(0L, now - lastHealthTickNanos);
            lastHealthTickNanos = now;
            VideoStreamSender.Diagnostics primaryDiagnostics = primary.diagnostics();
            com.dji.sdk.sample.transport.network.VideoSocketDiagnostics.Snapshot videoSocket =
                    primaryDiagnostics.socket;
            byte[] health = telemetryNetwork.sendHealth(sessionId, now, getPrimaryVideoDrops(),
                    getVideoSocketErrors(), primary.getCallbackCount(), primary.getAccessUnitCount(),
                    primary.getRtpPacketCount(), videoSocket.rtpAcceptedBytes,
                    videoSocket.metadataAccepted, videoSocket.metadataAcceptedBytes,
                    clockResponder == null ? 0L : clockResponder.getPongsSent());
            diagnostics.writeJsonRecord(health);
            egressSnapshot = egressMetrics.snapshot(now);
            com.dji.sdk.sample.transport.network.BoundedUdpSender.Snapshot telemetryDiagnostics =
                    telemetryNetwork.diagnostics();
            com.dji.sdk.sample.transport.network.RtpSequenceWindowMetrics.Snapshot rtpSequenceWindow =
                    primary.drainRtpSequenceWindow();
            diagnostics.writeTransportPulse(sessionId, "RUNNING", endpoint,
                    TransportForegroundService.isAlive(),
                    TransportForegroundService.getLastReconcileElapsedRealtimeNanos(),
                    System.currentTimeMillis(), now, tickInterval, primaryDiagnostics,
                    primary.drainMaxOfferDurationNanos(), primary.drainMaxSocketSendDurationNanos(),
                    rtpSequenceWindow, telemetryDiagnostics, hub.getPrimaryVideoDispatchSnapshot(),
                    collector.flightDiagnostics(), diagnostics.snapshot());
            recordStaleTransitions(now, primaryDiagnostics, telemetryDiagnostics);
            updatePrimaryStatus();
            recordTransportHealthTransition();
            if (++localDiagnosticTicks >= 10) {
                localDiagnosticTicks = 0;
                List<CallbackRateSnapshot> snapshots = rates.snapshots();
                diagnostics.writeRateSnapshots(sessionId, snapshots, clock);
                diagnostics.writeVideoDiagnostics(sessionId, primary.diagnostics(), clock);
            }
        }
    }

    private void recordStaleTransitions(long nowNanos, VideoStreamSender.Diagnostics primaryDiagnostics,
            com.dji.sdk.sample.transport.network.BoundedUdpSender.Snapshot telemetryDiagnostics) {
        boolean callbackStale = primaryDiagnostics.lastCallbackNanos > 0L
                && nowNanos - primaryDiagnostics.lastCallbackNanos >= 2_000_000_000L;
        if (callbackStale != primaryCallbackStale) {
            diagnostics.writeTransportTransition(sessionId,
                    callbackStale ? "PRIMARY_TRANSPORT_LISTENER_STALE"
                            : "PRIMARY_TRANSPORT_LISTENER_RECOVERED",
                    callbackStale ? "no Primary callback reached the transport listener for at least 2s"
                            : "Primary callbacks reached the transport listener again",
                    nowNanos);
            primaryCallbackStale = callbackStale;
        }
        boolean telemetryStale = telemetryDiagnostics.lastAcceptedSendNanos > 0L
                && nowNanos - telemetryDiagnostics.lastAcceptedSendNanos >= 2_000_000_000L;
        if (telemetryStale != telemetrySenderStale) {
            diagnostics.writeTransportTransition(sessionId,
                    telemetryStale ? "TELEMETRY_SENDER_STALE" : "TELEMETRY_SENDER_RECOVERED",
                    telemetryStale ? "no local telemetry UDP acceptance for at least 2s"
                            : "local telemetry UDP acceptance resumed", nowNanos);
            telemetrySenderStale = telemetryStale;
        }
    }

    private void updatePrimaryStatus() {
        PrimaryTransportMonitor.Snapshot next = primaryMonitor.observe(clock.nowNanos(), primary.diagnostics());
        primaryStatus = next;
        if (next.changed) diagnostics.writePrimaryIncident(sessionId, next.state, next.detail,
                clock.nowNanos());
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
    public long getVideoSocketErrors() {
        return primary.getSocketErrorCount();
    }
    public VideoStreamSender.Diagnostics getPrimaryDiagnostics() { return primary.diagnostics(); }
    /** Completed-access-unit cadence; this is an Android-local observation, not a camera promise. */
    public PrimaryLiveFeedMetrics.Snapshot getPrimaryLiveFeedSnapshot() {
        return primary.getLiveFeedSnapshot();
    }
    public TransportEgressMetrics.Snapshot getEgressSnapshot() {
        TransportEgressMetrics.Snapshot value = egressSnapshot;
        return value == null ? egressMetrics.snapshot(clock.nowNanos()) : value;
    }
    public String getPrimaryTransportState() {
        PrimaryTransportMonitor.Snapshot value = primaryStatus;
        return value == null ? "STARTING" : value.state;
    }
    public String getPrimaryTransportDetail() {
        PrimaryTransportMonitor.Snapshot value = primaryStatus;
        return value == null ? "Starting local transport" : value.detail;
    }

    /** Records only a change, so one transient transport fault cannot flood the local timeline. */
    private void recordTransportHealthTransition() {
        boolean degraded = getTelemetryDrops() > 0 || telemetryNetwork.getErrorCount() > 0
                || getPrimaryVideoDrops() > 0 || getVideoSocketErrors() > 0;
        if (lastTransportDegraded == null || degraded != lastTransportDegraded) {
            SessionTimelineStore.android("TRANSPORT_HEALTH", degraded
                    ? "Transport degraded: local drops or socket errors observed"
                    : "Transport healthy: no local drops or socket errors observed");
            lastTransportDegraded = degraded;
        }
    }

    @Override public void close() {
        if (!running) return;
        if (diagnostics != null) diagnostics.writeTransportTransition(sessionId, "RUNTIME_CLOSING",
                endpoint, clock.nowNanos());
        SessionTimelineStore.android("TRANSPORT_STOPPING", "Local transport diagnostics stopping");
        running = false;
        if (rateWriter != null) rateWriter.interrupt();
        if (collector != null) collector.stop();
        if (diagnostics != null) diagnostics.writeRateSnapshots(sessionId, rates.snapshots(), clock);
        if (hubSubscription != null) hubSubscription.close();
        if (primaryVideoSubscription != null) primaryVideoSubscription.close();
        if (readinessAuditSubscription != null) readinessAuditSubscription.close();
        if (timelineSubscription != null) timelineSubscription.close();
        if (clockResponder != null) clockResponder.close();
        if (primary != null) primary.close();
        if (telemetryNetwork != null) telemetryNetwork.close();
        if (diagnostics != null) try { diagnostics.close(); } catch (Exception ignored) { }
    }
}
