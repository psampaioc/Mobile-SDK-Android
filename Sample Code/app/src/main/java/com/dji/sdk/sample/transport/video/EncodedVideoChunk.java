package com.dji.sdk.sample.transport.video;

/** Immutable copy of one arbitrary encoded-video callback chunk. */
public final class EncodedVideoChunk {
    private final byte[] data;
    private final long receiveTimeNanos;
    private final long sequence;

    EncodedVideoChunk(byte[] data, long receiveTimeNanos, long sequence) {
        this.data = data;
        this.receiveTimeNanos = receiveTimeNanos;
        this.sequence = sequence;
    }

    public byte[] getData() {
        return data;
    }

    public long getReceiveTimeNanos() {
        return receiveTimeNanos;
    }

    public long getSequence() {
        return sequence;
    }
}
