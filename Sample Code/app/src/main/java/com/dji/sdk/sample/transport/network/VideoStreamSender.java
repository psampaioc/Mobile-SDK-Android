package com.dji.sdk.sample.transport.network;

import com.dji.sdk.sample.transport.clock.MonotonicClock;
import com.dji.sdk.sample.transport.TransportEgressMetrics;
import com.dji.sdk.sample.transport.rtp.H264RtpPacketizer;
import com.dji.sdk.sample.transport.rtp.RtpPacket;
import com.dji.sdk.sample.transport.video.EncodedVideoRtpPipeline;
import com.dji.sdk.sample.transport.video.PrimaryLiveFeedMetrics;

import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** One feed's bounded DJI Annex-B ingress and unchanged RFC 6184 RTP sender. */
public final class VideoStreamSender implements Closeable {
    private final String session;
    private final String feed;
    private final MonotonicClock clock;
    private final EncodedVideoRtpPipeline pipeline;
    private final InetSocketAddress rtpDestination;
    private final InetSocketAddress metadataDestination;
    private volatile DatagramSocket socket;
    private final Thread worker;
    private final Object wakeup = new Object();
    private final AtomicLong frameSequence = new AtomicLong();
    private final AtomicLong socketErrors = new AtomicLong();
    private final AtomicLong callbackCount = new AtomicLong();
    private final AtomicLong acceptedChunkCount = new AtomicLong();
    private final AtomicLong accessUnitCount = new AtomicLong();
    private final AtomicLong rtpPacketCount = new AtomicLong();
    private final AtomicLong workerFailures = new AtomicLong();
    private volatile String lastWorkerFailure = "";
    private volatile long lastWorkerFailureNanos;
    private volatile int effectiveSendBufferBytes;
    private volatile long lastCallbackNanos;
    private volatile long lastAcceptedChunkNanos;
    private volatile long lastAccessUnitNanos;
    private final AtomicLong maxOfferDurationNanos = new AtomicLong();
    private volatile long lastOfferDurationNanos;
    private final PrimaryLiveFeedMetrics liveFeedMetrics = new PrimaryLiveFeedMetrics();
    private final VideoSocketDiagnostics socketDiagnostics = new VideoSocketDiagnostics();
    private final RtpSequenceWindowMetrics rtpSequenceWindow = new RtpSequenceWindowMetrics();
    private volatile boolean running = true;
    private volatile String physicalSource = "UNKNOWN";
    private final List<RtpPacket> currentAccessUnit = new ArrayList<>();
    private final TransportEgressMetrics egressMetrics;

    public VideoStreamSender(String session, String feed, MonotonicClock clock, String host,
            int rtpPort, int metadataPort, int ssrc) throws Exception {
        this(session, feed, clock, host, rtpPort, metadataPort, ssrc, null);
    }

    public VideoStreamSender(String session, String feed, MonotonicClock clock, String host,
            int rtpPort, int metadataPort, int ssrc, TransportEgressMetrics egressMetrics) throws Exception {
        this.session = session;
        this.feed = feed;
        this.clock = clock;
        this.egressMetrics = egressMetrics;
        InetAddress address = InetAddress.getByName(host);
        rtpDestination = new InetSocketAddress(address, rtpPort);
        metadataDestination = new InetSocketAddress(address, metadataPort);
        socket = openSocket();
        H264RtpPacketizer packetizer = new H264RtpPacketizer(1200, 96, ssrc,
                (int) (clock.nowNanos() & 0xffff), clock.nowNanos() & 0xffffffffL);
        pipeline = new EncodedVideoRtpPipeline(64, 4 * 1024 * 1024, packetizer);
        worker = new Thread(this::run, "dji-video-" + feed);
        worker.setDaemon(true);
        worker.start();
    }

    /** Called by this sender's isolated Primary fan-out worker. */
    public boolean offer(byte[] data, int size) {
        callbackCount.incrementAndGet();
        long now = clock.nowNanos();
        lastCallbackNanos = now;
        long started = now;
        try {
            boolean accepted = pipeline.offer(data, size, now);
            if (accepted) {
                acceptedChunkCount.incrementAndGet();
                lastAcceptedChunkNanos = now;
            }
            synchronized (wakeup) { wakeup.notifyAll(); }
            return accepted;
        } finally {
            lastOfferDurationNanos = Math.max(0L, clock.nowNanos() - started);
            updateMaximum(maxOfferDurationNanos, lastOfferDurationNanos);
        }
    }

    /** Source is local diagnostic evidence; wire metadata intentionally omits it. */
    public void setPhysicalSource(String value) {
        physicalSource = value == null || value.isEmpty() ? "UNKNOWN" : value;
    }

    /** A hub consumer lost encoded bytes; never packetize a partial H.264 access unit. */
    public void signalDiscontinuity() {
        pipeline.requestDiscontinuity();
        synchronized (wakeup) { wakeup.notifyAll(); }
    }

    private void run() {
        while (running) {
            int drained;
            try {
                drained = pipeline.drain(128, this::acceptPacket);
            } catch (RuntimeException failure) {
                workerFailures.incrementAndGet();
                lastWorkerFailure = describe(failure);
                lastWorkerFailureNanos = clock.nowNanos();
                applyDiscontinuity();
                continue;
            }
            if (drained == 0) synchronized (wakeup) {
                try { wakeup.wait(5); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        while (pipeline.drain(128, this::acceptPacket) > 0) { }
    }

    private void acceptPacket(RtpPacket packet) {
        rtpSequenceWindow.recordGenerated(packet.getSequenceNumber());
        currentAccessUnit.add(packet);
        if (!packet.isMarker()) return;
        RtpPacket first = currentAccessUnit.get(0);
        accessUnitCount.incrementAndGet();
        recordAccessUnitTime(packet.getAccessUnitCompleteNanos());
        byte[] metadata = JsonWireEncoder.frameMetadata(session, feed,
                frameSequence.getAndIncrement(), packet.getSsrc(), packet.getTimestamp(),
                first.getAccessUnitFirstByteNanos(), packet.getAccessUnitCompleteNanos());
        // Metadata aids correlation only; a failed metadata datagram must never halt primary RTP.
        send(metadata, metadataDestination, true);
        for (RtpPacket item : currentAccessUnit) {
            if (!send(item.getBytes(), rtpDestination, false)) {
                rtpSequenceWindow.recordSendFailure();
                // Do not send the tail of a damaged AU. Resume cleanly at the next IDR.
                applyDiscontinuity();
                break;
            }
            rtpSequenceWindow.recordAccepted(item.getSequenceNumber(), item.getBytes().length);
            rtpPacketCount.incrementAndGet();
        }
        currentAccessUnit.clear();
    }

    private void applyDiscontinuity() {
        pipeline.signalDiscontinuity();
        liveFeedMetrics.signalDiscontinuity();
        currentAccessUnit.clear();
    }

    private boolean send(byte[] bytes, InetSocketAddress destination, boolean metadata) {
        long started = clock.nowNanos();
        try {
            DatagramSocket current = socket;
            current.send(new DatagramPacket(bytes, bytes.length, destination));
            socketDiagnostics.recordAccepted(metadata, bytes.length, clock.nowNanos(), clock.nowNanos() - started);
            if (egressMetrics != null) {
                if (metadata) egressMetrics.recordMetadata(bytes.length);
                else egressMetrics.recordPrimaryRtp(bytes.length);
            }
            return true;
        } catch (Exception failure) {
            socketErrors.incrementAndGet();
            socketDiagnostics.recordFailure(metadata, clock.nowNanos(), failure);
            if (!metadata) recreateSocket();
            return false;
        }
    }

    private DatagramSocket openSocket() throws Exception {
        DatagramSocket created = new DatagramSocket();
        // Keep the established buffer until measured Android/Edge evidence justifies tuning it.
        created.setSendBufferSize(4 * 1024 * 1024);
        effectiveSendBufferBytes = created.getSendBufferSize();
        return created;
    }

    private void recordAccessUnitTime(long completedNanos) {
        long timestamp = Math.max(0L, completedNanos);
        lastAccessUnitNanos = timestamp;
        liveFeedMetrics.recordCompletedAccessUnit(timestamp);
    }

    private long medianAccessUnitIntervalNanos() {
        return liveFeedMetrics.snapshot(clock.nowNanos()).medianIntervalNanos;
    }

    private synchronized void recreateSocket() {
        if (!running) return;
        DatagramSocket previous = socket;
        try { socket = openSocket(); }
        catch (Exception ignored) { return; }
        if (previous != null) previous.close();
    }

    private static String describe(Exception failure) {
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    public EncodedVideoRtpPipeline getPipeline() { return pipeline; }
    public long getSocketErrorCount() { return socketErrors.get(); }
    public long getCallbackCount() { return callbackCount.get(); }
    public long getAcceptedChunkCount() { return acceptedChunkCount.get(); }
    public long getAccessUnitCount() { return accessUnitCount.get(); }
    public long getRtpPacketCount() { return rtpPacketCount.get(); }
    public VideoSocketDiagnostics.Snapshot socketDiagnostics() { return socketDiagnostics.snapshot(); }
    public PrimaryLiveFeedMetrics.Snapshot getLiveFeedSnapshot() {
        return liveFeedMetrics.snapshot(clock.nowNanos());
    }
    public long drainMaxOfferDurationNanos() { return maxOfferDurationNanos.getAndSet(0L); }
    public long drainMaxSocketSendDurationNanos() { return socketDiagnostics.drainMaxSendDurationNanos(); }
    public RtpSequenceWindowMetrics.Snapshot drainRtpSequenceWindow() {
        return rtpSequenceWindow.drain();
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(feed, physicalSource, callbackCount.get(), acceptedChunkCount.get(),
                accessUnitCount.get(), rtpPacketCount.get(), lastCallbackNanos, lastAcceptedChunkNanos,
                lastAccessUnitNanos, workerFailures.get(), lastWorkerFailure, lastWorkerFailureNanos,
                effectiveSendBufferBytes, medianAccessUnitIntervalNanos(), lastOfferDurationNanos,
                pipeline.ingestionCounters(clock.nowNanos()), pipeline.parserCounters(),
                pipeline.accessUnitCounters(), pipeline.rtpCounters(), socketDiagnostics.snapshot());
    }

    public static final class Diagnostics {
        public final String feed;
        public final String physicalSource;
        public final long callbacks;
        public final long acceptedChunks;
        public final long accessUnits;
        public final long rtpPackets;
        public final long lastCallbackNanos;
        public final long lastAcceptedChunkNanos;
        public final long lastAccessUnitNanos;
        public final long workerFailures;
        public final String lastWorkerFailure;
        public final long lastWorkerFailureNanos;
        public final int effectiveSendBufferBytes;
        public final long medianAccessUnitIntervalNanos;
        public final long lastOfferDurationNanos;
        public final com.dji.sdk.sample.transport.video.BoundedVideoIngestor.Counters ingestion;
        public final com.dji.sdk.sample.transport.video.AnnexBParser.Counters parser;
        public final com.dji.sdk.sample.transport.video.H264AccessUnitAssembler.Counters assembler;
        public final H264RtpPacketizer.Counters rtp;
        public final VideoSocketDiagnostics.Snapshot socket;

        Diagnostics(String feed, String physicalSource, long callbacks, long acceptedChunks,
                long accessUnits, long rtpPackets, long lastCallbackNanos, long lastAcceptedChunkNanos,
                long lastAccessUnitNanos, long workerFailures, String lastWorkerFailure,
                long lastWorkerFailureNanos, int effectiveSendBufferBytes,
                long medianAccessUnitIntervalNanos, long lastOfferDurationNanos,
                com.dji.sdk.sample.transport.video.BoundedVideoIngestor.Counters ingestion,
                com.dji.sdk.sample.transport.video.AnnexBParser.Counters parser,
                com.dji.sdk.sample.transport.video.H264AccessUnitAssembler.Counters assembler,
                H264RtpPacketizer.Counters rtp, VideoSocketDiagnostics.Snapshot socket) {
            this.feed = feed;
            this.physicalSource = physicalSource;
            this.callbacks = callbacks;
            this.acceptedChunks = acceptedChunks;
            this.accessUnits = accessUnits;
            this.rtpPackets = rtpPackets;
            this.lastCallbackNanos = lastCallbackNanos;
            this.lastAcceptedChunkNanos = lastAcceptedChunkNanos;
            this.lastAccessUnitNanos = lastAccessUnitNanos;
            this.workerFailures = workerFailures;
            this.lastWorkerFailure = lastWorkerFailure;
            this.lastWorkerFailureNanos = lastWorkerFailureNanos;
            this.effectiveSendBufferBytes = effectiveSendBufferBytes;
            this.medianAccessUnitIntervalNanos = medianAccessUnitIntervalNanos;
            this.lastOfferDurationNanos = lastOfferDurationNanos;
            this.ingestion = ingestion;
            this.parser = parser;
            this.assembler = assembler;
            this.rtp = rtp;
            this.socket = socket;
        }
    }

    private static void updateMaximum(AtomicLong target, long value) {
        long current;
        do { current = target.get(); if (value <= current) return; }
        while (!target.compareAndSet(current, value));
    }

    @Override public void close() {
        running = false;
        synchronized (wakeup) { wakeup.notifyAll(); }
        try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        DatagramSocket current = socket;
        if (current != null) current.close();
    }
}
