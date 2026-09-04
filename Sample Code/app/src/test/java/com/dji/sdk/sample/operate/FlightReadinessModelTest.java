package com.dji.sdk.sample.operate;

/** Dependency-free JVM contract for the Mission-only flight readiness decision. */
public final class FlightReadinessModelTest {
    private FlightReadinessModelTest() { }

    public static void main(String[] args) {
        reviewIsRequiredUntilPhysicalChecksAreComplete();
        automaticBlockersCannotBeAcknowledgedAway();
        batterySeverityChangesTheMissionDecision();
        rtkIsAdvisoryUnlessTheMissionRequiresIt();
        specificReasonsRemainVisible();
        System.out.println("Flight readiness model tests passed");
    }

    private static void reviewIsRequiredUntilPhysicalChecksAreComplete() {
        FlightReadinessModel.Decision decision = FlightReadinessModel.evaluate(healthyInput(false));
        equal(FlightReadinessModel.State.REVIEW_REQUIRED, decision.getState(),
                "physical review is distinct from automatic health");
        require(!decision.canStartMission(), "mission must wait for physical review");
        equal(FlightReadinessModel.Reason.PHYSICAL_REVIEW_INCOMPLETE, decision.getPrimaryReason(),
                "incomplete review reason");

        decision = FlightReadinessModel.evaluate(healthyInput(true));
        equal(FlightReadinessModel.State.MISSION_READY, decision.getState(),
                "all current-session checks complete");
        require(decision.canStartMission(), "only ready state starts a mission");
    }

    private static void automaticBlockersCannotBeAcknowledgedAway() {
        FlightReadinessModel.Input input = healthyInput(true).withHomeAvailable(false);
        FlightReadinessModel.Decision decision = FlightReadinessModel.evaluate(input);
        equal(FlightReadinessModel.State.BLOCKED, decision.getState(), "missing DJI Home blocks");
        equal(FlightReadinessModel.Reason.DJI_HOME_UNAVAILABLE, decision.getPrimaryReason(),
                "home reason remains specific");
        require(!decision.canStartMission(), "acknowledgement cannot clear DJI Home blocker");
    }

    private static void batterySeverityChangesTheMissionDecision() {
        FlightReadinessModel.Decision attention = FlightReadinessModel.evaluate(
                healthyInput(true).withAircraftBatteryPercent(25));
        equal(FlightReadinessModel.State.ATTENTION, attention.getState(), "low battery needs review");
        require(attention.canStartMission(), "an advisory does not replace the Mission-only block gate");

        FlightReadinessModel.Decision blocked = FlightReadinessModel.evaluate(
                healthyInput(true).withAircraftBatteryPercent(15));
        equal(FlightReadinessModel.State.BLOCKED, blocked.getState(), "critical battery blocks");
        equal(FlightReadinessModel.Reason.AIRCRAFT_BATTERY_CRITICAL, blocked.getPrimaryReason(),
                "critical battery reason");
    }

    private static void rtkIsAdvisoryUnlessTheMissionRequiresIt() {
        FlightReadinessModel.Input ordinary = healthyInput(true).withRtkFix(false);
        FlightReadinessModel.Decision ordinaryDecision = FlightReadinessModel.evaluate(ordinary);
        equal(FlightReadinessModel.State.ATTENTION, ordinaryDecision.getState(),
                "ordinary GPS mission receives RTK advisory");
        require(ordinaryDecision.canStartMission(), "ordinary GPS mission may start after review");

        FlightReadinessModel.Input rtkRequired = ordinary.withRtkRequired(true);
        FlightReadinessModel.Decision rtkDecision = FlightReadinessModel.evaluate(rtkRequired);
        equal(FlightReadinessModel.State.BLOCKED, rtkDecision.getState(),
                "RTK-required mission blocks without FIX");
        equal(FlightReadinessModel.Reason.RTK_FIX_REQUIRED, rtkDecision.getPrimaryReason(),
                "RTK reason");
    }

    private static void specificReasonsRemainVisible() {
        FlightReadinessModel.Decision decision = FlightReadinessModel.evaluate(
                healthyInput(true).withFlightStateFresh(false));
        equal(FlightReadinessModel.State.BLOCKED, decision.getState(), "stale flight state blocks");
        equal(FlightReadinessModel.Reason.FLIGHT_STATE_STALE, decision.getPrimaryReason(),
                "stale reason");
        require(!decision.getRows().isEmpty(), "presentation rows describe the decision");
    }

    private static FlightReadinessModel.Input healthyInput(boolean physicalReviewComplete) {
        return new FlightReadinessModel.Input(true, true, true, true, true, false,
                true, true, true, false, false, 85, 85, 85, physicalReviewComplete);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected
                + ", got " + actual);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
