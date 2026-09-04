package com.dji.sdk.sample.transport.network;

import com.dji.sdk.sample.transport.clock.MonotonicClock;
import com.dji.sdk.sample.transport.rtp.H264RtpPacketizer;
import com.dji.sdk.sample.transport.rtp.RtpPacket;
import com.dji.sdk.sample.transport.video.EncodedVideoRtpPipeline;

import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** One feed's bounded DJI Annex-B ingress and unchanged RFC 6184 RTP sender. */
public final class VideoStreamSender implements Closeable {
    private final String session;
    private final String feed;
    private final MonotonicClock clock;
    private final EncodedVideoRtpPipeline pipeline;
    private final InetSocketAddress rtpDestination;
    private final InetSocketAddress metadataDestination;
    private final DatagramSocket socket;
    private final Thread worker;
    private final Object wakeup = new Object();
    private final AtomicLong frameSequence = new AtomicLong();
    private final AtomicLong socketErrors = new AtomicLong();
    private final AtomicLong callbackCount = new AtomicLong();
    private final AtomicLong acceptedChunkCount = new AtomicLong();
    private final AtomicLong accessUnitCount = new AtomicLong();
    private final AtomicLong rtpPacketCount = new AtomicLong();
    private volatile boolean running = true;
    private volatile String physicalSource = "UNKNOWN";
    private final List<RtpPacket> currentAccessUnit = new ArrayList<>();

    public VideoStreamSender(String session, String feed, MonotonicClock clock, String host,
            int rtpPort, int metadataPort, int ssrc) throws Exception {
        this.session = session;
        this.feed = feed;
        this.clock = clock;
        InetAddress address = InetAddress.getByName(host);
        rtpDestination = new InetSocketAddress(address, rtpPort);
        metadataDestination = new InetSocketAddress(address, metadataPort);
        socket = new DatagramSocket();
        socket.setSendBufferSize(4 * 1024 * 1024);
        H264RtpPacketizer packetizer = new H264RtpPacketizer(1200, 96, ssrc,
                (int) (clock.nowNanos() & 0xffff), clock.nowNanos() & 0xffffffffL);
        pipeline = new EncodedVideoRtpPipeline(64, 4 * 1024 * 1024, packetizer);
        worker = new Thread(this::run, "dji-video-" + feed);
        worker.setDaemon(true);
        worker.start();
    }

    /** Called directly by DJI; only timestamps, copies into a bounded queue, and wakes the worker. */
    public boolean offer(byte[] data, int size) {
        callbackCount.incrementAndGet();
        boolean accepted = pipeline.offer(data, size, clock.nowNanos());
        if (accepted) acceptedChunkCount.incrementAndGet();
        synchronized (wakeup) { wakeup.notifyAll(); }
        return accepted;
    }

    /** Source is local diagnostic evidence; wire metadata intentionally omits it. */
    public void setPhysicalSource(String value) {
        physicalSource = value == null || value.isEmpty() ? "UNKNOWN" : value;
    }

    private void run() {
        while (running) {
            int drained = pipeline.drain(128, this::acceptPacket);
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
        currentAccessUnit.add(packet);
        if (!packet.isMarker()) return;
        RtpPacket first = currentAccessUnit.get(0);
        accessUnitCount.incrementAndGet();
        byte[] metadata = JsonWireEncoder.frameMetadata(session, feed,
                frameSequence.getAndIncrement(), packet.getSsrc(), packet.getTimestamp(),
                first.getAccessUnitFirstByteNanos(), packet.getAccessUnitCompleteNanos());
        send(metadata, metadataDestination);
        for (RtpPacket item : currentAccessUnit) {
            send(item.getBytes(), rtpDestination);
            rtpPacketCount.incrementAndGet();
        }
        currentAccessUnit.clear();
    }

    private void send(byte[] bytes, InetSocketAddress destination) {
        try { socket.send(new DatagramPacket(bytes, bytes.length, destination)); }
        catch (Exception failure) { socketErrors.incrementAndGet(); }
    }

    public EncodedVideoRtpPipeline getPipeline() { return pipeline; }
    public long getSocketErrorCount() { return socketErrors.get(); }
    public long getCallbackCount() { return callbackCount.get(); }
    public long getAcceptedChunkCount() { return acceptedChunkCount.get(); }
    public long getAccessUnitCount() { return accessUnitCount.get(); }
    public long getRtpPacketCount() { return rtpPacketCount.get(); }

    public Diagnostics diagnostics() {
        return new Diagnostics(feed, physicalSource, callbackCount.get(), accessUnitCount.get(), rtpPacketCount.get(),
                pipeline.ingestionCounters(), pipeline.parserCounters(),
                pipeline.accessUnitCounters(), pipeline.rtpCounters());
    }

    public static final class Diagnostics {
        public final String feed;
        public final String physicalSource;
        public final long callbacks;
        public final long accessUnits;
        public final long rtpPackets;
        public final com.dji.sdk.sample.transport.video.BoundedVideoIngestor.Counters ingestion;
        public final com.dji.sdk.sample.transport.video.AnnexBParser.Counters parser;
        public final com.dji.sdk.sample.transport.video.H264AccessUnitAssembler.Counters assembler;
        public final H264RtpPacketizer.Counters rtp;

        Diagnostics(String feed, String physicalSource, long callbacks, long accessUnits, long rtpPackets,
                com.dji.sdk.sample.transport.video.BoundedVideoIngestor.Counters ingestion,
                com.dji.sdk.sample.transport.video.AnnexBParser.Counters parser,
                com.dji.sdk.sample.transport.video.H264AccessUnitAssembler.Counters assembler,
                H264RtpPacketizer.Counters rtp) {
            this.feed = feed;
            this.physicalSource = physicalSource;
            this.callbacks = callbacks;
            this.accessUnits = accessUnits;
            this.rtpPackets = rtpPackets;
            this.ingestion = ingestion;
            this.parser = parser;
            this.assembler = assembler;
            this.rtp = rtp;
        }
    }

    @Override public void close() {
        running = false;
        synchronized (wakeup) { wakeup.notifyAll(); }
        try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        socket.close();
    }
}
