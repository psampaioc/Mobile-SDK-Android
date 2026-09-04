package com.dji.sdk.sample.transport.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable H.264 access unit in decode order. */
public final class H264AccessUnit {
    private final List<H264NalUnit> nalUnits;
    private final long receiveTimeNanos;
    private final long completeReceiveTimeNanos;
    private final boolean keyFrame;

    H264AccessUnit(List<H264NalUnit> nalUnits, long receiveTimeNanos,
            long completeReceiveTimeNanos, boolean keyFrame) {
        this.nalUnits = Collections.unmodifiableList(new ArrayList<>(nalUnits));
        this.receiveTimeNanos = receiveTimeNanos;
        this.completeReceiveTimeNanos = completeReceiveTimeNanos;
        this.keyFrame = keyFrame;
    }

    public List<H264NalUnit> getNalUnits() {
        return nalUnits;
    }

    /** Timestamp of the first VCL NAL's first received start-code byte. */
    public long getReceiveTimeNanos() {
        return receiveTimeNanos;
    }

    /** Timestamp of the latest callback chunk contributing bytes to this access unit. */
    public long getCompleteReceiveTimeNanos() {
        return completeReceiveTimeNanos;
    }

    public boolean isKeyFrame() {
        return keyFrame;
    }
}
