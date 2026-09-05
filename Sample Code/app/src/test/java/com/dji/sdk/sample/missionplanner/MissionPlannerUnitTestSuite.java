package com.dji.sdk.sample.missionplanner;

import java.util.Arrays;
import java.util.List;

import com.dji.sdk.sample.operate.FlightReadinessModel;
import com.dji.sdk.sample.operate.SessionTimeline;

/** Dependency-free JVM coverage for the mission draft contract. */
public final class MissionPlannerUnitTestSuite {
    public static void main(String[] args) {
        rejectsMissingWaypoints();
        resolvesWaypointAltitudeOverrides();
        rejectsInvalidCoordinatesAndSpeed();
        preservesExplicitFinishAction();
        editsOrderedRouteWithoutChangingDefaults();
        reordersSelectedWaypointWithoutChangingIt();
        distinguishesLocalAndRemoteMissionEvidence();
        invalidatesUploadEvidenceWhenAircraftSessionChanges();
        calculatesRouteStatisticsFromLocalDraft();
        detectsCircleAndPolygonBoundaryWarnings();
        ignoresDisjointCollinearBoundarySegments();
        rejectsInvalidPlanningBoundaryGeometry();
        preservesBoundariesDuringProfileEdits();
        rejectsDegenerateBoundaryPolygons();
        boundsSessionTimelineWithoutBlockingProducers();
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

    private static void reordersSelectedWaypointWithoutChangingIt() {
        MissionWaypoint first = new MissionWaypoint(38.7, -9.1, null);
        MissionWaypoint second = new MissionWaypoint(38.71, -9.11, 35f);
        MissionWaypoint third = new MissionWaypoint(38.72, -9.12, 40f);
        MissionRouteEditor editor = new MissionRouteEditor(new MissionDraft("Order", 30f, 3f,
                MissionFinishAction.HOVER_AT_FINAL, Arrays.asList(first, second, third), null, null));
        require(editor.moveEarlier(1), "middle waypoint must move earlier");
        require(editor.getDraft().getWaypoints().get(0).equals(second),
                "moving earlier must preserve the selected waypoint");
        require(editor.moveLater(0), "first waypoint must move later");
        require(editor.getDraft().getWaypoints().get(1).equals(second),
                "moving later must preserve the selected waypoint");
        require(!editor.moveEarlier(0), "first waypoint cannot move earlier");
        require(!editor.moveLater(2), "last waypoint cannot move later");
    }

    private static void distinguishesLocalAndRemoteMissionEvidence() {
        MissionSyncState.Snapshot local = MissionSyncState.resolve(false, false, false, "READY_TO_UPLOAD");
        require(local.getLocal() == MissionSyncState.Local.LOCAL_DRAFT,
                "a local draft must remain local evidence");
        require(local.getRemote() == MissionSyncState.Remote.REMOTE_UNKNOWN,
                "idle state cannot prove the aircraft mission");
        MissionSyncState.Snapshot uploaded = MissionSyncState.resolve(true, true, false, "READY_TO_EXECUTE");
        require(uploaded.getLocal() == MissionSyncState.Local.UPLOADED_THIS_SESSION,
                "successful upload is limited to this app session");
        require(uploaded.getRemote() == MissionSyncState.Remote.REMOTE_UNKNOWN,
                "upload does not become remote readback");
        MissionSyncState.Snapshot changed = MissionSyncState.resolve(true, true, true, "READY_TO_EXECUTE");
        require(changed.getLocal() == MissionSyncState.Local.LOCAL_DRAFT,
                "local edits must clear load/upload evidence");
        MissionSyncState.Snapshot executing = MissionSyncState.resolve(false, false, false, "EXECUTING");
        require(executing.getRemote() == MissionSyncState.Remote.EXECUTING,
                "DJI execution state is remote evidence");
    }

    private static void invalidatesUploadEvidenceWhenAircraftSessionChanges() {
        MissionSessionEvidence evidence = new MissionSessionEvidence();
        evidence.recordLoaded(7L);
        require(evidence.recordUploaded(7L), "an upload after Load belongs to the current aircraft session");
        require(evidence.isUploadedFor(7L), "current-session upload evidence must be visible");
        require(evidence.invalidateFor(8L), "a reconnect must invalidate local mission evidence");
        require(!evidence.isLoadedFor(8L) && !evidence.isUploadedFor(8L),
                "a reconnect must require a fresh Load and Upload");
        require(!evidence.recordUploaded(7L), "a late upload completion from the old session is rejected");
    }

    private static void calculatesRouteStatisticsFromLocalDraft() {
        MissionDraft draft = new MissionDraft("Stats", 30f, 5f,
                MissionFinishAction.HOVER_AT_FINAL, Arrays.asList(
                new MissionWaypoint(0d, 0d, null),
                new MissionWaypoint(0d, 0.001d, 40f),
                new MissionWaypoint(0d, 0.002d, 20f)), null, null);
        RouteStatistics.Snapshot stats = RouteStatistics.calculate(draft);
        require(stats.isAvailable(), "valid route must produce local statistics");
        require(stats.getWaypointCount() == 3, "waypoint count must match draft");
        require(stats.getDistanceMeters() > 220d && stats.getDistanceMeters() < 224d,
                "haversine distance must be plausible");
        require(stats.getMinimumAltitudeMeters() == 20f && stats.getMaximumAltitudeMeters() == 40f,
                "resolved altitude range must use overrides");
        require(stats.getClimbMeters() == 10f && stats.getDescentMeters() == 20f,
                "climb/descent must follow ordered altitude changes");
        require(stats.getEstimatedDurationSeconds() > 44d && stats.getEstimatedDurationSeconds() < 45d,
                "duration must use route distance and base speed");
        require(!RouteStatistics.calculate(MissionDraft.empty()).isAvailable(),
                "incomplete drafts must not fabricate statistics");
    }

    private static void detectsCircleAndPolygonBoundaryWarnings() {
        MissionDraft crossing = new MissionDraft("Boundary", 30f, 3f,
                MissionFinishAction.HOVER_AT_FINAL, Arrays.asList(
                new MissionWaypoint(0d, -0.002d, null), new MissionWaypoint(0d, 0.002d, null)), null, null);
        PlanningBoundary circle = PlanningBoundary.exclusionCircle("Pad", 0d, 0d, 100d);
        require(BoundaryEvaluator.evaluate(crossing, Arrays.asList(circle)).getWarningCount() == 1,
                "route segment crossing an exclusion circle must warn");
        PlanningBoundary polygon = PlanningBoundary.inclusionPolygon("Area", Arrays.asList(
                new PlanningBoundary.Point(-0.001d, -0.001d), new PlanningBoundary.Point(-0.001d, 0.001d),
                new PlanningBoundary.Point(0.001d, 0.001d), new PlanningBoundary.Point(0.001d, -0.001d)));
        MissionDraft outside = new MissionDraft("Outside", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(0d, 0d, null), new MissionWaypoint(0d, 0.003d, null)), null, null);
        require(BoundaryEvaluator.evaluate(outside, Arrays.asList(polygon)).getWarningCount() == 1,
                "leaving an inclusion polygon must warn");
        MissionDraft throughPolygon = new MissionDraft("Cross", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(0d, -0.002d, null), new MissionWaypoint(0d, 0.002d, null)), null, null);
        PlanningBoundary exclusionPolygon = PlanningBoundary.exclusionPolygon("No go", Arrays.asList(
                new PlanningBoundary.Point(-0.001d, -0.001d), new PlanningBoundary.Point(-0.001d, 0.001d),
                new PlanningBoundary.Point(0.001d, 0.001d), new PlanningBoundary.Point(0.001d, -0.001d)));
        require(BoundaryEvaluator.evaluate(throughPolygon, Arrays.asList(exclusionPolygon)).getWarningCount() == 1,
                "route segment crossing an exclusion polygon must warn");
    }

    private static void ignoresDisjointCollinearBoundarySegments() {
        PlanningBoundary exclusion = PlanningBoundary.exclusionPolygon("No go", Arrays.asList(
                new PlanningBoundary.Point(-0.001d, -0.001d), new PlanningBoundary.Point(-0.001d, 0.001d),
                new PlanningBoundary.Point(0.001d, 0.001d), new PlanningBoundary.Point(0.001d, -0.001d)));
        MissionDraft disjoint = new MissionDraft("Disjoint", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Arrays.asList(new MissionWaypoint(0.001d, 0.002d, null), new MissionWaypoint(0.001d, 0.003d, null)),
                null, null);
        BoundaryEvaluator.Result result = BoundaryEvaluator.evaluate(disjoint, Arrays.asList(exclusion));
        require(result.getWarningCount() == 0, "a disjoint collinear segment must not warn");
    }

    private static void boundsSessionTimelineWithoutBlockingProducers() {
        SessionTimeline timeline = new SessionTimeline(2);
        timeline.record(1L, SessionTimeline.TimeSource.OPERATOR_ACTION, "LOAD", "Pressed Load");
        timeline.record(2L, SessionTimeline.TimeSource.DJI_CALLBACK, "MISSION", "Ready");
        timeline.record(3L, SessionTimeline.TimeSource.ANDROID_OBSERVATION, "LINK", "Connected");
        require(timeline.snapshot().size() == 2 && timeline.getDroppedCount() == 1,
                "timeline must bound old entries rather than blocking producers");
        require(timeline.snapshot().get(0).getTimeSource() == SessionTimeline.TimeSource.DJI_CALLBACK,
                "timeline must retain explicit time source");
    }

    private static void rejectsInvalidPlanningBoundaryGeometry() {
        boolean zeroRadiusRejected = false;
        try { PlanningBoundary.exclusionCircle("Bad", 0d, 0d, 0d); }
        catch (IllegalArgumentException expected) { zeroRadiusRejected = true; }
        require(zeroRadiusRejected, "a local circle must have a positive radius");

        boolean coordinateRejected = false;
        try { PlanningBoundary.exclusionCircle("Bad", 91d, 0d, 1d); }
        catch (IllegalArgumentException expected) { coordinateRejected = true; }
        require(coordinateRejected, "local boundary coordinates must be valid WGS84 coordinates");
    }

    private static void preservesBoundariesDuringProfileEdits() {
        PlanningBoundary first = PlanningBoundary.exclusionCircle("First", 0d, 0d, 10d);
        PlanningBoundary second = PlanningBoundary.exclusionCircle("Second", 0d, 0.001d, 10d);
        PlanningBoundary third = PlanningBoundary.exclusionCircle("Third", 0d, 0.002d, 10d);
        PlanningBoundary replacement = PlanningBoundary.exclusionCircle("Replacement", 0d, 0.003d, 10d);
        PlanningBoundaryProfile profile = new PlanningBoundaryProfile("Profile", Arrays.asList(first, second));
        PlanningBoundaryProfile appended = profile.withBoundaryAppended(third);
        require(appended.getBoundaries().size() == 3, "append must retain existing boundaries");
        require(appended.getBoundaries().get(0) == first && appended.getBoundaries().get(1) == second,
                "append must preserve previous boundary order");
        PlanningBoundaryProfile replaced = appended.withBoundaryReplaced(1, replacement);
        require(replaced.getBoundaries().get(0) == first && replaced.getBoundaries().get(1) == replacement
                        && replaced.getBoundaries().get(2) == third,
                "replace must change only the selected boundary");
        require("First".equals(replaced.getBoundaries().get(0).getName())
                        && "Replacement".equals(replaced.getBoundaries().get(1).getName())
                        && "Third".equals(replaced.getBoundaries().get(2).getName()),
                "individually named boundaries must retain their own names after an edit");
        PlanningBoundaryProfile deleted = replaced.withBoundaryDeleted(1);
        require(deleted.getBoundaries().size() == 2 && deleted.getBoundaries().get(0) == first
                        && deleted.getBoundaries().get(1) == third,
                "delete must retain unrelated boundaries");
        boolean unavailableIndexRejected = false;
        try { deleted.withBoundaryDeleted(2); }
        catch (IllegalArgumentException expected) { unavailableIndexRejected = true; }
        require(unavailableIndexRejected, "an unavailable boundary index must be rejected");
    }

    private static void rejectsDegenerateBoundaryPolygons() {
        requireBoundaryRejected(Arrays.asList(new PlanningBoundary.Point(0d, 0d),
                new PlanningBoundary.Point(0d, 0d), new PlanningBoundary.Point(0.001d, 0.001d)),
                "duplicate polygon vertices must be rejected");
        requireBoundaryRejected(Arrays.asList(new PlanningBoundary.Point(0d, 0d),
                new PlanningBoundary.Point(0d, 0.001d), new PlanningBoundary.Point(0d, 0.002d)),
                "zero-area polygon must be rejected");
        requireBoundaryRejected(Arrays.asList(new PlanningBoundary.Point(0d, 0d),
                new PlanningBoundary.Point(0.001d, 0.001d), new PlanningBoundary.Point(0d, 0.001d),
                new PlanningBoundary.Point(0.001d, 0d)), "self-crossing polygon must be rejected");
        PlanningBoundary concave = PlanningBoundary.inclusionPolygon("Concave", Arrays.asList(
                new PlanningBoundary.Point(38.700d, -9.100d), new PlanningBoundary.Point(38.700d, -9.098d),
                new PlanningBoundary.Point(38.701d, -9.099d), new PlanningBoundary.Point(38.702d, -9.098d),
                new PlanningBoundary.Point(38.702d, -9.100d)));
        require(concave.getPoints().size() == 5, "a valid concave polygon must remain available for persistence");
    }

    private static void requireBoundaryRejected(List<PlanningBoundary.Point> points, String message) {
        boolean rejected = false;
        try { PlanningBoundary.inclusionPolygon("Bad", points); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, message);
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
