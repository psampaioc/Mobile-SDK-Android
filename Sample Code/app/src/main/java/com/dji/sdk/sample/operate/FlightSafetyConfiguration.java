package com.dji.sdk.sample.operate;

import androidx.annotation.NonNull;

/** Capability-aware safety configuration presentation. No setter is exposed until hardware proof exists. */
public final class FlightSafetyConfiguration {
    public enum ReadbackState { UNAVAILABLE, LOADING, AVAILABLE, STALE, FAILED }

    private final ReadbackState readbackState;
    private final String summary;

    private FlightSafetyConfiguration(ReadbackState readbackState, String summary) {
        this.readbackState = readbackState;
        this.summary = summary;
    }

    @NonNull public static FlightSafetyConfiguration unvalidated() {
        return new FlightSafetyConfiguration(ReadbackState.UNAVAILABLE,
                "Read-only until validated on this M210 RTK V2 and Cendence.");
    }

    @NonNull public ReadbackState getReadbackState() { return readbackState; }
    @NonNull public String getSummary() { return summary; }
    public boolean canEdit() { return false; }
}
