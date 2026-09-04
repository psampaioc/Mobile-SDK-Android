package com.dji.sdk.sample.transport.video;

/** One H.264 NAL unit without its Annex-B start code. */
public final class H264NalUnit {
    private final byte[] data;
    private final long receiveTimeNanos;

    H264NalUnit(byte[] data, long receiveTimeNanos) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("NAL unit must not be empty");
        }
        this.data = data;
        this.receiveTimeNanos = receiveTimeNanos;
    }

    public byte[] getData() {
        return data;
    }

    public long getReceiveTimeNanos() {
        return receiveTimeNanos;
    }

    public int getType() {
        return data[0] & 0x1f;
    }

    public boolean isVcl() {
        int type = getType();
        return type >= 1 && type <= 5;
    }
}
