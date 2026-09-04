package com.dji.sdk.sample.transport.diagnostics;

import com.dji.sdk.sample.transport.clock.MonotonicClock;
import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.telemetry.TelemetrySink;
import com.dji.sdk.sample.transport.network.VideoStreamSender;
import com.dji.sdk.sample.operate.FlightReadinessSession;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Non-blocking bounded NDJSON diagnostics. Overflow drops the oldest queued record. */
public final class AsyncNdjsonDiagnosticWriter implements TelemetrySink, Closeable {
    private final ArrayBlockingQueue<Object> queue;
    private final AtomicLong droppedRecords = new AtomicLong();
    private final BufferedWriter writer;
    private final Thread worker;
    private volatile boolean running = true;

    public AsyncNdjsonDiagnosticWriter(File destination, int queueCapacity) throws IOException {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create diagnostic directory: " + parent);
        }
        queue = new ArrayBlockingQueue<>(queueCapacity);
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(destination, true), StandardCharsets.UTF_8), 64 * 1024);
        worker = new Thread(this::writeLoop, "dji-telemetry-ndjson");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void onTelemetry(TelemetryEvent event) {
        offer(event);
    }

    public void writeRateSnapshots(String sessionId, List<CallbackRateSnapshot> snapshots,
                                   MonotonicClock clock) {
        long now = clock.nowNanos();
        for (CallbackRateSnapshot snapshot : snapshots) {
            offer(new RateRecord(sessionId, snapshot, now));
        }
    }

    /** Full parser diagnostics stay in the local bounded diagnostic stream, not health UDP. */
    public void writeSecondaryVideoDiagnostics(String sessionId, VideoStreamSender.Diagnostics value,
                                               MonotonicClock clock) {
        if (value == null) return;
        offer(new RawJsonRecord(JsonEncoder.secondaryVideoDiagnostics(sessionId, value,
                clock.nowNanos())));
    }

    public long getDroppedRecordCount() { return droppedRecords.get(); }
    public int getQueueSize() { return queue.size(); }

    /** Adds an already encoded bounded JSON health record without blocking its producer. */
    public void writeJsonRecord(byte[] json) {
        if (json == null) throw new NullPointerException("json");
        offer(new RawJsonRecord(new String(json, StandardCharsets.UTF_8)));
    }

    /** Small operator-readiness audit record; it is bounded by this writer's existing queue. */
    public void writeReadinessEvent(String sessionId, FlightReadinessSession.Event event) {
        if (sessionId == null || event == null) return;
        String item = event.getItem() == null ? "" : event.getItem().name();
        offer(new RawJsonRecord("{\"schema_version\":1,\"kind\":\"flight_readiness\",\"session_id\":\""
                + sessionId + "\",\"event\":\"" + event.getType() + "\",\"item\":\""
                + item + "\",\"android_elapsed_ms\":" + event.getElapsedMs() + "}"));
    }

    private void offer(Object record) {
        if (!running) return;
        if (!queue.offer(record)) {
            queue.poll();
            droppedRecords.incrementAndGet();
            if (!queue.offer(record)) droppedRecords.incrementAndGet();
        }
    }

    private void writeLoop() {
        int sinceFlush = 0;
        try {
            while (running || !queue.isEmpty()) {
                Object record = queue.poll(250, TimeUnit.MILLISECONDS);
                if (record != null) {
                    String line = record instanceof TelemetryEvent
                            ? ((TelemetryEvent) record).toNdjsonLine()
                            : record instanceof RateRecord
                            ? ((RateRecord) record).toNdjsonLine()
                            : ((RawJsonRecord) record).json;
                    writer.write(line);
                    writer.newLine();
                    if (++sinceFlush >= 32) {
                        writer.flush();
                        sinceFlush = 0;
                    }
                } else if (sinceFlush > 0) {
                    writer.flush();
                    sinceFlush = 0;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The dropped/error count is intentionally observable without logging from this worker.
            droppedRecords.incrementAndGet();
        } finally {
            try { writer.flush(); } catch (IOException ignored) { }
            try { writer.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing diagnostic writer", e);
        }
        if (worker.isAlive()) throw new IOException("Diagnostic writer did not stop cleanly");
    }

    private static final class RateRecord {
        private final String sessionId;
        private final CallbackRateSnapshot snapshot;
        private final long generatedNanos;

        private RateRecord(String sessionId, CallbackRateSnapshot snapshot, long generatedNanos) {
            this.sessionId = sessionId;
            this.snapshot = snapshot;
            this.generatedNanos = generatedNanos;
        }

        private String toNdjsonLine() {
            return JsonEncoder.rateSnapshot(sessionId, snapshot, generatedNanos);
        }
    }

    private static final class RawJsonRecord {
        private final String json;
        private RawJsonRecord(String json) { this.json = json; }
    }
}
