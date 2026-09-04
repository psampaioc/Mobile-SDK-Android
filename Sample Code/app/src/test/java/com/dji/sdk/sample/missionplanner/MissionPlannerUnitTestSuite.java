package com.dji.sdk.sample.missionplanner;

import java.util.Arrays;
import java.util.List;

import com.dji.sdk.sample.operate.FlightReadinessModel;

/** Dependency-free JVM coverage for the mission draft contract. */
public final class MissionPlannerUnitTestSuite {
    public static void main(String[] args) {
        rejectsMissingWaypoints();
        resolvesWaypointAltitudeOverrides();
        rejectsInvalidCoordinatesAndSpeed();
        preservesExplicitFinishAction();
        editsOrderedRouteWithoutChangingDefaults();
        detectsLoadedDraftChanges();
        rejectsUnsafeConnectedState();
        rejectsMissionStartUntilCurrentSessionReviewIsComplete();
        System.out.println("Mission planner unit tests passed");
    }

    private static void rejectsMissingWaypoints() {
        require(!MissionDraftValidator.validate(MissionDraft.empty()).isEmpty(), "empty draft must fail");
    }

    private static void resolvesWaypointAltitudeOverrides() {
        MissionWaypoint inherited = new MissionWaypoint(38.7, -9.1, null);
        MissionWaypoint overridden = new MissionWaypoint(38.71, -9.11, 42f);
        MissionDraft draft = new MissionDraft("Test", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(inherited, overridden), null, null);
        require(MissionDraftValidator.validate(draft).isEmpty(), "valid draft must pass");
        require(MissionDraftValidator.resolvedAltitude(draft, inherited) == 30f, "default altitude expected");
        require(MissionDraftValidator.resolvedAltitude(draft, overridden) == 42f, "override expected");
    }

    private static void rejectsInvalidCoordinatesAndSpeed() {
        MissionDraft draft = new MissionDraft("Bad", 30f, 16f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(91d, 0d, null), new MissionWaypoint(38.7, -9.1, -1f)), null, null);
        List<String> errors = MissionDraftValidator.validate(draft);
        require(errors.size() == 3, "speed, coordinate, and altitude errors expected");
    }

    private static void preservesExplicitFinishAction() {
        MissionDraft draft = new MissionDraft("Land", 30f, 3f, MissionFinishAction.LAND_AT_FINAL,
                Arrays.asList(new MissionWaypoint(38.7, -9.1, 10f), new MissionWaypoint(38.71, -9.11, 0f)), null, null);
        require(draft.getFinishAction() == MissionFinishAction.LAND_AT_FINAL, "finish action must not be inferred");
    }

    private static void editsOrderedRouteWithoutChangingDefaults() {
        MissionDraft initial = new MissionDraft("Route", 30f, 3f,
                MissionFinishAction.HOVER_AT_FINAL, Arrays.asList(
                new MissionWaypoint(38.7, -9.1, null),
                new MissionWaypoint(38.71, -9.11, 35f)), null, null);
        MissionRouteEditor editor = new MissionRouteEditor(initial);
        editor.insertAfter(0, new MissionWaypoint(38.705, -9.105, null));
        editor.move(2, new MissionWaypoint(38.72, -9.12, 40f));
        MissionDraft changed = editor.getDraft();
        require(changed.getWaypoints().size() == 3, "insert must preserve an ordered route");
        require(changed.getWaypoints().get(1).getLatitude() == 38.705, "insert position must be respected");
        require(changed.getWaypoints().get(2).getAltitudeOverrideMeters() == 40f, "move must replace only the selected waypoint");
        require(changed.getDefaultAltitudeMeters() == 30f, "editing geometry must not change defaults");
        require(editor.delete(1), "selected waypoint must be deletable");
        require(editor.getDraft().getWaypoints().size() == 2, "delete must shrink route");
    }

    private static void rejectsUnsafeConnectedState() {
        MissionDraft draft = new MissionDraft("Ready", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(38.7, -9.1, null), new MissionWaypoint(38.71, -9.11, null)), null, null);
        List<String> errors = MissionPreflight.validate(draft, false, false, true);
        require(errors.size() == 3, "missing state, missing home, and RTH must block preflight");
        require(MissionPreflight.validate(draft, true, true, false).isEmpty(), "ready state must pass preflight");
    }

    private static void detectsLoadedDraftChanges() {
        MissionDraft loaded = new MissionDraft("Ready", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(38.7, -9.1, null), new MissionWaypoint(38.71, -9.11, null)), null, null);
        MissionDraft same = new MissionDraft("Ready", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(38.7, -9.1, null), new MissionWaypoint(38.71, -9.11, null)), null, null);
        MissionDraft changed = new MissionDraft("Ready", 30f, 4f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(38.7, -9.1, null), new MissionWaypoint(38.71, -9.11, null)), null, null);
        require(loaded.equals(same), "equivalent immutable drafts must compare equal");
        require(!loaded.equals(changed), "a changed draft must require another load");
    }

    private static void rejectsMissionStartUntilCurrentSessionReviewIsComplete() {
        MissionDraft draft = new MissionDraft("Ready", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(38.7, -9.1, null), new MissionWaypoint(38.71, -9.11, null)), null, null);
        FlightReadinessModel.Decision reviewRequired = FlightReadinessModel.evaluate(
                new FlightReadinessModel.Input(true, true, true, true, true, false,
                        true, true, true, false, false, 80, 80, 80, false));
        require(!MissionPreflight.validateStart(draft, true, true, false, reviewRequired).isEmpty(),
                "Mission Start must require the current-session physical review");
        FlightReadinessModel.Decision advisoryOnly = FlightReadinessModel.evaluate(
                new FlightReadinessModel.Input(true, true, true, true, true, false,
                        true, false, false, false, false, 80, 80, 80, true));
        require(MissionPreflight.validateStart(draft, true, true, false, advisoryOnly).isEmpty(),
                "RTK advisory must not block an ordinary GPS mission");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
