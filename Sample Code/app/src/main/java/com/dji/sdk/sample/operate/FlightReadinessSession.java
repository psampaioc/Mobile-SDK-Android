package com.dji.sdk.sample.operate;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Current-aircraft-session operator acknowledgements; intentionally never persisted across reconnects. */
public final class FlightReadinessSession {
    public enum ChecklistItem {
        AIRFRAME_AND_PAYLOAD("Airframe, propellers, payload and batteries secure"),
        LANDING_AREA("Takeoff and landing area clear"),
        VLOS_WEATHER_AIRSPACE("VLOS, weather and airspace reviewed"),
        HOME_AND_RTH("DJI Home and RTH recovery route reviewed"),
        CENDENCE_CONTROLS("Cendence controls understood"),
        MISSION_ROUTE("Mission route and final action reviewed");

        private final String label;
        ChecklistItem(String label) { this.label = label; }
        @NonNull public String getLabel() { return label; }
    }

    public static final class Event {
        private final String type;
        private final long elapsedMs;
        private final ChecklistItem item;

        private Event(String type, long elapsedMs, ChecklistItem item) {
            this.type = type;
            this.elapsedMs = elapsedMs;
            this.item = item;
        }

        @NonNull public String getType() { return type; }
        public long getElapsedMs() { return elapsedMs; }
        public ChecklistItem getItem() { return item; }
    }

    private final EnumSet<ChecklistItem> acknowledged = EnumSet.noneOf(ChecklistItem.class);
    private final ArrayDeque<Event> recentEvents = new ArrayDeque<>();
    private final int eventCapacity;
    private long droppedEventCount;

    public FlightReadinessSession(int eventCapacity) {
        if (eventCapacity < 1) throw new IllegalArgumentException("eventCapacity must be positive");
        this.eventCapacity = eventCapacity;
    }

    public synchronized void acknowledge(@NonNull ChecklistItem item, long elapsedMs) {
        if (acknowledged.add(item)) addEvent(new Event("acknowledged", elapsedMs, item));
    }

    public synchronized void beginNewAircraftSession(long elapsedMs) {
        acknowledged.clear();
        addEvent(new Event("aircraft_session_reset", elapsedMs, null));
    }

    public synchronized boolean isComplete() {
        return acknowledged.size() == ChecklistItem.values().length;
    }

    @NonNull public synchronized Set<ChecklistItem> getAcknowledgedItems() {
        return Collections.unmodifiableSet(EnumSet.copyOf(acknowledged));
    }

    @NonNull public synchronized List<Event> getRecentEvents() {
        return Collections.unmodifiableList(new ArrayList<>(recentEvents));
    }

    public synchronized long getDroppedEventCount() { return droppedEventCount; }

    private void addEvent(Event event) {
        if (recentEvents.size() == eventCapacity) {
            recentEvents.removeFirst();
            droppedEventCount++;
        }
        recentEvents.addLast(event);
    }
}
