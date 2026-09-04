package com.dji.sdk.sample.operate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded, app-local event timeline. Producers never perform I/O. */
public final class SessionTimeline {
    public enum TimeSource { OPERATOR_ACTION, DJI_CALLBACK, ANDROID_OBSERVATION }
    public static final class Event {
        private final long elapsedRealtimeMs; private final TimeSource timeSource;
        private final String type; private final String detail;
        Event(long elapsedRealtimeMs, TimeSource timeSource, String type, String detail) {
            this.elapsedRealtimeMs = elapsedRealtimeMs; this.timeSource = timeSource;
            this.type = type == null ? "UNKNOWN" : type; this.detail = detail == null ? "" : detail;
        }
        public long getElapsedRealtimeMs() { return elapsedRealtimeMs; }
        public TimeSource getTimeSource() { return timeSource; }
        public String getType() { return type; }
        public String getDetail() { return detail; }
    }
    private final int capacity; private final ArrayDeque<Event> events = new ArrayDeque<>(); private long dropped;
    public SessionTimeline(int capacity) { if (capacity < 1) throw new IllegalArgumentException("capacity must be positive"); this.capacity = capacity; }
    public synchronized void record(long elapsedRealtimeMs, TimeSource source, String type, String detail) {
        if (events.size() == capacity) { events.removeFirst(); dropped++; }
        events.addLast(new Event(elapsedRealtimeMs, source == null ? TimeSource.ANDROID_OBSERVATION : source, type, detail));
    }
    public synchronized List<Event> snapshot() { return Collections.unmodifiableList(new ArrayList<>(events)); }
    public synchronized long getDroppedCount() { return dropped; }
}
