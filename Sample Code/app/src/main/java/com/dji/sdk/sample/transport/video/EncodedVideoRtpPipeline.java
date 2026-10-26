package com.dji.sdk.sample.transport.video;

import com.dji.sdk.sample.transport.rtp.H264RtpPacketizer;

/** Pure-Java encoded video pipeline intended to be drained by one dedicated worker thread. */
public final class EncodedVideoRtpPipeline {
    private final BoundedVideoIngestor ingestor;
    private final AnnexBParser parser;
    private final H264AccessUnitAssembler assembler;
    private final H264RtpPacketizer packetizer;
    private long expectedChunkSequence = -1;

    public EncodedVideoRtpPipeline(int queueCapacity, int maximumNalBytes,
            H264RtpPacketizer packetizer) {
        if (packetizer == null) {
            throw new NullPointerException("packetizer");
        }
        ingestor = new BoundedVideoIngestor(queueCapacity);
        parser = new AnnexBParser(maximumNalBytes);
        assembler = new H264AccessUnitAssembler();
        this.packetizer = packetizer;
    }

    /** Fast callback API: copies bytes and never waits for the parser or network. */
    public boolean offer(byte[] videoBuffer, int size, long receiveTimeNanos) {
        return ingestor.offer(videoBuffer, size, receiveTimeNanos);
    }

    /** Processes at most {@code maximumChunks}; call only from one consumer thread. */
    public synchronized int drain(int maximumChunks, H264RtpPacketizer.PacketSink sink) {
        if (maximumChunks < 1) {
            throw new IllegalArgumentException("maximumChunks must be positive");
        }
        int processed = 0;
        EncodedVideoChunk chunk;
        while (processed < maximumChunks && (chunk = ingestor.poll()) != null) {
            if (expectedChunkSequence >= 0 && chunk.getSequence() != expectedChunkSequence) {
                parser.signalDiscontinuity();
                assembler.signalDiscontinuity();
            }
            expectedChunkSequence = chunk.getSequence() + 1;
            parser.accept(chunk.getData(), chunk.getReceiveTimeNanos(),
                    nal -> assembler.accept(nal, au -> packetizer.packetize(au, sink)));
            processed++;
        }
        return processed;
    }

    /** Drops parser/assembler partial state after a local send or worker failure. */
    public synchronized void signalDiscontinuity() {
        ingestor.discardQueued();
        expectedChunkSequence = -1;
        parser.signalDiscontinuity();
        assembler.signalDiscontinuity();
    }

    /** Thread-safe request; parser state is reset later by the single drain worker. */
    public void requestDiscontinuity() {
        ingestor.markDiscontinuity();
    }

    /** Test/file boundary only; a live stream should remain open and recover through IDRs. */
    public synchronized void endOfStream(H264RtpPacketizer.PacketSink sink) {
        parser.endOfStream(nal -> assembler.accept(nal, au -> packetizer.packetize(au, sink)));
        assembler.endOfStream(au -> packetizer.packetize(au, sink));
    }

    public BoundedVideoIngestor.Counters ingestionCounters() {
        return ingestor.snapshotCounters();
    }

    /** Monotonic snapshot used by local diagnostics to distinguish backlog from source pauses. */
    public BoundedVideoIngestor.Counters ingestionCounters(long nowNanos) {
        return ingestor.snapshotCounters(nowNanos);
    }

    public AnnexBParser.Counters parserCounters() {
        return parser.snapshotCounters();
    }

    public H264AccessUnitAssembler.Counters accessUnitCounters() {
        return assembler.snapshotCounters();
    }

    public H264RtpPacketizer.Counters rtpCounters() {
        return packetizer.snapshotCounters();
    }
}
