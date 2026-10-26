package com.dji.sdk.sample.operate;

/** Dependency-free JVM contract for the Mission-only flight readiness decision. */
public final class FlightReadinessModelTest {
    private FlightReadinessModelTest() { }

    public static void main(String[] args) {
        reviewIsRequiredUntilPhysicalChecksAreComplete();
        automaticBlockersCannotBeAcknowledgedAway();
        batterySeverityChangesTheMissionDecision();
        rtkIsAlwaysAdvisoryInTheApp();
        specificReasonsRemainVisible();
        staleAndWaitingEvidenceRemainVisible();
        homeEvidenceIsSharedAndNeverImplicitlyHealthy();
        staleBatteryCannotProduceReadyAssessment();
        staleCendenceBatteryIsNotReportedAsCritical();
        landingAdvisoryUsesConfiguredThresholdAndLowestPack();
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

    private static void rtkIsAlwaysAdvisoryInTheApp() {
        FlightReadinessModel.Input ordinary = healthyInput(true).withRtkFix(false);
        FlightReadinessModel.Decision ordinaryDecision = FlightReadinessModel.evaluate(ordinary);
        equal(FlightReadinessModel.State.ATTENTION, ordinaryDecision.getState(),
                "ordinary GPS mission receives RTK advisory");
        require(ordinaryDecision.canStartMission(), "ordinary GPS mission may start after review");

        FlightReadinessModel.Input rtkRequired = ordinary.withRtkRequired(true);
        FlightReadinessModel.Decision rtkDecision = FlightReadinessModel.evaluate(rtkRequired);
        equal(FlightReadinessModel.State.ATTENTION, rtkDecision.getState(),
                "RTK preference remains advisory without FIX");
        require(rtkDecision.canStartMission(), "RTK absence cannot add an app-side mission block");
    }

    private static void specificReasonsRemainVisible() {
        FlightReadinessModel.Decision decision = FlightReadinessModel.evaluate(
                healthyInput(true).withFlightStateFresh(false));
        equal(FlightReadinessModel.State.BLOCKED, decision.getState(), "stale flight state blocks");
        equal(FlightReadinessModel.Reason.FLIGHT_STATE_STALE, decision.getPrimaryReason(),
                "stale reason");
        require(!decision.getRows().isEmpty(), "presentation rows describe the decision");
    }

    private static void staleAndWaitingEvidenceRemainVisible() {
        FlightReadinessModel.Decision stale = FlightReadinessModel.evaluate(healthyInput(true)
                .withFlightStateEvidence(FlightReadinessModel.EvidenceState.STALE));
        equal(FlightReadinessModel.RowState.STALE, row(stale, "Flight state").getState(),
                "stale flight evidence stays distinct from unavailable");
        equal("STALE", row(stale, "Flight state").getValue(), "stale flight value");

        FlightReadinessModel.Decision waiting = FlightReadinessModel.evaluate(healthyInput(true)
                .withAircraftBatteryEvidence(0, FlightReadinessModel.EvidenceState.WAITING_FOR_SAMPLE));
        equal(FlightReadinessModel.RowState.WAITING_FOR_SAMPLE,
                row(waiting, "Drone battery B1").getState(), "battery waits for its first sample");
        equal("WAITING FOR SAMPLE", row(waiting, "Drone battery B1").getValue(),
                "battery waiting value");
    }

    private static void staleBatteryCannotProduceReadyAssessment() {
        FlightReadinessModel.Decision decision = FlightReadinessModel.evaluate(healthyInput(true)
                .withAircraftBatteryEvidence(1, FlightReadinessModel.EvidenceState.STALE));
        equal(FlightReadinessModel.State.BLOCKED, decision.getState(),
                "stale B2 cannot produce ready assessment");
        equal(FlightReadinessModel.RowState.STALE, row(decision, "Drone battery B2").getState(),
                "stale B2 stays visible as stale");
    }

    private static void staleCendenceBatteryIsNotReportedAsCritical() {
        FlightReadinessModel.Decision decision = FlightReadinessModel.evaluate(healthyInput(true)
                .withEvidence(FlightReadinessModel.EvidenceState.LIVE,
                        FlightReadinessModel.EvidenceState.LIVE,
                        FlightReadinessModel.EvidenceState.LIVE,
                        FlightReadinessModel.EvidenceState.LIVE,
                        FlightReadinessModel.EvidenceState.STALE,
                        FlightReadinessModel.EvidenceState.LIVE,
                        FlightReadinessModel.EvidenceState.LIVE));
        equal(FlightReadinessModel.RowState.STALE, row(decision, "Cendence battery").getState(),
                "stale Cendence charge remains visible as stale");
        equal("Cendence battery status is stale or unavailable.",
                decision.getPrimaryReason().getMessage(),
                "missing Cendence charge is not presented as critical charge");
    }

    private static void homeEvidenceIsSharedAndNeverImplicitlyHealthy() {
        FlightReadinessModel.Decision waiting = FlightReadinessModel.evaluate(healthyInput(true)
                .withDjiHomeEvidence(FlightReadinessModel.EvidenceState.WAITING_FOR_SAMPLE));
        equal(FlightReadinessModel.RowState.WAITING_FOR_SAMPLE, row(waiting, "DJI Home").getState(),
                "mission opening must distinguish checking Home from absence");
        equal(FlightReadinessModel.State.BLOCKED, waiting.getState(),
                "checking DJI Home cannot pass mission readiness");

        FlightReadinessModel.Decision stale = FlightReadinessModel.evaluate(healthyInput(true)
                .withDjiHomeEvidence(FlightReadinessModel.EvidenceState.STALE));
        equal(FlightReadinessModel.RowState.STALE, row(stale, "DJI Home").getState(),
                "stale Home evidence remains visible to the operator");
    }

    private static FlightReadinessModel.Row row(FlightReadinessModel.Decision decision, String label) {
        for (FlightReadinessModel.Row row : decision.getRows()) {
            if (label.equals(row.getLabel())) return row;
        }
        throw new AssertionError("missing readiness row " + label);
    }

    private static void landingAdvisoryUsesConfiguredThresholdAndLowestPack() {
        require(!LowBatteryAdvisory.isValidThreshold(14), "landing threshold below critical is rejected");
        require(LowBatteryAdvisory.isValidThreshold(15), "critical threshold is allowed");
        equal(LowBatteryAdvisory.Severity.CLEAR,
                LowBatteryAdvisory.evaluate(31, true, 55, true, 30).getSeverity(),
                "battery above threshold is clear");
        LowBatteryAdvisory.Snapshot soon = LowBatteryAdvisory.evaluate(55, true, 24, true, 30);
        equal(LowBatteryAdvisory.Severity.LAND_SOON, soon.getSeverity(), "low pack needs land-soon advisory");
        require(soon.getLimitingBatteryIndex() == 1 && soon.getLimitingPercent() == 24,
                "lower B2 pack is identified");
        equal(LowBatteryAdvisory.Severity.LAND_NOW,
                LowBatteryAdvisory.evaluate(15, true, 40, true, 30).getSeverity(),
                "critical pack uses land-now advisory");
        equal(LowBatteryAdvisory.Severity.UNAVAILABLE,
                LowBatteryAdvisory.evaluate(60, false, 60, true, 30).getSeverity(),
                "stale battery evidence is never healthy");
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
