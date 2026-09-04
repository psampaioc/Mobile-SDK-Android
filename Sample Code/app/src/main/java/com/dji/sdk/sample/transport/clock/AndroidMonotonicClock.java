package com.dji.sdk.sample.transport.clock;

import android.os.SystemClock;

/** Shared time domain for video ingress and DJI telemetry callback ingress. */
public final class AndroidMonotonicClock implements MonotonicClock {
    @Override
    public long nowNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }
}
