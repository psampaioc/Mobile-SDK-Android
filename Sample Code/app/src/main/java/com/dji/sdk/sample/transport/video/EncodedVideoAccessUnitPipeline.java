package com.dji.sdk.sample.transport.video;

/**
 * Bounded H.264 ingress which emits complete access units to a non-DJI consumer.
 *
 * <p>This deliberately has no RTP state. A recorder gets its own instance, so a slow disk
 * writer can drop only its own input and cannot mutate the Edge transport pipeline.</p>
 */
public final class EncodedVideoAccessUnitPipeline {
    public interface AccessUnitSink { void onAccessUnit(H264AccessUnit accessUnit); }

    private final BoundedVideoIngestor ingestor;
    private final AnnexBParser parser;
    private final H264AccessUnitAssembler assembler = new H264AccessUnitAssembler();
    private long expectedChunkSequence = -1L;

    public EncodedVideoAccessUnitPipeline(int queueCapacity, int maximumNalBytes) {
        ingestor = new BoundedVideoIngestor(queueCapacity);
        parser = new AnnexBParser(maximumNalBytes);
    }

    /** Callback-safe: copies input into a bounded queue and never waits for parsing or disk. */
    public synchronized boolean offer(byte[] videoBuffer, int size, long receiveTimeNanos) {
        return ingestor.offer(videoBuffer, size, receiveTimeNanos);
    }

    /** Must be called by one dedicated consumer worker. */
    public synchronized int drain(int maximumChunks, AccessUnitSink sink) {
        if (maximumChunks < 1) throw new IllegalArgumentException("maximumChunks must be positive");
        if (sink == null) throw new NullPointerException("sink");
        int processed = 0;
        EncodedVideoChunk chunk;
        while (processed < maximumChunks && (chunk = ingestor.poll()) != null) {
            if (expectedChunkSequence >= 0 && chunk.getSequence() != expectedChunkSequence) {
                parser.signalDiscontinuity();
                assembler.signalDiscontinuity();
            }
            expectedChunkSequence = chunk.getSequence() + 1L;
            parser.accept(chunk.getData(), chunk.getReceiveTimeNanos(),
                    nal -> assembler.accept(nal, sink::onAccessUnit));
            processed++;
        }
        return processed;
    }

    /** Drops queued fragments and waits for a clean subsequent access unit. */
    public synchronized void signalDiscontinuity() {
        ingestor.discardQueued();
        expectedChunkSequence = -1L;
        parser.signalDiscontinuity();
        assembler.signalDiscontinuity();
    }

    /** File/session boundary only. A live recorder normally resumes through a following IDR. */
    public synchronized void endOfStream(AccessUnitSink sink) {
        if (sink == null) throw new NullPointerException("sink");
        parser.endOfStream(nal -> assembler.accept(nal, sink::onAccessUnit));
        assembler.endOfStream(sink::onAccessUnit);
    }

    public BoundedVideoIngestor.Counters ingestionCounters() { return ingestor.snapshotCounters(); }
    public AnnexBParser.Counters parserCounters() { return parser.snapshotCounters(); }
    public H264AccessUnitAssembler.Counters accessUnitCounters() { return assembler.snapshotCounters(); }
}
