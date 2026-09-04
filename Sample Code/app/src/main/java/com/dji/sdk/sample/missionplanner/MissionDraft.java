package com.dji.sdk.sample.missionplanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable local-only mission draft. It contains no DJI connection or upload state. */
public final class MissionDraft {
    public static final int FORMAT_VERSION = 1;
    private final String name;
    private final float defaultAltitudeMeters;
    private final float baseSpeedMetersPerSecond;
    private final MissionFinishAction finishAction;
    private final List<MissionWaypoint> waypoints;
    private final Double planningReferenceLatitude;
    private final Double planningReferenceLongitude;

    public MissionDraft(String name, float defaultAltitudeMeters, float baseSpeedMetersPerSecond,
                        MissionFinishAction finishAction, List<MissionWaypoint> waypoints,
                        Double planningReferenceLatitude, Double planningReferenceLongitude) {
        this.name = name == null ? "" : name.trim();
        this.defaultAltitudeMeters = defaultAltitudeMeters;
        this.baseSpeedMetersPerSecond = baseSpeedMetersPerSecond;
        this.finishAction = finishAction == null ? MissionFinishAction.HOVER_AT_FINAL : finishAction;
        this.waypoints = Collections.unmodifiableList(new ArrayList<>(waypoints == null
                ? Collections.emptyList() : waypoints));
        this.planningReferenceLatitude = planningReferenceLatitude;
        this.planningReferenceLongitude = planningReferenceLongitude;
    }

    public static MissionDraft empty() {
        return new MissionDraft("Untitled mission", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                Collections.emptyList(), null, null);
    }

    public String getName() { return name; }
    public float getDefaultAltitudeMeters() { return defaultAltitudeMeters; }
    public float getBaseSpeedMetersPerSecond() { return baseSpeedMetersPerSecond; }
    public MissionFinishAction getFinishAction() { return finishAction; }
    public List<MissionWaypoint> getWaypoints() { return waypoints; }
    public Double getPlanningReferenceLatitude() { return planningReferenceLatitude; }
    public Double getPlanningReferenceLongitude() { return planningReferenceLongitude; }

    public MissionDraft withWaypoints(List<MissionWaypoint> nextWaypoints) {
        return new MissionDraft(name, defaultAltitudeMeters, baseSpeedMetersPerSecond, finishAction,
                nextWaypoints, planningReferenceLatitude, planningReferenceLongitude);
    }


    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MissionDraft)) return false;
        MissionDraft that = (MissionDraft) other;
        return Float.compare(defaultAltitudeMeters, that.defaultAltitudeMeters) == 0
                && Float.compare(baseSpeedMetersPerSecond, that.baseSpeedMetersPerSecond) == 0
                && Objects.equals(name, that.name)
                && finishAction == that.finishAction
                && Objects.equals(waypoints, that.waypoints)
                && Objects.equals(planningReferenceLatitude, that.planningReferenceLatitude)
                && Objects.equals(planningReferenceLongitude, that.planningReferenceLongitude);
    }

    @Override public int hashCode() {
        return Objects.hash(name, defaultAltitudeMeters, baseSpeedMetersPerSecond, finishAction,
                waypoints, planningReferenceLatitude, planningReferenceLongitude);
    }
}
