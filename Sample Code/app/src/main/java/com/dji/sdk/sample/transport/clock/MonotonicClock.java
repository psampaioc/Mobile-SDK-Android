package com.dji.sdk.sample.transport.clock;

/** A clock suitable for ordering and interval measurement within one Android boot. */
public interface MonotonicClock {
    long nowNanos();
}
