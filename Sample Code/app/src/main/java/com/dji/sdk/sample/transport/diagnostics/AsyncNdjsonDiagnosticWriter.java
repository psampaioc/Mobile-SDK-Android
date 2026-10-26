package com.dji.sdk.sample.transport.diagnostics;

import android.os.SystemClock;

import com.dji.sdk.sample.transport.clock.MonotonicClock;
import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.telemetry.TelemetrySink;
import com.dji.sdk.sample.transport.network.VideoStreamSender;
import com.dji.sdk.sample.transport.network.BoundedUdpSender;
import com.dji.sdk.sample.transport.network.RtpSequenceWindowMetrics;
import com.dji.sdk.sample.operate.FlightReadinessSession;
import com.dji.sdk.sample.operate.SessionTimeline;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.transport.telemetry.DjiTelemetryCollector;

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
    static final long MAX_SEGMENT_BYTES = 16L * 1024L * 1024L;
    static final int MAX_SEGMENTS = 4;
    private final ArrayBlockingQueue<Object> queue;
    private final AtomicLong droppedRecords = new AtomicLong();
    private final AtomicLong writtenRecords = new AtomicLong();
    private final File destination;
    private final FileOperations fileOperations;
    private BufferedWriter writer;
    private long currentSegmentBytes;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean workerAlive;
    private volatile long lastWriteElapsedRealtimeNanos;
    private volatile String terminalFailure = "";

    public AsyncNdjsonDiagnosticWriter(File destination, int queueCapacity) throws IOException {
        this(destination, queueCapacity, new FileOperations() {
            @Override
            public boolean delete(File file) {
                return file.delete();
            }

            @Override
            public boolean rename(File source, File target) {
                return source.renameTo(target);
            }
        });
    }

    AsyncNdjsonDiagnosticWriter(File destination, int queueCapacity,
            FileOperations fileOperations) throws IOException {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        if (fileOperations == null) throw new NullPointerException("fileOperations");
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create diagnostic directory: " + parent);
        }
        queue = new ArrayBlockingQueue<>(queueCapacity);
        this.destination = destination;
        this.fileOperations = fileOperations;
        writer = openWriter(destination);
        currentSegmentBytes = destination.length();
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
    public void writeVideoDiagnostics(String sessionId, VideoStreamSender.Diagnostics value,
                                               MonotonicClock clock) {
        if (value == null) return;
        offer(new RawJsonRecord(JsonEncoder.primaryVideoDiagnostics(sessionId, value,
                clock.nowNanos())));
    }

    public long getDroppedRecordCount() { return droppedRecords.get(); }
    public int getQueueSize() { return queue.size(); }

    /** Snapshot makes a missing local pulse distinguishable from a stopped transport writer. */
    public Snapshot snapshot() {
        return new Snapshot(queue.size(), droppedRecords.get(), writtenRecords.get(),
                lastWriteElapsedRealtimeNanos, workerAlive, terminalFailure);
    }

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

    /** Queues small local session evidence; producer threads never write to disk directly. */
    public void writeTimelineEvent(String sessionId, SessionTimeline.Event event) {
        if (sessionId == null || event == null) return;
        offer(new RawJsonRecord(JsonEncoder.sessionTimelineEvent(sessionId, event)));
    }

    /** One payload-free incident record per local Primary state transition. */
    public void writePrimaryIncident(String sessionId, String state, String detail,
            long generatedElapsedRealtimeNanos) {
        offer(new RawJsonRecord(JsonEncoder.primaryTransportIncident(sessionId, state, detail,
                generatedElapsedRealtimeNanos)));
    }

    public void writeTransportPulse(String sessionId, String runtimeState, String endpoint,
            boolean foregroundServiceAlive, long foregroundLastReconcileNanos,
            long wallClockEpochMs, long nowNanos, long tickIntervalNanos,
            VideoStreamSender.Diagnostics primary, long videoMaxOfferNanos,
            long videoMaxSendNanos, RtpSequenceWindowMetrics.Snapshot rtpSequenceWindow,
            BoundedUdpSender.Snapshot telemetry,
            DjiDataHub.VideoDispatchSnapshot hubPrimary,
            DjiTelemetryCollector.FlightDiagnostics flight, Snapshot writerSnapshot) {
        offer(new RawJsonRecord(JsonEncoder.transportPulse(sessionId, runtimeState, endpoint,
                foregroundServiceAlive, foregroundLastReconcileNanos, wallClockEpochMs, nowNanos,
                tickIntervalNanos, primary, videoMaxOfferNanos, videoMaxSendNanos, rtpSequenceWindow, telemetry,
                hubPrimary, flight, writerSnapshot)));
    }

    public void writeTransportTransition(String sessionId, String type, String detail,
            long nowNanos) {
        offer(new RawJsonRecord(JsonEncoder.transportTransition(sessionId, type, detail, nowNanos)));
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
        workerAlive = true;
        try {
            while (running || !queue.isEmpty()) {
                Object record = queue.poll(250, TimeUnit.MILLISECONDS);
                if (record != null) {
                    String line = record instanceof TelemetryEvent
                            ? ((TelemetryEvent) record).toNdjsonLine()
                            : record instanceof RateRecord
                            ? ((RateRecord) record).toNdjsonLine()
                            : ((RawJsonRecord) record).json;
                    rotateIfNeeded(line);
                    writer.write(line);
                    writer.newLine();
                    writtenRecords.incrementAndGet();
                    lastWriteElapsedRealtimeNanos = elapsedRealtimeNanos();
                    currentSegmentBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
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
        } catch (IOException failure) {
            // Stop producers from queueing records after the only disk writer has failed.
            droppedRecords.incrementAndGet();
            terminalFailure = describe(failure);
            running = false;
            droppedRecords.addAndGet(queue.size());
            queue.clear();
        } finally {
            workerAlive = false;
            try { writer.flush(); } catch (IOException ignored) { }
            try { writer.close(); } catch (IOException ignored) { }
        }
    }

    private static String describe(Exception failure) {
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    /** JVM unit tests provide a stub SystemClock; Android runtime always takes the first path. */
    private static long elapsedRealtimeNanos() {
        try {
            return SystemClock.elapsedRealtimeNanos();
        } catch (UnsatisfiedLinkError ignored) {
            return System.nanoTime();
        }
    }

    private BufferedWriter openWriter(File file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8), 64 * 1024);
    }

    private void rotateIfNeeded(String nextLine) throws IOException {
        if (currentSegmentBytes + nextLine.getBytes(StandardCharsets.UTF_8).length + 1L <= MAX_SEGMENT_BYTES) return;
        writer.flush();
        writer.close();
        File oldest = new File(destination.getPath() + "." + MAX_SEGMENTS);
        if (oldest.exists() && !fileOperations.delete(oldest)) {
            throw new IOException("Cannot delete diagnostic segment: " + oldest);
        }
        for (int index = MAX_SEGMENTS - 1; index >= 1; index--) {
            File source = new File(destination.getPath() + "." + index);
            File target = new File(destination.getPath() + "." + (index + 1));
            if (source.exists() && !fileOperations.rename(source, target)) {
                throw new IOException("Cannot rotate diagnostic segment: " + source + " to " + target);
            }
        }
        File firstArchive = new File(destination.getPath() + ".1");
        if (destination.exists() && !fileOperations.rename(destination, firstArchive)) {
            throw new IOException("Cannot rotate diagnostic source: " + destination + " to " + firstArchive);
        }
        writer = openWriter(destination);
        currentSegmentBytes = 0L;
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

    interface FileOperations {
        boolean delete(File file);
        boolean rename(File source, File target);
    }

    public static final class Snapshot {
        public final int queueDepth;
        public final long droppedRecords;
        public final long writtenRecords;
        public final long lastWriteElapsedRealtimeNanos;
        public final boolean workerAlive;
        public final String terminalFailure;

        Snapshot(int queueDepth, long droppedRecords, long writtenRecords,
                long lastWriteElapsedRealtimeNanos, boolean workerAlive, String terminalFailure) {
            this.queueDepth = queueDepth;
            this.droppedRecords = droppedRecords;
            this.writtenRecords = writtenRecords;
            this.lastWriteElapsedRealtimeNanos = lastWriteElapsedRealtimeNanos;
            this.workerAlive = workerAlive;
            this.terminalFailure = terminalFailure;
        }
    }
}
