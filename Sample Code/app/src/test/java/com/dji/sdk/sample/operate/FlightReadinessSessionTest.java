package com.dji.sdk.sample.operate;

/** Dependency-free JVM contract for a physical review that never survives a new aircraft session. */
public final class FlightReadinessSessionTest {
    private FlightReadinessSessionTest() { }

    public static void main(String[] args) {
        acknowledgementsSurvivePanelReopenButNotAircraftReconnect();
        eventHistoryIsBounded();
        System.out.println("Flight readiness session tests passed");
    }

    private static void acknowledgementsSurvivePanelReopenButNotAircraftReconnect() {
        FlightReadinessSession session = new FlightReadinessSession(8);
        for (FlightReadinessSession.ChecklistItem item : FlightReadinessSession.ChecklistItem.values()) {
            session.acknowledge(item, 100L);
        }
        require(session.isComplete(), "all physical checks acknowledged");
        require(session.getAcknowledgedItems().size() == FlightReadinessSession.ChecklistItem.values().length,
                "panel reads same current-session state");

        session.beginNewAircraftSession(200L);
        require(!session.isComplete(), "new aircraft session clears acknowledgement");
        require(session.getAcknowledgedItems().isEmpty(), "no acknowledgement leaks between sessions");
    }

    private static void eventHistoryIsBounded() {
        FlightReadinessSession session = new FlightReadinessSession(2);
        session.acknowledge(FlightReadinessSession.ChecklistItem.AIRFRAME_AND_PAYLOAD, 1L);
        session.acknowledge(FlightReadinessSession.ChecklistItem.LANDING_AREA, 2L);
        session.beginNewAircraftSession(3L);
        require(session.getRecentEvents().size() == 2, "session events stay bounded");
        require(session.getDroppedEventCount() == 1, "oldest session event is dropped on overflow");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
