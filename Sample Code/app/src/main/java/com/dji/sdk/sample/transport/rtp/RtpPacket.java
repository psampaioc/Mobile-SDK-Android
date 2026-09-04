package com.dji.sdk.sample.transport.rtp;

/** Complete RTP datagram plus useful header metadata. */
public final class RtpPacket {
    private final byte[] bytes;
    private final int sequenceNumber;
    private final long timestamp;
    private final boolean marker;
    private final int ssrc;
    private final long accessUnitFirstByteNanos;
    private final long accessUnitCompleteNanos;
    private final boolean keyFrame;

    RtpPacket(byte[] bytes, int sequenceNumber, long timestamp, boolean marker, int ssrc,
            long accessUnitFirstByteNanos, long accessUnitCompleteNanos, boolean keyFrame) {
        this.bytes = bytes;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.marker = marker;
        this.ssrc = ssrc;
        this.accessUnitFirstByteNanos = accessUnitFirstByteNanos;
        this.accessUnitCompleteNanos = accessUnitCompleteNanos;
        this.keyFrame = keyFrame;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isMarker() {
        return marker;
    }

    public int getSsrc() { return ssrc; }
    public long getAccessUnitFirstByteNanos() { return accessUnitFirstByteNanos; }
    public long getAccessUnitCompleteNanos() { return accessUnitCompleteNanos; }
    public boolean isKeyFrame() { return keyFrame; }
}
