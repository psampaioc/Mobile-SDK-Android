package com.dji.sdk.sample.transport.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CallbackRateRegistry {
    private final int samplesPerSource;
    private final Map<String, CallbackRateTracker> trackers = new LinkedHashMap<>();

    public CallbackRateRegistry(int samplesPerSource) {
        this.samplesPerSource = samplesPerSource;
    }

    public synchronized void record(String source, int componentIndex, long callbackEntryNanos) {
        String key = source + ":" + componentIndex;
        CallbackRateTracker tracker = trackers.get(key);
        if (tracker == null) {
            tracker = new CallbackRateTracker(key, samplesPerSource);
            trackers.put(key, tracker);
        }
        tracker.record(callbackEntryNanos);
    }

    public synchronized List<CallbackRateSnapshot> snapshots() {
        List<CallbackRateSnapshot> result = new ArrayList<>(trackers.size());
        for (CallbackRateTracker tracker : trackers.values()) result.add(tracker.snapshot());
        return Collections.unmodifiableList(result);
    }
}
